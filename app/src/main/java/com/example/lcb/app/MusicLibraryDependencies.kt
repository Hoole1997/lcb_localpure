package com.example.lcb.app

import android.content.Context
import androidx.room.Room
import com.example.lcb.app.library.MusicLibraryRepository
import com.example.lcb.app.library.RoomMusicLibraryRepository
import com.example.lcb.app.library.data.MusicLibraryDatabase

/** 应用级 Room 单例，所有页面共享同一失效追踪器和数据库线程池。 */
internal object MusicLibraryDependencies {
    @Volatile
    private var database: MusicLibraryDatabase? = null

    @Volatile
    private var repository: MusicLibraryRepository? = null

    fun repository(context: Context): MusicLibraryRepository = repository ?: synchronized(this) {
        repository ?: RoomMusicLibraryRepository(database(context).libraryDao()).also { repository = it }
    }

    private fun database(context: Context): MusicLibraryDatabase = database ?: synchronized(this) {
        database ?: Room.databaseBuilder(
            context.applicationContext,
            MusicLibraryDatabase::class.java,
            MusicLibraryDatabase.DATABASE_NAME,
        ).build().also { database = it }
    }
}
