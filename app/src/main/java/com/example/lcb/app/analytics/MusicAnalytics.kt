package com.example.lcb.app.analytics

import android.util.Log
import com.example.lcb.music.model.MusicPlatform
import net.corekit.core.report.ReportDataManager
import java.util.Locale

/**
 * 音乐业务埋点的唯一出口。
 *
 * 页面只传递枚举和统计值，避免事件名散落，也禁止把搜索词、歌名、歌单名等用户内容上报。
 * 上报异常不会影响播放、跳转等主流程。
 */
internal object MusicAnalytics {
    enum class Screen(val value: String) {
        HOME("home"),
        SEARCH("search"),
        PLAYER("player"),
        RECOMMENDED("recommended"),
        LOCAL_MUSIC("local_music"),
        ARTIST("artist"),
        FAVORITES("favorites"),
        PLAYLIST("playlist"),
        SETTINGS("settings"),
    }

    enum class Surface(val value: String) {
        HOME("home"),
        HOME_MINI_PLAYER("home_mini_player"),
        SEARCH("search"),
        PLAYER("player"),
        RECOMMENDED("recommended"),
        RECOMMENDED_MINI_PLAYER("recommended_mini_player"),
        LOCAL_MUSIC("local_music"),
        LOCAL_MINI_PLAYER("local_mini_player"),
        ARTIST("artist"),
        ARTIST_MINI_PLAYER("artist_mini_player"),
        FAVORITES("favorites"),
        PLAYLIST("playlist"),
        TRACK_ACTION_SHEET("track_action_sheet"),
    }

    enum class PlaybackAction(val value: String) {
        OPEN_PLAYER("open_player"),
        PLAY("play"),
        PAUSE("pause"),
        PLAY_ALL("play_all"),
        PREVIOUS("previous"),
        NEXT("next"),
        SEEK("seek"),
        QUEUE_OPEN("queue_open"),
        QUEUE_SELECT("queue_select"),
        SHARE("share"),
        MODE_SEQUENTIAL("mode_sequential"),
        MODE_REPEAT_ONE("mode_repeat_one"),
        MODE_SHUFFLE("mode_shuffle"),
    }

    enum class SearchAction(val value: String) {
        STARTED("started"),
        SUCCESS("success"),
        EMPTY("empty"),
        FAILURE("failure"),
        RETRY("retry"),
    }

    enum class TrackAction(val value: String) {
        OPEN_ARTIST("open_artist"),
        ADD_TO_PLAYLIST("add_to_playlist"),
        DOWNLOAD("download"),
        FAVORITE_ADD("favorite_add"),
        FAVORITE_REMOVE("favorite_remove"),
        DELETE_LOCAL("delete_local"),
        SHOW_DETAILS("show_details"),
        HIDE_DETAILS("hide_details"),
        SHARE_ARTIST("share_artist"),
    }

    enum class PlaylistAction(val value: String) {
        OPEN("open"),
        CREATE("create"),
        ADD_TRACK("add_track"),
        REMOVE_TRACKS("remove_tracks"),
        DELETE("delete"),
    }

    enum class SettingsAction(val value: String) {
        OPEN_LANGUAGE("open_language"),
        APPLY_LANGUAGE("apply_language"),
        OPEN_PRIVACY_POLICY("open_privacy_policy"),
        OPEN_TERMS_OF_SERVICE("open_terms_of_service"),
    }

    enum class Outcome(val value: String) {
        SUCCESS("success"),
        FAILURE("failure"),
        EMPTY("empty"),
        ALREADY_EXISTS("already_exists"),
        GRANTED("granted"),
        DENIED("denied"),
        OPEN_SETTINGS("open_settings"),
    }

    fun screenView(screen: Screen) {
        report(EVENT_SCREEN_VIEW, mapOf(KEY_SCREEN to screen.value))
    }

    fun trackSelected(source: Surface, platform: MusicPlatform?, queueSize: Int) {
        report(
            EVENT_TRACK_SELECT,
            mapOf(
                KEY_SOURCE to source.value,
                KEY_PLATFORM to platform.analyticsValue(),
                KEY_QUEUE_SIZE to queueSize.coerceAtLeast(0),
            ),
        )
    }

    fun playback(
        action: PlaybackAction,
        surface: Surface,
        platform: MusicPlatform? = null,
        queueSize: Int? = null,
    ) {
        report(
            EVENT_PLAYBACK_CONTROL,
            buildMap {
                put(KEY_ACTION, action.value)
                put(KEY_SURFACE, surface.value)
                platform?.let { put(KEY_PLATFORM, it.analyticsValue()) }
                queueSize?.let { put(KEY_QUEUE_SIZE, it.coerceAtLeast(0)) }
            },
        )
    }

    fun playbackError(platform: MusicPlatform?, errorCode: Any?) {
        report(EVENT_PLAYBACK_ERROR, playbackErrorParameters(platform, errorCode))
    }

