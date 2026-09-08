package com.futurepath.actionbox.ui.components

import androidx.annotation.StringRes
import com.futurepath.actionbox.R
import com.futurepath.actionbox.classification.ClassifiedState

/**
 * The localized display name for a category — every place that used to show [ClassifiedState]'s
 * raw enum name (always English, all-caps: "ACTION", "REPLY"...) now goes through this instead,
 * so the category badge, the correction picker, and the weekly insights breakdown all render in
 * the app's selected UI language (see Settings -> Appearance -> Language) instead of a hardcoded
 * English constant.
 */
@StringRes
fun ClassifiedState.labelRes(): Int = when (this) {
    ClassifiedState.ACTION -> R.string.category_action
    ClassifiedState.REPLY -> R.string.category_reply
    ClassifiedState.WAITING -> R.string.category_waiting
    ClassifiedState.DEADLINE -> R.string.category_deadline
    ClassifiedState.FYI -> R.string.category_fyi
    ClassifiedState.NOISE -> R.string.category_noise
}
