package com.futurepath.actionbox.classification

/** Result of classifying one captured notification. */
data class ClassificationResult(
    val state: ClassifiedState,
    val summary: String?,
    val date: String?,
    /** 0-100. See [NotificationClassifier.computeConfidence] for how it's derived. */
    val confidence: Int
)

/**
 * Rule-based (not ML) classification for v1. Each category scores independently against
 * its own pattern group(s), and the highest-scoring category wins — rather than the first
 * matching keyword short-circuiting the rest — so a notification carrying signals for
 * multiple categories is decided by which is actually strongest, not by check order.
 * Accuracy tuning and per-app parsing are later work.
 */
object NotificationClassifier {

    /**
     * @param learningBoosts Extra points added to each category's score before the winner is
     * picked, from [com.futurepath.actionbox.data.LearningPatternDao.strongCategoryFor] — the
     * local, on-device learning layer that biases classification toward a category this
     * sender/app/phrase has been consistently corrected to before. Empty by default so this
     * stays a pure function of its text inputs wherever no learning history applies (all
     * existing callers/tests included).
     *
     * This is the rule-engine-only decision (plus correction-learning boosts) — for the
     * Phase 5 hybrid decision that also folds in the on-device ML model's prediction, see
     * [HybridClassifier.classify], which calls [scoreCategories] and [resultFromScores]
     * directly rather than this function.
     */
    fun classify(
        sourceApp: String,
        sender: String,
        text: String,
        learningBoosts: Map<ClassifiedState, Int> = emptyMap()
    ): ClassificationResult {
        val scores = scoreCategories(sourceApp, sender, text, learningBoosts)
        return resultFromScores(scores.mapValues { it.value.toFloat() }, text, sender)
    }

    /**
     * The rule engine's per-category scores, with correction-learning boosts already folded
     * in — exposed (rather than just the winner+confidence [classify] derives from them) so
     * [HybridClassifier] can blend them with the on-device ML model's prediction before a
     * winner is picked. See [classify] for [learningBoosts].
     */
    internal fun scoreCategories(
        sourceApp: String,
        sender: String,
        text: String,
        learningBoosts: Map<ClassifiedState, Int> = emptyMap()
    ): Map<ClassifiedState, Int> {
        val lowerText = text.lowercase()
        val baseScores = mapOf(
            ClassifiedState.NOISE to scoreNoise(sourceApp, lowerText),
            ClassifiedState.FYI to score(lowerText, FYI_PATTERNS),
            ClassifiedState.DEADLINE to scoreDeadline(lowerText),
            ClassifiedState.ACTION to scoreAction(lowerText),
            ClassifiedState.WAITING to score(lowerText, WAITING_PATTERNS),
            ClassifiedState.REPLY to score(lowerText, REPLY_PATTERNS)
        )
        return baseScores.mapValues { (state, score) -> score + (learningBoosts[state] ?: 0) }
    }

    /**
     * Turns a per-category score map — [scoreCategories]'s rule-engine-only scores (promoted
     * to Float), or [HybridClassifier]'s blend of those with the ML model's prediction — into
     * a final decision: winner, confidence, and (for the actionable categories) an extracted
     * summary/date. Factored out of [classify] so both callers apply the exact same
     * tie-break/confidence/extraction rules no matter where the scores came from.
     */
    internal fun resultFromScores(scores: Map<ClassifiedState, Float>, text: String, sender: String): ClassificationResult {
        val topScore = scores.values.max()
        // Among categories tied for the top score, prefer the more actionable/urgent one —
        // a message that's plausibly both a deadline and an FYI is more useful surfaced as
        // a deadline. NOISE is checked first since a strong app-level signal should win
        // outright rather than lose a tie to a coincidental keyword elsewhere. When every
        // category scores 0, this arbitrarily lands on NOISE, but confidence will be 0 too
        // (below the LOW threshold), so the fallback below always overrides it to FYI.
        val winner = TIE_BREAK_ORDER.first { scores[it] == topScore }
        val runnerUpScore = scores.filterKeys { it != winner }.values.maxOrNull() ?: 0f
        val confidence = computeConfidence(topScore, runnerUpScore)

        val date = extractDate(text)
        val summary = when (winner) {
            ClassifiedState.ACTION, ClassifiedState.DEADLINE, ClassifiedState.WAITING ->
                extractSummary(text, sender)
            else -> null
        }

        // Only fall back to FYI when NOTHING scored at all — a genuine absence of signal.
        // A moderate/low confidence score from a close call between two real candidates
        // (e.g. WAITING vs. ACTION both firing) still keeps the higher-scoring pick: a near
        // tie between two plausible categories is meaningfully different from no evidence at
        // all, and silently overwriting a defensible answer with FYI just because two
        // categories were close discarded correct classifications in testing. The confidence
        // score is still reported as computed either way, so the UI can flag a low-confidence
        // pick as needing review without erasing what the classifier actually found.
        val finalState = if (topScore <= 0f) ClassifiedState.FYI else winner
        return ClassificationResult(finalState, summary, date, confidence)
    }

