package com.atrium.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import android.animation.TimeInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Atrium HomeRoot
 * Clean, minimal launcher architecture.
 *
 * Columns layout:
 * [settings] <---> [apps] <---> [contacts] <---> [web]
 *
 * Indexing:
 * Settings: index = -1 (left of apps; dedicated browse/search column, excluded from global priority query)
 * Apps:     index = 0  (primary apps column, displays full app list bottom-to-top when search is empty)
 * Other:    index = 1, 2, ... (priority-ranked search columns: Contacts, Web, etc.)
 *
 * Every visual property is read from [AtriumTheme]; the design studio edits
 * the theme live and the home re-skins in place.
 */
@SuppressLint("ClickableViewAccessibility")
class HomeRoot(
    ctx: Context,
    private val engine: SearchEngine,
    private val index: AppIndex,
    private val settings: LauncherSettings,
    private val registry: SettingsRegistry,
    private val theme: AtriumTheme
) : FrameLayout(ctx) {

    private val act: Activity = ctx as Activity
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private fun dp(v: Float) = context.dp(v)

    private val stage = Layer(ctx)
    private val homeContent = FrameLayout(ctx)
    private var clockBlock: ViewGroup? = null
    private val pinsRow = LinearLayout(ctx)

    private val search = VLayer(ctx)
    private val colsBox = Layer(ctx)
    private val searchBar = VLayer(ctx)
    private val input = EditText(ctx)
    private val inputUnderline = View(ctx)

    // Settings Column (Left of apps)
    private inner class SettingsCol {
        val view = FrameLayout(context).apply { visibility = GONE }
        val settingsView = SettingsColumnView(context, registry, theme, view)
        val pos = Spring({ applySettingsCol() }, {}, 300f, 0.6f)
        val pres = Spring({ applySettingsCol() }, { v -> if (v <= 0f) view.visibility = GONE }, 420f, 1f)

        init {
            view.addView(settingsView, FrameLayout.LayoutParams(-1, -1))
            colsBox.addView(view, FrameLayout.LayoutParams(colW, -1, Gravity.CENTER_HORIZONTAL))
        }

        fun updateQuery(q: String) {
            settingsView.setQuery(q)
        }
    }

    // Dynamic Search Result Columns (Apps, Contacts, Web)
    private inner class Col(val id: String, val titleName: String) {
        val view = FrameLayout(context).apply { visibility = GONE }
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val itemsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = Label(context)

        var live = false
        var first: Result? = null
        val pos = Spring({ applyCol(this) }, {}, 300f, 0.6f)
        val pres = Spring({ applyCol(this) }, { v -> if (v <= 0f) view.visibility = GONE }, 420f, 1f)

        init {
            scrollView.addView(itemsLayout, FrameLayout.LayoutParams(-1, -2))

            val wrap = VLayer(context).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                addView(header, LinearLayout.LayoutParams(-1, -2))
                addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            view.addView(wrap, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        }

        fun fill(res: List<Result>) {
            first = res.firstOrNull()
            itemsLayout.removeAllViews()

            // Invert the layout order so highest-priority result (index 0)
            // is placed at the bottom of itemsLayout (right above the searchbar),
            // and lower-priority results extend upwards.
            for (j in res.indices.reversed()) {
                val l = HomeComposer.buildResultRow(context, theme, index, res[j])
                itemsLayout.addView(l, LinearLayout.LayoutParams(-2, -2))
            }

            itemsLayout.gravity = resultGravity()
            header.text = titleName.uppercase()
            theme.styleHeader(header)
            header.setPadding(0, 0, 0, dp(6f).toInt())
            header.gravity = Gravity.CENTER
            header.visibility = if (theme.show_headers) VISIBLE else GONE

            itemsLayout.post {
                val contentH = itemsLayout.height
                val scrollH = scrollView.height
                if (contentH < scrollH) {
                    itemsLayout.translationY = (scrollH - contentH).toFloat()
                    scrollView.scrollTo(0, 0)
                } else {
                    itemsLayout.translationY = 0f
                    scrollView.scrollTo(0, contentH - scrollH)
                }
            }
        }
    }

    private val settingsCol = SettingsCol()
    private val cols = LinkedHashMap<String, Col>()
    private var liveOrder: List<String> = emptyList() // IDs of active right-side columns: "apps", "contacts", "web"
    private var focusId: String? = null // null means settings (-1), otherwise "apps", "contacts", etc.
    private var isSettingsFocused = false
    private var pinned: String? = null
    private var scrollOff = 0f
    private var colW = 0
    private val step get() = width.toFloat()

    private val spring = Spring(::setProgress, ::settled)
    private var open = false
    private var topInset = 0
    private var bottomInset = 0
    private var imeShift = 0f
    private var imeAnimating = false
    private var pinSig = ""

    private var homeBaseY = 0f
    private var searchP = 0f
    private var stageBlur = 0f

    private val themeListener: (String?) -> Unit = { key -> onThemeChanged(key) }

    init {
        theme.addListener(themeListener)

        homeContent.addView(pinsRow, FrameLayout.LayoutParams(-1, -2))
        stage.addView(homeContent, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        addView(stage, LayoutParams(-1, -1))

        input.apply {
            background = null
            setTextColor(Color.WHITE)
            setHintTextColor(0x66FFFFFF)
            hint = "search"
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28f)
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
                    if (!isSettingsFocused) pinned = null
                    refreshResults()
                }
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    if (isSettingsFocused) {
                        // In settings, enter does nothing destructive
                        true
                    } else {
                        val c = focusId?.let { cols[it] }
                        c?.first?.run?.invoke(null); true
                    }
                } else false
            }
        }
        search.gravity = Gravity.BOTTOM
        search.addView(colsBox, LinearLayout.LayoutParams(-1, 0, 1f))
        searchBar.addView(input, LinearLayout.LayoutParams(-2, -2))
        inputUnderline.visibility = GONE
        searchBar.addView(inputUnderline, LinearLayout.LayoutParams(-1, 1).apply {
            topMargin = dp(8f).toInt()
        })
        search.addView(searchBar, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        search.visibility = GONE
        addView(search, LayoutParams(-1, -1))

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

        buildClock()
        refreshPins()
        restyleSearchChrome()
        restyleLayout()
        applyBackground()
        setProgress(0f)
    }

    // ---------- Layout ----------

    private fun setImeFrom(i: WindowInsets) {
        val ime = i.getInsets(WindowInsets.Type.ime()).bottom
        imeShift = -max(0, ime - bottomInset).toFloat()
        applySearchY(spring.value)
    }

    private fun metrics() {
        val sidePx = dp(theme.side_inset).toInt()
        stage.setPadding(
            sidePx + dp(4f).toInt(), topInset + dp(10f).toInt(),
            sidePx, bottomInset + dp(12f).toInt()
        )
        search.setPadding(0, topInset + dp(10f).toInt(), 0, bottomInset + dp(14f).toInt())
        (pinsRow.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = dp(theme.clock_gap).toInt()
        }

        val nw = (width * theme.column_width).toInt()
        if (nw != colW) {
            colW = nw
            settingsCol.view.layoutParams = FrameLayout.LayoutParams(colW, -1, Gravity.CENTER_HORIZONTAL)
            for (c in cols.values) {
                c.view.layoutParams = FrameLayout.LayoutParams(colW, -1, Gravity.CENTER_HORIZONTAL)
                applyCol(c)
            }
            applySettingsCol()
        }
        positionHome()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        metrics()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionHome()
    }

    /** Vertical anchor of the home block; translation only, never re-measure. */
    private fun positionHome() {
        val h = stage.height
        if (h == 0) return
        val avail = (h - stage.paddingTop - stage.paddingBottom - homeContent.height).coerceAtLeast(0f)
        homeBaseY = avail * theme.anchor_y
        applyHomeTransform()
    }

    private fun applyHomeTransform() {
        homeContent.translationY = homeBaseY + searchP * dp(30f)
        val s = 1f - 0.05f * searchP
        homeContent.scaleX = s
        homeContent.scaleY = s
    }

    // ---------- Theme ----------

    private fun buildClock() {
        clockBlock?.let { homeContent.removeView(it) }
        val b = HomeComposer.buildClockBlock(context, theme)
        homeContent.addView(b, 0, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or theme.hGravity()))
        for (i in 0 until b.childCount) {
            b.getChildAt(i).setOnLongClickListener { openStudio(); true }
        }
        clockBlock = b
        positionHome()
    }

    private fun restyleLayout() {
        (clockBlock?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.gravity = Gravity.TOP or theme.hGravity()
        }
        pinsRow.gravity = theme.pinsGravity()
        pinsRow.visibility = if (theme.pins_visible) VISIBLE else GONE
        homeContent.requestLayout()
    }

    private fun restylePins() {
        for (i in 0 until pinsRow.childCount) {
            (pinsRow.getChildAt(i) as? Label)?.let { HomeComposer.stylePin(it, theme) }
        }
    }

    private fun restyleSearchChrome() {
        theme.styleUi(input, theme.search_size)
        input.setTextColor(theme.fg)
        input.setHintTextColor(theme.colorAt(0.5f))
        input.highlightColor = theme.colorAt(0.28f)
        (input.textCursorDrawable as? GradientDrawable)?.setColor(theme.accentColor)
        when (theme.search_style) {
            1 -> {
                input.background = null
                input.setPadding(dp(2f).toInt(), dp(8f).toInt(), dp(2f).toInt(), dp(6f).toInt())
                inputUnderline.visibility = VISIBLE
                inputUnderline.setBackgroundColor(theme.colorAt(0.30f))
            }
            2 -> {
                input.background = GradientDrawable().apply {
                    cornerRadius = dp(999f)
                    setColor(theme.colorAt(0.10f))
                }
                input.setPadding(dp(26f).toInt(), dp(10f).toInt(), dp(26f).toInt(), dp(10f).toInt())
                inputUnderline.visibility = GONE
            }
            else -> {
                input.background = null
                input.setPadding(dp(2f).toInt(), dp(8f).toInt(), dp(2f).toInt(), dp(8f).toInt())
                inputUnderline.visibility = GONE
            }
        }
    }

    private fun resultGravity(): Int = when (theme.result_align) {
        0 -> Gravity.START
        2 -> Gravity.END
        else -> Gravity.CENTER_HORIZONTAL
    }

    private fun restyleRows() {
        for (c in cols.values) {
            theme.styleHeader(c.header)
            c.header.visibility = if (theme.show_headers) VISIBLE else GONE
            c.itemsLayout.gravity = resultGravity()
            for (i in 0 until c.itemsLayout.childCount) {
                HomeComposer.restyleResultRow(c.itemsLayout.getChildAt(i), theme)
            }
        }
    }

    private fun applyBackground() {
        val w = act.window
        if (theme.background_mode == 1) {
            // Window stays transparent so the system wallpaper shows through;
            // the dim scrim is drawn at the view level so it can animate cheaply.
            w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setBackgroundColor(
                Color.argb((theme.wallpaper_dim * 255).toInt(), 0, 0, 0)
            )
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            w.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setBackgroundColor(Color.BLACK)
        }
    }

    private fun onThemeChanged(key: String?) {
        if (key == null || key in REBUILD_CLOCK_KEYS) buildClock()
        else clockBlock?.let { HomeComposer.restyleClockBlock(it, theme) }
        restylePins()
        restyleSearchChrome()
        restyleLayout()
        when (key) {
            null, Tk.SIDE_INSET, Tk.COLUMN_WIDTH -> metrics()
            Tk.ANCHOR_Y -> positionHome()
            Tk.BACKGROUND, Tk.WALLPAPER_DIM -> applyBackground()
            else -> {}
        }
        if (key == null || key in REBUILD_ROW_KEYS) refreshResults() else restyleRows()
        settingsCol.settingsView.markDirty()
    }

    // ---------- Data ----------

    fun refresh() {
        refreshPins()
        refreshResults()
        settingsCol.settingsView.rebuildIfDirty()
    }

    private fun refreshPins() {
        val p = index.pinned()
        val sig = p.joinToString { it.component.flattenToString() }
        if (sig == pinSig) return
        pinSig = sig
        pinsRow.removeAllViews()
        for (e in p) {
            val t = Label(context)
            HomeComposer.stylePin(t, theme)
            t.text = e.label
            t.setOnClickListener { index.launch(e, t) }
            pinsRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(22f).toInt() })
        }
        restyleLayout()
    }

    private fun refreshResults() {
        val query = input.text.toString()
        settingsCol.updateQuery(query)

        val columns = engine.query(query, 50)
        liveOrder = columns.map { it.id }

        if (pinned != null && pinned !in liveOrder && !isSettingsFocused) {
            pinned = null
        }

        if (!isSettingsFocused) {
            focusId = pinned ?: liveOrder.firstOrNull()
        }

        val focusIndex = if (isSettingsFocused) -1 else max(0, liveOrder.indexOf(focusId))

        // Position settings column at slot -1 relative to apps (slot 0)
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
        val fade = (1f - a * 2f).coerceIn(0f, 1f)
        v.alpha = c.pres.value.coerceIn(0f, 1f) * fade
        val s = 1f - 0.1f * a
        v.scaleX = s; v.scaleY = s
        v.visibility = if (v.alpha <= 0.01f || !c.live) GONE else VISIBLE
    }

    private fun applySettingsCol() {
        val p = settingsCol.pos.value + scrollOff
        val a = min(abs(p), 1f)
        val v = settingsCol.view
        v.translationX = p * step
        val fade = (1f - a * 2f).coerceIn(0f, 1f)
        v.alpha = settingsCol.pres.value.coerceIn(0f, 1f) * fade
        val s = 1f - 0.1f * a
        v.scaleX = s; v.scaleY = s
        v.visibility = if (v.alpha <= 0.01f) GONE else VISIBLE
    }

    // ---------- Search progress ----------

    private fun setProgress(p: Float) {
        val pc = p.coerceIn(0f, 1f)
        searchP = pc
        stage.alpha = (1f - pc * 1.8f).coerceIn(0f, 1f)
        stage.visibility = if (pc >= 0.56f) INVISIBLE else VISIBLE
        applyHomeTransform()
        if (Build.VERSION.SDK_INT >= 31) {
            val target = if (settings.blurEnabled) pc * 22f else 0f
            if (abs(target - stageBlur) > 0.4f) {
                stageBlur = target
                stage.setRenderEffect(
                    if (target < 0.6f) null
                    else RenderEffect.createBlurEffect(target, target, Shader.TileMode.CLAMP)
                )
            }
        }
        search.alpha = (pc * 1.6f - 0.15f).coerceIn(0f, 1f)
        applySearchY(p)
    }

    private fun applySearchY(p: Float) {
        search.translationY = -(1f - p) * dp(48f) + imeShift
    }

    private fun settled(p: Float) {
        if (p <= 0f) {
            closeCleanup()
            if (studio == null) playIntro()
        }
    }

    private fun closeCleanup() {
        search.visibility = GONE
        input.setText("")
        setInputFocusable(false)
        pinned = null
        scrollOff = 0f
        liveOrder = emptyList()
        focusId = null
        isSettingsFocused = false
        settingsCol.pres.snap(0f)
        settingsCol.pos.snap(-1f)
        settingsCol.view.visibility = GONE
        for (c in cols.values) {
            c.live = false
            c.pres.snap(0f)
            c.pos.snap(0f)
            c.view.visibility = GONE
            c.itemsLayout.removeAllViews()
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
        open = true
        prepareSearch()
        showIme()
        spring.animateTo(1f, v)
    }

    fun closeSearch(v: Float = 0f) {
        open = false
        hideIme()
        spring.animateTo(0f, v)
    }

    fun onBack(): Boolean {
        studio?.let { s ->
            if (s.back()) return true
        }
        if (open) {
            if (settingsCol.settingsView.consumeBack()) return true
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
        if (studio != null) {
            val s = studio
            studio = null
            if (s != null) removeView(s)
        }
        setStudioDim(0f)
    }

    // ---------- Design studio ----------

    fun openStudio() {
        if (studio != null) return
        if (open) {
            spring.stop()
            spring.snap(0f)
            closeCleanup()
        }
        hideIme()
        setProgress(0f)
        val s = DesignSheet(context, theme, index, registry) { closeStudio() }
        studio = s
        addView(s, LayoutParams(-1, -1))
        s.enter()
        animateStudioDim(1f)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun closeStudio() {
        val s = studio ?: return
        studio = null
        animateStudioDim(0f)
        postDelayed({ removeView(s) }, 340)
    }

    private fun setStudioDim(v: Float) {
        stage.alpha = 1f - 0.55f * v
        val s = 1f - 0.03f * v
        stage.scaleX = s
        stage.scaleY = s
        if (Build.VERSION.SDK_INT >= 31) {
            val r = if (settings.blurEnabled) v * 16f else 0f
            stage.setRenderEffect(
                if (r < 0.6f) null
                else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            )
        }
    }

    private fun animateStudioDim(to: Float) {
        studioDimAnim?.cancel()
        studioDimAnim = ValueAnimator.ofFloat(studioDim, to).apply {
            duration = 320
            interpolator = ExpoOut
            addUpdateListener { setStudioDim(it.animatedValue as Float) }
            start()
        }
    }

    private var studioDim = 0f
    private var studioDimAnim: ValueAnimator? = null
    private var studio: DesignSheet? = null

    // ---------- Blur <-> focus ----------

    private var fv = 0f
    private var focusAnim: ValueAnimator? = null

    private fun setFocus(f: Float) {
        fv = f
        val k = 1f - f
        alpha = (f * 2.2f).coerceIn(0f, 1f)
        val s = 1f + 0.05f * k
        scaleX = s; scaleY = s
        if (Build.VERSION.SDK_INT >= 31) {
            val r = if (settings.blurEnabled) k * 28f else 0f
            setRenderEffect(
                if (r < 0.6f) null
                else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            )
        }
    }

    private fun animateFocus(to: Float, ms: Long, interp: TimeInterpolator) {
        focusAnim?.cancel()
        val duration = (ms * settings.animSpeed).toLong()
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
        setIntro(0f)
    }

    fun playIntro() {
        animateFocus(1f, 520, ExpoOut)
        setIntro(0f)
        clockBlock?.let { b ->
            b.animate().alpha(1f).translationY(0f).setDuration(560).setInterpolator(ExpoOut).start()
        }
        pinsRow.animate().alpha(1f).translationY(0f).setStartDelay(80)
            .setDuration(560).setInterpolator(ExpoOut).start()
    }

    private fun setIntro(v: Float) {
        clockBlock?.alpha = v
        clockBlock?.translationY = (1f - v) * dp(16f)
        pinsRow.alpha = v
        pinsRow.translationY = (1f - v) * dp(24f)
    }

    fun blurOut() {
        animateFocus(0f, 320, AccelerateInterpolator(1.4f))
        postDelayed({ if (hasWindowFocus() && fv < 1f) playIntro() }, 1400)
    }

    // ---------- Touch & Scroll Gesture ----------

    private var downX = 0f
    private var downY = 0f
    private var mode = 0 // 1 = vertical search drag, 2 = horizontal column scroll
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
        if (studio != null) return
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
                if (mode == 1) {
                    val v = (vt?.yVelocity ?: 0f) / range
                    val goOpen = when {
                        v > 1.5f -> true
                        v < -1.5f -> false
                        else -> spring.value > 0.5f
                    }
                    if (goOpen) {
                        open = true
                        showIme()
                        spring.animateTo(1f, v)
                    } else {
                        open = false
                        hideIme()
                        spring.animateTo(0f, v)
                    }
                } else if (mode == 2) {
                    endScroll((vt?.xVelocity ?: 0f) / step)
                } else if (own && e.actionMasked == MotionEvent.ACTION_UP && open &&
                    abs(e.y - downY) < slop && abs(e.x - downX) < slop) {
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
        if (open) hideIme()
    }

    private fun setScroll(raw: Float) {
        val focusIndex = if (isSettingsFocused) -1 else max(0, liveOrder.indexOf(focusId))
        // Allow scrolling between settings (-1) and the rightmost live column
        val minSlot = -1 - focusIndex // settings relative pos
        val maxSlot = (liveOrder.size - 1 - focusIndex) // rightmost relative pos

        val hi = -minSlot.toFloat() // swiping right brings left column
        val lo = -maxSlot.toFloat() // swiping left brings right column

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

        // Candidate slots: settings is at pos -1, live columns at 0, 1, 2...
        var bestId: String? = null
        var bestIsSettings = false
        var bestDist = Float.MAX_VALUE

        // Check settings dist
        val settingsDist = abs(settingsCol.pos.value + proj)
        if (settingsDist < bestDist) {
            bestDist = settingsDist
            bestIsSettings = true
        }

        // Check each live column dist
        for (id in liveOrder) {
            val d = abs(cols[id]!!.pos.value + proj)
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

        if (bestIsSettings) {
            isSettingsFocused = true
            pinned = null
            focusId = null
        } else {
            isSettingsFocused = false
            pinned = bestId
            focusId = bestId
        }

        retarget(vSlots.coerceIn(-8f, 8f))
    }

    companion object {
        const val MAX_VISIBLE_ROWS = 12
        private val REBUILD_CLOCK_KEYS = setOf(Tk.ARRANGEMENT, Tk.DATE_FORMAT)
        private val REBUILD_ROW_KEYS = setOf(Tk.ROW_STYLE, Tk.SHOW_SUBTITLES, Tk.SHOW_HEADERS)
    }
}
