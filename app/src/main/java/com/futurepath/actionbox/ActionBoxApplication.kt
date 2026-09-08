package com.futurepath.actionbox

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.util.Log
import com.futurepath.actionbox.billing.BillingRepository
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import com.futurepath.actionbox.diagnostics.CrashLogger
import com.futurepath.actionbox.reminders.ReminderNotifications
import com.futurepath.actionbox.reminders.ReminderScheduler
import com.futurepath.actionbox.widget.ActionBoxWidget
import com.futurepath.actionbox.widget.ActionBoxWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val TAG = "ActionBoxApplication"

class ActionBoxApplication : Application() {

    // Lives for the whole process — this is what keeps WorkManager's schedule in sync with the
    // digest settings for as long as the app process exists, not just while an Activity is
    // visible (settings can change, and the process can be started by things other than
    // launching the UI, e.g. BootCompletedReceiver).
    //
    // The installed CoroutineExceptionHandler is what makes an exception here "handled" rather
    // than propagating to the process's default uncaught-exception handler and killing the whole
    // app: both collectors launched below are non-critical background features (reminder
    // scheduling, widget updates) that should degrade gracefully on their own failure, not take
    // the entire app down before a single screen can even appear — which is exactly what
    // happened here once, from an exception in the (until then never runtime-exercised) widget
    // update path below.
    private val applicationScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                CrashLogger.record(this, throwable)
            }
    )

    override fun onCreate() {
        super.onCreate()

        // Installed first, before anything else below, so a crash anywhere else in the app
        // (including in the third-party init that follows) still leaves a diagnosable trail —
        // see CrashLogger's doc.
        CrashLogger.installGlobalHandler(this)

        ReminderNotifications.ensureChannels(this)

        // Free-tier banner ads (ui/ads/BannerAdView.kt) and the Pro subscription
        // (billing/BillingRepository.kt) — both need to be ready before any screen that uses
        // them is first shown, so both are kicked off here rather than lazily on first use.
        MobileAds.initialize(this)
        BillingRepository.getInstance(this).startConnection()

        // Snooze checks (see SnoozeWorker) always run, unlike the digest/nudge schedule below —
        // snoozing is a direct per-item user action, not a background-summary preference the
        // user can turn off. Guarded the same way as the widget update below: scheduling a
        // periodic job is not worth crashing app startup over if WorkManager throws for any
        // reason.
        try {
            ReminderScheduler.scheduleSnoozeChecks(this)
        } catch (e: Exception) {
            CrashLogger.record(this, e)
        }

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
                try {
                    if (enabled) {
                        ReminderScheduler.scheduleAll(this, time)
                    } else {
                        ReminderScheduler.cancelAll(this)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to (re)schedule reminders", e)
                    CrashLogger.record(this, e)
                }
            }
            .launchIn(applicationScope)

        // Same reactive pattern for the home screen widget (widget/ActionBoxWidget.kt): fires
        // once at process start and again on every new/changed capture or isPro flip, so the
        // widget never needs a polling mechanism of its own — a fresh updateAll() re-runs
        // provideGlance, which reads the latest counts/plan status straight from these same
        // repositories. Wrapped in its own try/catch (on top of the scope-level handler above)
        // so a single failed update doesn't cancel this collector — the next emission gets
        // another chance rather than the widget silently going stale forever.
        val repository = NotificationRepository.getInstance(this)
        combine(repository.observeAll(), settings.isPro) { _, _ -> Unit }
            .onEach {
                try {
                    // Cheap existence check via the plain (non-Glance) AppWidgetManager API
                    // before touching Glance's own update machinery at all — the overwhelming
                    // majority of launches happen with no widget instance ever placed, and there
                    // is nothing to update in that case anyway.
                    val hasPlacedWidget = AppWidgetManager.getInstance(this)
                        .getAppWidgetIds(ComponentName(this, ActionBoxWidgetReceiver::class.java))
                        .isNotEmpty()
                    if (hasPlacedWidget) {
                        ActionBoxWidget().updateAll(this)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update the home screen widget", e)
                    CrashLogger.record(this, e)
                }
            }
            .launchIn(applicationScope)
    }
}
