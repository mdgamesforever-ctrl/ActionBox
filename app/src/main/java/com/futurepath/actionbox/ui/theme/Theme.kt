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
 * [themeMode] defaults to [ThemeMode.SYSTEM] (follows [isSystemInDarkTheme]) so every existing
 * call site that doesn't care about the user's appearance preference keeps working unchanged;
 * [com.futurepath.actionbox.MainActivity] is the one caller that reads the real stored
 * preference (via [com.futurepath.actionbox.viewmodel.NotificationViewModel.themeMode]) and
 * passes it explicitly, which is what lets Light/Dark override the system setting instead of
 * only ever following it.
 */
@Composable
fun ActionBoxTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
