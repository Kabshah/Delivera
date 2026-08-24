package com.kabshah.delivra.scheduling

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.kabshah.delivra.data.MessageStatus
import com.kabshah.delivra.data.ScheduleRepository
import com.kabshah.delivra.service.SchedulerService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * BackstopWorker — periodic catch-up + cleanup + reconciliation (§2.2, §4.8, §6.7, §6.8).
 *
 * Also acts as the single dispatcher for both:
 * - BootReceiver (one-shot, for reconciliation + alarm re-registration)
 * - AlarmReceiver (one-shot, triggered by exact alarm)
 * - Periodic (every ~15 min — OS minimum)
 *
 * WorkManager unique work name (WORK_BACKSTOP / WORK_DISPATCH) enforces single-dispatcher (§6.8).
 */
@HiltWorker
class BackstopWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ScheduleRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "BackstopWorker started (boot=${inputData.getBoolean("boot_triggered", false)})")

        // 1. Reconciliation — resolve any stuck/ambiguous rows from before this run (§6.7)
        repository.reconcile()

        // 2. On boot: re-register all PENDING alarms (§2.2)
        if (inputData.getBoolean("boot_triggered", false)) {
            repository.reRegisterAllAlarms()
        }

        // 3. Find due messages, applying § 6.4 missed-schedule grace policy
        val dueMessages = repository.getDueMessagesAndHandleMissed()
        if (dueMessages.isNotEmpty()) {
            Log.d(TAG, "BackstopWorker: ${dueMessages.size} due message(s) to dispatch")
            // Delegate actual sending to SchedulerService (which manages Node/Baileys lifecycle)
            SchedulerService.dispatchDueMessages(applicationContext, dueMessages.map { it.id })
        }

        // 4. Auto-cleanup (§4.8)
        repository.runCleanup()

        Log.d(TAG, "BackstopWorker finished")
        return Result.success()
    }

    companion object {
        private const val TAG = "BackstopWorker"

        /** Schedule the periodic backstop from Application startup. */
        fun schedulePeriodicBackstop(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackstopWorker>(
                Constants.BACKSTOP_INTERVAL_MIN, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)  // don't wake when offline (§2.5)
                        .build()
                )
                .addTag("delivra_backstop_periodic")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                Constants.WORK_BACKSTOP,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * One immediate catch-up pass (app launch / device boot): overdue sends
         * + stranded retryable rows get handled now, not at the next 15-min tick.
         * KEEP policy makes repeated calls cheap no-ops while one is enqueued.
         */
        fun runOnceNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackstopWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("delivra_backstop_once")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                Constants.WORK_BACKSTOP + "_once",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
