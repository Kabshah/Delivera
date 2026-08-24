package com.kabshah.delivra.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.kabshah.delivra.data.ScheduledMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps AlarmManager exact-alarm calls.
 * Wall-clock → epoch conversion happens here using java.time (§2.6, §3).
 * No manual offset math — ZonedDateTime handles DST naturally.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Register (or re-register) an exact alarm for the given message.
     * Uses setExactAndAllowWhileIdle — the strongest reliable alarm on modern Android (§2.2, §3).
     *
     * Re-derives resolvedEpochMs from scheduledLocalDateTime + timezoneId each time,
     * so that boot-recovery (§2.2) picks up any timezone shift that happened while off.
     */
    fun scheduleExact(message: ScheduledMessage) {
        val epochMs = resolveEpochMs(message.scheduledLocalDateTime, message.timezoneId)
        if (epochMs <= System.currentTimeMillis()) {
            Log.d(TAG, "scheduleExact: id=${message.id} is already in the past, skipping alarm registration")
            return
        }

        val intent = buildAlarmIntent(message.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "scheduleExact: exact alarm permission not granted — alarm NOT registered for id=${message.id}")
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, intent)
        Log.d(TAG, "Alarm registered: id=${message.id} at epochMs=$epochMs")
    }

    /** Cancel the alarm for a given message ID. */
    fun cancel(messageId: String) {
        val intent = buildAlarmIntent(messageId)
        alarmManager.cancel(intent)
        Log.d(TAG, "Alarm cancelled: id=$messageId")
    }

    /** Re-derive epoch ms from wall-clock local datetime + timezone ID. DST handled by ZonedDateTime. */
    fun resolveEpochMs(localDateTimeIso: String, timezoneId: String): Long {
        val localDt = LocalDateTime.parse(localDateTimeIso)
        val zone = ZoneId.of(timezoneId)
        return localDt.atZone(zone).toInstant().toEpochMilli()
    }

    private fun buildAlarmIntent(messageId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = Constants.ACTION_ALARM_FIRE
            putExtra(Constants.EXTRA_MESSAGE_ID, messageId)
        }
        return PendingIntent.getBroadcast(
            context,
            messageId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"
    }
}
