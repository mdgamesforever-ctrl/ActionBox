package com.futurepath.actionbox.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves [resolveAppLanguage] — the decision [SettingsRepository.appLanguage] uses to turn a
 * stored preference-file value back into an [AppLanguage] — resolves every real enum name
 * correctly and falls back to [AppLanguage.SYSTEM_DEFAULT] for a missing or corrupted value, the
 * same fallback contract [SettingsRepository.themeMode] has for [ThemeMode]. Can't unit-test the
 * DataStore read/write itself without a Context, but this is the actual decision logic behind it.
 */
class AppLanguageTest {

    @Test
    fun `a null stored value resolves to system default`() {
        assertEquals(AppLanguage.SYSTEM_DEFAULT, resolveAppLanguage(null))
    }

    @Test
    fun `an unrecognized stored value falls back to system default rather than crashing`() {
        assertEquals(AppLanguage.SYSTEM_DEFAULT, resolveAppLanguage("NOT_A_REAL_LANGUAGE"))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, resolveAppLanguage(""))
        // A removed enum entry (e.g. HINDI, dropped from this app in favor of ITALIAN) must
        // fall back cleanly rather than crash a device that has it stored from an older version.
        assertEquals(AppLanguage.SYSTEM_DEFAULT, resolveAppLanguage("HINDI"))
    }

    @Test
    fun `every real enum name round-trips back to itself`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, resolveAppLanguage(language.name))
        }
    }
}
