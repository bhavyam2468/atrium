package com.atrium.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object HeroFlash {
    var onChange: (() -> Unit)? = null
    fun flash(before: HashMap<String, String>, tokens: Tokens) { onChange?.invoke() }
}

// ════════════════════════════════════════════════════════════════════════════
//  ROW TYPES
// ════════════════════════════════════════════════════════════════════════════

/** A navigation row: title, one-line note, badge. */
@SuppressLint("ViewConstructor")
class NavRow(
    context: Context,
    title: String,
    sub: String = "",
    badge: String? = null,
    chevron: Boolean = true,
    back: Boolean = false,
    onClick: () -> Unit
) : View(context) {

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val t = title
    private val s = sub
    private val b = badge
    private val showChevron = chevron
    private val isBack = back
    private var pressed = 0f
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        titlePaint.typeface = Type.ui(context, if (isBack) 500 else 480)
        titlePaint.textSize = dp(if (isBack) 15f else 17f)
        titlePaint.color = 0xFFFFFFFF.toInt()
        titlePaint.letterSpacing = if (isBack) 0.02f else -0.005f
        subPaint.typeface = Type.ui(context, 400)
        subPaint.textSize = dp(11f)
        subPaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.34f)
        subPaint.letterSpacing = 0.005f
        badgePaint.typeface = Type.mono(context, 400)
        badgePaint.textSize = dp(10.5f)
        badgePaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.3f)
        setOnClickListener { Haptics.soft(context); onClick() }
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val hgt = if (s.isEmpty()) dp(48f) else dp(58f)
        setMeasuredDimension(width, hgt.toInt())
    }

    override fun onDraw(c: Canvas) {
        val pad = dp(10f)
        val cy = height / 2f
        val baseY = if (s.isEmpty()) cy + dp(5f) else cy - dp(1.5f)
        titlePaint.alpha = (255 * (1f - pressed * 0.35f)).toInt().coerceIn(0, 255)
        c.drawText(t, pad, baseY, titlePaint)
        if (s.isNotEmpty()) {
            c.drawText(s, pad, baseY + dp(16f), subPaint)
        }
        if (b != null) {
            c.drawText(b, width - pad - badgePaint.measureText(b), cy + dp(4f), badgePaint)
        } else if (showChevron) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(0xFFFFFFFF.toInt(), 0.28f)
                strokeWidth = dp(1.1f)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            val x = width - pad - dp(4f)
            c.drawLine(x - dp(4f), cy - dp(4f), x, cy, p)
            c.drawLine(x, cy, x - dp(4f), cy + dp(4f), p)
        }
        if (pressed > 0.01f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(0xFFFFFFFF.toInt(), 0.05f * pressed)
            }
            c.drawRoundRect(RectF(0f, dp(2f), width.toFloat(), height - dp(2f)), dp(8f), dp(8f), p)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        Ramp(this.pressed, if (pressed) 1f else 0f, if (pressed) 80L else 220L, ExpoOut, {
            this.pressed = it; invalidate()
        }).start()
    }
}

