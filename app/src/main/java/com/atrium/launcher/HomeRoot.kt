package com.atrium.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.AccelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Atrium HomeRoot
 * Minimalist, high-performance launcher architecture.
 *
 * Search columns layout:
 * [apps] <---> [contacts] <---> [search]
 *
 * Settings are opened by long-pressing empty home screen space.
 * Pinned favorites are editable via long-press on any app.
 */
@SuppressLint("ClickableViewAccessibility")
class HomeRoot(
    ctx: Context,
    private val engine: SearchEngine,
    val index: AppIndex,
    val settings: LauncherSettings,
    val registry: SettingsRegistry
) : FrameLayout(ctx) {

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private fun dp(v: Float) = context.dp(v)

    private val stage = Layer(ctx)
    private val home = VLayer(ctx)
    private val clockRow = LinearLayout(ctx)
    private val pins = VLayer(ctx)

    private val search = VLayer(ctx)
    private val colsBox = Layer(ctx)
    val input = EditText(ctx)

    val settingsView = SettingsColumnView(ctx, registry).apply {
        visibility = GONE
        onBack = { closeSettings() }
    }

    var isSettingsOpen = false
        private set

    private var currentPopup: AppContextPopup? = null

    // Search Result Columns (Apps, Contacts, Search)
    private inner class Col(val id: String, val titleName: String) {
        val view = FrameLayout(context).apply { visibility = GONE }
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
        }
        val itemsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        var live = false
        var first: Result? = null
        val pos = Spring({ applyCol(this) }, {}, 300f, 0.6f)
        val pres = Spring({ applyCol(this) }, { v -> if (v <= 0f) view.visibility = GONE }, 420f, 1f)

        init {
            scrollView.addView(itemsLayout, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))

            // Indicator right above the search bar
            val header = TextView(context).apply {
                text = titleName.uppercase()
                setTextColor(0x55FFFFFF)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4f).toInt(), 0, dp(6f).toInt())
                gravity = Gravity.CENTER
            }
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(header, LinearLayout.LayoutParams(-1, -2))
            }
            view.addView(wrap, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        }

        fun fill(res: List<Result>) {
            first = res.firstOrNull()
            itemsLayout.removeAllViews()

            // Invert the layout order so highest-priority result (index 0)
            // is placed at the bottom right above the indicator and searchbar
            for (j in res.indices.reversed()) {
                val x = res[j]
                val l = Label(context).apply {
                    styleLabel(this, 24f)
                    gravity = Gravity.CENTER
                    text = if (x.subtitle != null) "${x.title} · ${x.subtitle}" else x.title
                    setOnClickListener { x.run(this) }
                    if (x.appEntry != null) {
                        setOnLongClickListener {
                            showAppContext(x.appEntry, isFavorite = index.isPinned(x.appEntry))
                            true
                        }
                    }
                }
                itemsLayout.addView(l, LinearLayout.LayoutParams(-1, -2))
            }
            scrollView.post { scrollView.scrollTo(0, itemsLayout.height) }
        }
    }

    private val cols = LinkedHashMap<String, Col>()
    private var liveOrder: List<String> = emptyList()
    private var focusId: String? = null
    private var pinned: String? = null
    private var scrollOff = 0f
    private var colW = 0
    private val step get() = width.toFloat()

    private val spring = Spring(::setProgress, ::settled)
    var open = false
        private set

    private var topInset = 0
    private var bottomInset = 0
    private var imeShift = 0f
    private var imeAnimating = false
    private var pinSig = ""

    init {
        setBackgroundColor(Color.BLACK)

        val date = TextClock(ctx).apply {
            format12Hour = "EEE, d MMMM"; format24Hour = "EEE, d MMMM"; styleLabel(this, 24f)
        }
        val time = TextClock(ctx).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"; styleLabel(this, 24f)
        }
        clockRow.addView(date)
        clockRow.addView(time, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12f).toInt() })
        home.addView(clockRow, LinearLayout.LayoutParams(-2, -2))
        home.addView(pins, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(120f).toInt() })
        stage.addView(home, LayoutParams(-1, -1))
        addView(stage, LayoutParams(-1, -1))

        // Long press empty home space to open Settings
        home.isLongClickable = true
        home.setOnLongClickListener {
            openSettings()
            true
        }
        stage.isLongClickable = true
        stage.setOnLongClickListener {
            openSettings()
            true
        }

        input.apply {
            background = null
            setTextColor(Color.WHITE)
            setHintTextColor(0x66FFFFFF)
            hint = "search"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            includeFontPadding = false
            setSingleLine()
            minWidth = dp(220f).toInt()
            setPadding(dp(2f).toInt(), dp(8f).toInt(), dp(2f).toInt(), dp(8f).toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
            highlightColor = 0x55FFFFFF
            textCursorDrawable = GradientDrawable().apply { setColor(Color.WHITE); setSize(dp(2f).toInt(), 0) }
            isFocusable = false; isFocusableInTouchMode = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    pinned = null
                    refreshResults()
                }
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    val c = focusId?.let { cols[it] }
                    c?.first?.run?.invoke(null); true
                } else false
            }
        }
        search.gravity = Gravity.BOTTOM
        search.addView(colsBox, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addView(input, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        search.visibility = GONE
        addView(search, LayoutParams(-1, -1))

        // Standalone Settings View Overlay
        addView(settingsView, LayoutParams(-1, -1))

        setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topInset = bars.top; bottomInset = bars.bottom
            if (!imeAnimating) setImeFrom(ins)
            metrics()
            ins
        }
        setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(a: WindowInsetsAnimation) {
                if (a.typeMask and WindowInsets.Type.ime() != 0) imeAnimating = true
            }
            override fun onProgress(i: WindowInsets, l: MutableList<WindowInsetsAnimation>): WindowInsets {
                if (imeAnimating) setImeFrom(i); return i
            }
            override fun onEnd(a: WindowInsetsAnimation) {
                if (a.typeMask and WindowInsets.Type.ime() != 0) {
                    imeAnimating = false
                    rootWindowInsets?.let { setImeFrom(it) }
                }
            }
        })
        setProgress(0f)
    }

    private fun setImeFrom(i: WindowInsets) {
        val ime = i.getInsets(WindowInsets.Type.ime()).bottom
        imeShift = -max(0, ime - bottomInset).toFloat()
        applySearchY(spring.value)
    }

    private fun metrics() {
        val h = height
        val side = dp(20f).toInt()
        home.setPadding(side + dp(2f).toInt(), topInset + (h * 0.2f).toInt(), side, bottomInset)
        search.setPadding(0, topInset + dp(10f).toInt(), 0, bottomInset + dp(14f).toInt())
        settingsView.setPadding(0, topInset, 0, bottomInset)

        val nw = (width * 0.85f).toInt()
        if (nw != colW) {
            colW = nw
            for (c in cols.values) {
                c.view.layoutParams = FrameLayout.LayoutParams(colW, -1, Gravity.CENTER_HORIZONTAL)
                applyCol(c)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        metrics()
    }

    // ---------- Favorites & Context Menu ----------

    fun showAppContext(app: AppEntry, isFavorite: Boolean) {
        currentPopup?.dismiss()
        val popup = AppContextPopup(
            context = context,
            app = app,
            isFavorite = isFavorite,
            onAppInfo = { settings.openAppSettings(it.component.packageName) },
            onToggleFavorite = {
                if (isFavorite) index.removePin(it) else index.addPin(it)
                refreshPins()
            },
            onDismiss = {
                removeView(currentPopup)
                currentPopup = null
            }
        )
        currentPopup = popup
        addView(popup, LayoutParams(-1, -1))
    }

    // ---------- Settings Controls ----------

    fun openSettings() {
        if (isSettingsOpen) return
        isSettingsOpen = true
        settingsView.rebuild()
        settingsView.visibility = VISIBLE
        settingsView.alpha = 0f
        settingsView.translationY = dp(40f)
        settingsView.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(ExpoOut).start()
    }

    fun closeSettings() {
        if (!isSettingsOpen) return
        isSettingsOpen = false
        settingsView.animate().alpha(0f).translationY(dp(40f)).setDuration(160).setInterpolator(ExpoOut)
            .withEndAction { settingsView.visibility = GONE }.start()
    }

    // ---------- Data ----------

    fun refresh() {
        refreshPins()
        refreshResults()
        settingsView.rebuild()
    }

    fun refreshPins() {
        val p = index.pinned()
        val sig = p.joinToString { it.component.flattenToString() }
        if (sig == pinSig) return
        pinSig = sig
        pins.removeAllViews()
        for (e in p) {
            val t = Label(context)
            styleLabel(t, 28f)
            t.text = e.label
            t.setOnClickListener { index.launch(e, t) }
            t.setOnLongClickListener {
                showAppContext(e, isFavorite = true)
                true
            }
            pins.addView(t, LinearLayout.LayoutParams(-2, -2))
        }
    }

    fun refreshResults() {
        val query = input.text.toString()
        val columns = engine.query(query, 50)
        liveOrder = columns.map { it.id }

        if (pinned != null && pinned !in liveOrder) {
            pinned = null
        }
        focusId = pinned ?: liveOrder.firstOrNull()
        val focusIndex = max(0, liveOrder.indexOf(focusId))

        for (c in cols.values) {
            if (c.id !in liveOrder && c.live) {
                c.live = false
                c.pres.animateTo(0f)
            }
        }

        for ((i, column) in columns.withIndex()) {
            val c = cols.getOrPut(column.id) { makeCol(column.id, column.title) }
            c.fill(column.results)
            val tgt = (i - focusIndex).toFloat()
            if (!c.live) {
                c.live = true
                c.view.visibility = VISIBLE
                if (c.pres.value < 0.05f) c.pos.snap(tgt + 0.5f)
                c.pres.animateTo(1f)
            }
            c.pos.animateTo(tgt)
        }
    }

    private fun makeCol(id: String, title: String): Col {
        val c = Col(id, title)
        colsBox.addView(c.view, FrameLayout.LayoutParams(colW, -1, Gravity.CENTER_HORIZONTAL))
        return c
    }

    private fun retarget(velocity: Float = 0f) {
        val focusIndex = max(0, liveOrder.indexOf(focusId))
        for ((i, id) in liveOrder.withIndex()) {
            cols[id]?.pos?.animateTo((i - focusIndex).toFloat(), velocity)
        }
    }

    private fun applyCol(c: Col) {
        val p = c.pos.value + scrollOff
        val a = min(abs(p), 1f)
        val v = c.view
        v.translationX = p * step
        val fade = (1f - a * 2f).coerceIn(0f, 1f)
        v.alpha = c.pres.value.coerceIn(0f, 1f) * fade
        val s = 1f - 0.1f * a
        v.scaleX = s; v.scaleY = s
        v.visibility = if (v.alpha <= 0.01f || !c.live) GONE else VISIBLE
    }

    // ---------- Search Progress ----------

    private fun setProgress(p: Float) {
        val pc = p.coerceIn(0f, 1f)
        home.alpha = (1f - pc * 1.8f).coerceIn(0f, 1f)
        home.translationY = pc * dp(36f)
        home.visibility = if (pc >= 0.56f) INVISIBLE else VISIBLE
        search.alpha = (pc * 1.6f - 0.15f).coerceIn(0f, 1f)
        applySearchY(p)
    }

    private fun applySearchY(p: Float) {
        search.translationY = -(1f - p) * dp(48f) + imeShift
    }

    private fun settled(p: Float) {
        if (p <= 0f) closeCleanup()
    }

    private fun closeCleanup() {
        search.visibility = GONE
        input.setText("")
        setInputFocusable(false)
        pinned = null
        scrollOff = 0f
        liveOrder = emptyList()
        focusId = null
        for (c in cols.values) {
            c.live = false
            c.pres.snap(0f)
            c.pos.snap(0f)
            c.view.visibility = GONE
        }
    }

    private fun setInputFocusable(f: Boolean) {
        input.isFocusable = f
        input.isFocusableInTouchMode = f
    }

    fun openSearch(v: Float = 0f) {
        open = true
        prepareSearch()
        setInputFocusable(true)
        input.requestFocus()
        showIme()
        spring.animateTo(1f, v)
    }

    fun closeSearch(v: Float = 0f) {
        open = false
        hideIme()
        spring.animateTo(0f, v)
    }

    fun onBack(): Boolean {
        if (currentPopup != null) {
            currentPopup?.dismiss()
            return true
        }
        if (isSettingsOpen) {
            closeSettings()
            return true
        }
        if (open) {
            closeSearch()
            return true
        }
        return false
    }

    fun reset() {
        spring.stop()
        open = false
        spring.snap(0f)
        hideIme()
        closeCleanup()
    }

    // ---------- Blur <-> Focus ----------

    private var fv = 0f
    private var focusAnim: ValueAnimator? = null

    private fun setFocus(f: Float) {
        fv = f
        val k = 1f - f
        alpha = (f * 2.2f).coerceIn(0f, 1f)
        val s = 1f + 0.05f * k
        scaleX = s; scaleY = s
        if (Build.VERSION.SDK_INT >= 31 && settings.blurEnabled) {
            val r = k * 28f
            setRenderEffect(if (r < 0.6f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        } else if (Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(null)
        }
    }

    private fun animateFocus(to: Float, ms: Long, interp: android.animation.TimeInterpolator) {
        focusAnim?.cancel()
        val duration = (ms * settings.animSpeed).toLong()
        focusAnim = ValueAnimator.ofFloat(fv, to).apply {
            this.duration = duration
            this.interpolator = interp
            addUpdateListener { setFocus(it.animatedValue as Float) }
            start()
        }
    }

    fun prepareIntro() = setFocus(0f)
    fun playIntro() = animateFocus(1f, 320, ExpoOut)
    fun blurOut() = animateFocus(0f, 160, AccelerateInterpolator())

    private fun showIme() {
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.showSoftInput(input, 0)
    }

    private fun hideIme() {
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun prepareSearch() {
        search.visibility = VISIBLE
        refreshResults()
    }

    // ---------- Touch & Gestures ----------

    private var mode = 0
    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var startX = 0f
    private var baseP = 0f
    private val range get() = dp(180f)
    private var vt: VelocityTracker? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (currentPopup != null || isSettingsOpen) return super.onInterceptTouchEvent(e)
        track(e, false)
        return mode != 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (currentPopup != null || isSettingsOpen) return super.onTouchEvent(e)
        track(e, true)
        return true
    }

    private fun track(e: MotionEvent, own: Boolean) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                mode = 0
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt!!.addMovement(e)
                if (spring.running) beginV(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.y - downY
                val dx = e.x - downX
                if (mode == 0) {
                    if (!open) {
                        if (dy > slop && dy > abs(dx) * 1.2f) {
                            beginV(e)
                        }
                    } else {
                        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                            mode = 2
                            startX = e.x
                            parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (downY >= colsBox.bottom && dy > slop && dy > abs(dx) * 1.2f) {
                            beginV(e)
                        }
                    }
                }
                if (mode == 1) {
                    val raw = baseP + (e.y - startY) / range
                    spring.set(if (raw > 1f) 1f + (raw - 1f) * 0.2f else max(0f, raw))
                } else if (mode == 2) {
                    setScroll((e.x - startX) / step)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                val vx = vt?.xVelocity ?: 0f
                vt?.recycle()
                vt = null

                if (mode == 1) {
                    mode = 0
                    val vNorm = vy / range
                    val cur = spring.value
                    val target = when {
                        cur > 0.85f -> 1f
                        cur < 0.15f -> 0f
                        vNorm > 0.4f -> 1f
                        vNorm < -0.4f -> 0f
                        else -> if (cur >= 0.5f) 1f else 0f
                    }
                    if (target == 1f) openSearch(vNorm) else closeSearch(vNorm)
                } else if (mode == 2) {
                    mode = 0
                    endScroll(vx / step)
                }
            }
        }
    }

    private fun beginV(e: MotionEvent) {
        mode = 1
        spring.stop()
        baseP = spring.value
        startY = e.y
        prepareSearch()
        if (open) hideIme()
    }

    private fun setScroll(raw: Float) {
        if (liveOrder.isEmpty()) return
        val focusIndex = max(0, liveOrder.indexOf(focusId))
        val minSlot = -focusIndex
        val maxSlot = (liveOrder.size - 1 - focusIndex)

        val hi = -minSlot.toFloat()
        val lo = -maxSlot.toFloat()

        scrollOff = when {
            raw > hi -> hi + (raw - hi) * 0.25f
            raw < lo -> lo + (raw - lo) * 0.25f
            else -> raw
        }
        for (c in cols.values) applyCol(c)
    }

    private fun endScroll(vSlots: Float) {
        if (liveOrder.isEmpty()) return
        val proj = scrollOff + vSlots * 0.12f

        var bestId: String = liveOrder[0]
        var bestDist = Float.MAX_VALUE

        for (id in liveOrder) {
            val d = abs(cols[id]!!.pos.value + proj)
            if (d < bestDist) {
                bestDist = d
                bestId = id
            }
        }

        val off = scrollOff
        scrollOff = 0f
        for (c in cols.values) c.pos.set(c.pos.value + off)

        pinned = bestId
        focusId = bestId
        retarget(vSlots.coerceIn(-8f, 8f))
    }
}
