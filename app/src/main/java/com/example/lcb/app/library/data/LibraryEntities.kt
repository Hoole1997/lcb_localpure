package com.example.lcb.app.library.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 在线歌曲的可恢复播放元数据只存一份，歌单和收藏通过关系表引用。 */
@Entity(tableName = "library_tracks")
internal data class LibraryTrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val artworkThumbnailUrls: List<String>,
    val artworkFallbackRes: Int,
    val streamUrl: String,
    val durationMs: Long,
    val lyrics: String?,
    val description: String?,
    /** v3 新增可空字段，旧歌单数据可通过 Room AutoMigration 无损保留。 */
    val artistId: String?,
    val artistPlatform: String?,
)

@Entity(
    tableName = "playlists",
    indices = [Index(value = ["name"], unique = true)],
)
internal data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val createdAt: Long,
    /** v2 新增字段带默认值，可由 Room AutoMigration 从 v1 无损升级。 */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LibraryTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId")],
)
internal data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackId: String,
    val addedAt: Long,
)

@Entity(
    tableName = "favorite_tracks",
    foreignKeys = [
        ForeignKey(
            entity = LibraryTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class FavoriteTrackEntity(
    @PrimaryKey val trackId: String,
    val favoritedAt: Long,
)

/**
 * 最近播放只保存歌曲引用与最后播放时间；完整元数据复用 library_tracks，避免三处数据副本漂移。
 */
@Entity(
    tableName = "recent_tracks",
    foreignKeys = [
        ForeignKey(
            entity = LibraryTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playedAt")],
)
internal data class RecentTrackEntity(
    @PrimaryKey val trackId: String,
    val playedAt: Long,
)

/** 首页和歌单选择器只读取摘要，避免加载完整歌曲列表。 */
internal data class PlaylistSummaryRow(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val artworkUrl: String?,
    val artworkThumbnailUrls: List<String>?,
)
