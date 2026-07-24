package com.example.lcb.app.recommended

import com.example.lcb.app.R
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.music.MusicSdk
import com.example.lcb.music.model.MusicSort
import com.example.lcb.music.model.MusicTrack
import com.example.lcb.music.model.PageRequest
import com.example.lcb.music.model.TrackQuery

interface RecommendedMusicRepository {
    suspend fun load(offset: Int, limit: Int): RecommendedMusicPage
}

data class RecommendedMusicPage(
    val tracks: List<RecommendedTrackUi>,
    val nextOffset: Int?,
)

/** 推荐策略与首页一致，使用多平台周热门并由聚合 SDK 负责穿插、去重和 Key 容灾。 */
class MusicSdkRecommendedMusicRepository(
    private val musicSdk: MusicSdk,
) : RecommendedMusicRepository {
    override suspend fun load(offset: Int, limit: Int): RecommendedMusicPage {
        val page = musicSdk.tracks(
            query = TrackQuery(sort = MusicSort.POPULAR),
            page = PageRequest(offset = offset, limit = limit),
        )
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
        return RecommendedMusicPage(tracks, nextOffset)
    }

    private fun toUi(track: MusicTrack) = RecommendedTrackUi(
        track = PlayerTrack(
            id = "${track.platform}:${track.id}",
            title = track.title,
            artist = track.artistName,
            artworkUrl = track.artwork?.preferredUrl ?: track.artworkUrl,
            streamUrl = track.streamUrl,
            durationMs = track.durationMs,
            lyrics = track.lyrics,
            description = track.description,
            artistRef = track.artist,
        ),
        artworkThumbnailUrls = track.artwork?.thumbnailCandidates().orEmpty()
            .ifEmpty { listOfNotNull(track.artworkUrl) },
        artworkFallbackRes = R.drawable.home_cover_recommended_3,
    )
}
