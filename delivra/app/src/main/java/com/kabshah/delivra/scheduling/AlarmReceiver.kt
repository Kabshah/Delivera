package com.kabshah.delivra.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Receives AlarmManager exact-alarm fires.
 * Routes into the single WorkManager dispatcher (§6.8) rather than starting
 * a second independent execution path — so there's structurally one dispatcher.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.ACTION_ALARM_FIRE) return

        val messageId = intent.getStringExtra(Constants.EXTRA_MESSAGE_ID) ?: run {
            Log.e(TAG, "AlarmReceiver: missing message ID in intent")
            return
        }
        Log.d(TAG, "Alarm fired for message id=$messageId — routing to WorkManager dispatcher")

        // Route through unique work — same entry point as WorkManager backstop (§6.8)
        val workRequest = OneTimeWorkRequestBuilder<BackstopWorker>()
            .setInputData(workDataOf("triggered_message_id" to messageId))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            Constants.WORK_DISPATCH,
            ExistingWorkPolicy.KEEP,  // If already running, don't start a duplicate
            workRequest
        )
    }

    companion object {
        private const val TAG = "AlarmReceiver"
    }
}
