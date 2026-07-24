package com.example.lcb.music.internal

import com.example.lcb.music.model.PageRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationTest {
    @Test
    fun `known total produces exact next page`() {
        val page = ProviderPage(listOf("a", "b"), totalCount = 5).toPublic(PageRequest(offset = 2, limit = 2))

        assertTrue(page.hasMore)
        assertEquals(4, page.nextOffset)
        assertEquals(5, page.totalCount)
    }

    @Test
    fun `short page without total is terminal`() {
        val page = ProviderPage(listOf("a"), totalCount = null).toPublic(PageRequest(offset = 4, limit = 2))

        assertFalse(page.hasMore)
        assertNull(page.nextOffset)
    }

    @Test
    fun `filtered page uses upstream next offset instead of visible item count`() {
        val request = PageRequest(offset = 20, limit = 10)
        val page = ProviderPage(
            items = listOf("playable"),
            hasMoreHint = true,
            nextOffsetHint = 30,
        ).toPublic(request)

        assertTrue(page.hasMore)
        assertEquals(30, page.nextOffset)
    }

    @Test
    fun `empty filtered page always makes forward progress`() {
        val request = PageRequest(offset = 20, limit = 10)
        val page = ProviderPage<String>(items = emptyList(), hasMoreHint = true).toPublic(request)

        assertEquals(30, page.nextOffset)
    }
}
