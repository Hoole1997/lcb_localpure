package com.example.lcb.app.ad.renderer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.android.common.bill.ads.renderer.PangleNativeAdRenderer
import com.android.common.bill.ui.pangle.PangleNativeAdStyle
import com.bytedance.sdk.openadsdk.api.nativeAd.PAGNativeAdData
import com.bytedance.sdk.openadsdk.api.nativeAd.PAGViewBinder
import com.example.lcb.app.R

class DefaultPangleNativeAdRenderer : PangleNativeAdRenderer {

    override fun createLayout(context: Context, style: PangleNativeAdStyle): ViewGroup {
        return LayoutInflater.from(context)
            .inflate(R.layout.layout_native_ad_pangle, null, false) as ViewGroup
    }

    override fun bindData(context: Context, adView: ViewGroup, nativeAdData: PAGNativeAdData) {
        val ivIcon = adView.findViewById<ImageView>(R.id.iv_ad_icon)
        val tvTitle = adView.findViewById<TextView>(R.id.tv_ad_title)
        val tvDesc = adView.findViewById<TextView>(R.id.tv_ad_description)
        val tvButton = adView.findViewById<TextView>(R.id.tv_ad_button)

        tvTitle.text = nativeAdData.title
        tvDesc.text = nativeAdData.description
        tvButton.text = nativeAdData.buttonText

        nativeAdData.icon?.imageUrl?.let { url ->
            loadImageInto(url, ivIcon)
        }
    }

    override fun createViewBinder(container: ViewGroup, adView: ViewGroup): PAGViewBinder {
        return PAGViewBinder.Builder(container).build()
    }

    override fun getClickViews(adView: ViewGroup): List<View> {
        return listOf(
            adView.findViewById(R.id.tv_ad_button),
            adView.findViewById(R.id.tv_ad_title),
            adView.findViewById(R.id.iv_ad_icon)
        )
    }

    private fun loadImageInto(url: String, imageView: ImageView) {
        // Glide 按 View 尺寸解码并随 View 生命周期取消，避免裸线程和广告原图导致 OOM。
        Glide.with(imageView).load(url).override(96, 96).centerCrop().into(imageView)
    }
}
