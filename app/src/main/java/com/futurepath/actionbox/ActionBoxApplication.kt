package com.futurepath.actionbox

import android.app.Application
import com.futurepath.actionbox.billing.BillingRepository
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import com.futurepath.actionbox.reminders.ReminderNotifications
import com.futurepath.actionbox.reminders.ReminderScheduler
import com.futurepath.actionbox.widget.ActionBoxWidget
import androidx.glance.appwidget.updateAll
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

        // Snooze checks (see SnoozeWorker) always run, unlike the digest/nudge schedule below —
        // snoozing is a direct per-item user action, not a background-summary preference the
        // user can turn off.
        ReminderScheduler.scheduleSnoozeChecks(this)

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

        // Same reactive pattern for the home screen widget (widget/ActionBoxWidget.kt): fires
        // once at process start and again on every new/changed capture or isPro flip, so the
        // widget never needs a polling mechanism of its own — a fresh updateAll() re-runs
        // provideGlance, which reads the latest counts/plan status straight from these same
        // repositories.
        val repository = NotificationRepository.getInstance(this)
        combine(repository.observeAll(), settings.isPro) { _, _ -> Unit }
            .onEach { ActionBoxWidget().updateAll(this) }
            .launchIn(applicationScope)
    }
}
