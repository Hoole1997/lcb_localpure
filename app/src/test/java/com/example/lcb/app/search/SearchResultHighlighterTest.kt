package com.example.lcb.app.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultHighlighterTest {
    @Test
    fun `highlights every case insensitive literal match`() {
        val ranges = searchMatchRanges("Night night (NI)", "ni")

        assertEquals(listOf(0 until 2, 6 until 8, 13 until 15), ranges)
    }

    @Test
    fun `treats regex punctuation as normal text`() {
        assertEquals(listOf(5 until 11), searchMatchRanges("Song (live)", "(live)"))
    }
}