/** A collapsible group heading. */
@SuppressLint("ViewConstructor")
class GroupHeader(
    context: Context,
    title: String,
    count: Int,
    open: Boolean,
    onToggle: () -> Unit
) : View(context) {
    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val cp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val t = title
    private val n = count
    private var isOpen = open
    private var spin = if (open) 1f else 0f
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        p.typeface = Type.ui(context, 560)
        p.textSize = dp(10.5f)
        p.color = withAlpha(0xFFFFFFFF.toInt(), if (open) 0.62f else 0.4f)
        p.letterSpacing = 0.18f
        cp.typeface = Type.mono(context, 400)
        cp.textSize = dp(9.5f)
        cp.color = withAlpha(0xFFFFFFFF.toInt(), 0.26f)
        setOnClickListener { onToggle() }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(36f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val pad = dp(10f)
        val cy = height / 2f
        // disclosure chevron
        val a = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(0xFFFFFFFF.toInt(), 0.34f)
            strokeWidth = dp(1.1f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        val cx = pad + dp(3f)
        c.save()
        c.rotate(if (isOpen) 90f else 0f, cx, cy)
        c.drawLine(cx - dp(2.5f), cy - dp(3.5f), cx + dp(1f), cy, a)
        c.drawLine(cx + dp(1f), cy, cx - dp(2.5f), cy + dp(3.5f), a)
        c.restore()
        c.drawText(t.uppercase(), pad + dp(14f), cy + dp(3.5f), p)
        val ns = n.toString()
        c.drawText(ns, width - pad - cp.measureText(ns), cy + dp(3.2f), cp)
    }
}

/** A plain action row. */
@SuppressLint("ViewConstructor")
class SimpleRow(
    context: Context,
    title: String,
    value: String,
    onClick: () -> Unit,
    isToggle: Boolean = false,
    valueText: String = "",
    danger: Boolean = false,
    toggleOn: Boolean = false
) : View(context) {
    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val vp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val t = title
    private val v = if (isToggle) valueText else value
    private val isSw = isToggle
    private val swOn = toggleOn
    private var pressed = 0f
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        p.typeface = Type.ui(context, 440)
        p.textSize = dp(15f)
        p.color = if (danger) 0xFFFF8A8A.toInt() else 0xFFF2F2F2.toInt()
        vp.typeface = Type.mono(context, 400)
        vp.textSize = dp(11f)
        vp.color = withAlpha(0xFFFFFFFF.toInt(), 0.34f)
        setOnClickListener { Haptics.soft(context); onClick() }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(46f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val pad = dp(10f)
        val cy = height / 2f
        p.alpha = (255 * (1f - pressed * 0.3f)).toInt().coerceIn(0, 255)
        c.drawText(t, pad, cy + dp(5f), p)
        if (isSw) {
            val sw = dp(30f); val sh = dp(17f)
            val x = width - pad - sw
            val y = cy - sh / 2f
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(0xFFFFFFFF.toInt(), if (swOn) 0.85f else 0.13f)
            }
            c.drawRoundRect(RectF(x, y, x + sw, y + sh), sh / 2f, sh / 2f, tp)
            tp.color = if (swOn) 0xFF0A0A0A.toInt() else withAlpha(0xFFFFFFFF.toInt(), 0.5f)
            c.drawCircle(if (swOn) x + sw - dp(8.5f) else x + dp(8.5f), cy, dp(6.2f), tp)
        } else if (v.isNotEmpty()) {
            c.drawText(v, width - pad - vp.measureText(v), cy + dp(4f), vp)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        Ramp(this.pressed, if (pressed) 1f else 0f, if (pressed) 80L else 200L, ExpoOut, {
            this.pressed = it; invalidate()
        }).start()
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  TOKEN ROW — the universal control
// ════════════════════════════════════════════════════════════════════════════

/**
 * One token, rendered as a title + a live value with a per-control affordance:
 *
 *   NUM    a filled track; drag horizontally. Fine control when you keep the
 *          finger near where you started; coarse when you move far.
 *   BOOL   a switch that slides.
 *   CHOICE a stepped strip you can tap through, drag along, or scrub by value.
 *   TEXT   a field that opens inline.
 */
@SuppressLint("ViewConstructor")
class TokenRow(
    context: Context,
    private val tokens: Tokens,
    private val token: Tk,
    private val onStructuralChange: () -> Unit
) : View(context) {

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val helpPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val optPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rowRect = RectF()

    private var value = ""
    private var valueShown = ""
    private var valueAlpha = 1f
    private var pressed = 0f
    private var focused = false
    private var shown = false

    private var dragStartX = 0f
    private var dragStartValue = 0f
    private var dragging = false
    private var accumulated = 0f
    private var lastCommit = 0L
    private var tapCandidate = false

    private val isExpanded: Boolean get() = focused && (token.kind == TkKind.CHOICE || token.kind == TkKind.TEXT)

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        titlePaint.typeface = Type.ui(context, 440)
        titlePaint.textSize = dp(15f)
        titlePaint.color = 0xFFEFEFEF.toInt()
        titlePaint.letterSpacing = -0.004f
        helpPaint.typeface = Type.ui(context, 400)
        helpPaint.textSize = dp(10.5f)
        helpPaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.3f)
        valuePaint.typeface = Type.mono(context, 400)
        valuePaint.textSize = dp(11f)
        valuePaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.55f)
        optPaint.typeface = Type.ui(context, 440)
        optPaint.textSize = dp(11f)
        trackPaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.1f)
        sync()
    }

    fun sync() {
        value = token.display(tokens.raw(token.key))
        if (valueShown.isEmpty()) valueShown = value
        val changed = tokens.isDefault(token.key)
        titlePaint.color = if (changed) 0xFFEFEFEF.toInt() else 0xFFFFFFFF.toInt()
        invalidate()
    }

    private fun rowHeight(): Float = when {
        isExpanded && token.kind == TkKind.CHOICE -> dp(46f) + choiceHeight()
        isExpanded && token.kind == TkKind.TEXT -> dp(88f)
        token.help.isEmpty() -> dp(44f)
        else -> dp(54f)
    }

    private fun choiceHeight(): Float {
        val perRow = 2
        val rows = (token.options.size + perRow - 1) / perRow
        return dp(9f) + rows * dp(30f) + dp(4f)
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), rowHeight().toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!shown) {
            shown = true
            alpha = 0f
            translationX = dp(8f)
            Ramp(0f, 1f, 320L, ExpoOut, { v ->
                alpha = v
                translationX = (1f - v) * dp(8f)
            }).start()
        }
    }

    // ── draw ──────────────────────────────────────────────────────────────

    override fun onDraw(c: Canvas) {
        val pad = dp(10f)
        val w = width.toFloat()
        val cy = dp(22f)

        titlePaint.alpha = (255 * (1f - pressed * 0.25f)).toInt().coerceIn(0, 255)
        val custom = !tokens.isDefault(token.key)
        if (custom) {
            // a 2px tick marks anything you have touched — a diff at a glance
            trackPaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.55f)
            c.drawCircle(pad * 0.35f, cy, dp(1.6f), trackPaint)
        }
        c.drawText(token.qual, pad + dp(7f), cy + dp(5f), titlePaint)

        if (token.help.isNotEmpty()) {
            c.drawText(token.help, pad + dp(7f), cy + dp(19f), helpPaint)
        }

        valuePaint.alpha = (255 * valueAlpha).toInt().coerceIn(0, 255)
        val v = valueShown
        c.drawText(v, w - pad - valuePaint.measureText(v), cy + dp(4f), valuePaint)

        when (token.kind) {
            TkKind.NUM -> drawTrack(c, pad, w, cy)
            TkKind.BOOL -> drawSwitch(c, pad, w, cy)
            TkKind.CHOICE -> if (isExpanded) drawChoices(c, pad, w)
            TkKind.TEXT -> if (isExpanded) drawField(c, pad, w)
        }

        if (pressed > 0.01f && !isExpanded) {
            trackPaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.04f * pressed)
            rowRect.set(0f, dp(2f), w, rowHeight() - dp(2f))
            c.drawRoundRect(rowRect, dp(8f), dp(8f), trackPaint)
        }
    }

    private fun drawTrack(c: Canvas, pad: Float, w: Float, cy: Float) {
        val trackY = cy + dp(21f)
        val left = pad + dp(7f)
        val right = w - pad
        val frac = ((tokens.f(token.key) - token.min) / (token.max - token.min)).coerceIn(0f, 1f)
        trackPaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.085f)
        c.drawRoundRect(RectF(left, trackY, right, trackY + dp(2f)), dp(1f), dp(1f), trackPaint)
        trackPaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.5f)
        c.drawRoundRect(RectF(left, trackY, left + (right - left) * frac, trackY + dp(2f)),
            dp(1f), dp(1f), trackPaint)
        val kx = left + (right - left) * frac
        trackPaint.color = 0xFFFFFFFF.toInt()
        c.drawCircle(kx, trackY + dp(1f), if (dragging) dp(4f) else dp(2.6f), trackPaint)
        trackPaint.color = 0xFFFFFFFF.toInt()
        c.drawCircle(kx, trackY + dp(1f), if (dragging) dp(1.4f) else dp(0.9f), trackPaint)
    }

    private fun drawSwitch(c: Canvas, pad: Float, w: Float, cy: Float) {
        val on = tokens.b(token.key)
        val sw = dp(34f)
        val sh = dp(19f)
        val x = w - pad - sw
        val y = cy - sh / 2f + dp(1f)
        trackPaint.color = withAlpha(0xFFFFFFFF.toInt(), if (on) 0.9f else 0.13f)
        c.drawRoundRect(RectF(x, y, x + sw, y + sh), sh / 2f, sh / 2f, trackPaint)
        val kx = if (on) x + sw - dp(9.5f) else x + dp(9.5f)
        trackPaint.color = if (on) 0xFF0A0A0A.toInt() else withAlpha(0xFFFFFFFF.toInt(), 0.55f)
        c.drawCircle(kx, y + sh / 2f, dp(7f), trackPaint)
    }

    private fun drawChoices(c: Canvas, pad: Float, w: Float) {
        val top = dp(46f)
        val perRow = 2
        val gapX = dp(6f)
        val gapY = dp(4f)
        val colW = (w - pad * 2 - gapX) / perRow
        val cur = tokens.raw(token.key)
        for ((i, o) in token.options.withIndex()) {
            val cx = pad + (i % perRow) * (colW + gapX)
            val cy = top + dp(6f) + (i / perRow) * (dp(28f) + gapY)
            val r = RectF(cx, cy, cx + colW, cy + dp(28f))
            val sel = o.id == cur
            trackPaint.color = if (sel) withAlpha(0xFFFFFFFF.toInt(), 0.11f) else withAlpha(0xFFFFFFFF.toInt(), 0.028f)
            c.drawRoundRect(r, dp(6f), dp(6f), trackPaint)
            optPaint.color = if (sel) 0xFFFFFFFF.toInt() else withAlpha(0xFFFFFFFF.toInt(), 0.42f)
            optPaint.typeface = if (sel) Type.ui(context, 520) else Type.ui(context, 400)
            c.drawText(o.label, r.left + dp(9f), r.centerY() + dp(4f), optPaint)
        }
    }

    private fun drawField(c: Canvas, pad: Float, w: Float) {
        val top = dp(50f)
        val r = RectF(pad + dp(7f), top, w - pad, top + dp(32f))
        trackPaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.06f)
        c.drawRoundRect(r, dp(6f), dp(6f), trackPaint)
        val raw = tokens.raw(token.key)
        optPaint.color = if (raw.isEmpty()) withAlpha(0xFFFFFFFF.toInt(), 0.28f) else 0xFFFFFFFF.toInt()
        optPaint.typeface = Type.mono(context, 400)
        optPaint.textSize = dp(12f)
        c.drawText(raw.ifEmpty { "e.g. " + (token.keywords.firstOrNull() ?: "") }, r.left + dp(10f),
            r.centerY() + dp(4f), optPaint)
        optPaint.textSize = dp(11f)
    }

    // ── touch ─────────────────────────────────────────────────────────────

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragStartX = e.x
                dragStartValue = tokens.f(token.key)
                dragging = false
                accumulated = 0f
                tapCandidate = true
                press(true)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = e.x - dragStartX
                if (!dragging && abs(dx) > dp(5f) && token.kind == TkKind.NUM) {
                    dragging = true
                    tokens.begin()
                    tapCandidate = false
                }
                if (dragging) {
                    // fine near the origin, coarse far away
                    val speed = if (abs(dx) < dp(60f)) 0.0022f else 0.0075f
                    val delta = dx * speed * (token.max - token.min)
                    val nv = (dragStartValue + delta).coerceIn(token.min, token.max)
                    tokens.set(token.key, nv)
                    markValue(token.display(Tk.trimNum(nv)))
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                press(false)
                if (dragging) {
                    tokens.end()
                    tokens.commit()
                    dragging = false
                } else if (tapCandidate) {
                    performTap()
                }
                tapCandidate = false
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                press(false)
                if (dragging) { tokens.end(); tokens.commit(); dragging = false }
                tapCandidate = false
            }
        }
        return true
    }

    private fun press(p: Boolean) {
        Ramp(pressed, if (p) 1f else 0f, if (p) 70L else 220L, ExpoOut, { v ->
            pressed = v; invalidate()
        }).start()
        if (p) Haptics.tick(context)
    }

    private fun performTap() {
        when (token.kind) {
            TkKind.BOOL -> {
                tokens.set(token.key, !tokens.b(token.key))
                tokens.commit()
                markValue(token.display(tokens.raw(token.key)))
                Haptics.soft(context)
            }
            TkKind.CHOICE -> {
                focused = !focused
                requestLayout()
                invalidate()
                Haptics.tick(context)
            }
            TkKind.NUM -> {
                // tap the track to jump
                val frac = ((dragStartX - dp(17f)) / (width - dp(30f))).coerceIn(0f, 1f)
                val nv = token.min + (token.max - token.min) * frac
                tokens.set(token.key, nv)
                tokens.commit()
                markValue(token.display(tokens.raw(token.key)))
            }
            TkKind.TEXT -> {
                openTextField()
            }
        }
    }

    private fun openTextField() {
        val et = android.widget.EditText(context).apply {
            setText(tokens.raw(token.key))
            setSelection(text.length)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(withAlpha(0xFFFFFFFF.toInt(), 0.3f))
            hint = token.help
            typeface = Type.mono(context, 400)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(0x00000000)
            setPadding(0, 0, 0, 0)
            setSingleLine()
        }
        val row = this
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(token.label)
            .setView(et)
            .setPositiveButton("Apply") { _, _ ->
                tokens.set(token.key, et.text.toString())
                tokens.commit()
                markValue(token.display(tokens.raw(token.key)))
                onStructuralChange()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun markValue(v: String) {
        if (v == valueShown) return
        valueAlpha = 0.25f
        valueShown = v
        invalidate()
        Ramp(0.25f, 1f, 260L, ExpoOut, { a -> valueAlpha = a; invalidate() }).start()
    }
}
