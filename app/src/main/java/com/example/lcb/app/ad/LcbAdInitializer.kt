package com.example.lcb.app.ad

import android.app.Application
import android.util.Log
import com.android.common.bill.BillConfig
import com.android.common.bill.BillConfig.adLoadingDialogRenderer
import com.android.common.bill.BillConfig.admob
import com.android.common.bill.BillConfig.admobFullScreenNativeRenderer
import com.android.common.bill.BillConfig.admobNativeRenderer
import com.android.common.bill.BillConfig.gam
import com.android.common.bill.BillConfig.gamFullScreenNativeRenderer
import com.android.common.bill.BillConfig.gamNativeRenderer
import com.android.common.bill.BillConfig.googleMobileAds
import com.android.common.bill.BillConfig.pangle
import com.android.common.bill.BillConfig.pangleFullScreenNativeRenderer
import com.android.common.bill.BillConfig.pangleNativeRenderer
import com.android.common.bill.BillConfig.topon
import com.android.common.bill.BillConfig.toponFullScreenNativeRenderer
import com.android.common.bill.BillConfig.toponNativeRenderer
import com.android.common.bill.ads.bidding.AppOpenBiddingInitializer
import com.android.common.bill.ui.NativeAdStyle
import com.android.common.bill.ui.pangle.PangleNativeAdStyle
import com.android.common.bill.ui.topon.ToponNativeAdStyle
import com.example.lcb.app.BuildConfig
import com.example.lcb.app.R
import com.example.lcb.app.ad.renderer.DefaultAdLoadingDialogRenderer
import com.example.lcb.app.ad.renderer.DefaultAdmobFullScreenNativeAdRenderer
import com.example.lcb.app.ad.renderer.DefaultAdmobNativeAdRenderer
import com.example.lcb.app.ad.renderer.DefaultGamFullScreenNativeAdRenderer
import com.example.lcb.app.ad.renderer.DefaultGamNativeAdRenderer
import com.example.lcb.app.ad.renderer.DefaultPangleFullScreenNativeAdRenderer
import com.example.lcb.app.ad.renderer.DefaultPangleNativeAdRenderer
import com.example.lcb.app.ad.renderer.DefaultToponFullScreenNativeAdRenderer
import com.example.lcb.app.ad.renderer.DefaultToponNativeAdRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object LcbAdInitializer {

    private const val TAG = "LcbAdInitializer"
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun initialize(application: Application) {
        appScope.launch {
            runCatching {
                AppOpenBiddingInitializer.initialize(application, R.mipmap.ic_launcher) {
                    externallyInitialized = true
                    googleMobileAds = BillConfig.GoogleMobileAdsConfig(
                        applicationId = BuildConfig.ADMOB_APPLICATION_ID
                    )
                    admob = BillConfig.AdmobConfig(
                        splashId = BuildConfig.ADMOB_SPLASH_ID,
                        bannerId = BuildConfig.ADMOB_BANNER_ID,
                        interstitialId = BuildConfig.ADMOB_INTERSTITIAL_ID,
                        nativeId = BuildConfig.ADMOB_NATIVE_ID,
                        fullNativeId = BuildConfig.ADMOB_FULL_NATIVE_ID,
                        rewardedId = BuildConfig.ADMOB_REWARDED_ID,
                        nativeStyleStandard = NativeAdStyle(R.layout.layout_native_ad_admob, "normal"),
                        nativeStyleLarge = NativeAdStyle(R.layout.layout_native_ad_admob, "card"),
                    )
                    gam = BillConfig.GamConfig(
                        splashId = BuildConfig.GAM_SPLASH_ID,
                        bannerId = BuildConfig.GAM_BANNER_ID,
                        interstitialId = BuildConfig.GAM_INTERSTITIAL_ID,
                        nativeId = BuildConfig.GAM_NATIVE_ID,
                        fullNativeId = BuildConfig.GAM_FULL_NATIVE_ID,
                        rewardedId = BuildConfig.GAM_REWARDED_ID,
                        nativeStyleStandard = NativeAdStyle(R.layout.layout_native_ad_admob, "normal"),
                        nativeStyleLarge = NativeAdStyle(R.layout.layout_native_ad_admob, "card"),
                    )
                    pangle = BillConfig.PangleConfig(
                        applicationId = BuildConfig.PANGLE_APPLICATION_ID,
                        splashId = BuildConfig.PANGLE_SPLASH_ID,
                        bannerId = BuildConfig.PANGLE_BANNER_ID,
                        interstitialId = BuildConfig.PANGLE_INTERSTITIAL_ID,
                        nativeId = BuildConfig.PANGLE_NATIVE_ID,
                        fullNativeId = BuildConfig.PANGLE_FULL_NATIVE_ID,
                        rewardedId = BuildConfig.PANGLE_REWARDED_ID,
                        nativeStyleStandard = PangleNativeAdStyle(R.layout.layout_native_ad_pangle),
                        nativeStyleLarge = PangleNativeAdStyle(R.layout.layout_native_ad_pangle),
                    )
                    topon = BillConfig.ToponConfig(
                        applicationId = BuildConfig.TOPON_APPLICATION_ID,
                        appKey = BuildConfig.TOPON_APP_KEY,
                        interstitialId = BuildConfig.TOPON_INTERSTITIAL_ID,
                        rewardedId = BuildConfig.TOPON_REWARDED_ID,
                        nativeId = BuildConfig.TOPON_NATIVE_ID,
                        splashId = BuildConfig.TOPON_SPLASH_ID,
                        fullNativeId = BuildConfig.TOPON_FULL_NATIVE_ID,
                        bannerId = BuildConfig.TOPON_BANNER_ID,
                        nativeStyleStandard = ToponNativeAdStyle(
                            R.layout.layout_native_ad_topon,
                            "normal",
                            72
                        ),
                        nativeStyleLarge = ToponNativeAdStyle(
                            R.layout.layout_native_ad_topon,
                            "large",
                            146
                        ),
                    )
                    admobNativeRenderer = DefaultAdmobNativeAdRenderer()
                    admobFullScreenNativeRenderer = DefaultAdmobFullScreenNativeAdRenderer()
                    gamNativeRenderer = DefaultGamNativeAdRenderer()
                    gamFullScreenNativeRenderer = DefaultGamFullScreenNativeAdRenderer()
                    pangleNativeRenderer = DefaultPangleNativeAdRenderer()
                    pangleFullScreenNativeRenderer = DefaultPangleFullScreenNativeAdRenderer()
                    toponNativeRenderer = DefaultToponNativeAdRenderer()
                    toponFullScreenNativeRenderer = DefaultToponFullScreenNativeAdRenderer()
                    adLoadingDialogRenderer = DefaultAdLoadingDialogRenderer()
                }
            }.onFailure { throwable ->
                Log.e(TAG, "Failed to initialize ads", throwable)
            }
        }
    }
}
