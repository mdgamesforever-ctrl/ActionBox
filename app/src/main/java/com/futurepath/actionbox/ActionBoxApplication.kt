package com.futurepath.actionbox

import android.app.Application
import com.futurepath.actionbox.billing.BillingRepository
import com.futurepath.actionbox.data.SettingsRepository
import com.futurepath.actionbox.reminders.ReminderNotifications
import com.futurepath.actionbox.reminders.ReminderScheduler
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ActionBoxApplication : Application() {

    // Lives for the whole process — this is what keeps WorkManager's schedule in sync with the
    // digest settings for as long as the app process exists, not just while an Activity is
    // visible (settings can change, and the process can be started by things other than
    // launching the UI, e.g. BootCompletedReceiver).
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        ReminderNotifications.ensureChannels(this)

        // Free-tier banner ads (ui/ads/BannerAdView.kt) and the Pro subscription
        // (billing/BillingRepository.kt) — both need to be ready before any screen that uses
        // them is first shown, so both are kicked off here rather than lazily on first use.
        MobileAds.initialize(this)
        BillingRepository.getInstance(this).startConnection()

        // Single reactive source of truth for the reminder schedule: fires once immediately
        // with whatever's currently stored (including the defaults, on first launch) to
        // (re-)establish scheduling on every process start, and again every time the user
        // flips the digest toggle or changes its time in Settings — see
        // NotificationViewModel.setDigestsEnabled/setDigestTime, which only persist the
        // setting and rely on this collector to actually reschedule.
        val settings = SettingsRepository.getInstance(this)
        combine(settings.digestsEnabled, settings.digestTime) { enabled, time -> enabled to time }
            .distinctUntilChanged()
            .onEach { (enabled, time) ->
                if (enabled) {
                    ReminderScheduler.scheduleAll(this, time)
                } else {
                    ReminderScheduler.cancelAll(this)
                }
            }
            .launchIn(applicationScope)
    }
}
