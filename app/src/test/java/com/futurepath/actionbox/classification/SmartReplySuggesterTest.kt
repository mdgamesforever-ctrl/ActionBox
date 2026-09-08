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
}
