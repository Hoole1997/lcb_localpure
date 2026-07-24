package com.example.lcb.app.player

/** 播放页曲目文本的语义类型，避免将平台介绍错标为歌词。 */
internal enum class TrackTextType { LYRICS, DESCRIPTION }

internal data class TrackTextContent(
    val type: TrackTextType,
    val rawText: String,
)

/** 歌词信息更精确，两类内容同时存在时优先展示歌词。 */
internal fun PlayerTrack.resolveDisplayText(): TrackTextContent? =
    lyrics.normalized()?.let { TrackTextContent(TrackTextType.LYRICS, it) }
        ?: description.normalized()?.let { TrackTextContent(TrackTextType.DESCRIPTION, it) }

private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)
