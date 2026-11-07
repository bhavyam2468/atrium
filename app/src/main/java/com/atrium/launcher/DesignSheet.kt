package com.atrium.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/* ============================================================================
   The design studio.

   A live replica of the home screen sits in a card. There are no sliders and
   no property panels — the thing you are changing is the thing you touch:

     · drag the clock           → reposition it (snaps to a 3×3 grid)
     · tap the time / date      → cycle its representation
     · two fingers ↕            → size · two fingers ↔ → weight
     · drag row gaps            · edge handles → insets · tap rows → style
     · long-press anything      → every option for that zone, in a sheet
     · double-tap anything      → reset that zone

   A chip at the bottom names the zone under your finger, shows its live
   value and whispers the gestures. Everything writes straight to the theme,
   so the real home screen behind the card changes as you work. Undo/redo,
   curated presets, and a Home/Search view toggle complete the loop.
   ============================================================================ */

@SuppressLint("ClickableViewAccessibility")
internal class DesignSheet(
    ctx: Context,
    private val theme: AtriumTheme,
    private val index: AppIndex,
    private val registry: SettingsRegistry,
    private val onDone: () -> Unit
) : FrameLayout(ctx) {

    private enum class Zone { TIME, DATE, PINS, GAP, ROW, ROWGAP, BAR, EDGE_L, EDGE_R, BG }

    private class Hit(val z: Zone, val v: View? = null, val y: Float = 0f)

    private fun dp(v: Float) = context.dp(v)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /* ------------------------------ chrome ------------------------------ */

    private val content = VLayer(context).apply { setBackgroundColor(0x8C000000.toInt()) }
    private val toolbar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20f).toInt(), dp(10f).toInt(), dp(20f).toInt(), dp(8f).toInt())
    }
    private val cardArea = Layer(context)
    private val chipBar = VLayer(context).apply {
        setPadding(dp(20f).toInt(), dp(8f).toInt(), dp(20f).toInt(), dp(8f).toInt())
    }

    private val card = Layer(context).apply { clipToOutline = true }
    private val scrimOverlay = View(context)
    private val guides = GuideView(context)
    private val highlight = HighlightView(context)
    private val pane = StagePane(context)

    private val chipZone = Label(context)
    private val chipValue = Label(context)
    private val chipHint = Label(context)
    private val resetBtn = Label(context)

    private var doneBtn = Label(context)
    private var undoBtn = Label(context)
    private var redoBtn = Label(context)
    private var homeBtn = Label(context)
    private var searchBtn = Label(context)
    private var aaBtn = AaButton(context)
    private var accentBtn = Label(context)
    private var shuffleBtn = Label(context)

    /* ------------------------------ replica ----------------------------- */

    private var mode = 0 // 0 home · 1 search
    private var modeSwitchPending = false
    private var rContent: View? = null
    private var rHomeHost: FrameLayout? = null
    private var rClock: ViewGroup? = null
    private var rTime: View? = null
    private var rDate: View? = null
    private var rPins: LinearLayout? = null
    private var rCol: LinearLayout? = null
    private var rHeader: Label? = null
    private var rRowsBox: LinearLayout? = null
    private val rRows = ArrayList<View>()
    private var rBarHost: VLayer? = null
    private var rBar: Label? = null
    private var rBarUnder: View? = null

    /* ----------------------------- gestures ----------------------------- */

    private var gZone: Zone? = null
    private var gMode = 0 // 0 idle · 1 drag · 2 two-finger · 3 swallowed
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var moved = false
    private var lastTapT = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastHit: Hit? = null
    private var hintDismissed = false
    private var dragBase = 0f
    private var bgWasBlack = false
    private var moveBaseTx = 0f
    private var moveBaseTy = 0f
    private var pinsBaseTx = 0f
    private var lastSnapX = -1
    private var lastSnapY = -1
    private val twoStart = HashMap<String, Any>()
    private var twoBaseX = 0f
    private var twoBaseY = 0f

    /* ------------------------------ history ----------------------------- */

    private val history = ArrayList<String>()
    private var histIdx = -1
    private var presetIdx = 0
    private var resetArmed = false
    private var exiting = false

    private val lpRunnable = Runnable {
        if (!moved && gMode == 0 && gZone != null) {
            val z = gZone!!
            gMode = 3
            zoneLongPress(z)
        }
    }

    private val themeListener: (String?) -> Unit = { key -> onThemeChanged(key) }

    /* ------------------------------- init ------------------------------- */

    init {
        theme.addListener(themeListener)

        card.addView(scrimOverlay, LayoutParams(-1, -1))
        card.addView(guides, LayoutParams(-1, -1))
        card.addView(highlight, LayoutParams(-1, -1))
        card.addView(pane, LayoutParams(-1, -1))
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionReplicaHome() }
        cardArea.addView(card, LayoutParams(-1, -1).apply {
            setMargins(dp(16f).toInt(), dp(6f).toInt(), dp(16f).toInt(), dp(10f).toInt())
        })

        content.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        content.addView(cardArea, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(chipBar, LinearLayout.LayoutParams(-1, -2))
        addView(content, LayoutParams(-1, -1))

        buildToolbar()
        buildChipBar()
        post { buildReplica() }
        history.add(theme.snapshot())
        histIdx = 0
        updateToolbar()
        updateChip()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            rootWindowInsets?.let { ins ->
                val bars = ins.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout()
                )
                content.setPadding(0, bars.top, 0, bars.bottom)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        theme.removeListener(themeListener)
    }

    /* ------------------------------ toolbar ----------------------------- */

    private fun glyph(text: String, sp: Float): Label {
        val l = Label(context)
        theme.styleUi(l, sp)
        l.text = text
        l.setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
        l.isClickable = true
        return l
    }

    private fun buildToolbar() {
        toolbar.removeAllViews()
        doneBtn = glyph("Done", 15f).apply { setOnClickListener { requestExit() } }
        toolbar.addView(doneBtn, LinearLayout.LayoutParams(-2, -2))

        toolbar.addView(View(context), LinearLayout.LayoutParams(0, -2).apply { weight = 1f })

        undoBtn = glyph("↺", 17f).apply { setOnClickListener { undo() } }
        toolbar.addView(undoBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10f).toInt() })
        redoBtn = glyph("↻", 17f).apply { setOnClickListener { redo() } }
        toolbar.addView(redoBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f).toInt() })

        homeBtn = glyph("Home", 13f).apply { setOnClickListener { switchMode(0) } }
        toolbar.addView(homeBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(18f).toInt() })
        searchBtn = glyph("Search", 13f).apply { setOnClickListener { switchMode(1) } }
        toolbar.addView(searchBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10f).toInt() })

        aaBtn = AaButton(context)
        theme.styleUi(aaBtn, 18f)
        aaBtn.setPadding(dp(6f).toInt(), dp(4f).toInt(), dp(6f).toInt(), dp(4f).toInt())
        aaBtn.isClickable = true
        wireAaGestures()
        toolbar.addView(aaBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(18f).toInt() })

        accentBtn = glyph("●", 14f).apply {
            setOnClickListener {
                theme.set(Tk.ACCENT, (theme.accent + 1) % ACCENTS.size)
                announce("Accent", ACCENTS[theme.accent].first)
            }
            setOnLongClickListener {
                openNodeSheet("Colour & background", listOf(Tk.ACCENT, Tk.TINT_TEXT, Tk.BRIGHTNESS, Tk.BACKGROUND, Tk.WALLPAPER_DIM))
                true
            }
        }
        toolbar.addView(accentBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12f).toInt() })

        shuffleBtn = glyph("✦", 16f).apply { setOnClickListener { shuffle() } }
        toolbar.addView(shuffleBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14f).toInt() })
    }

    private fun buildChipBar() {
        chipBar.removeAllViews()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        theme.styleValue(chipZone)
        chipZone.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        chipValue.let { theme.styleUi(it, 14f) }
        resetBtn.let { theme.styleValue(it) }
        resetBtn.text = "⟲"
        resetBtn.isClickable = true
        resetBtn.setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(6f).toInt())
        resetBtn.setOnClickListener { onResetTap() }
        row.addView(chipZone, LinearLayout.LayoutParams(-2, -2))
        row.addView(chipValue, LinearLayout.LayoutParams(0, -2).apply { weight = 1f; gravity = Gravity.END })
        row.addView(resetBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10f).toInt() })
        chipBar.addView(row, LinearLayout.LayoutParams(-1, -2))
        theme.styleValue(chipHint)
        chipHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
        chipHint.setSingleLine(false)
        chipHint.maxLines = 2
        chipBar.addView(chipHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f).toInt() })
    }

    private fun onResetTap() {
        if (!resetArmed) {
            resetArmed = true
            resetBtn.text = "✓"
            announce("Careful", "tap again to reset everything")
            postDelayed({
                resetArmed = false
                resetBtn.text = "⟲"
            }, 2600)
        } else {
            resetArmed = false
            resetBtn.text = "⟲"
            theme.resetAll()
            commitHistory()
            announce("Reset", "back to defaults")
        }
        pane.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /* --------------------------- replica build -------------------------- */

    private fun buildReplica() {
        rContent?.let { card.removeView(it) }
        rContent = null
        rHomeHost = null
        rClock = null
        rTime = null
        rDate = null
        rPins = null
        rCol = null
        rHeader = null
        rRowsBox = null
        rBarHost = null
        rBar = null
        rBarUnder = null
        rRows.clear()

        if (mode == 0) {
            val contentHost = Layer(context)
            val host = FrameLayout(context)
            rHomeHost = host
            val clock = HomeComposer.buildClockBlock(context, theme)
            rClock = clock
            rTime = HomeComposer.findClockChild(clock, HomeComposer.TAG_TIME)
            rDate = HomeComposer.findClockChild(clock, HomeComposer.TAG_DATE)
            host.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or theme.hGravity()))

            val pins = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rPins = pins
            fillReplicaPins(pins)
            host.addView(pins, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                topMargin = dp(theme.clock_gap).toInt()
            })
            contentHost.addView(host, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            rContent = contentHost
        } else {
            val contentHost = VLayer(context).apply { gravity = Gravity.BOTTOM }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            rCol = col
            val header = Label(context).apply { text = "APPS" }
            rHeader = header
            col.addView(header, LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = dp(6f).toInt()
            })
            val rows = VLayer(context)
            rRowsBox = rows
            val apps = index.apps
            if (apps.isEmpty()) {
                for (name in listOf("Alarm", "Calendar", "Camera", "Mail", "Maps")) {
                    val r = HomeComposer.buildResultRow(context, theme, index, Result(name) { })
                    rRows.add(r)
                    rows.addView(r, LinearLayout.LayoutParams(-2, -2))
                }
            } else {
                for (a in apps.take(5)) {
                    val r = HomeComposer.buildResultRow(context, theme, index, Result(a.label, entry = a) { })
                    rRows.add(r)
                    rows.addView(r, LinearLayout.LayoutParams(-2, -2))
                }
            }
            col.addView(rows, LinearLayout.LayoutParams(-1, -2))
            contentHost.addView(col, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })

            val barHost = VLayer(context)
            rBarHost = barHost
            val bar = Label(context).apply { text = "search" }
            rBar = bar
            barHost.addView(bar, LinearLayout.LayoutParams(-2, -2))
            val underline = View(context)
            rBarUnder = underline
            barHost.addView(underline, LinearLayout.LayoutParams(-1, Math.max(1, dp(1f).toInt())).apply {
                topMargin = dp(8f).toInt()
            })
            contentHost.addView(barHost, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(18f).toInt()
            })
            rContent = contentHost
        }
        card.addView(rContent!!, 1)
        restyleReplica()
        positionReplicaHome()
    }

    private fun fillReplicaPins(pins: LinearLayout) {
        pins.removeAllViews()
        val pinned = index.pinned()
        val labels = if (pinned.isEmpty()) listOf("Phone", "Camera", "Music")
        else pinned.take(4).map { it.label }
        for (l in labels) {
            val t = Label(context)
            HomeComposer.stylePin(t, theme)
            t.text = l
            pins.addView(t, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(20f).toInt() })
        }
    }

    private fun restyleReplica() {
        val sidePx = dp(theme.side_inset).toInt()
        applyCardLook(sidePx)

        rClock?.let { b ->
            val lp = b.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) lp.gravity = Gravity.TOP or theme.hGravity()
            HomeComposer.restyleClockBlock(b, theme)
        }
        rPins?.let { pins ->
            pins.gravity = theme.pinsGravity()
            for (i in 0 until pins.childCount) {
                (pins.getChildAt(i) as? Label)?.let { HomeComposer.stylePin(it, theme) }
            }
            (pins.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = dp(theme.clock_gap).toInt() }
            pins.visibility = if (theme.pins_visible) View.VISIBLE else View.GONE
            pins.requestLayout()
        }

        rHeader?.let {
            theme.styleHeader(it)
            it.visibility = if (theme.show_headers) View.VISIBLE else View.GONE
        }
        rRowsBox?.let { box ->
            box.gravity = when (theme.result_align) {
                0 -> Gravity.START
                2 -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
        }
        for (row in rRows) HomeComposer.restyleResultRow(row, theme)
        if (mode == 1) {
            rCol?.let { col ->
                (col.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.width = (card.width * theme.column_width).toInt()
                }
                col.requestLayout()
            }
            rContent?.let { c ->
                c.setPadding(sidePx, 0, sidePx, dp(18f).toInt())
                c.requestLayout()
            }
        }
        restyleBar()
        positionReplicaHome()
    }

    private fun applyCardLook(sidePx: Int) {
        val radius = dp(26f)
        val stroke = Math.max(1, dp(0.75f).toInt())
        if (theme.background_mode == 1) {
            card.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF28303C.toInt(), 0xFF0B0E14.toInt())
            ).apply {
                cornerRadius = radius
                setStroke(stroke, theme.colorAt(0.10f))
            }
            scrimOverlay.setBackgroundColor(
                Color.argb((theme.wallpaper_dim * 255).toInt(), 0, 0, 0)
            )
        } else {
            card.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = radius
                setStroke(stroke, theme.colorAt(0.10f))
            }
            scrimOverlay.setBackgroundColor(Color.TRANSPARENT)
        }
        card.setPadding(sidePx, dp(26f).toInt(), sidePx, dp(26f).toInt())
    }

    private fun restyleBar() {
        val bar = rBar ?: return
        theme.styleUi(bar, theme.search_size)
        bar.setTextColor(theme.colorAt(0.55f))
        bar.gravity = Gravity.CENTER
        bar.setSingleLine()
        when (theme.search_style) {
            1 -> {
                rBarUnder?.visibility = View.VISIBLE
                rBarUnder?.setBackgroundColor(theme.colorAt(0.30f))
                bar.background = null
                bar.setPadding(dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt(), dp(2f).toInt())
            }
            2 -> {
                rBarUnder?.visibility = View.GONE
                bar.background = GradientDrawable().apply {
                    cornerRadius = dp(999f)
                    setColor(theme.colorAt(0.10f))
                }
                bar.setPadding(dp(26f).toInt(), dp(10f).toInt(), dp(26f).toInt(), dp(10f).toInt())
            }
            else -> {
                rBarUnder?.visibility = View.GONE
                bar.background = null
                bar.setPadding(dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt(), dp(2f).toInt())
            }
        }
    }

    private fun positionReplicaHome() {
        val host = rHomeHost ?: return
        if (card.height == 0 || host.height == 0) return
        val avail = (card.height - card.paddingTop - card.paddingBottom - host.height)
            .coerceAtLeast(0)
        host.translationY = avail * theme.anchor_y
    }

    /* ---------------------------- theme changes ------------------------- */

    private fun onThemeChanged(key: String?) {
        if (key == null || key in REBUILD_KEYS) buildReplica() else restyleReplica()
        updateChip()
        updateToolbar()
    }

    /* ---------------------------- zone lookup --------------------------- */

    private fun offsetInto(v: View): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var cur: View = v
        while (cur !== card) {
            x += cur.left - cur.scrollX
            y += cur.top - cur.scrollY
            val p = cur.parent as? View ?: break
            cur = p
        }
        return x to y
    }

    private fun inView(v: View?, x: Float, y: Float, pad: Float = 0f): Boolean {
        if (v == null || v.visibility != View.VISIBLE || v.width == 0) return false
        val (vx, vy) = offsetInto(v)
        return x >= vx - pad && x <= vx + v.width + pad &&
            y >= vy - pad && y <= vy + v.height + pad
    }

    private fun zoneAt(x: Float, y: Float): Hit {
        if (mode == 0) {
            if (inView(rTime, x, y, dp(6f))) return Hit(Zone.TIME, rTime)
            if (inView(rDate, x, y, dp(6f))) return Hit(Zone.DATE, rDate)
            if (theme.pins_visible && inView(rPins, x, y, dp(5f))) return Hit(Zone.PINS, rPins)
            if (rClock != null && rPins != null && theme.pins_visible) {
                val c = offsetInto(rClock!!)
                val p = offsetInto(rPins!!)
                val mid = (c.second + rClock!!.height + p.second) / 2f
                if (abs(y - mid) < dp(14f) && x > card.width * 0.2f && x < card.width * 0.8f)
                    return Hit(Zone.GAP, y = mid)
            }
        } else {
            for (row in rRows) {
                if (inView(row, x, y, dp(4f))) return Hit(Zone.ROW, row)
            }
            for (i in 0 until rRows.size - 1) {
                val a = rRows[i]
                val b = rRows[i + 1]
                val ao = offsetInto(a)
                val bo = offsetInto(b)
                val mid = (ao.second + a.height + bo.second) / 2f
                if (abs(y - mid) < dp(12f)) return Hit(Zone.ROWGAP, y = mid)
            }
            if (inView(rBarHost, x, y, dp(6f))) return Hit(Zone.BAR, rBarHost)
        }
        if (x < dp(26f)) return Hit(Zone.EDGE_L)
        if (x > card.width - dp(26f)) return Hit(Zone.EDGE_R)
        return Hit(Zone.BG)
    }

    private fun zoneRect(hit: Hit, out: RectF): Int {
        val v = hit.v
        when (hit.z) {
            Zone.TIME, Zone.DATE, Zone.PINS, Zone.ROW, Zone.BAR -> {
                if (v != null) {
                    val (x, y) = offsetInto(v)
                    val pad = dp(8f)
                    out.set(x - pad, y - pad, x + v.width + pad, y + v.height + pad)
                    return 0
                }
            }
            Zone.GAP -> {
                if (rClock != null && rPins != null) {
                    val c = offsetInto(rClock!!)
                    val p = offsetInto(rPins!!)
                    val mid = (c.second + rClock!!.height + p.second) / 2f
                    val inset = card.width * 0.28f
                    out.set(inset, mid - dp(2f), card.width - inset, mid + dp(2f))
                    return 2
                }
            }
            Zone.ROWGAP -> {
                val inset = card.width * 0.28f
                out.set(inset, hit.y - dp(2f), card.width - inset, hit.y + dp(2f))
                return 2
            }
            Zone.EDGE_L -> {
                val x = card.paddingLeft.toFloat()
                out.set(x - dp(2f), card.height * 0.22f, x + dp(2f), card.height * 0.78f)
                return 1
            }
            Zone.EDGE_R -> {
                val x = card.width - card.paddingRight.toFloat()
                out.set(x - dp(2f), card.height * 0.22f, x + dp(2f), card.height * 0.78f)
                return 1
            }
            Zone.BG -> {
                out.set(dp(8f), dp(8f), card.width - dp(8f), card.height - dp(8f))
                return 0
            }
        }
        out.setEmpty()
        return 0
    }

    /* --------------------------- gesture engine ------------------------- */

    private inner class StagePane(ctx: Context) : View(ctx) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            handle(e)
            return true
        }
    }

    private fun scheduleLp() {
        pane.postDelayed(lpRunnable, 420)
    }

    private fun cancelLp() {
        pane.removeCallbacks(lpRunnable)
    }

    private fun handle(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                downT = SystemClock.uptimeMillis()
                moved = false
                gMode = 0
                val hit = zoneAt(downX, downY)
                gZone = hit.z
                lastHit = hit
                hintDismissed = true
                theme.deferWrites()
                updateChip()
                showHighlightFor(lastHit, fast = false)
                scheduleLp()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2 && gMode == 0 && !moved) {
                    cancelLp()
                    twoStart.clear()
                    for (k in zoneKeys(gZone)) theme.raw(k)?.let { twoStart[k] = it }
                    twoBaseX = avgX(e)
                    twoBaseY = avgY(e)
                    gMode = 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gMode == 2) {
                    if (e.pointerCount >= 2) applyTwoFinger(avgX(e) - twoBaseX, avgY(e) - twoBaseY)
                } else if (gMode == 0) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        cancelLp()
                        if (zoneDraggable(gZone)) {
                            gMode = 1
                            beginDrag()
                        } else {
                            gMode = 3
                        }
                    }
                    if (gMode == 1) applyDrag(dx, dy)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gMode == 2 && e.pointerCount >= 2) {
                    applyTwoFinger(avgX(e) - twoBaseX, avgY(e) - twoBaseY)
                    gMode = 3
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelLp()
                val t = SystemClock.uptimeMillis()
                if (gMode == 1) endDrag()
                if (gMode == 0 && !moved && t - downT < 350) {
                    if (t - lastTapT < 320 && abs(e.x - lastTapX) < dp(48f) && abs(e.y - lastTapY) < dp(48f)) {
                        zoneDoubleTap(gZone)
                        lastTapT = 0
                    } else {
                        zoneTap(gZone)
                        lastTapT = t
                        lastTapX = e.x
                        lastTapY = e.y
                    }
                }
                theme.flushWrites()
                commitHistory()
                gMode = 0
                gZone = null
                highlight.hide()
                guides.on = false
                guides.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLp()
                theme.flushWrites()
                gMode = 0
                gZone = null
                highlight.hide()
                guides.on = false
                guides.invalidate()
            }
        }
    }

    private fun avgX(e: MotionEvent): Float = (e.getX(0) + e.getX(1)) / 2f
    private fun avgY(e: MotionEvent): Float = (e.getY(0) + e.getY(1)) / 2f

    private fun zoneDraggable(z: Zone?): Boolean = when (z) {
        Zone.TIME, Zone.DATE, Zone.PINS, Zone.GAP, Zone.EDGE_L, Zone.EDGE_R, Zone.ROWGAP, Zone.BG -> true
        else -> false
    }

    private fun zoneKeys(z: Zone?): List<String> = when (z) {
        Zone.TIME, Zone.DATE -> listOf(Tk.CLOCK_SCALE, Tk.CLOCK_WEIGHT)
        Zone.PINS -> listOf(Tk.PINS_SIZE)
        Zone.ROW -> listOf(Tk.UI_SIZE, Tk.UI_WEIGHT)
        Zone.BAR -> listOf(Tk.SEARCH_SIZE)
        Zone.BG -> listOf(Tk.BRIGHTNESS)
        else -> emptyList()
    }

    private fun beginDrag() {
        when (gZone) {
            Zone.TIME, Zone.DATE -> {
                rHomeHost?.let { moveBaseTy = it.translationY }
                moveBaseTx = 0f
                lastSnapX = -1
                lastSnapY = -1
                guides.on = true
            }
            Zone.PINS -> pinsBaseTx = rPins?.translationX ?: 0f
            Zone.GAP -> dragBase = theme.clock_gap
            Zone.EDGE_L, Zone.EDGE_R -> dragBase = theme.side_inset
            Zone.ROWGAP -> dragBase = theme.row_density
            Zone.BG -> {
                dragBase = theme.wallpaper_dim
                bgWasBlack = theme.background_mode == 0
            }
            else -> {}
        }
    }

    private fun setTk(key: String, v: Float) {
        val d = TOKEN_DEFS[key] ?: return
        theme.set(key, if (d.type == TokType.INT) v.toInt() else v)
    }

    private fun twoVal(key: String): Float = (twoStart[key] as? Number)?.toFloat() ?: 0f

    private fun applyDrag(dx: Float, dy: Float) {
        when (gZone) {
            Zone.TIME, Zone.DATE -> moveClock(dx, dy)
            Zone.PINS -> movePins(dx)
            Zone.GAP -> setTk(Tk.CLOCK_GAP, dragBase + dy)
            Zone.EDGE_L -> setTk(Tk.SIDE_INSET, dragBase - dx)
            Zone.EDGE_R -> setTk(Tk.SIDE_INSET, dragBase + dx)
            Zone.ROWGAP -> setTk(Tk.ROW_DENSITY, dragBase + dy / dp(64f))
            Zone.BG -> applyBgDrag(dy)
            else -> {}
        }
    }

    private fun applyBgDrag(dy: Float) {
        if (bgWasBlack) {
            if (dy < -dp(14f)) {
                theme.set(Tk.BACKGROUND, 1)
                bgWasBlack = false
                dragBase = theme.wallpaper_dim
                pane.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        } else {
            setTk(Tk.WALLPAPER_DIM, dragBase + dy / (card.height * 0.8f))
        }
    }

    private fun applyTwoFinger(dx: Float, dy: Float) {
        when (gZone) {
            Zone.TIME, Zone.DATE -> {
                setTk(Tk.CLOCK_SCALE, twoVal(Tk.CLOCK_SCALE) - dy / dp(80f))
                setTk(Tk.CLOCK_WEIGHT, twoVal(Tk.CLOCK_WEIGHT) + dx / 3f)
            }
            Zone.PINS -> setTk(Tk.PINS_SIZE, twoVal(Tk.PINS_SIZE) - dy / dp(90f))
            Zone.ROW -> {
                setTk(Tk.UI_SIZE, twoVal(Tk.UI_SIZE) - dy / dp(60f))
                setTk(Tk.UI_WEIGHT, twoVal(Tk.UI_WEIGHT) + dx / 3f)
            }
            Zone.BAR -> setTk(Tk.SEARCH_SIZE, twoVal(Tk.SEARCH_SIZE) - dy / dp(60f))
            Zone.BG -> setTk(Tk.BRIGHTNESS, twoVal(Tk.BRIGHTNESS) - dy / (card.height * 1.4f))
            else -> {}
        }
    }

    private fun endDrag() {
        when (gZone) {
            Zone.TIME, Zone.DATE -> endClockMove()
            Zone.PINS -> endPinsMove()
            else -> {}
        }
        guides.on = false
        guides.invalidate()
    }

    /* ------------------------------ moves ------------------------------- */

    private fun moveClock(dx: Float, dy: Float) {
        val host = rHomeHost ?: return
        if (card.height == 0 || host.height == 0) return
        val avail = (card.height - card.paddingTop - card.paddingBottom - host.height).toFloat()
            .coerceAtLeast(1f)
        var tx = moveBaseTx + dx
        var ty = (moveBaseTy + dy).coerceIn(0f, avail)

        val xs = floatArrayOf(card.width * 0.14f, card.width * 0.5f, card.width * 0.86f)
        val cx = card.width / 2f + tx
        var snapX = -1
        for (i in xs.indices) if (abs(cx - xs[i]) < dp(32f)) { snapX = i; break }
        if (snapX >= 0) tx += (xs[snapX] - cx) * 0.45f

        val half = host.height / 2f
        val ys = floatArrayOf(avail * 0.15f + half, avail * 0.5f + half, avail * 0.85f + half)
        val cy = ty + half
        var snapY = -1
        for (i in ys.indices) if (abs(cy - ys[i]) < dp(30f)) { snapY = i; break }
        if (snapY >= 0) ty += (ys[snapY] - cy) * 0.45f

        host.translationX = tx
        host.translationY = ty

        if (snapX != lastSnapX || snapY != lastSnapY) {
            if (snapX >= 0 || snapY >= 0)
                pane.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastSnapX = snapX
            lastSnapY = snapY
        }
        guides.update(snapX, snapY, xs, ys)
        chipZone.text = "Position"
        chipValue.text = posStringFrom(card.width / 2f + host.translationX, ty / avail)
        showHighlightFor(lastHit, fast = true)
    }

    private fun endClockMove() {
        val host = rHomeHost ?: return
        if (card.height == 0 || host.height == 0) {
            host.translationX = 0f
            positionReplicaHome()
            return
        }
        val avail = (card.height - card.paddingTop - card.paddingBottom - host.height).toFloat()
            .coerceAtLeast(1f)
        val cx = card.width / 2f + host.translationX
        val alignX = when {
            cx < card.width / 3f -> 0
            cx > card.width * 2f / 3f -> 2
            else -> 1
        }
        val anchor = (host.translationY / avail).coerceIn(0.02f, 0.95f)
        theme.batch {
            theme.set(Tk.ALIGN_X, alignX)
            theme.set(Tk.ANCHOR_Y, anchor)
        }
        host.translationX = 0f
        positionReplicaHome()
        pane.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun movePins(dx: Float) {
        val pins = rPins ?: return
        pins.translationX = pinsBaseTx + dx
        val host = rHomeHost
        if (host != null && host.width > 0) {
            chipZone.text = "Pins"
            chipValue.text = alignStringFrom(host.width / 2f + pins.translationX, host.width.toFloat())
        }
        showHighlightFor(lastHit, fast = true)
    }

    private fun endPinsMove() {
        val pins = rPins ?: return
        val host = rHomeHost ?: return
        val a = when {
            host.width == 0 -> 1
            host.width / 2f + pins.translationX < host.width / 3f -> 0
            host.width / 2f + pins.translationX > host.width * 2f / 3f -> 2
            else -> 1
        }
        theme.set(Tk.PINS_ALIGN, a)
        pins.translationX = 0f
        pane.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /* ------------------------------ taps -------------------------------- */

    private fun zoneTap(z: Zone?) {
        val acted = when (z) {
            Zone.TIME -> {
                theme.set(Tk.TIME_FORMAT, (theme.time_format + 1) % TIME_FORMATS.size)
                true
            }
            Zone.DATE -> {
                theme.set(Tk.DATE_FORMAT, (theme.date_format + 1) % DATE_FORMATS.size)
                true
            }
            Zone.PINS -> { cyclePins(); true }
            Zone.ROW -> { theme.set(Tk.ROW_STYLE, (theme.row_style + 1) % 3); true }
            Zone.BAR -> { theme.set(Tk.SEARCH_STYLE, (theme.search_style + 1) % 3); true }
            Zone.BG -> { theme.set(Tk.BACKGROUND, 1 - theme.background_mode); true }
            else -> false
        }
        if (acted) pane.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun cyclePins() {
        when {
            !theme.pins_visible -> {
                theme.set(Tk.PINS_VISIBLE, true)
                theme.set(Tk.PINS_SIZE, 1.5f)
            }
            theme.pins_size >= 1.8f -> theme.set(Tk.PINS_SIZE, 1.1f)
            theme.pins_size <= 1.2f -> theme.set(Tk.PINS_VISIBLE, false)
            else -> theme.set(Tk.PINS_SIZE, 1.9f)
        }
    }

    private fun zoneDoubleTap(z: Zone?) {
        val keys = when (z) {
            Zone.TIME -> listOf(Tk.TIME_FORMAT, Tk.CLOCK_FAMILY, Tk.CLOCK_SCALE, Tk.CLOCK_WEIGHT, Tk.CLOCK_TRACKING)
            Zone.DATE -> listOf(Tk.DATE_FORMAT, Tk.DATE_SCALE, Tk.DATE_WEIGHT, Tk.DATE_TRACKING, Tk.DATE_UPPER, Tk.ARRANGEMENT, Tk.SEPARATOR)
            Zone.PINS -> listOf(Tk.PINS_VISIBLE, Tk.PINS_SIZE, Tk.PINS_ALIGN)
            Zone.GAP -> listOf(Tk.CLOCK_GAP, Tk.ANCHOR_Y, Tk.ALIGN_X)
            Zone.ROW -> listOf(Tk.ROW_STYLE, Tk.UI_SIZE, Tk.UI_WEIGHT, Tk.ICON_SIZE, Tk.ICON_SHAPE, Tk.ICON_MONO, Tk.ROW_DENSITY, Tk.RESULT_ALIGN, Tk.SHOW_SUBTITLES)
            Zone.ROWGAP -> listOf(Tk.ROW_DENSITY)
            Zone.BAR -> listOf(Tk.SEARCH_STYLE, Tk.SEARCH_SIZE)
            Zone.EDGE_L, Zone.EDGE_R -> listOf(Tk.SIDE_INSET, Tk.COLUMN_WIDTH)
            Zone.BG -> listOf(Tk.BACKGROUND, Tk.WALLPAPER_DIM, Tk.BRIGHTNESS, Tk.ACCENT, Tk.TINT_TEXT)
            else -> emptyList()
        }
        if (keys.isEmpty()) return
        theme.batch {
            for (k in keys) TOKEN_DEFS[k]?.let { theme.set(k, it.def) }
        }
        pane.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        announce("Reset", "back to defaults")
    }

    private fun zoneLongPress(z: Zone) {
        val keys = when (z) {
            Zone.TIME -> listOf(Tk.TIME_FORMAT, Tk.CLOCK_FAMILY, Tk.CLOCK_SCALE, Tk.CLOCK_WEIGHT, Tk.CLOCK_TRACKING, Tk.ARRANGEMENT, Tk.SEPARATOR)
            Zone.DATE -> listOf(Tk.DATE_FORMAT, Tk.DATE_SCALE, Tk.DATE_WEIGHT, Tk.DATE_TRACKING, Tk.DATE_UPPER, Tk.ARRANGEMENT, Tk.SEPARATOR)
            Zone.PINS -> listOf(Tk.PINS_VISIBLE, Tk.PINS_SIZE, Tk.PINS_ALIGN)
            Zone.GAP -> listOf(Tk.CLOCK_GAP, Tk.ALIGN_X, Tk.ANCHOR_Y, Tk.SIDE_INSET)
            Zone.ROW -> listOf(Tk.ROW_STYLE, Tk.UI_SIZE, Tk.UI_WEIGHT, Tk.TRACKING, Tk.ICON_SIZE, Tk.ICON_SHAPE, Tk.ICON_MONO, Tk.ROW_DENSITY, Tk.RESULT_ALIGN, Tk.SHOW_SUBTITLES, Tk.SHOW_HEADERS, Tk.COLUMN_WIDTH)
            Zone.ROWGAP -> listOf(Tk.ROW_DENSITY)
            Zone.BAR -> listOf(Tk.SEARCH_STYLE, Tk.SEARCH_SIZE)
            Zone.EDGE_L, Zone.EDGE_R -> listOf(Tk.SIDE_INSET, Tk.COLUMN_WIDTH)
            Zone.BG -> listOf(Tk.BACKGROUND, Tk.WALLPAPER_DIM, Tk.BRIGHTNESS, Tk.ACCENT, Tk.TINT_TEXT)
        }
        openNodeSheet(sheetTitle(z), keys)
        pane.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun openNodeSheet(title: String, keys: List<String>) {
        val nodes = keys.mapNotNull { registry.nodeForToken(it) }
        if (nodes.isEmpty()) return
        OptionSheet(context, this, theme).showNodes(title, nodes)
    }

    private fun sheetTitle(z: Zone): String = when (z) {
        Zone.TIME -> "Clock"
        Zone.DATE -> "Date"
        Zone.PINS -> "Pins"
        Zone.GAP -> "Layout"
        Zone.ROW, Zone.ROWGAP -> "Rows"
        Zone.BAR -> "Search bar"
        Zone.EDGE_L, Zone.EDGE_R -> "Layout"
        Zone.BG -> "Colour & background"
    }

    /* ------------------------------ chip -------------------------------- */

    private fun rowStyleName(): String = when (theme.row_style) {
        1 -> "Icon + name"
        2 -> "Icon only"
        else -> "Name only"
    }

    private fun barStyleName(): String = when (theme.search_style) {
        1 -> "Underline"
        2 -> "Pill"
        else -> "Bare"
    }

    private fun chipFor(z: Zone?): Triple<String, String, String> = when (z) {
        Zone.TIME -> Triple("Time", patternExample(theme.timePattern), "tap format · drag move · two fingers size & weight · hold everything")
        Zone.DATE -> Triple("Date", if (theme.date_format == DATE_HIDDEN) "Hidden" else patternExample(theme.datePattern), "tap format · drag move · two fingers size & weight · hold everything")
        Zone.PINS -> Triple("Pins", if (!theme.pins_visible) "Hidden" else fmtX(theme.pins_size), "tap size · drag align · two fingers size · hold everything")
        Zone.GAP -> Triple("Gap", fmtDp(theme.clock_gap), "drag to open or close the gap")
        Zone.ROW -> Triple("Rows", rowStyleName(), "tap style · two fingers size & weight · hold everything")
        Zone.ROWGAP -> Triple("Row spacing", fmtX(theme.row_density), "drag to let rows breathe")
        Zone.BAR -> Triple("Search bar", barStyleName(), "tap style · two fingers size")
        Zone.EDGE_L, Zone.EDGE_R -> Triple("Inset", fmtDp(theme.side_inset), "drag to pull content away from the edge")
        Zone.BG -> Triple("Background", if (theme.background_mode == 0) "Pure black" else "Wallpaper · ${fmtPct(theme.wallpaper_dim)} dim", "tap mode · drag dim · two fingers brightness · hold everything")
        null -> Triple("Atrium studio", PRESETS[presetIdx].name, "touch anything — drag it, tap it, or use two fingers")
    }

    private fun updateChip() {
        val (z, v, h) = chipFor(lastHit?.z)
        chipZone.text = z
        chipValue.text = v
        chipHint.text = if (hintDismissed) h
        else "touch anything — drag it, tap it, or use two fingers"
    }

    private fun announce(z: String, v: String) {
        chipZone.text = z
        chipValue.text = v
    }

    private fun posStringFrom(cx: Float, anchor: Float): String {
        val w = card.width
        val a = when {
            w == 0 -> "Center"
            cx < w / 3f -> "Start"
            cx > w * 2f / 3f -> "End"
            else -> "Center"
        }
        return "$a · ${fmtPct(anchor.coerceIn(0f, 1f))}"
    }

    private fun alignStringFrom(cx: Float, w: Float): String = when {
        w == 0f -> "Center"
        cx < w / 3f -> "Start"
        cx > w * 2f / 3f -> "End"
        else -> "Center"
    }

    /* --------------------------- highlight ------------------------------ */

    private fun showHighlightFor(hit: Hit?, fast: Boolean) {
        if (hit == null) {
            highlight.hide()
            return
        }
        val r = RectF()
        val k = zoneRect(hit, r)
        if (r.isEmpty) highlight.hide() else highlight.show(r, k, fast)
    }

    private inner class HighlightView(ctx: Context) : View(ctx) {
        private val cur = RectF()
        private val from = RectF()
        private var anim: ValueAnimator? = null
        private var kind = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun show(rect: RectF, k: Int, fast: Boolean) {
            from.set(cur)
            targetRect.set(rect)
            kind = k
            animate().cancel()
            animate().alpha(1f).setDuration(if (fast) 60 else 180).start()
            anim?.cancel()
            val f0 = RectF(from)
            val f1 = RectF(targetRect)
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (fast) 90L else 220L
                interpolator = ExpoOut
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    cur.set(
                        f0.left + (f1.left - f0.left) * t,
                        f0.top + (f1.top - f0.top) * t,
                        f0.right + (f1.right - f0.right) * t,
                        f0.bottom + (f1.bottom - f0.bottom) * t
                    )
                    invalidate()
                }
                start()
            }
        }

        private val targetRect = RectF()

        fun hide() {
            animate().cancel()
            animate().alpha(0f).setDuration(200).start()
        }

        override fun onDraw(canvas: Canvas) {
            if (alpha <= 0.01f || cur.isEmpty) return
            when (kind) {
                0 -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = Math.max(1f, dp(1.25f))
                    paint.color = theme.colorAt(0.55f)
                    paint.pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(6f)), 0f)
                    canvas.drawRoundRect(cur, dp(12f), dp(12f), paint)
                }
                else -> {
                    paint.style = Paint.Style.FILL
                    paint.color = theme.colorAt(0.9f)
                    paint.pathEffect = null
                    canvas.drawRoundRect(cur, dp(2f), dp(2f), paint)
                }
            }
        }
    }

    private inner class GuideView(ctx: Context) : View(ctx) {
        var on = false
        private var xs = FloatArray(0)
        private var ys = FloatArray(0)
        private var ax = -1
        private var ay = -1
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun update(snapX: Int, snapY: Int, xsv: FloatArray, ysv: FloatArray) {
            ax = snapX; ay = snapY; xs = xsv; ys = ysv
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (!on) return
            if (ax in xs.indices) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = Math.max(1f, dp(0.75f))
                paint.color = theme.colorAt(0.30f)
                canvas.drawLine(xs[ax], 0f, xs[ax], height.toFloat(), paint)
            }
            if (ay in ys.indices) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = Math.max(1f, dp(0.75f))
                paint.color = theme.colorAt(0.30f)
                canvas.drawLine(0f, ys[ay], width.toFloat(), ys[ay], paint)
            }
            paint.style = Paint.Style.FILL
            for (gx in xs) for (gy in ys) {
                paint.color = theme.colorAt(0.45f)
                canvas.drawCircle(gx, gy, dp(3f), paint)
            }
            if (ax in xs.indices && ay in ys.indices) {
                paint.color = theme.accentColor
                canvas.drawCircle(xs[ax], ys[ay], dp(4.5f), paint)
            }
        }
    }

    /* ------------------------------ toolbar ----------------------------- */

    private fun updateToolbar() {
        doneBtn.setTextColor(theme.accentColor)
        aaBtn.typeface = Fonts.weighted(context, theme.family,
            (theme.ui_weight + 80).coerceAtMost(900))
        aaBtn.setTextColor(theme.fg)
        accentBtn.setTextColor(theme.accentColor)
        homeBtn.setTextColor(if (mode == 0) theme.fg else theme.colorAt(0.4f))
        searchBtn.setTextColor(if (mode == 1) theme.fg else theme.colorAt(0.4f))
        undoBtn.setTextColor(theme.colorAt(0.75f))
        redoBtn.setTextColor(theme.colorAt(0.75f))
        shuffleBtn.setTextColor(theme.colorAt(0.75f))
        undoBtn.isEnabled = histIdx > 0
        undoBtn.alpha = if (histIdx > 0) 1f else 0.3f
        redoBtn.isEnabled = histIdx < history.size - 1
        redoBtn.alpha = if (histIdx < history.size - 1) 1f else 0.3f
    }

    private fun switchMode(m: Int) {
        if (mode == m || modeSwitchPending) return
        mode = m
        modeSwitchPending = true
        updateToolbar()
        rContent?.let { old ->
            old.animate().alpha(0f).translationX(-dp(24f))
                .setDuration(140).setInterpolator(ExpoOut).start()
        }
        postDelayed({
            modeSwitchPending = false
            buildReplica()
            rContent?.let { n ->
                n.translationX = dp(24f)
                n.alpha = 0f
                n.animate().translationX(0f).alpha(1f)
                    .setDuration(240).setInterpolator(ExpoOut).start()
            }
        }, 130)
    }

    /* ------------------------------ history ----------------------------- */

    private fun commitHistory() {
        val s = theme.snapshot()
        if (histIdx in history.indices && history[histIdx] == s) return
        while (history.size > histIdx + 1) history.removeAt(history.size - 1)
        history.add(s)
        if (history.size > 80) history.removeAt(0)
        histIdx = history.size - 1
        updateToolbar()
    }

    private fun undo() {
        if (histIdx <= 0) return
        histIdx--
        theme.restore(history[histIdx])
        updateToolbar()
        announce("History", "undone")
        pane.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun redo() {
        if (histIdx >= history.size - 1) return
        histIdx++
        theme.restore(history[histIdx])
        updateToolbar()
        announce("History", "redone")
        pane.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun shuffle() {
        presetIdx = (presetIdx + 1) % PRESETS.size
        val p = PRESETS[presetIdx]
        theme.applyPreset(p)
        commitHistory()
        announce("Preset", p.name)
        card.animate().cancel()
        card.scaleX = 0.985f
        card.scaleY = 0.985f
        card.animate().scaleX(1f).scaleY(1f).setDuration(420)
            .setInterpolator(ExpoOut).start()
        pane.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /* ------------------------------ Aa chip ----------------------------- */

    private inner class AaButton(ctx: Context) : Label(ctx) {
        override fun performClick(): Boolean {
            super.performClick()
            theme.set(Tk.FAMILY, (theme.family + 1) % Fonts.specs.size)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            announce("Typeface", Fonts.specs[theme.family].name)
            return true
        }
    }

    private var aaDownX = 0f
    private var aaBase = 0f
    private var aaMoved = false
    private val aaLong = Runnable {
        openNodeSheet("Typography", listOf(Tk.FAMILY, Tk.UI_WEIGHT, Tk.TRACKING, Tk.CLOCK_FAMILY))
    }

    private fun wireAaGestures() {
        aaBtn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    aaDownX = e.x
                    aaBase = theme.ui_weight.toFloat()
                    aaMoved = false
                    v.postDelayed(aaLong, 420)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - aaDownX
                    if (!aaMoved && abs(dx) > dp(6f)) {
                        aaMoved = true
                        v.removeCallbacks(aaLong)
                    }
                    if (aaMoved) {
                        theme.set(Tk.UI_WEIGHT, (aaBase + dx / dp(0.9f)).toInt())
                        announce("Weight", fmtW(theme.ui_weight.toFloat()))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(aaLong)
                    if (!aaMoved) v.performClick()
                    if (aaMoved) commitHistory()
                    aaMoved = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(aaLong)
                    aaMoved = false
                }
            }
            true
        }
    }

    /* --------------------------- enter / exit --------------------------- */

    fun enter() {
        alpha = 0f
        card.translationY = dp(34f)
        card.scaleX = 0.97f
        card.scaleY = 0.97f
        animate().alpha(1f).setDuration(260).setInterpolator(ExpoOut).start()
        card.animate().translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(480).setInterpolator(ExpoOut).start()
        toolbar.alpha = 0f
        toolbar.translationY = -dp(10f)
        toolbar.animate().translationY(0f).alpha(1f).setStartDelay(140)
            .setDuration(380).setInterpolator(ExpoOut).start()
        chipBar.alpha = 0f
        chipBar.translationY = dp(10f)
        chipBar.animate().translationY(0f).alpha(1f).setStartDelay(180)
            .setDuration(380).setInterpolator(ExpoOut).start()
    }

    fun requestExit() {
        if (exiting) return
        exiting = true
        animate().alpha(0f).setDuration(260).setInterpolator(ExpoOut).start()
        card.animate().translationY(dp(26f)).scaleX(0.98f).scaleY(0.98f)
            .setDuration(260).setInterpolator(ExpoOut).start()
        postDelayed({ onDone() }, 300)
    }

    /** Returns true if it consumed a back press. */
    fun back(): Boolean {
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (c is OptionSheet) {
                c.dismiss()
                return true
            }
        }
        requestExit()
        return true
    }

    companion object {
        private val REBUILD_KEYS = setOf(
            Tk.ARRANGEMENT, Tk.DATE_FORMAT, Tk.ROW_STYLE,
            Tk.SHOW_SUBTITLES, Tk.SHOW_HEADERS
        )
    }
}
