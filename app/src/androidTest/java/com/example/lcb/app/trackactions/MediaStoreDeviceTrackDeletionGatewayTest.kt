package com.example.lcb.app.trackactions

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreDeviceTrackDeletionGatewayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    fun ownedMediaStoreAudioIsActuallyDeleted() {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val inserted = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "localpure_deletion_test.mp3")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/LocalPureTests")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.TITLE, "Deletion Test Track")
                put(MediaStore.Audio.Media.ARTIST, "LocalPure Test")
            },
        )
        assertNotNull(inserted)

        try {
            assertEquals(
                DeviceTrackDeleteResult.Deleted,
                MediaStoreDeviceTrackDeletionGateway(resolver).delete(requireNotNull(inserted)),
            )
            resolver.query(requireNotNull(inserted), arrayOf(MediaStore.Audio.Media._ID), null, null, null).use { cursor ->
                assertFalse(cursor?.moveToFirst() == true)
            }
        } finally {
            // 删除成功后部分 ROM 对同一失效 URI 再次 delete 会抛 SecurityException。
            runCatching { resolver.delete(requireNotNull(inserted), null, null) }
        }
    }
}
