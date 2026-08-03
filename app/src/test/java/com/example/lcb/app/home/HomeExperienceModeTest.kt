package com.example.lcb.app.home

import com.example.lcb.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeExperienceModeTest {
    @Test
    fun `missing or invalid config safely selects local mode`() {
        listOf(null, "", "unexpected", "[]").forEach { raw ->
            val resolution = resolveHomeExperienceMode(raw, HomeModeAudience.NATURAL)
            assertEquals(HomeExperienceMode.LOCAL, resolution.mode)
            assertNull(resolution.selectedValue)
        }
    }

    @Test
    fun `natural and paid users select their own json branch`() {
        val raw = """{"natural":"local","paid":"online"}"""

        val natural = resolveHomeExperienceMode(raw, HomeModeAudience.NATURAL)
        val paid = resolveHomeExperienceMode(raw, HomeModeAudience.PAID)

        assertEquals("local", natural.selectedValue)
        assertEquals(HomeExperienceMode.LOCAL, natural.mode)
        assertEquals("online", paid.selectedValue)
        assertEquals(HomeExperienceMode.ONLINE, paid.mode)
    }

    @Test
    fun `json keys are case insensitive and experiment aliases remain supported`() {
        val raw = """{"NATURAL":"b","PAID":"a"}"""

        assertEquals(
            HomeExperienceMode.ONLINE,
            resolveHomeExperienceMode(raw, HomeModeAudience.NATURAL).mode,
        )
        assertEquals(
            HomeExperienceMode.LOCAL,
            resolveHomeExperienceMode(raw, HomeModeAudience.PAID).mode,
        )
    }

    @Test
    fun `missing audience branch defaults only that audience to local`() {
        val raw = """{"paid":"online"}"""

        assertEquals(
            HomeExperienceMode.LOCAL,
            resolveHomeExperienceMode(raw, HomeModeAudience.NATURAL).mode,
        )
        assertEquals(
            HomeExperienceMode.ONLINE,
            resolveHomeExperienceMode(raw, HomeModeAudience.PAID).mode,
        )
    }

    @Test
    fun `local mode only renders device tracks and hides online entry points`() {
        val deviceTrack = track("LOCAL:1")
        val content = HomeContent(
            recommended = listOf(track("AUDIUS:online")),
            mostPlayed = listOf(track("JAMENDO:online")),
            localMusic = LocalHomeMusicState.Loaded(listOf(deviceTrack)),
        )

        val items = buildHomeItems(HomeExperienceMode.LOCAL, content, isOnlineLoading = false)

        assertFalse((items.first() as HomeListItem.Header).showSearch)
        assertTrue(items.filterIsInstance<HomeListItem.LocalTrack>().single().track == deviceTrack)
        assertFalse(items.any { it is HomeListItem.Recommended || it is HomeListItem.MostPlayed })
        assertFalse(
            items.filterIsInstance<HomeListItem.SectionTitle>().any {
                it.id == HomeSectionId.RECOMMENDED || it.id == HomeSectionId.MOST_PLAYED
            },
        )
    }

    @Test
    fun `online mode retains search recommendation and most played sections`() {
        val content = HomeContent(
            recommended = listOf(track("AUDIUS:1")),
            mostPlayed = listOf(track("JAMENDO:1")),
            localMusic = LocalHomeMusicState.Loaded(listOf(track("LOCAL:1"))),
        )

        val items = buildHomeItems(HomeExperienceMode.ONLINE, content, isOnlineLoading = false)

        assertTrue((items.first() as HomeListItem.Header).showSearch)
        assertTrue(items.any { it is HomeListItem.Recommended })
        assertTrue(items.any { it is HomeListItem.MostPlayed })
        assertFalse(items.any { it is HomeListItem.LocalTrack })
    }

    private fun track(id: String) = HomeTrackUi(
        id = id,
        title = "Track",
        artist = "Artist",
        artworkRes = R.drawable.placeholder_local_music_track,
    )
}
