package com.futurepath.actionbox.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.ui.feed.NotificationFeedScreen
import com.futurepath.actionbox.ui.inbox.CategoryInboxScreen
import com.futurepath.actionbox.ui.inbox.InboxTab
import com.futurepath.actionbox.ui.settings.SettingsScreen
import com.futurepath.actionbox.viewmodel.NotificationViewModel

private const val SETTINGS_ROUTE = "settings"
private const val DEBUG_ROUTE = "debug"

/**
 * The app's primary navigation shell: bottom-nav tabs for the grouped inboxes (see [InboxTab]),
 * a settings screen reached from the top bar, and the old flat debug feed reached only by a
 * long-press on the top bar title — see that composable's doc for why it's kept but hidden
 * rather than deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: NotificationViewModel) {
    val navController = rememberNavController()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val itemsByCategory by viewModel.itemsByCategory.collectAsStateWithLifecycle()
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    val correctionLearningEnabled by viewModel.correctionLearningEnabled.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = InboxTab.entries.find { it.route == currentRoute }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle(currentRoute, currentTab, onOpenDebug = { navController.navigate(DEBUG_ROUTE) }) },
                actions = {
                    if (currentRoute != SETTINGS_ROUTE) {
                        IconButton(onClick = { navController.navigate(SETTINGS_ROUTE) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
                NavigationBar {
                    InboxTab.entries.forEach { tab ->
                        val count = tab.states.sumOf { state -> itemsByCategory[state]?.size ?: 0 }
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = {
                                if (tab != currentTab) {
                                    navController.navigate(tab.route) {
                                        // Standard bottom-nav behavior: switching tabs doesn't
                                        // pile up back-stack entries, and returning to a
                                        // previously-visited tab restores its scroll position.
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
                                    Icon(iconFor(tab), contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) }
                        )
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
                        onCorrect = viewModel::correctClassification
                    )
                }
            }
            composable(SETTINGS_ROUTE) {
                SettingsScreen(
                    isPro = isPro,
                    onProChange = viewModel::setPro,
                    correctionLearningEnabled = correctionLearningEnabled,
                    onCorrectionLearningChange = viewModel::setCorrectionLearningEnabled
                )
            }
            composable(DEBUG_ROUTE) {
                NotificationFeedScreen(
                    notifications = notifications,
                    onCorrect = viewModel::correctClassification
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBarTitle(currentRoute: String?, currentTab: InboxTab?, onOpenDebug: () -> Unit) {
    val title = when {
        currentTab != null -> currentTab.label
        currentRoute == SETTINGS_ROUTE -> "Settings"
        currentRoute == DEBUG_ROUTE -> "Debug Feed"
        else -> "ActionBox"
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
