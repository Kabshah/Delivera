package com.kabshah.delivra.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {

    // ── Read ──────────────────────────────────────────────────────────────────
    @Query("SELECT * FROM scheduled_messages ORDER BY resolvedEpochMs ASC")
    fun observeAll(): Flow<List<ScheduledMessage>>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun getById(id: String): ScheduledMessage?

    @Query("SELECT * FROM scheduled_messages WHERE status = 'PENDING' ORDER BY resolvedEpochMs ASC")
    suspend fun getAllPending(): List<ScheduledMessage>

    @Query("SELECT * FROM scheduled_messages WHERE status IN ('CLAIMED','CONNECTING','SENDING')")
    suspend fun getInFlight(): List<ScheduledMessage>

    /** Due-now query: PENDING rows whose resolvedEpochMs is in the past (with optional grace). */
    @Query(
        "SELECT * FROM scheduled_messages WHERE status = 'PENDING' AND resolvedEpochMs <= :nowMs ORDER BY resolvedEpochMs ASC"
    )
    suspend fun getDueMessages(nowMs: Long): List<ScheduledMessage>

    // ── Write ─────────────────────────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ScheduledMessage)

    @Update
    suspend fun update(message: ScheduledMessage)

    @Delete
    suspend fun delete(message: ScheduledMessage)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    // ── Atomic claim (§6.2 idempotency guard) ─────────────────────────────────
    /**
     * Atomically transitions a single PENDING row to CLAIMED in one SQL statement.
     * Returns the number of rows updated (0 = already claimed by someone else).
     * This prevents the AlarmManager + WorkManager double-dispatch race.
     * Each claim IS one send attempt — increment the counter here so retry
     * caps (§6.2) actually advance.
     */
    @Query(
        "UPDATE scheduled_messages SET status = 'CLAIMED', " +
                "attemptStartedAtEpochMs = :nowMs, attemptCount = attemptCount + 1 " +
                "WHERE id = :id AND status = 'PENDING'"
    )
    suspend fun atomicClaim(id: String, nowMs: Long): Int

    /**
     * A scheduled retry (§6.2 backoff) re-enters the normal dispatch flow by
     * flipping RETRYABLE_FAILURE back to PENDING. Guarded by status so a row
     * that got resolved another way (sent/cancelled/reviewed) isn't touched.
     */
    @Query(
        "UPDATE scheduled_messages SET status = 'PENDING' " +
                "WHERE id = :id AND status = 'RETRYABLE_FAILURE'"
    )
    suspend fun retryToPending(id: String): Int

    // ── Reconciliation queries (§6.7) ─────────────────────────────────────────
    /**
     * Rows stuck in CLAIMED/CONNECTING past the staleness threshold → safe to revert to PENDING.
     * Nothing was actually sent yet.
     */
    @Query(
        "UPDATE scheduled_messages SET status = 'PENDING', attemptStartedAtEpochMs = NULL " +
                "WHERE status IN ('CLAIMED','CONNECTING') AND attemptStartedAtEpochMs <= :stalenessThresholdMs"
    )
    suspend fun revertStalePreconditionRows(stalenessThresholdMs: Long): Int

    /**
     * Revive RETRYABLE_FAILURE rows whose last attempt is older than the cutoff
     * (i.e. no live RetryWorker is about to handle them — e.g. rows stranded by
     * a process death before the retry could fire, or pre-dating the retry
     * mechanism entirely). attemptCount is preserved, so the §6.2 cap still
     * applies on their next dispatch.
     */
    @Query(
        "UPDATE scheduled_messages SET status = 'PENDING' " +
                "WHERE status = 'RETRYABLE_FAILURE' AND attemptStartedAtEpochMs <= :cutoffMs"
    )
    suspend fun reviveStaleRetryableRows(cutoffMs: Long): Int

    /**
     * Rows stuck in SENDING past the staleness threshold — ambiguous outcome.
     * Transition to NEEDS_REVIEW (§6.2); do NOT auto-retry.
     */
    @Query(
        "UPDATE scheduled_messages SET status = 'NEEDS_REVIEW', terminalAtEpochMs = :nowMs, " +
                "lastErrorReason = 'ambiguous_send_interrupted' " +
                "WHERE status = 'SENDING' AND attemptStartedAtEpochMs <= :stalenessThresholdMs"
    )
    suspend fun markStaleSendingAsNeedsReview(stalenessThresholdMs: Long, nowMs: Long): Int

    // ── Cleanup queries (§4.8) ─────────────────────────────────────────────────
    @Query(
        "SELECT * FROM scheduled_messages WHERE status = 'SENT_CONFIRMED' AND terminalAtEpochMs <= :cutoffMs"
    )
    suspend fun getSentForCleanup(cutoffMs: Long): List<ScheduledMessage>

    @Query(
        "SELECT * FROM scheduled_messages WHERE status IN ('FINAL_FAILURE','NEEDS_REVIEW') AND terminalAtEpochMs <= :cutoffMs"
    )
    suspend fun getFailedForCleanup(cutoffMs: Long): List<ScheduledMessage>

    // ── Stats (for Home screen stats strip) ──────────────────────────────────
    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE status = 'SENT_CONFIRMED'")
    fun observeSentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE status = 'FINAL_FAILURE'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE status = 'NEEDS_REVIEW'")
    fun observeNeedsReviewCount(): Flow<Int>
}
