package com.futurepath.actionbox.classification

private typealias Template = SyntheticNotificationCorpus.Template
private typealias Case = SyntheticNotificationCorpus.Case

/**
 * Genuinely distinct sentence structures for ML training, added because
 * [SyntheticNotificationCorpus]'s template+substitution approach — even with more templates
 * and more variants — tops out at 82.83% template-holdout accuracy: multiplying variants of
 * ~120 underlying sentence *structures* just produces more rows that differ only in a
 * substituted name/day/amount, which teaches a model to fit those specific structures rather
 * than the category itself. This file is the opposite approach: ~210 independently-written
 * sentences per category are NOT variants of each other or of anything in
 * SyntheticNotificationCorpus — different structures (statement/question/command/fragment),
 * different lengths (2-4 word micro-messages through rambling multi-clause messages),
 * different formality (corporate email through typo-laden texting), and realistic messiness
 * (typos, incomplete sentences, mixed punctuation, emoji-only/emoji-heavy messages) that real
 * notifications actually contain and the tidy synthetic corpus never did.
 *
 * Variant expansion is intentionally light here (mostly 1, a minority up to 5 where a
 * name/day/amount slot fits naturally) — per the instruction that drove this file's existence,
 * volume should come from more DISTINCT structures, not from multiplying a smaller base.
 *
 * Reuses [SyntheticNotificationCorpus]'s Template/Case shapes, [SyntheticNotificationCorpus
 * .fill] substitution, and its NAMES/DAYS/AMOUNTS pools and app-package constants, so there's
 * one canonical substitution implementation (train/serve parity already verified for it)
 * rather than a second, possibly-diverging copy.
 */
object DiverseNotificationCorpus {

    private val WHATSAPP = SyntheticNotificationCorpus.WHATSAPP
    private val SMS = SyntheticNotificationCorpus.SMS
    private val GMAIL = SyntheticNotificationCorpus.GMAIL
    private val BANK = SyntheticNotificationCorpus.BANK
    private val AMAZON = SyntheticNotificationCorpus.AMAZON
    private val DOORDASH = SyntheticNotificationCorpus.DOORDASH
    private val CALENDAR = SyntheticNotificationCorpus.CALENDAR
    private val YOUTUBE = SyntheticNotificationCorpus.YOUTUBE
    private val INSTAGRAM = SyntheticNotificationCorpus.INSTAGRAM
    private val TWITTER = SyntheticNotificationCorpus.TWITTER
    private val FACEBOOK = SyntheticNotificationCorpus.FACEBOOK
    private val PINTEREST = SyntheticNotificationCorpus.PINTEREST
    private val TIKTOK = SyntheticNotificationCorpus.TIKTOK
    private val GAME = SyntheticNotificationCorpus.GAME
    private val NAMES = SyntheticNotificationCorpus.NAMES

    private fun t(
        text: String,
        expected: ClassifiedState,
        sourceApp: String,
        senders: List<String>,
        variants: Int = 1
    ) = Template(text, expected, sourceApp, senders, variants, isPhase2Original = false)

