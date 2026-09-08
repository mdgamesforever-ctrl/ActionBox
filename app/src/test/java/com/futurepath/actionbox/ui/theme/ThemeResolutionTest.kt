package com.futurepath.actionbox.ui.theme

import com.futurepath.actionbox.data.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves [resolveDarkTheme] — the decision [ActionBoxTheme] uses to pick light vs. dark —
 * actually branches on the live system-dark flag for [ThemeMode.SYSTEM] rather than being
 * hardcoded to one outcome, which is exactly the bug reported ("System behaves identically to
 * Dark"). A device/emulator can't be used to verify this in this environment, so this is the
 * closest thing to a direct, running proof rather than just re-reading the code.
 */
class ThemeResolutionTest {

    @Test
    fun `system mode follows the live system-dark flag both ways`() {
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
    }

    @Test
    fun `light mode always resolves to light regardless of the system flag`() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = true))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = false))
    }

    @Test
    fun `dark mode always resolves to dark regardless of the system flag`() {
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = true))
    }
}
