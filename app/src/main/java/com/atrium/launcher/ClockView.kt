package com.atrium.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ATRIUM · CLOCK
 * ────────────────────────────────────────────────────────────────────────────
 * A single view that can render a hundred-odd variants of "the time": sixteen
 * hour patterns × sixteen date patterns × six typefaces × four arrangements ×
 * nine anchors × three alignments × continuous weight, size, tracking, case,
 * opacity and tabular figures.
 *
 * It also *is* the control for its own appearance:
 *   tap     → next arrangement
 *   drag ↔  → scrub through time patterns
 *   drag ↕  → weight
 *   pinch    → scale
 *   top-left grip → free move, released it snaps to the nearest anchor
 */
@SuppressLint("ViewConstructor")
class ClockView(
    ctx: Context,
    private val tokens: Tokens
) : View(ctx), LiveTarget, RegionHost {

    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val divPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }

    private val timeSlot = Slot()
    private val dateSlot = Slot()

    private var face: Typeface = Typeface.SANS_SERIF
    private var timeSize = 0f
    private var dateSize = 0f
    private var tracking = 0f
    private var inkTime = -1
    private var inkDate = -1
    private var divider = " · "
    private var arrangement = "dateAbove"
    private var align = "start"
    private var anchor = "topStart"
    private var dx = 0f
    private var dy = 0f
    private var blink = false
    private var tabular = true
    private var caseMode = "none"

    private var baseSize = 96f
    private var timeSizeBase = 96f
    private var dateSizeBase = 32f
    /** Shrink-to-fit factor applied when a pattern is wider than the screen. */
    private var fit = 1f
    private var glowColor = -1
    private var blockW = 0f
    private var blockH = 0f

    /** Footprint of the clock block, in this view's coordinates. */
    val block = RectF()

    /** Live temporary offset while free-dragging (px). */
    private var freeX = 0f
    private var freeY = 0f
    private var freeActive = false

    private var blinkOn = true
    private var lastTick = 0L
    private var ticking = false
    private var colonPhase = 0f
    private var lastBlink = 0L

    var onLayoutChanged: (() -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) { ticking = false; return }
            val now = System.currentTimeMillis()
            update(true, now)
            if (blink) {
                val phaseOn = (now / 500L) % 2L == 0L
                if (phaseOn != blinkOn) { blinkOn = phaseOn; invalidate() }
            }
            // re-render at the next second boundary, or ~every second when idle
            val delay = 1000L - (now % 1000L)
            postDelayed(this, delay.coerceAtLeast(60L))
        }
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        refresh(false)
    }

    // ---- token → paint ---------------------------------------------------

    fun refresh(animate: Boolean) {
        val t = tokens
        visibility = if (t.b(K.clockOn)) VISIBLE else GONE
        if (visibility != VISIBLE) return
        val style = t.s(K.clockStyle)
        val weight = t.f(K.clockWeight).roundToInt() + t.f(K.typeWeightShift).roundToInt()
        val d = Density(t)
        face = resolveFace(t, style, weight)
        baseSize = context.sp(56f) * d.base
        timeSizeBase = baseSize * d.clock
        timeSize = timeSizeBase

        var trackingEm = t.f(K.clockTracking)
        // a monospaced or "wide" face already carries its own rhythm
        if (style == "wide") trackingEm += 0.04f
        if (style == "mono") trackingEm += 0.005f
        tracking = trackingEm

        dateSizeBase = (timeSizeBase * d.clockDate).coerceAtLeast(baseSize * 0.16f)
        dateSize = dateSizeBase * fit
        val p = Ink(t)
        val white = p.primary
        inkTime = withAlpha(white, t.f(K.clockOpacity))
        inkDate = withAlpha(white, t.f(K.clockOpacity) * t.f(K.clockDateOpacity))

        arrangement = t.s(K.clockArrangement)
        align = t.s(K.clockAlign)
        anchor = t.s(K.clockAnchor)
        dx = t.f(K.clockDx)
        dy = t.f(K.clockDy)
        blink = t.b(K.clockColonBlink)
        tabular = t.b(K.clockTabular)
        caseMode = t.s(K.clockCase)
        divider = Fmt.divider(t.s(K.clockDivider))

        timePaint.typeface = face
        timePaint.textSize = timeSizeBase * fit
        timePaint.letterSpacing = tracking
        timePaint.color = inkTime
        timePaint.isAntiAlias = true
        timePaint.isSubpixelText = true
        if (tabular) Type.tabular(timePaint) else Type.noKern(timePaint)

        datePaint.typeface = face
        datePaint.textSize = dateSizeBase * fit
        datePaint.letterSpacing = tracking * 1.5f
        datePaint.color = inkDate
        datePaint.isAntiAlias = true
        datePaint.isSubpixelText = true
        if (tabular) Type.tabular(datePaint)

        divPaint.typeface = face
        divPaint.textSize = dateSizeBase * fit
        divPaint.letterSpacing = tracking
        divPaint.color = inkDate
        divPaint.isAntiAlias = true

        // light: a soft shadow grounds the type, a glow lifts it
        val shadow = t.f(K.clockShadow).coerceIn(0f, 1f)
        val glow = t.f(K.clockGlow).coerceIn(0f, 1f)
        when {
            glow > 0.01f -> {
                // a coloured halo, brightest right at the glyph edge
                timePaint.setShadowLayer(
                    dpOf(context, 3f) + dpOf(context, 22f) * glow, 0f, 0f,
                    withAlpha(inkTime, 0.75f * glow)
                )
                datePaint.setShadowLayer(
                    dpOf(context, 2f) + dpOf(context, 12f) * glow, 0f, 0f,
                    withAlpha(inkDate, 0.7f * glow)
                )
            }
            shadow > 0.01f -> {
                timePaint.setShadowLayer(
                    dpOf(context, 2f) + dpOf(context, 10f) * shadow,
                    dpOf(context, 0.4f), dpOf(context, 1.6f),
                    0x99000000.toInt()
                )
                datePaint.setShadowLayer(
                    dpOf(context, 1f) + dpOf(context, 6f) * shadow, 0f, dpOf(context, 1f), 0x88000000.toInt()
                )
            }
            else -> {
                timePaint.clearShadowLayer()
                datePaint.clearShadowLayer()
            }
        }

        update(animate, System.currentTimeMillis())
        requestLayout()
        invalidate()
    }

    /**
     * Face resolution: an explicit face always wins; "auto" follows the
     * typeface preset, and a free-text family name lets the clock be set in any
     * font the device has installed.
     */
    private fun resolveFace(t: Tokens, style: String, weight: Int): Typeface {
        val custom = t.s(K.clockCustomFace).trim()
        if (custom.isNotEmpty()) {
            return try {
                Type.weighted(Typeface.create(custom, Typeface.NORMAL), weight)
            } catch (e: Exception) {
                Type.clockFace(context, style, weight)
            }
        }
        return when (t.s(K.clockFace)) {
            "inter" -> Type.weighted(Type.sans(context, 400), weight)
            "grotesk" -> Type.weighted(Type.grotesk(context, 400), weight)
            "serif" -> Type.serif(context)
            "serifItalic" -> Type.serifItalic(context)
            "mono" -> Type.weighted(Type.mono(context, 400), weight)
            "system" -> Type.weighted(Typeface.SANS_SERIF, weight)
            "systemSerif" -> Type.weighted(Typeface.SERIF, weight)
            "systemMono" -> Type.weighted(Typeface.MONOSPACE, weight)
            "systemCondensed" -> Type.weighted(Typeface.create("sans-serif-condensed", Typeface.NORMAL), weight)
            else -> Type.clockFace(context, style, weight)
        }
    }

    // ---- text ------------------------------------------------------------

    private fun caseOf(s: String): String = when (caseMode) {
        "upper" -> s.uppercase(Locale.getDefault())
        "lower" -> s.lowercase(Locale.getDefault())
        else -> s
    }

    private fun update(animate: Boolean, now: Long) {
        if (!tokens.b(K.clockOn)) return
        val tp = Fmt.patternFor(tokens)
        val dp = Fmt.datePatternFor(tokens)
        val timeText = caseOf(Fmt.format(tp, Date(now)))
        val dateText = caseOf(Fmt.format(dp, Date(now)))
        val smooth = animate && tokens.b(K.clockSecondsTick) && tokens.b(K.motionStagger)
        val before = timeSlot.cur.length + dateSlot.cur.length
        val beforeW = max(timePaint.measureText(timeSlot.cur), datePaint.measureText(dateSlot.cur))
        timeSlot.set(timeText, smooth)
        dateSlot.set(dateText, smooth)
        contentDescription = "$timeText, $dateText"
        val afterW = max(timePaint.measureText(timeSlot.cur), datePaint.measureText(dateSlot.cur))
        lastTick = now
        if (abs(afterW - beforeW) > 0.5f && align != "start") requestLayout()
    }

    private class Slot {
        var cur = ""
        var old = ""
        var p = 1f
        var charW: FloatArray = FloatArray(0)
        var oldW: FloatArray = FloatArray(0)

        fun set(next: String, animated: Boolean) {
            if (next == cur) return
            old = if (animated) cur else ""
            cur = next
            p = if (animated && old.isNotEmpty()) 0f else 1f
            if (p >= 1f) old = ""
        }

        fun isAnimating() = p < 1f
        fun step(dt: Float) { if (p < 1f) p = (p + dt).coerceAtMost(1f) }
    }

    // ---- measuring -------------------------------------------------------

    private fun lines(): List<Int> = when (arrangement) {
        "time" -> listOf(L_TIME)
        "dateOnly" -> listOf(L_DATE)
        "dateAbove" -> listOf(L_DATE, L_TIME)
        "dateBelow" -> listOf(L_TIME, L_DATE)
        "inline" -> listOf(L_INLINE)
        "inlineReverse" -> listOf(L_INLINE_R)
        else -> listOf(L_DATE, L_TIME)
    }

    private fun lineWidth(kind: Int): Float = when (kind) {
        L_TIME -> timePaint.measureText(timeSlot.cur)
        L_DATE -> datePaint.measureText(dateSlot.cur)
        L_INLINE -> datePaint.measureText(dateSlot.cur) + divPaint.measureText(divider) + timePaint.measureText(timeSlot.cur)
        L_INLINE_R -> timePaint.measureText(timeSlot.cur) + divPaint.measureText(divider) + datePaint.measureText(dateSlot.cur)
        else -> 0f
    }

    private fun lineHeight(kind: Int): Float = when (kind) {
        L_TIME -> timeSize * 1.02f
        L_DATE -> dateSize * 1.3f
        else -> max(timeSize, dateSize) * 1.08f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        measureBlock(w, h)
    }

    private fun measureBlock(w: Int, h: Int) {
        val ls = lines()
        val marginX = dpOf(context, 6f)
        // shrink-to-fit: no variant is ever clipped
        var fitNow = 1f
        val avail = (w - marginX * 2).coerceAtLeast(1f)
        for (attempt in 0 until 2) {
            var widest = 0f
            for (k in ls) widest = max(widest, lineWidth(k))
            if (widest <= avail || widest <= 0f) break
            fitNow = (fitNow * (avail / widest) * 0.995f).coerceIn(0.2f, 1f)
            applySizes(fitNow)
        }
        if (abs(fitNow - fit) > 0.0005f) { fit = fitNow; applySizes(fit) }
        var maxW = 0f
        var totalH = 0f
        for (k in ls) {
            maxW = max(maxW, lineWidth(k))
            totalH += lineHeight(k)
        }
        if (arrangement == "inline" || arrangement == "inlineReverse") totalH = lineHeight(ls[0])
        blockW = maxW
        blockH = totalH
        val pad = dpOf(context, if (ls.size > 1) 4f else 2f)
        val marginY = dpOf(context, 2f)
        val free = w - blockW - marginX * 2
        val freeH = h - blockH - marginY * 2
        val ox = dx * w
        val oy = dy * h

        val left = when {
            anchor.endsWith("Start") -> marginX
            anchor.endsWith("End") -> free - marginX
            else -> free / 2f
        } + ox + freeX
        val top = when {
            anchor.startsWith("top") -> marginY
            anchor.startsWith("bottom") -> freeH - marginY
            else -> freeH / 2f
        } + oy + freeY

        block.set(left.coerceAtLeast(-pad), top.coerceAtLeast(-pad), left + blockW + pad, top + blockH + pad)
        block.offset(0f, 0f)
    }

    private fun applySizes(f: Float) {
        timePaint.textSize = timeSizeBase * f
        datePaint.textSize = dateSizeBase * f
        divPaint.textSize = dateSizeBase * f
        timeSize = timeSizeBase * f
        dateSize = dateSizeBase * f
    }

    // ---- drawing ---------------------------------------------------------

    override fun onDraw(c: Canvas) {
        if (!tokens.b(K.clockOn)) return

        if (timeSlot.isAnimating()) {
            timeSlot.step(0.11f)
            dateSlot.step(0.11f)
            postInvalidateOnAnimation()
        }
        if (blink) {
            val now = System.currentTimeMillis()
            val phaseOn = (now / 500L) % 2L == 0L
            if (phaseOn != blinkOn) { blinkOn = phaseOn; postInvalidateOnAnimation() }
        }

        val ls = lines()
        var y = block.top + dpOf(context, 2f)
        for (kind in ls) {
            val lh = lineHeight(kind)
            val baseline = y + lh * 0.78f
            drawLine(c, kind, baseline)
            y += lh
        }
        drawGrips(c)
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Parallax nudge when the phone is tilted (only if the token is on). */
    fun nudge(dx: Float, dy: Float) {
        val depth = tokens.f(K.motionDepth).coerceIn(0f, 2f)
        translationX = dx * dpOf(context, 6f) * depth
        translationY = dy * dpOf(context, 6f) * depth
    }

    private fun drawLine(c: Canvas, kind: Int, baseline: Float) {
        val left = block.left
        when (kind) {
            L_TIME -> {
                val w = lineWidth(L_TIME)
                val x = alignedX(left, w)
                drawSlot(c, timeSlot, timePaint, x, baseline, skipColons = blink && !blinkOn)
            }
            L_DATE -> {
                val w = lineWidth(L_DATE)
                drawSlot(c, dateSlot, datePaint, alignedX(left, w), baseline, false)
            }
            L_INLINE, L_INLINE_R -> {
                val dw = datePaint.measureText(dateSlot.cur)
                val vw = timePaint.measureText(timeSlot.cur)
                val dvdW = divPaint.measureText(divider)
                val total = dw + dvdW + vw
                var x = alignedX(left, total)
                val dateFirst = kind == L_INLINE
                if (dateFirst) {
                    drawSlot(c, dateSlot, datePaint, x, baseline, false); x += dw
                    divPaint.alpha = 255
                    c.drawText(divider, x, baseline, divPaint); x += dvdW
                    drawSlot(c, timeSlot, timePaint, x, baseline, skipColons = blink && !blinkOn)
                } else {
                    drawSlot(c, timeSlot, timePaint, x, baseline, skipColons = blink && !blinkOn); x += vw
                    c.drawText(divider, x, baseline, divPaint); x += dvdW
                    drawSlot(c, dateSlot, datePaint, x, baseline, false)
                }
            }
        }
    }

    private fun alignedX(left: Float, lineW: Float): Float = when (align) {
        "center" -> left + (blockW - lineW) / 2f
        "end" -> left + (blockW - lineW)
        else -> left
    }

    /** Draw a slot with per-character cross-fade; identical glyphs never flicker. */
    private fun drawSlot(c: Canvas, slot: Slot, paint: TextPaint, x0: Float, baseline: Float, skipColons: Boolean) {
        val cur = slot.cur
        paint.alpha = 255
        if (slot.p >= 1f || slot.old.isEmpty()) {
            if (skipColons) {
                var x = x0
                for (i in cur.indices) {
                    val ch = cur[i]
                    if (ch != ':') {
                        paint.alpha = 255
                        c.drawText(cur, i, i + 1, x, baseline, paint)
                    }
                    x += paint.measureText(cur, i, i + 1)
                }
            } else {
                c.drawText(cur, 0, cur.length, x0, baseline, paint)
            }
            return
        }
        val old = slot.old
        val p = slot.p
        var x = x0
        // old layer: only where the glyph differs
        for (i in old.indices) {
            val same = i < cur.length && cur[i] == old[i]
            val ch = old[i]
            if (!same && ch != ' ' && !(skipColons && ch == ':')) {
                paint.alpha = ((1f - p) * 255).toInt().coerceIn(0, 255)
                c.drawText(old, i, i + 1, x, baseline, paint)
            }
            x += paint.measureText(old, i, i + 1)
        }
        // new layer
        x = x0
        for (i in cur.indices) {
            val same = i < old.length && old[i] == cur[i]
            val ch = cur[i]
            if (!(skipColons && ch == ':')) {
                paint.alpha = (if (same) 255 else (p * 255).toInt()).coerceIn(0, 255)
                c.drawText(cur, i, i + 1, x, baseline, paint)
            }
            x += paint.measureText(cur, i, i + 1)
        }
        paint.alpha = 255
    }

    // ---- animation hooks -------------------------------------------------

    /** Vertical parallax while the search surface is dragged. */
    fun parallax(f: Float) {
        val depth = tokens.f(K.motionDepth).coerceIn(0f, 2f)
        translationY = -f * dpOf(context, 26f) * depth
        translationX = -f * dpOf(context, 10f) * depth * 0.4f
        alpha = (1f - f * 0.5f).coerceIn(0f, 1f)
    }

    fun entrance(progress: Float, delayFrac: Float) {
        val mode = tokens.s(K.motionEntrance)
        val p = ((progress - delayFrac) / (1f - delayFrac)).coerceIn(0f, 1f)
        val e = ExpoOut.getInterpolation(p)
        when (mode) {
            "none" -> { alpha = 1f; translationY = 0f; scaleX = 1f; scaleY = 1f }
            "fade" -> { alpha = e; }
            "mask" -> {
                alpha = e
                translationY = (1f - e) * dpOf(context, 6f)
            }
            else -> {
                alpha = e
                translationY = (1f - e) * dpOf(context, 22f)
            }
        }
        if (mode != "none" && mode != "fade") {
            val s = 0.985f + 0.015f * e
            scaleX = s; scaleY = s
        } else {
            scaleX = 1f; scaleY = 1f
        }
    }

    // ---- live targets ----------------------------------------------------

    private fun scaleStep() = tokens.f(K.clockScale)
    override fun targets(): List<Target> = listOf(
        // free-move grip lives in the top-left of the footprint
        Target(
            id = "clock.move",
            label = "Clock · position",
            verb = "drag to place",
            handle = Target.HANDLE_DOT,
            value = { anchorLabel(tokens.s(K.clockAnchor)) },
            hint = { "Release to snap to the nearest anchor" },
            onScrubX = { d -> freeMove(d * width * 0.4f, 0f) },
            onScrubY = { d -> freeMove(0f, -d * height * 0.4f) },
            onPinch = null,
            ink = { inkTime }
        ),
        Target(
            id = "clock.scale",
            label = "Clock · scale",
            verb = "drag to resize",
            handle = Target.HANDLE_CORNER,
            value = { String.format(Locale.US, "%.2f×", scaleStep()) },
            hint = { "Drag the corner outward to grow" },
            onScrubX = { d -> nudgeScale(d) },
            onScrubY = { d -> nudgeScale(d * 0.85f) },
            onPinch = { f -> pinchScale(f) },
            ink = { inkTime }
        ),
        Target(
            id = "clock",
            label = "Clock",
            verb = "tap · drag",
            handle = Target.HANDLE_FRAME,
            value = {
                val fmt = tokens.s(K.clockFormat)
                val arr = arrangementLabel(tokens.s(K.clockArrangement))
                "$fmt · $arr"
            },
            hint = { "Hold, then drag ↔ for the next format · ↕ for weight" },
            onClick = null,
            onScrubX = { d -> scrubFormat(d) },
            onScrubY = { d -> scrubWeight(d) },
            onPinch = { f -> pinchScale(f) },
            ink = { inkTime }
        )
    )

    override fun regionOf(t: Target): RectF? = when (t.id) {
        "clock.move" -> RectF(block.left - dpOf(context, 10f), block.top - dpOf(context, 10f),
            block.left + dpOf(context, 14f), block.top + dpOf(context, 14f))
        "clock.scale" -> RectF(block.right - dpOf(context, 4f), block.bottom - dpOf(context, 4f),
            block.right + dpOf(context, 26f), block.bottom + dpOf(context, 26f))
        else -> RectF(block)
    }

    // ---- edits -----------------------------------------------------------

    private var scrubAccum = 0f
    private var weightAccum = 0f
    private var pinchRef = 1f

    private fun scrubFormat(delta: Float) {
        scrubAccum += delta
        if (abs(scrubAccum) < 0.12f) return
        val dir = if (scrubAccum > 0) 1 else -1
        scrubAccum = 0f
        val opts = Schema.byKey[K.clockFormat]!!.options
        val cur = opts.indexOfFirst { it.id == tokens.s(K.clockFormat) }.coerceAtLeast(0)
        val next = ((cur + dir) % opts.size + opts.size) % opts.size
        tokens.set(K.clockFormat, opts[next].id)
        tokens.set(K.clockFormatCustom, "")
        Haptics.tick(context)
        tokens.commit()
    }

    private fun scrubWeight(delta: Float) {
        weightAccum += delta
        if (abs(weightAccum) < 0.01f) return
        val step = weightAccum * 220f
        weightAccum = 0f
        val w = (tokens.f(K.clockWeight) + step).coerceIn(100f, 800f)
        tokens.set(K.clockWeight, (w / 10f).roundToInt() * 10f)
        tokens.commit()
    }

    private fun nudgeScale(delta: Float) {
        val s = (tokens.f(K.clockScale) * (1f + delta * 0.9f)).coerceIn(0.45f, 2.2f)
        tokens.set(K.clockScale, s)
        tokens.commit()
    }

    private fun pinchScale(factor: Float) {
        if (factor == 0f) return
        val s = (tokens.f(K.clockScale) * factor).coerceIn(0.45f, 2.2f)
        tokens.set(K.clockScale, s)
        tokens.commit()
    }

    private fun cycleArrangement() {
        val opts = Schema.byKey[K.clockArrangement]!!.options
        val cur = opts.indexOfFirst { it.id == tokens.s(K.clockArrangement) }.coerceAtLeast(0)
        tokens.set(K.clockArrangement, opts[(cur + 1) % opts.size].id)
        Haptics.tick(context)
        tokens.commit()
    }

    // ---- free positioning ------------------------------------------------

    private fun freeMove(ddx: Float, ddy: Float) {
        if (!freeActive) { freeActive = true; freeX = 0f; freeY = 0f }
        freeX += ddx
        freeY += ddy
        requestLayout()
        invalidate()
        onLayoutChanged?.invoke()
    }

    /**
     * Snap to the nearest anchor on release. We re-anchor first, then animate the
     * residual offset back to zero so the block glides into the grid instead of
     * jumping.
     */
    fun endFreeMove() {
        if (!freeActive) return
        freeActive = false
        val cx = block.centerX()
        val cy = block.centerY()
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val col = when {
            cx < w * 0.34f -> "Start"
            cx > w * 0.66f -> "End"
            else -> "Center"
        }
        val row = when {
            cy < h * 0.34f -> "top"
            cy > h * 0.66f -> "bottom"
            else -> "center"
        }
        val newAnchor = row + col
        tokens.begin()
        tokens.set(K.clockAnchor, newAnchor)
        // residual: where the block is now vs. where the new anchor puts it
        val resX = freeX
        val resY = freeY
        freeX = 0f
        freeY = 0f
        requestLayout()
        measureBlock(width, height)
        val baseDx = tokens.f(K.clockDx)
        val baseDy = tokens.f(K.clockDy)
        val needX = baseDx + resX / w
        val needY = baseDy + resY / h
        tokens.set(K.clockDx, needX)
        tokens.set(K.clockDy, needY)
        tokens.end()
        // animate the residual away
        val r = Ramp(1f, 0f, 380L, ExpoOut, { k ->
            tokens.begin()
            tokens.set(K.clockDx, baseDx + (needX - baseDx) * k)
            tokens.set(K.clockDy, baseDy + (needY - baseDy) * k)
            tokens.end()
        }, { tokens.commit() })
        r.start()
        Haptics.tick(context)
    }

    // ---- lifecycle -------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ticking) { ticking = true; postDelayed(ticker, 200L) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ticking = false
        removeCallbacks(ticker)
    }

    // ---- affordance: grips fade in while the deck is open ------------------

    private var hintAmt = 0f
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun hint(v: Float) {
        if (abs(hintAmt - v) < 0.005f) return
        hintAmt = v
        invalidate()
    }

    private fun drawGrips(c: Canvas) {
        if (hintAmt <= 0.01f) return
        val col = withAlpha(inkTime, 0.5f * hintAmt)
        val r = dpOf(context, 4.5f)
        val b = block
        // scale grip, bottom-right
        Grip.draw(c, b.right + r * 1.1f, b.bottom + r * 1.1f, r, col, Target.HANDLE_CORNER, 1f)
        // move grip, top-left
        Grip.draw(c, b.left - r * 1.1f, b.top - r * 1.1f, r, col, Target.HANDLE_DOT, 1f)
    }

    override fun hasOverlappingRendering() = false

    private companion object {
        const val L_TIME = 0
        const val L_DATE = 1
        const val L_INLINE = 2
        const val L_INLINE_R = 3
    }
}

