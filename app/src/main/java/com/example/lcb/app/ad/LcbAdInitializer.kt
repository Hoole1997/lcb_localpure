package com.example.lcb.app.ad

import android.app.Application

/** 广告 SDK 已移除；保留稳定入口，避免应用初始化与具体广告实现再次耦合。 */
object LcbAdInitializer {

    @Suppress("UNUSED_PARAMETER")
    fun initialize(application: Application) = Unit
}
