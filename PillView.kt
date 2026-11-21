package app.iosvolume.module

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import java.lang.reflect.Method

/**
 * iOS-style volume HUD: a vertical capsule on the left edge.
 *  - translucent neutral-gray (frosted) track + white fill rising from the bottom
 *  - speaker glyph at the bottom of the capsule (only while expanded)
 *  - "expanded" (wide) state that shrinks to a thin bar after a moment, like iOS 13+
 *
 * All sizes are in dp and are meant to be tuned in the companion object.
 */
class PillView(ctx: Context) : View(ctx) {

    companion object {
        // ---- Shape (dp) ----
        const val FULL_W = 30f      // expanded capsule width
        const val FULL_H = 150f     // expanded capsule height
        const val THIN_W = 6f       // thin-bar width
        const val THIN_H = 138f     // thin-bar height
        const val PAD = 8f          // gap between screen edge and capsule
        const val VIEW_W = PAD + FULL_W + 16f   // window width (extra = touch margin)
        const val VIEW_H = FULL_H + 24f         // window height

        // ---- Motion (ms) ----
        const val EXPAND_MS = 320L
        const val SHRINK_MS = 380L
        const val LEVEL_MS = 300L

        // ---- Material ----
        const val USE_BLUR = true   // frosted glass via SystemUI's blur drawable (falls back to flat gray)
        const val BLUR_RADIUS = 28f // dp
    }

    private val d = ctx.resources.displayMetrics.density

    // ---- public API used by VolumeInjector ----
    var level: Float = 0f
        private set
    var dragging: Boolean = false
        private set
    var onLevelChanged: ((Float) -> Unit)? = null
    var onActivity: (() -> Unit)? = null

    // ---- state ----
    private var shownLevel = 0f
    private var grow = 0f               // 0 = thin bar, 1 = expanded capsule
    private var levelAnim: ValueAnimator? = null
    private var growAnim: ValueAnimator? = null
    private var dragStartY = 0f
    private var dragStartLevel = 0f

    // ---- drawing helpers ----
    private val rect = RectF()
    private val fillRect = RectF()
    private val arcRect = RectF()
    private val clip = Path()
    private val speaker = Path()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var night = true
    private var blurDrawable: Drawable? = null
    private var blurSetCorner: Method? = null
    private var blurChecked = false

    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val easeInOut = PathInterpolator(0.3f, 0f, 0.2f, 1f)

    init {
        applyTheme()
    }

    // ------------------------------------------------------------------ level

