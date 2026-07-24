package com.example.lcb.music.internal

import com.example.lcb.music.MusicSdkCredentials
import com.example.lcb.music.model.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CancellationException

internal class JamendoProvider(
    private val client: OkHttpClient,
    private val keys: KeyPool<String>,
    private val baseUrl: String = "https://api.jamendo.com/v3.0",
) : MusicProvider {
    override val platform = MusicPlatform.JAMENDO

    override suspend fun search(query: String, offset: Int, limit: Int) =
        tracks(TrackQuery(text = query), PageRequest(offset, limit.coerceAtMost(100))).items

    override suspend fun trending(offset: Int, limit: Int) =
        tracks(TrackQuery(sort = MusicSort.POPULAR), PageRequest(offset, limit.coerceAtMost(100))).items

    override suspend fun getTrack(id: String) = trackPage(mapOf("id" to id), PageRequest(limit = 1)).items.firstOrNull()
        ?: throw MusicSdkException("Jamendo track $id was not found")

    override suspend fun tracks(query: TrackQuery, page: PageRequest): ProviderPage<MusicTrack> {
        val parameters = linkedMapOf<String, String>()
        query.text?.takeIf(String::isNotBlank)?.let { parameters["search"] = it }
        query.genre?.takeIf(String::isNotBlank)?.let { parameters["fuzzytags"] = it }
        query.artistId?.let { parameters["artist_id"] = it }
        query.collectionId?.let { parameters["album_id"] = it }
        if (query.minDurationSeconds != null || query.maxDurationSeconds != null) {
            parameters["durationbetween"] = "${query.minDurationSeconds ?: 0}_${query.maxDurationSeconds ?: 86_400}"
        }
        parameters["order"] = when (query.sort) {
            MusicSort.RELEVANCE -> "relevance"
            MusicSort.POPULAR -> "popularity_week"
            MusicSort.LATEST -> "releasedate_desc"
        }
        return trackPage(parameters, page)
    }

    override suspend fun similarTracks(id: String, page: PageRequest): ProviderPage<MusicTrack> =
        trackPage(emptyMap(), page, "tracks/similar", mapOf("id" to id))

    override suspend fun searchArtists(query: String, page: PageRequest): ProviderPage<MusicArtist> {
        val root = request("artists", mapOf("namesearch" to query, "hasimage" to "true"), page)
        return page(root, page) { item -> mapArtist(item) }
    }

    override suspend fun getArtist(id: String): MusicArtist {
        val root = request("artists", mapOf("id" to id), PageRequest(limit = 1))
        return results(root).firstOrNull()?.let(::mapArtist)
            ?: throw MusicSdkException("Jamendo artist $id was not found")
    }

    override suspend fun getArtistDetails(id: String): MusicArtistDetails {
        val root = request("artists/musicinfo", mapOf("id" to id), PageRequest(limit = 1))
        val artistItem = results(root).firstOrNull()
            ?: throw MusicSdkException("Jamendo artist $id was not found")
        // 地区是独立的可选子接口；失败时保留其余详情，但协程取消必须继续向上传递。
        val locationItem = try {
            results(request("artists/locations", mapOf("id" to id), PageRequest(limit = 1))).firstOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        return mapArtistDetails(artistItem, locationItem)
    }

    override suspend fun artistTracks(id: String, page: PageRequest, sort: MusicSort) =
        trackPage(
            mapOf(
                "artist_id" to id,
                "order" to when (sort) {
                    MusicSort.LATEST -> "releasedate_desc"
                    MusicSort.POPULAR, MusicSort.RELEVANCE -> "popularity_total"
                },
            ),
            page,
        )

    override suspend fun artistAlbums(id: String, page: PageRequest): ProviderPage<MusicCollection> {
        val root = request("albums", mapOf("artist_id" to id, "imagesize" to "500"), page)
        return page(root, page, ::mapAlbum)
    }

    override suspend fun artistPlaylists(id: String, page: PageRequest): ProviderPage<MusicCollection> {
        // Jamendo 没有“歌手创建的歌单”关系，能力表会让业务层隐藏该栏目。
        return ProviderPage(items = emptyList(), totalCount = null, hasMoreHint = false)
    }

    override suspend fun searchAlbums(query: String, page: PageRequest): ProviderPage<MusicCollection> {
        val root = request("albums", mapOf("namesearch" to query, "imagesize" to "500"), page)
        return page(root, page) { mapAlbum(it) }
    }

    override suspend fun getAlbum(id: String): MusicCollection {
        val root = request("albums", mapOf("id" to id, "imagesize" to "500"), PageRequest(limit = 1))
        return results(root).firstOrNull()?.let(::mapAlbum) ?: throw MusicSdkException("Jamendo album $id was not found")
    }

    override suspend fun albumTracks(id: String, page: PageRequest) =
        trackPage(mapOf("album_id" to id, "order" to "id_asc"), page)

    override suspend fun searchPlaylists(query: String, page: PageRequest): ProviderPage<MusicCollection> {
        val root = request("playlists", mapOf("namesearch" to query), page)
        return page(root, page) { mapPlaylist(it) }
    }

    override suspend fun getPlaylist(id: String): MusicCollection {
        val root = request("playlists", mapOf("id" to id), PageRequest(limit = 1))
        return results(root).firstOrNull()?.let(::mapPlaylist) ?: throw MusicSdkException("Jamendo playlist $id was not found")
    }

    override suspend fun playlistTracks(id: String, page: PageRequest): ProviderPage<MusicTrack> {
        val firstPosition = page.offset + 1
        val root = request(
            "playlists/tracks",
            mapOf(
                "id" to id,
                "audioformat" to "mp32",
                "imagesize" to "500",
                "track_type" to "single albumtrack",
                "positionbetween" to "${firstPosition}_${firstPosition + page.limit - 1}",
            ),
            PageRequest(limit = 1),
        )
        val tracks = results(root).firstOrNull()?.getAsJsonArray("tracks")?.map { mapTrack(it.asJsonObject) }.orEmpty()
        return ProviderPage(tracks)
    }

    private suspend fun trackPage(
        parameters: Map<String, String>,
        page: PageRequest,
        endpoint: String = "tracks",
        required: Map<String, String> = emptyMap(),
    ): ProviderPage<MusicTrack> {
        // type=single albumtrack 与 popularity_week 组合时 Jamendo 会返回空列表；曲目接口本身已提供可播放 audio。
        val defaults = mapOf("audioformat" to "mp32", "imagesize" to "500", "include" to "lyrics")
        val root = request(endpoint, defaults + parameters + required, page)
        return page(root, page, ::mapTrack)
    }

    private suspend fun request(endpoint: String, parameters: Map<String, String>, page: PageRequest): JsonObject =
        withKeyFailover(keys) { clientId ->
            val url = "$baseUrl/$endpoint/".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("format", "json")
                .addQueryParameter("fullcount", "true")
                .addQueryParameter("offset", page.offset.toString())
                .addQueryParameter("limit", page.limit.toString())
                .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
            val root = JsonParser.parseString(client.getJson(Request.Builder().url(url).build())).asJsonObject
            val headers = root.getAsJsonObject("headers")
            if (headers?.string("status") != "success") {
                val jamendoCode = headers?.get("code")?.takeUnless { it.isJsonNull }?.asInt
                throw ProviderRequestException(
                    statusCode = jamendoCode.toFailoverStatus(),
                    message = headers?.string("error_message") ?: "Jamendo request failed",
                )
            }
            root
        }

    /**
     * Jamendo 大多数业务错误仍返回 HTTP 200，需把响应体错误码映射到通用轮换语义。
     * 5=无效 client id，6=超额，11=应用被停用，1=平台通用异常；其他错误视为参数/业务错误。
     */
    private fun Int?.toFailoverStatus(): Int = when (this) {
        5 -> 401
        6 -> 429
        11 -> 403
        1, null -> 500
        else -> 400
    }

    private fun <T> page(root: JsonObject, request: PageRequest, mapper: (JsonObject) -> T): ProviderPage<T> {
        val total = root.getAsJsonObject("headers")?.get("results_fullcount")?.takeUnless { it.isJsonNull }?.asInt
        return ProviderPage(results(root).map(mapper), total)
    }

    private fun results(root: JsonObject) = root.getAsJsonArray("results")?.map { it.asJsonObject }.orEmpty()

    private fun mapTrack(item: JsonObject): MusicTrack {
        val artworkUrl = item.optionalString("image")
        val artistId = item.optionalString("artist_id")
        val artistName = item.string("artist_name")
        return MusicTrack(
            id = item.string("id"), platform = platform, title = item.string("name"),
            artistName = artistName, artworkUrl = artworkUrl,
            artwork = artworkUrl?.let(::mapTrackArtwork),
            durationMs = item.long("duration") * 1_000, streamUrl = item.string("audio"),
            albumName = item.optionalString("album_name"), permalink = item.optionalString("shareurl"),
            lyrics = item.optionalString("lyrics"), description = item.optionalString("description"),
            artist = artistId?.let { MusicArtistRef(it, platform, artistName) },
        )
    }

    /** Jamendo 图片 URL 支持 width 参数，保留原图给播放页，同时为列表生成 150px 缩略图。 */
    private fun mapTrackArtwork(url: String): MusicImage {
        val thumbnailUrl = url.toHttpUrlOrNull()?.newBuilder()
            ?.setQueryParameter("width", HOME_THUMBNAIL_WIDTH.toString())
            ?.build()
            ?.toString()
        return MusicImage(smallUrl = thumbnailUrl, largeUrl = url)
    }

    private fun mapArtist(item: JsonObject) = MusicArtist(
        id = item.string("id"), platform = platform, name = item.string("name"),
        image = item.optionalString("image")?.let { MusicImage(largeUrl = it) },
        bio = mapArtistBio(item),
        website = item.optionalString("website"), permalink = item.optionalString("shareurl"),
    )

    private fun mapArtistDetails(artistItem: JsonObject, locationItem: JsonObject?) = MusicArtistDetails(
        artist = mapArtist(artistItem),
        location = mapArtistLocation(locationItem),
        tags = artistItem.getAsJsonObject("musicinfo")?.stringList("tags").orEmpty().distinct(),
        joinedDate = artistItem.optionalString("joindate"),
        socialLinks = artistItem.optionalString("website")
            ?.let { listOf(MusicSocialLink(MusicSocialPlatform.WEBSITE, it)) }
            .orEmpty(),
        capabilities = JAMENDO_ARTIST_CAPABILITIES,
    )

    private fun mapArtistBio(item: JsonObject): String? {
        val descriptions = item.getAsJsonObject("musicinfo")?.getAsJsonObject("description") ?: return null
        val html = descriptions.optionalString("en")
            ?: descriptions.entrySet().firstNotNullOfOrNull { (_, value) ->
                value.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
            }
        return html?.let(::htmlToPlainText)?.takeIf(String::isNotBlank)
    }

    private fun mapArtistLocation(item: JsonObject?): MusicArtistLocation? {
        val location = item?.getAsJsonArray("locations")?.firstOrNull()?.asJsonObject ?: return null
        val city = location.optionalString("city")
        val country = location.optionalString("country")
        val displayName = listOfNotNull(city, country).joinToString(", ").takeIf(String::isNotBlank) ?: return null
        return MusicArtistLocation(displayName = displayName, city = city, countryCode = country)
    }

    /** Jamendo 简介是 HTML；SDK 统一输出可直接展示的纯文本，避免业务层依赖平台格式。 */
    private fun htmlToPlainText(html: String): String = html
        .replace(HTML_LINE_BREAK, "\n")
        .replace(HTML_PARAGRAPH_END, "\n")
        .replace(HTML_TAG, "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .lines()
        .joinToString("\n") { it.trim() }
        .trim()

    private fun mapAlbum(item: JsonObject) = MusicCollection(
        id = item.string("id"), platform = platform, type = MusicCollectionType.ALBUM,
        title = item.string("name"), owner = MusicArtist(item.string("artist_id"), platform, item.string("artist_name")),
        artwork = item.optionalString("image")?.let { MusicImage(largeUrl = it) },
        releaseDate = item.optionalString("releasedate"), permalink = item.optionalString("shareurl"),
    )

    private fun mapPlaylist(item: JsonObject) = MusicCollection(
        id = item.string("id"), platform = platform, type = MusicCollectionType.PLAYLIST,
        title = item.string("name"), artwork = item.optionalString("image")?.let { MusicImage(largeUrl = it) },
        trackCount = item.intOrNull("tracks_count"), permalink = item.optionalString("shareurl"),
    )

    override fun health(): ProviderHealth {
        val (available, cooling, disabled) = keys.snapshot()
        return ProviderHealth(platform, available, cooling, disabled, available > 0)
    }

    override fun updateCredentials(credentials: MusicSdkCredentials) {
        keys.replace(credentials.jamendoClientIds)
    }

    private companion object {
        const val HOME_THUMBNAIL_WIDTH = 150
        val HTML_LINE_BREAK = Regex("(?i)<br\\s*/?>")
        val HTML_PARAGRAPH_END = Regex("(?i)</p\\s*>")
        val HTML_TAG = Regex("<[^>]+>")
        val JAMENDO_ARTIST_CAPABILITIES = MusicArtistCapabilities(
            supportedFeatures = setOf(
                MusicArtistFeature.TRACKS,
                MusicArtistFeature.ALBUMS,
                MusicArtistFeature.BIOGRAPHY,
                MusicArtistFeature.LOCATION,
                MusicArtistFeature.TAGS,
                MusicArtistFeature.JOIN_DATE,
                MusicArtistFeature.SOCIAL_LINKS,
            ),
        )
    }
}

internal fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
internal fun JsonObject.optionalString(name: String): String? = string(name).takeIf(String::isNotBlank)
internal fun JsonObject.long(name: String): Long = get(name)?.takeUnless { it.isJsonNull }?.asLong ?: 0
internal fun JsonObject.intOrNull(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt
internal fun JsonObject.boolean(name: String): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
internal fun JsonObject.stringList(name: String): List<String> = getAsJsonArray(name)?.mapNotNull { it.takeUnless { value -> value.isJsonNull }?.asString }.orEmpty()
