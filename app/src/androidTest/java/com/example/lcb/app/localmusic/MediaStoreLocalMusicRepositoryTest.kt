package com.example.lcb.app.localmusic

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreLocalMusicRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    fun mediaStoreTrackIsMappedToAPlayableContentUri() = runBlocking {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        var insertedUri: Uri? = null
        try {
            insertedUri = resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "localpure_repository_test.mp3")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/LocalPureTests")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.TITLE, "Repository Test Track")
                    put(MediaStore.Audio.Media.ARTIST, "LocalPure Test")
                    put(MediaStore.Audio.Media.DURATION, 1_000L)
                },
            )
            assertNotNull(insertedUri)

            val track = MediaStoreLocalMusicRepository(context)
                .observeTracks()
                .first()
                .first { it.contentUri == insertedUri.toString() }

            assertEquals("Repository Test Track", track.title)
            assertEquals("LocalPure Test", track.artist)
            assertEquals("LocalPureTests", track.folderName)
        } finally {
            insertedUri?.let { resolver.delete(it, null, null) }
        }
    }
}
