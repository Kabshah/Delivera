package com.kabshah.delivra

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kabshah.delivra.diagnostics.EventRingBuffer
import com.kabshah.delivra.diagnostics.RingEvent
import com.kabshah.delivra.scheduling.BackstopWorker
import com.kabshah.delivra.scheduling.Constants
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class DelivraApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var ringBuffer: EventRingBuffer

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        recoverDispatchTombstone()
        // Ensure the periodic backstop is scheduled on every app start
        // (WorkManager is idempotent with KEEP policy — safe to call on every launch)
        BackstopWorker.schedulePeriodicBackstop(this)
        // Also run one backstop pass NOW: catches up overdue PENDING messages
        // and revives stranded RETRYABLE_FAILURE rows immediately on launch,
        // instead of waiting up to 15 minutes for the periodic tick (§6.4, §6.7).
        BackstopWorker.runOnceNow(this)
    }

    /**
     * A hard process death mid-dispatch (native crash, WASM abort, LMK)
     * bypasses every try/catch in the dispatch path — SchedulerService's
     * tombstone file is the only evidence it happened. Surface it in the
     * diagnostics ring buffer (visible in Settings) BEFORE the backstop starts
     * re-dispatching, then clear it. See Constants.DISPATCH_TOMBSTONE.
     */
    private fun recoverDispatchTombstone() {
        val file = File(filesDir, Constants.DISPATCH_TOMBSTONE)
        if (!file.exists()) return
        try {
            val json = org.json.JSONObject(file.readText())
            val ids = json.optJSONArray("ids")
            val at = json.optLong("at", 0L)
            Log.e(TAG, "Previous process died mid-dispatch (tombstone at ${java.util.Date(at)}), ids=" +
                    (0 until (ids?.length() ?: 0)).joinToString { ids?.getString(it) ?: "?" })
            if (ids != null) {
                for (i in 0 until ids.length()) {
                    ringBuffer.record(RingEvent.SendFailed(ids.getString(i), "process_died_mid_dispatch"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dispatch tombstone unreadable — clearing anyway: ${e.message}")
        } finally {
            try { file.delete() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "DelivraApp"
    }
}
