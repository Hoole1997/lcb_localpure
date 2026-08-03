package com.example.lcb.app.library.dialog

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.lcb.app.MusicLibraryDependencies
import com.example.lcb.app.R
import com.example.lcb.app.analytics.MusicAnalytics
import com.example.lcb.app.library.AddTrackResult
import com.example.lcb.app.library.LibraryTrack
import com.example.lcb.app.library.MusicLibraryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 新建与选择歌单的统一流程控制器，可由首页、歌曲弹框和播放器共同复用。 */
class PlaylistDialogsController(
    private val activity: AppCompatActivity,
    private val repository: MusicLibraryRepository = MusicLibraryDependencies.repository(activity),
) : DefaultLifecycleObserver {
    private var picker: PlaylistPickerBottomSheet? = null
    private var creator: CreatePlaylistBottomSheet? = null
    private var playlistJob: Job? = null
    private var operationJob: Job? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    fun showPlaylistPicker(track: LibraryTrack) {
        dismissPicker()
        var nextPicker: PlaylistPickerBottomSheet? = null
        nextPicker = PlaylistPickerBottomSheet(
            context = activity,
            onPlaylistSelected = { playlist -> addTrack(playlist.id, playlist.name, track) },
            onCreateRequested = {
                dismissPicker()
                showCreatePlaylist { playlistId, playlistName ->
                    addTrack(playlistId, playlistName, track)
                }
            },
            onDismiss = {
                if (picker === nextPicker) {
                    picker = null
                    playlistJob?.cancel()
                    playlistJob = null
                }
            },
        )
        picker = nextPicker
        nextPicker.show()
        playlistJob = activity.lifecycleScope.launch {
            repository.observePlaylists().collectLatest { playlists ->
                picker?.submitPlaylists(playlists)
            }
        }
    }

    fun showCreatePlaylist(onCreated: (playlistId: Long, playlistName: String) -> Unit = { _, _ -> }) {
        dismissCreator()
        var nextCreator: CreatePlaylistBottomSheet? = null
        nextCreator = CreatePlaylistBottomSheet(
            context = activity,
            onCreate = { name -> createPlaylist(name, onCreated) },
            onDismiss = {
                if (creator === nextCreator) creator = null
            },
        )
        creator = nextCreator
        nextCreator.show()
    }

    private fun createPlaylist(name: String, onCreated: (Long, String) -> Unit) {
        if (operationJob?.isActive == true) return
        creator?.setBusy(true)
        operationJob = activity.lifecycleScope.launch {
            val result = repository.createPlaylist(name)
            operationJob = null
            result
                .onSuccess { playlistId ->
                    MusicAnalytics.playlist(
                        MusicAnalytics.PlaylistAction.CREATE,
                        MusicAnalytics.Outcome.SUCCESS,
                    )
                    dismissCreator()
                    onCreated(playlistId, name.trim())
                }
                .onFailure { error ->
                    MusicAnalytics.playlist(
                        MusicAnalytics.PlaylistAction.CREATE,
                        MusicAnalytics.Outcome.FAILURE,
                    )
                    creator?.showError(error.message ?: activity.getString(R.string.playlist_create_failed))
                }
        }
    }

    private fun addTrack(playlistId: Long, playlistName: String, track: LibraryTrack) {
        if (operationJob?.isActive == true) return
        picker?.setBusy(true)
        operationJob = activity.lifecycleScope.launch {
            val result = repository.addTrackToPlaylist(playlistId, track)
            operationJob = null
            MusicAnalytics.playlist(
                MusicAnalytics.PlaylistAction.ADD_TRACK,
                if (result == AddTrackResult.ADDED) {
                    MusicAnalytics.Outcome.SUCCESS
                } else {
                    MusicAnalytics.Outcome.ALREADY_EXISTS
                },
                trackCount = 1,
            )
            Toast.makeText(
                activity,
                if (result == AddTrackResult.ADDED) {
                    activity.getString(R.string.playlist_track_added, playlistName)
                } else {
                    activity.getString(R.string.playlist_track_already_added, playlistName)
                },
                Toast.LENGTH_SHORT,
            ).show()
            dismissPicker()
        }
    }

    private fun dismissPicker() {
        playlistJob?.cancel()
        playlistJob = null
        picker?.dismiss()
        picker = null
    }

    private fun dismissCreator() {
        creator?.dismiss()
        creator = null
    }

    override fun onStop(owner: LifecycleOwner) {
        operationJob?.cancel()
        operationJob = null
        dismissPicker()
        dismissCreator()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        operationJob?.cancel()
        activity.lifecycle.removeObserver(this)
    }
}
