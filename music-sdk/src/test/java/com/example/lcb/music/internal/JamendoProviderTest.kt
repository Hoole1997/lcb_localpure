package com.example.lcb.music.internal

import com.example.lcb.music.model.PageRequest
import com.example.lcb.music.model.MusicArtistFeature
import com.example.lcb.music.model.MusicSort
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

class JamendoProviderTest {
    @Test
    fun `track request includes and maps real lyrics`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "headers":{"status":"success","code":0,"results_fullcount":1},
                  "results":[{
                    "id":"42","name":"Real Song","artist_id":"artist-42","artist_name":"Artist","duration":120,
                    "audio":"https://example.test/song.mp3","lyrics":"First line\nSecond line",
                    "image":"https://usercontent.jamendo.test?type=album&id=42&width=600"
                  }]
                }""",
            ),
        )
        server.start()
        try {
            val provider = JamendoProvider(
                client = OkHttpClient(),
                keys = KeyPool(listOf("test-client"), defaultCooldownMs = 0),
                baseUrl = server.url("/v3.0").toString().trimEnd('/'),
            )

            val page = runBlocking { provider.tracks(TrackQuery(), PageRequest(limit = 1)) }
            val request = server.takeRequest()

            assertEquals("lyrics", request.requestUrl?.queryParameter("include"))
            assertEquals("First line\nSecond line", page.items.single().lyrics)
            assertEquals("artist-42", page.items.single().artistId)
            assertEquals(
                "https://usercontent.jamendo.test/?type=album&id=42&width=150",
                page.items.single().artwork?.smallUrl,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `artist details combine music info and optional location`() = withServer { server, provider ->
        server.enqueue(
            successResponse(
                """{
                  "id":"376782","name":"WE ARE FM","website":"https://artist.test","joindate":"2011-12-29",
                  "image":"https://img.test/artist.jpg","shareurl":"https://jamendo.test/artist/376782",
                  "musicinfo":{
                    "tags":["rock","electronic","rock"],
                    "description":{"en":"<p>Energetic &amp; independent.</p><p>Second line.</p>","fr":""}
                  }
                }""",
            ),
        )
        server.enqueue(
            successResponse(
                """{
                  "id":"376782","name":"WE ARE FM",
                  "locations":[{"country":"NLD","city":"Amsterdam"}]
                }""",
            ),
        )

        val details = runBlocking { provider.getArtistDetails("376782") }
        val profileRequest = server.takeRequest()
        val locationRequest = server.takeRequest()

        assertEquals("/v3.0/artists/musicinfo/", profileRequest.requestUrl?.encodedPath)
        assertEquals("/v3.0/artists/locations/", locationRequest.requestUrl?.encodedPath)
        assertEquals("Energetic & independent.\nSecond line.", details.artist.bio)
        assertEquals(listOf("rock", "electronic"), details.tags)
        assertEquals("Amsterdam, NLD", details.location?.displayName)
        assertEquals("NLD", details.location?.countryCode)
        assertEquals("2011-12-29", details.joinedDate)
        assertTrue(details.capabilities.supports(MusicArtistFeature.ALBUMS))
        assertFalse(details.capabilities.supports(MusicArtistFeature.PLAYLISTS))
    }

    @Test
    fun `basic artist lookup remains a single lightweight request`() = withServer { server, provider ->
        server.enqueue(
            successResponse(
                """{
                  "id":"artist-1","name":"Artist","image":"https://img.test/artist.jpg",
                  "shareurl":"https://jamendo.test/artist/artist-1"
                }""",
            ),
        )

        val artist = runBlocking { provider.getArtist("artist-1") }
        val request = server.takeRequest()

        assertEquals("Artist", artist.name)
        assertEquals("/v3.0/artists/", request.requestUrl?.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `artist albums expose exact total and artist filter`() = withServer { server, provider ->
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "headers":{"status":"success","code":0,"results_fullcount":5},
                  "results":[{
                    "id":"album-1","name":"First Album","artist_id":"artist-1","artist_name":"Artist",
                    "image":"https://img.test/album.jpg","releasedate":"2024-01-01"
                  }]
                }""",
            ),
        )

        val requestPage = PageRequest(offset = 2, limit = 1)
        val albums = runBlocking { provider.artistAlbums("artist-1", requestPage) }.toPublic(requestPage)
        val request = server.takeRequest()

        assertEquals("artist-1", request.requestUrl?.queryParameter("artist_id"))
        assertEquals("500", request.requestUrl?.queryParameter("imagesize"))
        assertEquals(5, albums.totalCount)
        assertEquals(3, albums.nextOffset)
        assertEquals("artist-1", albums.items.single().owner?.id)
    }

    @Test
    fun `artist latest tracks use release date sorting`() = withServer { server, provider ->
        server.enqueue(successResponse("""{
          "id":"track-1","name":"Latest","artist_id":"artist-1","artist_name":"Artist",
          "duration":100,"audio":"https://audio.test/latest.mp3"
        }"""))

        runBlocking { provider.artistTracks("artist-1", PageRequest(limit = 1), MusicSort.LATEST) }
        val request = server.takeRequest()

        assertEquals("releasedate_desc", request.requestUrl?.queryParameter("order"))
        assertEquals("artist-1", request.requestUrl?.queryParameter("artist_id"))
    }

    @Test
    fun `unsupported artist playlists return an explicit empty terminal page`() = withServer { server, provider ->
        val page = runBlocking { provider.artistPlaylists("artist-1", PageRequest()) }.toPublic(PageRequest())

        assertTrue(page.items.isEmpty())
        assertNull(page.totalCount)
        assertNull(page.nextOffset)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `invalid client id in successful http body rotates to next credential`() =
        withServer(listOf("invalid-client", "healthy-client")) { server, provider ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"headers":{"status":"failed","code":5,"error_message":"Invalid Client Id"},"results":[]}""",
                ),
            )
            server.enqueue(successResponse("""{
              "id":"track-1","name":"Fallback","artist_id":"artist-1","artist_name":"Artist",
              "duration":100,"audio":"https://audio.test/fallback.mp3"
            }"""))

            val page = runBlocking { provider.tracks(TrackQuery(), PageRequest(limit = 1)) }
            val failedRequest = server.takeRequest()
            val successfulRequest = server.takeRequest()

            assertEquals("Fallback", page.items.single().title)
            assertEquals("invalid-client", failedRequest.requestUrl?.queryParameter("client_id"))
            assertEquals("healthy-client", successfulRequest.requestUrl?.queryParameter("client_id"))
            assertEquals(1, provider.health().disabledKeys)
        }

    private fun withServer(
        clientIds: List<String> = listOf("test-client"),
        block: (MockWebServer, JamendoProvider) -> Unit,
    ) {
        val server = MockWebServer()
        server.start()
        try {
            val provider = JamendoProvider(
                client = OkHttpClient(),
                keys = KeyPool(clientIds, defaultCooldownMs = 0),
                baseUrl = server.url("/v3.0").toString().trimEnd('/'),
            )
            block(server, provider)
        } finally {
            server.shutdown()
        }
    }

    private fun successResponse(item: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{
              "headers":{"status":"success","code":0,"results_fullcount":1},
              "results":[$item]
            }""",
        )
}
