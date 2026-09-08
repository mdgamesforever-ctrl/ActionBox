package com.futurepath.actionbox.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futurepath.actionbox.R
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.ui.ads.BannerAdView
import com.futurepath.actionbox.reminders.ReminderNotifications
import com.futurepath.actionbox.ui.feed.NotificationFeedScreen
import com.futurepath.actionbox.ui.inbox.CategoryInboxScreen
import com.futurepath.actionbox.ui.inbox.InboxTab
import com.futurepath.actionbox.ui.insights.WeeklyInsightsScreen
import com.futurepath.actionbox.ui.paywall.PaywallScreen
import com.futurepath.actionbox.ui.search.SearchScreen
import com.futurepath.actionbox.ui.settings.CrashLogScreen
import com.futurepath.actionbox.ui.settings.SettingsScreen
import com.futurepath.actionbox.ui.settings.VipSendersScreen
import com.futurepath.actionbox.viewmodel.NotificationViewModel
import com.futurepath.actionbox.widget.WIDGET_EXTRA_OPEN_PAYWALL
import com.futurepath.actionbox.widget.WIDGET_EXTRA_TAB

private const val SETTINGS_ROUTE = "settings"
private const val DEBUG_ROUTE = "debug"
private const val PAYWALL_ROUTE = "paywall"
private const val SEARCH_ROUTE = "search"
private const val CRASH_LOG_ROUTE = "crash_log"
private const val VIP_SENDERS_ROUTE = "vip_senders"
private const val WEEKLY_INSIGHTS_ROUTE = "weekly_insights"

