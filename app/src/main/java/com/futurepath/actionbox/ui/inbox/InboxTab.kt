package com.futurepath.actionbox.ui.inbox

import com.futurepath.actionbox.classification.ClassifiedState

/**
 * The user-facing grouping of [ClassifiedState] into bottom-navigation destinations. ACTION/
 * WAITING/DEADLINE/REPLY each get their own tab since those are the categories worth a
 * dedicated glance; FYI and NOISE are collapsed into one lower-priority "Other" tab — still
 * fully accessible, just not competing for top-level attention the way an actionable item does.
 */
enum class InboxTab(val route: String, val label: String, val states: Set<ClassifiedState>) {
    ACTION("inbox/action", "Action", setOf(ClassifiedState.ACTION)),
    WAITING("inbox/waiting", "Waiting", setOf(ClassifiedState.WAITING)),
    DEADLINE("inbox/deadline", "Deadline", setOf(ClassifiedState.DEADLINE)),
    REPLY("inbox/reply", "Reply", setOf(ClassifiedState.REPLY)),
    OTHER("inbox/other", "Other", setOf(ClassifiedState.FYI, ClassifiedState.NOISE));

    companion object {
        val DEFAULT = ACTION
    }
}
