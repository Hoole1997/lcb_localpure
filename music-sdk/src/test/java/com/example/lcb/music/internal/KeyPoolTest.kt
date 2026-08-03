package com.example.lcb.music.internal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolTest {
    @Test
    fun `rate limited key cools down and recovers`() {
        var now = 1_000L
        val pool = KeyPool(listOf("a", "b"), defaultCooldownMs = 500, now = { now })

        pool.markFailure("a", ProviderRequestException(429, message = "limited"))
        assertFalse(pool.candidates().contains("a"))

        now += 501
        assertTrue(pool.candidates().contains("a"))
    }

    @Test
    fun `invalid credential is permanently disabled`() {
        val pool = KeyPool(listOf("a", "b"), defaultCooldownMs = 500)

        pool.markFailure("a", ProviderRequestException(401, message = "invalid"))

        assertEquals(listOf("b"), pool.candidates())
        assertEquals(Triple(1, 0, 1), pool.snapshot())
    }

    @Test
    fun `credentials are trimmed and deduplicated`() {
        assertEquals(listOf("a", "b"), KeyPool.clean(listOf(" a ", "", "a", "b")))
    }

    @Test
    fun `runtime replacement preserves failures and enables only new credentials`() {
        val pool = KeyPool(listOf("a", "b"), defaultCooldownMs = 500)
        pool.markFailure("a", ProviderRequestException(401, message = "invalid"))

        pool.replace(listOf("a", "c"))

        assertEquals(listOf("c"), pool.candidates())
        assertEquals(Triple(1, 0, 1), pool.snapshot())

        pool.replace(listOf("c", "d"))
        assertEquals(setOf("c", "d"), pool.candidates().toSet())
        assertEquals(Triple(2, 0, 0), pool.snapshot())
    }

    @Test
    fun `empty runtime pool can later be enabled`() {
        val pool = KeyPool<String>(emptyList(), defaultCooldownMs = 500)

        assertTrue(pool.candidates().isEmpty())
        pool.replace(listOf("new-key"))

        assertEquals(listOf("new-key"), pool.candidates())
    }

    @Test
    fun `network failure keeps credentials healthy for immediate retry`() = runBlocking {
        val pool = KeyPool(listOf("a", "b"), defaultCooldownMs = 120_000)
        val attempts = mutableListOf<String>()
        val networkFailure = ProviderRequestException(
            statusCode = null,
            message = "Network request failed",
        )

        val result = runCatching {
            withKeyFailover<String, Unit>(pool) { credential ->
                attempts += credential
                throw networkFailure
            }
        }

        assertSame(networkFailure, result.exceptionOrNull())
        assertEquals(listOf("a"), attempts)
        assertEquals(Triple(2, 0, 0), pool.snapshot())
        assertEquals(setOf("a", "b"), pool.candidates().toSet())
    }

    @Test
    fun `rate limit rotates to the next credential`() = runBlocking {
        val pool = KeyPool(listOf("a", "b"), defaultCooldownMs = 120_000)
        val attempts = mutableListOf<String>()

        val selected = withKeyFailover(pool) { credential ->
            attempts += credential
            if (credential == "a") {
                throw ProviderRequestException(statusCode = 429, message = "Rate limited")
            }
            credential
        }

        assertEquals("b", selected)
        assertEquals(listOf("a", "b"), attempts)
        assertEquals(Triple(1, 1, 0), pool.snapshot())
    }
}
