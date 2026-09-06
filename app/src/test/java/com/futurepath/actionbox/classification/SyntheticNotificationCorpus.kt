package com.futurepath.actionbox.classification

/**
 * The Phase 2 synthetic notification corpus, extracted out of [NotificationClassifierCorpusTest]
 * so it can be reused by [MlTrainingDataExportTest] without duplicating the template list.
 * ~100 template sentences per category (formal, casual/slang, and edge-case phrasings modeled
 * on WhatsApp/SMS, Gmail, banking, delivery, calendar, and social apps), each expanded into
 * many variants by substituting names/days/times/amounts/items — templated synthetic data, not
 * independently hand-written sentences, but the substitution deliberately varies which value
 * lands in which slot per variant so the surface text differs meaningfully, not just
 * cosmetically (see [fill]'s per-slot step sizes, chosen so the combined substitution pattern
 * doesn't repeat for 420 variants).
 *
 * [Template.variants] was raised from Phase 2's original 12 to 24 for Phase 4's ML training
 * per the "expand it further... for training data volume" instruction, and 28 new templates
 * were added to the WAITING/FYI/NOISE categories (94 -> 122 templates total, 2928 examples) —
 * an early training run showed those three categories generalizing far worse than
 * ACTION/REPLY/DEADLINE to phrasing the model hadn't seen during training, and they were also
 * the three categories with the fewest distinct templates to begin with (14/15/12 vs.
 * ACTION's 23), so more DISTINCT phrasing rather than more variants of the same phrasing was
 * the fix (see tools/train_and_export_tflite_model.py's template-holdout report). The
 * rule-based NotificationClassifier's accuracy numbers reported by
 * [NotificationClassifierCorpusTest] will shift as a result of both changes (more templates,
 * more examples per template), which is expected and doesn't change either of the two accepted
 * ambiguity clusters from Phase 2.
 */
object SyntheticNotificationCorpus {

    data class Case(
        val sourceApp: String,
        val sender: String,
        val text: String,
        val expected: ClassifiedState,
        // Which Template this case was generated from — lets a held-out evaluation split by
        // *template* (unseen phrasing) rather than just by row (which would only ever hold out
        // a different name/day/amount substituted into an already-seen sentence pattern).
        val templateIndex: Int
    )

    // ---- Substitution value pools ----------------------------------------------------

    private val NAMES = listOf(
        "Priya", "Sam", "Jordan", "Taylor", "Morgan", "Chris", "Dana", "Jamie", "Robin",
        "Casey", "Riley", "Quinn", "Avery", "Sasha", "Alex"
    )
    private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val TIMES = listOf("9am", "10:30am", "noon", "2pm", "5pm", "6:45pm")
    private val AMOUNTS = listOf("$45.00", "$120.50", "$9.99", "$1,200.00", "$76.25", "$18.30")
    private val ITEMS = listOf(
        "the report", "the invoice", "the package", "the contract", "the presentation",
        "the form", "the payment", "the file", "the document", "the order"
    )
    private val CODES = listOf("482911", "017234", "993051", "126480")
    private val PERCENTS = listOf("30", "50", "70", "25")

    private fun fill(template: String, i: Int): String = template
        .replace("{name}", NAMES[i % NAMES.size])
        .replace("{day}", DAYS[(i * 3 + 1) % DAYS.size])
        .replace("{time}", TIMES[(i * 5 + 2) % TIMES.size])
        .replace("{amount}", AMOUNTS[(i * 7 + 3) % AMOUNTS.size])
        .replace("{item}", ITEMS[(i * 11 + 4) % ITEMS.size])
        .replace("{code}", CODES[(i * 13 + 1) % CODES.size])
        .replace("{percent}", PERCENTS[(i * 17 + 2) % PERCENTS.size])

    // ---- App packages ------------------------------------------------------------------

    private val WHATSAPP = "com.whatsapp"
    private val SMS = "com.google.android.apps.messaging"
    private val GMAIL = "com.google.android.gm"
    private val BANK = "com.examplebank.mobile"
    private val AMAZON = "com.amazon.mShop.android.shopping"
    private val DOORDASH = "com.dd.doordash"
    private val CALENDAR = "com.google.android.calendar"
    private val YOUTUBE = "com.google.android.youtube"
    private val INSTAGRAM = "com.instagram.android"
    private val TWITTER = "com.twitter.android"
    private val FACEBOOK = "com.facebook.katana"
    private val PINTEREST = "com.pinterest"
    private val TIKTOK = "com.zhiliaoapp.musically"
    private val GAME = "com.example.gameapp"

