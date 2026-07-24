package com.example.lcb.app.localmusic

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.example.lcb.app.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface LocalMusicRepository {
    fun observeTracks(): Flow<List<LocalMusicTrack>>
}

/**
 * 仅通过 MediaStore 查询公开音频元数据。ContentObserver 负责响应系统媒体扫描结果，页面无需轮询。
 */
class MediaStoreLocalMusicRepository private constructor(
    private val resolver: ContentResolver,
    private val unknownTitle: String,
    private val unknownArtist: String,
) : LocalMusicRepository {
    constructor(context: Context) : this(
        resolver = context.applicationContext.contentResolver,
        unknownTitle = context.getString(R.string.local_music_unknown_title),
        unknownArtist = context.getString(R.string.local_music_unknown_artist),
    )

    override fun observeTracks(): Flow<List<LocalMusicTrack>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(audioCollectionUri(), true, observer)
        }
        if (registered.isFailure) {
            close(registered.exceptionOrNull())
            return@callbackFlow
        }
        trySend(Unit)
        awaitClose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
        .conflate()
        .map { queryTracks() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    private fun queryTracks(): List<LocalMusicTrack> {
        val collection = audioCollectionUri()
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            pathColumn,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val cursor = resolver.query(collection, projection, selection, null, sortOrder) ?: return emptyList()
        return cursor.use { rows ->
            val idIndex = rows.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val displayNameIndex = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleIndex = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIndex = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIndex = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdIndex = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationIndex = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathIndex = rows.getColumnIndexOrThrow(pathColumn)
            buildList(rows.count.coerceAtLeast(0)) {
                while (rows.moveToNext()) {
                    val id = rows.getLong(idIndex)
                    val displayName = rows.getString(displayNameIndex).orEmpty()
                    val title = rows.getString(titleIndex).normalizedMediaText()
                        ?: displayName.substringBeforeLast('.').takeIf(String::isNotBlank)
                        ?: unknownTitle
                    val artist = rows.getString(artistIndex).normalizedMediaText() ?: unknownArtist
                    val album = rows.getString(albumIndex).normalizedMediaText()
                    val albumId = rows.getLong(albumIdIndex)
                    val artwork = albumId.takeIf { it > 0L }?.let { value ->
                        ContentUris.withAppendedId(ALBUM_ART_URI, value).toString()
                    }
                    add(
                        LocalMusicTrack(
                            mediaStoreId = id,
                            title = title,
                            artist = artist,
                            album = album,
                            folderName = folderName(rows.getString(pathIndex)),
                            artworkUrl = artwork,
                            contentUri = ContentUris.withAppendedId(collection, id).toString(),
                            durationMs = rows.getLong(durationIndex).coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }
    }

    private fun folderName(rawPath: String?): String? {
        val path = rawPath?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            path.trim('/').substringAfterLast('/').takeIf(String::isNotBlank)
        } else {
            File(path).parentFile?.name?.takeIf(String::isNotBlank)
        }
    }

    private fun String?.normalizedMediaText(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING }

    private fun audioCollectionUri(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private companion object {
        val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}
