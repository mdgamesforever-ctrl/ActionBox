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
        // A message addressed to a public/group audience (a subreddit post, a Discord
        // server channel) rather than to the recipient specifically — "what do you guys
        // think" reads exactly like a personal REPLY-inviting question in isolation, but
        // nobody individually owes a reply to a public post. See isPublicBroadcastContext's
        // doc for exactly what's detected and why it's package/sender-based rather than a
        // generic text heuristic.
        val isPublicContext = isPublicBroadcastContext(sourceApp, sender)

        // Package-identity priors for FYI/DEADLINE: a calendar app's notifications are
        // essentially never ACTION/WAITING/REPLY/NOISE, but word-based scoring alone can't
        // always tell a status update ("meeting moved") from an actual deadline ("meeting in
        // 10 minutes") — this nudges BOTH candidates so whichever already has word-based
        // support (see scoreDeadline/FYI_PATTERNS) wins the internal split, rather than trying
        // to duplicate that decision here. A banking transaction template (see
        // isBankingTransactionTemplate) is a stronger, mutually-exclusive signal that routes to
        // exactly one of the two depending on whether due-language is present, so it's computed
        // separately below rather than folded into this generic calendar-style split.
        val isCalendarPackage = sourceApp in CALENDAR_PACKAGES
        val calendarBonus = if (isCalendarPackage) CALENDAR_PACKAGE_WEIGHT else 0

        val bankingTemplateHit = isBankingTransactionTemplate(lowerText)
        val bankingTemplateHasDueLanguage = bankingTemplateHit &&
            (DUE_PATTERNS.any { it.containsMatchIn(lowerText) } || BY_DEADLINE_PATTERN.containsMatchIn(lowerText))
        val bankingFyiBonus = if (bankingTemplateHit && !bankingTemplateHasDueLanguage) BANKING_TEMPLATE_WEIGHT else 0
        val bankingDeadlineBonus = if (bankingTemplateHasDueLanguage) BANKING_TEMPLATE_WEIGHT else 0

        val deliveryTemplateBonus = if (isDeliveryStatusTemplate(sender, lowerText)) DELIVERY_TEMPLATE_WEIGHT else 0

        val baseScores = mapOf(
            ClassifiedState.NOISE to scoreNoise(sourceApp, sender, lowerText, isPublicContext),
            ClassifiedState.FYI to score(lowerText, FYI_PATTERNS) + calendarBonus + bankingFyiBonus + deliveryTemplateBonus,
            ClassifiedState.DEADLINE to scoreDeadline(lowerText) + calendarBonus + bankingDeadlineBonus,
            ClassifiedState.ACTION to dampenInPublicContext(scoreAction(lowerText), isPublicContext),
            ClassifiedState.WAITING to score(lowerText, WAITING_PATTERNS),
            ClassifiedState.REPLY to dampenInPublicContext(score(lowerText, REPLY_PATTERNS), isPublicContext)
        )

        // A bare day-name mention with no due/expiry language (see scoreDeadline) is weak
        // enough that it should never be the SOLE basis for classifying a notification as a
        // deadline — real-device testing turned up purely reflective/casual social posts that
        // happen to mention day names ("Sunday in Amman hits different... pretending Monday
        // doesn't exist") with every other category scoring exactly 0, which let this weak
        // signal win by default even though it was never meant to be decisive on its own (see
        // scoreDeadline's own comment). When nothing else has any signal either, this treats
        // that the same as no signal at all — resultFromScores's topScore<=0 check then falls
        // back to FYI, same as if the day name weren't mentioned.
        val adjustedScores = if (isWeakStandaloneDeadlineOnly(lowerText, baseScores)) {
            baseScores + (ClassifiedState.DEADLINE to 0)
        } else {
            baseScores
        }

        return adjustedScores.mapValues { (state, score) -> score + (learningBoosts[state] ?: 0) }
    }

    /**
     * In a public context, an ACTION/REPLY-shaped phrase still isn't a request directed at the
     * recipient personally — dented rather than zeroed, since a public post can still carry
     * enough other signal (e.g. NOISE's own patterns) to win regardless, and a light dent
     * keeps this a bias rather than a hard override (contrast [BOT_CONTENT_NOISE_WEIGHT],
     * which IS meant to override regardless of phrasing for automated content).
     */
    private fun dampenInPublicContext(score: Int, isPublicContext: Boolean): Int =
        if (isPublicContext) (score - PUBLIC_CONTEXT_PERSONAL_PENALTY).coerceAtLeast(0) else score

    /**
     * True when [sourceApp] is an inherently public/community platform (every notification is
     * a broadcast to an audience, never a 1:1 message — Reddit chief among them), or when
     * [sender] carries a channel-name marker ("#general", "#raid-timers") the way Discord/
     * Slack-style apps format a server-channel message's title. Discord's own package isn't
     * listed here since it carries both personal DMs and public channel messages through the
     * same app — the "#" marker in the title is what actually distinguishes them, and DMs
     * don't carry one.
     */
    private fun isPublicBroadcastContext(sourceApp: String, sender: String): Boolean =
        sourceApp in PUBLIC_BROADCAST_PACKAGES || sender.contains("#")

    /**
     * True when the DEADLINE score came ONLY from [scoreDeadline]'s weak standalone-day-name
     * path (no due/expiry word present) AND every other category scored exactly 0 — the
     * specific combination that let a casual day-name mention win by default. A genuine due/
     * expiry word makes this a real signal regardless of what else fired; and if any OTHER
     * category also scored something, the normal scoring/tie-break already handles it (see
     * scoreDeadline and Phase 2's original day-name fix).
     */
    private fun isWeakStandaloneDeadlineOnly(lowerText: String, baseScores: Map<ClassifiedState, Int>): Boolean {
        val deadlineScore = baseScores[ClassifiedState.DEADLINE] ?: 0
        if (deadlineScore <= 0) return false
        val hasDueLanguage = DUE_PATTERNS.any { it.containsMatchIn(lowerText) } ||
            BY_DEADLINE_PATTERN.containsMatchIn(lowerText)
        if (hasDueLanguage) return false
        return baseScores.filterKeys { it != ClassifiedState.DEADLINE }.values.all { it == 0 }
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

    private fun scoreNoise(sourceApp: String, sender: String, lowerText: String, isPublicContext: Boolean): Int {
        val packageScore = if (sourceApp in NOISE_PACKAGES) NOISE_PACKAGE_WEIGHT else 0
        // Known gaming-app packages default hard toward NOISE: daily-reward/event notifications
        // ("Claim your daily reward!", "Event ends in 3 hours!") linguistically mimic ACTION's
        // imperatives and DEADLINE's countdown language worse than ordinary promo text does, so
        // word-based scoring alone is the LEAST reliable place to catch these — the app's own
        // identity is the stronger signal here. Weighted just under NOISE_PACKAGE_WEIGHT (not
        // equal to it) so a genuinely strong word-based signal can still win a real edge case.
        val gamingPackageScore = if (sourceApp in GAMING_PACKAGES) GAMING_PACKAGE_NOISE_WEIGHT else 0
        // Automated bot/broadcast content (a Discord raid-reminder bot, a giveaway-announcement
        // bot) should read as NOISE regardless of its surface phrasing — a bot's giveaway rules
        // text can look WAITING-ish, a raid-timer roster dump can look DEADLINE-ish, but neither
        // is a message from a person. Weighted like NOISE_PACKAGE_WEIGHT (strong enough to win
        // outright) rather than the lighter public-context dent below, since "obviously
        // automated" is a much stronger signal than "merely public" — a human's public Reddit
        // post still deserves its own category, an automated bot dump generally doesn't.
        val botScore = if (isBotOrMassMentionContent(sender, lowerText)) BOT_CONTENT_NOISE_WEIGHT else 0
        // Catches an UNNAMED bot/webhook by its formatting shape rather than its sender name —
        // see isBotFormattedContent's doc. A real signal but a softer one than a literal "bot"
        // sender/mass-mention match above (inferred from format rather than stated outright),
        // so it's weighted lower to stay beatable by a strong word-based signal in a genuine
        // edge case, consistent with every other new structural signal in this function.
        val botFormatScore = if (isBotFormattedContent(lowerText)) BOT_FORMAT_NOISE_WEIGHT else 0
        // The sender IS the app/brand itself (an Instagram engagement ping, not a friend's DM)
        // rather than a human contact — see isImpersonalSender's doc.
        val impersonalSenderScore = if (isImpersonalSender(sourceApp, sender)) IMPERSONAL_SENDER_NOISE_WEIGHT else 0
        // A noreply/do-not-reply address marks a fully automated sender, the same underlying
        // idea as a "bot"-named sender or a mass mention — but deliberately NOT folded into
        // isBotOrMassMentionContent's hard-override weight: unlike a Discord bot dump (almost
        // always genuine noise/broadcast), a noreply@ address routinely sends perfectly
        // legitimate FYI/DEADLINE content — bank transaction alerts, delivery confirmations,
        // calendar invites are commonly sent "noreply" too. Overriding those outright would
        // fight directly against the banking/delivery/calendar signals above, which exist
        // specifically to route this kind of automated-but-legitimate content correctly. Kept
        // at the same weight as the impersonal-sender bias instead, so it nudges rather than
        // drowns out a real FYI/DEADLINE signal on the same message.
        val noreplyScore = if (NOREPLY_EMAIL_PATTERN.containsMatchIn(sender)) IMPERSONAL_SENDER_NOISE_WEIGHT else 0
        // A public post/broadcast that doesn't otherwise match a specific NOISE phrase is
        // still more likely informational/promotional than a personal request — a light bias,
        // not a hard override (see dampenInPublicContext's doc for the contrast with bots).
        val publicContextScore = if (isPublicContext) PUBLIC_CONTEXT_NOISE_BOOST else 0
        return packageScore + gamingPackageScore + botScore + botFormatScore + impersonalSenderScore +
            noreplyScore + publicContextScore + score(lowerText, NOISE_PATTERNS)
    }

    private fun isBotOrMassMentionContent(sender: String, lowerText: String): Boolean =
        BOT_SENDER_PATTERN.containsMatchIn(sender) || MASS_MENTION_PATTERN.containsMatchIn(lowerText)

    /**
     * True when [sender] textually IS the app's own brand (e.g. sourceApp is Instagram and
     * sender is "Instagram") rather than a human contact — the generalizable version of "this
     * notification is the app pinging you" that applies across social engagement pings, some
     * delivery/banking status senders, and beyond. A word-boundary match against a small,
     * curated per-package name list (not a generic "looks corporate" heuristic) to keep false
     * positives near zero — a friend's contact name essentially never collides with an app's
     * own brand name.
     */
    private fun isImpersonalSender(sourceApp: String, sender: String): Boolean {
        val brandNames = APP_OWN_SENDER_NAMES[sourceApp] ?: return false
        return brandNames.any { brand -> Regex("\\b${Regex.escape(brand)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(sender) }
    }

    /**
     * Detects an automated Discord-style bot/webhook dump by its FORMATTING SHAPE rather than
     * its sender name, so an unnamed bot ("Server Assistant", not "RaidBot") is still caught.
     * Requires at least two of: high @mention density, emoji used as a visual header/bullet,
     * and colon-delimited stat/field pairs ("Kills: 12", "Time Left: 2h") — any ONE of these
     * alone is something a normal enthusiastic human message could plausibly contain, but the
     * combination is a genuine template fingerprint. Deliberately doesn't attempt to detect
     * "near-identical structure repeated across messages from the same sender" — this function
     * is a pure function of a single notification's text, with no access to the sender's prior
     * message history, so that part of the fingerprint is out of scope here.
     */
    private fun isBotFormattedContent(lowerText: String): Boolean {
        val highMentionDensity = MENTION_PATTERN.findAll(lowerText).count() >= 2
        val hasEmojiHeader = EMOJI_PATTERN.containsMatchIn(lowerText)
        val hasStatPairs = STAT_PAIR_PATTERN.findAll(lowerText).count() >= 2
        return listOf(highMentionDensity, hasEmojiHeader, hasStatPairs).count { it } >= 2
    }

    /**
     * A masked account number + a currency amount + a "balance" keyword together are close to
     * a deterministic fingerprint of a bank transaction alert ("$500 debited from A/c ****1234,
     * Avail Bal: $2,300") — real bank SMS/push templates are this formulaic. Requires all
     * three since any one alone is common in unrelated text (e.g. "balance" in "work-life
     * balance"); the combination essentially only occurs in this exact template.
     */
    private fun isBankingTransactionTemplate(lowerText: String): Boolean =
        MASKED_ACCOUNT_PATTERN.containsMatchIn(lowerText) &&
            CURRENCY_AMOUNT_PATTERN.containsMatchIn(lowerText) &&
            BALANCE_KEYWORD_PATTERN.containsMatchIn(lowerText)

    /**
     * A delivery/shipping status update's structural fingerprint: an order/tracking number, a
     * known carrier/retailer as the sender, and a past-tense completed-status verb. Requires
     * only two of the three (not all three) — real notifications routinely drop one, e.g.
     * "Package delivered." from sender "Amazon" has no order number at all, which is exactly
     * the real-device case this generalizes beyond (previously only caught by the literal
     * phrase "package delivered" in FYI_PATTERNS).
     */
    private fun isDeliveryStatusTemplate(sender: String, lowerText: String): Boolean {
        val hasOrderNumber = ORDER_NUMBER_PATTERN.containsMatchIn(lowerText)
        val hasBrandSender = DELIVERY_BRAND_SENDER_PATTERN.containsMatchIn(sender)
        val hasStatusVerb = DELIVERY_STATUS_VERB_PATTERN.containsMatchIn(lowerText)
        return listOf(hasOrderNumber, hasBrandSender, hasStatusVerb).count { it } >= 2
    }

    private fun scoreDeadline(lowerText: String): Int {
        // "should arrive by tonight" / "expect it by 5pm" — a WAITING delivery-commitment
        // phrase describing WHEN something will show up isn't a deadline the recipient must
        // act by. Without this, "by <time>" ties DEADLINE against WAITING's own score and
        // DEADLINE wins the tie (see TIE_BREAK_ORDER) — this only suppresses the generic "by
        // <time>" trigger, not an explicit due/expiry word, so "should arrive by the
        // deadline" still correctly scores DEADLINE via DUE_PATTERNS.
        val deliveryCommitment = DEADLINE_SUPPRESSED_BY_DELIVERY_COMMITMENT.containsMatchIn(lowerText)
        val byDeadlineHit = !deliveryCommitment && BY_DEADLINE_PATTERN.containsMatchIn(lowerText)
        // Tracked separately from byDeadlineHit: an explicit due/expiry WORD ("due", "cutoff",
        // "expires"...) is a much stronger, less generic signal than the bare "by <time>"
        // trigger — see below for why that distinction matters for the combo-date broadening.
        val explicitDueWordCount = DUE_PATTERNS.count { it.containsMatchIn(lowerText) }
        val dueMatches = explicitDueWordCount + (if (byDeadlineHit) 1 else 0)

        if (dueMatches == 0) {
            // A bare day name/relative phrase with no due/expiry language is a weak signal
            // on its own — ordinary sentences mention days constantly without implying a
            // deadline ("are you free on Tuesday?", "meeting moved to Tuesday", "booking
            // confirmed for Tuesday"). Score low enough that it can never tie with, let alone
            // beat, a specific phrase match belonging to another category — and see
            // scoreCategories' isWeakStandaloneDeadlineOnly for why it also can't win purely
            // by default when nothing else scores anything either. Deliberately the NARROW
            // pattern set (day names + a few relative phrases), not DEADLINE_COMBO_DATE_PATTERNS'
            // broader one below — "today"/"tomorrow"/time-of-day are far too common in ordinary
            // non-deadline sentences to trust as a standalone trigger on their own.
            val dateMatches = DEADLINE_STANDALONE_DATE_PATTERNS.count { it.containsMatchIn(lowerText) }
            return dateMatches * STANDALONE_DATE_WEIGHT
        }

        // The broader combo date-pattern set (calendar dates, "today"/"tomorrow"/"tonight", a
        // bare time of day) only strengthens the score when a genuinely STRONG due/expiry
        // word backs it up — e.g. "payment due Sep 12" or "cutoff is 5pm today" — NOT when
        // dueMatches is nonzero purely from "by <time>" or the equally-generic "before"
        // (STRONG_DUE_PATTERNS excludes it for exactly this reason). "by 9am"/"before 9am"
        // already form part of the accepted ACTION/DEADLINE overlap (e.g. "complete the form
        // by 9am" is a legitimate ACTION request), and crediting a bare time-of-day there in
        // addition to what "by"/"before" already contribute tipped several such cases from
        // ACTION to DEADLINE that previously resolved correctly — this keeps that widening
        // from happening while still fixing the systematically-low confidence on genuine,
        // strong-due-word-backed deadlines (see EXTRACT_DATE_PATTERN, which already
        // recognized these for display; scoring previously didn't credit them at all).
        val hasStrongDueWord = STRONG_DUE_PATTERNS.any { it.containsMatchIn(lowerText) }
        val dateMatches = if (hasStrongDueWord) {
            DEADLINE_COMBO_DATE_PATTERNS.count { it.containsMatchIn(lowerText) }
        } else {
            DEADLINE_STANDALONE_DATE_PATTERNS.count { it.containsMatchIn(lowerText) }
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
                DEADLINE_COMBO_DATE_PATTERNS.any { it.containsMatchIn(lower) }
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
    // Sized like NOISE_PACKAGE_WEIGHT: automated/bot broadcast content should win outright
    // regardless of surface phrasing, the same way a known noise-app package does.
    private const val BOT_CONTENT_NOISE_WEIGHT = 10
    // A light bias, not a hard override — see dampenInPublicContext's doc.
    private const val PUBLIC_CONTEXT_NOISE_BOOST = 2
    private const val PUBLIC_CONTEXT_PERSONAL_PENALTY = 3
    // Structural-signal weights (metadata/format-based priors rather than word content) — see
    // each is-check's doc for why it's calibrated where it is. All are sized to beat the
    // ordinary word-based scores they're meant to override in typical cases (DEFAULT_WEIGHT=2,
    // ACTION_VERB_WEIGHT=2, DUE_WEIGHT/DATE_WEIGHT=2) while still being beatable by a
    // genuinely strong multi-signal word-based cluster, per the "prior, not hard override"
    // requirement — deliberately kept below BOT_CONTENT_NOISE_WEIGHT/NOISE_PACKAGE_WEIGHT
    // (10), which are the one pre-existing pair of signals meant to win outright.
    private const val IMPERSONAL_SENDER_NOISE_WEIGHT = 3
    private const val BOT_FORMAT_NOISE_WEIGHT = 6
    private const val GAMING_PACKAGE_NOISE_WEIGHT = 8
    private const val CALENDAR_PACKAGE_WEIGHT = 2
    private const val BANKING_TEMPLATE_WEIGHT = 6
    private const val DELIVERY_TEMPLATE_WEIGHT = 5

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

    // See isPublicBroadcastContext's doc: apps where every notification is a broadcast to an
    // audience, never a 1:1 message. Reddit doesn't carry personal DMs through the same
    // notification surface the way Discord/Slack do, so its package alone is a safe signal —
    // Discord is intentionally NOT listed here (see that function's doc).
    private val PUBLIC_BROADCAST_PACKAGES = setOf(
        "com.reddit.frontpage"
    )

    // A sender/title containing "bot" (case-insensitive) — common Discord/Slack automated-
    // account naming ("RaidBot", "GiveawayBot", "MEE6 Bot"). Doesn't catch every bot (some
    // have no "bot" in the name at all), but it's the specific, low-false-positive signal a
    // real person's display name essentially never collides with.
    private val BOT_SENDER_PATTERN = Regex("bot", RegexOption.IGNORE_CASE)

    // A mass ping ("@everyone", "@here") is addressed to an entire server/channel, never to
    // the recipient individually — a strong automated/broadcast signal regardless of sender.
    private val MASS_MENTION_PATTERN = Regex("@(?:everyone|here)\\b", RegexOption.IGNORE_CASE)

    // A noreply/do-not-reply address is a well-known convention for a fully automated sender —
    // see scoreNoise's noreplyScore for why it's a moderate bias rather than folded into
    // isBotOrMassMentionContent's stronger override.
    private val NOREPLY_EMAIL_PATTERN = Regex("\\b(?:no-?reply|do-?not-?reply)@", RegexOption.IGNORE_CASE)

    // Any @mention-shaped token, not just the mass-ping forms above — used only to gauge
    // mention DENSITY for isBotFormattedContent, not as a standalone signal on its own.
    private val MENTION_PATTERN = Regex("@[\\w-]+")

    // A visual-header/bullet emoji range covering the common Discord bot-embed decorations
    // (🎉📢⏰🔥) — see isBotFormattedContent.
    private val EMOJI_PATTERN = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]")

    // "Kills: 12", "Time Left: 2h" — a colon-delimited label/number pair, the shape of a bot's
    // stat tracker or field list. Used only as one of isBotFormattedContent's cues.
    private val STAT_PAIR_PATTERN = Regex("[a-z]+:\\s*\\d+", RegexOption.IGNORE_CASE)

    // See isImpersonalSender: apps where the notification's "sender" field is sometimes the
    // app/brand's own name (an engagement ping) rather than a human contact (a DM from a
    // friend through the same app/package). Deliberately a small, curated list of an app's
    // OWN name(s) — not a generic "sounds corporate" word list — to keep false positives
    // against real human contact names near zero. NOT extended to apps like Amazon's shopping
    // app where every notification's sender is the brand regardless of content (there's no
    // personal-contact channel to distinguish from, so this check would flag genuine delivery
    // FYIs as impersonal too — see isDeliveryStatusTemplate for how that domain is handled).
    private val APP_OWN_SENDER_NAMES: Map<String, Set<String>> = mapOf(
        "com.instagram.android" to setOf("instagram"),
        "com.zhiliaoapp.musically" to setOf("tiktok"),
        "com.facebook.katana" to setOf("facebook"),
        "com.twitter.android" to setOf("twitter", "x"),
        "com.google.android.youtube" to setOf("youtube"),
        "com.pinterest" to setOf("pinterest"),
        "com.snapchat.android" to setOf("snapchat")
    )

    // Known gaming-app packages — see scoreNoise's doc for why package identity is trusted
    // over word content here. Real-world package names (not the synthetic corpus's
    // "com.example.gameapp" placeholder, which a real device would never emit).
    private val GAMING_PACKAGES = setOf(
        "com.king.candycrushsaga",
        "com.supercell.clashofclans",
        "com.supercell.clashroyale",
        "com.mojang.minecraftpe",
        "com.nianticlabs.pokemongo",
        "com.rovio.angrybirds",
        "com.king.candycrushsodasaga"
    )

    // See scoreCategories' calendarBonus: known calendar/scheduling app packages.
    private val CALENDAR_PACKAGES = setOf(
        "com.google.android.calendar",
        "com.samsung.android.calendar"
    )

    // "****1234", "XXXX6791", "A/c XXXX6791" — a masked/partial account number, part of
    // isBankingTransactionTemplate's fingerprint.
    private val MASKED_ACCOUNT_PATTERN = Regex("(?:\\*{2,}|x{2,})\\d{2,6}\\b", RegexOption.IGNORE_CASE)

    // "$500", "Rs.248,759.00", "₹500" — a currency symbol/code followed by an amount.
    private val CURRENCY_AMOUNT_PATTERN = Regex("(?:[$€£₹]|\\brs\\.?)\\s?[\\d,]+(?:\\.\\d+)?", RegexOption.IGNORE_CASE)

    // "Avail Bal:", "Available Balance", bare "balance" — the third leg of the banking
    // template fingerprint.
    private val BALANCE_KEYWORD_PATTERN = Regex("\\bbal(?:ance)?\\b", RegexOption.IGNORE_CASE)

    // "#12345" — an order/tracking number, part of isDeliveryStatusTemplate's fingerprint.
    private val ORDER_NUMBER_PATTERN = Regex("#\\d{3,}")

    // A known carrier/retailer as the SENDER (not just mentioned in the body) — the other half
    // of isDeliveryStatusTemplate's fingerprint, checked against the sender field.
    private val DELIVERY_BRAND_SENDER_PATTERN = Regex("\\b(?:amazon|ups|fedex|dhl|usps|dpd)\\b", RegexOption.IGNORE_CASE)

    // "shipped", "delivered", "out for delivery" — completed/in-progress status, not a request.
    private val DELIVERY_STATUS_VERB_PATTERN = Regex(
        "\\b(?:shipped|delivered|dispatched|out for delivery)\\b",
        RegexOption.IGNORE_CASE
    )

    private val NOISE_PATTERNS: List<Regex> = phrases(
        "new video", "recommended for you", "sale", "promotion", "trending", "suggested post",
        "new follower", "daily reward",
        "liked your", "started following you", "posted a new", "limited time", "flash sale",
        "new post from", "commented on your",
        // Promotional/marketing phrasing whose imperative-sounding call-to-action ("check it
        // out") was being read as a literal command rather than recognized as promotional
        // framing (see the "check it out" suppression on ACTION_VERB_SUPPRESSED_BY).
        "today only", "check it out", "new update available", "don't miss out", "act now",
        "shop now"
    ) + listOf(
        // "50% off", "70% off" — percent-off framing, a classic promo pattern not otherwise
        // caught by any literal phrase above.
        Regex("\\d+%\\s*off")
    )

    private val FYI_PATTERNS = phrases(
        "has shipped", "has been delivered", "out for delivery", "order confirmed",
        "payment received", "transaction completed", "meeting moved", "meeting rescheduled",
        "flight landed", "package arrived", "backup completed",
        "payment successful", "your receipt", "verification code", "otp", "confirmation number",
        "booking confirmed", "successfully completed",
        // Real-device testing: "package delivered" (no "has been") and OS-style
        // completion-status notifications weren't covered by any existing phrase.
        "package delivered", "was delivered", "download complete", "download finished",
        "installation complete", "update installed"
    )

    private val DUE_PATTERNS = phrases(
        "due", "expires", "expiring", "deadline", "before",
        "last day", "final date", "closing date", "cutoff", "renewal", "ends on"
    )

    // "before" (like "by") is too generic on its own to unlock DEADLINE_COMBO_DATE_PATTERNS'
    // broader bonus — both show up constantly in the accepted ACTION/DEADLINE overlap cluster
    // ("submit the form before Friday", "complete the form by 9am"/"...before 9am" once "b4"
    // normalizes to "before"). Everything else in DUE_PATTERNS is an unambiguous due/expiry
    // word that genuinely warrants trusting a broader date match alongside it — see
    // scoreDeadline's dateMatches gate.
    private val STRONG_DUE_PATTERNS = phrases(
        "due", "expires", "expiring", "deadline",
        "last day", "final date", "closing date", "cutoff", "renewal", "ends on"
    )

    // "by" alone is too generic (fires on "by tomorrow morning", "by the way", etc.) — only
    // counts as a deadline signal when it actually precedes a day/date/time.
    private val BY_DEADLINE_PATTERN = Regex(
        "\\bby\\s+(?:the\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "today|tomorrow|tonight|midnight|noon|end of (?:the )?month|end of (?:the )?week|next week|" +
            "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "\\d{1,2}(?::\\d{2})?\\s?(?:am|pm)|\\d{1,2}/\\d{1,2}|\\d{1,2}(?:st|nd|rd|th))",
        RegexOption.IGNORE_CASE
    )

    // "should arrive by tonight" / "expect it by 5pm" — see scoreDeadline's doc for why a
    // delivery-commitment phrase makes the following "by <time>" not a real deadline signal.
    private val DEADLINE_SUPPRESSED_BY_DELIVERY_COMMITMENT = Regex(
        "\\b(?:should (?:arrive|expect)|expect (?:it|the|your|this|that))\\b"
    )

    // Day names and relative time phrases count as deadline signals ON THEIR OWN, per spec,
    // even without an accompanying due/expiry word — deliberately the NARROW set: "today"/
    // "tomorrow"/a bare time-of-day are far too common in ordinary non-deadline sentences to
    // trust without due/expiry language already establishing the context (see scoreDeadline).
    private val DEADLINE_STANDALONE_DATE_PATTERNS: List<Regex> = phrases(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "next week"
    ) + listOf(
        // "end of month" and "end of the month" are both common phrasings of the same thing —
        // the literal-phrase match above would only catch the former.
        Regex("\\bend of (?:the )?month\\b"),
        Regex("\\bend of (?:the )?week\\b")
    )

    // Broader set used only to STRENGTHEN a deadline that due/expiry language already
    // established (see scoreDeadline) — adds calendar dates, "today"/"tomorrow"/"tonight",
    // and a bare time of day, all of which EXTRACT_DATE_PATTERN already recognizes for
    // display but scoring previously ignored, leaving even an unambiguous dated deadline
    // ("payment due Sep 12", "cutoff is 5pm today") capped at a low, under-confident score.
    private val DEADLINE_COMBO_DATE_PATTERNS: List<Regex> = DEADLINE_STANDALONE_DATE_PATTERNS + phrases(
        "today", "tomorrow", "tonight", "midnight", "noon",
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december"
    ) + listOf(
        Regex("\\b\\d{1,2}(?::\\d{2})?\\s?(?:am|pm)\\b", RegexOption.IGNORE_CASE), // "5pm", "10:30 am"
        Regex("\\b\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?\\b"), // "9/12", "9/12/2025"
        Regex("\\b\\d{1,2}(?:st|nd|rd|th)\\b", RegexOption.IGNORE_CASE), // "the 1st", "the 30th"
        // "due Sep 12", "expires Dec. 25" — abbreviated month name + day number; the full
        // month names above don't match these, and only abbreviations that actually differ
        // from the full spelling need listing (May's abbreviation is itself).
        Regex(
            "\\b(?:jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\.?\\s+\\d{1,2}\\b",
            RegexOption.IGNORE_CASE
        )
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

    // A future-commitment prefix immediately before one of these verbs means the SENDER is
    // committing to do it themselves — a WAITING follow-up, not a request aimed at the
    // recipient — even though the bare verb also appears in ACTION_VERBS. Real notifications
    // ("I'll call you back after the meeting", "gonna send the file soon") were scoring ACTION
    // because the only competing WAITING signal (the generic "i will" catch-all below) merely
    // TIES the verb's own ACTION score, and ACTION wins ties (see TIE_BREAK_ORDER) — an actual
    // suppression is needed, not just an added competing score.
    //
    // Deliberately subject-agnostic ("will" alone, not just "i will"/"i'll") so a third-person
    // commitment reported secondhand ("she said she will call back", "the team will confirm
    // shortly") suppresses the verb the same way a first-person one does — real-device testing
    // and hand-written diverse examples both surfaced this class of commitment, and there's no
    // reason the subject pronoun should matter to whether it's a commitment. "bout to"/"about
    // to"/"fixing to" are casual/regional equivalents of "going to" that TextNormalizer doesn't
    // expand (unlike "gonna"), so they're listed explicitly.
    private fun futureCommitmentPattern(verb: String): String =
        "\\b(?:i'll|will|going to|bout to|about to|fixing to)\\s+${Regex.escape(verb)}\\b"

    // See the suppression check in scoreAction(): these verbs double as the head word of a
    // more specific REPLY/WAITING phrase, or of promotional call-to-action framing, so that
    // phrase already "owns" the word.
    private val ACTION_VERB_SUPPRESSED_BY: Map<String, Regex> = mapOf(
        // "final call"/"last call" is a deadline idiom ("last chance"), not a literal request
        // to phone someone — without this, "call" ties DEADLINE's own score on phrases like
        // "Final call — offer expires at midnight." and badly undercuts confidence even though
        // the tie-break still lands on the right category (see computeConfidence's doc: a
        // near-tie is scored as low-confidence regardless of which side the tie-break favors).
        "call" to Regex("\\bcall me\\b|\\b(?:final|last) call\\b|${futureCommitmentPattern("call")}"),
        "check" to Regex(
            futureCommitmentPattern("check") +
                // "new update available - check it out" is promotional framing, not a literal
                // command directed at the recipient — a genuine imperative reads "check X"
                // (an object), not the idiomatic "check it out".
                "|\\bcheck it out\\b" +
                // "give me a moment to check on that" — a WAITING commitment-to-look-into-it
                // phrased as "[a] moment/second/minute/sec to check", not a request directed
                // at the recipient.
                "|\\b(?:a\\s+)?(?:moment|second|minute|sec)\\s+to\\s+check\\b"
        ),
        // Only suppress when confirming something about the recipient themselves (receipt,
        // attendance, agreement — "confirm you received/got/are coming"), which is a REPLY-
        // style acknowledgment request. "Confirm the details/report/numbers" is confirming
        // a deliverable, a genuine ACTION request, and must NOT be suppressed — narrowed
        // after the broader "any 'can you confirm'" version incorrectly pulled those into
        // REPLY.
        "confirm" to Regex("\\bcan you confirm you\\b|${futureCommitmentPattern("confirm")}"),
        "bring" to Regex("\\bi(?:'ll| will) bring\\b|${futureCommitmentPattern("bring")}"),
        "send" to Regex(futureCommitmentPattern("send")),
        // "Installation complete."/"Download complete." is a completion-STATUS noun phrase
        // (an FYI announcement), not an imperative verb directed at the recipient — without
        // this, it ties FYI's own "installation complete" phrase match and ACTION wins the
        // tie (see TIE_BREAK_ORDER).
        "complete" to Regex("\\b(?:download|installation|update|setup|backup|sync|upload)\\s+complete\\b")
    )

    private val REQUEST_MARKERS = phrases("can you", "could you", "would you", "need you to")

    private val WAITING_PATTERNS: List<Regex> = phrases(
        "i'll send", "i will", "i'll check", "i'll get back to you", "i'll have",
        // Generalized from the object-literal "expect it"/"i'll bring it", which only
        // matched when the object was literally "it" and missed "expect the file"/"i'll
        // bring the presentation" — these match the verb+commitment structure regardless
        // of what's being expected/brought, consistent with how "i'll send"/"i'll check"
        // already don't require a specific object.
        "should expect", "i'll bring", "should have",
        "should arrive", "i'm working on", "i am working on", "we're working on",
        "we are working on", "working through it",
        "on it", "will do", "will send", "will get back", "will reply",
        // Formal/third-person commitment idioms — deliberately NOT anchored to "i" the way
        // "i'll .../i will" above are, since a formal or secondhand commitment is routinely
        // phrased "we will.../the team will.../she'll..." rather than "I will..." (see
        // futureCommitmentPattern's doc for the parallel reasoning on the verb-suppression
        // side). None of these collide with an ACTION_VERBS word, so no suppression entry is
        // needed for them the way call/send/check/confirm/bring need one.
        "will have", "will follow up", "will keep you posted",
        // Short "give me a moment"-family and progress-status idioms — real-device and
        // hand-written diverse examples turned up a cluster of these with no existing
        // coverage at all (falling through to the FYI/zero-signal default), distinct from
        // the "check"-suppression fix above which only covers the case where "check" is
        // literally the following verb.
        "give me a moment", "give me a second", "give me a minute",
        "one moment", "one sec", "one second", "almost done", "about done",
        "hang tight", "hold up", "in progress"
    ) + listOf(
        // "expect it in your inbox shortly" — a bare "expect" commitment without the "should"
        // prefix above. Scoped to "expect + a determiner/pronoun" (it/the/your/this/that)
        // rather than the bare word "expect", which appears in enough unrelated contexts
        // ("what did you expect") to be too broad on its own.
        Regex("\\bexpect\\s+(?:it|the|your|this|that)\\b"),
        // "on my way", "on the way", "on his way", "on her way", "on their way" — a delivery/
        // arrival commitment-in-progress, whoever it's about ("otw with the docs" normalizes
        // to "on the way..." via TextNormalizer).
        Regex("\\bon (?:my|his|her|their|the) way\\b"),
        // "circle back"/"circling back" — a common formal follow-up idiom independent of
        // subject or tense form.
        Regex("\\bcircl(?:e|ing) back\\b"),
        // "will reach out"/"will reach back out" — "back" is a common but optional insertion.
        Regex("\\bwill reach (?:back )?out\\b"),
        // "will respond" — a formal commitment-to-reply idiom, distinct from REPLY's own
        // patterns (those are the RECIPIENT being asked to respond; this is the SENDER
        // committing to respond).
        Regex("\\bwill respond\\b"),
        // "look into"/"looking into" — bare, not anchored to "will", since this idiom is just
        // as much a commitment in present-progressive form ("we're looking into this") as in
        // the future-tense "will look into" phrasing.
        Regex("\\blook(?:ing)?\\s+into\\b"),
        // "hit you back" ("hit u back" normalizes "u"→"you") — a casual "I'll get back to
        // you" idiom.
        Regex("\\bhit you back\\b"),
        // A bare casual/regional "about to do something" marker with no object requirement —
        // covers commitments built on a verb outside ACTION_VERBS entirely (e.g. "fixing to
        // head out with the package"), which futureCommitmentPattern's per-verb entries below
        // don't reach since they're scoped to the specific verbs that need ACTION suppression.
        Regex("\\b(?:bout to|about to|fixing to)\\b"),
        // "I'll call you back", "gonna send the file soon" (normalizes to "going to send...")
        // — see futureCommitmentPattern's doc on ACTION_VERB_SUPPRESSED_BY for why a real
        // WAITING match is needed here, not just suppressing the verb's ACTION score.
        Regex(futureCommitmentPattern("call")),
        Regex(futureCommitmentPattern("send")),
        Regex(futureCommitmentPattern("check")),
        Regex(futureCommitmentPattern("confirm"))
    )

    private val REPLY_PATTERNS = phrases(
        "let me know", "lmk", "tell me", "get back to me", "call me", "text me", "hmu",
        "hit me up", "what do you think", "can you confirm", "keep me posted", "are you free",
        "when are you free"
    ) + listOf(
        // "how was your day" — a casual check-in question with no ACTION_VERBS/REQUEST_MARKERS
        // match, so it previously scored 0 everywhere and fell through to a weak, essentially
        // arbitrary pick (real-device testing saw this land on ACTION at 12% confidence).
        Regex("\\bhow (?:was|is) your\\b")
    )

    // Broader than DEADLINE_STANDALONE_DATE_PATTERNS — used only to populate extractedDate, not scoring.
    private val EXTRACT_DATE_PATTERN = Regex(
        "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tomorrow|tonight|" +
            "midnight|noon|end of (?:the )?month|end of (?:the )?week|next week|" +
            "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|\\d{1,2}(?::\\d{2})?\\s?(?:am|pm)|\\d{1,2}(?:st|nd|rd|th))\\b",
        RegexOption.IGNORE_CASE
    )
}
