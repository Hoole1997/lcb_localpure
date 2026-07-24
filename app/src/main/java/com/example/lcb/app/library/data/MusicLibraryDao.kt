package com.example.lcb.app.library.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class MusicLibraryDao {
    @Query(
        """
        SELECT playlists.id, playlists.name,
               COUNT(playlist_tracks.trackId) AS trackCount,
               (
                   SELECT library_tracks.artworkUrl
                   FROM playlist_tracks AS preview_relation
                   INNER JOIN library_tracks ON library_tracks.id = preview_relation.trackId
                   WHERE preview_relation.playlistId = playlists.id
                   ORDER BY preview_relation.addedAt DESC
                   LIMIT 1
               ) AS artworkUrl,
               (
                   SELECT library_tracks.artworkThumbnailUrls
                   FROM playlist_tracks AS preview_relation
                   INNER JOIN library_tracks ON library_tracks.id = preview_relation.trackId
                   WHERE preview_relation.playlistId = playlists.id
                   ORDER BY preview_relation.addedAt DESC
                   LIMIT 1
               ) AS artworkThumbnailUrls
        FROM playlists
        LEFT JOIN playlist_tracks ON playlist_tracks.playlistId = playlists.id
        GROUP BY playlists.id
        ORDER BY playlists.updatedAt DESC, playlists.createdAt DESC
        """,
    )
    abstract fun observePlaylists(): Flow<List<PlaylistSummaryRow>>

    @Query("SELECT name FROM playlists WHERE id = :playlistId")
    abstract fun observePlaylistName(playlistId: Long): Flow<String?>

    @Query(
        """
        SELECT library_tracks.*
        FROM playlist_tracks
        INNER JOIN library_tracks ON library_tracks.id = playlist_tracks.trackId
        WHERE playlist_tracks.playlistId = :playlistId
        ORDER BY playlist_tracks.addedAt DESC
        """,
    )
    abstract fun observePlaylistTracks(playlistId: Long): Flow<List<LibraryTrackEntity>>

    @Query(
        """
        SELECT library_tracks.*
        FROM favorite_tracks
        INNER JOIN library_tracks ON library_tracks.id = favorite_tracks.trackId
        ORDER BY favorite_tracks.favoritedAt DESC
        """,
    )
    abstract fun observeFavoriteTracks(): Flow<List<LibraryTrackEntity>>

    @Query(
        """
        SELECT library_tracks.*
        FROM recent_tracks
        INNER JOIN library_tracks ON library_tracks.id = recent_tracks.trackId
        ORDER BY recent_tracks.playedAt DESC, recent_tracks.trackId DESC
        LIMIT :limit
        """,
    )
    abstract fun observeRecentlyPlayed(limit: Int): Flow<List<LibraryTrackEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_tracks WHERE trackId = :trackId)")
    abstract fun observeFavorite(trackId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_tracks WHERE trackId = :trackId)")
    abstract suspend fun isFavorite(trackId: String): Boolean

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Upsert
    abstract suspend fun upsertTrack(track: LibraryTrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPlaylistTrack(relation: PlaylistTrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFavorite(favorite: FavoriteTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertRecentTrack(recentTrack: RecentTrackEntity)

    @Query("DELETE FROM favorite_tracks WHERE trackId = :trackId")
    abstract suspend fun deleteFavorite(trackId: String): Int

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId IN (:trackIds)")
    abstract suspend fun deletePlaylistTracks(playlistId: Long, trackIds: Set<String>): Int

    @Query("DELETE FROM favorite_tracks WHERE trackId IN (:trackIds)")
    abstract suspend fun deleteFavorites(trackIds: Set<String>): Int

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    abstract suspend fun deletePlaylist(playlistId: Long): Int

    @Query("UPDATE playlists SET updatedAt = :updatedAt WHERE id = :playlistId")
    abstract suspend fun updatePlaylistTimestamp(playlistId: Long, updatedAt: Long)

    @Query(
        """
        DELETE FROM recent_tracks
        WHERE trackId IN (
            SELECT trackId
            FROM recent_tracks
            ORDER BY playedAt DESC, trackId DESC
            LIMIT -1 OFFSET :limit
        )
        """,
    )
    abstract suspend fun trimRecentTracks(limit: Int)

    @Transaction
    open suspend fun addTrackToPlaylist(
        playlistId: Long,
        track: LibraryTrackEntity,
        addedAt: Long,
    ): Boolean {
        upsertTrack(track)
        val inserted = insertPlaylistTrack(PlaylistTrackEntity(playlistId, track.id, addedAt)) != -1L
        if (inserted) updatePlaylistTimestamp(playlistId, addedAt)
        return inserted
    }

    @Transaction
    open suspend fun setFavorite(
        track: LibraryTrackEntity,
        favorite: Boolean,
        changedAt: Long,
    ) {
        if (favorite) {
            upsertTrack(track)
            insertFavorite(FavoriteTrackEntity(track.id, changedAt))
        } else {
            deleteFavorite(track.id)
        }
    }

    /** upsert 会让重复歌曲移动到顶部，随后在同一事务内裁剪，数据库中始终不超过上限。 */
    @Transaction
    open suspend fun recordRecentlyPlayed(
        track: LibraryTrackEntity,
        playedAt: Long,
        limit: Int,
    ) {
        upsertTrack(track)
        insertRecentTrack(RecentTrackEntity(track.id, playedAt))
        trimRecentTracks(limit)
    }
}
