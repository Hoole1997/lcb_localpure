package com.example.lcb.music.internal

import com.example.lcb.music.MusicSdkCredentials
import com.example.lcb.music.model.MusicPlatform
import com.example.lcb.music.model.MusicArtist
import com.example.lcb.music.model.MusicArtistDetails
import com.example.lcb.music.model.MusicCollection
import com.example.lcb.music.model.PageRequest
import com.example.lcb.music.model.MusicSort
import com.example.lcb.music.model.MusicTrack
import com.example.lcb.music.model.TrackQuery
import com.example.lcb.music.model.ProviderHealth

internal interface MusicProvider {
    val platform: MusicPlatform
    suspend fun search(query: String, offset: Int, limit: Int): List<MusicTrack>
    suspend fun trending(offset: Int, limit: Int): List<MusicTrack>
    suspend fun getTrack(id: String): MusicTrack
    suspend fun tracks(query: TrackQuery, page: PageRequest): ProviderPage<MusicTrack>
    suspend fun similarTracks(id: String, page: PageRequest): ProviderPage<MusicTrack>
    suspend fun searchArtists(query: String, page: PageRequest): ProviderPage<MusicArtist>
    suspend fun getArtistDetails(id: String): MusicArtistDetails
    suspend fun getArtist(id: String): MusicArtist = getArtistDetails(id).artist
    suspend fun artistTracks(id: String, page: PageRequest, sort: MusicSort): ProviderPage<MusicTrack>
    suspend fun artistAlbums(id: String, page: PageRequest): ProviderPage<MusicCollection>
    suspend fun artistPlaylists(id: String, page: PageRequest): ProviderPage<MusicCollection>
    suspend fun searchAlbums(query: String, page: PageRequest): ProviderPage<MusicCollection>
    suspend fun getAlbum(id: String): MusicCollection
    suspend fun albumTracks(id: String, page: PageRequest): ProviderPage<MusicTrack>
    suspend fun searchPlaylists(query: String, page: PageRequest): ProviderPage<MusicCollection>
    suspend fun getPlaylist(id: String): MusicCollection
    suspend fun playlistTracks(id: String, page: PageRequest): ProviderPage<MusicTrack>
    fun health(): ProviderHealth
    /** 替换本 Provider 关心的那部分凭据，不重建网络客户端。 */
    fun updateCredentials(credentials: MusicSdkCredentials)
}

internal data class ProviderPage<T>(
    val items: List<T>,
    val totalCount: Int? = null,
    /** 适配器已知上游仍可能有数据时使用，适合过滤后不足一页的响应。 */
    val hasMoreHint: Boolean? = null,
    /** 过滤响应内容时仍按上游实际消费数量推进，防止重复页或空页死循环。 */
    val nextOffsetHint: Int? = null,
) {
    fun toPublic(page: PageRequest): com.example.lcb.music.model.MusicPage<T> {
        val hasMore = hasMoreHint ?: totalCount?.let { page.offset + items.size < it } ?: (items.size >= page.limit)
        val nextOffset = if (!hasMore) {
            null
        } else {
            nextOffsetHint?.takeIf { it > page.offset }
                ?: page.offset + (items.size.takeIf { it > 0 } ?: page.limit)
        }
        return com.example.lcb.music.model.MusicPage(
            items = items,
            offset = page.offset,
            limit = page.limit,
            hasMore = hasMore,
            totalCount = totalCount,
            nextOffset = nextOffset,
        )
    }
}

class MusicSdkException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal class ProviderRequestException(
    val statusCode: Int?,
    val retryAfterMs: Long? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val invalidCredential: Boolean get() = statusCode == 401 || statusCode == 403
    val rateLimited: Boolean get() = statusCode == 429
}
