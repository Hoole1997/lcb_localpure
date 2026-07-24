package com.example.lcb.app.trackactions

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** MediaStore 文件操作边界；调用方负责切到 IO 线程并处理系统授权 UI。 */
internal class MediaStoreDeviceTrackDeletionGateway(
    private val resolver: ContentResolver,
) {
    fun delete(uri: Uri): DeviceTrackDeleteResult = try {
        if (resolver.delete(uri, null, null) > 0) {
            DeviceTrackDeleteResult.Deleted
        } else {
            DeviceTrackDeleteResult.Failed
        }
    } catch (security: SecurityException) {
        recoverableIntentSender(security)
            ?.let(DeviceTrackDeleteResult::NeedsConsent)
            ?: DeviceTrackDeleteResult.Failed
    } catch (_: Throwable) {
        DeviceTrackDeleteResult.Failed
    }

    private fun recoverableIntentSender(error: SecurityException): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return (error as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
    }
}

internal sealed interface DeviceTrackDeleteResult {
    data object Deleted : DeviceTrackDeleteResult
    data class NeedsConsent(val intentSender: IntentSender) : DeviceTrackDeleteResult
    data object Failed : DeviceTrackDeleteResult
}
