package com.futurepath.actionbox.classification

/**
 * Normalizes raw notification text before classification: lowercases, collapses
 * exaggerated character/punctuation repetition, and expands common texting/Gen-Z
 * abbreviations and slang to plain-English equivalents. This is deliberately a broad,
 * approximate dictionary — v1 heuristic classification benefits far more from catching
 * "u wanna grab lunch" as "you want to grab lunch" than from being precise about every
 * possible slang term, and a missed or over-eager expansion here is low-stakes since the
 * original text is preserved separately and still shown to the user.
 */
object TextNormalizer {

    fun normalize(text: String): String {
        if (text.isBlank()) return text

        var result = text.lowercase()

        // "freeee" -> "free", "nooooo" -> "noo" — collapse a run of 3+ identical letters
        // down to 2 rather than 1, since many ordinary words have legitimate double
        // letters (free, all, will) and collapsing further would corrupt them.
        result = Regex("([a-z])\\1{2,}").replace(result) { it.groupValues[1].repeat(2) }

        // "!!!" -> "!", "???" -> "?"
        result = Regex("([!?.,;:])\\1+").replace(result) { it.groupValues[1] }

        result = expandSlang(result)

        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun expandSlang(text: String): String {
        // Alternation keeps alphanumeric runs (words, including alnum slang like "2nite"
        // or "b4") together as single tokens, separated from whitespace/punctuation runs,
        // so a dictionary lookup never partially matches inside a larger word.
        return Regex("[a-z0-9']+|[^a-z0-9']+").findAll(text)
            .joinToString("") { match -> SLANG_MAP[match.value] ?: match.value }
    }

    private val SLANG_MAP: Map<String, String> = mapOf(
        // Single-letter / short texting shorthand
        "u" to "you",
        "ur" to "your",
        "r" to "are",
        "y" to "why",
        "n" to "and",
        "c" to "see",
        "k" to "okay",
        "kk" to "okay",
        // Common phrase abbreviations
        "lmk" to "let me know",
        "lemme" to "let me",
        "gimme" to "give me",
        "rn" to "right now",
        "tmr" to "tomorrow",
        "tmrw" to "tomorrow",
        "2mrw" to "tomorrow",
        "2moro" to "tomorrow",
        "2nite" to "tonight",
        "2day" to "today",
        "pls" to "please",
        "plz" to "please",
        "msg" to "message",
        "hmu" to "hit me up",
        "btw" to "by the way",
        "rq" to "real quick",
        "asap" to "asap",
        "fyi" to "for your information",
        // "going to" / "want to" family
        "wanna" to "want to",
        "gonna" to "going to",
        "gotta" to "got to",
        "hafta" to "have to",
        "tryna" to "trying to",
        "finna" to "going to",
        "imma" to "i am going to",
        "kinda" to "kind of",
        "sorta" to "sort of",
        "lotta" to "lot of",
        "outta" to "out of",
        // "I don't know" family
        "idk" to "i do not know",
        "idc" to "i do not care",
        "idky" to "i do not know why",
        "dunno" to "do not know",
        // Opinion / emphasis
        "imo" to "in my opinion",
        "imho" to "in my honest opinion",
        "tbh" to "to be honest",
        "ngl" to "not gonna lie",
        "fr" to "for real",
        "def" to "definitely",
        "obv" to "obviously",
        "obvi" to "obviously",
        "rly" to "really",
        "totes" to "totally",
        "whatevs" to "whatever",
        "srsly" to "seriously",
        // Exclamations / reactions
        "omg" to "oh my god",
        "omw" to "on my way",
        "otw" to "on the way",
        "abt" to "about",
        "brb" to "be right back",
        "gtg" to "got to go",
        "g2g" to "got to go",
        "ttyl" to "talk to you later",
        "nvm" to "never mind",
        "np" to "no problem",
        "smh" to "shaking my head",
        "irl" to "in real life",
        "jk" to "just kidding",
        // Gratitude / greetings
        "ty" to "thank you",
        "tysm" to "thank you so much",
        "thx" to "thanks",
        "yw" to "you are welcome",
        "ily" to "i love you",
        "sry" to "sorry",
        "gm" to "good morning",
        "gn" to "good night",
        "gnight" to "good night",
        "sup" to "what is up",
        "wassup" to "what is up",
        "wyd" to "what are you doing",
        "hbu" to "how about you",
        "wbu" to "what about you",
        // Compact alphanumeric slang
        "b4" to "before",
        "gr8" to "great",
        "l8r" to "later",
        "l8" to "late",
        "w8" to "wait",
        "atm" to "at the moment",
        "afaik" to "as far as i know",
        // Misc contractions/shorthand
        "cuz" to "because",
        "cus" to "because",
        "bc" to "because",
        "tho" to "though",
        "thru" to "through",
        "dm" to "direct message",
        "convo" to "conversation",
        "info" to "information",
        "appt" to "appointment",
        "addy" to "address",
        "bday" to "birthday",
        "prob" to "probably",
        "prolly" to "probably",
        // Dropped-apostrophe contractions ("ill", "im", "dont"...) — extremely common in
        // casual texting and previously invisible to any pattern expecting the apostrophe
        // form ("i'll", "don't"). Deliberately excludes ones that collide with a common
        // standalone word ("well" as we'll/as in feeling fine, "hell" as he'll/the place) —
        // those ambiguous cases are left unexpanded rather than risk corrupting them.
        "ill" to "i will",
        "im" to "i am",
        "ive" to "i have",
        "youre" to "you are",
        "youve" to "you have",
        "youll" to "you will",
        "theyre" to "they are",
        "theyve" to "they have",
        "theyll" to "they will",
        "hes" to "he is",
        "shes" to "she is",
        "weve" to "we have",
        "dont" to "do not",
        "doesnt" to "does not",
        "didnt" to "did not",
        "cant" to "cannot",
        "wont" to "will not",
        "wouldnt" to "would not",
        "couldnt" to "could not",
        "shouldnt" to "should not",
        "isnt" to "is not",
        "arent" to "are not",
        "wasnt" to "was not",
        "werent" to "were not",
        "hasnt" to "has not",
        "havent" to "have not",
        "hadnt" to "had not",
        "aint" to "is not",
        "whats" to "what is",
        "thats" to "that is",
        "theres" to "there is",
        "heres" to "here is",
        "whos" to "who is",
        "lets" to "let us"
    )
}
