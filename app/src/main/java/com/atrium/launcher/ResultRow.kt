package com.atrium.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * One row in the search surface. Rebuilt (not recycled) for clarity; the object
 * count is bounded by [K.listMaxRows] × visible columns.
 *
 * Rendering is entirely token-driven: type scale, alignment, icon presence and
 * size, label/subtitle pairing, row spacing, dimming, tracking.
 */
@SuppressLint("ViewConstructor")
class ResultRow(
    ctx: Context,
    private val tokens: Tokens,
    private val result: Result
) : View(ctx) {

    var onTap: ((View) -> Unit)? = null
    /** The row Enter would run — marked with a caret in the gutter. */
    var isCaret: Boolean = false
    /** Delay before the row rises in; negative disables the animation entirely. */
    var revealDelay = -1L

    private val primary = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val secondary = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }

    private var size = 0f
    private var label = ""
    private var sub: String? = null
    private var showIcon = false
    private var iconSize = 0f
    private var align = "center"
    private var ink = -1
    private var inkDim = -1
    private var rowGap = 0f
    private var tracking = 0f
    private var reveal = 1f
    private var pressedAmount = 0f
    private var icon: Drawable? = null

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        isSoundEffectsEnabled = false
        label = result.title
        sub = result.subtitle
        refresh()
        reveal = 1f
    }

    /** Called by the host once layout is known and the row should animate. */
    fun animateIn(delayMs: Long) {
        if (revealDelay < 0L) return
        reveal = 0f
        alpha = 0f
        translationY = dp(10f)
        postDelayed({
            Ramp(0f, 1f, 420L, ExpoOut, { v ->
                reveal = v
                alpha = v
                translationY = (1f - v) * dp(10f)
                invalidate()
            }).start()
        }, delayMs)
    }

    fun refresh() {
        val t = tokens
        val den = Density(t)
        size = context.sp(23f) * den.base * den.list
        showIcon = t.b(K.listIcons)
        iconSize = dp(t.f(K.listIconSize))
        align = t.s(K.listAlign)
        rowGap = dp(6f) * t.f(K.listRowGap)
        tracking = t.f(K.listTracking)
        val palette = Ink(t)
        ink = palette.primary
        inkDim = withAlpha(ink, t.f(K.listDim).coerceIn(0.1f, 1f))

        val content = t.s(K.listLabelStyle)
        val face: Typeface = Type.ui(context, 400)
        primary.typeface = face
        primary.textSize = size
        primary.letterSpacing = tracking
        primary.color = ink
        secondary.typeface = Type.ui(context, 400)
        secondary.textSize = size * 0.5f
        secondary.letterSpacing = tracking + 0.02f
        secondary.color = withAlpha(ink, 0.42f)

        label = when (content) {
            "subOnly" -> result.subtitle ?: result.title
            else -> result.title
        }
        sub = if (content == "labelSub") result.subtitle else null
        setPadding(0, rowGap.toInt(), 0, rowGap.toInt())
        requestLayout()
        invalidate()
    }

    fun setPalette(inkColor: Int, dim: Int) {
        ink = inkColor
        inkDim = dim
        primary.color = inkColor
        invalidate()
    }

    fun setCurrent() {
        // handled by alpha at the container level
    }

    private fun hasIcon(): Boolean = showIcon && icon != null

    private fun contentWidth(): Float {
        var w = primary.measureText(label)
        if (hasIcon()) w += iconSize + dp(14f)
        return w
    }

    override fun onMeasure(w: Int, h: Int) {
        val widthSize = MeasureSpec.getSize(w)
        val hgt = (size * 1.34f + rowGap * 2f + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(widthSize, hgt)
        if (icon == null && showIcon) icon = loadIcon()
    }

    private fun loadIcon(): Drawable? = null // filled by the host when available

    fun setIcon(dr: Drawable?) {
        icon = dr
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val a = min(1f, reveal) * (1f - pressedAmount * 0.5f)
        val w = width.toFloat()
        val mid = height / 2f
        var totalW = contentWidth()
        val left = when (align) {
            "start" -> dp(2f)
            "end" -> w - totalW - dp(2f)
            else -> (w - totalW) / 2f
        }
        var x = left
        val ic = icon
        if (hasIcon() && ic != null) {
            val r = RectF(x, mid - iconSize / 2f, x + iconSize, mid + iconSize / 2f)
            ic.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
            ic.alpha = (255 * a).toInt().coerceIn(0, 255)
            ic.draw(c)
            x += iconSize + dp(14f)
        }
        primary.alpha = (255 * a).toInt().coerceIn(0, 255)
        c.drawText(label, x, mid + primary.textSize * 0.34f - (if (sub != null) dp(6f) else 0f), primary)
        val s = sub
        if (s != null) {
            secondary.alpha = (200 * a).toInt().coerceIn(0, 255)
            c.drawText(s, x, mid + primary.textSize * 0.34f + dp(15f), secondary)
        }
        if (isCaret) {
            val cp = Paint(Paint.ANTI_ALIAS_FLAG)
            cp.color = withAlpha(ink, 0.4f * a)
            val cy = mid - size * 0.2f
            val cx = (left - dp(9f)).coerceAtLeast(dp(1f))
            cp.strokeWidth = dp(1.2f)
            cp.strokeCap = Paint.Cap.ROUND
            c.drawLine(cx, cy - dp(3.4f), cx + dp(3f), cy, cp)
            c.drawLine(cx + dp(3f), cy, cx - dp(0f), cy + dp(3.4f), cp)
        }

        // press: a thin underline grows in
        if (pressedAmount > 0.01f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = withAlpha(ink, 0.5f * pressedAmount)
            p.strokeWidth = dp(1f)
            c.drawLine(left, mid + size * 0.62f, left + totalW, mid + size * 0.62f, p)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().cancel()
        animate().alpha(if (pressed) 1f else 1f).setDuration(0L).start()
        Ramp(pressedAmount, if (pressed) 1f else 0f, if (pressed) 90L else 260L, ExpoOut, { v ->
            pressedAmount = v
            scaleX = 1f - v * 0.012f
            scaleY = 1f - v * 0.012f
            invalidate()
        }).start()
    }

    /** Invoked by the host once it has decided a gesture was a tap. */
    fun tap() {
        Haptics.soft(context)
        onTap?.invoke(this)
    }

    override fun performClick(): Boolean {
        tap()
        return true
    }

    override fun hasOverlappingRendering() = false
}
