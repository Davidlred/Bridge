package app.thebridge

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.thebridge.data.reminders.ReminderScheduler
import app.thebridge.data.session.SessionManager
import app.thebridge.data.session.SessionPhase
import app.thebridge.data.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BridgeApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var session: SessionManager
    @Inject lateinit var reminders: ReminderScheduler
    @Inject lateinit var billing: app.thebridge.data.BillingRepository
    @Inject lateinit var analytics: app.thebridge.data.Analytics

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedulePeriodic(this)
        billing.configure(this)
        analytics.setup(this)
        app.thebridge.data.AlertChannels.create(this)
        reminders.rescheduleAsync()
        // Sync whenever the app comes to the foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { if (session.phase.value == SessionPhase.Ready || session.phase.value == SessionPhase.Onboarding) SyncWorker.kick(this@BridgeApp) }
        })
    }
}
