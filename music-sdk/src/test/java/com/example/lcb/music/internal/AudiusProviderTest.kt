package com.example.lcb.music.internal

import com.example.lcb.music.AudiusCredential
import com.example.lcb.music.model.MusicSort
import com.example.lcb.music.model.MusicArtistFeature
import com.example.lcb.music.model.PageRequest
import com.example.lcb.music.model.TrackQuery
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiusProviderTest {
    @Test
    fun `track pages remove items explicitly marked unstreamable`() = withServer { server, provider ->
        server.enqueue(
            jsonResponse(
                """{"data":[
                    ${trackJson("playable", true)},
                    ${trackJson("locked", false)}
                ]}""",
            ),
        )

        val result = runBlocking { provider.tracks(TrackQuery(sort = MusicSort.LATEST), PageRequest(limit = 10)) }

        assertEquals(listOf("playable"), result.items.map { it.id })
        assertTrue(result.items.single().isStreamable)
        assertEquals("About playable", result.items.single().description)
        assertEquals(
            listOf(
                "https://primary.test/content/playable/150x150.jpg",
                "https://mirror.test/content/playable/150x150.jpg",
            ),
            result.items.single().artwork?.thumbnailCandidates(),
        )
        assertEquals("artist-id", result.items.single().artistId)
        assertFalse(result.hasMoreHint == true)
    }

    @Test
    fun `artist track pagination advances by raw items after streamability filtering`() = withServer { server, provider ->
        server.enqueue(
            jsonResponse(
                """{"data":[
                    ${trackJson("playable", true)},
                    ${trackJson("locked", false)}
                ]}""",
            ),
        )

        val requestPage = PageRequest(offset = 40, limit = 2)
        val providerPage = runBlocking { provider.artistTracks("artist-id", requestPage, MusicSort.POPULAR) }
        val page = providerPage.toPublic(requestPage)
        val request = server.takeRequest()

        assertEquals(listOf("playable"), page.items.map { it.id })
        assertTrue(page.hasMore)
        assertEquals(42, page.nextOffset)
        assertEquals("plays", request.requestUrl?.queryParameter("sort_method"))
        assertEquals("desc", request.requestUrl?.queryParameter("sort_direction"))
        assertEquals("public", request.requestUrl?.queryParameter("filter_tracks"))
    }

    @Test
    fun `artist details map cover counts location and social links`() = withServer { server, provider ->
        server.enqueue(
            jsonResponse(
                """{
                  "data":{
                    "id":"artist-id","name":"Artist Name","handle":"artist_handle","bio":"Artist bio",
                    "website":"https://artist.test","location":"New York, US","created_at":"2020-01-02",
                    "follower_count":120,"followee_count":9,"track_count":14,"album_count":3,"playlist_count":2,
                    "is_verified":true,"twitter_handle":"artist_x","instagram_handle":"artist_ig","tiktok_handle":"@artist_tt",
                    "profile_picture":{"150x150":"https://img.test/avatar-150.jpg","1000x1000":"https://img.test/avatar.jpg"},
                    "cover_photo":{"480x480":"https://img.test/cover-480.jpg","1000x1000":"https://img.test/cover.jpg"}
                  }
                }""",
            ),
        )

        val details = runBlocking { provider.getArtistDetails("artist-id") }
        val request = server.takeRequest()

        assertEquals("/v1/users/artist-id", request.requestUrl?.encodedPath)
        assertEquals("Artist Name", details.artist.name)
        assertEquals("https://audius.co/artist_handle", details.artist.permalink)
        assertEquals("https://img.test/cover.jpg", details.coverImage?.largeUrl)
        assertEquals("New York, US", details.location?.displayName)
        assertEquals(9, details.followingCount)
        assertEquals(3, details.albumCount)
        assertEquals(2, details.playlistCount)
        assertEquals("artist_tt", details.socialLinks.first { it.platform.name == "TIKTOK" }.handle)
        assertTrue(details.capabilities.supports(MusicArtistFeature.PLAYLISTS))
        assertFalse(details.capabilities.supports(MusicArtistFeature.TAGS))
    }

    @Test
    fun `artist albums and playlists use dedicated paged endpoints`() = withServer { server, provider ->
        server.enqueue(jsonResponse("""{"data":[${collectionJson("album", true)},${collectionJson("album-2", true)}]}"""))
        server.enqueue(jsonResponse("""{"data":[${collectionJson("playlist", false)}]}"""))

        val albumRequestPage = PageRequest(offset = 10, limit = 2)
        val albums = runBlocking { provider.artistAlbums("artist-id", albumRequestPage) }.toPublic(albumRequestPage)
        val playlists = runBlocking { provider.artistPlaylists("artist-id", PageRequest(limit = 2)) }.toPublic(PageRequest(limit = 2))
        val albumRequest = server.takeRequest()
        val playlistRequest = server.takeRequest()

        assertEquals("/v1/users/artist-id/albums", albumRequest.requestUrl?.encodedPath)
        assertEquals("10", albumRequest.requestUrl?.queryParameter("offset"))
        assertEquals(12, albums.nextOffset)
        assertEquals("/v1/users/artist-id/playlists", playlistRequest.requestUrl?.encodedPath)
        assertEquals(listOf("playlist"), playlists.items.map { it.id })
        assertNull(playlists.nextOffset)
    }

    @Test
    fun `popular track query uses weekly trending discovery`() = withServer { server, provider ->
        server.enqueue(jsonResponse("""{"data":[${trackJson("weekly", true)}]}"""))

        val result = runBlocking { provider.tracks(TrackQuery(sort = MusicSort.POPULAR), PageRequest(limit = 8)) }
        val request = server.takeRequest()

        assertEquals("/v1/tracks/trending", request.requestUrl?.encodedPath)
        assertEquals("week", request.requestUrl?.queryParameter("time"))
        assertFalse(result.items.single().isStreamGated)
    }

    @Test
    fun `authentication failure rotates api key and its paired bearer token`() {
        val credentials = listOf(
            AudiusCredential("first-api", "first-token"),
            AudiusCredential("second-api", "second-token"),
        )
        withServer(credentials) { server, provider ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            server.enqueue(jsonResponse("""{"data":[${trackJson("fallback", true)}]}"""))

            val tracks = runBlocking { provider.trending(0, 1) }
            val firstRequest = server.takeRequest()
            val secondRequest = server.takeRequest()

            assertEquals(listOf("fallback"), tracks.map { it.id })
            assertEquals("first-api", firstRequest.getHeader("x-api-key"))
            assertEquals("Bearer first-token", firstRequest.getHeader("Authorization"))
            assertEquals("second-api", secondRequest.getHeader("x-api-key"))
            assertEquals("Bearer second-token", secondRequest.getHeader("Authorization"))
        }
    }

    private fun withServer(
        credentials: List<AudiusCredential> = listOf(AudiusCredential("test-key", "test-token")),
        block: (MockWebServer, AudiusProvider) -> Unit,
    ) {
        val server = MockWebServer()
        server.start()
        try {
            val provider = AudiusProvider(
                client = OkHttpClient(),
                keys = KeyPool(credentials, defaultCooldownMs = 0),
                baseUrl = server.url("/v1").toString().trimEnd('/'),
            )
            block(server, provider)
        } finally {
            server.shutdown()
        }
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun trackJson(id: String, streamable: Boolean) = """
        {
          "id":"$id",
          "title":"Track $id",
          "user":{"id":"artist-id","name":"Artist"},
          "user_id":"artist-id",
          "duration":120,
          "description":"About $id",
          "artwork":{
            "150x150":"https://primary.test/content/$id/150x150.jpg",
            "480x480":"https://primary.test/content/$id/480x480.jpg",
            "1000x1000":"https://primary.test/content/$id/1000x1000.jpg",
            "mirrors":["https://mirror.test"]
          },
          "is_streamable":$streamable,
          "is_stream_gated":false
        }
    """.trimIndent()

    private fun collectionJson(id: String, isAlbum: Boolean) = """
        {
          "id":"$id",
          "playlist_name":"Collection $id",
          "is_album":$isAlbum,
          "playlist_contents":[],
          "user":{"id":"artist-id","name":"Artist"}
        }
    """.trimIndent()
}
