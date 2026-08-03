package com.example.lcb.app.localmusic

import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.app.ui.AppLoadError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalMusicViewModelTest {
    @Test
    fun `permission starts observation and playback is projected into local item`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeRepository(listOf(track(1), track(2)))
            val viewModel = LocalMusicViewModel(repository)

            viewModel.setPermissionGranted(true)
            advanceUntilIdle()
            assertEquals(2, viewModel.state.value.tracks.size)

            viewModel.updatePlayback(viewModel.state.value.tracks[1].track.toPlayerTrack(), true)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.tracks[0].isPlaying)
            assertTrue(viewModel.state.value.tracks[1].isPlaying)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `large queue is capped and always starts with selected track`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeRepository((0L until 150L).map(::track))
            val viewModel = LocalMusicViewModel(repository)
            viewModel.setPermissionGranted(true)
            advanceUntilIdle()

            val queue = viewModel.queueForTrack("LOCAL:120")

            assertEquals(100, queue.size)
            assertEquals("LOCAL:120", queue.first().id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `folder selection filters tracks and queue without rescanning repository`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeRepository(
                listOf(
                    track(1, folder = "Download"),
                    track(2, folder = "Music"),
                    track(3, folder = "Music"),
                ),
            )
            val viewModel = LocalMusicViewModel(repository)
            viewModel.setPermissionGranted(true)
            advanceUntilIdle()

            assertEquals(3, viewModel.state.value.totalTrackCount)
            assertEquals(listOf("Download", "Music"), viewModel.state.value.folders.drop(1).map { it.name })

            viewModel.selectFolder("Music")
            advanceUntilIdle()

            assertEquals(listOf("LOCAL:2", "LOCAL:3"), viewModel.state.value.tracks.map { it.id })
            assertEquals(listOf("LOCAL:3", "LOCAL:2"), viewModel.queueForTrack("LOCAL:3").map { it.id })
            assertEquals(1, repository.observationCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `revoked permission clears previously visible device metadata`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = LocalMusicViewModel(FakeRepository(listOf(track(1))))
            viewModel.setPermissionGranted(true)
            advanceUntilIdle()
            viewModel.setPermissionGranted(false)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.hasPermission)
            assertTrue(viewModel.state.value.tracks.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `media store exception is exposed as localized local music error semantic`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = object : LocalMusicRepository {
                override fun observeTracks(): Flow<List<LocalMusicTrack>> = flow {
                    error("English platform error that must not reach the UI")
                }
            }
            val viewModel = LocalMusicViewModel(repository)

            viewModel.setPermissionGranted(true)
            advanceUntilIdle()

            assertEquals(AppLoadError.LOCAL_MUSIC, viewModel.state.value.loadError)
            assertTrue(viewModel.state.value.hasPermission)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeRepository(initial: List<LocalMusicTrack>) : LocalMusicRepository {
        private val tracks = MutableStateFlow(initial)
        var observationCount = 0
            private set

        override fun observeTracks(): Flow<List<LocalMusicTrack>> {
            observationCount += 1
            return tracks
        }
    }

    private fun track(id: Long, folder: String = "Music") = LocalMusicTrack(
        mediaStoreId = id,
        title = "Track $id",
        artist = "Artist",
        album = null,
        folderName = folder,
        artworkUrl = null,
        contentUri = "content://media/external/audio/media/$id",
        durationMs = 60_000L,
    )
}
