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
            (DUE_PATTERNS.any { it.containsMatchIn(lowerText) } || BY_DEADLINE_PATTERN.containsMatchIn(lowerText) ||
                BEFORE_DEADLINE_PATTERN.containsMatchIn(lowerText))
        val bankingFyiBonus = if (bankingTemplateHit && !bankingTemplateHasDueLanguage) BANKING_TEMPLATE_WEIGHT else 0
        val bankingDeadlineBonus = if (bankingTemplateHasDueLanguage) BANKING_TEMPLATE_WEIGHT else 0

        val deliveryTemplateBonus = if (isDeliveryStatusTemplate(sender, lowerText)) DELIVERY_TEMPLATE_WEIGHT else 0

        // Pure word-based scores, with NO package-identity bonus folded in yet — used only to
        // decide whether isWeakStandaloneDeadlineOnly should fire (see below). The calendar
        // bonus nudges FYI and DEADLINE by the same fixed amount regardless of whether either
        // side has any real word-based support, which — found via large-scale validation —
        // defeats that safeguard's "every OTHER category scored exactly 0" check: a calendar
        // notification with a bare day name and nothing else ("Calendar updated: new event
        // added for Friday.") got FYI=0+bonus and DEADLINE=1(standalone)+bonus, so FYI was no
        // longer 0 and the safeguard didn't fire, letting the bare day name win DEADLINE
        // outright again — exactly the false positive the safeguard exists to prevent, just
        // reintroduced through the bonus instead of a word match. Checking against the
        // pre-bonus scores keeps the safeguard's original semantics intact regardless of which
        // package-identity priors also apply to the same message.
        val wordBasedScores = mapOf(
            ClassifiedState.NOISE to scoreNoise(sourceApp, sender, lowerText, isPublicContext),
            ClassifiedState.FYI to score(lowerText, FYI_PATTERNS),
            ClassifiedState.DEADLINE to scoreDeadline(lowerText),
            ClassifiedState.ACTION to dampenInPublicContext(scoreAction(lowerText), isPublicContext),
            ClassifiedState.WAITING to score(lowerText, WAITING_PATTERNS),
            ClassifiedState.REPLY to dampenInPublicContext(score(lowerText, REPLY_PATTERNS), isPublicContext)
        )

        val baseScores = wordBasedScores + mapOf(
            ClassifiedState.FYI to wordBasedScores.getValue(ClassifiedState.FYI) + calendarBonus + bankingFyiBonus + deliveryTemplateBonus,
            ClassifiedState.DEADLINE to wordBasedScores.getValue(ClassifiedState.DEADLINE) + calendarBonus + bankingDeadlineBonus
        )

        // A bare day-name mention with no due/expiry language (see scoreDeadline) is weak
        // enough that it should never be the SOLE basis for classifying a notification as a
        // deadline — real-device testing turned up purely reflective/casual social posts that
        // happen to mention day names ("Sunday in Amman hits different... pretending Monday
        // doesn't exist") with every other category scoring exactly 0, which let this weak
        // signal win by default even though it was never meant to be decisive on its own (see
        // scoreDeadline's own comment). When nothing else has any signal either, this treats
        // that the same as no signal at all — resultFromScores's topScore<=0 check then falls
        // back to FYI, same as if the day name weren't mentioned. Zeroes DEADLINE entirely
        // (bonus included, not just the word-based part) once triggered, since the bonus was
        // never meant to independently establish a deadline on its own either.
        val adjustedScores = if (isWeakStandaloneDeadlineOnly(lowerText, wordBasedScores)) {
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
        // BARE_BEFORE_PATTERN counts as "real" due language here even though scoreDeadline
        // itself only credits it weakly (see hasBareBefore there) — this check only fires when
        // EVERY OTHER category also scored exactly 0, so a bare "before" is never what lets a
        // false deadline win a genuine competing signal; it just keeps a lone "3 days left
        // before your subscription lapses."-style message (no other category support, no day
        // name either) from being zeroed to FYI purely because "before" isn't the day-name
        // pattern this safeguard was built for.
        val hasDueLanguage = DUE_PATTERNS.any { it.containsMatchIn(lowerText) } ||
            BY_DEADLINE_PATTERN.containsMatchIn(lowerText) || BEFORE_DEADLINE_PATTERN.containsMatchIn(lowerText) ||
            BARE_BEFORE_PATTERN.containsMatchIn(lowerText) ||
            ARABIC_BY_DEADLINE_PATTERN.containsMatchIn(lowerText) || ARABIC_BEFORE_DEADLINE_PATTERN.containsMatchIn(lowerText) ||
            ARABIC_BARE_BEFORE_PATTERN.containsMatchIn(lowerText)
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
        val byDeadlineHit = !deliveryCommitment &&
            (BY_DEADLINE_PATTERN.containsMatchIn(lowerText) || ARABIC_BY_DEADLINE_PATTERN.containsMatchIn(lowerText))
        val beforeDeadlineHit = !deliveryCommitment &&
            (BEFORE_DEADLINE_PATTERN.containsMatchIn(lowerText) || ARABIC_BEFORE_DEADLINE_PATTERN.containsMatchIn(lowerText))
        // A bare "before" with no anchored day/date/time (see BEFORE_DEADLINE_PATTERN for the
        // anchored case) is an ordinary subordinating conjunction — "clean the desk before you
        // leave", "double check the numbers before sending" — that appears constantly in
        // ordinary ACTION-request sentences with no deadline intent at all. Tracked separately
        // so it falls through to the same weak STANDALONE_DATE_WEIGHT-level treatment as a bare
        // day-name mention below (see the dueMatches==0 branch), rather than the full DUE_WEIGHT
        // credit it used to get unconditionally, which tied or beat a genuine ACTION/WAITING
        // signal on exactly this class of sentence.
        val hasBareBefore = !beforeDeadlineHit &&
            (BARE_BEFORE_PATTERN.containsMatchIn(lowerText) || ARABIC_BARE_BEFORE_PATTERN.containsMatchIn(lowerText))
        // Tracked separately from byDeadlineHit/beforeDeadlineHit: an explicit due/expiry WORD
        // ("due", "cutoff", "expires"...) is a much stronger, less generic signal than the bare
        // "by/before <time>" trigger — see below for why that distinction matters for the
        // combo-date broadening.
        val explicitDueWordCount = DUE_PATTERNS.count { it.containsMatchIn(lowerText) }
        val dueMatches = explicitDueWordCount + (if (byDeadlineHit) 1 else 0) + (if (beforeDeadlineHit) 1 else 0)

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
            val dateMatches = (DEADLINE_STANDALONE_DATE_PATTERNS + ARABIC_DEADLINE_STANDALONE_DATE_PATTERNS)
                .count { it.containsMatchIn(lowerText) }
            val bareBeforeWeight = if (hasBareBefore) STANDALONE_DATE_WEIGHT else 0
            return dateMatches * STANDALONE_DATE_WEIGHT + bareBeforeWeight
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
            (DEADLINE_COMBO_DATE_PATTERNS + ARABIC_DEADLINE_COMBO_DATE_PATTERNS).count { it.containsMatchIn(lowerText) }
        } else {
            (DEADLINE_STANDALONE_DATE_PATTERNS + ARABIC_DEADLINE_STANDALONE_DATE_PATTERNS).count { it.containsMatchIn(lowerText) }
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
        // Arabic request verbs (MSA + Jordanian/Levantine colloquial) — see ARABIC_ACTION_VERBS'
        // doc. No suppression map exists for these yet (unlike ACTION_VERB_SUPPRESSED_BY above),
        // since none of the curated Arabic surface forms below collide with a specific REPLY/
        // WAITING idiom the way English "call"/"check"/"confirm" do.
        for (verb in ARABIC_ACTION_VERBS) {
            val suppressedBy = ARABIC_ACTION_VERB_SUPPRESSED_BY[verb.word]
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
            // A request explicitly directed at the recipient ("can you...", "kindly...",
            // "please...", "would you mind...") is unambiguously an action ask even when it
            // also names a deadline ("...by Friday") — unlike a BARE imperative with an
            // attached day-name (the large-scale validation report's documented, accepted
            // ACTION/DEADLINE overlap — see scoreDeadline's COMBO_BONUS), a genuine
            // question/politeness marker never doubles as an automated deadline announcement's
            // phrasing. Sized so the combined ACTION score clears DEADLINE's own "verb + by/
            // before <date>" combo score rather than losing to it the way a bare imperative
            // does.
            if (BY_DEADLINE_PATTERN.containsMatchIn(lowerText) || BEFORE_DEADLINE_PATTERN.containsMatchIn(lowerText) ||
                ARABIC_BY_DEADLINE_PATTERN.containsMatchIn(lowerText) || ARABIC_BEFORE_DEADLINE_PATTERN.containsMatchIn(lowerText) ||
                DUE_PATTERNS.any { it.containsMatchIn(lowerText) }
            ) {
                total += ACTION_REQUEST_WITH_DEADLINE_BONUS
            }
        }
        return total
    }

    private fun score(text: String, patterns: List<Regex>): Int = patterns.count { it.containsMatchIn(text) } * DEFAULT_WEIGHT

    private fun extractDate(text: String): String? =
        EXTRACT_DATE_PATTERN.find(text)?.value ?: ARABIC_EXTRACT_DATE_PATTERN.find(text)?.value

    /**
     * Picks the sentence/clause containing the strongest signal (a verb, request marker, or
     * date match) as a rough one-line summary, capped to a readable length. Falls back to
     * sender + a truncated excerpt when no clause with a clear signal is found.
     */
    private fun extractSummary(text: String, sender: String): String {
        // "؟" (the Arabic question mark) is added to the clause-splitter alongside the ASCII
        // sentence terminators so an Arabic-only notification's clauses break the same way an
        // English one's do — casual Arabic texting almost never uses the ASCII "?" instead.
        val clauses = text.split(Regex("[.!?\n؟]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val candidate = clauses.firstOrNull { clause ->
            val lower = clause.lowercase()
            ACTION_VERBS.any { it.bare.containsMatchIn(lower) } ||
                ARABIC_ACTION_VERBS.any { it.bare.containsMatchIn(lower) } ||
                REQUEST_MARKERS.any { it.containsMatchIn(lower) } ||
                WAITING_PATTERNS.any { it.containsMatchIn(lower) } ||
                DUE_PATTERNS.any { it.containsMatchIn(lower) } ||
                BY_DEADLINE_PATTERN.containsMatchIn(lower) ||
                ARABIC_BY_DEADLINE_PATTERN.containsMatchIn(lower) ||
                DEADLINE_COMBO_DATE_PATTERNS.any { it.containsMatchIn(lower) } ||
                ARABIC_DEADLINE_COMBO_DATE_PATTERNS.any { it.containsMatchIn(lower) }
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
    // See scoreAction's doc: only added on top of ACTION_REQUEST_MARKER_BONUS, and only when a
    // deadline-shaped clause is ALSO present, so it can't affect any request that doesn't
    // already compete against DEADLINE's combo score.
    private const val ACTION_REQUEST_WITH_DEADLINE_BONUS = 4
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
        "shop now",
        // Classic SMS/email spam-register phrasing — found via real public-dataset
        // integration (SMS Spam Collection + SpamAssassin) to be almost entirely uncaught by
        // the app-notification-style promo phrasing above (98% miss rate on real spam text:
        // "WINNER!! ... you have been selected", "URGENT! You have won...", "Free entry in 2
        // a wkly comp..."). Deliberately kept to markers distinctive enough to avoid colliding
        // with genuine ACTION/DEADLINE urgency ("urgent" and "txt" alone were considered and
        // rejected as too generic/collision-prone; see the session report).
        "you've won", "you have won", "u have won", "claim your prize", "claim now",
        "free entry", "reply stop"
    ) + listOf(
        // "50% off", "70% off" — percent-off framing, a classic promo pattern not otherwise
        // caught by any literal phrase above.
        Regex("\\d+%\\s*off"),
        // "WINNER!!", "URGENT!" — spam's characteristic multi-exclamation-mark shouting on a
        // single all-caps or near-all-caps attention word, distinct from an ordinary excited
        // sentence because it's the word ALONE (not part of a longer imperative).
        Regex("\\b(?:winner|urgent|congratulations)!!", RegexOption.IGNORE_CASE)
    ) + arabicPhrases(
        // MSA + Levantine promotional/marketing phrasing (see the Arabic pattern coverage
        // section at the bottom of this file for why these go through arabicPhrases() rather
        // than phrases()).
        "خصم", "عرض خاص", "عرض لفترة محدودة", "مجاني", "احجز الآن", "احجز الان", "كوبون",
        "تخفيضات", "اشتري الآن", "اشتري الان", "لا تفوت الفرصة", "عرض حصري", "مبروك ربحت",
        "فزت بجائزة", "اضغط هنا"
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
    ) + arabicPhrases(
        "تم الشحن", "تم التوصيل", "تم استلام طلبك", "تم الدفع بنجاح", "رمز التحقق", "رمز التأكيد",
        "رمز التاكيد", "تم تأكيد الحجز", "تم تاكيد الحجز", "تم تسليم الطلب", "وصلت الطلبية",
        "تم تنفيذ العملية بنجاح"
    )

    // "before" is deliberately NOT included here — unlike "due"/"expires"/"deadline", it's an
    // ordinary subordinating conjunction ("do X before Y happens") that appears constantly in
    // non-deadline sentences with no due/expiry meaning at all. It only counts as a due/expiry
    // signal when actually anchored to a day/date/time (BEFORE_DEADLINE_PATTERN below, mirroring
    // BY_DEADLINE_PATTERN); an unanchored "before" gets the weaker, standalone-level treatment
    // in scoreDeadline instead (see hasBareBefore there).
    // Arabic strong due/expiry words shared by DUE_PATTERNS and STRONG_DUE_PATTERNS below — "حتى"
    // and "قبل" are deliberately NOT included here (they're as generic as English "by"/"before"
    // unless anchored to a date — see ARABIC_BY_DEADLINE_PATTERN/ARABIC_BEFORE_DEADLINE_PATTERN
    // at the bottom of this file).
    private val ARABIC_DUE_PATTERNS = arabicPhrases(
        "آخر موعد", "اخر موعد", "الموعد النهائي", "ينتهي", "تنتهي", "مهلة", "اخر يوم", "آخر يوم",
        "تاريخ الانتهاء", "صلاحية العرض تنتهي"
    )

    private val DUE_PATTERNS = phrases(
        "due", "expires", "expiring", "deadline",
        "last day", "final date", "closing date", "cutoff", "renewal", "ends on"
    ) + ARABIC_DUE_PATTERNS

    // Same list as DUE_PATTERNS now that "before" has its own anchored/unanchored handling —
    // kept as a separate named list since scoreDeadline's dateMatches gate documents its intent
    // in terms of "the strong due/expiry words", independent of DUE_PATTERNS' own membership.
    private val STRONG_DUE_PATTERNS = phrases(
        "due", "expires", "expiring", "deadline",
        "last day", "final date", "closing date", "cutoff", "renewal", "ends on"
    ) + ARABIC_DUE_PATTERNS

    // Shared date/day/time alternation anchoring both BY_DEADLINE_PATTERN and
    // BEFORE_DEADLINE_PATTERN — "by"/"before" alone are both too generic (fire on "by the way",
    // "before you know it", etc.) to count as a deadline signal without actually precede a
    // day/date/time.
    private const val DEADLINE_ANCHOR_DATES =
        "(?:the\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "today|tomorrow|tonight|midnight|noon|end of (?:the )?month|end of (?:the )?week|next week|" +
            "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "\\d{1,2}(?::\\d{2})?\\s?(?:am|pm)|\\d{1,2}/\\d{1,2}|\\d{1,2}(?:st|nd|rd|th))"

    private val BY_DEADLINE_PATTERN = Regex("\\bby\\s+$DEADLINE_ANCHOR_DATES", RegexOption.IGNORE_CASE)

    // The "before" counterpart to BY_DEADLINE_PATTERN — see DUE_PATTERNS' doc for why bare
    // "before" (no anchored date) is handled separately and more weakly in scoreDeadline.
    private val BEFORE_DEADLINE_PATTERN = Regex("\\bbefore\\s+$DEADLINE_ANCHOR_DATES", RegexOption.IGNORE_CASE)

    // The weak, unanchored form of "before" — see scoreDeadline's hasBareBefore.
    private val BARE_BEFORE_PATTERN = Regex("\\bbefore\\b")

    // "should arrive by tonight" / "expect it by 5pm" — see scoreDeadline's doc for why a
    // delivery-commitment phrase makes the following "by <time>" not a real deadline signal.
    // Broadened to cover the equivalent WAITING-style follow-up commitment ("Rest assured, our
    // team is on it and will follow up by Tuesday.", "will have something for you by tonight")
    // — a sender's own promise to act/respond by a given time reads as a status update, not a
    // deadline the RECIPIENT must meet, the same distinction the delivery-commitment case
    // already draws; without this, WAITING's own strong multi-pattern signal ("on it" + "will
    // follow up") was losing outright to the "by <day>" combo bonus below.
    private val DEADLINE_SUPPRESSED_BY_DELIVERY_COMMITMENT = Regex(
        "\\b(?:should (?:arrive|expect)|expect (?:it|the|your|this|that|news|an update|a reply|a response)|" +
            "will (?:follow up|get back|update|respond)|will have (?:something|an update|news))\\b"
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

    // Default inflection suffix for an ACTION_VERBS word — "upload"/"uploading"/"uploaded"/
    // "uploads" should all count as the same request verb; the ORIGINAL bare-word-only regex
    // matched none of the inflected forms at all (large-scale validation: "Would you mind
    // uploading the presentation before the meeting?" scored zero ACTION signal purely because
    // "uploading" never matched "\\bupload\\b"). A handful of verbs override this default below
    // because their inflected form collides with a common NON-imperative usage elsewhere.
    private const val DEFAULT_VERB_SUFFIX = "(?:s|ed|ing)?"

    private val ACTION_VERB_SUFFIX_OVERRIDES: Map<String, String> = mapOf(
        // "renewed" is almost always a completed-status FYI statement ("Your subscription
        // renewed automatically."), never a request — excludes "ed" so only the imperative
        // present-tense/gerund forms count.
        "renew" to "(?:s|ing)?",
        // "texts"/"prints" as PLURAL NOUNS collide with unrelated spam-template text ("stop
        // texts", "Make your prints beautiful") — excludes "s"; "text" also excludes "ed" since
        // there's no real-world upside to matching it and it keeps the verb's footprint minimal.
        "text" to "(?:ing)?",
        "print" to "(?:ing|ed)?",
        // "order confirmed"/"booking confirmed"/"your appointment is confirmed" are completion-
        // STATUS statements (FYI), never an imperative — "confirmed" (past tense) essentially
        // never occurs as a request. Excludes "ed"/"ing" entirely, keeping only bare present-
        // tense "confirm(s)".
        "confirm" to "(?:s)?",
        // "Booking confirmed for Tuesday." / "table booked for half eight" — "booking"/"booked"
        // are FYI status-noun/status-verb usages, not the gerund/past tense of an imperative
        // "book the flights" request. Bare present-tense only, same reasoning as "confirm".
        "book" to "(?:s)?",
        // "Your review has been submitted" / "New device signed in to your account" / "Your
        // document has been signed by all parties" / "Class canceled today." are all
        // completion-status FYI statements, not requests — same reasoning as confirm/book
        // above. Excludes "ed" for all three.
        "submit" to "(?:s|ing)?",
        "sign" to "(?:s|ing)?",
        "cancel" to "(?:s|ing)?"
    )

    private val ACTION_VERBS = listOf(
        "send", "bring", "upload", "finish", "buy", "pay", "call", "pick up", "check",
        "submit", "sign", "review", "forward", "complete", "book", "cancel", "confirm",
        // Added from large-scale validation's ACTION-miss analysis — common real-world
        // request/task verbs with no prior coverage at all (see the session report for the
        // per-verb collision check against every existing corpus before inclusion; verbs with
        // a real collision risk — e.g. "attach"/"draft"/"fix"/"grab" all collide with genuine
        // FYI/WAITING usage elsewhere in the corpora — were deliberately left out).
        "reboot", "water", "escalate", "handle", "approve", "authorize", "lock", "text",
        "renew", "arrange", "take out", "clean up"
    ).map { verb ->
        val suffix = ACTION_VERB_SUFFIX_OVERRIDES[verb] ?: DEFAULT_VERB_SUFFIX
        val parts = verb.split(" ")
        // Two-word verbs ("pick up", "take out", "clean up") inflect on the FIRST word only —
        // "picking up"/"cleaned up", never "pick uping" — the trailing particle stays fixed.
        val bareText = if (parts.size == 2) {
            "\\b${Regex.escape(parts[0])}$suffix\\s+${Regex.escape(parts[1])}\\b"
        } else {
            "\\b${Regex.escape(verb)}$suffix\\b"
        }
        ActionVerb(
            word = verb,
            bare = Regex(bareText),
            // e.g. "send this", "call me", "pick up that" — verb directed at me/this/that
            // within a couple of words.
            directed = Regex("$bareText(?:\\s+\\w+){0,2}\\s+(me|this|that)\\b")
        )
    }

    // A future-commitment prefix before one of these verbs means the SENDER is committing to
    // do it themselves — a WAITING follow-up, not a request aimed at the recipient — even
    // though the bare verb also appears in ACTION_VERBS. Real notifications ("I'll call you
    // back after the meeting", "gonna send the file soon") were scoring ACTION because the
    // only competing WAITING signal (the generic "i will" catch-all below) merely TIES the
    // verb's own ACTION score, and ACTION wins ties (see TIE_BREAK_ORDER) — an actual
    // suppression is needed, not just an added competing score.
    //
    // Deliberately subject-agnostic ("will" alone, not just "i will"/"i'll") so a third-person
    // commitment reported secondhand ("she said she will call back", "the team will confirm
    // shortly") suppresses the verb the same way a first-person one does — real-device testing
    // and hand-written diverse examples both surfaced this class of commitment, and there's no
    // reason the subject pronoun should matter to whether it's a commitment. "bout to"/"about
    // to"/"fixing to" are casual/regional equivalents of "going to" that TextNormalizer doesn't
    // expand (unlike "gonna"), so they're listed explicitly.
    //
    // "will" is deliberately zero-width/immediate-only ("will $verb") — unlike "i'll"/"going
    // to"/"let me", a bare "will" is also how a request QUESTION is phrased ("will you please
    // check the report?"), so allowing filler words here would wrongly swallow that into a
    // commitment suppression. The other prefixes are unambiguously self-referential regardless
    // of what filler words come between them and the verb ("going to go check", "let me
    // quickly send", "i'll just call") — large-scale validation turned up real casual
    // constructions inserting a word or two (an intervening verb like "go", an adverb like
    // "quickly"/"double") that a strict immediate-adjacency match was missing entirely (e.g.
    // "lemme double check n ill lyk", "brb, gonna go check with the front desk").
    private fun futureCommitmentPattern(verb: String): String {
        val escaped = Regex.escape(verb)
        return "\\bwill\\s+$escaped\\b|\\b(?:i'll|going to|bout to|about to|fixing to|let me)\\s+(?:\\w+\\s+){0,2}$escaped\\b"
    }

    // See the suppression check in scoreAction(): these verbs double as the head word of a
    // more specific REPLY/WAITING phrase, or of promotional call-to-action framing, so that
    // phrase already "owns" the word. Every ACTION_VERBS word gets AT LEAST the generic
    // future-commitment suppression below (a self-referential "I'll .../going to .../let me
    // ..." prefix means the SENDER is committing to do it, not asking the recipient) — verbs
    // with additional idiom-specific collisions layer their own extra alternatives on top.
    private val ACTION_VERB_SUPPRESSION_EXTRAS: Map<String, String> = mapOf(
        // "call me"/"a quick call"/"a phone call" are noun/idiom usages, not a request to
        // phone someone — "final call"/"last call" is a deadline idiom ("last chance"). Without
        // these, "call" ties DEADLINE's own score on phrases like "Final call — offer expires
        // at midnight." (see computeConfidence's doc on near-ties), and "a quick call" as a
        // NOUN ("Are you free on Friday for a quick call?") was outright beating REPLY's own
        // "are you free" match on a tie (see TIE_BREAK_ORDER).
        "call" to "\\bcall me\\b|\\b(?:final|last) call\\b|\\b(?:a|the|quick|phone|video)\\s+call\\b",
        "check" to
            // "new update available - check it out" is promotional framing, not a literal
            // command directed at the recipient — a genuine imperative reads "check X" (an
            // object), not the idiomatic "check it out".
            "\\bcheck it out\\b" +
                // "give me a moment to check on that" — a WAITING commitment-to-look-into-it
                // phrased as "[a] moment/second/minute/sec to check", not a request directed
                // at the recipient.
                "|\\b(?:a\\s+)?(?:moment|second|minute|sec)\\s+to\\s+check\\b" +
                // "checking on that"/"checkin on that rn"/"check in with the team" — a
                // progress-status self-report (WAITING), not a request directed at the
                // recipient; also registered as a positive WAITING signal (see WAITING_PATTERNS).
                "|\\bcheck(?:ing|in)?\\s+(?:on|in with)\\b",
        // Only suppress when confirming something about the recipient themselves (receipt,
        // attendance, agreement — "confirm you received/got/are coming"), which is a REPLY-
        // style acknowledgment request. "Confirm the details/report/numbers" is confirming
        // a deliverable, a genuine ACTION request, and must NOT be suppressed — narrowed
        // after the broader "any 'can you confirm'" version incorrectly pulled those into
        // REPLY.
        "confirm" to "\\bcan you confirm you\\b",
        "bring" to "\\bi(?:'ll| will) bring\\b",
        // "Installation complete."/"Download complete." is a completion-STATUS noun phrase
        // (an FYI announcement), not an imperative verb directed at the recipient — without
        // this, it ties FYI's own "installation complete" phrase match and ACTION wins the
        // tie (see TIE_BREAK_ORDER).
        "complete" to "\\b(?:download|installation|update|setup|backup|sync|upload)\\s+complete\\b",
        // "download finished"/"installation finished" — the same completion-STATUS collision
        // as "complete" above, now reachable since "finish" gained inflection matching
        // ("finished" previously never matched the old bare-word-only "\\bfinish\\b").
        "finish" to "\\b(?:download|installation|update|setup|backup|sync|upload)\\s+finished\\b",
        // "Lock in Your Clients' Gains!" — a marketing idiom ("lock in a rate/price/gain"), not
        // a literal request to secure a physical lock.
        "lock" to "\\block(?:ing)?\\s+in\\b",
        // "text me" mirrors "call me" — a REPLY-style request to be contacted, not a request to
        // send someone else a text.
        "text" to "\\btext me\\b"
    )

    private val ACTION_VERB_SUPPRESSED_BY: Map<String, Regex> = ACTION_VERBS.associate { verb ->
        val extra = ACTION_VERB_SUPPRESSION_EXTRAS[verb.word]
        val generic = futureCommitmentPattern(verb.word)
        verb.word to Regex(if (extra != null) "$extra|$generic" else generic)
    }

    // "please"/"kindly"/"requesting that you" only add their ACTION_REQUEST_MARKER_BONUS when
    // an ACTION_VERBS word ALSO matched (see scoreAction's verbHit gate), so adding these
    // common polite/formal request markers can't manufacture a false ACTION signal on its own
    // — it only strengthens an already-real verb match, exactly like "can you"/"could you"
    // already do.
    private val REQUEST_MARKERS = phrases(
        "can you", "could you", "would you", "need you to", "please", "kindly", "requesting that you"
    ) + listOf(
        Regex("\\bwhen you get a (?:chance|sec|second|minute|moment)\\b"),
        Regex("\\bat your convenience\\b")
    ) + arabicPhrases(
        "ممكن", "لو سمحت", "لو سمحتي", "من فضلك", "من فضلكي", "محتاج", "محتاجة", "بدي", "بدك",
        "عطيني", "عطني", "ياريت"
    )

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
        "hang tight", "hold up", "in progress",
        // Casual "hang on"/"hold on" family — large-scale validation turned up a big cluster
        // of these with no existing coverage ("almost got the fix ready, hang on", "hol on,
        // checkin on that rn") — "hol on" is a common dropped-letter typo of "hold on" that
        // TextNormalizer doesn't expand.
        "hang on", "hold on", "hol on",
        // "brb" normalizes to "be right back" via TextNormalizer.
        "be right back",
        // Additional third-person/formal future-commitment idioms in the same family as
        // "will follow up"/"will have" above — "will get to it", "will ship", "will update",
        // and the sender describing something as already queued/in the pipeline.
        "will get to it", "will ship", "will update", "queued up",
        // Literal "waiting" language — surprisingly absent before despite being the category's
        // own name; a message that says it's still waiting on something, or asks for more
        // time, is about as direct a WAITING signal as exists.
        "waiting to hear", "waiting on", "still waiting", "still sorting it out",
        "still finalizing",
        // A formal support/status-update idiom family ("We're aware of the issue and our
        // engineering team is actively investigating — we'll share an update...").
        "actively investigating", "we'll share an update", "will share an update",
        // Casual "swamped"/"radio silence" idioms for "busy, will get to it" and "sorry for
        // not responding" respectively.
        "swamped", "radio silence"
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
        // "give me until end of day"/"gimme til Tuesday, still sorting it out" — generalizes
        // the literal "give me a moment/second/minute" phrases above to any "give me ... <time
        // word>" construction, including the "til"/"till"/"until" family (note: "gimme"
        // normalizes to "give me" via TextNormalizer, but "til" does not normalize to "until").
        Regex("\\bgive me\\b.{0,25}\\b(?:min|mins|minute|minutes|moment|second|secs|sec|time|until|till|til|end of day|a bit)\\b"),
        // "almost got it figured out"/"almost there"/"almost got the fix ready" — broader than
        // the literal "almost done"/"about done" phrases above.
        Regex("\\balmost\\s+(?:done|ready|there|got|finished)\\b"),
        // "checking on that"/"checkin on that rn"/"check in with the team" — see the matching
        // suppression on ACTION_VERB_SUPPRESSION_EXTRAS["check"] for why this is also excluded
        // from ACTION's own "check" credit.
        Regex("\\bcheck(?:ing|in)?\\s+(?:on|in with)\\b"),
        // "he'll swing by and take a look" — a third-party visit/handle-it commitment idiom.
        Regex("\\bswing by\\b")
    ) +
        // "I'll call you back", "gonna send the file soon" (normalizes to "going to send...") —
        // see futureCommitmentPattern's doc on ACTION_VERB_SUPPRESSED_BY for why a real WAITING
        // match is needed here, not just suppressing the verb's ACTION score. Generalized to
        // EVERY ACTION_VERBS word (not just call/send/check/confirm) so any future-commitment-
        // framed request verb ("fixing to review it now", "going to go pick that up") reads as
        // WAITING the same way.
        ACTION_VERBS.map { Regex(futureCommitmentPattern(it.word)) } +
        // Arabic/Levantine waiting-and-pending phrasing — "جاري المراجعة"/"قيد الانتظار" are the
        // formal/automated "under review"/"pending" idioms named in the spec; "لسا"/"لسه"
        // ("still")/"عم اشتغل عليها" ("I'm working on it")/"راح ارجعلك"/"هرجعلك" ("I'll get back
        // to you") are the casual Jordanian equivalents of "i'll get back to you"/"working on
        // it" above.
        arabicPhrases(
            "جاري المراجعة", "قيد الانتظار", "قيد المعالجة", "جاري التنفيذ", "جاري التحقق",
            "لسا", "لسه", "عم اشتغل عليها", "راح ارجعلك", "هرجعلك", "خليني اتأكد", "لحظة",
            "ثانية واحدة", "بعطيك خبر", "تم الاستلام وجاري المراجعة"
        )

    private val REPLY_PATTERNS = phrases(
        "let me know", "lmk", "tell me", "get back to me", "call me", "text me", "hmu",
        "hit me up", "what do you think", "can you confirm", "keep me posted", "are you free",
        "when are you free",
        // Casual check-in/reply-seeking phrasing found via large-scale validation's REPLY-miss
        // analysis — none of these matched any prior pattern and fell through to the FYI
        // default. "thoughts" is deliberately bare (not "any thoughts on") since it shows up
        // standalone ("So... thoughts?", "🤔 thoughts?") as often as attached to an object.
        "thoughts", "any update", "no reply yet", "did you see my", "did you get my",
        "still up for", "still on for", "still meeting", "the verdict", "anyone home",
        "still there", "how about you", "what is up"
    ) + listOf(
        // "how was your day" — a casual check-in question with no ACTION_VERBS/REQUEST_MARKERS
        // match, so it previously scored 0 everywhere and fell through to a weak, essentially
        // arbitrary pick (real-device testing saw this land on ACTION at 12% confidence).
        Regex("\\bhow (?:was|is) your\\b"),
        // "did it work??"/"did that work for u" — a results/receipt check, distinct from
        // "did you see my"/"did you get my" above (those check on the MESSAGE, this checks on
        // an OUTCOME).
        Regex("\\bdid (?:it|that) work\\b"),
        // "did that make sense?" — a comprehension check.
        Regex("\\bmake sense\\b"),
        // "u good?"/"u there?" normalize to "you good"/"you there" via TextNormalizer.
        Regex("\\byou (?:good|there)\\b")
    ) + arabicPhrases(
        // MSA + Levantine reply-seeking phrasing: "خبرني"/"خبريني" ("tell me", masc/fem-
        // addressed), "قلي" ("tell me", colloquial contraction of "قل لي"), "شو رأيك"/"شو رايك"
        // ("what do you think"), "فيه/في حدا" ("is anyone there" — Levantine "حدا" = "anyone").
        "خبرني", "خبريني", "قلي", "رد علي", "ردي علي", "شو رأيك", "شو رايك", "في اخبار",
        "فيه اخبار", "شفت الرسالة", "وصلتك الرسالة", "لسا مستني رد", "فيه حدا", "في حدا",
        "انت موجود", "انتي موجودة", "شو الوضع", "ماشي الحال", "باقي منتظر ردك"
    ) + listOf(
        // A bare Arabic question mark — real casual Arabic texting almost never uses the ASCII
        // "?", so without this an Arabic question with no other REPLY-phrase match (e.g. "متى
        // بترجع؟") would score 0 everywhere and fall through to FYI. NOISE's own patterns still
        // win a tie against this (see TIE_BREAK_ORDER), so a promotional message phrased as a
        // question ("خصم 50%؟") still correctly lands on NOISE rather than REPLY.
        Regex("؟")
    )

    // Broader than DEADLINE_STANDALONE_DATE_PATTERNS — used only to populate extractedDate, not scoring.
    private val EXTRACT_DATE_PATTERN = Regex(
        "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tomorrow|tonight|" +
            "midnight|noon|end of (?:the )?month|end of (?:the )?week|next week|" +
            "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|\\d{1,2}(?::\\d{2})?\\s?(?:am|pm)|\\d{1,2}(?:st|nd|rd|th))\\b",
        RegexOption.IGNORE_CASE
    )

    // ---- Arabic (Modern Standard + Jordanian/Levantine colloquial) pattern coverage --------
    //
    // Kotlin's \b/\w in a plain Regex(...) are ASCII-only by default — verified directly against
    // the real java.util.regex engine this runs on (a throwaway jshell check): Pattern.compile(
    // "\\w").matcher("ع").find() is false, and Pattern.compile("\\bعندي\\b") fails to find
    // "عندي" in "مرحبا عندي سؤال" at all — UNLESS Pattern.UNICODE_CHARACTER_CLASS makes both \w
    // and \b use their real Unicode definitions instead of the English-only shortcut. An earlier
    // version of this file passed that flag straight to Pattern.compile(), which crashed every
    // real device it ran on: android.icu.util.regex.Pattern — what java.util.regex.Pattern
    // actually delegates to on Android, a completely different implementation from desktop
    // OpenJDK's — has never implemented UNICODE_CHARACTER_CLASS, on any API level, and rejects it
    // with IllegalArgumentException unconditionally. This was invisible in
    // NotificationClassifierTest because JVM unit tests run against the desktop JDK's own Pattern
    // class, where the flag IS implemented; it only surfaced once this ran inside an actual
    // Android process, where it failed inside this file's own <clinit> and took down
    // classification for every notification — English included, since this is all one class —
    // not just Arabic ones.
    //
    // Fixed without the flag at all: every \b below is a boundary against Arabic script
    // specifically, not against \w in general, so ARABIC_WORD_BOUNDARY reimplements exactly that
    // — a transition between "is an ARABIC_LETTER_CLASS char" and "isn't" — using only fixed-
    // width lookaround and explicit \u escapes, both of which are ordinary regex features Android
    // has always supported. Covers the Arabic, Arabic Supplement, Arabic Extended-A, and Arabic
    // Presentation Forms A/B Unicode blocks, which is every code point these hand-written
    // Arabic/Levantine patterns can actually contain.
    private const val ARABIC_LETTER_CLASS =
        "[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]"
    private const val ARABIC_WORD_BOUNDARY =
        "(?:(?<!$ARABIC_LETTER_CLASS)(?=$ARABIC_LETTER_CLASS)|(?<=$ARABIC_LETTER_CLASS)(?!$ARABIC_LETTER_CLASS))"

    private fun arabicPattern(pattern: String): Regex = pattern.replace("\\b", ARABIC_WORD_BOUNDARY).toRegex()

    private fun arabicPhrases(vararg raw: String): List<Regex> = raw.map { arabicPattern("\\b${Regex.escape(it)}\\b") }

    // Day/date/relative-time words shared by the Arabic "حتى/قبل + anchor" mirrors of
    // BY_DEADLINE_PATTERN/BEFORE_DEADLINE_PATTERN below — mirrors DEADLINE_ANCHOR_DATES' own
    // rationale: a bare "حتى"/"قبل" is exactly as generic as English "by"/"before" ("حتى الآن" =
    // "until now", "قبل ما" = "before ...") to trust as a deadline signal without being anchored
    // to an actual day/date/time. Includes common Levantine/Jordanian colloquial relative-day
    // words (بكرا/بكره/غدا all mean "tomorrow") alongside MSA month names.
    private const val ARABIC_DEADLINE_ANCHOR_DATES =
        "(?:يوم\\s+)?(الاثنين|الثلاثاء|الأربعاء|الاربعاء|الخميس|الجمعة|السبت|الأحد|الاحد|" +
            "اليوم|الليلة|بكرا|بكره|غدا|منتصف الليل|الظهر|نهاية (?:ال)?شهر|نهاية (?:ال)?اسبوع|" +
            "الأسبوع الجاي|الاسبوع الجاي|" +
            "يناير|فبراير|مارس|أبريل|ابريل|مايو|يونيو|يوليو|أغسطس|اغسطس|سبتمبر|أكتوبر|اكتوبر|نوفمبر|ديسمبر|" +
            "الساعة\\s?\\d{1,2}(?::\\d{2})?|\\d{1,2}/\\d{1,2})"

    // "حتى الجمعة" ("until Friday") — the Arabic mirror of BY_DEADLINE_PATTERN.
    private val ARABIC_BY_DEADLINE_PATTERN = arabicPattern("\\bحتى\\s+$ARABIC_DEADLINE_ANCHOR_DATES")

    // "قبل بكرا" ("before tomorrow") — the Arabic mirror of BEFORE_DEADLINE_PATTERN.
    private val ARABIC_BEFORE_DEADLINE_PATTERN = arabicPattern("\\bقبل\\s+$ARABIC_DEADLINE_ANCHOR_DATES")

    // The weak, unanchored form — mirrors BARE_BEFORE_PATTERN ("قبل" alone, e.g. "خلص الشغل قبل
    // ما تروح" = "finish the work before you go", an ordinary subordinating use with no deadline
    // intent at all).
    private val ARABIC_BARE_BEFORE_PATTERN = arabicPattern("\\bقبل\\b")

    // Bare day-name/relative-phrase mentions, weak on their own — the Arabic mirror of
    // DEADLINE_STANDALONE_DATE_PATTERNS.
    private val ARABIC_DEADLINE_STANDALONE_DATE_PATTERNS: List<Regex> = arabicPhrases(
        "الاثنين", "الثلاثاء", "الأربعاء", "الاربعاء", "الخميس", "الجمعة", "السبت", "الأحد", "الاحد",
        "الأسبوع الجاي", "الاسبوع الجاي"
    ) + listOf(
        arabicPattern("نهاية (?:ال)?شهر"),
        arabicPattern("نهاية (?:ال)?اسبوع")
    )

    // Broader set that only strengthens a deadline already established by a strong due/expiry
    // word — the Arabic mirror of DEADLINE_COMBO_DATE_PATTERNS.
    private val ARABIC_DEADLINE_COMBO_DATE_PATTERNS: List<Regex> = ARABIC_DEADLINE_STANDALONE_DATE_PATTERNS +
        arabicPhrases(
            "اليوم", "بكرا", "بكره", "غدا", "الليلة", "منتصف الليل", "الظهر",
            "يناير", "فبراير", "مارس", "أبريل", "ابريل", "مايو", "يونيو", "يوليو",
            "أغسطس", "اغسطس", "سبتمبر", "أكتوبر", "اكتوبر", "نوفمبر", "ديسمبر"
        ) + listOf(
            arabicPattern("الساعة\\s?\\d{1,2}(?::\\d{2})?")
        )

    // Used only to populate extractedDate for an Arabic-only deadline — the Arabic mirror of
    // EXTRACT_DATE_PATTERN.
    private val ARABIC_EXTRACT_DATE_PATTERN = arabicPattern(
        "\\b(الاثنين|الثلاثاء|الأربعاء|الاربعاء|الخميس|الجمعة|السبت|الأحد|الاحد|اليوم|بكرا|بكره|غدا|الليلة|" +
            "منتصف الليل|الظهر|نهاية (?:ال)?شهر|نهاية (?:ال)?اسبوع|الأسبوع الجاي|الاسبوع الجاي|" +
            "يناير|فبراير|مارس|أبريل|ابريل|مايو|يونيو|يوليو|أغسطس|اغسطس|سبتمبر|أكتوبر|اكتوبر|نوفمبر|ديسمبر|" +
            "الساعة\\s?\\d{1,2}(?::\\d{2})?)\\b"
    )

    // Common Jordanian/Levantine request verbs, MSA + colloquial surface forms listed side by
    // side (e.g. MSA "أرسل"/"ارسل" next to colloquial "ابعت"/"ابعتلي"). Arabic morphology doesn't
    // inflect by a simple suffix the way English does (contrast ACTION_VERB_SUFFIX_OVERRIDES),
    // so each realistic surface form actually seen in casual texting is listed explicitly
    // instead — consistent with this object's own "curated v1 heuristic, not a generative
    // pipeline" philosophy (see the class doc at the top of this file). [directed] checks for a
    // following "لي"/"لنا"/"إلي"/"الي" ("to me"/"to us") within a couple of words — the Arabic
    // equivalent of the English verbs' "me/this/that" object check.
    private fun arabicVerb(vararg forms: String): ActionVerb {
        val alternation = forms.joinToString("|") { Regex.escape(it) }
        return ActionVerb(
            word = forms.first(),
            bare = arabicPattern("\\b(?:$alternation)\\b"),
            directed = arabicPattern("\\b(?:$alternation)\\b(?:\\s+\\S+){0,2}?\\s*(?:لي|لنا|إلي|الي)\\b")
        )
    }

    private val ARABIC_ACTION_VERBS: List<ActionVerb> = listOf(
        // Both the bare imperative ("ارسل"/"ابعتلي") AND the second-person "ت-" present-tense
        // form ("ترسل"/"ترسلي") are included for every verb below — real Jordanian/Levantine
        // requests are phrased at least as often as "ممكن ترسلي..." ("can you send...") as they
        // are as a bare command, and only covering the imperative missed that whole class of
        // real phrasing (see the ARABIC_ACTION_VERB_SUPPRESSED_BY / test-driven fixes this
        // section went through).
        arabicVerb("ارسل", "أرسل", "ارسلي", "أرسلي", "ترسل", "ترسلي", "ابعت", "ابعتي", "ابعتلي", "ابعتيلي"),
        arabicVerb(
            "جيب", "جيبي", "جيبلي", "جيبيلي", "تجيب", "تجيبي", "تجيبلي", "تجيبيلي",
            "احضر", "أحضر", "احضري", "أحضري", "احضريلي", "أحضريلي", "تحضر", "تحضري"
        ),
        arabicVerb("ادفع", "إدفع", "ادفعي", "تدفع", "تدفعي", "سدد", "سددي", "تسدد", "تسددي"),
        arabicVerb("اتصل", "إتصل", "اتصلي", "تتصل", "تتصلي", "كلمني", "كلميني"),
        arabicVerb("تأكد", "اتأكد", "تأكدي", "أكد", "اكد", "اكدي", "أكدي"),
        arabicVerb("راجع", "راجعي", "تراجع", "تراجعي"),
        arabicVerb("خلص", "خلصي", "تخلص", "تخلصي", "أكمل", "اكمل", "أكملي", "اكملي", "تكمل", "تكملي"),
        arabicVerb("الغي", "الغى", "تلغي", "كنسل"),
        arabicVerb("وافق", "وافقي", "توافق", "توافقي"),
        // Deliberately NOT given a "ت-" present-tense form ("توقع"/"توقعي") — that spelling
        // collides with the completely unrelated, much more common verb "توقع" ("to expect/
        // predict": "ما توقعت هيك رد" = "I didn't expect such a reply"), which would falsely
        // fire an ACTION signal on an ordinary expectation statement that has nothing to do with
        // signing anything. The bare imperative "وقع"/"وقعي" carries the same (much smaller,
        // already-accepted) ambiguity risk as the rest of this curated v1 list.
        arabicVerb("وقع", "وقعي"),
        arabicVerb("استلم", "استلمي", "تستلم", "تستلمي"),
        arabicVerb("اشتري", "اشترِ", "اشتر", "تشتري")
    )

    // "خليني اتأكد" ("let me just check/make sure") is one of WAITING_PATTERNS' own Arabic
    // phrases (see above) — a self-commitment to verify something, not a request directed at the
    // recipient. Without this, "اتأكد" inside that exact phrase ALSO matched as a bare ACTION
    // verb, tying WAITING's own phrase-match score and losing the tie to ACTION (see
    // TIE_BREAK_ORDER) — mirrors the English ACTION_VERB_SUPPRESSED_BY map's "check" entry for
    // the same underlying reason (found the same way: a real test case misclassifying).
    private val ARABIC_ACTION_VERB_SUPPRESSED_BY: Map<String, Regex> = mapOf(
        "تأكد" to arabicPattern("خليني\\s+(?:تأكد|اتأكد|تأكدي)\\b")
    )
}
