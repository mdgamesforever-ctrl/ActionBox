package com.futurepath.actionbox.classification

/**
 * A large-scale (10,000+ example) validation corpus, independent of [SyntheticNotificationCorpus]
 * and [DiverseNotificationCorpus], built to stress-test the classifier at a scale neither of
 * those reaches (1464 and 318 examples respectively) and to specifically exercise the
 * structural/metadata signals added on top of word-content scoring (impersonal-sender,
 * banking-template, delivery-template, unnamed-bot-formatting, gaming-package, calendar-package,
 * noreply@ detection) — none of which the existing corpora meaningfully triggers, since they
 * were written before those signals existed.
 *
 * Two sources of volume, deliberately kept separate so neither masks the other:
 *  1. [generalTemplates] — new template sentences (not copies of existing ones) across all six
 *     categories, WAITING weighted higher (more templates AND more variants) since real-device
 *     testing found it the historically weakest category. Realistic messiness — typos, slang,
 *     mixed formality, short fragments through rambling multi-clause messages — is written
 *     directly into the template text rather than injected via random perturbation, so every
 *     case's ground truth stays unambiguous.
 *  2. The `*Cases()` functions — hand-built cases exercising each structural signal directly:
 *     banking transaction templates, delivery templates, Discord bot formatting (both
 *     named-bot and unnamed-bot-by-format), gaming-package notifications, social-media
 *     engagement bait from a brand-name sender (contrasted with the same package's personal
 *     DMs), calendar-package notifications, and noreply@ senders.
 */
object LargeScaleValidationCorpus {

    private val NAMES = SyntheticNotificationCorpus.NAMES
    private val DAYS = SyntheticNotificationCorpus.DAYS
    private val WHATSAPP = SyntheticNotificationCorpus.WHATSAPP
    private val SMS = SyntheticNotificationCorpus.SMS
    private val GMAIL = SyntheticNotificationCorpus.GMAIL
    private val fill = SyntheticNotificationCorpus::fill

    private fun t(text: String, expected: ClassifiedState, sourceApp: String, senders: List<String>, variants: Int = 60) =
        SyntheticNotificationCorpus.Template(text, expected, sourceApp, senders, variants, isPhase2Original = false)

    // ---- Extra substitution pools for the structural-signal generators -----------------

    private val BANKS = listOf(
        "Chase", "Wells Fargo", "Bank of America", "Citibank", "PNC Bank",
        "US Bank", "Capital One", "TD Bank", "Regions Bank", "Ally Bank"
    )
    private val ACCOUNT_SUFFIXES = listOf("1234", "6791", "4420", "8890", "3301", "7712", "0099", "5567", "2246", "9981")
    private val BANK_AMOUNTS = listOf(
        "$45.00", "$120.50", "$9.99", "$1,200.00", "$76.25", "$500.00", "$2,300.00", "$18.30", "$64.75", "$999.00",
        "Rs.5,000", "Rs.248,759.00", "€32.10", "£88.40", "₹1,500"
    )
    private val CARRIERS = listOf("Amazon", "UPS", "FedEx", "DHL", "USPS")
    private val DELIVERY_STATUS_VERBS = listOf("shipped", "delivered", "dispatched", "out for delivery")
    private val GAME_PACKAGES = listOf(
        "com.king.candycrushsaga" to "Candy Crush Saga",
        "com.supercell.clashofclans" to "Clash of Clans",
        "com.supercell.clashroyale" to "Clash Royale",
        "com.mojang.minecraftpe" to "Minecraft",
        "com.nianticlabs.pokemongo" to "Pokémon GO",
        "com.rovio.angrybirds" to "Angry Birds",
        "com.king.candycrushsodasaga" to "Candy Crush Soda Saga"
    )
    private val GAME_HOOKS = listOf(
        "Claim your daily reward now!", "Event ends in 3 hours!", "Your energy is full!",
        "Login streak bonus available!", "New event starting soon, don't miss out!",
        "Your lives have been refilled!", "Special offer inside, limited time!",
        "Level up! Claim your prize.", "Daily quest available, come back and play!",
        "Your friend passed you on the leaderboard!"
    )
    private val DISCORD_STAT_LABELS = listOf("Entries", "Kills", "Deaths", "Wins", "Losses", "Level", "XP", "Participants", "Time Left", "Rank")
    private val DISCORD_SENDERS_UNNAMED = listOf("Server Announcements", "Event Coordinator", "Guild Assistant", "Community Updates", "Mod Tools")
    private val DISCORD_SENDERS_NAMED_BOT = listOf("GiveawayBot", "RaidBot", "MEE6 Bot", "EventBot", "StatsBot")
    private val NOREPLY_SENDERS = listOf("noreply@dealsstore.com", "no-reply@promoemail.com", "do-not-reply@newsletter.com", "noreply@flashsales.net")
    private val BRAND_SENDER_PACKAGES = listOf(
        "com.instagram.android" to "Instagram",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.facebook.katana" to "Facebook",
        "com.twitter.android" to "Twitter",
        "com.google.android.youtube" to "YouTube",
        "com.pinterest" to "Pinterest",
        "com.snapchat.android" to "Snapchat"
    )

