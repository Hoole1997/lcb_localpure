package com.example.lcb.app.search

import com.example.lcb.app.R
import com.example.lcb.music.MusicSdk
import com.example.lcb.music.model.MusicTrack

interface SearchRepository {
    suspend fun search(query: String, offset: Int, limit: Int): SearchPage
}

data class SearchPage(
    val tracks: List<SearchTrackUi>,
    val nextOffset: Int?,
)

/** 平台并发、去重、Key 轮换与可播放过滤全部由 MusicSdk 负责。 */
class MusicSdkSearchRepository(private val musicSdk: MusicSdk) : SearchRepository {
    override suspend fun search(query: String, offset: Int, limit: Int): SearchPage {
        val page = musicSdk.searchTracks(query = query, offset = offset, limit = limit)
        val tracks = page.items
            .asSequence()
            .filter { it.isStreamable && it.streamUrl.isNotBlank() }
            .map(::toUi)
            .toList()
        val nextOffset = if (page.hasMore) {
            page.nextOffset?.takeIf { it > offset } ?: offset + limit
        } else {
            null
        }
        return SearchPage(tracks = tracks, nextOffset = nextOffset)
    }

    private fun toUi(track: MusicTrack) = SearchTrackUi(
        id = "${track.platform}:${track.id}",
        title = track.title,
        artist = track.artistName,
        artworkFallbackRes = R.drawable.home_cover_recommended_3,
        artworkUrl = track.artwork?.preferredUrl ?: track.artworkUrl,
        artworkThumbnailUrls = track.artwork?.thumbnailCandidates().orEmpty()
            .ifEmpty { listOfNotNull(track.artworkUrl) },
        streamUrl = track.streamUrl,
        durationMs = track.durationMs,
        lyrics = track.lyrics,
        description = track.description,
        artistRef = track.artist,
    )
}
