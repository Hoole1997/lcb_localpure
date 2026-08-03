package com.example.lcb.app.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.lcb.music.model.MusicArtistRef
import com.example.lcb.music.model.MusicPlatform

/** 从 MediaSession 读取不可变队列快照，供不同页面的 Mini Player/队列弹层共同使用。 */
internal fun Player.toPlayerTrackQueue(): List<PlayerTrack> = List(mediaItemCount) { index ->
    getMediaItemAt(index).toPlayerTrack(
        durationMs = if (index == currentMediaItemIndex) {
            duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        } else {
            0L
        },
    )
}

internal fun MediaItem.toPlayerTrack(durationMs: Long): PlayerTrack {
    val artworkUrl = mediaMetadata.artworkUri?.toString()
    val extras = mediaMetadata.extras
    val artworkThumbnailUrls = extras
        ?.getStringArrayList(MEDIA_METADATA_ARTWORK_THUMBNAILS_KEY)
        .orEmpty()
        .ifEmpty { listOfNotNull(artworkUrl) }
    return PlayerTrack(
        id = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        artworkUrl = artworkUrl,
        streamUrl = localConfiguration?.uri?.toString().orEmpty(),
        durationMs = durationMs,
        lyrics = extras?.getString(MEDIA_METADATA_LYRICS_KEY),
        description = extras?.getString(MEDIA_METADATA_DESCRIPTION_KEY),
        artistRef = extras?.let {
            val artistId = it.getString(MEDIA_METADATA_ARTIST_ID_KEY).orEmpty()
            val platform = it.getString(MEDIA_METADATA_ARTIST_PLATFORM_KEY)
                ?.let { raw -> runCatching { MusicPlatform.valueOf(raw) }.getOrNull() }
            if (artistId.isNotBlank() && platform != null) {
                MusicArtistRef(artistId, platform, mediaMetadata.artist?.toString().orEmpty())
            } else {
                null
            }
        },
        artworkThumbnailUrls = artworkThumbnailUrls,
    )
}
