package com.example.lcb.app.player

import com.example.lcb.app.home.HomeTrackUi

/** 首页展示模型进入播放层时完整保留封面候选链，避免不同页面重复且不一致的字段映射。 */
internal fun HomeTrackUi.toPlayerTrack(): PlayerTrack = PlayerTrack(
    id = id,
    title = title,
    artist = artist,
    artworkUrl = artworkUrl,
    streamUrl = streamUrl,
    durationMs = durationMs,
    lyrics = lyrics,
    description = description,
    artistRef = artistRef,
    artworkThumbnailUrls = artworkThumbnailUrls,
)
