package com.futurepath.actionbox.ui.inbox

import androidx.annotation.StringRes
import com.futurepath.actionbox.R
import com.futurepath.actionbox.classification.ClassifiedState

/**
 * The user-facing grouping of [ClassifiedState] into bottom-navigation destinations. ACTION/
 * WAITING/DEADLINE/REPLY each get their own tab since those are the categories worth a
 * dedicated glance; FYI and NOISE are collapsed into one lower-priority "Other" tab — still
 * fully accessible, just not competing for top-level attention the way an actionable item does.
 *
 * [labelRes] is a string resource (not a raw String) so every call site renders it through
 * [androidx.compose.ui.res.stringResource] and picks up the app's selected UI language — see
 * Settings -> Appearance -> Language.
 */
enum class InboxTab(val route: String, @StringRes val labelRes: Int, val states: Set<ClassifiedState>) {
    ACTION("inbox/action", R.string.tab_action, setOf(ClassifiedState.ACTION)),
    WAITING("inbox/waiting", R.string.tab_waiting, setOf(ClassifiedState.WAITING)),
    DEADLINE("inbox/deadline", R.string.tab_deadline, setOf(ClassifiedState.DEADLINE)),
    REPLY("inbox/reply", R.string.tab_reply, setOf(ClassifiedState.REPLY)),
    OTHER("inbox/other", R.string.tab_other, setOf(ClassifiedState.FYI, ClassifiedState.NOISE));

    companion object {
        val DEFAULT = ACTION
    }
}