    fun updateLevel(v: Float, animate: Boolean = true) {
        level = v.coerceIn(0f, 1f)
        if (animate && !dragging) {
            levelAnim?.cancel()
            levelAnim = ValueAnimator.ofFloat(shownLevel, level).apply {
                duration = LEVEL_MS
                interpolator = easeOut
                addUpdateListener {
                    shownLevel = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            levelAnim?.cancel()
            shownLevel = level
            invalidate()
        }
    }

    // ------------------------------------------------------- expand / shrink

    fun expand() = animateGrow(1f, EXPAND_MS, true)

    fun shrink() {
        if (!dragging) animateGrow(0f, SHRINK_MS, false)
    }

    private var growTarget = -1f

    private fun animateGrow(target: Float, ms: Long, bounce: Boolean) {
        // don't restart the same animation on every touch-move event
        if (target == growTarget && (growAnim?.isRunning == true || kotlin.math.abs(grow - target) < 0.001f)) return
        growTarget = target
        growAnim?.cancel()
        growAnim = ValueAnimator.ofFloat(grow, target).apply {
            duration = ms
            // slight spring on the way out, smooth ease when collapsing
            interpolator = if (bounce) android.view.animation.OvershootInterpolator(1.0f) else easeInOut
            addUpdateListener {
                grow = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ----------------------------------------------------------------- theme

    private fun applyTheme() {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        night = mode == Configuration.UI_MODE_NIGHT_YES
        updateTrackColor()
    }

    private fun updateTrackColor() {
        val blurred = blurDrawable != null
        trackPaint.color = when {
            night && blurred -> Color.argb(112, 62, 62, 68)
            night -> Color.argb(150, 58, 58, 64)
            blurred -> Color.argb(92, 118, 118, 128)
            else -> Color.argb(135, 112, 112, 122)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
        invalidate()
    }

    // ------------------------------------------------------------------ blur

    private fun ensureBlur() {
        if (!USE_BLUR || blurChecked || !isAttachedToWindow) return
        blurChecked = true
        try {
            val root = View::class.java.getMethod("getViewRootImpl").invoke(this)
            if (root == null) {
                blurChecked = false
                return
            }
            val drawable = root.javaClass.getMethod("createBackgroundBlurDrawable").invoke(root) as Drawable
            val cls = drawable.javaClass
            cls.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
                .invoke(drawable, (BLUR_RADIUS * d).toInt())
            try {
                cls.getMethod("setColor", Int::class.javaPrimitiveType).invoke(drawable, Color.TRANSPARENT)
            } catch (e: Throwable) {
            }
            blurSetCorner = try {
                cls.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
            } catch (e: Throwable) {
                null
            }
            blurDrawable = drawable
            updateTrackColor()
        } catch (e: Throwable) {
            blurDrawable = null
        }
    }

    // ------------------------------------------------------------------ draw

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        pivotX = PAD * d
        pivotY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        ensureBlur()

        val w = lerp(THIN_W, FULL_W, grow) * d
        val h = lerp(THIN_H, FULL_H, grow) * d
        val left = PAD * d
        val top = (height - h) / 2f
        rect.set(left, top, left + w, top + h)
        val r = w / 2f

        // 1) frosted background
        blurDrawable?.let { b ->
            b.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            try {
                blurSetCorner?.invoke(b, r)
            } catch (e: Throwable) {
            }
            b.draw(canvas)
        }
        canvas.drawRoundRect(rect, r, r, trackPaint)

        // 2) white fill, clipped to the capsule
        canvas.save()
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.clipPath(clip)
        val fillTop = rect.bottom - rect.height() * shownLevel.coerceIn(0f, 1f)
        fillRect.set(rect.left, fillTop, rect.right, rect.bottom)
        canvas.drawRect(fillRect, fillPaint)

        // 3) speaker glyph at the bottom (fades in while expanded)
        val g = grow.coerceIn(0f, 1f)
        val glyphAlpha = (g * g * g)
        if (glyphAlpha > 0.02f) {
            val u = w * 0.52f
            val cx = rect.centerX()
            val cy = rect.bottom - w * 0.64f
            val waves = when {
                shownLevel <= 0.001f -> 0
                shownLevel < 0.34f -> 1
                shownLevel < 0.67f -> 2
                else -> 3
            }
            val muted = shownLevel <= 0.001f
            // white glyph over the empty (gray) part ...
            drawGlyph(canvas, cx, cy, u, waves, muted, Color.argb((255 * glyphAlpha).toInt(), 255, 255, 255))
            // ... and dark gray glyph over the white fill
            canvas.save()
            canvas.clipRect(fillRect)
            drawGlyph(canvas, cx, cy, u, waves, muted, Color.argb((200 * glyphAlpha).toInt(), 60, 60, 67))
            canvas.restore()
        }
        canvas.restore()
    }

    /** SF-Symbols-like speaker: filled cone + up to 3 arcs, or a slash when muted. */
    private fun drawGlyph(c: Canvas, cx: Float, cy: Float, u: Float, waves: Int, muted: Boolean, color: Int) {
        bodyPaint.color = color
        bodyPaint.strokeWidth = 0.07f * u
        wavePaint.color = color
        wavePaint.strokeWidth = 0.085f * u

        speaker.reset()
        speaker.moveTo(cx - 0.50f * u, cy - 0.17f * u)
        speaker.lineTo(cx - 0.33f * u, cy - 0.17f * u)
        speaker.lineTo(cx - 0.10f * u, cy - 0.38f * u)
        speaker.lineTo(cx - 0.10f * u, cy + 0.38f * u)
        speaker.lineTo(cx - 0.33f * u, cy + 0.17f * u)
        speaker.lineTo(cx - 0.50f * u, cy + 0.17f * u)
        speaker.close()
        c.drawPath(speaker, bodyPaint)

        for (i in 0 until waves) {
            val rr = (0.26f + 0.16f * i) * u
            arcRect.set(cx - 0.10f * u - rr, cy - rr, cx - 0.10f * u + rr, cy + rr)
            c.drawArc(arcRect, -40f, 80f, false, wavePaint)
        }
        if (muted) {
            c.drawLine(cx - 0.02f * u, cy - 0.22f * u, cx + 0.44f * u, cy + 0.22f * u, wavePaint)
        }
    }

    // ----------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        onActivity?.invoke()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragStartY = e.y
                dragStartLevel = level
                parent?.requestDisallowInterceptTouchEvent(true)
                expand()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val span = FULL_H * d
                val v = (dragStartLevel + (dragStartY - e.y) / span).coerceIn(0f, 1f)
                updateLevel(v, false)
                onLevelChanged?.invoke(level)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        levelAnim?.cancel()
        growAnim?.cancel()
        blurDrawable = null
        blurChecked = false
        updateTrackColor()
    }
}
