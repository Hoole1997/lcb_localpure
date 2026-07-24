package com.example.lcb.app.ad.renderer

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.android.common.bill.ads.renderer.ToponNativeAdRenderer
import com.android.common.bill.ui.topon.ToponNativeAdStyle
import com.example.lcb.app.R
import com.thinkup.nativead.api.TUNativeMaterial
import com.thinkup.nativead.api.TUNativePrepareInfo

class DefaultToponNativeAdRenderer : ToponNativeAdRenderer {

    override fun createLayout(context: Context, style: ToponNativeAdStyle): ViewGroup {
        return LayoutInflater.from(context)
            .inflate(R.layout.layout_native_ad_topon, null, false) as ViewGroup
    }

    override fun bindData(adView: ViewGroup, material: TUNativeMaterial) {
        val ivIcon = adView.findViewById<ImageView>(R.id.iv_ad_icon)
        val tvTitle = adView.findViewById<TextView>(R.id.tv_ad_title)
        val tvDesc = adView.findViewById<TextView>(R.id.tv_ad_desc)
        val tvCta = adView.findViewById<TextView>(R.id.tv_ad_cta)

        tvTitle.text = material.title
        tvDesc.text = material.descriptionText
        tvCta.text = material.callToActionText

        material.iconImageUrl?.let { url ->
            loadImageInto(url, ivIcon)
        }
    }

    override fun createPrepareInfo(adView: ViewGroup): TUNativePrepareInfo {
        val tvTitle = adView.findViewById<TextView>(R.id.tv_ad_title)
        val tvDesc = adView.findViewById<TextView>(R.id.tv_ad_desc)
        val tvCta = adView.findViewById<TextView>(R.id.tv_ad_cta)
        val ivIcon = adView.findViewById<ImageView>(R.id.iv_ad_icon)

        return TUNativePrepareInfo().apply {
            titleView = tvTitle
            descView = tvDesc
            ctaView = tvCta
            iconView = ivIcon
        }
    }

    private fun loadImageInto(url: String, imageView: ImageView) {
        // Glide 按 View 尺寸解码并随 View 生命周期取消，避免裸线程和广告原图导致 OOM。
        Glide.with(imageView).load(url).override(96, 96).centerCrop().into(imageView)
    }
}
