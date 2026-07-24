package com.example.lcb.app.trackactions

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.net.URI

/** 下载结果保持平台无关，弹框 Controller 只负责将结果转换为用户提示。 */
internal enum class TrackDownloadResult {
    Enqueued,
    AlreadyQueued,
    Unavailable,
    Failed,
}

internal data class TrackDownloadSpec(
    val sourceUrl: String,
    val fileName: String,
)

/**
 * 把业务模型转换为系统下载参数。该部分不依赖 Android 组件，便于单元测试 URL 与文件名边界。
 */
internal object TrackDownloadSpecFactory {
    private val invalidFileNameCharacters = Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]")
    private val repeatedWhitespace = Regex("\\s+")
    private val repeatedUnderscores = Regex("_+")

    fun create(track: TrackActionUiModel): TrackDownloadSpec? {
        val sourceUrl = track.streamUrl.trim()
        val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in SUPPORTED_SCHEMES || uri.host.isNullOrBlank()) return null

        val readableName = listOf(track.artist, track.title)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" - ")
            .sanitizeFileName()
            .ifBlank { DEFAULT_FILE_NAME }
            .take(MAX_FILE_NAME_BASE_LENGTH)
            .trim(' ', '.')
            .ifBlank { DEFAULT_FILE_NAME }
        // 同名歌曲仍可能来自不同平台；稳定短后缀可防止系统因目标文件已存在而直接失败。
        val stableSuffix = track.id.hashCode().toUInt().toString(16).padStart(8, '0')
        return TrackDownloadSpec(sourceUrl, "$readableName-$stableSuffix.$DEFAULT_EXTENSION")
    }

    private fun String.sanitizeFileName(): String = replace(invalidFileNameCharacters, "_")
        .replace(repeatedUnderscores, "_")
        .replace(repeatedWhitespace, " ")
        .trim(' ', '.')

    private const val DEFAULT_FILE_NAME = "Music"
    private const val DEFAULT_EXTENSION = "mp3"
    private const val MAX_FILE_NAME_BASE_LENGTH = 96
    private val SUPPORTED_SCHEMES = setOf("http", "https")
}

/**
 * 使用应用 Context 调用系统 DownloadManager，避免下载任务或系统服务持有 Activity。
 * Android 10+ 保存到公共 Music/LocalPure；旧系统保存到应用专属 Music 目录以免申请存储权限。
 */
internal class SystemTrackDownloader(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun enqueue(track: TrackActionUiModel): TrackDownloadResult {
        val spec = TrackDownloadSpecFactory.create(track) ?: return TrackDownloadResult.Unavailable
        val manager = downloadManager ?: return TrackDownloadResult.Failed
        return synchronized(downloadLock) {
            val preferenceKey = DOWNLOAD_ID_PREFIX + track.id
            val existingId = preferences.getLong(preferenceKey, INVALID_DOWNLOAD_ID)
            if (existingId != INVALID_DOWNLOAD_ID && manager.isPendingRunningOrComplete(existingId)) {
                return@synchronized TrackDownloadResult.AlreadyQueued
            }

            runCatching {
                val request = DownloadManager.Request(Uri.parse(spec.sourceUrl))
                    .setTitle(track.title.ifBlank { spec.fileName })
                    .setDescription(track.artist)
                    .setMimeType(AUDIO_MIME_TYPE)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .apply { configureDestination(spec.fileName) }
                val downloadId = manager.enqueue(request)
                preferences.edit().putLong(preferenceKey, downloadId).apply()
                TrackDownloadResult.Enqueued
            }.getOrElse {
                preferences.edit().remove(preferenceKey).apply()
                TrackDownloadResult.Failed
            }
        }
    }

    private fun DownloadManager.Request.configureDestination(fileName: String) {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "$PUBLIC_DIRECTORY/$fileName")
        } else {
            // minSdk 26~28 使用应用专属目录，无需旧式 WRITE_EXTERNAL_STORAGE 运行时权限。
            setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_MUSIC, fileName)
        }
    }

    private fun DownloadManager.isPendingRunningOrComplete(downloadId: Long): Boolean = runCatching {
        query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_SUCCESSFUL,
                -> true
                else -> false
            }
        } ?: false
    }.getOrDefault(false)

    private companion object {
        const val PREFERENCES_NAME = "track_downloads"
        const val DOWNLOAD_ID_PREFIX = "download_id:"
        const val INVALID_DOWNLOAD_ID = -1L
        const val AUDIO_MIME_TYPE = "audio/mpeg"
        const val PUBLIC_DIRECTORY = "LocalPure"
        val downloadLock = Any()
    }
}
