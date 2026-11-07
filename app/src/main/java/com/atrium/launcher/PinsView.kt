package com.atrium.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ATRIUM · APPS
 * ────────────────────────────────────────────────────────────────────────────
 * The pinned app block. Stack, wrap, grid or single line; icons on or off;
 * monograms, numbers, brackets or plain names; nine anchors; continuous size,
 * weight, tracking, spacing and opacity.
 *
 * The block is also its own control:
 *   tap a name → launch
 *   long-press → hand the deck to this block's settings
 *   drag ↔     → alignment
 *   drag ↕     → spacing
 *   pinch      → type scale
 */
@SuppressLint("ViewConstructor")
class PinsView(
    ctx: Context,
    private val tokens: Tokens,
    private val index: AppIndex
) : View(ctx), LiveTarget, RegionHost {

    class Cell(
        var entry: AppEntry,
        var x: Float = 0f,
        var y: Float = 0f,
        var w: Float = 0f,
        var h: Float = 0f,
        var labelW: Float = 0f,
        var ordinal: Int = 0
    )

    private val label = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val tiny = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }

    private val cells = ArrayList<Cell>(16)
    private val icons = HashMap<String, Drawable?>(32)

    private var face: Typeface = Typeface.SANS_SERIF
    private var textSize = 34f
    private var weight = 400
    private var tracking = 0f

    private var layoutMode = "list"
    private var align = "start"
    private var anchor = "center"
    private var showIcons = false
    private var iconSize = 0f
    private var iconStyle = "mono"
    private var showLabels = true
    private var gap = 0f
    private var opacity = 1f
    private var caseMode = "none"
    private var styleMode = "plain"
    private var dx = 0f
    private var dy = 0f

    private var fountain = "none"
    private var revealOn = false
    private var iconBreathe = false
    private var ink = -1
    private var inkDim = -1

    private var block = RectF()
    private var entries: List<AppEntry> = emptyList()

    var onLaunch: ((AppEntry, View) -> Unit)? = null
    var onLongPress: ((AppEntry) -> Unit)? = null
    var onAdjust: ((String) -> Unit)? = null

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    init {
        setWillNotDraw(false)
        // Deliberately *not* clickable: the host resolves taps against the cell
        // layout, so a drag that begins on an app still belongs to the page.
        isClickable = false
        contentDescription = "Pinned apps"
        refresh()
    }

    fun setEntries(list: List<AppEntry>) {
        entries = list
        requestLayout()
        invalidate()
    }

    // ---- tokens ----------------------------------------------------------

    fun refresh() {
        val t = tokens
        visibility = if (t.b(K.pinsOn)) VISIBLE else GONE
        if (visibility != VISIBLE) return
        val den = Density(t)
        weight = (t.f(K.pinsWeight).roundToInt() + t.f(K.typeWeightShift).roundToInt()).coerceIn(100, 900)
        face = Type.ui(context, weight)
        textSize = context.sp(27f) * den.base * den.pins
        layoutMode = t.s(K.pinsLayout)
        align = t.s(K.pinsAlign)
        anchor = t.s(K.pinsAnchor)
        showIcons = t.b(K.pinsIcons)
        iconSize = dp(t.f(K.pinsIconSize))
        iconStyle = t.s(K.pinsIconStyle)
        showLabels = t.b(K.pinsLabels)
        gap = dp(12f) * t.f(K.pinsGap)
        opacity = t.f(K.pinsOpacity)
        caseMode = t.s(K.pinsCase)
        styleMode = t.s(K.pinsStyle)
        tracking = t.f(K.pinsTracking)
        dx = t.f(K.pinsDx)
        dy = t.f(K.pinsDy)
        fountain = t.s(K.pinsFountain)
        revealOn = t.b(K.pinsFadeIn)
        iconBreathe = t.b(K.pinsIconBreathe)
        ink = Ink(t).primary
        inkDim = withAlpha(ink, 0.5f)

        label.typeface = face
        label.textSize = textSize
        label.letterSpacing = tracking
        label.color = ink
        label.isSubpixelText = true
        if (styleMode == "monogram") {
            label.letterSpacing = tracking + 0.02f
        }
        tiny.typeface = Type.mono(context, 400)
        tiny.textSize = textSize * 0.62f
        tiny.color = withAlpha(ink, 0.42f)
        tiny.letterSpacing = 0.06f

        // icons are now enabled: resolve the drawables we were waiting for
        if (showIcons && !iconsResolved) {
            iconsResolved = true
            icons.clear()
        }
        val want = t.i(K.pinsCount).coerceIn(1, 12)
        val src = index.pinned()
        val slice = src.take(want)
        if (slice != entries) setEntries(slice)
        requestLayout()
        invalidate()
    }

    // ---- text ------------------------------------------------------------

    private fun applyCase(s: String): String = when (caseMode) {
        "upper" -> s.uppercase(Locale.getDefault())
        "lower" -> s.lowercase(Locale.getDefault())
        "small" -> s.lowercase(Locale.getDefault())
        else -> s
    }

    private fun labelFor(c: Cell): String {
        val raw = c.entry.label
        val base = when (styleMode) {
            "monogram" -> raw.take(1).uppercase(Locale.getDefault())
            "numbered" -> String.format(Locale.US, "%02d", c.ordinal + 1)
            "bracket" -> "[$raw]"
            else -> raw
        }
        return applyCase(base)
    }

    private fun ellipsize(p: TextPaint, text: String, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        val dots = "…"
        val dw = p.measureText(dots)
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (p.measureText(text, 0, mid) + dw <= maxW) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo) + dots
    }

    private fun measureLabel(c: Cell, availW: Float): Float {
        if (!showLabels && styleMode != "monogram") return 0f
        val txt = ellipsize(label, labelFor(c), availW * 0.8f)
        return label.measureText(txt)
    }

    // ---- layout ----------------------------------------------------------

    override fun onMeasure(w: Int, h: Int) {
        val ww = MeasureSpec.getSize(w)
        val hh = MeasureSpec.getSize(h)
        setMeasuredDimension(ww, hh)
        layout(ww, hh)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layout(w, h)
    }

    private fun layout(vw: Int, vh: Int) {
        cells.clear()
        if (entries.isEmpty() || vw == 0 || visibility != VISIBLE) {
            block.set(0f, 0f, 0f, 0f)
            return
        }
        val availW = vw - dp(36f)
        val rowH = max(iconSize, textSize * 1.16f) + gap * 0.5f
        var blockW = 0f
        var blockH = 0f

        when (layoutMode) {
            "list" -> {
                for ((i, e) in entries.withIndex()) {
                    val c = Cell(e, ordinal = i)
                    c.labelW = measureLabel(c, availW)
                    c.w = (if (showIcons) iconSize + dp(12f) else 0f) + c.labelW
                    c.h = rowH
                    c.x = 0f
                    c.y = i * rowH
                    cells.add(c)
                    blockW = max(blockW, c.w)
                    blockH = c.y + c.h
                }
            }
            "row", "inline" -> {
                val maxW = if (layoutMode == "inline") availW * 1.6f else availW
                val spacing = if (layoutMode == "inline") gap * 1.4f else gap * 1.6f
                var x = 0f
                var y = 0f
                for ((i, e) in entries.withIndex()) {
                    val c = Cell(e, ordinal = i)
                    c.labelW = measureLabel(c, availW)
                    c.w = (if (showIcons) iconSize + dp(9f) else 0f) + c.labelW
                    c.h = rowH
                    if (layoutMode == "row" && x > 0f && x + c.w > maxW) {
                        x = 0f
                        y += rowH + gap * 0.4f
                    }
                    if (layoutMode == "inline" && x > 0f && x + c.w > maxW) {
                        c.w = max(0f, maxW - x)
                        c.labelW = c.w
                    }
                    c.x = x
                    c.y = y
                    cells.add(c)
                    x += c.w + spacing
                    blockW = max(blockW, c.x + c.w)
                }
                blockH = y + rowH
            }
            "grid2", "grid3" -> {
                val cols = if (layoutMode == "grid3") 3 else 2
                val colW = availW / cols
                val cellH = max(iconSize, textSize * 1.1f) + dp(20f)
                for ((i, e) in entries.withIndex()) {
                    val c = Cell(e, ordinal = i)
                    c.w = colW
                    c.h = cellH
                    c.x = (i % cols) * colW
                    c.y = (i / cols) * cellH
                    cells.add(c)
                }
                blockW = availW
                blockH = ((entries.size + cols - 1) / cols) * cellH
            }
        }

        val marginX = dp(28f)
        val marginY = dp(20f)
        val freeW = vw - blockW - marginX * 2
        val freeH = vh - blockH - marginY * 2
        val ox = dx * vw
        val oy = dy * vh
        val left = when {
            anchor.endsWith("Start") -> marginX
            anchor.endsWith("End") -> freeW - marginX
            else -> freeW / 2f
        } + ox
        val top = when {
            anchor.startsWith("top") -> marginY
            anchor.startsWith("bottom") -> freeH - marginY
            else -> freeH / 2f
        } + oy
        block.set(left, top, left + blockW, top + blockH)

        // horizontal alignment inside the block
        for (c in cells) {
            c.x += when (align) {
                "center" -> left + (blockW - c.w) / 2f
                "end" -> left + (blockW - c.w)
                else -> left
            }
            c.y += top
        }
        block.set(left, top, left + max(blockW, 1f), top + max(blockH, 1f))
    }

    // ---- drawing ---------------------------------------------------------

    override fun onDraw(c: Canvas) {
        if (entries.isEmpty()) return
        val n = cells.size
        for ((i, cell) in cells.withIndex()) {
            val a = when (fountain) {
                "fade" -> (1f - i / (n + 1f) * 0.82f)
                "focus" -> {
                    // near the anchor's edge is brightest; the far end recedes
                    val fromTop = norm(i, n)
                    val anchoredDown = anchor.startsWith("bottom")
                    if (anchoredDown) 0.18f + 0.82f * fromTop else 1f - 0.82f * fromTop
                }
                "centre" -> {
                    val mid = (n - 1) / 2f
                    val d = if (mid <= 0f) 0f else kotlin.math.abs(i - mid) / mid
                    1f - d * 0.72f
                }
                else -> 1f
            }
            drawCell(c, cell, opacity * a)
        }
        if (studioHint > 0f) drawGrip(c)
    }

    private fun norm(i: Int, n: Int): Float = if (n <= 1) 0f else i / (n - 1f)

    private fun drawCell(c: Canvas, cell: Cell, alphaScale: Float) {
        val save = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
            (alphaScale.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255))
        try {
            drawCellInner(c, cell)
        } finally {
            c.restoreToCount(save)
        }
    }

    private fun drawCellInner(c: Canvas, cell: Cell) {
        var textLeft = cell.x
        val midY = cell.y + cell.h / 2f
        if (showIcons) {
            val dr = iconFor(cell.entry)
            val r = RectF(cell.x, midY - iconSize / 2f, cell.x + iconSize, midY + iconSize / 2f)
            if (dr != null) {
                dr.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                val baseAlpha = if (iconStyle == "dim") 150 else 255
                when (iconStyle) {
                    "mono" -> dr.colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN)
                    "dim" -> dr.colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN)
                    else -> dr.colorFilter = null
                }
                dr.alpha = if (revealOn) (iconReveal * baseAlpha).toInt().coerceIn(0, 255) else baseAlpha
                dr.draw(c)
                dr.colorFilter = null
                dr.alpha = 255
            } else {
                // fallback: a hollow ring
                tiny.color = withAlpha(ink, 0.25f)
                c.drawText("◦", r.centerX(), midY + tiny.textSize * 0.36f, tiny)
            }
            textLeft += iconSize + dp(11f)
        }
        if (showLabels || styleMode == "monogram") {
            val txt = ellipsize(label, labelFor(cell), width * 0.8f)
            label.color = ink
            val small = caseMode == "small"
            label.textSize = if (small) textSize * 0.86f else textSize
            if (small) {
                val prev = label.letterSpacing
                label.letterSpacing = prev + 0.08f
                c.drawText(txt, textLeft, midY + label.textSize * 0.34f, label)
                label.letterSpacing = prev
            } else {
                c.drawText(txt, textLeft, midY + label.textSize * 0.34f, label)
            }
            label.textSize = textSize
        }
    }

    private var studioHint = 0f
    private var iconReveal = 1f
    private var iconsResolved = false

    /** Called by the home root to hint that this block is adjustable. */
    fun hint(v: Float) {
        if (abs(studioHint - v) < 0.01f) return
        studioHint = v
        invalidate()
    }

    private fun drawGrip(c: Canvas) {
        val x = block.right + dp(6f)
        val y = block.top + dp(4f)
        Grip.draw(c, x, y - dp(1f), dp(5f), withAlpha(ink, 0.55f), Target.HANDLE_BAR, studioHint * 0.9f)
        Grip.draw(c, x, y + dp(7f), dp(5f), withAlpha(ink, 0.55f), Target.HANDLE_BAR, studioHint * 0.9f)
    }

    // ---- icons -----------------------------------------------------------

    private fun iconFor(e: AppEntry): Drawable? {
        val key = e.component.flattenToString()
        if (icons.containsKey(key)) return icons[key]
        val dr = index.icon(e)
        icons[key] = dr
        return dr
    }

    // ---- hit testing -----------------------------------------------------

    fun cellAt(x: Float, y: Float): Cell? {
        for (c in cells) {
            if (x >= c.x - dp(6f) && x <= c.x + c.w + dp(10f) &&
                y >= c.y - dp(4f) && y <= c.y + c.h + dp(4f)) return c
        }
        return null
    }

    // ---- live targets ----------------------------------------------------

    private var regions: HashMap<Target, RectF> = HashMap()
    private var rowRegions: HashMap<Target, Boolean> = HashMap()

    override fun targets(): List<Target> {
        val list = ArrayList<Target>(cells.size + 2)
        regions = HashMap(cells.size * 2 + 4)
        for (c in cells) {
            val rect = RectF(c.x, c.y, c.x + max(c.w, dp(20f)), c.y + c.h)
            val t = Target(
                id = "pins.app", // inert: the block owns the drag, the host owns the tap
                label = c.entry.label,
                verb = "tap",
                handle = Target.HANDLE_DOT,
                value = { c.entry.label },
                hint = { "Release to pin or unpin" },
                onClick = { onLongPress?.invoke(c.entry) },
                ink = { ink }
            )
            regions[t] = rect
            rowRegions[t] = layoutMode == "list"
            list.add(t)
        }
        list.addAll(blockTargets())
        return list
    }

    override fun regionOf(t: Target): RectF? = regions[t] ?: RectF(block)

    /** True when a target is a single row (the HUD rings those as a stadium). */
    fun isRow(t: Target): Boolean = rowRegions[t] == true

    private fun blockTargets(): List<Target> {
        val gutter = RectF(block.right + dp(8f), block.top, block.right + dp(34f), block.bottom)
        regions[weightProxy] = weightBounds()
        return listOf(
        Target(
            id = "pins",
            label = "Apps",
            verb = "tap · drag · pinch",
            handle = Target.HANDLE_BAR,
            value = {
                val modeLabel = layoutMode.replace("grid2", "grid 2").replace("grid3", "grid 3")
                "$modeLabel · ${entries.size}"
            },
            hint = { "Hold, then drag ↔ to align · ↕ for spacing · pinch to scale" },
            onClick = null,
            onScrubX = { d -> scrubAlign(d) },
            onScrubY = { d -> scrubGap(d) },
            onPinch = { f -> pinchSize(f) },
            ink = { ink }
        ).also { regions[it] = gutter },
        Target(
            id = "pins.weight",
            label = "Apps · weight",
            verb = "drag",
            handle = Target.HANDLE_DOT,
            value = { String.format(Locale.US, "%d", weight) },
            hint = { "Drag vertically to change the label weight" },
            onScrubY = { d -> scrubWeight(d) },
            ink = { ink }
        ).also { regions[it] = weightBounds() }
        )
    }

    private val weightProxy: Target = Target(
        id = "pins.weight", label = "", verb = "",
        value = { "" }, hint = { "" }
    )

    private fun weightBounds(): RectF = RectF(block.right - dp(8f), block.top - dp(12f),
        block.right + dp(20f), block.top + dp(10f))

    private var accumX = 0f
    private var accumY = 0f
    private var accumW = 0f

    private fun scrubAlign(delta: Float) {
        accumX += delta
        if (abs(accumX) < 0.16f) return
        val dir = if (accumX > 0) 1 else -1
        accumX = 0f
        val opts = Schema.byKey[K.pinsAlign]!!.options
        val cur = opts.indexOfFirst { it.id == tokens.s(K.pinsAlign) }.coerceAtLeast(0)
        tokens.set(K.pinsAlign, opts[((cur + dir) % opts.size + opts.size) % opts.size].id)
        Haptics.tick(context)
        tokens.commit()
    }

    private fun scrubGap(delta: Float) {
        accumY += delta
        if (abs(accumY) < 0.05f) return
        val step = accumY * 0.9f
        accumY = 0f
        tokens.set(K.pinsGap, (tokens.f(K.pinsGap) + step).coerceIn(0f, 2.4f))
        tokens.commit()
    }

    private fun scrubWeight(delta: Float) {
        accumW += delta
        if (abs(accumW) < 0.02f) return
        val step = accumW * 300f
        accumW = 0f
        tokens.set(K.pinsWeight, ((tokens.f(K.pinsWeight) + step) / 10f).roundToInt() * 10f)
        tokens.commit()
    }

    private fun pinchSize(f: Float) {
        if (f == 0f) return
        tokens.set(K.pinsSize, (tokens.f(K.pinsSize) * f).coerceIn(0.6f, 1.8f))
        tokens.commit()
    }

    private fun cycleLayout() {
        val opts = Schema.byKey[K.pinsLayout]!!.options
        val cur = opts.indexOfFirst { it.id == tokens.s(K.pinsLayout) }.coerceAtLeast(0)
        tokens.set(K.pinsLayout, opts[(cur + 1) % opts.size].id)
        Haptics.tick(context)
        tokens.commit()
    }

    /** Public hit test used by the host for launches and long-presses. */
    fun appAt(x: Float, y: Float): AppEntry? = cellAt(x - left, y - top)?.entry

    fun iconBoundsAt(x: Float, y: Float): RectF? {
        val c = cellAt(x - left, y - top) ?: return null
        val l = c.x
        val mid = c.y + c.h / 2f
        return RectF(l, mid - iconSize / 2f, l + maxOf(iconSize, c.w), mid + iconSize / 2f)
    }

    /**
     * The icon bloom plays once per appearance of the app block — on an entrance,
     * not on every token tweak.
     */
    fun revealIcons() {
        if (!revealOn) { iconReveal = 1f; invalidate(); return }
        iconReveal = 0f
        Ramp(0f, 1f, 520L, ExpoOut, { v -> iconReveal = v; invalidate() }).start()
    }

    fun entrance(progress: Float, delayFrac: Float) {
        val p = ((progress - delayFrac) / (1f - delayFrac)).coerceIn(0f, 1f)
        val e = ExpoOut.getInterpolation(p)
        when (tokens.s(K.motionEntrance)) {
            "none" -> { alpha = 1f; translationY = 0f }
            "fade" -> alpha = e
            else -> {
                alpha = e
                translationY = (1f - e) * dpOf(context, 18f)
            }
        }
    }

    override fun hasOverlappingRendering() = false
}
