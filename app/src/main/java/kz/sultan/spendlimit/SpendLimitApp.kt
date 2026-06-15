package kz.sultan.spendlimit

import android.app.Application
import kz.sultan.spendlimit.util.LimitAlertNotifier
import kz.sultan.spendlimit.work.SyncScheduler

class SpendLimitApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        LimitAlertNotifier.ensureChannel(this)
        // Периодическая фоновая синхронизация локальной очереди в Supabase.
        SyncScheduler.schedulePeriodic(this)
    }
}