    /** 错误码统一按字符串上报；上游缺失或只传空白时使用 500，保证事件字段始终存在。 */
    internal fun playbackErrorParameters(platform: MusicPlatform?, errorCode: Any?): Map<String, Any> = mapOf(
        KEY_PLATFORM to platform.analyticsValue(),
        KEY_ERROR_CODE to errorCode.normalizedPlaybackErrorCode(),
    )

    fun search(
        action: SearchAction,
        queryLength: Int,
        resultCount: Int? = null,
    ) {
        report(
            EVENT_SEARCH,
            buildMap {
                put(KEY_ACTION, action.value)
                put(KEY_QUERY_LENGTH, queryLength.coerceAtLeast(0))
                resultCount?.let { put(KEY_RESULT_COUNT, it.coerceAtLeast(0)) }
            },
        )
    }

    fun trackAction(
        action: TrackAction,
        surface: Surface,
        platform: MusicPlatform?,
        outcome: Outcome? = null,
    ) {
        report(
            EVENT_TRACK_ACTION,
            buildMap {
                put(KEY_ACTION, action.value)
                put(KEY_SURFACE, surface.value)
                put(KEY_PLATFORM, platform.analyticsValue())
                outcome?.let { put(KEY_OUTCOME, it.value) }
            },
        )
    }

    fun playlist(
        action: PlaylistAction,
        outcome: Outcome,
        trackCount: Int? = null,
    ) {
        report(
            EVENT_PLAYLIST_ACTION,
            buildMap {
                put(KEY_ACTION, action.value)
                put(KEY_OUTCOME, outcome.value)
                trackCount?.let { put(KEY_TRACK_COUNT, it.coerceAtLeast(0)) }
            },
        )
    }

    fun settings(action: SettingsAction, outcome: Outcome? = null, value: String? = null) {
        report(EVENT_SETTINGS_ACTION, settingsParameters(action, outcome, value))
    }

    /** apply_language 的 value 是必填契约；跟随系统或异常空值统一归一为 system。 */
    internal fun settingsParameters(
        action: SettingsAction,
        outcome: Outcome? = null,
        value: String? = null,
    ): Map<String, Any> = buildMap {
        put(KEY_ACTION, action.value)
        outcome?.let { put(KEY_OUTCOME, it.value) }
        when (action) {
            SettingsAction.APPLY_LANGUAGE -> put(KEY_VALUE, value.normalizedSettingsValue())
            else -> value?.trim()?.takeIf(String::isNotEmpty)?.let {
                put(KEY_VALUE, it.lowercase(Locale.ROOT))
            }
        }
    }

    fun localMediaPermission(outcome: Outcome) {
        report(EVENT_LOCAL_MEDIA_PERMISSION, mapOf(KEY_OUTCOME to outcome.value))
    }

    private fun MusicPlatform?.analyticsValue(): String =
        this?.name?.lowercase(Locale.ROOT) ?: PLATFORM_LOCAL_OR_UNKNOWN

    private fun Any?.normalizedPlaybackErrorCode(): String =
        this?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: FALLBACK_PLAYBACK_ERROR_CODE

    private fun String?.normalizedSettingsValue(): String =
        this?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT) ?: SYSTEM_LANGUAGE_VALUE

    private fun report(eventName: String, parameters: Map<String, Any>) {
        try {
            ReportDataManager.reportData(eventName, parameters)
        } catch (error: Exception) {
            // 埋点 SDK 的初始化或参数异常不能阻断用户操作；VM/OOM 等致命错误仍交给系统处理。
            Log.w(TAG, "Unable to report analytics event: $eventName", error)
        }
    }

    private const val TAG = "MusicAnalytics"
    private const val PLATFORM_LOCAL_OR_UNKNOWN = "local_or_unknown"
    private const val FALLBACK_PLAYBACK_ERROR_CODE = "500"
    private const val SYSTEM_LANGUAGE_VALUE = "system"

    private const val EVENT_SCREEN_VIEW = "music_screen_view"
    private const val EVENT_TRACK_SELECT = "music_track_select"
    private const val EVENT_PLAYBACK_CONTROL = "music_playback_control"
    private const val EVENT_PLAYBACK_ERROR = "music_playback_error"
    private const val EVENT_SEARCH = "music_search"
    private const val EVENT_TRACK_ACTION = "music_track_action"
    private const val EVENT_PLAYLIST_ACTION = "music_playlist_action"
    private const val EVENT_SETTINGS_ACTION = "music_settings_action"
    private const val EVENT_LOCAL_MEDIA_PERMISSION = "music_local_permission"

    private const val KEY_ACTION = "action"
    private const val KEY_ERROR_CODE = "error_code"
    private const val KEY_OUTCOME = "outcome"
    private const val KEY_PLATFORM = "platform"
    private const val KEY_QUERY_LENGTH = "query_length"
    private const val KEY_QUEUE_SIZE = "queue_size"
    private const val KEY_RESULT_COUNT = "result_count"
    private const val KEY_SCREEN = "screen"
    private const val KEY_SOURCE = "source"
    private const val KEY_SURFACE = "surface"
    private const val KEY_TRACK_COUNT = "track_count"
    private const val KEY_VALUE = "value"
}
