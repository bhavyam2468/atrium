package com.atrium.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * ATRIUM · HUD
 * ────────────────────────────────────────────────────────────────────────────
 * The one piece of chrome that appears while editing: a ring around the thing
 * you are holding, a label, the live value, and a one-line hint about the
 * gesture. It is a *readout*, never a panel — it tells you what your finger is
 * already doing instead of asking you to configure anything.
 */
@SuppressLint("ViewConstructor")
class HudView(ctx: Context) : View(ctx) {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
    }
    private val path = Path()

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private var label = ""
    private var value = ""
    private var hint = ""
    private var handle = Target.HANDLE_FRAME

    private var ringRect = RectF()
    private var ringTarget = RectF()
    private var ringRadius = 0f
    private var ringAlpha = 0f

    private var hudX = 0f
    private var hudY = 0f
    private var hudAlpha = 0f
    private var hudW = 0f
    private var hudH = 0f
    private var wantAlpha = 0f
    private var wantX = 0f
    private var wantY = 0f

    private var valueShown = ""
    private var valueAlpha = 1f

    private var reveal = 0f

    private var accent = 0xFFFFFFFF.toInt()

    var isLive = false
        private set

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
    }

    fun setPalette(ink: Int) {
        accent = ink
        invalidate()
    }

    fun show(hit: Resolved, px: Float, py: Float, regionInHud: RectF, radius: Float) {
        label = hit.target.label
        hint = hit.hint()
        handle = hit.target.handle
        value = hit.value()
        // first caption appears instantly; later changes cross-fade in the loop
        if (valueShown.isEmpty()) { valueShown = hit.value(); valueAlpha = 1f }
        ringTarget.set(regionInHud)
        // a stadium region (an app row) should be ringed as a stadium
        ringRadius = if (regionInHud.height() > 0f &&
            regionInHud.width() / regionInHud.height() > 4f) regionInHud.height() * 0.5f else radius
        if (!isLive) {
            isLive = true
            ringRect.set(ringTarget)
            reveal = 0f
            hudX = wantX
            hudY = wantY
            alpha = alpha.coerceAtLeast(0.55f)
        }
        val first = wantAlpha < 0.5f
        positionHud(px, py)
        if (first) { hudX = wantX; hudY = wantY }
        hudW = hudW.coerceAtLeast(1f)
        wantAlpha = 1f
        revealUp()
        kick()
    }

    fun setValue(v: String) {
        if (v == value) return
        value = v
        if (!isLive) return
        kick()
    }

    fun setHint(h: String) { hint = h; invalidate() }

    fun hide() {
        isLive = false
        wantAlpha = 0f
        kick()
    }

    fun hideNow() {
        isLive = false
        wantAlpha = 0f
        alpha = 0f
        reveal = 0f
        revealing = false
        invalidate()
    }

    private var revealing = false

    private fun revealUp() {
        if (revealing || reveal >= 0.99f) return
        revealing = true
        Ramp(reveal, 1f, 220L, ExpoOut, { v -> reveal = v; invalidate() }, { revealing = false }).start()
    }

    private fun positionHud(px: Float, py: Float) {
        measureHud()
        val margin = dp(14f)
        val w = hudW
        val h = hudH
        // beside the finger, flipping to the other side near an edge
        var x = px + dp(24f)
        var y = py - h - dp(16f)
        if (y < margin) y = py + dp(22f)
        if (x + w > width - margin) x = px - w - dp(20f)
        if (y + h > height - margin) y = py - h - dp(20f)
        x = x.coerceIn(margin, (width - w - margin).coerceAtLeast(margin))
        y = y.coerceIn(margin, (height - h - margin).coerceAtLeast(margin))
        wantX = x
        wantY = y
    }

    private val cb = object : Runnable {
        override fun run() {
            var busy = false
            // ring lerp
            val k = 0.34f
            if (abs(ringRect.left - ringTarget.left) > 0.4f || abs(ringRect.top - ringTarget.top) > 0.4f ||
                abs(ringRect.right - ringTarget.right) > 0.4f || abs(ringRect.bottom - ringTarget.bottom) > 0.4f) {
                ringRect.left += (ringTarget.left - ringRect.left) * k
                ringRect.top += (ringTarget.top - ringRect.top) * k
                ringRect.right += (ringTarget.right - ringRect.right) * k
                ringRect.bottom += (ringTarget.bottom - ringRect.bottom) * k
                busy = true
            }
            if (abs(hudX - wantX) > 0.4f || abs(hudY - wantY) > 0.4f) {
                hudX += (wantX - hudX) * 0.26f
                hudY += (wantY - hudY) * 0.26f
                busy = true
            }
            val targetAlpha = wantAlpha
            if (abs(alpha - targetAlpha) > 0.01f) {
                alpha += (targetAlpha - alpha) * (if (targetAlpha > alpha) 0.4f else 0.22f)
                busy = true
            }
            if (value != valueShown) {
                valueAlpha -= 0.22f
                if (valueAlpha <= 0f) { valueShown = value; valueAlpha = 0f }
                busy = true
            } else if (valueAlpha < 1f) {
                valueAlpha = min(1f, valueAlpha + 0.14f)
                busy = true
            }
            invalidate()
            if (busy || isLive || alpha > 0.01f || revealing || reveal < 0.999f) post(this)
        }
    }

    fun kick() {
        removeCallbacks(cb)
        post(cb)
    }

    // ---- drawing --------------------------------------------------------

    private fun fontPx(dpV: Float) = dpV * d

    override fun onDraw(c: Canvas) {
        if (alpha < 0.01f) return
        drawRing(c)
        drawHud(c)
    }

    private fun drawRing(c: Canvas) {
        if (reveal <= 0f || ringRadius.isNaN()) return
        val r = RectF(ringRect)
        val inset = dp(4f)
        r.inset(-inset, -inset)
        r.intersect(0f, 0f, width.toFloat(), height.toFloat())
        ring.clearShadowLayer()
        ring.color = accent
        ring.strokeWidth = dp(1.2f)
        ring.alpha = (172 * reveal * alpha).toInt().coerceIn(0, 255)
        // dashed marquee around the exact footprint
        path.reset()
        val rr = min(r.width(), r.height()) * 0.5f
        path.addRoundRect(r, (ringRadius + inset).coerceIn(0f, min(r.width(), r.height()) * 0.5f),
            (ringRadius + inset).coerceIn(0f, min(r.width(), r.height()) * 0.5f), Path.Direction.CW)
        val dash = dp(5f)
        ring.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dash, dash * 1.15f), 0f)
        c.drawPath(path, ring)
        ring.pathEffect = null

        // corner grips
        val gr = dp(5.5f)
        val col = accent
        Grip.draw(c, r.left, r.top, gr, col, Target.HANDLE_CORNER, reveal * alpha)
        c.save(); c.rotate(90f, r.right, r.top)
        Grip.draw(c, r.right, r.top, gr, col, Target.HANDLE_CORNER, reveal * alpha); c.restore()
        c.save(); c.rotate(180f, r.right, r.bottom)
        Grip.draw(c, r.right, r.bottom, gr, col, Target.HANDLE_CORNER, reveal * alpha); c.restore()
        c.save(); c.rotate(270f, r.left, r.bottom)
        Grip.draw(c, r.left, r.bottom, gr, col, Target.HANDLE_CORNER, reveal * alpha); c.restore()
    }

    private fun measureHud() {
        val padX = dp(13f)
        text.typeface = Type.ui(context, 500)
        text.textSize = fontPx(12.5f)
        text.letterSpacing = 0.01f
        val wLabel = text.measureText(label)
        text.typeface = Type.mono(context, 400)
        text.textSize = fontPx(11.5f)
        text.letterSpacing = 0f
        val wValue = text.measureText(valueShown)
        val wHint = if (hint.isNotEmpty()) {
            text.typeface = Type.ui(context, 400)
            text.textSize = fontPx(11f)
            text.measureText(hint)
        } else 0f
        val contentW = maxOf(wLabel + wValue + dp(26f), wHint)
        hudW = contentW + padX * 2
        hudH = if (hint.isEmpty()) dp(36f) else dp(56f)
    }

    private fun drawHud(c: Canvas) {
        if (alpha < 0.02f) return
        val padX = dp(13f)
        measureHud()

        text.typeface = Type.ui(context, 500)
        text.textSize = fontPx(12.5f)
        val wLabel = text.measureText(label)
        text.typeface = Type.mono(context, 400)
        text.textSize = fontPx(11.5f)
        val wValue = text.measureText(valueShown)
        val wHint = if (hint.isNotEmpty()) {
            text.typeface = Type.ui(context, 400)
            text.textSize = fontPx(11f)
            text.measureText(hint)
        } else 0f

        val contentW = maxOf(wLabel + wValue + dp(26f), wHint)
        hudW = contentW + padX * 2
        hudH = if (hint.isEmpty()) dp(36f) else dp(56f)

        val x = hudX
        val y = hudY
        val r = dp(11f)

        // glassy pill
        fill.color = 0xFF101012.toInt()
        fill.alpha = (238 * alpha).toInt().coerceIn(0, 255)
        val body = RectF(x, y, x + hudW, y + hudH)
        c.drawRoundRect(body, r, r, fill)

        // hairline
        ring.color = accent
        ring.strokeWidth = dp(1f)
        ring.alpha = (30 * alpha).toInt().coerceIn(0, 255)
        c.drawRoundRect(body, r, r, ring)

        // label
        text.typeface = Type.ui(context, 560)
        text.textSize = fontPx(12.5f)
        text.color = accent
        text.alpha = (235 * alpha * min(1f, reveal * 1.6f)).toInt().coerceIn(0, 255)
        text.letterSpacing = 0.01f
        c.drawText(label, x + padX, y + dp(24f), text)

        // value
        text.typeface = Type.mono(context, 400)
        text.textSize = fontPx(11.5f)
        text.letterSpacing = 0f
        text.alpha = (210 * alpha * valueAlpha).toInt().coerceIn(0, 255)
        c.drawText(valueShown, x + hudW - padX - wValue, y + dp(24f), text)

        // hint
        if (hint.isNotEmpty()) {
            text.typeface = Type.ui(context, 400)
            text.textSize = fontPx(11f)
            text.color = accent
            text.alpha = (140 * alpha * (1f - valueAlpha * 0.4f)).toInt().coerceIn(0, 255)
            c.drawText(hint, x + padX, y + dp(43f), text)
        }
    }

    override fun hasOverlappingRendering() = false
}

