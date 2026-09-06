package com.futurepath.actionbox.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashedTextVectorizerTest {

    @Test
    fun `empty text produces an all-zero vector of the expected width`() {
        val vector = HashedTextVectorizer.vectorize("")
        assertEquals(HashedTextVectorizer.VECTOR_SIZE, vector.size)
        assertTrue(vector.all { it == 0f })
    }

    @Test
    fun `vector is deterministic for the same text`() {
        val text = "please confirm you received this month's rent receipt"
        assertEquals(
            HashedTextVectorizer.vectorize(text).toList(),
            HashedTextVectorizer.vectorize(text).toList()
        )
    }

    @Test
    fun `buckets sum to 1 (L1-normalized) whenever there is at least one token`() {
        val vector = HashedTextVectorizer.vectorize("send me the report by friday")
        assertEquals(1f, vector.sum(), 0.0001f)
    }

    @Test
    fun `different wording produces a different vector`() {
        val a = HashedTextVectorizer.vectorize("can you send the file")
        val b = HashedTextVectorizer.vectorize("your package has been delivered")
        assertTrue(!a.contentEquals(b))
    }
}
