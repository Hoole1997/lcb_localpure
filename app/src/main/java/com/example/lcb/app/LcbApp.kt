package com.example.lcb.app

import android.app.Application
import com.example.lcb.app.ad.LcbAdInitializer
import com.example.lcb.app.utils.BusinessAdPolicy

/**
 * 应用唯一入口。
 *
 * Application 不再继承任何渠道 Launcher SDK 类，公共初始化因此与渠道完全解耦。
 * 广告初始化入口保留为 no-op，便于业务代码在不引入广告 SDK 的情况下继续编译。
 */
class LcbApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 本地凭据先兜底，Remote Config 完成后再热更新。
        MusicDependencies.initialize()
        MusicRemoteConfigSync.start()
        BusinessAdPolicy.initializeSession()
        LcbAdInitializer.initialize(this)
    }
}