    /**
     * Confidence blends two things: the margin by which the winner beat the runner-up
     * (0 = a dead tie, 1 = the runner-up scored nothing at all) and the winner's absolute
     * score relative to [STRENGTH_CAP] (a lone weak match shouldn't score as confidently as
     * a cluster of strong ones, even with zero competition). Margin is weighted higher
     * since "is this actually the right category" matters more than "how much evidence."
     *
     * Operates on whatever scale it's handed — plain rule-engine scores from
     * [scoreCategories], or [HybridClassifier]'s blended scores (which can run up to
     * [HybridClassifier.ML_WEIGHT] points higher when the ML model reinforces an already
     * strong rule-engine pick — [STRENGTH_CAP] was raised from 8 to 9 for Phase 5 to keep
     * that higher ceiling from saturating the strength component too readily). When the ML
     * model instead disagrees with the rule engine, its contribution goes to a different
     * category, which narrows — not widens — the winner's margin over the runner-up, so a
     * genuine disagreement between the two signals correctly lowers confidence rather than
     * being invisible to it.
     */
    private fun computeConfidence(winnerScore: Float, runnerUpScore: Float): Int {
        if (winnerScore <= 0f) return 0
        val marginRatio = (winnerScore - runnerUpScore) / winnerScore
        val strengthRatio = (winnerScore / STRENGTH_CAP).coerceAtMost(1f)
        val raw = 100 * (MARGIN_WEIGHT * marginRatio + STRENGTH_WEIGHT * strengthRatio)
        return raw.toInt().coerceIn(0, 100)
    }

    private val TIE_BREAK_ORDER = listOf(
        ClassifiedState.NOISE,
        ClassifiedState.DEADLINE,
        ClassifiedState.ACTION,
        ClassifiedState.WAITING,
        ClassifiedState.FYI,
        ClassifiedState.REPLY
    )

    // ---- Per-category scoring ----------------------------------------------------------

    private fun scoreNoise(sourceApp: String, lowerText: String): Int {
        val packageScore = if (sourceApp in NOISE_PACKAGES) NOISE_PACKAGE_WEIGHT else 0
        return packageScore + score(lowerText, NOISE_PATTERNS)
    }

    private fun scoreDeadline(lowerText: String): Int {
        val dueMatches = DUE_PATTERNS.count { it.containsMatchIn(lowerText) } +
            (if (BY_DEADLINE_PATTERN.containsMatchIn(lowerText)) 1 else 0)
        val dateMatches = DEADLINE_DATE_PATTERNS.count { it.containsMatchIn(lowerText) }

        if (dueMatches == 0) {
            // A bare day name/relative phrase with no due/expiry language is a weak signal
            // on its own — ordinary sentences mention days constantly without implying a
            // deadline ("are you free on Tuesday?", "meeting moved to Tuesday", "booking
            // confirmed for Tuesday"). Score low enough that it can never tie with, let
            // alone beat, a specific phrase match belonging to another category.
            return dateMatches * STANDALONE_DATE_WEIGHT
        }

        var total = dueMatches * DUE_WEIGHT + dateMatches * DATE_WEIGHT
        if (dateMatches > 0) total += COMBO_BONUS
        return total
    }

