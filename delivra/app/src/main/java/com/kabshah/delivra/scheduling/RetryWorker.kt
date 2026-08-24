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
 * RetryWorker — one-shot delayed retry for RETRYABLE_FAILURE rows (§6.2).
 *
 * Without this, a transient send failure (network blip, socket conflict) left
 * the row stuck in RETRYABLE_FAILURE forever: the backstop's due query only
 * selects PENDING, so nobody ever picked it up again.
 *
 * Flow: NodeBridge marks RETRYABLE_FAILURE → schedules this worker with the
 * §6.2 backoff (5s / 20s / 60s) → on fire: flip row to PENDING (guarded by
 * status) → hand it to SchedulerService's normal dispatch. The atomic claim +
 * attemptCount cap in NodeBridge remain the safety net underneath.
 */
@HiltWorker
class RetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ScheduleRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID)
            ?: return Result.failure()

        val msg = repository.getById(messageId)
        if (msg == null || msg.status != MessageStatus.RETRYABLE_FAILURE) {
            Log.d(TAG, "Retry skipped for id=$messageId (status=${msg?.status} — resolved another way)")
            return Result.success()
        }

        if (!repository.markRetryableAsPending(messageId)) {
            Log.d(TAG, "Retry dropped for id=$messageId — row no longer RETRYABLE_FAILURE")
            return Result.success()
        }

        Log.d(TAG, "Retry due for id=$messageId (attempt ${msg.attemptCount}) — dispatching")
        SchedulerService.dispatchDueMessages(applicationContext, listOf(messageId))
        return Result.success()
    }

    companion object {
        private const val TAG = "RetryWorker"
        private const val KEY_MESSAGE_ID = "message_id"

        /**
         * Schedules a retry for one message. Unique-per-message with REPLACE:
         * if something re-schedules sooner, the newer delay wins.
         */
        fun schedule(context: Context, messageId: String, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<RetryWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("delivra_retry")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "delivra_retry_$messageId",
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "Retry scheduled for id=$messageId in ${delayMs}ms")
        }
    }
}
