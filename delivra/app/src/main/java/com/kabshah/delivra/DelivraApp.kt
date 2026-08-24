package com.kabshah.delivra

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kabshah.delivra.scheduling.BackstopWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DelivraApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Ensure the periodic backstop is scheduled on every app start
        // (WorkManager is idempotent with KEEP policy — safe to call on every launch)
        BackstopWorker.schedulePeriodicBackstop(this)
        // Also run one backstop pass NOW: catches up overdue PENDING messages
        // and revives stranded RETRYABLE_FAILURE rows immediately on launch,
        // instead of waiting up to 15 minutes for the periodic tick (§6.4, §6.7).
        BackstopWorker.runOnceNow(this)
    }
}
