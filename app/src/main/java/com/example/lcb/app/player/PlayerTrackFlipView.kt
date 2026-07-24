package com.example.lcb.app.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.widget.NestedScrollView
import com.example.lcb.app.R

/**
 * 播放页内容舞台：正面保持唱片进度视图，背面使用整块可用区域展示歌词或歌曲介绍。
 * 组件自己管理翻转状态与动画取消，Activity 只传入内容，避免页面层堆积动画细节。
 */
class PlayerTrackFlipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val artworkFace = PlayerArtworkView(context)
    private val textFace = LayoutInflater.from(context).inflate(
        R.layout.view_player_track_text_face,
        this,
        false,
    )
    private val textLabel: TextView = textFace.findViewById(R.id.trackTextLabel)
    private val textContent: TextView = textFace.findViewById(R.id.trackTextContent)
    private val textScroll: NestedScrollView = textFace.findViewById(R.id.trackTextScroll)

    private var flipAnimator: AnimatorSet? = null
    private var animationToken = 0
    private var isAnimating = false
    private var textAvailable = false
    private var textVisible = false
    private var requestedTextVisible = false
    private var pendingTarget: Boolean? = null
    private var showTextDescription: CharSequence = context.getString(R.string.player_show_lyrics)

    /** 用户发起的正反面切换，交给 ViewModel 保存配置变更期间的状态。 */
    var onTextVisibilityChanged: ((Boolean) -> Unit)? = null

    /** Glide 只解码一份封面，背面纯由 View 绘制，不增加 Bitmap 内存。 */
    val artwork: AppCompatImageView
        get() = artworkFace.artwork

    init {
        // 外层占据完整内容区，只有正面封面保持设计稿的正方形尺寸。
        addView(artworkFace, LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(textFace, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val distance = resources.displayMetrics.density * CAMERA_DISTANCE_DP
        artworkFace.cameraDistance = distance
        textFace.cameraDistance = distance
        isFocusable = true

        artworkFace.setOnClickListener { performClick() }
        textFace.setOnClickListener { performClick() }
        textScroll.setOnClickListener { performClick() }
        textContent.setOnClickListener { performClick() }
        setFaceImmediately(showText = false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val topMargin = resources.getDimensionPixelSize(R.dimen.player_artwork_margin_top)
        val horizontalMargin = (ARTWORK_HORIZONTAL_MARGIN_DP * resources.displayMetrics.density).toInt()
        val maxArtworkSize = resources.getDimensionPixelSize(R.dimen.player_artwork_max_size)
        val artworkSize = minOf(
            maxArtworkSize,
            (measuredWidth - horizontalMargin * 2).coerceAtLeast(0),
            (measuredHeight - topMargin).coerceAtLeast(0),
        )
        (artworkFace.layoutParams as LayoutParams).apply {
            width = artworkSize
            height = artworkSize
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.topMargin = topMargin
        }
        // FrameLayout 先测量全屏文字面，再单独限制封面，两面不再共享正方形高度。
        artworkFace.measure(
            MeasureSpec.makeMeasureSpec(artworkSize, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(artworkSize, MeasureSpec.EXACTLY),
        )
    }

    /** 更新内容时保留 ViewModel 指定的面；切歌时 ViewModel 会先将它重置为封面。 */
    fun setTrackText(
        @StringRes labelRes: Int?,
        content: CharSequence?,
        showText: Boolean,
    ) {
        textAvailable = labelRes != null && !content.isNullOrBlank()
        if (textAvailable) {
            textLabel.setText(labelRes!!)
            textContent.text = content
            showTextDescription = context.getString(
                if (labelRes == R.string.player_lyrics) {
                    R.string.player_show_lyrics
                } else {
                    R.string.player_show_description
                },
            )
            textScroll.post { textScroll.scrollTo(0, 0) }
        } else {
            textContent.text = null
        }
        cancelAnimationAndSetFace(showText && textAvailable)
    }

    fun updatePlayback(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        artworkFace.updatePlayback(positionMs, durationMs, isPlaying)
    }

    fun stopProgressAnimation() {
        artworkFace.stopProgressAnimation()
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!textAvailable) return false
        requestFace(!requestedTextVisible, animate = true, notify = true)
        return true
    }

    override fun onDetachedFromWindow() {
        // 脱离窗口后立即释放 Animator 对 View 的引用，但保留用户最后请求的面。
        cancelAnimationAndSetFace(requestedTextVisible && textAvailable)
        super.onDetachedFromWindow()
    }

    private fun requestFace(showText: Boolean, animate: Boolean, notify: Boolean) {
        val target = showText && textAvailable
        requestedTextVisible = target
        if (notify) onTextVisibilityChanged?.invoke(target)

        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            cancelAnimationAndSetFace(target)
            return
        }
        if (isAnimating) {
            // 快速连点时记住最后意图，当前 330ms 翻转结束后再无缝执行。
            pendingTarget = target
            return
        }
        if (textVisible == target) {
            updateAccessibility()
            return
        }
        startFlip(target)
    }

    private fun startFlip(showText: Boolean) {
        val token = ++animationToken
        val outgoing = if (textVisible) textFace else artworkFace
        val incoming = if (showText) textFace else artworkFace
        val direction = if (showText) 1f else -1f

        isAnimating = true
        outgoing.visibility = View.VISIBLE
        outgoing.rotationY = 0f
        outgoing.alpha = 1f
        incoming.visibility = View.INVISIBLE
        incoming.rotationY = -direction * HALF_TURN_DEGREES
        incoming.alpha = MIDPOINT_ALPHA

        val firstHalf = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(outgoing, View.ROTATION_Y, 0f, direction * HALF_TURN_DEGREES),
                ObjectAnimator.ofFloat(outgoing, View.ALPHA, 1f, MIDPOINT_ALPHA),
            )
            duration = OUTGOING_DURATION_MS
            interpolator = OUTGOING_INTERPOLATOR
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != animationToken) return
                    outgoing.visibility = View.INVISIBLE
                    incoming.visibility = View.VISIBLE
                }
            })
        }
        val secondHalf = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(incoming, View.ROTATION_Y, -direction * HALF_TURN_DEGREES, 0f),
                ObjectAnimator.ofFloat(incoming, View.ALPHA, MIDPOINT_ALPHA, 1f),
            )
            duration = INCOMING_DURATION_MS
            interpolator = INCOMING_INTERPOLATOR
        }
        flipAnimator = AnimatorSet().apply {
            playSequentially(firstHalf, secondHalf)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != animationToken) return
                    flipAnimator = null
                    isAnimating = false
                    setFaceImmediately(showText)
                    val queuedTarget = pendingTarget.also { pendingTarget = null }
                    if (queuedTarget != null && queuedTarget != textVisible) startFlip(queuedTarget)
                }
            })
            start()
        }
    }

    private fun cancelAnimationAndSetFace(showText: Boolean) {
        animationToken++
        flipAnimator?.cancel()
        flipAnimator = null
        isAnimating = false
        pendingTarget = null
        requestedTextVisible = showText
        setFaceImmediately(showText)
    }

    private fun setFaceImmediately(showText: Boolean) {
        textVisible = showText && textAvailable
        artworkFace.visibility = if (textVisible) View.INVISIBLE else View.VISIBLE
        artworkFace.rotationY = 0f
        artworkFace.alpha = 1f
        textFace.visibility = if (textVisible) View.VISIBLE else View.INVISIBLE
        textFace.rotationY = 0f
        textFace.alpha = 1f
        updateAccessibility()
    }

    private fun updateAccessibility() {
        isClickable = textAvailable
        contentDescription = when {
            !textAvailable -> context.getString(R.string.player_artwork)
            textVisible -> context.getString(R.string.player_show_artwork)
            else -> showTextDescription
        }
    }

    private companion object {
        const val HALF_TURN_DEGREES = 90f
        const val MIDPOINT_ALPHA = 0.72f
        const val OUTGOING_DURATION_MS = 145L
        const val INCOMING_DURATION_MS = 185L
        const val CAMERA_DISTANCE_DP = 12_000f
        const val ARTWORK_HORIZONTAL_MARGIN_DP = 23f
        val OUTGOING_INTERPOLATOR = PathInterpolator(0.7f, 0f, 0.84f, 0f)
        val INCOMING_INTERPOLATOR = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    }
}
