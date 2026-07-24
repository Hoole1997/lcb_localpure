package com.example.lcb.app.trackactions

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.lcb.app.MusicLibraryDependencies
import com.example.lcb.app.MusicDependencies
import com.example.lcb.app.R
import com.example.lcb.app.artist.ArtistActivity
import com.example.lcb.app.library.MusicLibraryRepository
import com.example.lcb.app.library.dialog.PlaylistDialogsController
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多页面复用入口。Controller 随宿主生命周期关闭弹框，避免 Dialog 持有已销毁 Activity。
 */
class TrackActionsController(
    private val activity: AppCompatActivity,
    private val repository: MusicLibraryRepository = MusicLibraryDependencies.repository(activity),
    private val artistResolver: TrackArtistResolver = MusicSdkTrackArtistResolver(MusicDependencies.sdk),
) : DefaultLifecycleObserver {
    private var sheet: TrackActionsBottomSheet? = null
    private val playlistDialogs = PlaylistDialogsController(activity, repository)
    private var showJob: Job? = null
    private var favoriteJob: Job? = null
    private var artistJob: Job? = null
    private val trackDownloader = SystemTrackDownloader(activity.applicationContext)

    init {
        activity.lifecycle.addObserver(this)
    }

    fun show(track: TrackActionUiModel) {
        if (activity.isFinishing || activity.isDestroyed) return
        showJob?.cancel()
        showJob = activity.lifecycleScope.launch {
            val latestTrack = track.copy(isFavorite = repository.isFavorite(track.id))
            if (
                activity.isFinishing || activity.isDestroyed ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return@launch
            showSheet(latestTrack)
        }
    }

    fun dismiss() {
        sheet?.dismiss()
        sheet = null
    }

    override fun onStop(owner: LifecycleOwner) {
        dismiss()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        showJob?.cancel()
        favoriteJob?.cancel()
        artistJob?.cancel()
        dismiss()
        activity.lifecycle.removeObserver(this)
    }

    private fun showSheet(track: TrackActionUiModel) {
        sheet?.dismiss()
        var nextSheet: TrackActionsBottomSheet? = null
        nextSheet = TrackActionsBottomSheet(
            context = activity,
            track = track,
            onAction = ::handleAction,
            onDismiss = {
                if (sheet === nextSheet) sheet = null
            },
        )
        sheet = nextSheet
        nextSheet.show()
    }

    private fun handleAction(event: TrackActionEvent) {
        when (event.type) {
            TrackActionType.SONG_INFO -> openArtist(event.track)
            TrackActionType.ADD_TO_PLAYLIST -> playlistDialogs.showPlaylistPicker(event.track.toLibraryTrack())
            TrackActionType.DOWNLOAD -> enqueueDownload(event.track)
            TrackActionType.FAVORITE_CHANGED -> {
                favoriteJob?.cancel()
                favoriteJob = activity.lifecycleScope.launch {
                    repository.setFavorite(event.track.toLibraryTrack(), event.isFavorite)
                }
            }
            TrackActionType.DELETE_FROM_DEVICE -> DeviceTrackDeletionActivity.open(activity, event.track)
        }
    }

    /** Binder 与下载数据库操作放到 IO 线程；任务提交后由系统接管，不依赖页面生命周期。 */
    private fun enqueueDownload(track: TrackActionUiModel) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { trackDownloader.enqueue(track) }
            if (
                activity.isFinishing || activity.isDestroyed ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                return@launch
            }
            val message = when (result) {
                TrackDownloadResult.Enqueued -> activity.getString(R.string.track_download_started, track.title)
                TrackDownloadResult.AlreadyQueued -> activity.getString(R.string.track_download_already_queued, track.title)
                TrackDownloadResult.Unavailable -> activity.getString(R.string.track_download_unavailable)
                TrackDownloadResult.Failed -> activity.getString(R.string.track_download_failed)
            }
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openArtist(track: TrackActionUiModel) {
        artistJob?.cancel()
        artistJob = activity.lifecycleScope.launch {
            val artist = try {
                artistResolver.resolve(track)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            if (
                activity.isFinishing || activity.isDestroyed ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                return@launch
            }
            if (artist != null) {
                ArtistActivity.open(activity, artist)
            } else {
                Toast.makeText(activity, R.string.track_action_artist_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