    // ---- 1. Banking transaction templates (exercises isBankingTransactionTemplate) ------

    private fun bankingCases(): List<SyntheticNotificationCorpus.Case> {
        val cases = mutableListOf<Case>()
        var ti = 20000
        val nonDueFrames = listOf(
            "{amt} debited from A/c ****{acct}. Avail Bal: {bal}.",
            "{amt} credited to A/c XXXX{acct}. Available Balance: {bal}.",
            "Rs.{amt} withdrawn from A/c ****{acct} at ATM. Bal: {bal}.",
            "{bank}: {amt} spent on your card ending {acct}. Avail Bal: {bal}.",
            "{amt} was debited from your account ****{acct} on {day}. Balance: {bal}."
        )
        val dueFrames = listOf(
            "Payment of {amt} due from A/c ****{acct} on {day}. Avail Bal: {bal}.",
            "{bank}: minimum payment {amt} due {day} for card ending {acct}. Balance: {bal}.",
            "Autopay of {amt} scheduled for {day} from A/c XXXX{acct}, expires if unpaid. Bal: {bal}."
        )
        for ((i, frame) in nonDueFrames.withIndex()) {
            for (v in 0 until 120) {
                val amt = BANK_AMOUNTS[(v * 3 + i) % BANK_AMOUNTS.size]
                val acct = ACCOUNT_SUFFIXES[(v * 7 + i) % ACCOUNT_SUFFIXES.size]
                val bal = BANK_AMOUNTS[(v * 11 + i + 1) % BANK_AMOUNTS.size]
                val bank = BANKS[(v * 5 + i) % BANKS.size]
                val day = DAYS[(v * 2 + i) % DAYS.size]
                val text = frame.replace("{amt}", amt).replace("{acct}", acct).replace("{bal}", bal)
                    .replace("{bank}", bank).replace("{day}", day)
                cases += SyntheticNotificationCorpus.Case("com.examplebank.mobile", "Bank Alert", text, ClassifiedState.FYI, ti, v, false)
            }
            ti++
        }
        for ((i, frame) in dueFrames.withIndex()) {
            for (v in 0 until 120) {
                val amt = BANK_AMOUNTS[(v * 3 + i) % BANK_AMOUNTS.size]
                val acct = ACCOUNT_SUFFIXES[(v * 7 + i) % ACCOUNT_SUFFIXES.size]
                val bal = BANK_AMOUNTS[(v * 11 + i + 1) % BANK_AMOUNTS.size]
                val bank = BANKS[(v * 5 + i) % BANKS.size]
                val day = DAYS[(v * 2 + i) % DAYS.size]
                val text = frame.replace("{amt}", amt).replace("{acct}", acct).replace("{bal}", bal)
                    .replace("{bank}", bank).replace("{day}", day)
                cases += SyntheticNotificationCorpus.Case("com.examplebank.mobile", "Bank Alert", text, ClassifiedState.DEADLINE, ti, v, false)
            }
            ti++
        }
        return cases
    }

