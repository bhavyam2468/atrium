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
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Atrium HomeRoot
 *
 * Composition
 * -----------
 *   clock        large, light, optical centre-left of the upper third
 *   pins         bottom-anchored on exactly the baseline the search results use,
 *                so opening search dissolves one list into another in place
 *   search       bottom-anchored input + horizontally paged result columns
 *
 * Columns: [settings] <---> [apps] <---> [contacts] <---> [web]
 */
@SuppressLint("ClickableViewAccessibility")
class HomeRoot(
    ctx: Context,
    private val engine: SearchEngine,
    private val index: AppIndex,
    private val settings: LauncherSettings,
    private val registry: SettingsRegistry,
) : FrameLayout(ctx) {

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private fun dp(v: Float) = context.dp(v)
    private fun dpI(v: Float) = context.dpInt(v)

    private val stage = Layer(ctx)
    private val home = Layer(ctx)
    private val clock = ClockView(ctx, settings)
    private val pins = VLayer(ctx)

    private val search = VLayer(ctx)
    private val colsBox = Layer(ctx)
    private val homeHint = TextView(ctx)
    private val input = EditText(ctx)

    private var uiScale = 1f
    private val inputHeight get() = dpI(58f)

    // ---------- Settings column ----------

    private inner class SettingsCol {
        val view = FrameLayout(context).apply { visibility = GONE }
        val settingsView = SettingsColumnView(context, registry, settings)
        val headerLabel = TextView(context)
        val headerBack = Chevron(context)
        val pos = Spring({ applySettingsCol() }, {}, 300f, 0.78f)
        val pres = Spring({ applySettingsCol() }, { v -> if (v <= 0f) view.visibility = GONE }, 420f, 1f)

        init {
            headerLabel.apply {
                text = "SETTINGS"
                type(9.5f, 520, Ink.LOW, mono = true, tracking = 0.24f, caps = true)
            }
            headerBack.apply {
                snap(false)
                baseRotation = 180f
                visibility = GONE
            }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dpI(2f), 0, dpI(10f))
            }
            header.addView(headerBack, LinearLayout.LayoutParams(dpI(16f), dpI(16f)).apply {
                marginEnd = dpI(8f)
            })
            header.addView(headerLabel, LinearLayout.LayoutParams(-2, -2))
            header.setOnClickListener { settingsView.back() }

            settingsView.onHeader = { title, canBack ->
                headerLabel.text = title
                headerLabel.setTextColor(if (canBack) Ink.MID else Ink.LOW)
                headerBack.visibility = if (canBack) VISIBLE else GONE
            }

            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            wrap.addView(header, LinearLayout.LayoutParams(-1, -2))
            wrap.addView(settingsView, LinearLayout.LayoutParams(-1, 0, 1f))
            view.addView(wrap, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
            colsBox.addView(view, FrameLayout.LayoutParams(colW, -1, Gravity.CENTER_HORIZONTAL))
            settingsView.refreshHeader()
        }

        fun updateQuery(q: String) = settingsView.setQuery(q)
    }

    // ---------- Result columns ----------

    private inner class Col(val id: String, initialTitle: String) {
        val view = FrameLayout(context).apply { visibility = GONE }
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val itemsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val header = TextView(context).apply {
            type(9.5f, 520, Ink.LOW, mono = true, tracking = 0.24f, caps = true)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpI(8f))
        }

        var titleName: String = initialTitle
            set(value) {
                field = value
                header.text = value
            }

        var live = false
        var first: Result? = null
        var sig = ""
        val pos = Spring({ applyCol(this) }, {}, 300f, 0.78f)
        val pres = Spring({ applyCol(this) }, { v -> if (v <= 0f) view.visibility = GONE }, 420f, 1f)

        init {
            header.text = initialTitle
            scrollView.addView(itemsLayout, FrameLayout.LayoutParams(-1, -2))
            // Results are inverted: index 0 must sit on the search bar. Re-align whenever
            // the viewport is measured, not only when the list changes.
            scrollView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) alignToBottom()
            }
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            wrap.addView(header, LinearLayout.LayoutParams(-1, -2))
            // Fade the top of the scroll viewport so overflow dissolves instead of
            // being sliced by the header above it.
            val viewport = FrameLayout(context).apply { clipChildren = true }
            viewport.addView(scrollView, FrameLayout.LayoutParams(-1, -1))
            viewport.addView(
                Scrim(context),
                FrameLayout.LayoutParams(-1, dpI(40f), Gravity.TOP)
            )
            wrap.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
            view.addView(wrap, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        }

        fun fill(res: List<Result>, animate: Boolean) {
            val newSig = buildString {
                append(settings.compact).append('\u0000')
                for (r in res) {
                    append(r.title).append('\u0001').append(r.subtitle ?: "").append('\u0002')
                }
            }
            if (newSig == sig) return
            sig = newSig
            first = res.firstOrNull()
            itemsLayout.removeAllViews()

            val size = (if (settings.compact) 18.5f else 20f) * uiScale
            val padV = dpI(if (settings.compact) 5f else 6f)
            val dur = (300 * settings.animSpeed).toLong().coerceIn(120L, 700L)

            // Deepest-priority rows are added first so index 0 lands at the bottom,
            // immediately above the search bar.
            for (j in res.indices.reversed()) {
                val r = res[j]
                val label = Label(context).apply {
                    text = caption(
                        r.title,
                        r.subtitle,
                        if (r.subtitle != null) Typo.mono(context, 420) else null
                    )
                    type(size, 430, Ink.FULL)
                    gravity = Gravity.CENTER
                    letterSpacing = -0.008f
                    setPadding(dpI(8f), padV, dpI(8f), padV)
                    setOnClickListener { r.run(this) }
                }
                itemsLayout.addView(label, LinearLayout.LayoutParams(-1, -2))
                if (animate && settings.staggerReveal) {
                    // Cap the cascade: deep rows should never wait a beat to appear.
                    label.enter(delay = min(j, 12) * 16L, duration = dur, dyDp = 12f)
                }
            }

            itemsLayout.post { alignToBottom() }
        }

        fun alignToBottom() {
            val contentH = itemsLayout.height
            val scrollH = scrollView.height
            if (scrollH <= 0) return
            if (contentH < scrollH) {
                itemsLayout.translationY = (scrollH - contentH).toFloat()
                scrollView.scrollTo(0, 0)
            } else {
                itemsLayout.translationY = 0f
                scrollView.scrollTo(0, contentH - scrollH)
            }
        }
    }

    private val settingsCol = SettingsCol()
    private val cols = LinkedHashMap<String, Col>()
    private var liveOrder: List<String> = emptyList()
    private var focusId: String? = null
    private var isSettingsFocused = false
    private var pinnedColumn: String? = null
    private var scrollOff = 0f
    private var colW = 0
    private val step get() = width.toFloat()

    private val spring = Spring(::setProgress, ::settled)
    private var isOpen = false
    private var topInset = 0
    private var bottomInset = 0
    private var imeShift = 0f
    private var imeAnimating = false
    private var pinSig = ""
    private var suppressRefresh = false

    init {
        setBackgroundColor(Color.BLACK)

        home.addView(clock, LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        pins.gravity = Gravity.CENTER_HORIZONTAL
        home.addView(
            pins,
            LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dpI(14f) + inputHeight
            }
        )

        // Occupies exactly the box the search field will fill, so opening search is a swap
        // in place rather than a new layer arriving.
        homeHint.apply {
            text = "SEARCH"
            type(11f, 450, 0x54FFFFFF, mono = true, tracking = 0.26f, caps = true)
            gravity = Gravity.CENTER
            isClickable = false
        }
        home.addView(
            homeHint,
            LayoutParams(-1, inputHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dpI(14f)
            }
        )
        stage.addView(home, LayoutParams(-1, -1))
        addView(stage, LayoutParams(-1, -1))

        input.apply {
            background = null
            setTextColor(Ink.FULL)
            setHintTextColor(0x4DFFFFFF)
            hint = "search"
            gravity = Gravity.CENTER
            typeface = Typo.sans(context, 400)
            includeFontPadding = false
            setSingleLine()
            minWidth = dpI(120f)
            setPadding(dpI(2f), dpI(8f), dpI(2f), dpI(8f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
            highlightColor = 0x33FFFFFF
            textCursorDrawable = GradientDrawable().apply {
                setColor(Color.WHITE)
                setSize(dpI(2f), 0)
            }
            isFocusable = false
            isFocusableInTouchMode = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressRefresh) return
                    if (!isSettingsFocused) pinnedColumn = null
                    refreshResults()
                }
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    if (!isSettingsFocused) {
                        focusId?.let { cols[it] }?.first?.run?.invoke(null)
                    }
                    true
                } else false
            }
        }

        search.gravity = Gravity.BOTTOM
        search.addView(colsBox, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addView(
            input,
            LinearLayout.LayoutParams(-2, inputHeight).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        search.visibility = GONE
        addView(search, LayoutParams(-1, -1))

        setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topInset = bars.top
            bottomInset = bars.bottom
            if (!imeAnimating) setImeFrom(ins)
            metrics()
            ins
        }
        setWindowInsetsAnimationCallback(
            object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(a: WindowInsetsAnimation) {
                    if (a.typeMask and WindowInsets.Type.ime() != 0) imeAnimating = true
                }
                override fun onProgress(i: WindowInsets, l: MutableList<WindowInsetsAnimation>): WindowInsets {
                    if (imeAnimating) setImeFrom(i)
                    return i
                }
                override fun onEnd(a: WindowInsetsAnimation) {
                    if (a.typeMask and WindowInsets.Type.ime() != 0) {
                        imeAnimating = false
                        rootWindowInsets?.let { setImeFrom(it) }
                    }
                }
            }
        )
        setProgress(0f)
    }

    // ---------- Layout ----------

    private fun setImeFrom(i: WindowInsets) {
        val ime = i.getInsets(WindowInsets.Type.ime()).bottom
        imeShift = -max(0, ime - bottomInset).toFloat()
        applySearchY(spring.value)
    }

    private fun metrics() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val s = (w / 1080f).coerceIn(0.82f, 1.35f)
        val scaleChanged = abs(s - uiScale) > 0.001f
        uiScale = s

        val side = dpI(22f)
        home.setPadding(side, topInset + (h * 0.165f).toInt(), side, bottomInset)
        search.setPadding(0, topInset + dpI(10f), 0, bottomInset + dpI(14f))
        clock.scale = s
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f * s)
        homeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * s)
        input.maxWidth = (w * 0.88f).toInt()

        val nw = (w * 0.86f).toInt()
        if (nw != colW) {
            colW = nw
            settingsCol.view.layoutParams = FrameLayout.LayoutParams(colW, -1, Gravity.CENTER_HORIZONTAL)
            for (c in cols.values) {
                c.view.layoutParams = FrameLayout.LayoutParams(colW, -1, Gravity.CENTER_HORIZONTAL)
                applyCol(c)
            }
            applySettingsCol()
        }
        if (scaleChanged) refreshPins(force = true)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        metrics()
    }

    // ---------- Data ----------

    fun refresh() {
        clock.sync()
        refreshPins(force = true)
        refreshResults()
    }

    /** Only when the shape of the settings list changed, never for a plain value change. */
    fun refreshSettings() {
        settingsCol.settingsView.rebuild()
    }

    private fun refreshPins(force: Boolean = false) {
        val p = index.pinned().take(MAX_PINS)
        val sig = buildString {
            append(uiScale).append('|').append(settings.compact).append('|')
            for (e in p) append(e.component.flattenToString()).append(',')
        }
        if (!force && sig == pinSig) return
        val firstFill = pins.childCount == 0
        pinSig = sig
        pins.removeAllViews()

        val size = (if (settings.compact) 21f else 23f) * uiScale
        val dur = (420 * settings.animSpeed).toLong().coerceIn(160L, 900L)
        p.forEachIndexed { i, e ->
            val t = Label(context).apply {
                text = e.label
                type(size, 440, Ink.FULL)
                gravity = Gravity.CENTER
                letterSpacing = -0.012f
                val v = dpI(if (settings.compact) 6f else 8f)
                setPadding(dpI(6f), v, dpI(6f), v)
                setOnClickListener { index.launch(e, this) }
            }
            pins.addView(t, LinearLayout.LayoutParams(-2, -2))
            if (firstFill && settings.staggerReveal) {
                t.enter(delay = 90L + i * 40L, duration = dur, dyDp = 16f)
            }
        }
    }

    private fun refreshResults() {
        val query = input.text.toString()
        settingsCol.updateQuery(query)

        val columns = engine.query(query, 60)
        liveOrder = columns.map { it.id }

        if (pinnedColumn != null && pinnedColumn !in liveOrder && !isSettingsFocused) pinnedColumn = null
        if (!isSettingsFocused) focusId = pinnedColumn ?: liveOrder.firstOrNull()

        val focusIndex = if (isSettingsFocused) -1 else max(0, liveOrder.indexOf(focusId))
        val targetSettingsSlot = (-1 - focusIndex).toFloat()

        if (settingsCol.pres.value < 0.05f) {
            settingsCol.pos.snap(targetSettingsSlot)
            settingsCol.view.visibility = VISIBLE
            settingsCol.pres.animateTo(1f)
        } else {
            settingsCol.pos.animateTo(targetSettingsSlot)
        }

        for (c in cols.values) {
            if (c.id !in liveOrder && c.live) {
                c.live = false
                c.pres.animateTo(0f)
            }
        }

        for ((i, column) in columns.withIndex()) {
            val c = cols.getOrPut(column.id) { makeCol(column.id, column.title) }
            c.titleName = column.title.uppercase()

            val wasLive = c.live
            c.fill(column.results, animate = !wasLive || query.isNotEmpty())
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
        val focusIndex = if (isSettingsFocused) -1 else max(0, liveOrder.indexOf(focusId))
        settingsCol.pos.animateTo((-1 - focusIndex).toFloat(), velocity)
        for ((i, id) in liveOrder.withIndex()) {
            cols[id]?.pos?.animateTo((i - focusIndex).toFloat(), velocity)
        }
    }

    private fun applyCol(c: Col) {
        val p = c.pos.value + scrollOff
        val a = min(abs(p), 1f)
        val v = c.view
        v.translationX = p * step
        v.translationY = a * dp(14f)
        // Smoothstep falloff reads softer than a linear fade at the pager edges.
        val soft = a * a * (3f - 2f * a)
        v.alpha = (c.pres.value * (1f - soft)).coerceIn(0f, 1f)
        val s = 1f - 0.06f * soft
        v.scaleX = s
        v.scaleY = s
        v.visibility = if (v.alpha <= 0.01f || !c.live) GONE else VISIBLE
    }

    private fun applySettingsCol() {
        val p = settingsCol.pos.value + scrollOff
        val a = min(abs(p), 1f)
        val v = settingsCol.view
        v.translationX = p * step
        v.translationY = a * dp(14f)
        val soft = a * a * (3f - 2f * a)
        v.alpha = (settingsCol.pres.value * (1f - soft)).coerceIn(0f, 1f)
        val s = 1f - 0.06f * soft
        v.scaleX = s
        v.scaleY = s
        v.visibility = if (v.alpha <= 0.01f) GONE else VISIBLE
    }

    // ---------- Search progress ----------

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun setProgress(p: Float) {
        val pc = p.coerceIn(0f, 1f)

        val homeFade = 1f - smooth(0f, 0.55f, pc)
        home.alpha = homeFade
        home.translationY = -pc * dp(30f)
        val hs = 1f - 0.035f * pc
        home.scaleX = hs
        home.scaleY = hs
        home.visibility = if (homeFade <= 0.01f) INVISIBLE else VISIBLE

        homeHint.alpha = 1f - smooth(0f, 0.28f, pc)

        val searchFade = smooth(0.14f, 0.72f, pc)
        search.alpha = searchFade
        search.visibility = if (searchFade <= 0.004f) GONE else VISIBLE

        applySearchY(pc)
    }

    private fun applySearchY(p: Float) {
        search.translationY = -(1f - p) * dp(58f) + imeShift
    }

    private fun settled(p: Float) {
        if (p <= 0f) closeCleanup()
    }

    private fun closeCleanup() {
        search.visibility = GONE
        suppressRefresh = true
        input.setText("")
        suppressRefresh = false
        setInputFocusable(false)
        pinnedColumn = null
        scrollOff = 0f
        liveOrder = emptyList()
        focusId = null
        isSettingsFocused = false
        settingsCol.pres.snap(0f)
        settingsCol.pos.snap(-1f)
        settingsCol.view.visibility = GONE
        for (c in cols.values) {
            c.live = false
            c.sig = ""
            c.pres.snap(0f)
            c.pos.snap(0f)
            c.view.visibility = GONE
        }
    }

    private fun setInputFocusable(f: Boolean) {
        input.isFocusable = f
        input.isFocusableInTouchMode = f
    }

    private fun prepareSearch() {
        search.visibility = VISIBLE
        setInputFocusable(true)
        refreshResults()
    }

    private fun showIme() {
        input.requestFocus()
        windowInsetsController?.show(WindowInsets.Type.ime())
    }

    private fun hideIme() {
        windowInsetsController?.hide(WindowInsets.Type.ime())
        input.clearFocus()
    }

    fun openSearch(v: Float = 0f) {
        isOpen = true
        prepareSearch()
        showIme()
        spring.animateTo(1f, v)
    }

    fun closeSearch(v: Float = 0f) {
        isOpen = false
        hideIme()
        spring.animateTo(0f, v)
    }

    fun onBack(): Boolean {
        if (isOpen) {
            if (isSettingsFocused && settingsCol.settingsView.back()) return true
            closeSearch()
            return true
        }
        return false
    }

    fun reset() {
        clock.pause()
        spring.stop()
        isOpen = false
        spring.snap(0f)
        hideIme()
        closeCleanup()
    }

    // ---------- Blur <-> focus ----------

    private var fv = 0f
    private var focusAnim: ValueAnimator? = null

    private fun setFocus(f: Float) {
        fv = f
        val k = 1f - f
        alpha = (f * 2.2f).coerceIn(0f, 1f)
        val s = 1f + 0.035f * k
        scaleX = s
        scaleY = s
        if (Build.VERSION.SDK_INT >= 31 && settings.blurEnabled) {
            val r = k * settings.blurRadius
            setRenderEffect(
                if (r < 0.5f) null
                else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            )
        } else if (Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(null)
        }
    }

    private fun animateFocus(to: Float, ms: Long, interp: android.animation.TimeInterpolator) {
        focusAnim?.cancel()
        val duration = (ms * settings.animSpeed).toLong().coerceIn(80L, 1400L)
        focusAnim = ValueAnimator.ofFloat(fv, to).apply {
            this.duration = duration
            this.interpolator = interp
            addUpdateListener { setFocus(it.animatedValue as Float) }
            start()
        }
    }

    fun prepareIntro() {
        focusAnim?.cancel()
        setFocus(0f)
    }

    fun playIntro() {
        clock.resume()
        animateFocus(1f, 560, ExpoOut)
    }

    fun blurOut() {
        animateFocus(0f, 320, AccelerateInterpolator(1.4f))
        postDelayed({ if (hasWindowFocus() && fv < 1f) playIntro() }, 1500)
    }

    // ---------- Gestures ----------

    private var downX = 0f
    private var downY = 0f
    private var mode = 0 // 1 = vertical search drag, 2 = horizontal column paging
    private var startY = 0f
    private var baseP = 0f
    private var startX = 0f
    private var vt: VelocityTracker? = null
    private val range get() = min(height * 0.3f, dp(260f))

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        track(e, false)
        return mode != 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
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
                    if (!isOpen) {
                        if (dy > slop && dy > abs(dx) * 1.2f) beginV(e)
                    } else {
                        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                            mode = 2
                            settingsCol.settingsView.ensureRendered()
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
                if (mode == 1) {
                    val v = (vt?.yVelocity ?: 0f) / range
                    val goOpen = when {
                        v > 1.5f -> true
                        v < -1.5f -> false
                        else -> spring.value > 0.5f
                    }
                    if (goOpen) {
                        isOpen = true
                        showIme()
                        spring.animateTo(1f, v)
                        Haptics.tick(this)
                    } else {
                        isOpen = false
                        hideIme()
                        spring.animateTo(0f, v)
                    }
                } else if (mode == 2) {
                    endScroll((vt?.xVelocity ?: 0f) / step)
                } else if (own && e.actionMasked == MotionEvent.ACTION_UP && isOpen &&
                    abs(e.y - downY) < slop && abs(e.x - downX) < slop
                ) {
                    closeSearch()
                }
                mode = 0
                vt?.recycle()
                vt = null
            }
        }
    }

    private fun beginV(e: MotionEvent) {
        mode = 1
        spring.stop()
        baseP = spring.value
        startY = e.y
        prepareSearch()
        if (isOpen) hideIme()
    }

    private fun setScroll(raw: Float) {
        val focusIndex = if (isSettingsFocused) -1 else max(0, liveOrder.indexOf(focusId))
        val minSlot = -1 - focusIndex
        val maxSlot = liveOrder.size - 1 - focusIndex

        val hi = -minSlot.toFloat()
        val lo = -maxSlot.toFloat()

        scrollOff = when {
            raw > hi -> hi + (raw - hi) * 0.25f
            raw < lo -> lo + (raw - lo) * 0.25f
            else -> raw
        }
        applySettingsCol()
        for (c in cols.values) applyCol(c)
    }

    private fun endScroll(vSlots: Float) {
        val proj = scrollOff + vSlots * 0.12f

        var bestId: String? = null
        var bestIsSettings = false
        var bestDist = Float.MAX_VALUE

        val settingsDist = abs(settingsCol.pos.value + proj)
        if (settingsDist < bestDist) {
            bestDist = settingsDist
            bestIsSettings = true
        }
        for (id in liveOrder) {
            val c = cols[id] ?: continue
            val d = abs(c.pos.value + proj)
            if (d < bestDist) {
                bestDist = d
                bestIsSettings = false
                bestId = id
            }
        }

        val off = scrollOff
        scrollOff = 0f
        settingsCol.pos.set(settingsCol.pos.value + off)
        for (c in cols.values) c.pos.set(c.pos.value + off)

        if (!bestIsSettings && bestId == null) bestIsSettings = true
        val changed = bestIsSettings != isSettingsFocused || (!bestIsSettings && bestId != focusId)
        if (bestIsSettings) {
            isSettingsFocused = true
            pinnedColumn = null
            focusId = null
        } else {
            isSettingsFocused = false
            pinnedColumn = bestId
            focusId = bestId
        }
        if (changed) Haptics.tick(this)

        // Settings is a reading surface: give it the room the keyboard was taking.
        if (input.text.isEmpty()) {
            if (bestIsSettings) hideIme() else showIme()
        }

        retarget(vSlots.coerceIn(-8f, 8f))
    }

    companion object {
        const val MAX_PINS = 8
    }
}
