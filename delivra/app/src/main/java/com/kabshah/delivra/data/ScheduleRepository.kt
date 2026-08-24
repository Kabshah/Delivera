package com.kabshah.delivra.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kabshah.delivra.scheduling.AlarmScheduler
import com.kabshah.delivra.scheduling.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth exposed to ViewModels (§8 design).
 * All reads/writes to Room go through here — no direct DAO access from UI layer.
 */
@Singleton
class ScheduleRepository @Inject constructor(
    private val dao: ScheduledMessageDao,
    private val alarmScheduler: AlarmScheduler,
    private val failureNotifier: com.kabshah.delivra.diagnostics.FailureNotifier,
    @ApplicationContext private val context: Context
) {

    // ── Reactive UI data ────────────────────────────────────────────────────
    fun observeAll(): Flow<List<ScheduledMessage>> = dao.observeAll()
    fun observePendingCount(): Flow<Int> = dao.observePendingCount()
    fun observeSentCount(): Flow<Int> = dao.observeSentCount()
    fun observeFailedCount(): Flow<Int> = dao.observeFailedCount()
    fun observeNeedsReviewCount(): Flow<Int> = dao.observeNeedsReviewCount()

    // ── Schedule a new message ──────────────────────────────────────────────
    suspend fun scheduleMessage(message: ScheduledMessage) {
        dao.insert(message)
        alarmScheduler.scheduleExact(message)
        Log.d(TAG, "Scheduled message id=${message.id} at ${message.scheduledLocalDateTime}")
    }

    // ── Cancel a PENDING message (§4.7) ────────────────────────────────────
    suspend fun cancelPendingMessage(id: String) {
        val msg = dao.getById(id) ?: return
        if (msg.status != MessageStatus.PENDING) return
        alarmScheduler.cancel(id)
        // Clean up app-owned voice note file if any
        msg.voiceNotePath?.let { path ->
            try { java.io.File(path).delete() } catch (e: Exception) { /* best effort */ }
        }
        dao.deleteById(id)
        Log.d(TAG, "Cancelled and deleted PENDING message id=$id")
    }

    // ── Status transitions ──────────────────────────────────────────────────
    suspend fun updateStatus(id: String, newStatus: MessageStatus, reason: String? = null) {
        val msg = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        val terminal = when (newStatus) {
            MessageStatus.SENT_CONFIRMED, MessageStatus.FINAL_FAILURE, MessageStatus.NEEDS_REVIEW -> now
            else -> msg.terminalAtEpochMs
        }
        dao.update(
            msg.copy(
                status = newStatus,
                lastErrorReason = reason ?: msg.lastErrorReason,
                terminalAtEpochMs = terminal
            )
        )
    }

    /** Atomically claim a PENDING row. Returns true if claim succeeded. */
    suspend fun atomicClaim(id: String): Boolean {
        val updated = dao.atomicClaim(id, System.currentTimeMillis())
        return updated > 0
    }

    /**
     * Flip a RETRYABLE_FAILURE row back to PENDING so a scheduled retry can go
     * through the normal claim→send flow (§6.2). Returns false if the row moved
     * on meanwhile (sent / cancelled / promoted) — the retry is then dropped.
     */
    suspend fun markRetryableAsPending(id: String): Boolean {
        val updated = dao.retryToPending(id)
        return updated > 0
    }

    // ── Reconciliation (§6.7) ──────────────────────────────────────────────
    suspend fun reconcile() {
        val now = System.currentTimeMillis()
        val stalenessThreshold = now - Constants.CLAIM_STALENESS_MS
        val r1 = dao.revertStalePreconditionRows(stalenessThreshold)
        // Capture stale SENDING rows BEFORE marking, so we can notify each one
        // that just became ambiguous (§4.9).
        val newlyAmbiguous = dao.getInFlight().filter {
            it.status == MessageStatus.SENDING && it.attemptStartedAtEpochMs != null &&
                    it.attemptStartedAtEpochMs <= stalenessThreshold
        }
        val r2 = dao.markStaleSendingAsNeedsReview(stalenessThreshold, now)
        // Revive stranded RETRYABLE_FAILURE rows (process died before its
        // RetryWorker fired, or row predates the retry mechanism). Cutoff is a
        // full retry cycle + margin, so rows with an imminent scheduled retry
        // are left alone. attemptCount survives — the 3-attempt cap still holds.
        val reviveCutoff = now - Constants.RETRY_BACKOFF_MS.sum() - Constants.CLAIM_STALENESS_MS
        val r3 = dao.reviveStaleRetryableRows(reviveCutoff)
        if (r1 > 0 || r2 > 0 || r3 > 0) {
            Log.d(TAG, "Reconcile: reverted=$r1 stale pre-send, marked=$r2 NEEDS_REVIEW, revived=$r3 stale retries")
        }
        newlyAmbiguous.forEach {
            failureNotifier.notifyNeedsReview(it.contactName, it.id)
        }
    }

    // ── Alarm re-registration after reboot (§2.2) ──────────────────────────
    suspend fun reRegisterAllAlarms() {
        val pending = dao.getAllPending()
        pending.forEach { msg ->
            alarmScheduler.scheduleExact(msg)
        }
        Log.d(TAG, "Re-registered ${pending.size} alarms after boot")
    }

    // ── Cleanup (§4.8) ────────────────────────────────────────────────────
    suspend fun runCleanup() {
        val now = System.currentTimeMillis()
        val sent12h = now - Constants.SENT_CLEANUP_DELAY_MS
        val failed24h = now - Constants.FAILED_CLEANUP_DELAY_MS

        val sentRows = dao.getSentForCleanup(sent12h)
        val failedRows = dao.getFailedForCleanup(failed24h)

        (sentRows + failedRows).forEach { msg ->
            msg.voiceNotePath?.let { path ->
                try { java.io.File(path).delete() } catch (e: Exception) { /* best effort */ }
            }
            dao.deleteById(msg.id)
        }
        if (sentRows.isNotEmpty() || failedRows.isNotEmpty()) {
            Log.d(TAG, "Cleanup: removed ${sentRows.size} sent + ${failedRows.size} failed rows")
        }

        // Orphaned staged attachments (cache/attach_*): normally deleted right
        // after each send attempt by NodeBridge, but a crash mid-copy can leak
        // one. Sweep any older than the failed-cleanup window.
        val staleCutoff = failed24h
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("attach_") && f.lastModified() < staleCutoff) {
                try { f.delete() } catch (_: Exception) { /* best effort */ }
            }
        }
    }

    // ── Needs Review resolution (§6.2) ─────────────────────────────────────
    /**
     * User checked WhatsApp: the message went through (or they don't care) —
     * delete the row and any app-owned voice file immediately.
     */
    suspend fun resolveNeedsReview(id: String) {
        val msg = dao.getById(id) ?: return
        if (msg.status != MessageStatus.NEEDS_REVIEW) return
        msg.voiceNotePath?.let { path ->
            try { java.io.File(path).delete() } catch (_: Exception) { /* best effort */ }
        }
        dao.deleteById(id)
        Log.d(TAG, "Needs Review id=$id resolved by user — deleted")
    }

    /**
     * User checked WhatsApp: the message did NOT go through — resend now with
     * a fresh attempt budget, due immediately.
     */
    suspend fun resendNeedsReview(id: String): Boolean {
        val msg = dao.getById(id) ?: return false
        if (msg.status != MessageStatus.NEEDS_REVIEW) return false
        dao.update(
            msg.copy(
                status = MessageStatus.PENDING,
                attemptCount = 0,
                attemptStartedAtEpochMs = null,
                lastErrorReason = null,
                terminalAtEpochMs = null,
                resolvedEpochMs = System.currentTimeMillis()
            )
        )
        Log.d(TAG, "Needs Review id=$id → manual resend requested")
        com.kabshah.delivra.service.SchedulerService.dispatchDueMessages(context, listOf(id))
        return true
    }

    // ── Missed schedule handling (§6.4) ────────────────────────────────────
    suspend fun getDueMessagesAndHandleMissed(): List<ScheduledMessage> {
        val now = System.currentTimeMillis()
        val due = dao.getDueMessages(now)
        val (sendable, missed) = due.partition {
            (now - it.resolvedEpochMs) <= Constants.MISSED_SCHEDULE_GRACE_MS
        }
        missed.forEach { msg ->
            dao.update(
                msg.copy(
                    status = MessageStatus.FINAL_FAILURE,
                    lastErrorReason = "missed_window_exceeded",
                    terminalAtEpochMs = now
                )
            )
            failureNotifier.notifyFailed(msg.contactName, "missed_window_exceeded", msg.id)
            Log.d(TAG, "Message id=${msg.id} missed grace window — marked FINAL_FAILURE")
        }
        return sendable
    }

    // ── Attachment re-validation at send time (§2.7 point 5) ───────────────
    // Supports both legacy content:// URIs (old rows) and attach-time staged
    // local files (current flow).
    fun validateAttachmentReadable(uriString: String): Boolean {
        return try {
            if (uriString.startsWith("content://")) {
                val uri = Uri.parse(uriString)
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            } else {
                val f = java.io.File(uriString.removePrefix("file://"))
                f.exists() && f.length() > 0
            }
        } catch (e: FileNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    suspend fun getById(id: String): ScheduledMessage? = dao.getById(id)

    companion object {
        private const val TAG = "ScheduleRepository"
    }
}
