package com.example.lcb.music.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicImageTest {
    @Test
    fun `thumbnail candidates preserve path while replacing Audius content host`() {
        val image = MusicImage(
            smallUrl = "https://primary.audius.test/content/cid/150x150.jpg",
            largeUrl = "https://primary.audius.test/content/cid/1000x1000.jpg",
            mirrors = listOf("https://mirror-one.test", "https://mirror-two.test/"),
        )

        assertEquals(
            listOf(
                "https://primary.audius.test/content/cid/150x150.jpg",
                "https://mirror-one.test/content/cid/150x150.jpg",
                "https://mirror-two.test/content/cid/150x150.jpg",
            ),
            image.thumbnailCandidates(),
        )
    }
}
