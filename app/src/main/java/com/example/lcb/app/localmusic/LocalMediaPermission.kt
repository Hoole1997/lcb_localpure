package com.example.lcb.app.localmusic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** 本地音乐权限规则的唯一出口，首页 A 面和独立本地音乐页共享相同版本判断。 */
internal object LocalMediaPermission {
    fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun isGranted(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context,
        requiredPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    fun shouldOpenSettings(activity: ComponentActivity, requestAttempted: Boolean): Boolean =
        requestAttempted && !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            requiredPermission(),
        )
}
