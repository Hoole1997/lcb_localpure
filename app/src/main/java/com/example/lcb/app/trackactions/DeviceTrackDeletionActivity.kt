package com.example.lcb.app.trackactions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresApi
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.lcb.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 删除本地 MediaStore 音频的短生命周期协调页。
 *
 * 独立 Activity 可以安全持有系统 IntentSender/运行时权限结果，调用方不需要为每个歌曲列表
 * 重复注册 ActivityResultLauncher；删除完成后 MediaStore 的 ContentObserver 会刷新本地音乐列表。
 */
class DeviceTrackDeletionActivity : AppCompatActivity() {
    private lateinit var request: DeleteRequest
    private var awaitingSystemDeleteConsent = false
    private var awaitingRecoverableConsent = false
    private var awaitingLegacyPermission = false
    private var retriedAfterConsent = false
    private var legacyDeleteConfirmed = false
    private val deletionGateway by lazy(LazyThreadSafetyMode.NONE) {
        MediaStoreDeviceTrackDeletionGateway(contentResolver)
    }

    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val shouldRetryDirectDelete = awaitingRecoverableConsent
        awaitingSystemDeleteConsent = false
        awaitingRecoverableConsent = false
        if (result.resultCode != Activity.RESULT_OK) {
            finishWithoutAnimation()
            return@registerForActivityResult
        }
        if (shouldRetryDirectDelete) {
            retriedAfterConsent = true
            deleteDirectly()
        } else {
            // Android 11+ 的 createDeleteRequest 在用户确认后由系统完成删除。
            completeSuccessfully()
        }
    }

    private val legacyWritePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        awaitingLegacyPermission = false
        if (granted) {
            deleteDirectly()
        } else {
            showFailure(R.string.track_delete_storage_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = intent.toDeleteRequest() ?: run {
            finishWithoutAnimation()
            return
        }
        awaitingRecoverableConsent = savedInstanceState?.getBoolean(STATE_AWAITING_RECOVERABLE) == true
        awaitingSystemDeleteConsent = savedInstanceState?.getBoolean(STATE_AWAITING_SYSTEM_DELETE) == true
        awaitingLegacyPermission = savedInstanceState?.getBoolean(STATE_AWAITING_LEGACY_PERMISSION) == true
        retriedAfterConsent = savedInstanceState?.getBoolean(STATE_RETRIED_AFTER_CONSENT) == true
        legacyDeleteConfirmed = savedInstanceState?.getBoolean(STATE_LEGACY_CONFIRMED) == true
        if (!awaitingSystemDeleteConsent && !awaitingRecoverableConsent && !awaitingLegacyPermission) {
            if (legacyDeleteConfirmed && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                requestLegacyDelete()
            } else {
                beginDeletion()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_AWAITING_SYSTEM_DELETE, awaitingSystemDeleteConsent)
        outState.putBoolean(STATE_AWAITING_RECOVERABLE, awaitingRecoverableConsent)
        outState.putBoolean(STATE_AWAITING_LEGACY_PERMISSION, awaitingLegacyPermission)
        outState.putBoolean(STATE_RETRIED_AFTER_CONSENT, retriedAfterConsent)
        outState.putBoolean(STATE_LEGACY_CONFIRMED, legacyDeleteConfirmed)
        super.onSaveInstanceState(outState)
    }

    private fun beginDeletion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchSystemDeleteRequest()
        } else {
            // Android 10 及以下没有统一的删除确认面板，应用先做一次明确的不可逆操作确认。
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.track_delete_title)
                .setMessage(getString(R.string.track_delete_message, request.title))
                .setNegativeButton(R.string.playlist_cancel) { _, _ -> finishWithoutAnimation() }
                .setOnCancelListener { finishWithoutAnimation() }
                .setPositiveButton(R.string.track_delete_confirm) { _, _ ->
                    legacyDeleteConfirmed = true
                    requestLegacyDelete()
                }
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun launchSystemDeleteRequest() {
        val pendingIntent = runCatching {
            MediaStore.createDeleteRequest(contentResolver, listOf(request.uri))
        }.getOrElse {
            showFailure(R.string.track_delete_failed)
            return
        }
        awaitingSystemDeleteConsent = true
        deleteConsentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    private fun requestLegacyDelete() {
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            awaitingLegacyPermission = true
            legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            deleteDirectly()
        }
    }

    private fun deleteDirectly() {
        lifecycleScope.launch {
            when (val result = withContext(Dispatchers.IO) { deletionGateway.delete(request.uri) }) {
                DeviceTrackDeleteResult.Deleted -> completeSuccessfully()
                is DeviceTrackDeleteResult.NeedsConsent -> {
                    if (retriedAfterConsent) {
                        showFailure(R.string.track_delete_failed)
                    } else {
                        awaitingRecoverableConsent = true
                        deleteConsentLauncher.launch(IntentSenderRequest.Builder(result.intentSender).build())
                    }
                }
                DeviceTrackDeleteResult.Failed -> showFailure(R.string.track_delete_failed)
            }
        }
    }

    private fun completeSuccessfully() {
        Toast.makeText(this, getString(R.string.track_delete_success, request.title), Toast.LENGTH_SHORT).show()
        finishWithoutAnimation()
    }

    private fun showFailure(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
        finishWithoutAnimation()
    }

    @Suppress("DEPRECATION")
    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    private fun Intent.toDeleteRequest(): DeleteRequest? {
        getStringExtra(EXTRA_TRACK_ID)?.takeIf { it.startsWith(LOCAL_TRACK_ID_PREFIX) } ?: return null
        val title = getStringExtra(EXTRA_TRACK_TITLE)?.takeIf(String::isNotBlank) ?: return null
        val uri = getStringExtra(EXTRA_CONTENT_URI)?.let(Uri::parse) ?: return null
        if (uri.scheme != CONTENT_RESOLVER_SCHEME || uri.authority != MediaStore.AUTHORITY) return null
        return DeleteRequest(title, uri)
    }

    private data class DeleteRequest(val title: String, val uri: Uri)

    companion object {
        private const val EXTRA_TRACK_ID = "device_delete.track_id"
        private const val EXTRA_TRACK_TITLE = "device_delete.track_title"
        private const val EXTRA_CONTENT_URI = "device_delete.content_uri"
        private const val STATE_AWAITING_SYSTEM_DELETE = "device_delete.awaiting_system"
        private const val STATE_AWAITING_RECOVERABLE = "device_delete.awaiting_recoverable"
        private const val STATE_AWAITING_LEGACY_PERMISSION = "device_delete.awaiting_legacy_permission"
        private const val STATE_RETRIED_AFTER_CONSENT = "device_delete.retried"
        private const val STATE_LEGACY_CONFIRMED = "device_delete.legacy_confirmed"
        private const val LOCAL_TRACK_ID_PREFIX = "LOCAL:"
        private const val CONTENT_RESOLVER_SCHEME = "content"

        fun open(context: Context, track: TrackActionUiModel) {
            if (!track.isLocalDeviceTrack) return
            val intent = Intent(context, DeviceTrackDeletionActivity::class.java).apply {
                putExtra(EXTRA_TRACK_ID, track.id)
                putExtra(EXTRA_TRACK_TITLE, track.title)
                putExtra(EXTRA_CONTENT_URI, track.streamUrl)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            if (context is Activity) {
                @Suppress("DEPRECATION")
                context.overridePendingTransition(0, 0)
            }
        }
    }
}
