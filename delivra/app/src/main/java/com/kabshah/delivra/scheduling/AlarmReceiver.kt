package com.kabshah.delivra.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kabshah.delivra.service.SchedulerService

/**
 * Receives AlarmManager exact-alarm fires.
 *
 * Dispatches directly to SchedulerService for instantaneous exact-alarm delivery
 * without waiting on WorkManager queue delays or Android Doze deferral.
 * Also triggers BackstopWorker.runOnceNow() for reconciliation.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.ACTION_ALARM_FIRE) return

        val messageId = intent.getStringExtra(Constants.EXTRA_MESSAGE_ID) ?: run {
            Log.e(TAG, "AlarmReceiver: missing message ID in intent")
            return
        }
        Log.d(TAG, "Alarm fired for message id=$messageId — dispatching to SchedulerService")

        // 1. Immediate direct dispatch to Foreground Service (instant send on alarm fire)
        try {
            SchedulerService.dispatchDueMessages(context, listOf(messageId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SchedulerService directly: ${e.message}", e)
        }

        // 2. Also run reconciliation pass via BackstopWorker
        try {
            BackstopWorker.runOnceNow(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger BackstopWorker: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
    }
}
