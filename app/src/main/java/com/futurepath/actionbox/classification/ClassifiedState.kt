package com.futurepath.actionbox.classification

/**
 * The state a captured notification is sorted into. v1 is rule-based (see
 * [NotificationClassifier]) — accuracy tuning and per-app parsing are later work.
 */
enum class ClassifiedState {
    /** Someone is asking the user to do something ("can you send...", "please..."). */
    ACTION,
    /** A direct message with no clear action or deadline — the default for ambiguous cases. */
    REPLY,
    /** The sender committed to doing something themselves ("I'll send...", "on it"). */
    WAITING,
    /** A date/time reference combined with due/expiry language ("due Friday", "expires..."). */
    DEADLINE,
    /** Informational, no response needed (delivery updates, confirmations, receipts). */
    FYI,
    /** Promotional/marketing/social/app-generated noise. */
    NOISE
}
