package com.example.lcb.app.library.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Schema 由 Room Gradle Plugin 导出。升级时提升 [SCHEMA_VERSION] 并声明 AutoMigration，
 * 编译阶段会校验新旧 schema，禁止静默丢失用户歌单。
 */
@Database(
    entities = [
        LibraryTrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        FavoriteTrackEntity::class,
        RecentTrackEntity::class,
    ],
    version = MusicLibraryDatabase.SCHEMA_VERSION,
    exportSchema = true,
    autoMigrations = [
        androidx.room.AutoMigration(from = 1, to = 2),
        androidx.room.AutoMigration(from = 2, to = 3),
        androidx.room.AutoMigration(from = 3, to = 4),
    ],
)
@TypeConverters(LibraryTypeConverters::class)
internal abstract class MusicLibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): MusicLibraryDao

    companion object {
        const val SCHEMA_VERSION = 4
        const val DATABASE_NAME = "music_library.db"
    }
}
