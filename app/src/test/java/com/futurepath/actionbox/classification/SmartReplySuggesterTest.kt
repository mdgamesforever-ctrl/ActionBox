package com.futurepath.actionbox.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartReplySuggesterTest {

    @Test
    fun `blank text has no suggestions`() {
        assertTrue(SmartReplySuggester.suggest("").isEmpty())
        assertTrue(SmartReplySuggester.suggest("   ").isEmpty())
    }

    @Test
    fun `a plain question gets yes-no-later suggestions`() {
        val suggestions = SmartReplySuggester.suggest("Can you send the report?")
        assertEquals(listOf("Yes", "No", "Let me get back to you"), suggestions)
    }

    @Test
    fun `a time or date mention gets confirm-reschedule suggestions`() {
        assertEquals(listOf("Sounds good", "Can we reschedule?", "Confirmed"), SmartReplySuggester.suggest("Let's meet tomorrow"))
        assertEquals(listOf("Sounds good", "Can we reschedule?", "Confirmed"), SmartReplySuggester.suggest("See you at 3pm"))
        assertEquals(listOf("Sounds good", "Can we reschedule?", "Confirmed"), SmartReplySuggester.suggest("Works for Friday"))
    }

    @Test
    fun `a time mention takes priority when the text is also a question`() {
        val suggestions = SmartReplySuggester.suggest("Are you free tomorrow?")
        assertEquals(listOf("Sounds good", "Can we reschedule?", "Confirmed"), suggestions)
    }

    @Test
    fun `plain statement text falls back to generic acknowledgment suggestions`() {
        val suggestions = SmartReplySuggester.suggest("Just checking in on this")
        assertEquals(listOf("👍", "On it", "Will reply soon"), suggestions)
    }

    @Test
    fun `never returns more than 3 suggestions`() {
        val allInputs = listOf("Can you help?", "Tomorrow at noon", "Just an update", "")
        allInputs.forEach { input ->
            assertTrue(SmartReplySuggester.suggest(input).size <= 3)
        }
    }

    // Casual texting very often skips question marks and formal day names entirely — these
    // confirm suggestions still vary for that real-world phrasing, not just clean, fully-
    // punctuated examples. (An earlier version required a trailing "?" and a narrow token list,
    // which meant almost every message like these fell through to the same 3 defaults.)
    @Test
    fun `a casual question with no question mark still gets yes-no-later suggestions`() {
        assertEquals(listOf("Yes", "No", "Let me get back to you"), SmartReplySuggester.suggest("can you call me"))
        assertEquals(listOf("Yes", "No", "Let me get back to you"), SmartReplySuggester.suggest("did you see this"))
        assertEquals(listOf("Yes", "No", "Let me get back to you"), SmartReplySuggester.suggest("you around"))
    }

    @Test
    fun `informal time shorthand still gets confirm-reschedule suggestions`() {
        assertEquals(listOf("Sounds good", "Can we reschedule?", "Confirmed"), SmartReplySuggester.suggest("u free tmrw"))
        assertEquals(listOf("Sounds good", "Can we reschedule?", "Confirmed"), SmartReplySuggester.suggest("let's catch up this weekend"))
    }

    @Test
    fun `different real-looking messages produce different suggestion sets, not one fixed default`() {
        val messages = listOf(
            "can you send the file",
            "see you at 3pm",
            "thanks for the update",
            "you free tonight"
        )
        val suggestionSets = messages.map { SmartReplySuggester.suggest(it) }.toSet()
        assertTrue("Expected more than one distinct suggestion set across varied messages", suggestionSets.size > 1)
    }
}
