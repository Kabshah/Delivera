package com.kabshah.delivra.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * RECEIVE_BOOT_COMPLETED receiver (§2.2).
 *
 * On every device boot:
 * 1. Triggers reconciliation pass (finds rows stuck mid-send from before reboot).
 * 2. Re-registers AlarmManager exact alarms for all PENDING rows
 *    (AlarmManager alarms are cleared by Android on every reboot — OS guarantee).
 * 3. Also handles any PENDING rows that are now overdue per §6.4 grace policy.
 * 4. Ensures the periodic WorkManager backstop is scheduled.
 *
 * Disclosed plainly in README per §7 — boot permission is for rescheduling alarms only.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.LOCKED_BOOT_COMPLETED"
            )
        ) return

        Log.d(TAG, "Boot completed — triggering reconciliation + alarm re-registration")

        // Schedule a one-off BackstopWorker immediately for reconciliation + re-registration
        val immediate = androidx.work.OneTimeWorkRequestBuilder<BackstopWorker>()
            .setInputData(androidx.work.workDataOf("boot_triggered" to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "delivra_boot_reconcile",
            ExistingWorkPolicy.KEEP,
            immediate
        )

        // Ensure periodic backstop is running (it clears itself on clear-data, so re-enqueue here)
        val periodic = PeriodicWorkRequestBuilder<BackstopWorker>(
            Constants.BACKSTOP_INTERVAL_MIN, TimeUnit.MINUTES
        )
            .addTag("delivra_backstop_periodic")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            Constants.WORK_BACKSTOP,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
