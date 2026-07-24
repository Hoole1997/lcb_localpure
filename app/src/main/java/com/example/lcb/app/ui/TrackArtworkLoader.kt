package com.example.lcb.app.ui

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy

/**
 * 列表封面统一使用小图候选链和本地兜底；Glide 会按 View 尺寸解码，避免小列表项持有原图 Bitmap。
 */
object TrackArtworkLoader {
    fun load(view: ImageView, urls: List<String>, @DrawableRes fallbackRes: Int) {
        val imageLoader = Glide.with(view)
        val localFallback: RequestBuilder<Drawable> = imageLoader.load(fallbackRes).centerCrop()
        val request = urls.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
            .asReversed()
            .fold(localFallback) { fallback, url ->
                imageLoader
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerCrop()
                    .error(fallback)
            }
        request.placeholder(fallbackRes).dontAnimate().into(view)
    }

    fun clear(view: ImageView) {
        Glide.with(view).clear(view)
    }
}