    val templates: List<Template> = listOf(
        // ============================== ACTION ==============================
        t("Fix this now.", ClassifiedState.ACTION, WHATSAPP, NAMES),
        t("sign here plz", ClassifiedState.ACTION, WHATSAPP, NAMES),
        t("Need the numbers before I present.", ClassifiedState.ACTION, GMAIL, listOf("Manager")),
        t("Grab milk on your way back?", ClassifiedState.ACTION, WHATSAPP, listOf("Mom", "Dana")),
        t("u up? need a favor", ClassifiedState.ACTION, WHATSAPP, NAMES),
        t("Attach the invoice when you reply.", ClassifiedState.ACTION, GMAIL, listOf("Accounting", "Client")),
        t("drop off the keys at the front desk", ClassifiedState.ACTION, WHATSAPP, listOf("Landlord")),
        t("Handle this today, no excuses.", ClassifiedState.ACTION, GMAIL, listOf("Manager")),
        t("Double check the numbers before sending.", ClassifiedState.ACTION, GMAIL, listOf("Manager", "Client")),
        t("Reply with your availability for {day}.", ClassifiedState.ACTION, GMAIL, listOf("Recruiter", "Client"), variants = 4),
        t("Take out the trash before you leave", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t("renew ur license b4 it lapses", ClassifiedState.ACTION, SMS, listOf("DMV")),
        t("Fill this out and send it back.", ClassifiedState.ACTION, GMAIL, listOf("HR", "Registrar")),
        t("make sure the door is locked tonite", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t("Print 10 copies for the meeting.", ClassifiedState.ACTION, GMAIL, listOf("Manager")),
        t("swing by the store real quick?", ClassifiedState.ACTION, WHATSAPP, NAMES),
        t("Please escalate this ticket internally.", ClassifiedState.ACTION, GMAIL, listOf("Support", "IT Dept")),
        t("u gotta call the plumber today", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t("wrap up the report n send it ovr", ClassifiedState.ACTION, WHATSAPP, NAMES),
        t("Set the table before guests arrive.", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t("grab my charger from the car pls", ClassifiedState.ACTION, WHATSAPP, NAMES),
        t("Approve the request when you get a sec.", ClassifiedState.ACTION, GMAIL, listOf("Manager")),
        t("turn off the stove!!", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t("walk the dog b4 it rains", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t("Escalate if there's no response by noon.", ClassifiedState.ACTION, GMAIL, listOf("Manager")),
        t("clean up ur desk b4 {day}", ClassifiedState.ACTION, WHATSAPP, NAMES, variants = 3),
        t("Draft a reply for the client today.", ClassifiedState.ACTION, GMAIL, listOf("Manager")),
        t("lock the office when u leave", ClassifiedState.ACTION, WHATSAPP, listOf("Coworker")),
        t("Get this signed off by legal.", ClassifiedState.ACTION, GMAIL, listOf("Manager", "Legal Team")),
        t("text the landlord about the leak", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t("Update the spreadsheet with today's numbers.", ClassifiedState.ACTION, GMAIL, listOf("Manager")),
        t("feed the cat!!", ClassifiedState.ACTION, WHATSAPP, listOf("Roommate")),
        t("fix the leak asap", ClassifiedState.ACTION, WHATSAPP, listOf("Landlord")),
        t("Circle back with finance before EOD.", ClassifiedState.ACTION, GMAIL, listOf("Manager")),
        t(
            "hey so I know you're super busy but when you get a chance could you please just " +
                "take a quick look at the budget spreadsheet I sent over last week because " +
                "finance keeps asking me about it and I really don't want to keep pushing them " +
                "off any longer",
            ClassifiedState.ACTION, GMAIL, listOf("Coworker")
        ),
        t("CALL THE CLIENT NOW", ClassifiedState.ACTION, WHATSAPP, listOf("Manager")),
        t("pls pls pls send the file today im begging", ClassifiedState.ACTION, WHATSAPP, NAMES),
        t("need this by eod no joke", ClassifiedState.ACTION, WHATSAPP, listOf("Manager")),
        t("Book the flights before prices go up.", ClassifiedState.ACTION, GMAIL, listOf("Manager", "Travel Desk")),
        t("sign here 🖊️", ClassifiedState.ACTION, GMAIL, listOf("Legal Team")),
        t("call me 📞 asap", ClassifiedState.ACTION, WHATSAPP, NAMES),
        t("🔧 fix this today pls", ClassifiedState.ACTION, WHATSAPP, listOf("Landlord")),

        // ============================== REPLY ================================
        t("u still there?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("wyd", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("So... thoughts?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("Did you see my last message?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("thoughts on the plan?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("hellooo? anyone home", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("wyd rn", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("did that make sense?", ClassifiedState.REPLY, GMAIL, listOf("Coworker", "Client")),
        t("so what's the verdict", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("Any thoughts on the proposal I sent?", ClassifiedState.REPLY, GMAIL, listOf("Client", "Manager")),
        t("u good?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("still thinking it over?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("hbu", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("so are we still on for {day}?", ClassifiedState.REPLY, WHATSAPP, NAMES, variants = 4),
        t("did u get my last text", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("sup", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("wut do u think tho", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("no reply yet?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("so... yes or no?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("Just checking in — any update on your end?", ClassifiedState.REPLY, GMAIL, listOf("Client", "Vendor")),
        t("hola, u there?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("did that work for u", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("still up for it?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("so hows it looking", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("wanna grab coffee later? lmk", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("did you land yet, text me", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("so what's up with that thing", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("hey u around today?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("did the meeting happen? how'd it go", ClassifiedState.REPLY, WHATSAPP, listOf("Coworker")),
        t("so we still doing this or nah", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("lemme know either way", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("quick q — u free later?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("so...?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t(
            "so i've been waiting all day and i still haven't heard anything back from you " +
                "about whether you're coming to the thing tonight so can you just let me know " +
                "either way because i need to figure out food and stuff",
            ClassifiedState.REPLY, WHATSAPP, NAMES
        ),
        t("yo", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("u there??", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("k but fr tho, thoughts?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("Would love your take on this when you have a moment.", ClassifiedState.REPLY, GMAIL, listOf("Client", "Manager")),
        t("👀?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("🤔 thoughts?", ClassifiedState.REPLY, WHATSAPP, NAMES),
        t("still there? 😅", ClassifiedState.REPLY, WHATSAPP, NAMES),

        // ============================== WAITING ==============================
        t("brb, checking now", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("one sec", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("give me 5", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("on my way, 10 min out", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("workin on it, chill", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("lemme dig into this and get back to u", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("brb checking the file", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("still compiling the numbers, sry for the delay", ClassifiedState.WAITING, GMAIL, listOf("Support", "Vendor")),
        t("almost got it figured out", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("hold on lemme see", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("gimme a bit, its almost done", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("checking with the team, hang on", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("sec, pulling it up now", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("loading...", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("diggin through my emails rn", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("hol up lemme check", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("researching this now, will report back", ClassifiedState.WAITING, GMAIL, listOf("Support", "IT Dept")),
        t("brb 5 min", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("workin thru the backlog, ur next", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("cooking something up for u", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("putting the finishing touches on it", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("let me sync with the team first", ClassifiedState.WAITING, GMAIL, listOf("Manager", "Support")),
        t("reaching out to support now", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("getting this sorted, one moment", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("chasing this down as we speak", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("circling back shortly", ClassifiedState.WAITING, GMAIL, listOf("Support", "Vendor")),
        t("hol on, lemme pull that up", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("looking into it", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("gimme a min, multitasking rn", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("almost there", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("brewing a fix as we speak", ClassifiedState.WAITING, GMAIL, listOf("IT Dept", "Support")),
        t("just need a bit more time on this", ClassifiedState.WAITING, GMAIL, listOf("Support", "Vendor")),
        t("getting closer, hang tight", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("patching it up now", ClassifiedState.WAITING, GMAIL, listOf("IT Dept")),
        t("will circle back once i hear from them", ClassifiedState.WAITING, GMAIL, listOf("Vendor", "Support")),
        t(
            "so i know i said i'd have this done yesterday but things got kind of crazy on my " +
                "end and i promise i'm working on it right now and should have something for " +
                "you within the next hour or so, sorry for the delay",
            ClassifiedState.WAITING, WHATSAPP, NAMES
        ),
        t("workin on it", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("almost", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("hang on lemme finish this up real quick", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("Following up with the vendor, expect news by {day}.", ClassifiedState.WAITING, GMAIL, listOf("Support"), variants = 3),
        t("🔍 looking into it", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("🛠️ patching it up now", ClassifiedState.WAITING, GMAIL, listOf("IT Dept")),
        t("⏳ almost there, hang tight", ClassifiedState.WAITING, WHATSAPP, NAMES),

        // WAITING, cont'd: real-device testing found WAITING to be the weakest-performing
        // category (~70% on real-device phrasing) and no public dataset labels this concept
        // directly (a future commitment/promise implying something is pending is distinct
        // from a simple ACTION statement — see the research this batch is based on). These
        // are hand-written, structurally distinct additions across four subtypes rather than
        // more substitution variants of the above: formal commitments, casual/slang
        // commitments, commitments reported in the third person, and — specifically targeting
        // the real-device verb-overlap confusion — commitments built on a verb (call/send/
        // check/confirm) that ALSO appears in ACTION_VERBS, where the future-commitment
        // structure (not the verb) is what should decide the category.

        // -- formal commitments --
        t("I will have this ready by end of day.", ClassifiedState.WAITING, GMAIL, listOf("Manager")),
        t("We will have an update for you within the hour.", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("Please allow 24 to 48 hours while we look into this.", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("I will follow up with legal and confirm shortly.", ClassifiedState.WAITING, GMAIL, listOf("Manager")),
        t("We are currently reviewing your request and will respond soon.", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("I will personally ensure this reaches you as soon as possible.", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("Rest assured, we're working on this and you'll hear from us soon.", ClassifiedState.WAITING, GMAIL, listOf("Manager")),
        t("I will finalize the report and get it over to you once it's ready.", ClassifiedState.WAITING, GMAIL, listOf("Manager")),
        t("Our team will reach back out to you shortly with next steps.", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("I will confirm the details once I hear from the vendor.", ClassifiedState.WAITING, GMAIL, listOf("Vendor")),

        // -- casual/slang commitments --
        t("bout to send it", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("otw with the docs", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("im finna hit u back in a min", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("fixing to head out with the package rn", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("lemme wrap this up real quick n ill send it ovr", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("just abt done, sending in a sec", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("gimme a minute, almost thru with it", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("bout to hop off n ill call u right after", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("brb sending rn, hold up", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("im finishing up rq, one sec", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("hol up im bout to confirm w the team", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("ok im otw, gimme like 10", ClassifiedState.WAITING, WHATSAPP, NAMES),

        // -- third-person commitments (reported secondhand, not "I will...") --
        t("The team is working on it and will circle back soon.", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("They said they will call back after lunch.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("Support says they're looking into the issue.", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("He mentioned he will have the report ready soon.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("She's finishing up the slides and will send them shortly.", ClassifiedState.WAITING, GMAIL, listOf("Coworker")),
        t("IT said they're on it, should be fixed soon.", ClassifiedState.WAITING, GMAIL, listOf("IT Dept")),
        t("The vendor confirmed they will send it out this week.", ClassifiedState.WAITING, GMAIL, listOf("Vendor")),
        t("My manager said she will get back to us by end of day.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("The plumber said he's on his way.", ClassifiedState.WAITING, WHATSAPP, listOf("Roommate")),
        t("They mentioned it's in progress and should be done soon.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("Landlord says he will check the leak tomorrow.", ClassifiedState.WAITING, WHATSAPP, listOf("Roommate")),
        t("The doctor's office said they will call with results soon.", ClassifiedState.WAITING, WHATSAPP, NAMES),

        // -- verb-overlap commitments: call/send/check/confirm also live in ACTION_VERBS, so
        // these specifically exercise the future-commitment structure overriding the verb --
        t("I'll call you back after the meeting.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("gonna send it over in a bit", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("I'll check and confirm shortly.", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("I'll confirm once I hear back from them.", ClassifiedState.WAITING, GMAIL, listOf("Manager")),
        t("gonna call you back in five.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("I'll send this over as soon as it's ready.", ClassifiedState.WAITING, GMAIL, listOf("Vendor")),
        t("going to check on this and let you know.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("I'll call back once I'm free.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("gonna check in with the team and get back to u.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("I'll send it your way once it's done.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("bout to call em back real quick.", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("gonna confirm with the office and let u know.", ClassifiedState.WAITING, GMAIL, listOf("Support")),
        t("📞 I'll call you back shortly", ClassifiedState.WAITING, WHATSAPP, NAMES),
        t("📤 gonna send it over soon", ClassifiedState.WAITING, WHATSAPP, NAMES),

        // ============================== DEADLINE ==============================
        t("Card expires end of month.", ClassifiedState.DEADLINE, BANK, listOf("Bank Alert")),
        t("Offer ends tmrw!!", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("Only 2 days left to claim.", ClassifiedState.DEADLINE, GMAIL, listOf("Events Team")),
        t("Deadline moved up to today.", ClassifiedState.DEADLINE, GMAIL, listOf("Manager", "Registrar")),
        t("Last chance — closes tonight.", ClassifiedState.DEADLINE, GMAIL, listOf("Events Team")),
        t("Renewal due in 3 days.", ClassifiedState.DEADLINE, GMAIL, listOf("Utility Co")),
        t("Submissions close at midnight.", ClassifiedState.DEADLINE, GMAIL, listOf("Registrar")),
        t("Time's almost up — respond by 5.", ClassifiedState.DEADLINE, GMAIL, listOf("HR", "Manager")),
        t("Pass expires this weekend.", ClassifiedState.DEADLINE, GMAIL, listOf("Events Team")),
        t("Final reminder: due tomorrow.", ClassifiedState.DEADLINE, BANK, listOf("Bank Alert")),
        t("Window closes in 24 hrs.", ClassifiedState.DEADLINE, GMAIL, listOf("Registrar")),
        t("Act before it's too late — expires soon.", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("Only hours left to enroll.", ClassifiedState.DEADLINE, GMAIL, listOf("Registrar", "HR")),
        t("Payment overdue, please settle immediately.", ClassifiedState.DEADLINE, BANK, listOf("Bank Alert")),
        t("Coverage lapses next week if unpaid.", ClassifiedState.DEADLINE, GMAIL, listOf("Utility Co")),
        t("Enrollment ends this Friday.", ClassifiedState.DEADLINE, GMAIL, listOf("Registrar")),
        t("Grace period ends tomorrow.", ClassifiedState.DEADLINE, BANK, listOf("Bank Alert")),
        t("Warranty expires in 5 days.", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("Contract renewal due by end of quarter.", ClassifiedState.DEADLINE, GMAIL, listOf("Manager", "Client")),
        t("Trial ends in 2 days, upgrade now.", ClassifiedState.DEADLINE, GMAIL, listOf("Streaming Co")),
        t("Final call — offer expires at midnight.", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("Rent grace period ends {day}.", ClassifiedState.DEADLINE, SMS, listOf("Landlord"), variants = 4),
        t("3 days left before your subscription lapses.", ClassifiedState.DEADLINE, GMAIL, listOf("Streaming Co")),
        t("Deadline extended to {day}, don't miss it.", ClassifiedState.DEADLINE, GMAIL, listOf("Registrar"), variants = 3),
        t("Application window closes {day} at {time}.", ClassifiedState.DEADLINE, GMAIL, listOf("Registrar"), variants = 4),
        t("expires in 1 hour", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("last day to save is today", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("hurry, sale ends in a few hrs", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("reminder: bill due in 2 days", ClassifiedState.DEADLINE, BANK, listOf("Bank Alert")),
        t("your pass expires soon, renew now", ClassifiedState.DEADLINE, GMAIL, listOf("Events Team")),
        t(
            "just a heads up that the deadline for the grant application is coming up fast " +
                "and if you don't submit everything including the budget narrative and the " +
                "letters of support by end of day friday they will not accept a late " +
                "submission under any circumstances",
            ClassifiedState.DEADLINE, GMAIL, listOf("Registrar")
        ),
        t("due today!!", ClassifiedState.DEADLINE, GMAIL, listOf("HR")),
        t("1 day left", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("expires 11:59pm tonight", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("last chance, ends soon", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("Utility shutoff scheduled if unpaid by {day}.", ClassifiedState.DEADLINE, BANK, listOf("Utility Co"), variants = 3),
        t("⏰ expires in 1 hour", ClassifiedState.DEADLINE, GMAIL, listOf("Vendor")),
        t("⏳ 1 day left, don't miss it", ClassifiedState.DEADLINE, GMAIL, listOf("Registrar")),
        t("🚨 final notice: due today", ClassifiedState.DEADLINE, BANK, listOf("Bank Alert")),

        // ================================ FYI =================================
        t("Package delivered to front desk.", ClassifiedState.FYI, AMAZON, listOf("Amazon", "Courier")),
        t("Your table is ready.", ClassifiedState.FYI, GMAIL, listOf("Restaurant")),
        t("Server maintenance completed.", ClassifiedState.FYI, GMAIL, listOf("IT Dept")),
        t("Meeting recording is now available.", ClassifiedState.FYI, GMAIL, listOf("Coworker", "Calendar")),
        t("Your review has been posted.", ClassifiedState.FYI, AMAZON, listOf("Amazon")),
        t("Balance updated.", ClassifiedState.FYI, BANK, listOf("Bank Alert")),
        t("New device signed in to your account.", ClassifiedState.FYI, GMAIL, listOf("Service", "IT Dept")),
        t("Class canceled today.", ClassifiedState.FYI, GMAIL, listOf("Registrar")),
        t("Weather alert: heavy rain expected.", ClassifiedState.FYI, GMAIL, listOf("Service")),
        t("Your subscription renewed automatically.", ClassifiedState.FYI, GMAIL, listOf("Streaming Co")),
        t("Invoice attached for your records.", ClassifiedState.FYI, GMAIL, listOf("Vendor", "Accounting")),
        t("System update installed successfully.", ClassifiedState.FYI, GMAIL, listOf("IT Dept")),
        t("Your seat is confirmed.", ClassifiedState.FYI, GMAIL, listOf("Airline")),
        t("delivered!", ClassifiedState.FYI, AMAZON, listOf("Amazon", "Courier")),
        t("payment done", ClassifiedState.FYI, BANK, listOf("Bank Alert")),
        t("fyi meeting's in room 4 now", ClassifiedState.FYI, WHATSAPP, listOf("Coworker")),
        t("heads up, parking lot's closed today", ClassifiedState.FYI, GMAIL, listOf("Building Mgmt")),
        t("just so u know, im runnin late", ClassifiedState.FYI, WHATSAPP, NAMES),
        t("note: office closed tmrw", ClassifiedState.FYI, GMAIL, listOf("HR")),
        t("for ur records, receipt attached", ClassifiedState.FYI, GMAIL, listOf("Vendor")),
        t("order shipped", ClassifiedState.FYI, AMAZON, listOf("Amazon")),
        t("table for 2 confirmed at 7", ClassifiedState.FYI, GMAIL, listOf("Restaurant")),
        t("flight delayed by 20 min", ClassifiedState.FYI, GMAIL, listOf("Airline")),
        t("your case has been resolved", ClassifiedState.FYI, GMAIL, listOf("Support")),
        t("landed safely", ClassifiedState.FYI, WHATSAPP, NAMES),
        t("reminder sent, no action needed", ClassifiedState.FYI, GMAIL, listOf("Support")),
        t("your review is live now", ClassifiedState.FYI, AMAZON, listOf("Amazon")),
        t("gate change: now boarding at B12", ClassifiedState.FYI, GMAIL, listOf("Airline")),
        t("package left at the door", ClassifiedState.FYI, AMAZON, listOf("Courier")),
        t("fyi rescheduled to next week, no need to reply", ClassifiedState.FYI, GMAIL, listOf("Coworker", "Client")),
        t(
            "just wanted to let you know that the shipment finally arrived at the warehouse " +
                "this morning and everything looks like it's in good condition so we should " +
                "be able to get it out for delivery sometime later this week, no action " +
                "needed on your end",
            ClassifiedState.FYI, GMAIL, listOf("Vendor", "Shipping")
        ),
        t("fyi done", ClassifiedState.FYI, WHATSAPP, NAMES),
        t("all set", ClassifiedState.FYI, WHATSAPP, NAMES),
        t("note: resolved", ClassifiedState.FYI, GMAIL, listOf("Support")),
        t("heads up, all good now", ClassifiedState.FYI, WHATSAPP, NAMES),
        t("Your {amount} refund was applied to your card.", ClassifiedState.FYI, BANK, listOf("Bank Alert"), variants = 3),
        t("📦 delivered!", ClassifiedState.FYI, AMAZON, listOf("Amazon", "Courier")),
        t("✅ payment done", ClassifiedState.FYI, BANK, listOf("Bank Alert")),
        t("🎉 order shipped", ClassifiedState.FYI, AMAZON, listOf("Amazon")),
        t("🛬 landed safely", ClassifiedState.FYI, WHATSAPP, NAMES),

        // ================================ NOISE ================================
        t("flash sale!!", ClassifiedState.NOISE, TIKTOK, listOf("TikTok", "Shop")),
        t("u won't believe this deal", ClassifiedState.NOISE, TIKTOK, listOf("Shop")),
        t("new arrivals just dropped", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        t("swipe for more", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        t("your daily horoscope is here", ClassifiedState.NOISE, GAME, listOf("GameApp")),
        t("free gift inside", ClassifiedState.NOISE, TIKTOK, listOf("Shop")),
        t("top 10 trending now", ClassifiedState.NOISE, TWITTER, listOf("Twitter")),
        t("someone viewed your profile", ClassifiedState.NOISE, FACEBOOK, listOf("Facebook")),
        t("your streak is about to end!", ClassifiedState.NOISE, GAME, listOf("GameApp")),
        t("70% off everything", ClassifiedState.NOISE, TIKTOK, listOf("Shop")),
        t("new episode just dropped", ClassifiedState.NOISE, YOUTUBE, listOf("YouTube")),
        t("your friend joined the app", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        t("daily quest available", ClassifiedState.NOISE, GAME, listOf("GameApp")),
        t("see who liked u back", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        t("you have new notifications", ClassifiedState.NOISE, FACEBOOK, listOf("Facebook")),
        t("level up! claim your reward", ClassifiedState.NOISE, GAME, listOf("GameApp")),
        t("trending near u right now", ClassifiedState.NOISE, TWITTER, listOf("Twitter")),
        t("your weekly stats are in", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        t("rate us 5 stars!", ClassifiedState.NOISE, GAME, listOf("GameApp")),
        t("don't miss the livestream tonight", ClassifiedState.NOISE, TIKTOK, listOf("TikTok")),
        t("items in your cart are selling fast", ClassifiedState.NOISE, AMAZON, listOf("Shop")),
        t("new badge unlocked!", ClassifiedState.NOISE, GAME, listOf("GameApp")),
        t("spin the wheel for a prize", ClassifiedState.NOISE, GAME, listOf("GameApp")),
        t("your subscription box shipped", ClassifiedState.NOISE, AMAZON, listOf("Shop")),
        t("hot deals just for u", ClassifiedState.NOISE, TIKTOK, listOf("Shop")),
        t("join the challenge now", ClassifiedState.NOISE, TIKTOK, listOf("TikTok")),
        t("happy anniversary with the app!", ClassifiedState.NOISE, GAME, listOf("GameApp")),
        t("your feed has new content", ClassifiedState.NOISE, FACEBOOK, listOf("Facebook")),
        t("exclusive drop, don't sleep on it", ClassifiedState.NOISE, TIKTOK, listOf("Shop")),
        t("sale ends soon, shop now", ClassifiedState.NOISE, AMAZON, listOf("Shop")),
        t(
            "hey just wanted to remind you that there's a huge sale happening right now " +
                "across the entire store with discounts up to seventy percent off on some of " +
                "our most popular items so make sure you don't miss out because it's only " +
                "for a limited time",
            ClassifiedState.NOISE, AMAZON, listOf("Shop")
        ),
        t("sale", ClassifiedState.NOISE, TIKTOK, listOf("Shop")),
        t("new post!", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        t("check this out", ClassifiedState.NOISE, TWITTER, listOf("Twitter")),
        t("trending", ClassifiedState.NOISE, TWITTER, listOf("Twitter")),
        t("Your {percent}% off code expires soon, shop now!", ClassifiedState.NOISE, TIKTOK, listOf("Shop"), variants = 3),
        t("🔥🔥🔥 flash sale!!", ClassifiedState.NOISE, TIKTOK, listOf("TikTok", "Shop")),
        t("😍😍 new arrivals just dropped", ClassifiedState.NOISE, INSTAGRAM, listOf("Instagram")),
        t("🎁 free gift inside, tap now", ClassifiedState.NOISE, TIKTOK, listOf("Shop")),
        t("🥳🎉✨", ClassifiedState.NOISE, GAME, listOf("GameApp")),
        t("🛒🔥 70% off, ends soon", ClassifiedState.NOISE, AMAZON, listOf("Shop"))
    )

    fun buildCorpus(): List<Case> {
        val cases = mutableListOf<Case>()
        templates.forEachIndexed { templateIndex, template ->
            for (i in 0 until template.variants) {
                cases += Case(
                    sourceApp = template.sourceApp,
                    sender = template.senders[i % template.senders.size],
                    text = SyntheticNotificationCorpus.fill(template.text, i),
                    expected = template.expected,
                    templateIndex = templateIndex,
                    variantIndex = i,
                    isPhase2Original = false
                )
            }
        }
        return cases
    }
}
