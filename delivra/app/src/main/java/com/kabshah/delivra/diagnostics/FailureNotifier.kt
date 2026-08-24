package com.kabshah.delivra.diagnostics

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kabshah.delivra.MainActivity
import com.kabshah.delivra.scheduling.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FailureNotifier — §4.9: when a message transitions to Failed or Needs
 * Review, post a system notification (contact name + short reason) that opens
 * the app on tap. Fires even if no activity is open.
 *
 * Called from every terminal-transition site: NodeBridge (send failures /
 * bridge exceptions), ScheduleRepository (missed-window, reconciliation).
 */
@Singleton
class FailureNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun notifyFailed(contactName: String, reason: String, messageId: String) {
        post(
            title = "Message to $contactName failed",
            text = "Reason: ${reason.humanReadable()}. Tap to view.",
            messageId = messageId
        )
    }

    fun notifyNeedsReview(contactName: String, messageId: String) {
        post(
            title = "Check WhatsApp for $contactName",
            text = "We're not sure if this message was sent. Please verify in WhatsApp.",
            messageId = messageId
        )
    }

    private fun post(title: String, text: String, messageId: String) {
        try {
            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification: Notification = android.app.Notification.Builder(context, Constants.CHANNEL_FAILURES)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // messageId-derived id keeps retries of different messages distinct
            nm.notify(messageId.hashCode(), notification)
        } catch (e: Exception) {
            Log.w("FailureNotifier", "Failed to post notification: ${e.message}")
        }
    }

    private fun String.humanReadable(): String = when (this) {
        "network_timeout" -> "network timeout"
        "connection_closed" -> "connection lost"
        "media_too_large" -> "file too large"
        "invalid_jid" -> "invalid contact"
        "source_file_unavailable" -> "source file missing"
        "missed_window_exceeded" -> "scheduled time passed too long ago"
        else -> this.take(80)
    }
}
