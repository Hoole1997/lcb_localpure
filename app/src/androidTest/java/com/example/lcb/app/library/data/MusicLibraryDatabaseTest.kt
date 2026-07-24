package com.example.lcb.app.library.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lcb.app.library.LibraryCollection
import com.example.lcb.app.library.LibraryTrack
import com.example.lcb.app.library.RoomMusicLibraryRepository
import com.example.lcb.music.model.MusicArtistRef
import com.example.lcb.music.model.MusicPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicLibraryDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: MusicLibraryDatabase? = null

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MusicLibraryDatabase::class.java,
    )

    @After
    fun closeDatabase() {
        database?.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun playlistRelationsAndFavoritesRemainIndependent() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, MusicLibraryDatabase::class.java).build()
        val repository = RoomMusicLibraryRepository(database!!.libraryDao()) { 100L }
        val playlistId = repository.createPlaylist("Focus").getOrThrow()
        val track = track("audius:1")

        repository.addTrackToPlaylist(playlistId, track)
        repository.setFavorite(track, true)
        val storedTrack = repository.observeTracks(LibraryCollection.Playlist(playlistId)).first().single()
        assertEquals(track.id, storedTrack.id)
        assertEquals(track.artistRef, storedTrack.artistRef)

        repository.deletePlaylist(playlistId)
        assertTrue(repository.observePlaylists().first().isEmpty())
        assertEquals(
            listOf(track.id),
            repository.observeTracks(LibraryCollection.Favorites).first().map(LibraryTrack::id),
        )
    }

    @Test
    fun autoMigrationFromOneToTwoPreservesPlaylist() {
        migrationHelper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (1, 'Road trip', 99)")
            close()
        }

        database = Room.databaseBuilder(context, MusicLibraryDatabase::class.java, TEST_DATABASE).build()
        database!!.openHelper.writableDatabase.query(
            "SELECT name, updatedAt FROM playlists WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Road trip", cursor.getString(0))
            assertEquals(0L, cursor.getLong(1))
        }
    }

    @Test
    fun autoMigrationFromTwoToThreeAddsNullableArtistReference() {
        migrationHelper.createDatabase(TEST_DATABASE, 2).apply {
            execSQL(
                """INSERT INTO library_tracks (
                    id, title, artist, artworkUrl, artworkThumbnailUrls, artworkFallbackRes,
                    streamUrl, durationMs, lyrics, description
                ) VALUES (
                    'AUDIUS:legacy', 'Legacy', 'Artist', NULL, '[]', 0,
                    'https://example.test/legacy.mp3', 60000, NULL, NULL
                )""".trimIndent(),
            )
            close()
        }

        database = Room.databaseBuilder(context, MusicLibraryDatabase::class.java, TEST_DATABASE).build()
        database!!.openHelper.writableDatabase.query(
            "SELECT artistId, artistPlatform FROM library_tracks WHERE id = 'AUDIUS:legacy'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
    }

    @Test
    fun autoMigrationFromThreeToFourCreatesRecentPlaybackTable() {
        migrationHelper.createDatabase(TEST_DATABASE, 3).close()

        database = Room.databaseBuilder(context, MusicLibraryDatabase::class.java, TEST_DATABASE).build()
        database!!.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'recent_tracks'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("recent_tracks", cursor.getString(0))
        }
    }

    @Test
    fun recentPlaybackIsUniqueOrderedAndCappedAtFifty() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, MusicLibraryDatabase::class.java).build()
        var clock = 0L
        val repository = RoomMusicLibraryRepository(database!!.libraryDao()) { ++clock }

        repeat(52) { index -> repository.recordRecentlyPlayed(track("AUDIUS:$index")) }
        val capped = repository.observeRecentlyPlayed().first()
        assertEquals(50, capped.size)
        assertEquals("AUDIUS:51", capped.first().id)
        assertEquals("AUDIUS:2", capped.last().id)

        repository.recordRecentlyPlayed(track("AUDIUS:10"))
        val reordered = repository.observeRecentlyPlayed().first()
        assertEquals(50, reordered.size)
        assertEquals("AUDIUS:10", reordered.first().id)
        assertEquals(1, reordered.count { it.id == "AUDIUS:10" })
    }

    private fun track(id: String) = LibraryTrack(
        id = id,
        title = "Track",
        artist = "Artist",
        artworkUrl = null,
        artworkThumbnailUrls = emptyList(),
        artworkFallbackRes = 0,
        streamUrl = "https://example.test/track.mp3",
        durationMs = 60_000,
        lyrics = null,
        description = null,
        artistRef = MusicArtistRef("artist-1", MusicPlatform.AUDIUS, "Artist"),
    )

    private companion object {
        const val TEST_DATABASE = "music-library-migration-test"
    }
}
