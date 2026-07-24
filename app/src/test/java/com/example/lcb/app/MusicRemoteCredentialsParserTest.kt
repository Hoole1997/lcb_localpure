package com.example.lcb.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicRemoteCredentialsParserTest {
    @Test
    fun `parses json credentials and keeps api bearer pairing`() {
        val patch = MusicRemoteCredentialsParser.parse(
            jamendoRaw = "[\"jamendo-a\", \"jamendo-b\"]",
            audiusCredentialsRaw = """
                [
                  {"apiKey":"api-a","bearerToken":"Bearer token-a"},
                  {"apiKey":"api-b","bearerToken":"token-b"}
                ]
            """.trimIndent(),
            legacyAudiusApiKeysRaw = null,
            legacyAudiusBearerTokensRaw = null,
        )

        assertEquals(listOf("jamendo-a", "jamendo-b"), patch.jamendoClientIds)
        assertEquals(listOf("api-a", "api-b"), patch.audiusCredentials?.map { it.apiKey })
        assertEquals(listOf("token-a", "token-b"), patch.audiusCredentials?.map { it.bearerToken })
    }

    @Test
    fun `legacy lists pair values by index`() {
        val patch = MusicRemoteCredentialsParser.parse(
            jamendoRaw = "jamendo-a, jamendo-b",
            audiusCredentialsRaw = null,
            legacyAudiusApiKeysRaw = "api-a,api-b",
            legacyAudiusBearerTokensRaw = "token-a,token-b",
        )

        assertEquals(listOf("jamendo-a", "jamendo-b"), patch.jamendoClientIds)
        assertEquals("token-b", patch.audiusCredentials?.get(1)?.bearerToken)
    }

    @Test
    fun `malformed remote values do not erase current credentials`() {
        val patch = MusicRemoteCredentialsParser.parse(
            jamendoRaw = "[invalid",
            audiusCredentialsRaw = "[{\"bearerToken\":\"missing-api\"}]",
            legacyAudiusApiKeysRaw = null,
            legacyAudiusBearerTokensRaw = null,
        )

        assertNull(patch.jamendoClientIds)
        assertNull(patch.audiusCredentials)
    }

    @Test
    fun `explicit empty arrays disable a platform`() {
        val patch = MusicRemoteCredentialsParser.parse(
            jamendoRaw = "[]",
            audiusCredentialsRaw = "[]",
            legacyAudiusApiKeysRaw = null,
            legacyAudiusBearerTokensRaw = null,
        )

        assertEquals(emptyList<String>(), patch.jamendoClientIds)
        assertEquals(emptyList<String>(), patch.audiusCredentials?.map { it.apiKey })
    }
}