/**
 * The app's primary navigation shell: bottom-nav tabs for the grouped inboxes (see [InboxTab]),
 * a settings screen reached from the top bar, and the old flat debug feed reached only by a
 * long-press on the top bar title — see that composable's doc for why it's kept but hidden
 * rather than deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: NotificationViewModel,
    widgetIntent: Intent? = null,
    onWidgetIntentHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val itemsByCategory by viewModel.itemsByCategory.collectAsStateWithLifecycle()
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    val correctionLearningEnabled by viewModel.correctionLearningEnabled.collectAsStateWithLifecycle()
    val digestsEnabled by viewModel.digestsEnabled.collectAsStateWithLifecycle()
    val digestTime by viewModel.digestTime.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val productDetails by viewModel.productDetails.collectAsStateWithLifecycle()
    val billingUnavailable by viewModel.billingUnavailable.collectAsStateWithLifecycle()
    val vipSenders by viewModel.vipSenders.collectAsStateWithLifecycle()
    val weeklyInsights by viewModel.weeklyInsights.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = InboxTab.entries.find { it.route == currentRoute }

    // A widget tap (see widget/ActionBoxWidget.kt) reaches here as widgetIntent — either a
    // specific category tab, or a nudge to the paywall for the Free-tier placeholder — rather
    // than through NavHost's own deep-link handling, since MainActivity's launchMode="singleTop"
    // routes it to onNewIntent instead of a fresh navigation graph.
    LaunchedEffect(widgetIntent) {
        val intent = widgetIntent ?: return@LaunchedEffect
        when {
            intent.getBooleanExtra(WIDGET_EXTRA_OPEN_PAYWALL, false) -> {
                navController.navigate(PAYWALL_ROUTE)
            }
            // A tap on the weekly insights notification (see ReminderNotifications.postWeeklyInsights)
            // reaches here the same way a widget tap does — MainActivity's launchMode="singleTop"
            // routes any of these into onNewIntent rather than a fresh navigation graph.
            intent.getBooleanExtra(ReminderNotifications.INSIGHTS_EXTRA_OPEN, false) -> {
                navController.navigate(WEEKLY_INSIGHTS_ROUTE)
            }
            intent.hasExtra(WIDGET_EXTRA_TAB) -> {
                val tab = InboxTab.entries.find { it.name == intent.getStringExtra(WIDGET_EXTRA_TAB) }
                if (tab != null) {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
        onWidgetIntentHandled()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle(currentRoute, currentTab, onOpenDebug = { navController.navigate(DEBUG_ROUTE) }) },
                actions = {
                    if (currentTab != null) {
                        IconButton(onClick = { navController.navigate(SEARCH_ROUTE) }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                        }
                    }
                    if (currentRoute != SETTINGS_ROUTE) {
                        IconButton(onClick = { navController.navigate(SETTINGS_ROUTE) }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.tab_settings))
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Only shown on the inbox tabs themselves — settings/debug are one level "into"
            // the app and use the back button (via the system/gesture nav) to return, the same
            // way any secondary screen would.
            if (currentTab != null) {
                Column {
                    // Free-tier banner ad — see ui/ads/BannerAdView's doc. Pro removes ads
                    // entirely, so this is skipped rather than rendered-and-hidden.
                    if (!isPro) {
                        BannerAdView()
                    }
                    NavigationBar {
                        InboxTab.entries.forEach { tab ->
                            val count = tab.states.sumOf { state -> itemsByCategory[state]?.size ?: 0 }
                            NavigationBarItem(
                                selected = tab == currentTab,
                                onClick = {
                                    if (tab != currentTab) {
                                        navController.navigate(tab.route) {
                                            // Standard bottom-nav behavior: switching tabs
                                            // doesn't pile up back-stack entries, and returning
                                            // to a previously-visited tab restores its scroll
                                            // position.
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                                        Icon(iconFor(tab), contentDescription = stringResource(tab.labelRes))
                                    }
                                },
                                label = { Text(stringResource(tab.labelRes)) }
                            )
                        }
                    }
                }
            }
        }
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = InboxTab.DEFAULT.route,
            modifier = Modifier.padding(contentPadding)
        ) {
            InboxTab.entries.forEach { tab ->
                composable(tab.route) {
                    CategoryInboxScreen(
                        tab = tab,
                        itemsByCategory = itemsByCategory,
                        onCorrect = viewModel::correctClassification,
                        onMarkHandled = viewModel::markHandled,
                        onUndoHandled = viewModel::undoHandled,
                        onSnooze = viewModel::snooze,
                        onUndoSnooze = viewModel::undoSnooze,
                        isPro = isPro
                    )
                }
            }
            composable(SETTINGS_ROUTE) {
                SettingsScreen(
                    isPro = isPro,
                    onUpgradeClick = { navController.navigate(PAYWALL_ROUTE) },
                    onDebugProOverrideChange = viewModel::setProDebugOverride,
                    correctionLearningEnabled = correctionLearningEnabled,
                    onCorrectionLearningChange = viewModel::setCorrectionLearningEnabled,
                    digestsEnabled = digestsEnabled,
                    onDigestsEnabledChange = viewModel::setDigestsEnabled,
                    digestTime = digestTime,
                    onDigestTimeChange = viewModel::setDigestTime,
                    themeMode = themeMode,
                    onThemeModeChange = viewModel::setThemeMode,
                    appLanguage = appLanguage,
                    onAppLanguageChange = viewModel::setAppLanguage,
                    onOpenVipSenders = { navController.navigate(VIP_SENDERS_ROUTE) },
                    onOpenWeeklyInsights = { navController.navigate(WEEKLY_INSIGHTS_ROUTE) },
                    onViewCrashLogClick = { navController.navigate(CRASH_LOG_ROUTE) }
                )
            }
            composable(CRASH_LOG_ROUTE) {
                CrashLogScreen()
            }
            composable(VIP_SENDERS_ROUTE) {
                VipSendersScreen(
                    vipSenders = vipSenders,
                    notifications = notifications,
                    onAdd = viewModel::addVipSender,
                    onRemove = viewModel::removeVipSender
                )
            }
            composable(WEEKLY_INSIGHTS_ROUTE) {
                WeeklyInsightsScreen(insights = weeklyInsights)
            }
            composable(PAYWALL_ROUTE) {
                PaywallScreen(
                    productDetails = productDetails,
                    billingUnavailable = billingUnavailable,
                    onSubscribeClick = viewModel::purchasePro,
                    onRetryClick = viewModel::retryBillingConnection,
                    onContinueFreeClick = { navController.popBackStack() }
                )
            }
            composable(DEBUG_ROUTE) {
                NotificationFeedScreen(
                    notifications = notifications,
                    onCorrect = viewModel::correctClassification
                )
            }
            composable(SEARCH_ROUTE) {
                SearchScreen(
                    notifications = notifications,
                    onCorrect = viewModel::correctClassification,
                    isPro = isPro
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBarTitle(currentRoute: String?, currentTab: InboxTab?, onOpenDebug: () -> Unit) {
    val title = when {
        currentTab != null -> stringResource(currentTab.labelRes)
        currentRoute == SETTINGS_ROUTE -> stringResource(R.string.tab_settings)
        currentRoute == PAYWALL_ROUTE -> stringResource(R.string.title_upgrade_to_pro)
        currentRoute == DEBUG_ROUTE -> stringResource(R.string.title_debug_feed)
        currentRoute == SEARCH_ROUTE -> stringResource(R.string.action_search)
        currentRoute == CRASH_LOG_ROUTE -> stringResource(R.string.title_crash_log)
        currentRoute == VIP_SENDERS_ROUTE -> stringResource(R.string.title_vip_senders)
        currentRoute == WEEKLY_INSIGHTS_ROUTE -> stringResource(R.string.title_weekly_insights)
        else -> stringResource(R.string.app_name)
    }
    Text(
        text = title,
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            // Hidden dev-menu entry point for our own ongoing testing — see this file's doc.
            // Not discoverable from anywhere in the visible UI on purpose.
            onLongClick = onOpenDebug
        )
    )
}

private fun iconFor(tab: InboxTab): ImageVector = when (tab) {
    InboxTab.ACTION -> Icons.Filled.Warning
    InboxTab.WAITING -> Icons.Filled.Refresh
    InboxTab.DEADLINE -> Icons.Filled.DateRange
    InboxTab.REPLY -> Icons.Filled.Email
    InboxTab.OTHER -> Icons.AutoMirrored.Filled.List
}
