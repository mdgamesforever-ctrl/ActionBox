package com.futurepath.actionbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.futurepath.actionbox.data.ThemeMode

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/**
 * Pure decision logic pulled out of [ActionBoxTheme] so it's directly unit-testable
 * (ThemeResolutionTest) without a device/emulator — the one thing actually worth proving here is
 * that [ThemeMode.SYSTEM] really does branch on [systemInDarkTheme] rather than being hardcoded
 * to either outcome, which a Composable function's body alone can't be asserted against in a
 * plain JVM test.
 */
internal fun resolveDarkTheme(themeMode: ThemeMode, systemInDarkTheme: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * [themeMode] defaults to [ThemeMode.SYSTEM] (follows [isSystemInDarkTheme]) so every existing
 * call site that doesn't care about the user's appearance preference keeps working unchanged;
 * [com.futurepath.actionbox.MainActivity] is the one caller that reads the real stored
 * preference (via [com.futurepath.actionbox.viewmodel.NotificationViewModel.themeMode]) and
 * passes it explicitly, which is what lets Light/Dark override the system setting instead of
 * only ever following it.
 */
@Composable
fun ActionBoxTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = resolveDarkTheme(themeMode, isSystemInDarkTheme())
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
