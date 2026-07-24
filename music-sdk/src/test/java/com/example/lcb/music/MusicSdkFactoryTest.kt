package com.example.lcb.music

import com.example.lcb.music.model.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSdkFactoryTest {
    @Test
    fun `empty sdk can be enabled after remote credentials arrive`() {
        val sdk = MusicSdkFactory.create(MusicSdkConfig())

        assertEquals(2, sdk.health().size)
        assertTrue(sdk.health().none { it.isAvailable })

        sdk.updateCredentials(
            MusicSdkCredentials(
                jamendoClientIds = listOf("jamendo"),
                audiusCredentials = listOf(AudiusCredential("audius", "bearer")),
            ),
        )

        assertTrue(sdk.health().all { it.isAvailable })
        assertEquals(1, sdk.health().first { it.platform == MusicPlatform.JAMENDO }.availableKeys)
        assertFalse(sdk.health().any { it.disabledKeys > 0 })
    }
}