/**
 * A tiny radial gesture legend that orbits the HUD for a beat when you first
 * touch something adjustable, then dissolves. Teaches without a manual.
 */
@SuppressLint("ViewConstructor")
class CompassView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    private var prog = 0f
    private var label = ""
    private var dirX = ""
    private var dirY = ""
    private var accent = 0xFFFFFFFF.toInt()
    private var cx = 0f
    private var cy = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
    }

    fun setPalette(ink: Int) { accent = ink; invalidate() }

    fun show(x: Float, y: Float, xLabel: String, yLabel: String, ink: Int) {
        cx = x; cy = y; dirX = xLabel; dirY = yLabel; accent = ink
        prog = 0f
        animate().alpha(1f).setDuration(180L).start()
        invalidate()
        val r = Ramp(0f, 1f, 900L, ExpoOut, { v -> prog = v; invalidate() })
        r.start()
    }

    fun hide() {
        animate().alpha(0f).setDuration(200L).start()
    }

    override fun onDraw(c: Canvas) {
        if (alpha < 0.02f) return
        val dp = { v: Float -> v * d }
        val spread = dp(46f) * prog
        val a = (alpha * (1f - prog * 0.25f))

        p.color = accent
        p.strokeWidth = dp(1f)
        p.style = Paint.Style.STROKE

        // horizontal arms with arrowheads
        val armX = dp(16f) + spread
        p.alpha = (70 * a).toInt().coerceIn(0, 255)
        c.drawLine(cx - armX, cy, cx - dp(10f), cy, p)
        c.drawLine(cx + dp(10f), cy, cx + armX, cy, p)

        val armY = dp(13f) + spread * 0.8f
        p.alpha = (45 * a).toInt().coerceIn(0, 255)
        c.drawLine(cx, cy - armY, cx, cy - dp(10f), p)
        c.drawLine(cx, cy + dp(10f), cx, cy + armY, p)

        if (dirX.isNotEmpty()) {
            p.style = Paint.Style.FILL
            p.alpha = (110 * a).toInt().coerceIn(0, 255)
            c.drawCircle(cx - armX, cy, dp(1.6f), p)
            c.drawCircle(cx + armX, cy, dp(1.6f), p)
        }
    }
}
