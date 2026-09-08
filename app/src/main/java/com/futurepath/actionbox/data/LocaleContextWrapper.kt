package com.futurepath.actionbox.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Wraps [base] so its `Resources` resolve strings against [language]'s locale, regardless of the
 * device's actual system language — the manual, pre-AppCompatDelegate Context-override pattern,
 * used here specifically because it doesn't depend on any interaction between AppCompatDelegate
 * and a plain (non-AppCompatActivity) Activity that can't be directly verified by reading the
 * code (see [AppLanguage]'s class doc for the full story). Called from `attachBaseContext` —
 * the platform calls that synchronously before `onCreate`/`setContent`, which is what makes this
 * the actual "apply before any UI renders" point, not a convenience.
 *
 * [AppLanguage.SYSTEM_DEFAULT] (`tag == null`) returns [base] unchanged: "follow the system
 * language" means not overriding anything, so the device's own current locale — whatever it is,
 * including if the user changes it later — keeps being what resources resolve against.
 */
fun localeAwareContext(base: Context, language: AppLanguage): Context {
    val tag = language.tag ?: return base
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(locale)
    return base.createConfigurationContext(configuration)
}
