package com.example.lcb.music.internal

import com.example.lcb.music.model.MusicPlatform
import com.example.lcb.music.model.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackDeduplicationTest {
    @Test
    fun `different ids with copy suffix are treated as the same recording`() {
        val original = track("first", "Cool S#%t off Y.T.   Dusty Rhodes ReDux (Final Cut)")
        val copied = track("second", "Cool S#%t off Y.T.   Dusty Rhodes ReDux (Final Cut) (1)")

        assertEquals(listOf(original), deduplicateTracks(listOf(original, copied)))
    }

    @Test
    fun `case whitespace and punctuation do not create duplicates`() {
        val original = track("first", "A  Quiet—Song")
        val copied = track("second", "a quiet song")

        assertEquals(trackContentIdentity(original), trackContentIdentity(copied))
    }

    @Test
    fun `named live and remix versions remain distinct`() {
        val studio = track("studio", "Closer Than I Show")
        val live = track("live", "Closer Than I Show (Live)")
        val remix = track("remix", "Closer Than I Show Remix")

        assertEquals(3, deduplicateTracks(listOf(studio, live, remix)).size)
    }

    private fun track(id: String, title: String) = MusicTrack(
        id = id,
        platform = MusicPlatform.AUDIUS,
        title = title,
        artistName = "VooDooGod1",
        artworkUrl = null,
        durationMs = 244_000,
        streamUrl = "https://example.test/$id",
    )
}
