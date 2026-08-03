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
            // dev 1.0.1: openMainActivity -> scansafeloc
            returnToLauncher = { minicoreshield() },
            // 正式依赖没有 appShowAd API，展示广告前无需额外通知 Launcher SDK。
            beforeShowAd = { scanmetasmartlitetool(activity, "", -1) },
        )
        delegate.onCreate { listener -> scanmetasmartlitetool(listener) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun deeprestorecorepanel(): Class<in Any>? {
        // dev 1.0.1: getLauncherActivityClass -> smartbackuptoolsignal
        return delegate.launcherActivityClass() as Class<in Any>?
    }

    @Suppress("UNCHECKED_CAST")
    override fun hyperrestoresafelocker(): List<Class<in Any>?>? {
        // dev 1.0.1: getAppActivityClassArray -> prodailysmartmemory
        return delegate.protectedActivityClasses() as List<Class<in Any>?>?
    }
}
