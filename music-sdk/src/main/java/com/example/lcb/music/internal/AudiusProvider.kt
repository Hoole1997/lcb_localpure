package com.example.lcb.music.internal

import com.example.lcb.music.AudiusCredential
import com.example.lcb.music.MusicSdkCredentials
import com.example.lcb.music.model.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class AudiusProvider(
    private val client: OkHttpClient,
    private val keys: KeyPool<AudiusCredential>,
    private val baseUrl: String = "https://api.audius.co/v1",
) : MusicProvider {
    override val platform = MusicPlatform.AUDIUS

    override suspend fun search(query: String, offset: Int, limit: Int) =
        tracks(TrackQuery(text = query), PageRequest(offset, limit.coerceAtMost(100))).items

    override suspend fun trending(offset: Int, limit: Int) =
        trackPage("tracks/trending", pageParams(PageRequest(offset, limit.coerceAtMost(100)))).items

    override suspend fun getTrack(id: String): MusicTrack {
        val item = dataObject("tracks/$id") ?: throw MusicSdkException("Audius track $id was not found")
        if (!item.isStreamable()) throw MusicSdkException("Audius track $id is not streamable")
        return mapTrack(item)
    }

    override suspend fun tracks(query: TrackQuery, page: PageRequest): ProviderPage<MusicTrack> {
        if (query.artistId != null) return artistTracks(query.artistId, page, query.sort)
        if (query.collectionId != null) return playlistTracks(query.collectionId, page)
        val endpoint = if (query.text.isNullOrBlank() && query.sort == MusicSort.POPULAR) "tracks/trending" else
            if (query.text.isNullOrBlank() && query.sort == MusicSort.LATEST) "tracks/latest" else "tracks/search"
        val params = pageParams(page).toMutableMap()
        query.text?.takeIf(String::isNotBlank)?.let { params["query"] = it }
        query.genre?.takeIf(String::isNotBlank)?.let { params["genre"] = it }
        query.mood?.takeIf(String::isNotBlank)?.let { params["mood"] = it }
        if (endpoint == "tracks/trending") params["time"] = "week"
        if (endpoint == "tracks/search") params["sort_method"] = when (query.sort) {
            MusicSort.RELEVANCE -> "relevant"; MusicSort.POPULAR -> "popular"; MusicSort.LATEST -> "recent"
        }
        return trackPage(endpoint, params)
    }

    override suspend fun similarTracks(id: String, page: PageRequest): ProviderPage<MusicTrack> =
        trackPage("tracks/recommended", mapOf("limit" to page.limit.toString(), "exclusion_list" to id))

    override suspend fun searchArtists(query: String, page: PageRequest) =
        list("users/search", pageParams(page) + ("query" to query), ::mapArtist)

    override suspend fun getArtistDetails(id: String) = dataObject("users/$id")?.let(::mapArtistDetails)
        ?: throw MusicSdkException("Audius artist $id was not found")

    override suspend fun artistTracks(id: String, page: PageRequest, sort: MusicSort) =
        trackPage("users/$id/tracks", artistTrackParams(page, sort))

    override suspend fun artistAlbums(id: String, page: PageRequest): ProviderPage<MusicCollection> {
        val result = list("users/$id/albums", pageParams(page), ::mapCollection)
        return result.copy(items = result.items.filter { it.type == MusicCollectionType.ALBUM })
    }

    override suspend fun artistPlaylists(id: String, page: PageRequest): ProviderPage<MusicCollection> {
        val result = list("users/$id/playlists", pageParams(page), ::mapCollection)
        return result.copy(items = result.items.filter { it.type == MusicCollectionType.PLAYLIST })
    }

    override suspend fun searchAlbums(query: String, page: PageRequest): ProviderPage<MusicCollection> {
        // Audius 的 album 与 playlist 共用实体，搜索响应通过 is_album 区分。
        val result = list("playlists/search", pageParams(page) + ("query" to query), ::mapCollection)
        return result.copy(items = result.items.filter { it.type == MusicCollectionType.ALBUM })
    }

    override suspend fun getAlbum(id: String) = getCollection(id, MusicCollectionType.ALBUM)

    override suspend fun albumTracks(id: String, page: PageRequest) = collectionTracks(id, page)

    override suspend fun searchPlaylists(query: String, page: PageRequest): ProviderPage<MusicCollection> {
        val result = list("playlists/search", pageParams(page) + ("query" to query), ::mapCollection)
        return result.copy(items = result.items.filter { it.type == MusicCollectionType.PLAYLIST })
    }

    override suspend fun getPlaylist(id: String) = getCollection(id, MusicCollectionType.PLAYLIST)

    override suspend fun playlistTracks(id: String, page: PageRequest) = collectionTracks(id, page)

    private suspend fun getCollection(id: String, expected: MusicCollectionType): MusicCollection {
        val collection = dataObject("playlists/$id")?.let(::mapCollection)
            ?: throw MusicSdkException("Audius collection $id was not found")
        if (collection.type != expected) throw MusicSdkException("Audius collection $id is ${collection.type}, not $expected")
        return collection
    }

    private suspend fun collectionTracks(id: String, page: PageRequest): ProviderPage<MusicTrack> {
        // 官方接口不接收 offset/limit，因此仅在响应映射后做稳定的本地分页。
        val all = trackPage("playlists/$id/tracks", emptyMap()).items
        return ProviderPage(all.drop(page.offset).take(page.limit), all.size)
    }

    /** 在映射公共模型前读取 Audius 的可播放标记，避免向业务层暴露必然失败的流地址。 */
    private suspend fun trackPage(path: String, parameters: Map<String, String>): ProviderPage<MusicTrack> {
        val rawItems = request(path, parameters).getAsJsonArray("data")?.map { it.asJsonObject }.orEmpty()
        val playableItems = rawItems.filter { it.isStreamable() }.map(::mapTrack)
        val requestedLimit = parameters["limit"]?.toIntOrNull()
        val offset = parameters["offset"]?.toIntOrNull() ?: 0
        val hasMore = requestedLimit?.let { rawItems.size >= it } == true
        return ProviderPage(
            items = playableItems,
            hasMoreHint = hasMore,
            nextOffsetHint = (offset + rawItems.size).takeIf { hasMore },
        )
    }

    private suspend fun <T> list(path: String, parameters: Map<String, String>, mapper: (JsonObject) -> T): ProviderPage<T> {
        val root = request(path, parameters)
        val items = root.getAsJsonArray("data")?.map { mapper(it.asJsonObject) }.orEmpty()
        val requestedLimit = parameters["limit"]?.toIntOrNull()
        val offset = parameters["offset"]?.toIntOrNull() ?: 0
        val hasMore = requestedLimit?.let { items.size >= it }
        return ProviderPage(
            items = items,
            hasMoreHint = hasMore,
            nextOffsetHint = (offset + items.size).takeIf { hasMore == true },
        )
    }

    private suspend fun dataObject(path: String): JsonObject? = request(path, emptyMap()).getAsJsonObject("data")

    private suspend fun request(path: String, parameters: Map<String, String>): JsonObject = withKeyFailover(keys) { credential ->
        val url = "$baseUrl/$path".toHttpUrl().newBuilder()
            .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", credential.apiKey)
            .apply {
                credential.bearerToken?.let { token -> header("Authorization", "Bearer $token") }
            }
            .build()
        JsonParser.parseString(client.getJson(request)).asJsonObject
    }

    private fun pageParams(page: PageRequest) = mapOf("offset" to page.offset.toString(), "limit" to page.limit.toString())

    private fun artistTrackParams(page: PageRequest, sort: MusicSort): Map<String, String> = buildMap {
        putAll(pageParams(page))
        // 只请求公开曲目；仍保留 is_streamable 二次校验以兼容平台中的门控内容。
        put("filter_tracks", "public")
        put("sort_direction", "desc")
        put(
            "sort_method",
            when (sort) {
                MusicSort.POPULAR -> "plays"
                MusicSort.LATEST -> "release_date"
                MusicSort.RELEVANCE -> "added_date"
            },
        )
    }

    private fun mapImage(image: JsonObject?): MusicImage? = image?.let {
        MusicImage(it.optionalString("150x150"), it.optionalString("480x480"), it.optionalString("1000x1000"), it.stringList("mirrors"))
    }

    private fun mapTrack(item: JsonObject): MusicTrack {
        val id = item.string("id")
        val artwork = mapImage(item.getAsJsonObject("artwork"))
        val user = item.getAsJsonObject("user")
        val artistName = user?.string("name").orEmpty()
        val artistId = user?.string("id").orEmpty().ifBlank { item.string("user_id") }
        return MusicTrack(
            id = id, platform = platform, title = item.string("title"), artistName = artistName,
            artworkUrl = artwork?.preferredUrl, artwork = artwork, durationMs = item.long("duration") * 1_000,
            streamUrl = "$baseUrl/tracks/$id/stream", albumName = item.optionalString("album_name"),
            genre = item.optionalString("genre"), permalink = item.optionalString("permalink"),
            description = item.optionalString("description"),
            isStreamable = item.isStreamable(), isStreamGated = item.boolean("is_stream_gated"),
            artist = artistId.takeIf(String::isNotBlank)?.let { MusicArtistRef(it, platform, artistName) },
        )
    }

    /** 缺失字段兼容旧节点；字段明确为 false 时必须过滤。 */
    private fun JsonObject.isStreamable(): Boolean = get("is_streamable")
        ?.takeUnless { it.isJsonNull }
        ?.asBoolean
        ?: true

    private fun mapArtist(item: JsonObject): MusicArtist {
        val handle = item.optionalString("handle")
        return MusicArtist(
            id = item.string("id"), platform = platform, name = item.string("name"), handle = handle,
            image = mapImage(item.getAsJsonObject("profile_picture")), bio = item.optionalString("bio"),
            website = item.optionalString("website"),
            permalink = item.optionalString("permalink") ?: handle?.let { "https://audius.co/$it" },
            followerCount = item.intOrNull("follower_count"), trackCount = item.intOrNull("track_count"),
            isVerified = item.boolean("is_verified"),
        )
    }

    private fun mapArtistDetails(item: JsonObject) = MusicArtistDetails(
        artist = mapArtist(item),
        coverImage = mapImage(item.getAsJsonObject("cover_photo")),
        location = item.optionalString("location")?.let { MusicArtistLocation(displayName = it) },
        followingCount = item.intOrNull("followee_count"),
        albumCount = item.intOrNull("album_count"),
        playlistCount = item.intOrNull("playlist_count"),
        joinedDate = item.optionalString("created_at"),
        socialLinks = mapSocialLinks(item),
        capabilities = AUDIUS_ARTIST_CAPABILITIES,
    )

    private fun mapSocialLinks(item: JsonObject): List<MusicSocialLink> = buildList {
        item.optionalString("website")?.let { add(MusicSocialLink(MusicSocialPlatform.WEBSITE, it)) }
        socialLink(MusicSocialPlatform.TWITTER, item.optionalString("twitter_handle"), "https://x.com/")?.let(::add)
        socialLink(MusicSocialPlatform.INSTAGRAM, item.optionalString("instagram_handle"), "https://instagram.com/")?.let(::add)
        socialLink(MusicSocialPlatform.TIKTOK, item.optionalString("tiktok_handle"), "https://www.tiktok.com/@")?.let(::add)
    }.distinctBy { it.platform to it.url }

    private fun socialLink(
        platform: MusicSocialPlatform,
        rawHandle: String?,
        baseUrl: String,
    ): MusicSocialLink? {
        val value = rawHandle?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val handle = value.removePrefix("@").substringAfterLast('/').trim()
        val url = value.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: "$baseUrl$handle"
        return MusicSocialLink(platform = platform, url = url, handle = handle.takeIf(String::isNotEmpty))
    }

    private fun mapCollection(item: JsonObject) = MusicCollection(
        id = item.string("id"), platform = platform,
        type = if (item.boolean("is_album")) MusicCollectionType.ALBUM else MusicCollectionType.PLAYLIST,
        title = item.string("playlist_name"), owner = item.getAsJsonObject("user")?.let(::mapArtist),
        artwork = mapImage(item.getAsJsonObject("artwork")), description = item.optionalString("description"),
        trackCount = item.getAsJsonArray("playlist_contents")?.size() ?: item.intOrNull("track_count"),
        releaseDate = item.optionalString("release_date"), permalink = item.optionalString("permalink"),
    )

    override fun health(): ProviderHealth {
        val (available, cooling, disabled) = keys.snapshot()
        return ProviderHealth(platform, available, cooling, disabled, available > 0)
    }

    override fun updateCredentials(credentials: MusicSdkCredentials) {
        keys.replace(credentials.audiusCredentials)
    }

    private companion object {
        val AUDIUS_ARTIST_CAPABILITIES = MusicArtistCapabilities(
            supportedFeatures = setOf(
                MusicArtistFeature.TRACKS,
                MusicArtistFeature.ALBUMS,
                MusicArtistFeature.PLAYLISTS,
                MusicArtistFeature.COVER_IMAGE,
                MusicArtistFeature.BIOGRAPHY,
                MusicArtistFeature.LOCATION,
                MusicArtistFeature.JOIN_DATE,
                MusicArtistFeature.VERIFICATION,
                MusicArtistFeature.FOLLOWER_COUNT,
                MusicArtistFeature.FOLLOWING_COUNT,
                MusicArtistFeature.SOCIAL_LINKS,
            ),
        )
    }
}
