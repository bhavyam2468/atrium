package com.atrium.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun Context.dp(v: Float) = v * resources.displayMetrics.density

/** Bare text. Press feedback is a tiny scale + dim, no background anywhere. */
internal open class Label(ctx: Context) : TextView(ctx) {
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().alpha(if (pressed) 0.45f else 1f).scaleX(if (pressed) 0.97f else 1f)
            .scaleY(if (pressed) 0.97f else 1f).setDuration(if (pressed) 70 else 220)
            .setInterpolator(ExpoOut).start()
    }
    override fun hasOverlappingRendering() = false
}

/** A row container that dims exactly like Label when pressed. */
internal open class PressableRow(ctx: Context) : LinearLayout(ctx) {
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().alpha(if (pressed) 0.45f else 1f).scaleX(if (pressed) 0.985f else 1f)
            .scaleY(if (pressed) 0.985f else 1f).setDuration(if (pressed) 70 else 220)
            .setInterpolator(ExpoOut).start()
    }
    override fun hasOverlappingRendering() = false
}

internal class VLayer(ctx: Context) : LinearLayout(ctx) {
    init { orientation = VERTICAL }
    override fun hasOverlappingRendering() = false
}

internal class Layer(ctx: Context) : FrameLayout(ctx) {
    override fun hasOverlappingRendering() = false
}

/**
 * A ticking clock. Renders a SimpleDateFormat pattern and refreshes only as
 * often as the pattern needs (every second if it shows seconds, else every
 * minute, aligned to the wall clock). Ticks only while attached.
 */
internal class ClockText(ctx: Context) : TextView(ctx) {

    var pattern: String = "h:mm"
        set(v) {
            if (field == v) return
            field = v
            fmt = try { SimpleDateFormat(v, Locale.getDefault()) } catch (_: Exception) {
                SimpleDateFormat("h:mm", Locale.getDefault())
            }
            render()
        }

    var upper: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            render()
        }

    private var fmt = SimpleDateFormat(pattern, Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            render()
            val step = if (pattern.contains('s')) 1000L else 60_000L
            val now = System.currentTimeMillis()
            handler.postDelayed(this, step - now % step + 25)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tick)
    }

    private fun render() {
        val s = try { fmt.format(Date()) } catch (_: Exception) { "" }
        text = if (upper) s.uppercase(Locale.ROOT) else s
    }
}

/**
 * App icon rendered through a rounded-rect mask at the theme's size, shape
 * and tint. Bitmaps are pre-rendered once by AppIndex; this view only draws.
 */
internal class IconView(ctx: Context) : View(ctx) {

    var icon: BitmapDrawable? = null
        set(v) { field = v; invalidate() }

    var sizeDp: Float = 22f
        set(v) { field = v; requestLayout() }

    /** 0 circle · 1 squircle · 2 rounded · 3 square */
    var shape: Int = 0
        set(v) { field = v; invalidate() }

    var mono: Boolean = false
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val s = context.dp(sizeDp).toInt().coerceAtLeast(1)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val b = icon?.bitmap ?: return
        val w = width.toFloat()
        paint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        matrix.setScale(w / b.width, w / b.height)
        paint.shader.setLocalMatrix(matrix)
        paint.colorFilter = if (mono)
            PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) else null
        val r = w * when (shape) {
            1 -> 0.34f
            2 -> 0.18f
            3 -> 0.05f
            else -> 0.5f
        }
        canvas.drawRoundRect(0f, 0f, w, w, r, r, paint)
    }

    override fun hasOverlappingRendering() = false
}

/** cubic-bezier(0.16, 1, 0.3, 1) – quick start, very long soft landing. */
internal object ExpoOut : Interpolator {
    private val p = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    override fun getInterpolation(t: Float) = p.getInterpolation(t)
}

/** 1px hairline used as a divider between settings rows. */
internal fun hairline(ctx: Context, color: Int): View =
    View(ctx).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(-1, 1)
    }
