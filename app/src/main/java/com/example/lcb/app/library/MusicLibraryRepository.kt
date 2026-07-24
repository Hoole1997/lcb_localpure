package com.example.lcb.app.library

import android.database.sqlite.SQLiteConstraintException
import com.example.lcb.app.library.data.LibraryTrackEntity
import com.example.lcb.app.library.data.MusicLibraryDao
import com.example.lcb.app.library.data.PlaylistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.example.lcb.music.model.MusicArtistRef
import com.example.lcb.music.model.MusicPlatform

interface MusicLibraryRepository {
    fun observePlaylists(): Flow<List<PlaylistSummary>>
    fun observeRecentlyPlayed(): Flow<List<LibraryTrack>>
    fun observePlaylistName(playlistId: Long): Flow<String?>
    fun observeTracks(collection: LibraryCollection): Flow<List<LibraryTrack>>
    fun observeFavorite(trackId: String): Flow<Boolean>
    suspend fun isFavorite(trackId: String): Boolean
    suspend fun createPlaylist(name: String): Result<Long>
    suspend fun addTrackToPlaylist(playlistId: Long, track: LibraryTrack): AddTrackResult
    suspend fun recordRecentlyPlayed(track: LibraryTrack)
    suspend fun setFavorite(track: LibraryTrack, favorite: Boolean)
    suspend fun removeTracks(collection: LibraryCollection, trackIds: Set<String>): Int
    suspend fun deletePlaylist(playlistId: Long): Boolean
}

internal class RoomMusicLibraryRepository(
    private val dao: MusicLibraryDao,
    private val recentTrackLimit: Int = DEFAULT_RECENT_TRACK_LIMIT,
    private val now: () -> Long = System::currentTimeMillis,
) : MusicLibraryRepository {
    init {
        require(recentTrackLimit > 0) { "Recent track limit must be positive" }
    }

    override fun observePlaylists(): Flow<List<PlaylistSummary>> = dao.observePlaylists().map { rows ->
        rows.map { row ->
            PlaylistSummary(
                id = row.id,
                name = row.name,
                trackCount = row.trackCount,
                artworkUrl = row.artworkUrl,
                artworkThumbnailUrls = row.artworkThumbnailUrls.orEmpty(),
            )
        }
    }

    override fun observePlaylistName(playlistId: Long): Flow<String?> = dao.observePlaylistName(playlistId)

    override fun observeRecentlyPlayed(): Flow<List<LibraryTrack>> =
        dao.observeRecentlyPlayed(recentTrackLimit).map { tracks -> tracks.map(LibraryTrackEntity::toDomain) }

    override fun observeTracks(collection: LibraryCollection): Flow<List<LibraryTrack>> = when (collection) {
        LibraryCollection.Favorites -> dao.observeFavoriteTracks()
        is LibraryCollection.Playlist -> dao.observePlaylistTracks(collection.id)
    }.map { tracks -> tracks.map(LibraryTrackEntity::toDomain) }

    override fun observeFavorite(trackId: String): Flow<Boolean> = dao.observeFavorite(trackId)

    override suspend fun isFavorite(trackId: String): Boolean = dao.isFavorite(trackId)

    override suspend fun createPlaylist(name: String): Result<Long> {
        val normalized = name.trim()
        if (normalized.isEmpty()) return Result.failure(IllegalArgumentException("Playlist name is required"))
        return runCatching {
            val timestamp = now()
            dao.insertPlaylist(
                PlaylistEntity(name = normalized, createdAt = timestamp, updatedAt = timestamp),
            )
        }.recoverCatching { error ->
            if (error is SQLiteConstraintException) {
                throw IllegalArgumentException("A playlist with this name already exists")
            }
            throw error
        }
    }

    override suspend fun addTrackToPlaylist(playlistId: Long, track: LibraryTrack): AddTrackResult {
        return if (dao.addTrackToPlaylist(playlistId, track.toEntity(), now())) {
            AddTrackResult.ADDED
        } else {
            AddTrackResult.ALREADY_PRESENT
        }
    }

    override suspend fun recordRecentlyPlayed(track: LibraryTrack) {
        dao.recordRecentlyPlayed(track.toEntity(), now(), recentTrackLimit)
    }

    override suspend fun setFavorite(track: LibraryTrack, favorite: Boolean) {
        dao.setFavorite(track.toEntity(), favorite, now())
    }

    override suspend fun removeTracks(collection: LibraryCollection, trackIds: Set<String>): Int {
        if (trackIds.isEmpty()) return 0
        return when (collection) {
            LibraryCollection.Favorites -> dao.deleteFavorites(trackIds)
            is LibraryCollection.Playlist -> dao.deletePlaylistTracks(collection.id, trackIds)
        }
    }

    override suspend fun deletePlaylist(playlistId: Long): Boolean = dao.deletePlaylist(playlistId) > 0

    private companion object {
        const val DEFAULT_RECENT_TRACK_LIMIT = 50
    }
}

private fun LibraryTrackEntity.toDomain() = LibraryTrack(
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
    artistRef = artistId?.takeIf(String::isNotBlank)?.let { id ->
        artistPlatform
            ?.let { raw -> runCatching { MusicPlatform.valueOf(raw) }.getOrNull() }
            ?.let { platform -> MusicArtistRef(id, platform, artist) }
    },
)

private fun LibraryTrack.toEntity() = LibraryTrackEntity(
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
    artistId = artistRef?.id,
    artistPlatform = artistRef?.platform?.name,
)