    // ---- 2. Delivery/shipping templates (exercises isDeliveryStatusTemplate) -----------

    private fun deliveryCases(): List<SyntheticNotificationCorpus.Case> {
        val cases = mutableListOf<Case>()
        var ti = 21000
        val framesWithOrderNumber = listOf(
            "Order #{order} has {verb} via {carrier}.",
            "Your {carrier} package #{order} was {verb} today.",
            "Tracking #{order}: {verb} and on its way."
        )
        val framesNoOrderNumber = listOf(
            "Package {verb}.", "Your order was {verb}.", "{verb2}!", "Your item has been {verb}."
        )
        for ((i, frame) in framesWithOrderNumber.withIndex()) {
            for (v in 0 until 120) {
                val order = 40000 + v * 137 + i * 977
                val carrier = CARRIERS[(v + i) % CARRIERS.size]
                val verb = DELIVERY_STATUS_VERBS[(v * 3 + i) % DELIVERY_STATUS_VERBS.size]
                val text = frame.replace("{order}", order.toString()).replace("{carrier}", carrier).replace("{verb}", verb)
                cases += SyntheticNotificationCorpus.Case(GMAIL, carrier, text, ClassifiedState.FYI, ti, v, false)
            }
            ti++
        }
        for ((i, frame) in framesNoOrderNumber.withIndex()) {
            for (v in 0 until 100) {
                val carrier = CARRIERS[(v + i) % CARRIERS.size]
                val verb = DELIVERY_STATUS_VERBS[(v * 3 + i) % DELIVERY_STATUS_VERBS.size]
                val text = frame.replace("{verb}", verb).replace("{verb2}", verb.replaceFirstChar { it.uppercase() })
                cases += SyntheticNotificationCorpus.Case("com.amazon.mShop.android.shopping", carrier, text, ClassifiedState.FYI, ti, v, false)
            }
            ti++
        }
        return cases
    }

    // ---- 3. Discord bot formatting: named-bot senders AND unnamed-bot-by-format --------

    private fun discordBotCases(): List<SyntheticNotificationCorpus.Case> {
        val cases = mutableListOf<Case>()
        var ti = 22000
        val giveawayFrames = listOf(
            "🎉 GIVEAWAY 🎉\n@here @role-vip\n{s1}: {v1} | {s2}: {v2}\nGood luck everyone!",
            "📢 RAID STARTING 📢\n@everyone\n{s1}: {v1} | {s2}: {v2}\nBe ready!",
            "⏰ EVENT REMINDER ⏰\n@here @raid-team\n{s1}: {v1} | {s2}: {v2}\nDon't be late!",
            "🏆 TOURNAMENT UPDATE 🏆\n@player1 @player2 @player3\n{s1}: {v1} | {s2}: {v2}\nGG!"
        )
        for ((i, frame) in giveawayFrames.withIndex()) {
            for (v in 0 until 120) {
                val s1 = DISCORD_STAT_LABELS[(v + i) % DISCORD_STAT_LABELS.size]
                val s2 = DISCORD_STAT_LABELS[(v + i + 3) % DISCORD_STAT_LABELS.size]
                val text = frame.replace("{s1}", s1).replace("{v1}", (v * 13 + 5).toString())
                    .replace("{s2}", s2).replace("{v2}", (v * 7 + 2).toString())
                val sender = if (v % 2 == 0) DISCORD_SENDERS_NAMED_BOT[v % DISCORD_SENDERS_NAMED_BOT.size]
                    else DISCORD_SENDERS_UNNAMED[v % DISCORD_SENDERS_UNNAMED.size]
                cases += SyntheticNotificationCorpus.Case("com.discord", sender, text, ClassifiedState.NOISE, ti, v, false)
            }
            ti++
        }
        return cases
    }

    // ---- 4. Gaming-package notifications (exercises the gaming-package NOISE prior) ----

