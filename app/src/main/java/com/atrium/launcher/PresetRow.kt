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

// ════════════════════════════════════════════════════════════════════════════
//  PRESET ROW — a preset is shown as the screen it produces
// ════════════════════════════════════════════════════════════════════════════

/**
 * A preset rendered as a miniature of the home screen it creates: the clock
 * block at its anchor, in its arrangement and weight, with the app block laid
 * out underneath. You choose a starting point by looking at it, not by reading
 * its name.
 */
@SuppressLint("ViewConstructor")
class PresetRow(
    context: Context,
    private val tokens: Tokens,
    private val preset: Preset,
    onClick: () -> Unit
) : View(context) {

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val resolved: HashMap<String, String> = HashMap<String, String>(160).apply {
        for (t in Schema.all) put(t.key, t.def)
        putAll(preset.patch)
    }

    private fun v(key: String) = resolved[key] ?: ""
    private fun f(key: String) = v(key).toFloatOrNull() ?: 0f
    private fun b(key: String) = v(key) == "1"

    private val ink: Int by lazy {
        val tone = f(K.typeTone).coerceIn(0.55f, 1f)
        val sat = f(K.palSat)
        val warm = f(K.palWarm)
        if (sat <= 0.001f) {
            val x = (255 * tone).toInt()
            val r = (x + warm * 10f).toInt().coerceIn(0, 255)
            val g = (x + warm * 3f).toInt().coerceIn(0, 255)
            val bl = (x - warm * 12f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        } else {
            hsl(f(K.palHue), sat * 0.34f, 0.5f + 0.45f * tone)
        }
    }

    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Type.ui(context, 500)
        textSize = dp(14.5f)
        color = 0xFFF0F0F0.toInt()
        isSubpixelText = true
        letterSpacing = 0.004f
    }
    private val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Type.ui(context, 400)
        textSize = dp(10.5f)
        color = withAlpha(0xFFFFFFFF.toInt(), 0.3f)
        isSubpixelText = true
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var pressed = 0f

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        setOnClickListener { Haptics.soft(context); onClick() }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(64f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val pad = dp(10f)
        val gw = dp(31f)
        val gh = dp(44f)
        val gx = pad + dp(6f) * 0f
        val gy = (height - gh) / 2f
        drawGlyph(c, gx, gy, gw, gh)

        val tx = gx + gw + dp(13f)
        namePaint.alpha = (255 * (1f - pressed * 0.25f)).toInt().coerceIn(0, 255)
        c.drawText(preset.name, tx, height / 2f - dp(3f), namePaint)
        val note = ellipsize(notePaint, preset.note, width - tx - pad)
        c.drawText(note, tx, height / 2f + dp(12f), notePaint)

        if (pressed > 0.01f) {
            barPaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.05f * pressed)
            c.drawRoundRect(RectF(0f, dp(3f), width.toFloat(), height - dp(3f)), dp(8f), dp(8f), barPaint)
        }
    }

    private fun ellipsize(p: TextPaint, text: String, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var lo = 0
        var hi = text.length
        val dots = "…"
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (p.measureText(text, 0, mid) + p.measureText(dots) <= maxW) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo).trimEnd() + dots
    }

    /** The whole home screen, at 31 × 44 dp. */
    private fun drawGlyph(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val r = dp(3.5f)
        val rect = RectF(x, y, x + w, y + h)

        // surface
        when (v(K.bgMode)) {
            "solid" -> {
                barPaint.color = surfaceTint(0.16f)
                c.drawRoundRect(rect, r, r, barPaint)
            }
            "gradient" -> {
                val top = surfaceTint(0.26f)
                val bottom = surfaceTint(0.05f)
                barPaint.shader = android.graphics.LinearGradient(
                    x, y, x, y + h, top, bottom, Shader.TileMode.CLAMP
                )
                c.drawRoundRect(rect, r, r, barPaint)
                barPaint.shader = null
            }
            "aurora" -> {
                barPaint.color = surfaceTint(0.08f)
                c.drawRoundRect(rect, r, r, barPaint)
                val g = android.graphics.RadialGradient(
                    x + w * 0.45f, y + h * 0.32f, w * 0.75f,
                    intArrayOf(surfaceTint(0.34f), 0x00000000),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                barPaint.shader = g
                c.drawRoundRect(rect, r, r, barPaint)
                barPaint.shader = null
            }
            "wallpaper" -> {
                barPaint.color = surfaceTint(0.22f)
                c.drawRoundRect(rect, r, r, barPaint)
            }
            else -> {
                barPaint.color = 0xFF08080A.toInt()
                c.drawRoundRect(rect, r, r, barPaint)
            }
        }
        framePaint.color = withAlpha(ink, 0.16f)
        framePaint.strokeWidth = dp(0.8f)
        c.drawRoundRect(rect, r, r, framePaint)

        drawClockGlyph(c, rect)
        drawAppsGlyph(c, rect)
    }

    private fun surfaceTint(amount: Float): Int {
        val sat = f(K.palSat).coerceAtLeast(0.25f)
        val hue = if (f(K.palHue) == 0f) f(K.bgHue) else f(K.palHue)
        return hsl(hue, sat * 0.5f, amount)
    }

    private fun drawClockGlyph(c: Canvas, frame: RectF) {
        if (!b(K.clockOn)) return
        val pad = dp(4f)
        val scale = f(K.clockScale).coerceIn(0.45f, 2.2f)
        val timeH = dp(1.5f + scale * 1.7f)
        val dateH = dp(0.9f)
        val arrangement = v(K.clockArrangement)
        val align = v(K.clockAlign)
        val anchor = v(K.clockAnchor)
        val timeW = (frame.width() - pad * 2) * (0.34f + 0.3f * scale / 2.2f).coerceAtMost(0.86f)
        val dateW = timeW * 0.82f

        val stack = when (arrangement) {
            "time" -> timeH
            "dateOnly" -> dateH
            "inline", "inlineReverse" -> timeH
            else -> timeH + dateH + dp(1.6f)
        }
        val left = when {
            anchor.endsWith("Start") -> frame.left + pad
            anchor.endsWith("End") -> frame.right - pad - timeW
            else -> frame.centerX() - timeW / 2f
        }
        val top = when {
            anchor.startsWith("top") -> frame.top + pad + dp(1f)
            anchor.startsWith("bottom") -> frame.bottom - pad - stack
            else -> frame.centerY() - stack / 2f
        }
        val alpha = f(K.clockOpacity).coerceIn(0.2f, 1f) * f(K.typeTone).coerceIn(0.55f, 1f)
        barPaint.color = withAlpha(ink, alpha)

        fun bar(bx: Float, by: Float, bw: Float, bh: Float, a: Float) {
            barPaint.color = withAlpha(ink, alpha * a)
            c.drawRoundRect(RectF(bx, by, bx + bw, by + bh), bh / 2f, bh / 2f, barPaint)
        }

        fun aligned(w: Float): Float = when (align) {
            "center" -> frame.centerX() - w / 2f
            "end" -> frame.right - pad - w
            else -> frame.left + pad
        }

        val textW = (frame.width() - pad * 2) * 0.5f
        when (arrangement) {
            "time" -> bar(left, top, timeW, timeH, 1f)
            "dateOnly" -> bar(aligned(dateW), top, dateW, dateH, 0.6f)
            "dateAbove" -> {
                bar(aligned(dateW), top, dateW, dateH, 0.6f)
                bar(aligned(timeW), top + dateH + dp(1.6f), timeW, timeH, 1f)
            }
            "dateBelow" -> {
                bar(aligned(timeW), top, timeW, timeH, 1f)
                bar(aligned(dateW), top + timeH + dp(1.6f), dateW, dateH, 0.6f)
            }
            else -> {
                val inlineW = textW * 1.15f
                bar(aligned(inlineW), top, inlineW * 0.46f, timeH, 1f)
                bar(aligned(inlineW) + inlineW * 0.54f, top + timeH * 0.3f,
                    inlineW * 0.46f, dateH, 0.6f)
            }
        }
    }

    private fun drawAppsGlyph(c: Canvas, frame: RectF) {
        if (!b(K.pinsOn)) return
        val pad = dp(4f)
        val count = f(K.pinsCount).toInt().coerceIn(1, 12).coerceAtMost(5)
        val layout = v(K.pinsLayout)
        val align = v(K.pinsAlign)
        val anchor = v(K.pinsAnchor)
        val alpha = f(K.pinsOpacity).coerceIn(0.2f, 1f) * f(K.typeTone).coerceIn(0.55f, 1f)
        barPaint.color = withAlpha(ink, alpha * 0.9f)
        val dot = dp(2.1f)

        fun r(x: Float, y: Float, w: Float, h: Float) {
            c.drawRoundRect(RectF(x, y, x + w, y + h), h / 2f, h / 2f, barPaint)
        }

        val innerW = frame.width() - pad * 2
        when (layout) {
            "grid2", "grid3" -> {
                val cols = if (layout == "grid3") 3 else 2
                val cellW = innerW / cols
                val rows = (count + cols - 1) / cols
                val blockH = rows * dot * 3.4f
                val top = when {
                    anchor.startsWith("top") -> frame.top + frame.height() * 0.45f
                    anchor.startsWith("bottom") -> frame.bottom - pad - blockH
                    else -> frame.centerY() + dot * 1.5f
                }
                var i = 0
                for (ri in 0 until rows) for (ci in 0 until cols) {
                    if (i >= count) break
                    val cx = frame.left + pad + cellW * ci + cellW / 2f
                    val cy = top + ri * dot * 3.4f + dot
                    c.drawCircle(cx, cy, dot, barPaint)
                    i++
                }
            }
            "row", "inline" -> {
                val gap = dp(3.4f)
                val cw = ((innerW - gap * (count - 1)) / count).coerceAtLeast(dp(2f))
                val y = when {
                    anchor.startsWith("top") -> frame.top + frame.height() * 0.5f
                    anchor.startsWith("bottom") -> frame.bottom - pad - dot * 2
                    else -> frame.centerY() + dot * 2.5f
                }
                val totalW = cw * count + gap * (count - 1)
                val x0 = when (align) {
                    "center" -> frame.centerX() - totalW / 2f
                    "end" -> frame.right - pad - totalW
                    else -> frame.left + pad
                }
                for (i in 0 until count) r(x0 + i * (cw + gap), y, cw, dot * 1.5f)
            }
            else -> {
                val lineH = dot * 1.6f
                val gap = dot * 1.9f
                val blockH = count * lineH + (count - 1) * gap
                val top = when {
                    anchor.startsWith("top") -> frame.top + frame.height() * 0.42f
                    anchor.startsWith("bottom") -> frame.bottom - pad - blockH
                    else -> frame.centerY() + dot * 1.2f
                }
                for (i in 0 until count) {
                    val w = innerW * (0.52f - (i % 2) * 0.12f)
                    val x = when (align) {
                        "center" -> frame.centerX() - w / 2f
                        "end" -> frame.right - pad - w
                        else -> frame.left + pad
                    }
                    r(x, top + i * (lineH + gap), w, lineH)
                }
            }
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        Ramp(this.pressed, if (pressed) 1f else 0f, if (pressed) 80L else 220L, ExpoOut, {
            this.pressed = it; invalidate()
        }).start()
    }

    override fun hasOverlappingRendering() = false
}

// ════════════════════════════════════════════════════════════════════════════
//  DECK HEAD — the masthead follows the clock's typeface
// ════════════════════════════════════════════════════════════════════════════

/**
 * The deck's masthead is set in whatever face the user chose for the clock, at
 * whatever weight. The settings screen therefore looks like the home screen —
 * typography is not a preference buried in a list, it is visible in the chrome.
 */
@SuppressLint("ViewConstructor")
class DeckHead(context: Context, private val tokens: Tokens) : View(context) {

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val word = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val meta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)

    init { setWillNotDraw(false) }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(78f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val pad = dp(10f)
        val style = tokens.s(K.clockStyle)
        val weight = tokens.f(K.clockWeight).toInt().coerceIn(100, 800)
        word.typeface = Type.clockFace(context, style, weight)
        word.textSize = dp(30f) * tokens.f(K.typeScale).coerceIn(0.8f, 1.3f)
        word.letterSpacing = tokens.f(K.clockTracking).coerceIn(-0.06f, 0.24f)
        val ink = Ink(tokens).primary
        word.color = ink
        val case = tokens.s(K.clockCase)
        val text = when (case) {
            "upper" -> "SETTINGS"
            "lower" -> "settings"
            else -> "Settings"
        }
        c.drawText(text, pad, dp(44f), word)

        meta.typeface = Type.ui(context, 500)
        meta.textSize = dp(9.5f)
        meta.letterSpacing = 0.16f
        meta.color = withAlpha(ink, 0.34f)
        val changed = tokens.changedCount()
        val sub = "${Schema.all.size} CONTROLS · " + if (changed == 0) "UNTOUCHED" else "$changed CHANGED"
        c.drawText(sub, pad, dp(64f), meta)

        // a small bracket hints that this column lives off the right edge
        val cx = width - pad - dp(10f)
        val cy = dp(32f)
        line.color = withAlpha(ink, 0.24f)
        line.strokeWidth = dp(1.1f)
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx + dp(3f), cy - dp(4f), cx - dp(2f), cy, line)
        c.drawLine(cx - dp(2f), cy, cx + dp(3f), cy + dp(4f), line)
        line.style = Paint.Style.FILL

        line.color = withAlpha(ink, 0.12f)
        line.strokeWidth = dp(1f)
        c.drawLine(pad, dp(72f), width - pad, dp(72f), line)
    }
}
