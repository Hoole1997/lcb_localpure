package com.example.lcb.app

import android.app.Application
import com.blankj.utilcode.util.LogUtils
import com.example.lcb.app.ad.LcbAdInitializer
import com.example.lcb.app.utils.BusinessAdPolicy
import net.corekit.metrics.adjust.AdjustTracker

/**
 * 归因 SDK 的稳定回调类型，避免公共初始化逻辑感知渠道 SDK 的混淆类。
 */
internal typealias AttributionListener = (
    isOrganic: Boolean,
    network: String,
    campaign: String,
    adgroup: String,
    creative: String,
    jsonResponse: String,
) -> Unit

/**
 * 承载所有渠道共用的 Application 初始化逻辑。
 *
 * 各渠道的 [LcbApp] 只负责适配对应 Launcher SDK 的 pre-R8 符号，业务初始化统一留在这里，
 * 后续升级任一渠道 SDK 时不需要复制或同步广告、归因逻辑。
 */
internal class LcbAppDelegate(
    private val application: Application,
) {

    fun onCreate(registerAttributionListener: (AttributionListener) -> Unit) {
        // 先建立本地凭据兜底，Remote Config 完成后再线程安全地热更新，页面无需关心配置来源。
        MusicDependencies.initialize()
        MusicRemoteConfigSync.start()
        BusinessAdPolicy.initializeSession()
        LcbAdInitializer.initialize(application)
        registerAttributionListener { isOrganic, network, campaign, adgroup, creative, jsonResponse ->
            AdjustTracker.init(
                context = application.applicationContext,
                network = network,
                campaign = campaign,
                adgroup = adgroup,
                creative = creative,
                jsonResponse = jsonResponse,
            )
            LogUtils.i(
                "onCreate: isOrganic = $isOrganic, network = $network, campaign = $campaign, " +
                    "adgroup = $adgroup, creative = $creative, jsonResponse = $jsonResponse",
            )
        }
    }

    fun launcherActivityClass(): Class<*> = MainActivity::class.java

    fun protectedActivityClasses(): List<Class<*>> = listOf(MainActivity::class.java)
}