    private val GAME_REWARDS = listOf("gold", "gems", "coins", "energy", "lives", "chests", "boosters", "tickets")

    private fun gamingCases(): List<SyntheticNotificationCorpus.Case> {
        val cases = mutableListOf<Case>()
        var ti = 23000
        for ((gi, pkg) in GAME_PACKAGES.withIndex()) {
            for ((hi, hook) in GAME_HOOKS.withIndex()) {
                for ((ri, reward) in GAME_REWARDS.withIndex()) {
                    val text = hook.replace("reward", reward).replace("prize", reward)
                    cases += SyntheticNotificationCorpus.Case(pkg.first, pkg.second, text, ClassifiedState.NOISE, ti, gi * 100 + hi * 10 + ri, false)
                }
            }
        }
        return cases
    }

    // ---- 5. Social-media engagement bait from a brand-name sender (exercises ------------
    // ---- isImpersonalSender) ------------------------------------------------------------
    // NOTE: no "personal DM through the same package" contrast case here — every package in
    // BRAND_SENDER_PACKAGES is also in NotificationClassifier's own NOISE_PACKAGES set, which
    // biases the ENTIRE package toward NOISE regardless of sender (a pre-existing, Phase-2-era
    // simplification, not something introduced by the structural signals) — a contrast case
    // would just be testing an interaction the classifier was never designed to resolve, not
    // a real finding. isImpersonalSender's actual sender-vs-brand distinction is already
    // covered properly in StructuralSignalsTest against non-NOISE_PACKAGES text.

    private fun socialEngagementBaitCases(): List<SyntheticNotificationCorpus.Case> {
        val cases = mutableListOf<Case>()
        var ti = 24000
        val baitTexts = listOf(
            "{name} liked your photo", "You have a new follower", "See what's trending near you",
            "Your weekly recap is ready", "{name} started following you", "New post from someone you follow",
            "Someone viewed your profile", "Your story was viewed 42 times", "Check out today's top posts"
        )
        for ((bi, brand) in BRAND_SENDER_PACKAGES.withIndex()) {
            for (v in 0 until 100) {
                val text = fill(baitTexts[(v + bi) % baitTexts.size], v)
                cases += SyntheticNotificationCorpus.Case(brand.first, brand.second, text, ClassifiedState.NOISE, ti, v, false)
            }
            ti++
        }
        return cases
    }

    // ---- 6. Calendar-package notifications (exercises the calendar-package prior) ------

    private fun calendarCases(): List<SyntheticNotificationCorpus.Case> {
        val cases = mutableListOf<Case>()
        var ti = 25000
        val fyiFrames = listOf(
            "Meeting moved to {time} on {day}.", "Meeting rescheduled to {day}.",
            "Event cancelled: {day} sync is off.", "Calendar updated: new event added for {day}."
        )
        val deadlineFrames = listOf(
            "RSVP deadline for the {day} event is tomorrow.", "Event starts in 10 minutes.",
            "Reminder: submit your availability for {day} by end of day."
        )
        val times = listOf("9am", "10:30am", "noon", "2pm", "5pm", "6:45pm")
        for ((i, frame) in fyiFrames.withIndex()) {
            for (v in 0 until 80) {
                val text = frame.replace("{day}", DAYS[(v + i) % DAYS.size]).replace("{time}", times[(v + i) % times.size])
                cases += SyntheticNotificationCorpus.Case("com.google.android.calendar", "Calendar", text, ClassifiedState.FYI, ti, v, false)
            }
            ti++
        }
        for ((i, frame) in deadlineFrames.withIndex()) {
            for (v in 0 until 80) {
                val text = frame.replace("{day}", DAYS[(v + i) % DAYS.size])
                cases += SyntheticNotificationCorpus.Case("com.google.android.calendar", "Calendar", text, ClassifiedState.DEADLINE, ti, v, false)
            }
            ti++
        }
        return cases
    }

    // ---- 7. noreply@ senders (marketing/promo content — a clean, unambiguous NOISE label)

