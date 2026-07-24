package com.example.lcb.music.internal

import com.example.lcb.music.MusicSdk
import com.example.lcb.music.MusicSdkCredentials
import com.example.lcb.music.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import java.text.Normalizer
import java.util.Locale

internal class AggregatingMusicSdk(private val providers: List<MusicProvider>) : MusicSdk {
    override suspend fun tracks(query: TrackQuery, page: PageRequest): MusicPage<MusicTrack> {
        query.platform?.let { platform -> return provider(platform).tracks(query, page).toPublic(page) }
        return aggregate(page, ::trackContentIdentity) { source, providerPage -> source.tracks(query, providerPage) }
    }

    override suspend fun searchTracks(query: String, offset: Int, limit: Int): MusicPage<MusicTrack> {
        require(query.isNotBlank()) { "query cannot be blank" }
        return aggregate(offset, limit) { provider, providerLimit -> provider.search(query.trim(), 0, providerLimit) }
    }

    override suspend fun trendingTracks(offset: Int, limit: Int): MusicPage<MusicTrack> =
        aggregate(offset, limit) { provider, providerLimit -> provider.trending(0, providerLimit) }

    override suspend fun getTrack(platform: MusicPlatform, id: String): MusicTrack {
        require(id.isNotBlank()) { "id cannot be blank" }
        return provider(platform).getTrack(id)
    }

    override suspend fun similarTracks(platform: MusicPlatform, id: String, page: PageRequest) =
        provider(platform).similarTracks(id, page).toPublic(page)

    override suspend fun searchArtists(query: String, page: PageRequest) =
        aggregate(page, { "${it.platform}:${it.id}" }) { source, providerPage -> source.searchArtists(requiredQuery(query), providerPage) }

    override suspend fun getArtist(platform: MusicPlatform, id: String) = provider(platform).getArtist(requiredId(id, "artistId"))
    override suspend fun getArtistDetails(platform: MusicPlatform, id: String) =
        provider(platform).getArtistDetails(requiredId(id, "artistId"))
    override suspend fun artistTracks(platform: MusicPlatform, artistId: String, page: PageRequest, sort: MusicSort) =
        provider(platform).artistTracks(requiredId(artistId, "artistId"), page, sort).toPublic(page)
    override suspend fun artistAlbums(platform: MusicPlatform, artistId: String, page: PageRequest) =
        provider(platform).artistAlbums(requiredId(artistId, "artistId"), page).toPublic(page)
    override suspend fun artistPlaylists(platform: MusicPlatform, artistId: String, page: PageRequest) =
        provider(platform).artistPlaylists(requiredId(artistId, "artistId"), page).toPublic(page)

    override suspend fun searchAlbums(query: String, page: PageRequest) =
        aggregate(page, { "${it.platform}:${it.id}" }) { source, providerPage -> source.searchAlbums(requiredQuery(query), providerPage) }

    override suspend fun getAlbum(platform: MusicPlatform, id: String) = provider(platform).getAlbum(id)
    override suspend fun albumTracks(platform: MusicPlatform, albumId: String, page: PageRequest) =
        provider(platform).albumTracks(albumId, page).toPublic(page)

    override suspend fun searchPlaylists(query: String, page: PageRequest) =
        aggregate(page, { "${it.platform}:${it.id}" }) { source, providerPage -> source.searchPlaylists(requiredQuery(query), providerPage) }

    override suspend fun getPlaylist(platform: MusicPlatform, id: String) = provider(platform).getPlaylist(id)
    override suspend fun playlistTracks(platform: MusicPlatform, playlistId: String, page: PageRequest) =
        provider(platform).playlistTracks(playlistId, page).toPublic(page)

    override fun health(): List<ProviderHealth> = providers.map(MusicProvider::health)

    override fun updateCredentials(credentials: MusicSdkCredentials) {
        // Provider 内部用同一把锁替换各自凭据池，顺序更新不会阻塞已发出的网络请求。
        providers.forEach { it.updateCredentials(credentials) }
    }

    override suspend fun checkHealth(): List<ProviderHealth> {
        supervisorScope {
            providers.map { provider -> async { runCatching { provider.trending(0, 1) } } }.awaitAll()
        }
        return health()
    }

