package eu.kanade.tachiyomi.ui.reader.viewer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.guided.NormalizedRect
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import okio.BufferedSource

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private var pageView: View? = null

    private var config: Config? = null

    private var pendingGuidedRegion: NormalizedRect? = null

    private var guidedRegionOverlay: GuidedRegionOverlay? = null

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                if (!applyPendingGuidedRegion()) landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            if (!applyPendingGuidedRegion()) landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        if (
            config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun recycle() = pageView?.let {
        when (it) {
            is SubsamplingScaleImageView -> it.recycle()
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    fun showGuidedRegion(region: NormalizedRect, duration: Int = GUIDED_OVERLAY_DURATION) {
        pendingGuidedRegion = region
        val view = pageView as? SubsamplingScaleImageView ?: return
        if (!view.isReady) return

        pendingGuidedRegion = null
        view.setScaleAndCenter(view.minScale, PointF(view.sWidth / 2f, view.sHeight / 2f))
        getOrCreateGuidedRegionOverlay(view).show(region, duration.getSystemScaledDuration())
    }

    protected fun queueGuidedRegion(region: NormalizedRect) {
        pendingGuidedRegion = region
    }

    fun hideGuidedRegion(duration: Int = GUIDED_OVERLAY_DURATION) {
        pendingGuidedRegion = null
        guidedRegionOverlay?.hide(duration.getSystemScaledDuration())
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun SubsamplingScaleImageView.applyPendingGuidedRegion(): Boolean {
        val region = pendingGuidedRegion ?: return false
        showGuidedRegion(region, duration = 1)
        return true
    }

    private fun getOrCreateGuidedRegionOverlay(source: SubsamplingScaleImageView): GuidedRegionOverlay {
        val overlay = guidedRegionOverlay
            ?.takeIf { it.source === source }
            ?: GuidedRegionOverlay(context, source).also {
                guidedRegionOverlay?.let(::removeView)
                guidedRegionOverlay = it
                addView(it, MATCH_PARENT, MATCH_PARENT)
            }
        overlay.bringToFront()
        return overlay
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        guidedRegionOverlay?.invalidate()
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        guidedRegionOverlay?.invalidate()
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (!applyPendingGuidedRegion() && isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                if (!isWebtoon) {
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            onImageLoadError(result.throwable)
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F
private const val GUIDED_OVERLAY_DURATION = 300
private const val GUIDED_REGION_PADDING = 0.18f
private const val GUIDED_OVERLAY_MAX_SCALE = 2.4f
private const val GUIDED_OVERLAY_WIDTH_FRACTION = 0.88f
private const val GUIDED_OVERLAY_HEIGHT_FRACTION = 0.62f
private const val GUIDED_OVERLAY_EDGE_DP = 12f
private const val GUIDED_OVERLAY_CORNER_DP = 12f
private const val GUIDED_OVERLAY_BORDER_DP = 2f
private const val GUIDED_OVERLAY_SHADOW_DP = 8f

/**
 * Draws a second, enlarged copy of a speech bubble over the page while keeping the whole page
 * visible underneath. The copy grows from the bubble's original position, so hiding it naturally
 * returns the bubble to its place on the page.
 */
private class GuidedRegionOverlay(
    context: Context,
    val source: SubsamplingScaleImageView,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(GUIDED_OVERLAY_SHADOW_DP * density, 0f, GUIDED_OVERLAY_BORDER_DP * density, Color.BLACK)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = GUIDED_OVERLAY_BORDER_DP * density
    }
    private val animationInterpolator = DecelerateInterpolator()
    private val clippingPath = Path()

    private var region: NormalizedRect? = null
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var animationGeneration = 0

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    fun show(newRegion: NormalizedRect, duration: Int) {
        val generation = ++animationGeneration
        animator?.cancel()

        if (region != null && progress > 0f) {
            animateTo(0f, duration / 2) {
                if (generation != animationGeneration) return@animateTo
                region = newRegion
                animateTo(1f, duration)
            }
        } else {
            region = newRegion
            animateTo(1f, duration)
        }
    }

    fun hide(duration: Int) {
        val generation = ++animationGeneration
        animator?.cancel()
        if (region == null) return
        animateTo(0f, duration) {
            if (generation == animationGeneration) region = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentRegion = region?.expanded(GUIDED_REGION_PADDING) ?: return
        if (!source.isReady || progress <= 0f) return

        val sourceRect = sourceRectInView(currentRegion) ?: return
        val targetRect = targetRect(sourceRect)
        val drawnRect = interpolate(sourceRect, targetRect, progress)
        val cornerRadius = GUIDED_OVERLAY_CORNER_DP * density * progress

        canvas.drawRoundRect(drawnRect, cornerRadius, cornerRadius, backgroundPaint)
        val saveCount = canvas.save()
        clippingPath.reset()
        clippingPath.addRoundRect(drawnRect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clippingPath)
        canvas.translate(drawnRect.left, drawnRect.top)
        canvas.scale(drawnRect.width() / sourceRect.width(), drawnRect.height() / sourceRect.height())
        canvas.translate(-sourceRect.left, -sourceRect.top)
        source.draw(canvas)
        canvas.restoreToCount(saveCount)
        borderPaint.alpha = (progress * MAX_ALPHA).toInt()
        canvas.drawRoundRect(drawnRect, cornerRadius, cornerRadius, borderPaint)
    }

    private fun sourceRectInView(region: NormalizedRect): RectF? {
        val topLeft = source.sourceToViewCoord(region.left * source.sWidth, region.top * source.sHeight) ?: return null
        val bottomRight =
            source.sourceToViewCoord(region.right * source.sWidth, region.bottom * source.sHeight) ?: return null
        return RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
            .takeIf { it.width() > 0f && it.height() > 0f }
    }

    private fun targetRect(sourceRect: RectF): RectF {
        val edge = GUIDED_OVERLAY_EDGE_DP * density
        val availableWidth = width - edge * 2
        val availableHeight = height - edge * 2
        val scale = minOf(
            GUIDED_OVERLAY_MAX_SCALE,
            availableWidth * GUIDED_OVERLAY_WIDTH_FRACTION / sourceRect.width(),
            availableHeight * GUIDED_OVERLAY_HEIGHT_FRACTION / sourceRect.height(),
        ).coerceAtLeast(1f)

        val targetWidth = sourceRect.width() * scale
        val targetHeight = sourceRect.height() * scale
        val left = (sourceRect.centerX() - targetWidth / 2)
            .coerceIn(edge, (width - edge - targetWidth).coerceAtLeast(edge))
        val top = (sourceRect.centerY() - targetHeight / 2)
            .coerceIn(edge, (height - edge - targetHeight).coerceAtLeast(edge))
        return RectF(left, top, left + targetWidth, top + targetHeight)
    }

    private fun animateTo(target: Float, duration: Int, onEnd: (() -> Unit)? = null) {
        animator = ValueAnimator.ofFloat(progress, target).apply {
            this.duration = duration.toLong()
            interpolator = animationInterpolator
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            if (onEnd != null) {
                doOnAnimationEnd(onEnd)
            }
            start()
        }
    }

    private fun ValueAnimator.doOnAnimationEnd(block: () -> Unit) {
        addListener(
            object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = block()
            },
        )
    }

    private fun interpolate(start: RectF, end: RectF, fraction: Float): RectF {
        return RectF(
            start.left + (end.left - start.left) * fraction,
            start.top + (end.top - start.top) * fraction,
            start.right + (end.right - start.right) * fraction,
            start.bottom + (end.bottom - start.bottom) * fraction,
        )
    }

    private companion object {
        const val MAX_ALPHA = 255
    }
}
