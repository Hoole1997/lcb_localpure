package com.example.lcb.app

/**
 * Google 正式渠道的 Launcher Application 适配层。
 *
 * 本类只维护 quizguessoncolor-dev 1.0.1 的 pre-R8 API 映射；公共初始化由 [LcbAppDelegate]
 * 负责，避免正式渠道与 Local 测试渠道的混淆符号相互污染。
 */
class LcbApp : com.sonicpure.local.audio.tool.Gb1j0c8gtf8a89n70qeu() {

    private val delegate = LcbAppDelegate(this)

    override fun onCreate() {
        super.onCreate()

        LauncherSdkGateway.install(
            // 正式 SDK: openMainActivity -> syncmemory
            returnToLauncher = { syncmemory() },
            // 正式 SDK: appShowAd -> scanmetasmartlitetool(Activity, String, Int)
            beforeShowAd = { activity -> scanmetasmartlitetool(activity, "", -1) },
        )
        delegate.onCreate { listener -> scanmetasmartlitetool(listener) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun autocleantooltool(): Class<in Any>? {
        // 正式 SDK: getLauncherActivityClass -> autocleantooltool
        return delegate.launcherActivityClass() as Class<in Any>?
    }

    @Suppress("UNCHECKED_CAST")
    override fun deeprestorecorepanel(): List<Class<in Any>?>? {
        // 正式 SDK: getAppActivityClassArray -> deeprestorecorepanel
        return delegate.protectedActivityClasses() as List<Class<in Any>?>?
    }
}