    private suspend fun aggregate(
        offset: Int,
        limit: Int,
        load: suspend (MusicProvider, Int) -> List<MusicTrack>,
    ): MusicPage<MusicTrack> {
        require(offset >= 0) { "offset cannot be negative" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
        // 平台可能返回不同 ID 的重复音频，适度超量拉取后再去重，避免请求 8 条最终只剩 7 条。
        val requestedPerProvider = providerFetchLimit(offset, limit)
        val results = supervisorScope {
            providers.map { provider -> async { runCatching { load(provider, requestedPerProvider) } } }.awaitAll()
        }
        val successes = results.mapNotNull(Result<List<MusicTrack>>::getOrNull)
        if (successes.isEmpty()) {
            throw MusicSdkException("Every music provider failed", results.firstNotNullOfOrNull { it.exceptionOrNull() })
        }
        val merged = deduplicateTracks(interleave(successes))
        val page = merged.drop(offset).take(limit)
        return MusicPage(page, offset, limit, merged.size > offset + page.size)
    }

    private suspend fun <T> aggregate(
        page: PageRequest,
        identity: (T) -> String,
        load: suspend (MusicProvider, PageRequest) -> ProviderPage<T>,
    ): MusicPage<T> {
        val providerPage = PageRequest(limit = providerFetchLimit(page.offset, page.limit))
        val results = supervisorScope {
            providers.map { source -> async { runCatching { load(source, providerPage) } } }.awaitAll()
        }
        val pages = results.mapNotNull { it.getOrNull() }
        if (pages.isEmpty()) throw MusicSdkException("Every music provider failed", results.firstNotNullOfOrNull { it.exceptionOrNull() })
        val successes = pages.map { it.items }
        val merged = interleaveGeneric(successes).distinctBy(identity)
        val items = merged.drop(page.offset).take(page.limit)
        val hasMore = merged.size > page.offset + items.size || pages.any { it.hasMoreHint == true || it.items.size >= providerPage.limit }
        return MusicPage(items, page.offset, page.limit, hasMore)
    }

    private fun <T> interleaveGeneric(groups: List<List<T>>): List<T> = buildList {
        val maxSize = groups.maxOfOrNull(List<T>::size) ?: 0
        repeat(maxSize) { index -> groups.forEach { group -> group.getOrNull(index)?.let(::add) } }
    }

    private fun provider(platform: MusicPlatform) = providers.firstOrNull { it.platform == platform }
        ?: throw MusicSdkException("$platform is not configured")

    private fun requiredQuery(query: String) = query.trim().also { require(it.isNotEmpty()) { "query cannot be blank" } }
    private fun requiredId(id: String, label: String) = id.trim().also { require(it.isNotEmpty()) { "$label cannot be blank" } }

    /** 轮流取各平台结果，避免某个平台完全占据首屏。 */
    private fun interleave(groups: List<List<MusicTrack>>): List<MusicTrack> = buildList {
        val maxSize = groups.maxOfOrNull(List<MusicTrack>::size) ?: 0
        repeat(maxSize) { index -> groups.forEach { group -> group.getOrNull(index)?.let(::add) } }
    }
}

/**
 * 平台可能为同一音频生成多个 ID，并在复制标题后追加 `(1)`。内容指纹只清理这类技术噪声，
 * 不删除 live、remix 等版本信息，因此真实的不同版本仍会保留。
 */
internal fun trackContentIdentity(track: MusicTrack): String {
    val normalizedTitle = normalizeTrackTitle(track.title)
    val normalizedArtist = normalizeTrackText(track.artistName)
    return if (normalizedTitle.isNotEmpty() && normalizedArtist.isNotEmpty()) {
        "$normalizedArtist|$normalizedTitle"
    } else {
        "${track.platform}:${track.id}"
    }
}

internal fun deduplicateTracks(tracks: List<MusicTrack>): List<MusicTrack> {
    val seenIds = hashSetOf<String>()
    val seenContent = hashSetOf<String>()
    return tracks.filter { track ->
        seenIds.add("${track.platform}:${track.id}") && seenContent.add(trackContentIdentity(track))
    }
}

private fun normalizeTrackText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(SEPARATOR_RUN, " ")
    .trim()

private fun normalizeTrackTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(COPY_SUFFIX, "")
    .replace(SEPARATOR_RUN, " ")
    .trim()

private val COPY_SUFFIX = Regex("\\s*\\(\\s*\\d+\\s*\\)$")
private val SEPARATOR_RUN = Regex("[\\p{P}\\p{S}\\s]+")

private fun providerFetchLimit(offset: Int, limit: Int): Int =
    ((offset.toLong() + limit) * DEDUPLICATION_FETCH_FACTOR)
        .coerceAtMost(MAX_PROVIDER_PAGE_SIZE.toLong())
        .toInt()

private const val DEDUPLICATION_FETCH_FACTOR = 2
private const val MAX_PROVIDER_PAGE_SIZE = 100
