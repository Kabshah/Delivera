package com.kabshah.delivra.scheduling

object Constants {
    /** Warm-up window before earliest due message: soft target for connecting Baileys (§2.3) */
    const val SEND_WARMUP_WINDOW_MS: Long = 2 * 60 * 1000L  // 2 minutes

    /** Grace window for missed schedules: send if overdue by ≤ 6h, else FINAL_FAILURE (§6.4) */
    const val MISSED_SCHEDULE_GRACE_MS: Long = 6 * 60 * 60 * 1000L  // 6 hours

    /** Staleness threshold for reconciliation: if CLAIMED/CONNECTING/SENDING older than this, stale (§6.7) */
    const val CLAIM_STALENESS_MS: Long = 2 * 60 * 1000L  // 2 minutes

    /** Auto-cleanup delay for SENT_CONFIRMED rows (§4.8) */
    const val SENT_CLEANUP_DELAY_MS: Long = 12 * 60 * 60 * 1000L  // 12 hours

    /** Auto-cleanup delay for FINAL_FAILURE / NEEDS_REVIEW rows (§4.8) */
    const val FAILED_CLEANUP_DELAY_MS: Long = 24 * 60 * 60 * 1000L  // 24 hours

    /** WorkManager periodic interval minimum (Android OS limit, §3) */
    const val BACKSTOP_INTERVAL_MIN: Long = 15L  // minutes

    /** Lookahead window: if next due message is within this, don't disconnect Baileys yet (§2.3) */
    const val SEND_LOOKAHEAD_WINDOW_MS: Long = 3 * 60 * 1000L  // 3 minutes

    /** Max retry attempts for RETRYABLE_FAILURE before promoting to FINAL_FAILURE (§6.2) */
    const val MAX_RETRY_ATTEMPTS: Int = 3

    /** Retry backoff delays (ms) per attempt index (§6.2) */
    val RETRY_BACKOFF_MS = listOf(5_000L, 20_000L, 60_000L)

    // Notification channel IDs
    const val CHANNEL_FOREGROUND = "delivra_foreground"
    const val CHANNEL_FAILURES = "delivra_failures"

    // WorkManager unique work names
    const val WORK_BACKSTOP = "delivra_backstop"
    const val WORK_DISPATCH = "delivra_dispatch"

    // AlarmManager intent action
    const val ACTION_ALARM_FIRE = "com.kabshah.delivra.ACTION_ALARM_FIRE"
    const val EXTRA_MESSAGE_ID = "message_id"
}
