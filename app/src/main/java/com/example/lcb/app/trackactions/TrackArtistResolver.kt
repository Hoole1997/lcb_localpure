package com.example.lcb.app.trackactions

import com.example.lcb.music.MusicSdk
import com.example.lcb.music.model.MusicArtistRef
import com.example.lcb.music.model.MusicPlatform
import com.example.lcb.music.model.MusicTrack
import java.util.Locale

/** Song Info 的歌手解析与弹框、Activity 解耦，便于旧歌单数据按需补齐歌手引用。 */
fun interface TrackArtistResolver {
    suspend fun resolve(track: TrackActionUiModel): MusicArtistRef?
}

class MusicSdkTrackArtistResolver internal constructor(
    private val loadTrack: suspend (MusicPlatform, String) -> MusicTrack,
) : TrackArtistResolver {
    constructor(musicSdk: MusicSdk) : this(musicSdk::getTrack)

    override suspend fun resolve(track: TrackActionUiModel): MusicArtistRef? {
        track.artistRef?.let { return it }
        val source = track.id.toSourceTrackId() ?: return null
        return loadTrack(source.first, source.second).artist
    }
}

/** App 内在线歌曲 id 统一为 PLATFORM:upstreamId；这里只接受明确格式，禁止用歌手名模糊查询。 */
private fun String.toSourceTrackId(): Pair<MusicPlatform, String>? {
    val separator = indexOf(':')
    if (separator <= 0 || separator == lastIndex) return null
    val platform = substring(0, separator)
        .uppercase(Locale.US)
        .let { runCatching { MusicPlatform.valueOf(it) }.getOrNull() }
        ?: return null
    val upstreamId = substring(separator + 1).takeIf(String::isNotBlank) ?: return null
    return platform to upstreamId
}
