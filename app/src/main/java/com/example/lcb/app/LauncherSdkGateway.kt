package com.example.lcb.app

import android.app.Activity

/**
 * 公共业务与渠道 Launcher SDK 之间的稳定边界。
 *
 * 渠道 Application 在启动时注册实际动作；Activity 和广告业务无需引用 LcbApp 或混淆符号。
 */
internal object LauncherSdkGateway {

    @Volatile
    private var actions = Actions()

    fun install(
        returnToLauncher: () -> Unit,
        beforeShowAd: (Activity) -> Unit,
    ) {
        actions = Actions(
            returnToLauncher = returnToLauncher,
            beforeShowAd = beforeShowAd,
        )
    }

    fun returnToLauncher() {
        actions.returnToLauncher()
    }

    fun beforeShowAd(activity: Activity) {
        actions.beforeShowAd(activity)
    }

    /** Application 尚未完成初始化时保持安全空操作，避免异常启动路径崩溃。 */
    private data class Actions(
        val returnToLauncher: () -> Unit = {},
        val beforeShowAd: (Activity) -> Unit = {},
    )
}