    private fun scoreAction(lowerText: String): Int {
        var total = 0
        var verbHit = false
        for (verb in ACTION_VERBS) {
            // "check"/"confirm"/"call" also double as the head word of a more specific
            // REPLY/WAITING phrase ("i'll check", "can you confirm", "call me"). When that
            // phrase is present, the word is already claimed by the more specific category
            // and shouldn't also inflate ACTION's score for the same underlying mention.
            val suppressedBy = ACTION_VERB_SUPPRESSED_BY[verb.word]
            if (suppressedBy != null && suppressedBy.containsMatchIn(lowerText)) {
                continue
            }
            if (verb.bare.containsMatchIn(lowerText)) {
                verbHit = true
                total += ACTION_VERB_WEIGHT
                if (verb.directed.containsMatchIn(lowerText)) {
                    total += ACTION_DIRECTED_BONUS
                }
            }
        }
        if (verbHit && REQUEST_MARKERS.any { it.containsMatchIn(lowerText) }) {
            total += ACTION_REQUEST_MARKER_BONUS
        }
        return total
    }

    private fun score(text: String, patterns: List<Regex>): Int = patterns.count { it.containsMatchIn(text) } * DEFAULT_WEIGHT

    private fun extractDate(text: String): String? = EXTRACT_DATE_PATTERN.find(text)?.value

    /**
     * Picks the sentence/clause containing the strongest signal (a verb, request marker, or
     * date match) as a rough one-line summary, capped to a readable length. Falls back to
     * sender + a truncated excerpt when no clause with a clear signal is found.
     */
    private fun extractSummary(text: String, sender: String): String {
        val clauses = text.split(Regex("[.!?\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val candidate = clauses.firstOrNull { clause ->
            val lower = clause.lowercase()
            ACTION_VERBS.any { it.bare.containsMatchIn(lower) } ||
                REQUEST_MARKERS.any { it.containsMatchIn(lower) } ||
                WAITING_PATTERNS.any { it.containsMatchIn(lower) } ||
                DUE_PATTERNS.any { it.containsMatchIn(lower) } ||
                BY_DEADLINE_PATTERN.containsMatchIn(lower) ||
                DEADLINE_DATE_PATTERNS.any { it.containsMatchIn(lower) }
        } ?: clauses.firstOrNull()

        val base = candidate ?: (if (sender.isNotBlank()) "$sender: $text" else text)
        val trimmed = base.trim()
        return if (trimmed.length > 80) trimmed.take(77).trimEnd() + "…" else trimmed
    }

    // ---- Pattern groups -------------------------------------------------------------------

    private const val DEFAULT_WEIGHT = 2
    private const val NOISE_PACKAGE_WEIGHT = 10
    private const val DUE_WEIGHT = 2
    private const val DATE_WEIGHT = 2
    private const val STANDALONE_DATE_WEIGHT = 1
    private const val COMBO_BONUS = 3
    private const val ACTION_VERB_WEIGHT = 2
    private const val ACTION_DIRECTED_BONUS = 1
    private const val ACTION_REQUEST_MARKER_BONUS = 2
    private const val STRENGTH_CAP = 9f // was 8 pre-Phase 5; see computeConfidence's doc
    private const val MARGIN_WEIGHT = 0.6f
    private const val STRENGTH_WEIGHT = 0.4f

    private fun phrases(vararg raw: String): List<Regex> = raw.map { Regex("\\b${Regex.escape(it)}\\b") }

    private val NOISE_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.zhiliaoapp.musically", // TikTok
        "com.twitter.android",
        "com.facebook.katana", // Facebook feed app, distinct from com.facebook.orca (Messenger)
        "com.pinterest",
        "com.snapchat.android"
    )

    private val NOISE_PATTERNS = phrases(
        "new video", "recommended for you", "sale", "promotion", "trending", "suggested post",
        "new follower", "daily reward",
        "liked your", "started following you", "posted a new", "limited time", "flash sale",
        "new post from", "commented on your"
    )