    data class Template(
        val text: String,
        val expected: ClassifiedState,
        val sourceApp: String,
        val senders: List<String>,
        val variants: Int = 24
    )

    // ---- Templates -----------------------------------------------------------------------
    // Formal, then casual/slang, then edge-case phrasings, per category.

    val templates: List<Template> = listOf(
        // ============================== ACTION ==============================
        Template("Can you send {item} by {day}?", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("Could you review {item} and confirm receipt?", ClassifiedState.ACTION, GMAIL, listOf("Legal Team", "HR", "Manager", "Compliance")),
        Template("Please submit {item} before {day}.", ClassifiedState.ACTION, GMAIL, listOf("HR", "Payroll", "Registrar")),
        Template("Need you to complete the form by {time}.", ClassifiedState.ACTION, GMAIL, listOf("Manager", "Onboarding Team")),
        Template("Please sign and forward {item} to the team.", ClassifiedState.ACTION, GMAIL, listOf("Jordan", "Legal Team")),
        Template("Could you book the meeting room for {day}?", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("Can you confirm the details for {item}?", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("Please pay {amount} for {item}.", ClassifiedState.ACTION, SMS, listOf("Billing", "Landlord")),
        Template("Could you pick up {item} from the office?", ClassifiedState.ACTION, WHATSAPP, listOf("Mom", "Dana", "Chris")),
        Template("Can you cancel the order for {item}?", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("Would you mind uploading {item} before the meeting?", ClassifiedState.ACTION, GMAIL, listOf("Manager", "Ops")),
        Template("Kindly complete {item} at your earliest convenience.", ClassifiedState.ACTION, GMAIL, listOf("Vendor", "Support")),
        Template("Requesting that you finish {item} by {day}.", ClassifiedState.ACTION, GMAIL, listOf("Client", "Manager")),
        Template("can u send {item} rn", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("pls check {item} asap", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("yo can u call {name} 2day", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("can u pick up {item} on ur way", ClassifiedState.ACTION, WHATSAPP, listOf("Mom", "Dana")),
        Template("need u to submit {item} b4 {time}", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("can u buy {item} 2nite", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("pls confirm u got {item}", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("can u review {item} rq", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("hey can u forward {item} to {name}", ClassifiedState.ACTION, WHATSAPP, NAMES),
        Template("could u sign {item} 2moro", ClassifiedState.ACTION, WHATSAPP, NAMES),

        // ============================== REPLY ================================
        Template("Let me know if {day} works for you.", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("Please get back to me at your earliest convenience.", ClassifiedState.REPLY, GMAIL, listOf("Recruiter", "Client", "Vendor")),
        Template("Tell me what you think about {item}.", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("Are you free on {day} for a quick call?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("Can you confirm you received {item}?", ClassifiedState.REPLY, GMAIL, listOf("Vendor", "Support")),
        Template("Keep me posted on {item}.", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("When are you free to discuss {item}?", ClassifiedState.REPLY, GMAIL, listOf("Client", "Manager")),
        Template("Please tell me how {item} turned out.", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("lmk if ur free {day}", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("hmu when ur done with {item}", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("call me when u get a sec", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("text me when u land", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("tell me what u think fr", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("lmk asap pls", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("hmu later", ClassifiedState.REPLY, WHATSAPP, NAMES),
        Template("keep me posted plz", ClassifiedState.REPLY, WHATSAPP, NAMES),

        // ============================== WAITING ==============================
        Template("I will send {item} shortly.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("I'll check {item} and get back to you.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("We're working on it and will update you soon.", ClassifiedState.WAITING, GMAIL, listOf("Support", "Vendor")),
        Template("You should expect {item} soon.", ClassifiedState.WAITING, GMAIL, listOf("Shipping", "Support")),
        Template("I'll get back to you with an update.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("I'll bring {item} tomorrow.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("ill send it in a min", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("ill get back to u soon", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("on it, gimme a sec", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("will do!", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("ill reply asap", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("im working on {item} rn", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("ill get back to you with the numbers", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("we're working on it, thanks for your patience", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        // Added for Phase 4 ML training: same category, genuinely different phrasing/words
        // from the templates above, to give the model more than one lexical "shape" to learn
        // WAITING from (see tools/train_and_export_tflite_model.py's template-holdout report).
        Template("I'll have {item} to you shortly.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("Should have an update within the hour.", ClassifiedState.WAITING, GMAIL, listOf("Support", "Vendor")),
        Template("Still looking into it, will follow up soon.", ClassifiedState.WAITING, GMAIL, listOf("Support", "IT Dept")),
        Template("Give me a moment to check on that.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("One moment while I sort this out.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("Working through it, appreciate your patience.", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        Template("almost done, hang tight", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("no updates yet but im on it", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("still working on it, sry for the wait", ClassifiedState.WAITING, WHATSAPP, NAMES),
        Template("ill loop back once its done", ClassifiedState.WAITING, WHATSAPP, NAMES),

        // ============================== DEADLINE ==============================
        Template("Your payment of {amount} is due on {day}.", ClassifiedState.DEADLINE, BANK, listOf("Bank Alert")),
        Template("The proposal is due by end of week.", ClassifiedState.DEADLINE, GMAIL, listOf("Manager", "Client")),
        Template("Final date to renew is next week.", ClassifiedState.DEADLINE, GMAIL, listOf("DMV", "Registrar")),
        Template("The offer expires on {day}.", ClassifiedState.DEADLINE, GMAIL, listOf("Utility Co", "Vendor")),
        Template("Registration cutoff is {day}.", ClassifiedState.DEADLINE, GMAIL, listOf("Events Team", "Registrar")),
        Template("Rent is due before the end of the month.", ClassifiedState.DEADLINE, SMS, listOf("Landlord")),
        Template("Your subscription expires by {time} on {day}.", ClassifiedState.DEADLINE, GMAIL, listOf("Streaming Co")),
        Template("rent due {day} dont forget", ClassifiedState.DEADLINE, SMS, listOf("Landlord", "Roommate")),
        Template("assignment deadline is {day}", ClassifiedState.DEADLINE, WHATSAPP, listOf("Classmate")),
        Template("last day to apply is {day}", ClassifiedState.DEADLINE, GMAIL, listOf("Registrar")),
        Template("gotta submit b4 {day} or ur late", ClassifiedState.DEADLINE, WHATSAPP, listOf("Ops", "Classmate")),
        Template("cutoff for signup is this {day}", ClassifiedState.DEADLINE, GMAIL, listOf("Events Team")),
        Template("bill of {amount} due by {day}", ClassifiedState.DEADLINE, BANK, listOf("Bank Alert")),
        Template("final notice: payment due by {day}", ClassifiedState.DEADLINE, BANK, listOf("Bank Alert", "Utility Co")),

        // ================================ FYI =================================
        Template("Your order has been delivered.", ClassifiedState.FYI, AMAZON, listOf("Amazon")),
        Template("Payment of {amount} received, thank you.", ClassifiedState.FYI, BANK, listOf("Bank Alert")),
        Template("Your package arrived at {time}.", ClassifiedState.FYI, AMAZON, listOf("Amazon", "Courier")),
        Template("Transaction of {amount} completed successfully.", ClassifiedState.FYI, BANK, listOf("Bank Alert")),
        Template("Your flight has landed.", ClassifiedState.FYI, GMAIL, listOf("Airline")),
        Template("Meeting moved to {time} on {day}.", ClassifiedState.FYI, CALENDAR, listOf("Coworker", "Calendar")),
        Template("Your verification code is {code}.", ClassifiedState.FYI, SMS, listOf("Bank", "Service")),
        Template("Booking confirmed for {day}.", ClassifiedState.FYI, GMAIL, listOf("Salon", "Restaurant")),
        Template("Backup completed successfully.", ClassifiedState.FYI, GMAIL, listOf("IT Dept")),
        Template("Order confirmed, thank you for your purchase.", ClassifiedState.FYI, AMAZON, listOf("Amazon", "Shop")),
        Template("Your food is out for delivery.", ClassifiedState.FYI, DOORDASH, listOf("DoorDash")),
        Template("order confirmed ty!", ClassifiedState.FYI, DOORDASH, listOf("DoorDash", "Shop")),
        Template("payment received, ty", ClassifiedState.FYI, BANK, listOf("Bank Alert")),
        Template("booking confirmed for {day}, ty", ClassifiedState.FYI, GMAIL, listOf("Salon")),
        Template("ur package arrived just now", ClassifiedState.FYI, AMAZON, listOf("Courier")),
        // Added for Phase 4 ML training: genuinely different phrasing/words from the
        // templates above (see the WAITING block's comment for why).
        Template("Delivery scheduled for {day}.", ClassifiedState.FYI, AMAZON, listOf("Amazon", "Courier")),
        Template("Refund of {amount} has been processed.", ClassifiedState.FYI, BANK, listOf("Bank Alert")),
        Template("Your appointment is confirmed for {day}.", ClassifiedState.FYI, GMAIL, listOf("Salon", "Clinic")),
        Template("Your statement is now available to view.", ClassifiedState.FYI, BANK, listOf("Bank Alert")),
        Template("Your password was changed successfully.", ClassifiedState.FYI, GMAIL, listOf("IT Dept", "Service")),
        Template("Your ride has arrived.", ClassifiedState.FYI, DOORDASH, listOf("Rideshare")),
        Template("Ticket confirmed for {day}.", ClassifiedState.FYI, GMAIL, listOf("Events Team", "Airline")),
        Template("reminder: nothing to do here, all set", ClassifiedState.FYI, GMAIL, listOf("Support")),
        Template("fyi ur ride is here", ClassifiedState.FYI, DOORDASH, listOf("Rideshare")),
        Template("refund of {amount} done", ClassifiedState.FYI, BANK, listOf("Bank Alert")),

        // ================================ NOISE ================================
        Template("{name} liked your photo", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        Template("You have a new video from {name}", ClassifiedState.NOISE, YOUTUBE, listOf("YouTube")),
        Template("You have a new follower", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        Template("Check out this recommended for you post", ClassifiedState.NOISE, PINTEREST, listOf("Pinterest", "Instagram")),
        Template("Flash sale - {percent}% off today only!", ClassifiedState.NOISE, TIKTOK, listOf("TikTok", "Shop")),
        Template("New post from @{name} is trending", ClassifiedState.NOISE, TWITTER, listOf("Twitter")),
        Template("{name} commented on your post", ClassifiedState.NOISE, FACEBOOK, listOf("Facebook")),
        Template("Your suggested post of the week", ClassifiedState.NOISE, PINTEREST, listOf("Pinterest")),
        Template("Limited time promotion just for you!", ClassifiedState.NOISE, TIKTOK, listOf("TikTok")),
        Template("Claim your daily reward now!", ClassifiedState.NOISE, GAME, listOf("GameApp")),
        Template("{name} started following you", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        Template("New post from {name} is trending near you", ClassifiedState.NOISE, TWITTER, listOf("Twitter")),
        // Added for Phase 4 ML training: genuinely different phrasing/words from the
        // templates above (see the WAITING block's comment for why).
        Template("Don't miss out on today's deals!", ClassifiedState.NOISE, TIKTOK, listOf("TikTok", "Shop")),
        Template("{name} shared a story", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        Template("Your weekly digest is ready", ClassifiedState.NOISE, FACEBOOK, listOf("Facebook")),
        Template("Top posts picked for you this week", ClassifiedState.NOISE, PINTEREST, listOf("Pinterest")),
        Template("You might like this creator", ClassifiedState.NOISE, YOUTUBE, listOf("YouTube")),
        Template("New merch just dropped!", ClassifiedState.NOISE, TIKTOK, listOf("Shop")),
        Template("{name} tagged you in a post", ClassifiedState.NOISE, FACEBOOK, listOf("Facebook", "Instagram")),
        Template("unlock a mystery reward today", ClassifiedState.NOISE, GAME, listOf("GameApp"))
    )

    fun buildCorpus(): List<Case> {
        val cases = mutableListOf<Case>()
        templates.forEachIndexed { templateIndex, template ->
            for (i in 0 until template.variants) {
                cases += Case(
                    sourceApp = template.sourceApp,
                    sender = template.senders[i % template.senders.size],
                    text = fill(template.text, i),
                    expected = template.expected,
                    templateIndex = templateIndex
                )
            }
        }
        return cases
    }
}
