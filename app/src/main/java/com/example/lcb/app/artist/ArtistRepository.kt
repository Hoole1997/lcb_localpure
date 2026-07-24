package com.example.lcb.app.artist

import com.example.lcb.app.R
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.music.MusicSdk
import com.example.lcb.music.model.MusicArtistDetails
import com.example.lcb.music.model.MusicCollection
import com.example.lcb.music.model.MusicCollectionType
import com.example.lcb.music.model.MusicPage
import com.example.lcb.music.model.MusicSort
import com.example.lcb.music.model.MusicTrack
import com.example.lcb.music.model.PageRequest

interface ArtistRepository {
    suspend fun loadDetails(request: ArtistRequest): MusicArtistDetails
    suspend fun loadTracks(request: ArtistRequest, offset: Int, limit: Int): ArtistTrackPage
    suspend fun loadAlbums(request: ArtistRequest, limit: Int): List<ArtistCollectionUi>
    suspend fun loadPlaylists(request: ArtistRequest, limit: Int): List<ArtistCollectionUi>
    suspend fun loadCollectionTracks(collection: ArtistCollectionUi, limit: Int): List<PlayerTrack>
}

data class ArtistTrackPage(
    val tracks: List<ArtistTrackUi>,
    val nextOffset: Int?,
)

/**
 * SDK DTO 到歌手页展示模型的唯一映射层。Activity/ViewModel 不感知平台字段差异，
 * 平台 Key 轮换、可播放过滤和请求线程均继续由聚合 SDK 负责。
 */
class MusicSdkArtistRepository(
    private val musicSdk: MusicSdk,
) : ArtistRepository {
    override suspend fun loadDetails(request: ArtistRequest): MusicArtistDetails =
        musicSdk.getArtistDetails(request.platform, request.artistId)

    override suspend fun loadTracks(request: ArtistRequest, offset: Int, limit: Int): ArtistTrackPage {
        val page = musicSdk.artistTracks(
            platform = request.platform,
            artistId = request.artistId,
            page = PageRequest(offset, limit),
            sort = MusicSort.POPULAR,
        )
        return page.toArtistTrackPage(offset, limit)
    }

    override suspend fun loadAlbums(request: ArtistRequest, limit: Int): List<ArtistCollectionUi> =
        musicSdk.artistAlbums(
            request.platform,
            request.artistId,
            PageRequest(limit = limit),
        ).items.map(::toCollectionUi)

    override suspend fun loadPlaylists(request: ArtistRequest, limit: Int): List<ArtistCollectionUi> =
        musicSdk.artistPlaylists(
            request.platform,
            request.artistId,
            PageRequest(limit = limit),
        ).items.map(::toCollectionUi)

    override suspend fun loadCollectionTracks(
        collection: ArtistCollectionUi,
        limit: Int,
    ): List<PlayerTrack> {
        val page = when (collection.type) {
            MusicCollectionType.ALBUM -> musicSdk.albumTracks(
                collection.platform,
                collection.id,
                PageRequest(limit = limit),
            )
            MusicCollectionType.PLAYLIST -> musicSdk.playlistTracks(
                collection.platform,
                collection.id,
                PageRequest(limit = limit),
            )
        }
        return page.items
            .asSequence()
            .filter { it.isStreamable && it.streamUrl.isNotBlank() }
            .map(::toPlayerTrack)
            .distinctBy(PlayerTrack::id)
            .toList()
    }

    private fun MusicPage<MusicTrack>.toArtistTrackPage(offset: Int, limit: Int): ArtistTrackPage {
        val playable = items.asSequence()
            .filter { it.isStreamable && it.streamUrl.isNotBlank() }
            .map { track ->
                ArtistTrackUi(
                    track = toPlayerTrack(track),
                    artworkUrls = track.artwork?.thumbnailCandidates().orEmpty()
                        .ifEmpty { listOfNotNull(track.artworkUrl) },
                    artworkFallbackRes = R.drawable.home_cover_recommended_3,
                )
            }
            .toList()
        val next = if (hasMore) nextOffset?.takeIf { it > offset } ?: offset + limit else null
        return ArtistTrackPage(playable, next)
    }

    private fun toPlayerTrack(track: MusicTrack) = PlayerTrack(
        id = "${track.platform}:${track.id}",
        title = track.title,
        artist = track.artistName,
        artworkUrl = track.artwork?.preferredUrl ?: track.artworkUrl,
        streamUrl = track.streamUrl,
        durationMs = track.durationMs,
        lyrics = track.lyrics,
        description = track.description,
        artistRef = track.artist,
    )

    private fun toCollectionUi(collection: MusicCollection) = ArtistCollectionUi(
        id = collection.id,
        platform = collection.platform,
        type = collection.type,
        title = collection.title,
        subtitle = collection.releaseDate
            ?: collection.owner?.name
            .orEmpty(),
        artworkUrls = collection.artwork?.thumbnailCandidates().orEmpty()
            .ifEmpty { listOfNotNull(collection.artwork?.preferredUrl) },
        artworkFallbackRes = R.drawable.home_cover_recommended_3,
        trackCount = collection.trackCount,
    )
}
