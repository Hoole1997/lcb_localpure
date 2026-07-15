package com.example.lcb.app

/**
 * Local 测试渠道的 Launcher Application 适配层。
 *
 * 本类只维护 RemoteControl 1.0.1 的 pre-R8 API 映射。SDK 升级导致符号变化时，只需调整
 * 这个渠道文件，不会影响 Google 正式包。
 */
class LcbApp : com.leafmotivation.quizguessoncolor.Iej9ieio6r89e7ya() {

    private val delegate = LcbAppDelegate(this)

    override fun onCreate() {
        super.onCreate()

        LauncherSdkGateway.install(
            // RemoteControl 1.0.1: openMainActivity -> prodailysmartmemory
            returnToLauncher = { prodailysmartmemory() },
            // RemoteControl 1.0.1: appShowAd -> maxquicklitememory(Activity, String, Int)
            beforeShowAd = { activity -> maxquicklitememory(activity, "", -1) },
        )
        // RemoteControl 1.0.1: setNetworkEventListener -> maxquicklitememory(Function6)
        delegate.onCreate { listener -> maxquicklitememory(listener) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun metaautovault(): Class<in Any>? {
        // RemoteControl 1.0.1: getLauncherActivityClass -> metaautovault
        return delegate.launcherActivityClass() as Class<in Any>?
    }

    @Suppress("UNCHECKED_CAST")
    override fun convertsafepower(): List<Class<in Any>?>? {
        // RemoteControl 1.0.1: getAppActivityClassArray -> convertsafepower
        return delegate.protectedActivityClasses() as List<Class<in Any>?>?
    }
}