internal fun anchorLabel(id: String): String = when (id) {
    "topStart" -> "Top left"
    "topCenter" -> "Top centre"
    "topEnd" -> "Top right"
    "centerStart" -> "Middle left"
    "center" -> "Middle centre"
    "centerEnd" -> "Middle right"
    "bottomStart" -> "Bottom left"
    "bottomCenter" -> "Bottom centre"
    "bottomEnd" -> "Bottom right"
    else -> id
}

internal fun arrangementLabel(id: String): String = when (id) {
    "time" -> "Time only"
    "dateOnly" -> "Date only"
    "dateAbove" -> "Date above"
    "dateBelow" -> "Date below"
    "inline" -> "Inline"
    "inlineReverse" -> "Inline reversed"
    else -> id
}

/**
 * A four-word haptic vocabulary, quiet by design. Everything routes through the
 * single "Haptics" token so it can be silenced in one place.
 */
internal object Haptics {
    private fun enabled(ctx: Context): Boolean = try {
        ctx.getSharedPreferences("atrium_tokens", Context.MODE_PRIVATE)
            .getBoolean("haptics", true)
    } catch (e: Exception) { false }

    private fun buzz(ctx: Context, ms: Long, amp: Int) {
        if (!enabled(ctx)) return
        try {
            val v = ctx.getSystemService(android.os.Vibrator::class.java) ?: return
            if (!v.hasVibrator()) return
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                v.vibrate(android.os.VibrationEffect.createOneShot(ms, amp.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (e: Exception) {}
    }

    /** A detent: something snapped into a new discrete state. */
    fun tick(ctx: Context) = buzz(ctx, 6L, 90)

    /** A touch: light acknowledgement on press. */
    fun soft(ctx: Context) = buzz(ctx, 4L, 50)

    /** A grab: the moment a hold is recognised. */
    fun hold(ctx: Context) = buzz(ctx, 14L, 140)

    /** A release: a preset landed / the surface changed wholesale. */
    fun land(ctx: Context) = buzz(ctx, 22L, 170)
}
