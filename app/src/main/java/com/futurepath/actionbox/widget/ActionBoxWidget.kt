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
import androidx.compose.ui.unit.dp
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.futurepath.actionbox.MainActivity
import com.futurepath.actionbox.R
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import com.futurepath.actionbox.data.groupActiveByCategory
import com.futurepath.actionbox.diagnostics.CrashLogger
import com.futurepath.actionbox.ui.inbox.InboxTab
import kotlinx.coroutines.CancellationException
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
 * Data is always fetched to completion in [provideGlance]'s own coroutine BEFORE
 * [provideContent] is ever called — never inside a `LaunchedEffect` or other composition-scoped
 * coroutine builder within the composables below, and never inside the same try/catch as
 * [provideContent] itself. That second point matters just as much as the first: [provideContent]
 * suspends for the widget's entire session (it doesn't "return quickly" the way a normal
 * function call does), so if a newer [androidx.glance.appwidget.updateAll] call supersedes an
 * in-flight one, Glance cancels that session out from under it — surfacing as
 * `LeftCompositionCancellationException` (a real
 * [kotlinx.coroutines.CancellationException] subtype, confirmed by inspecting the actual
 * compose-runtime AAR). A broad `catch (e: Exception)` wrapped around [provideContent] itself
 * would swallow that cancellation and then try to call [provideContent] AGAIN from inside the
 * catch block on an already-cancelling coroutine — which is exactly what silently broke this
 * widget before. Only the plain data-fetching calls below are wrapped in try/catch, each one
 * re-throwing [CancellationException] rather than swallowing it, and [provideContent] itself is
 * called exactly once per branch, outside any catch.
 */
class ActionBoxWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Re-checked on every render (not just at add-time) so a Pro->Free downgrade — or a
        // Free user placing the widget some other way, e.g. via the launcher's widget picker
        // even though the paywall gates the in-app "add widget" entry point — always falls
        // back to this placeholder instead of showing stale/incorrect counts.
        val isPro = try {
            SettingsRepository.getInstance(context).isPro.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogger.record(context, e)
            false
        }

        if (!isPro) {
            provideContent { UpgradePlaceholder(context) }
            return
        }

        val counts = try {
            val activeByCategory = NotificationRepository.getInstance(context).observeAll().first().groupActiveByCategory()
            WIDGET_TABS.associateWith { tab -> tab.states.sumOf { state -> activeByCategory[state]?.size ?: 0 } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogger.record(context, e)
            null
        }

        if (counts == null) {
            provideContent { WidgetErrorState(context) }
        } else {
            provideContent { WidgetContent(context, counts) }
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
            .padding(12.dp)
            .clickable(actionStartActivity(paywallIntent(context))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        Text(
            text = context.getString(R.string.widget_upgrade_placeholder),
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
            .padding(12.dp)
            // Tapping anywhere that isn't one of the category rows below opens the main inbox —
            // Glance/RemoteViews dispatch a tap to the innermost clickable region under it, so
            // each row's own clickable() modifier (below) takes priority over this one.
            .clickable(actionStartActivity(mainIntent(context))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        WIDGET_TABS.forEach { tab ->
            Text(
                text = "${counts[tab] ?: 0} ${context.getString(tab.labelRes)}",
                style = TextStyle(color = ColorProvider(Color.WHITE), fontWeight = FontWeight.Medium),
                modifier = GlanceModifier
                    .padding(4.dp)
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
            .padding(12.dp)
            .clickable(actionStartActivity(mainIntent(context))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = context.getString(R.string.widget_load_error),
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
