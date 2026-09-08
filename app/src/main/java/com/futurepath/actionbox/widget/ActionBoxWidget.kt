package com.futurepath.actionbox.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.futurepath.actionbox.MainActivity
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import com.futurepath.actionbox.data.groupActiveByCategory
import com.futurepath.actionbox.diagnostics.CrashLogger
import com.futurepath.actionbox.ui.inbox.InboxTab
import kotlinx.coroutines.flow.first

/** Extras [MainActivity.onNewIntent]/[MainActivity.onCreate] read to route a widget tap to a
 * specific tab or straight to the paywall — see [ActionBoxWidgetReceiver]'s doc for why this is
 * plain Intent extras rather than Glance's typed ActionParameters. */
const val WIDGET_EXTRA_TAB = "com.futurepath.actionbox.widget.EXTRA_TAB"
const val WIDGET_EXTRA_OPEN_PAYWALL = "com.futurepath.actionbox.widget.EXTRA_OPEN_PAYWALL"

private val WIDGET_TABS = listOf(InboxTab.ACTION, InboxTab.WAITING, InboxTab.DEADLINE, InboxTab.REPLY)
private const val BACKGROUND_COLOR = 0xFF1E1E1E.toInt()

/**
 * Home screen widget showing live per-category counts (Pro-exclusive — see this class's Free-tier
 * branch below). Re-rendered from scratch on every [androidx.glance.appwidget.updateAll] call,
 * which [com.futurepath.actionbox.ActionBoxApplication] triggers from the same reactive
 * "single source of truth" collector already used for the reminder schedule — this class itself
 * has no polling of its own.
 *
 * [provideGlance]'s whole body is wrapped in a try/catch: an uncaught exception anywhere in here
 * (or thrown back out of a failed composition) is exactly what previously showed up as Glance's
 * generic "Can't show content" fallback on the home screen with nothing to diagnose it by — see
 * [WidgetErrorState] and [onCompositionError], which together guarantee [provideContent] is
 * always given something real to render and that a failure still leaves a traceable
 * [CrashLogger] record instead of a silent, generic error.
 */
class ActionBoxWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        try {
            // Re-checked on every render (not just at add-time) so a Pro->Free downgrade — or a
            // Free user placing the widget some other way, e.g. via the launcher's widget picker
            // even though the paywall gates the in-app "add widget" entry point — always falls
            // back to this placeholder instead of showing stale/incorrect counts.
            val isPro = SettingsRepository.getInstance(context).isPro.first()
            if (!isPro) {
                provideContent { UpgradePlaceholder(context) }
                return
            }

            val activeByCategory = NotificationRepository.getInstance(context).observeAll().first().groupActiveByCategory()
            val counts = WIDGET_TABS.associateWith { tab -> tab.states.sumOf { state -> activeByCategory[state]?.size ?: 0 } }

            provideContent { WidgetContent(context, counts) }
        } catch (e: Exception) {
            CrashLogger.record(context, e)
            provideContent { WidgetErrorState(context) }
        }
    }

    /** Glance's own fallback path for a composition failure that manages to escape the
     * try/catch above (e.g. inside the composable itself, past the point [provideContent] was
     * called) — logged here too rather than only ever showing as an unexplained blank/error
     * widget. [super]'s default behavior (rendering Glance's built-in error layout) is
     * preserved; this only adds the diagnostic side effect. */
    override fun onCompositionError(context: Context, glanceId: GlanceId, appWidgetId: Int, throwable: Throwable) {
        CrashLogger.record(context, throwable)
        super.onCompositionError(context, glanceId, appWidgetId, throwable)
    }
}

@Composable
private fun UpgradePlaceholder(context: Context) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(BACKGROUND_COLOR)
            .padding(12)
            .clickable(actionStartActivity(paywallIntent(context))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        Text(
            text = "Upgrade to Pro to add this widget",
            style = TextStyle(color = ColorProvider(Color.WHITE), fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun WidgetContent(context: Context, counts: Map<InboxTab, Int>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(BACKGROUND_COLOR)
            .padding(12)
            // Tapping anywhere that isn't one of the category rows below opens the main inbox —
            // Glance/RemoteViews dispatch a tap to the innermost clickable region under it, so
            // each row's own clickable() modifier (below) takes priority over this one.
            .clickable(actionStartActivity(mainIntent(context))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        WIDGET_TABS.forEach { tab ->
            Text(
                text = "${counts[tab] ?: 0} ${tab.label}",
                style = TextStyle(color = ColorProvider(Color.WHITE), fontWeight = FontWeight.Medium),
                modifier = GlanceModifier
                    .padding(4)
                    .clickable(actionStartActivity(tabIntent(context, tab)))
            )
        }
    }
}

/** Last-resort fallback when the real content couldn't be built (see [ActionBoxWidget.provideGlance]'s
 * try/catch) — deliberately as simple as possible (a single Text, no data reads) since this is
 * exactly the path that runs when something else already went wrong. */
@Composable
private fun WidgetErrorState(context: Context) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(BACKGROUND_COLOR)
            .padding(12)
            .clickable(actionStartActivity(mainIntent(context))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Couldn't load — tap to open ActionBox",
            style = TextStyle(color = ColorProvider(Color.WHITE), fontWeight = FontWeight.Medium)
        )
    }
}

private fun mainIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)

private fun tabIntent(context: Context, tab: InboxTab): Intent =
    Intent(context, MainActivity::class.java).putExtra(WIDGET_EXTRA_TAB, tab.name)

private fun paywallIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).putExtra(WIDGET_EXTRA_OPEN_PAYWALL, true)