    private fun noreplyCases(): List<SyntheticNotificationCorpus.Case> {
        val cases = mutableListOf<Case>()
        var ti = 26000
        val texts = listOf(
            "Big savings just for you, shop the sale now!", "Don't miss this week's deals.",
            "Your exclusive discount code is inside.", "New arrivals just dropped, check them out."
        )
        for ((i, sender) in NOREPLY_SENDERS.withIndex()) {
            for (v in 0 until 60) {
                cases += SyntheticNotificationCorpus.Case(GMAIL, sender, texts[(v + i) % texts.size], ClassifiedState.NOISE, ti, v, false)
            }
            ti++
        }
        return cases
    }

    // ---- General templates: new structures across all six categories, WAITING weighted -

    private val generalTemplates: List<SyntheticNotificationCorpus.Template> = listOf(
        // ---------------- ACTION (realistic messiness: typos, varying length) ----------
        t("Plz send the invocie before EOD, thx.", ClassifiedState.ACTION, WHATSAPP, NAMES),
        t("Can u finish the report n send it ovr 2day?", ClassifiedState.ACTION, WHATSAPP, NAMES),
        t("Kindly arrange for the shipment to be picked up by {day}.", ClassifiedState.ACTION, GMAIL, listOf("Vendor", "Logistics")),
        t("yo pls dont forget to pay the internet bill", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t(
            "I know this is a lot to ask but could you possibly review the entire contract " +
                "one more time before we send it to the client, just to make sure there are no " +
                "typos or missing clauses since this is a pretty big deal for us",
            ClassifiedState.ACTION, GMAIL, listOf("Manager")
        ),
        t("submit ur timesheet b4 {day} or payroll gets delayed", ClassifiedState.ACTION, SMS, listOf("HR")),
        t("Reboot the router when you get a chance?", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t("PLEASE water the plants while im away!!", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t("Kindly authorize the expense report at your convenience.", ClassifiedState.ACTION, GMAIL, listOf("Manager", "Finance")),
        t("hey can u proofread this rq before i send it", ClassifiedState.ACTION, WHATSAPP, NAMES),

        // ---------------- REPLY -----------------------------------------------------
        t("hey u good? been a min since i heard from u", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("Any update on the offer letter?", ClassifiedState.REPLY, GMAIL, listOf("Recruiter")),
        t("so did u end up going or nah", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("Just circling back — do you have thoughts on the draft?", ClassifiedState.REPLY, GMAIL, listOf("Client", "Manager")),
        t("wyd this weekend, lmk", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("r we still meeting {day}?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("Following up on my last email — any thoughts?", ClassifiedState.REPLY, GMAIL, listOf("Client")),
        t("did it work??", ClassifiedState.REPLY, WHATSAPP, NAMES),

        // ---------------- WAITING (extra weight: more templates, more variants) --------
        t("gimme til {day}, still sorting it out", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t("Rest assured, our team is on it and will follow up by {day}.", ClassifiedState.WAITING, GMAIL, listOf("Support"), variants = 100),
        t("lemme double check n ill lyk", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t("Still gathering the numbers, should have them shortly.", ClassifiedState.WAITING, GMAIL, listOf("Manager", "Finance"), variants = 100),
        t("otw to grab it now", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t("kinda swamped rn but will get to it soon", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t(
            "We're aware of the issue and our engineering team is actively investigating — " +
                "we'll share an update as soon as we have more information.",
            ClassifiedState.WAITING, GMAIL, listOf("Support"), variants = 20
        ),
        t("bout to jump on a call, ill send it after", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t("fixing to review it now, gimme a bit", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t("The contractor said he'll swing by and take a look tomorrow.", ClassifiedState.WAITING, WHATSAPP, listOf("Roommate"), variants = 100),
        t("hol on, checkin on that rn", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t("Still waiting to hear back from the vendor, will update once I know more.", ClassifiedState.WAITING, GMAIL, listOf("Manager"), variants = 100),
        t("brb, gonna go check with the front desk", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t("They mentioned it's queued up and will ship soon.", ClassifiedState.WAITING, GMAIL, listOf("Vendor"), variants = 100),
        t("one sec, pulling up the tracking info", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t("Support confirmed they're escalating and will follow up shortly.", ClassifiedState.WAITING, GMAIL, listOf("Support"), variants = 100),
        t("almost got the fix ready, hang on", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t("Give me until end of day, still finalizing the numbers.", ClassifiedState.WAITING, GMAIL, listOf("Finance", "Manager"), variants = 100),
        t("she said she's bout to leave, should be there soon", ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 100),
        t(
            "sorry for the radio silence, ive been slammed but i promise im getting to this " +
                "today and will have something for u by tonight",
            ClassifiedState.WAITING, WHATSAPP, NAMES, variants = 20
        ),

        // ---------------- DEADLINE ----------------------------------------------------
        t("Heads up — the scholarship application closes {day}.", ClassifiedState.DEADLINE, GMAIL, listOf("Registrar")),
        t("dont forget rent is due the 1st", ClassifiedState.DEADLINE, SMS, listOf("Landlord")),
        t("Final reminder: RSVP required by noon {day}.", ClassifiedState.DEADLINE, GMAIL, listOf("Events Team")),
        t("passport renewal cutoff is {day}, dont wait", ClassifiedState.DEADLINE, WHATSAPP, listOf("Roommate")),
        t("Your subscription expires at midnight tonight.", ClassifiedState.DEADLINE, GMAIL, listOf("Streaming Co")),
        t("last day for early bird pricing is {day}", ClassifiedState.DEADLINE, GMAIL, listOf("Events Team")),
        t("Tax filing deadline is coming up fast, don't miss it.", ClassifiedState.DEADLINE, GMAIL, listOf("Accountant")),

        // ---------------- FYI --------------------------------------------------------
        t("Your prescription is ready for pickup.", ClassifiedState.FYI, GMAIL, listOf("Pharmacy")),
        t("just landed, all good", ClassifiedState.FYI, WHATSAPP, NAMES),
        t("Your table for {day} is confirmed.", ClassifiedState.FYI, GMAIL, listOf("Restaurant")),
        t("car's fixed, picking it up now", ClassifiedState.FYI, WHATSAPP, listOf("Roommate")),
        t("Your review has been submitted, thanks for your feedback.", ClassifiedState.FYI, "com.amazon.mShop.android.shopping", listOf("Amazon")),
        t("fyi wifi's back up", ClassifiedState.FYI, WHATSAPP, listOf("Roommate")),
        t("Your document has been signed by all parties.", ClassifiedState.FYI, GMAIL, listOf("Legal Team")),

        // ---------------- NOISE (general promo/engagement, no structural pattern) -----
        t("You've got mail! Open the app to see what's new.", ClassifiedState.NOISE, GMAIL, listOf("Service")),
        t("omg have u seen this trend", ClassifiedState.NOISE, "com.twitter.android", listOf("Twitter")),
        t("Your monthly recap is here — see your top moments!", ClassifiedState.NOISE, "com.facebook.katana", listOf("Facebook")),
        t("just dropped: new collection, shop now", ClassifiedState.NOISE, "com.zhiliaoapp.musically", listOf("Shop"))
    )

    fun buildCorpus(): List<SyntheticNotificationCorpus.Case> {
        val cases = mutableListOf<Case>()
        generalTemplates.forEachIndexed { templateIndex, template ->
            for (i in 0 until template.variants) {
                cases += SyntheticNotificationCorpus.Case(
                    sourceApp = template.sourceApp,
                    sender = template.senders[i % template.senders.size],
                    text = fill(template.text, i),
                    expected = template.expected,
                    templateIndex = 10000 + templateIndex,
                    variantIndex = i,
                    isPhase2Original = false
                )
            }
        }
        cases += bankingCases()
        cases += deliveryCases()
        cases += discordBotCases()
        cases += gamingCases()
        cases += socialEngagementBaitCases()
        cases += calendarCases()
        cases += noreplyCases()
        return cases
    }
}
