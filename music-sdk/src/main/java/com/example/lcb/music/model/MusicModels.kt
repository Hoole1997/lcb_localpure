package com.example.lcb.music.model

import java.net.URI

/** 媒体来源；业务层可用于展示版权归属或做来源筛选。 */
enum class MusicPlatform { JAMENDO, AUDIUS }

data class MusicImage(
    val smallUrl: String? = null,
    val mediumUrl: String? = null,
    val largeUrl: String? = null,
    /** Audius 返回的备用内容节点；图片加载失败时业务层可依次替换 URL host。 */
    val mirrors: List<String> = emptyList(),
) {
    val preferredUrl: String? get() = largeUrl ?: mediumUrl ?: smallUrl

    /** 列表优先使用平台的最小图片，并将 Audius 镜像节点展开成可直接加载的备用 URL。 */
    fun thumbnailCandidates(): List<String> = candidatesFor(smallUrl ?: mediumUrl ?: largeUrl)

    private fun candidatesFor(primaryUrl: String?): List<String> {
        if (primaryUrl.isNullOrBlank()) return emptyList()
        val primaryUri = runCatching { URI(primaryUrl) }.getOrNull()
        val path = primaryUri?.rawPath?.takeIf(String::isNotBlank) ?: return listOf(primaryUrl)
        val suffix = buildString {
            append(path)
            primaryUri.rawQuery?.let { append('?').append(it) }
        }
        return buildList {
            add(primaryUrl)
            mirrors.forEach { mirror ->
                mirror.trim().takeIf(String::isNotBlank)?.let { add(it.trimEnd('/') + suffix) }
            }
        }.distinct()
    }
}

data class MusicArtist(
    val id: String,
    val platform: MusicPlatform,
    val name: String,
    val handle: String? = null,
    val image: MusicImage? = null,
    val bio: String? = null,
    val website: String? = null,
    val permalink: String? = null,
    val followerCount: Int? = null,
    val trackCount: Int? = null,
    val isVerified: Boolean = false,
)

/**
 * 曲目中携带的稳定歌手引用。业务层应使用 platform + id 打开歌手页，不能用可能重名的 name 反查。
 */
data class MusicArtistRef(
    val id: String,
    val platform: MusicPlatform,
    val name: String,
)

enum class MusicSocialPlatform { WEBSITE, TWITTER, INSTAGRAM, TIKTOK }

data class MusicSocialLink(
    val platform: MusicSocialPlatform,
    val url: String,
    val handle: String? = null,
)

data class MusicArtistLocation(
    val displayName: String,
    val city: String? = null,
    /** ISO 3166-1 alpha-3 或平台提供的国家标识；未知时为空。 */
    val countryCode: String? = null,
)

/** 平台明确支持的歌手页能力，避免业务层把“不支持”错误展示为 0 或空列表。 */
enum class MusicArtistFeature {
    TRACKS,
    ALBUMS,
    PLAYLISTS,
    COVER_IMAGE,
    BIOGRAPHY,
    LOCATION,
    TAGS,
    JOIN_DATE,
    VERIFICATION,
    FOLLOWER_COUNT,
    FOLLOWING_COUNT,
    SOCIAL_LINKS,
}

data class MusicArtistCapabilities(
    val supportedFeatures: Set<MusicArtistFeature>,
) {
    fun supports(feature: MusicArtistFeature): Boolean = feature in supportedFeatures
}

/**
 * 歌手页详情。基础信息继续复用 MusicArtist，平台特有字段统一为可空值，并由 capabilities 描述能力边界。
 */
data class MusicArtistDetails(
    val artist: MusicArtist,
    val coverImage: MusicImage? = null,
    val location: MusicArtistLocation? = null,
    val followingCount: Int? = null,
    val albumCount: Int? = null,
    val playlistCount: Int? = null,
    val tags: List<String> = emptyList(),
    val joinedDate: String? = null,
    val socialLinks: List<MusicSocialLink> = emptyList(),
    val capabilities: MusicArtistCapabilities,
)

enum class MusicCollectionType { ALBUM, PLAYLIST }

data class MusicCollection(
    val id: String,
    val platform: MusicPlatform,
    val type: MusicCollectionType,
    val title: String,
    val owner: MusicArtist? = null,
    val artwork: MusicImage? = null,
    val description: String? = null,
    val trackCount: Int? = null,
    val releaseDate: String? = null,
    val permalink: String? = null,
)

/** 聚合 SDK 对外唯一的曲目模型，隔离各平台字段差异。 */
data class MusicTrack(
    val id: String,
    val platform: MusicPlatform,
    val title: String,
    val artistName: String,
    val artworkUrl: String?,
    val artwork: MusicImage? = artworkUrl?.let { MusicImage(largeUrl = it) },
    val durationMs: Long,
    val streamUrl: String,
    val albumName: String? = null,
    val genre: String? = null,
    val permalink: String? = null,
    /** 平台提供的非时间轴歌词；没有歌词能力或曲目未提供时为 null。 */
    val lyrics: String? = null,
    /** 平台提供的歌曲介绍；它与歌词语义不同，由业务层明确标注后展示。 */
    val description: String? = null,
    /** 当前调用方是否可以直接播放；聚合列表默认只返回 true 的曲目。 */
    val isStreamable: Boolean = true,
    /** 是否配置了关注、购买或其他访问门控。 */
    val isStreamGated: Boolean = false,
    /** 用于从任意歌曲入口稳定跳转到歌手页；旧数据缺少 ID 时允许为空。 */
    val artist: MusicArtistRef? = null,
) {
    val artistId: String? get() = artist?.id
}

data class MusicPage<T>(
    val items: List<T>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    /** 平台未提供总数时为 null。聚合结果通常无法得到精确总数。 */
    val totalCount: Int? = null,
    /**
     * 下一次应提交给同一接口的上游 offset。经过不可播放内容过滤后，它可能大于 offset + items.size。
     */
    val nextOffset: Int? = if (hasMore) offset + items.size else null,
)

data class PageRequest(val offset: Int = 0, val limit: Int = 20) {
    init {
        require(offset >= 0) { "offset cannot be negative" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
    }
}

enum class MusicSort { RELEVANCE, POPULAR, LATEST }

data class TrackQuery(
    val text: String? = null,
    val genre: String? = null,
    val mood: String? = null,
    val artistId: String? = null,
    val collectionId: String? = null,
    val minDurationSeconds: Int? = null,
    val maxDurationSeconds: Int? = null,
    val sort: MusicSort = MusicSort.RELEVANCE,
    val platform: MusicPlatform? = null,
)

data class ProviderHealth(
    val platform: MusicPlatform,
    val availableKeys: Int,
    val coolingDownKeys: Int,
    val disabledKeys: Int,
    val isAvailable: Boolean,
)
