package com.kabshah.delivra.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// ─── Status Enum (§5, §6.2) ─────────────────────────────────────────────────
enum class MessageStatus {
    PENDING,            // scheduled, not yet claimed by any dispatch cycle
    CLAIMED,            // atomically claimed, about to connect — safe to revert to PENDING on failure
    CONNECTING,         // Baileys socket authenticating — safe to revert to PENDING on failure
    SENDING,            // actual send call in flight — ambiguous if interrupted, see §6.2/§6.7
    SENT_CONFIRMED,     // Baileys ack received AND persisted — UI shows "Sent"
    RETRYABLE_FAILURE,  // transient failure, will auto-retry with backoff
    FINAL_FAILURE,      // exhausted retries or non-retryable error — UI shows "Failed"
    NEEDS_REVIEW        // ambiguous outcome after crash during SENDING — UI shows "Needs Review"
}

// ─── Room Entity (§5) ────────────────────────────────────────────────────────
@Entity(tableName = "scheduled_messages")
data class ScheduledMessage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), // also correlationId (§6.2)
    val contactName: String,
    val contactJid: String,                  // resolved at scheduling time, not send time (§6.3)

    val messageText: String?,
    val voiceNotePath: String?,              // app-owned file in app-private storage
    val attachmentUri: String?,              // content:// URI, persistent permission taken (§2.7)
    val attachmentDisplayName: String?,
    val attachmentMimeType: String?,         // "application/pdf" | ".docx" mime | null
    val attachmentSizeBytes: Long?,

    // Wall-clock time semantics (§2.6)
    val scheduledLocalDateTime: String,      // ISO-8601 local, no offset, e.g. "2026-08-25T09:00:00"
    val timezoneId: String,                  // e.g. "Asia/Karachi", captured at scheduling time
    val resolvedEpochMs: Long,               // derived — re-derived on boot/timezone change

    val status: MessageStatus,
    val attemptCount: Int = 0,
    val attemptStartedAtEpochMs: Long? = null, // for reconciliation staleness detection (§6.7)
    val engineSessionId: String? = null,        // diagnostics only (§6.9)
    val lastErrorReason: String? = null,

    val terminalAtEpochMs: Long? = null,    // drives 12h/24h cleanup windows (§4.8)
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