    private val FYI_PATTERNS = phrases(
        "has shipped", "has been delivered", "out for delivery", "order confirmed",
        "payment received", "transaction completed", "meeting moved", "meeting rescheduled",
        "flight landed", "package arrived", "backup completed",
        "payment successful", "your receipt", "verification code", "otp", "confirmation number",
        "booking confirmed", "successfully completed"
    )

    private val DUE_PATTERNS = phrases(
        "due", "expires", "expiring", "deadline", "before",
        "last day", "final date", "closing date", "cutoff", "renewal", "ends on"
    )

    // "by" alone is too generic (fires on "by tomorrow morning", "by the way", etc.) — only
    // counts as a deadline signal when it actually precedes a day/date/time.
    private val BY_DEADLINE_PATTERN = Regex(
        "\\bby\\s+(?:the\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "today|tomorrow|tonight|end of month|end of week|next week|" +
            "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "\\d{1,2}(?::\\d{2})?\\s?(?:am|pm)|\\d{1,2}/\\d{1,2})",
        RegexOption.IGNORE_CASE
    )

    // Day names and relative time phrases count as deadline signals on their own, per spec,
    // even without an accompanying due/expiry word.
    private val DEADLINE_DATE_PATTERNS = phrases(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "end of month", "end of week", "next week"
    )

    private data class ActionVerb(val word: String, val bare: Regex, val directed: Regex)

    private val ACTION_VERBS = listOf(
        "send", "bring", "upload", "finish", "buy", "pay", "call", "pick up", "check",
        "submit", "sign", "review", "forward", "complete", "book", "cancel", "confirm"
    ).map { verb ->
        val escaped = Regex.escape(verb)
        ActionVerb(
            word = verb,
            bare = Regex("\\b$escaped\\b"),
            // e.g. "send this", "call me", "pick up that" — verb directed at me/this/that
            // within a couple of words.
            directed = Regex("\\b$escaped\\b(?:\\s+\\w+){0,2}\\s+(me|this|that)\\b")
        )
    }

    // See the suppression check in scoreAction(): these verbs double as the head word of a
    // more specific REPLY/WAITING phrase, so that phrase already "owns" the word.
    private val ACTION_VERB_SUPPRESSED_BY: Map<String, Regex> = mapOf(
        "call" to Regex("\\bcall me\\b"),
        "check" to Regex("\\bi'll check\\b"),
        // Only suppress when confirming something about the recipient themselves (receipt,
        // attendance, agreement — "confirm you received/got/are coming"), which is a REPLY-
        // style acknowledgment request. "Confirm the details/report/numbers" is confirming
        // a deliverable, a genuine ACTION request, and must NOT be suppressed — narrowed
        // after the broader "any 'can you confirm'" version incorrectly pulled those into
        // REPLY.
        "confirm" to Regex("\\bcan you confirm you\\b"),
        "bring" to Regex("\\bi(?:'ll| will) bring\\b")
    )

    private val REQUEST_MARKERS = phrases("can you", "could you", "would you", "need you to")

    private val WAITING_PATTERNS = phrases(
        "i'll send", "i will", "i'll check", "i'll get back to you",
        // Generalized from the object-literal "expect it"/"i'll bring it", which only
        // matched when the object was literally "it" and missed "expect the file"/"i'll
        // bring the presentation" — these match the verb+commitment structure regardless
        // of what's being expected/brought, consistent with how "i'll send"/"i'll check"
        // already don't require a specific object.
        "should expect", "i'll bring",
        "should arrive", "we're working on it", "i'm working on", "i am working on",
        "on it", "will do", "will send", "will get back", "will reply"
    )

    private val REPLY_PATTERNS = phrases(
        "let me know", "lmk", "tell me", "get back to me", "call me", "text me", "hmu",
        "hit me up", "what do you think", "can you confirm", "keep me posted", "are you free",
        "when are you free"
    )

    // Broader than DEADLINE_DATE_PATTERNS — used only to populate extractedDate, not scoring.
    private val EXTRACT_DATE_PATTERN = Regex(
        "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tomorrow|tonight|" +
            "end of month|end of week|next week|" +
            "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|\\d{1,2}(?::\\d{2})?\\s?(?:am|pm))\\b",
        RegexOption.IGNORE_CASE
    )
}
