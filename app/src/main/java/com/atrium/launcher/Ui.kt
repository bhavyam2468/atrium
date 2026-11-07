package com.atrium.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView

internal fun Context.dp(v: Float) = v * resources.displayMetrics.density
internal fun Context.dpInt(v: Float) = dp(v).toInt()
internal fun Context.spToPx(v: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** Monochrome ink ramp. Everything on screen is one of these. */
internal object Ink {
    val FULL = 0xFFFFFFFFL.toInt()
    val HIGH = 0xE8FFFFFFL.toInt()
    val MID = 0xABFFFFFFL.toInt()
    val LOW = 0x7DFFFFFFL.toInt()
    val FAINT = 0x5CFFFFFFL.toInt()
    val GHOST = 0x36FFFFFFL.toInt()
    val WISP = 0x1FFFFFFFL.toInt()
    val HAIR = 0x14FFFFFFL.toInt()

    fun mix(from: Long, to: Long, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF) / 255f
        val a = ch(from, 24) + (ch(to, 24) - ch(from, 24)) * k
        val r = ch(from, 16) + (ch(to, 16) - ch(from, 16)) * k
        val g = ch(from, 8) + (ch(to, 8) - ch(from, 8)) * k
        val b = ch(from, 0) + (ch(to, 0) - ch(from, 0)) * k
        return Color.argb((a * 255).toInt(), (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }
}

/**
 * Typography.
 *
 * Display + text: Inter Tight (variable, 100–900) — a tight, Swiss grotesque that
 * keeps its composure at poster sizes. Micro-labels and numbers: Geist Mono, used
 * only at tiny sizes with wide tracking, which reads as instrument labelling next
 * to the large text. Both are SIL OFL licensed; see assets/licenses.
 */
internal object Typo {
    private var sansBaseType: Typeface? = null
    private var monoBaseType: Typeface? = null
    private val sansWeights = HashMap<Int, Typeface>()
    private val monoWeights = HashMap<Int, Typeface>()

    private fun sansBase(ctx: Context): Typeface {
        sansBaseType?.let { return it }
        val t = runCatching { ctx.resources.getFont(R.font.inter_tight) }.getOrNull()
            ?: Typeface.create("sans-serif", Typeface.NORMAL)
        sansBaseType = t
        return t
    }

    private fun monoBase(ctx: Context): Typeface {
        monoBaseType?.let { return it }
        val t = runCatching { ctx.resources.getFont(R.font.geist_mono) }.getOrNull()
            ?: Typeface.create("monospace", Typeface.NORMAL)
        monoBaseType = t
        return t
    }

    fun sans(ctx: Context, weight: Int): Typeface {
        val key = weight.coerceIn(100, 900)
        sansWeights[key]?.let { return it }
        val base = sansBase(ctx)
        val t = runCatching { Typeface.create(base, key, false) }.getOrDefault(base)
        sansWeights[key] = t
        return t
    }

    fun mono(ctx: Context, weight: Int): Typeface {
        val key = weight.coerceIn(100, 900)
        monoWeights[key]?.let { return it }
        val base = monoBase(ctx)
        val t = runCatching { Typeface.create(base, key, false) }.getOrDefault(base)
        monoWeights[key] = t
        return t
    }
}

/** Global haptic switch, mirrored from LauncherSettings. */
internal object Haptics {
    var enabled = true
    fun tap(v: View) {
        if (enabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
    fun tick(v: View) {
        if (enabled) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

/** Single-line body text with an optional dimmed, smaller caption trailing it. */
internal fun caption(title: String, subtitle: String?, monoTypeface: Typeface? = null): CharSequence {
    if (subtitle.isNullOrBlank()) return title
    val sb = SpannableStringBuilder()
    sb.append(title)
    sb.append("\u2002\u2002")
    val start = sb.length
    sb.append(subtitle)
    val end = sb.length
    sb.setSpan(RelativeSizeSpan(0.56f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    sb.setSpan(ForegroundColorSpan(Ink.FAINT), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (monoTypeface != null) {
        sb.setSpan(TypefaceSpan(monoTypeface), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return sb
}

/** Bare text. Press feedback is a tiny scale + dim, no background anywhere. */
internal class Label(ctx: Context) : TextView(ctx) {
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (pressed) Haptics.tap(this)
        animate().cancel()
        animate().alpha(if (pressed) 0.5f else 1f)
            .scaleX(if (pressed) 0.975f else 1f)
            .scaleY(if (pressed) 0.975f else 1f)
            .setDuration(if (pressed) 90L else 260L)
            .setInterpolator(ExpoOut)
            .start()
    }
    override fun hasOverlappingRendering() = false
}

/** A settings row: dims on press instead of scaling, so long rows don't wobble. */
internal class PressRow(ctx: Context) : LinearLayout(ctx) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (pressed) Haptics.tap(this)
        animate().cancel()
        animate().alpha(if (pressed) 0.5f else 1f)
            .setDuration(if (pressed) 80L else 240L)
            .setInterpolator(ExpoOut)
            .start()
    }
    override fun hasOverlappingRendering() = false
}

/** Minimal hairline switch: white pill when on, hairline outline when off. */
internal class MiniSwitch(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.1f)
    }
    private val outer = RectF()
    private val inner = RectF()
    private var anim: ValueAnimator? = null
    private var t = 0f

    var checked = false
        set(value) {
            if (field == value) return
            field = value
            anim?.cancel()
            anim = ValueAnimator.ofFloat(t, if (value) 1f else 0f).apply {
                duration = 260
                interpolator = ExpoOut
                addUpdateListener {
                    t = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Sets state without animating — used when a row is first built. */
    fun snap(on: Boolean) {
        checked = on
        anim?.cancel()
        anim = null
        t = if (on) 1f else 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dpInt(42f), context.dpInt(26f))
    }

    override fun onDraw(canvas: Canvas) {
        val w = context.dp(34f)
        val h = context.dp(19f)
        val cx = width / 2f
        val cy = height / 2f
        val r = h / 2f
        outer.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        inner.set(outer)
        inner.inset(line.strokeWidth / 2f, line.strokeWidth / 2f)

        fill.color = Ink.mix(Ink.WISP.toLong() and 0xFFFFFFFFL, 0xFFFFFFFFL, t)
        canvas.drawRoundRect(outer, r, r, fill)

        line.color = Ink.mix(0x38FFFFFFL, 0xFFFFFFFFL, t)
        canvas.drawRoundRect(inner, r - line.strokeWidth / 2f, r - line.strokeWidth / 2f, line)

        val knobR = context.dp(5.2f)
        val knobX = outer.left + r + (outer.right - outer.left - 2 * r) * t
        fill.color = Ink.mix(0x5CFFFFFFL, 0xFF000000L, t)
        canvas.drawCircle(knobX, cy, knobR, fill)
    }
}

/** Two-stroke chevron that rotates on toggle. No glyph dependency. */
internal class Chevron(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Ink.LOW
    }
    private var rot = 0f
    private var anim: ValueAnimator? = null

    /** Extra rotation applied on top of open/closed, e.g. 180 for a left-pointing chevron. */
    var baseRotation = 0f
        set(value) {
            field = value
            invalidate()
        }

    var expanded = true
        set(value) {
            if (field == value) return
            field = value
            anim?.cancel()
            anim = ValueAnimator.ofFloat(rot, if (value) 0f else -90f).apply {
                duration = 300
                interpolator = ExpoOut
                addUpdateListener {
                    rot = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    /** Sets direction without animating — used when a row is first built. */
    fun snap(expanded: Boolean) {
        this.expanded = expanded
        anim?.cancel()
        anim = null
        rot = if (expanded) 0f else -90f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dpInt(18f), context.dpInt(18f))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val w = context.dp(3.6f)
        val h = context.dp(2.2f)
        canvas.save()
        canvas.rotate(rot + baseRotation, cx, cy)
        canvas.drawLine(cx - w, cy - h, cx, cy + h, p)
        canvas.drawLine(cx, cy + h, cx + w, cy - h, p)
        canvas.restore()
    }
}

internal fun TextView.type(
    sizeSp: Float,
    weight: Int = 400,
    color: Int = Ink.FULL,
    mono: Boolean = false,
    tracking: Float = 0f,
    caps: Boolean = false,
    singleLine: Boolean = true,
    align: Layout.Alignment? = null,
) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    typeface = if (mono) Typo.mono(context, weight) else Typo.sans(context, weight)
    setTextColor(color)
    letterSpacing = tracking
    isAllCaps = caps
    includeFontPadding = false
    if (singleLine) {
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
    } else {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    align?.let { textAlignment = it }
}

internal fun View.enter(delay: Long = 0L, duration: Long = 320L, dyDp: Float = 10f) {
    alpha = 0f
    translationY = context.dp(dyDp)
    animate().cancel()
    animate().alpha(1f)
        .translationY(0f)
        .setStartDelay(delay)
        .setDuration(duration)
        .setInterpolator(ExpoOut)
        .start()
}

/** cubic-bezier(0.16, 1, 0.3, 1) – quick start, very long soft landing. */
internal object ExpoOut : Interpolator {
    private val p = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    override fun getInterpolation(t: Float) = p.getInterpolation(t)
}

internal class VLayer(ctx: Context) : LinearLayout(ctx) {
    init { orientation = VERTICAL }
    override fun hasOverlappingRendering() = false
}

internal class Layer(ctx: Context) : android.widget.FrameLayout(ctx) {
    override fun hasOverlappingRendering() = false
}

/**
 * Vertical scrim used at the top of the result area so lists dissolve into the
 * display edge instead of being sliced by it.
 */
internal class Scrim(ctx: Context) : View(ctx) {
    private val p = Paint()

    init {
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        p.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.coerceAtLeast(1).toFloat(),
            intArrayOf(0xFF000000.toInt(), 0xCC000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}
