package com.example.lcb.app.library

import androidx.annotation.DrawableRes
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.music.model.MusicArtistRef

data class LibraryTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val artworkThumbnailUrls: List<String>,
    @param:DrawableRes val artworkFallbackRes: Int,
    val streamUrl: String,
    val durationMs: Long,
    val lyrics: String?,
    val description: String?,
    val artistRef: MusicArtistRef? = null,
) {
    fun toPlayerTrack() = PlayerTrack(
        id = id,
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        streamUrl = streamUrl,
        durationMs = durationMs,
        lyrics = lyrics,
        description = description,
        artistRef = artistRef,
    )
}

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val artworkUrl: String?,
    val artworkThumbnailUrls: List<String>,
)

enum class AddTrackResult { ADDED, ALREADY_PRESENT }

sealed interface LibraryCollection {
    data object Favorites : LibraryCollection
    data class Playlist(val id: Long) : LibraryCollection
}

fun PlayerTrack.toLibraryTrack(
    artworkThumbnailUrls: List<String> = listOfNotNull(artworkUrl),
    @DrawableRes artworkFallbackRes: Int,
) = LibraryTrack(
    id = id,
    title = title,
    artist = artist,
    artworkUrl = artworkUrl,
    artworkThumbnailUrls = artworkThumbnailUrls,
    artworkFallbackRes = artworkFallbackRes,
    streamUrl = streamUrl,
    durationMs = durationMs,
    lyrics = lyrics,
    description = description,
    artistRef = artistRef,
)
