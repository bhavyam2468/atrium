package com.atrium.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * ATRIUM · HOME
 * ────────────────────────────────────────────────────────────────────────────
 * The composition layer: atmosphere, clock, apps, search, results, the studio
 * HUD, and the gesture arbitration that makes direct manipulation feel obvious.
 *
 * Columns (horizontal):  [ settings ] ← [ apps ] → [ contacts ] → [ web ]
 * Vertical:              drag down anywhere → the search surface rises
 *
 * Touch vocabulary on the home screen
 *   tap            → act (launch an app, next variant, open search)
 *   long-press     → take hold of whatever is under the finger and shape it
 *   drag (page)    → columns or search
 */
@SuppressLint("ClickableViewAccessibility")
class HomeRoot(
    ctx: Context,
    private val engine: SearchEngine,
    private val index: AppIndex,
    private val settings: LauncherSettings,
    private val tokens: Tokens,
    private val registry: SettingsRegistry
) : FrameLayout(ctx) {

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private fun dp(v: Float) = dpOf(context, v)

    private val bg = BackgroundView(ctx, tokens)
    private val stage = Layer(ctx)
    private val clock = ClockView(ctx, tokens)
    private val pins = PinsView(ctx, tokens, index)
    private val whisper = WhisperView(ctx)
    private val scrim = View(ctx)

    private val search = VLayer(ctx)
    private val colsBox = Layer(ctx)
    private val input = EditText(ctx)
    private val inputRow = LinearLayout(ctx)
    private val fieldBox = FrameLayout(ctx).apply { setWillNotDraw(true) }
    private val fieldRule = View(ctx)
    private val glyph = TextView(ctx)
    private val fieldBg = GradientDrawable()

    private val hud = HudView(ctx)
    private val compass = CompassView(ctx)

    private var ink = Ink(tokens)

    private var topInset = 0
    private var bottomInset = 0
    private var imeShift = 0f
    private var imeAnimating = false
    private var colW = 0

    private val spring = Spring(::setProgress, ::settled, 380f, dampFor(tokens))
    private var open = false
    private var scrollOff = 0f
    private val step get() = width.toFloat().coerceAtLeast(1f)

    // ════════════════════════════════════════════════════════════════════════
    //  COLUMNS
    // ════════════════════════════════════════════════════════════════════════

    private inner class Col(val id: String, val titleName: String) {
        val view = FrameLayout(context).apply { visibility = GONE }
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }
        val itemsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        val header = TextView(context)
        var live = false
        var first: Result? = null
        val fade = Spring({ applyCol(this) }, {}, 420f, 1f)
        val pos = Spring({ applyCol(this) }, {}, 340f, dampFor(tokens))

        init {
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
            }
            wrap.addView(header, LinearLayout.LayoutParams(-1, -2))
            wrap.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
            scrollView.addView(itemsLayout, LayoutParams(-1, -2))
            view.addView(wrap, LayoutParams(-1, -1, Gravity.BOTTOM))
        }

        fun fill(res: List<Result>, animateIn: Boolean = false) {
            first = res.firstOrNull()
            itemsLayout.removeAllViews()
            val maxRows = tokens.i(K.listMaxRows).coerceIn(4, 24)
            val slice = res.take(maxRows)
            val stagger = animateIn && tokens.b(K.listStagger) && tokens.b(K.motionStagger)
            for (j in slice.indices.reversed()) {
                val x = slice[j]
                val row = ResultRow(context, tokens, x)
                row.onTap = { v -> x.run(v) }
                // Icons are only fetched when the token asks for them: resolving
                // a hundred drawables to paint nothing is wasted work on a phone.
                if (tokens.b(K.listIcons) && x.appEntry != null) row.setIcon(index.icon(x.appEntry))
                row.revealDelay = if (stagger) ((slice.size - 1 - j) * 22L) else -1L
                row.isCaret = (j == 0)
                itemsLayout.addView(row, LayoutParams(-1, -2))
            }
            itemsLayout.post {
                alignScroll()
                for (i in 0 until itemsLayout.childCount) {
                    val row = itemsLayout.getChildAt(i) as? ResultRow ?: continue
                    val delay = row.revealDelay
                    if (delay >= 0L) row.animateIn(delay)
                }
            }
        }

        fun alignScroll() {
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

    private val cols = LinkedHashMap<String, Col>()
    private var liveOrder: List<String> = emptyList()
    private var focusId: String? = null
    private var pinnedCol: String? = null

    private inner class SettingsCol {
        val view = FrameLayout(context).apply { visibility = GONE }
        val deck = SettingsColumnView(context, registry, tokens)
        val fade = Spring({ applySettingsCol() }, {}, 420f, 1f)
        val pos = Spring({ applySettingsCol() }, {}, 340f, dampFor(tokens))

        init {
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
            }
            wrap.addView(deck, LinearLayout.LayoutParams(-1, 0, 1f))
            view.addView(wrap, LayoutParams(-1, -1, Gravity.BOTTOM))
            deck.onNeedRefresh = {
                ink = Ink(tokens)
                bg.refresh()
                clock.refresh(false)
                pins.refresh()
                styleSearch()
                for (c in cols.values) {
                    styleHeader(c)
                    for (i in 0 until c.itemsLayout.childCount) {
                        (c.itemsLayout.getChildAt(i) as? ResultRow)?.refresh()
                    }
                }
                settingsCol.deck.setPalette(ink)
                pins.hint(if (isSettingsFocused) 1f else 0f)
            }
        }

        fun updateQuery(q: String) = deck.setQuery(q)
    }

    private val settingsCol by lazy { SettingsCol() }
    private var isSettingsFocused = false

    // ════════════════════════════════════════════════════════════════════════
    //  INIT
    // ════════════════════════════════════════════════════════════════════════

    init {
        setBackgroundColor(Color.BLACK)

        addView(bg, LayoutParams(-1, -1))
        scrim.setBackgroundColor(Color.BLACK)
        scrim.alpha = 0f
        addView(scrim, LayoutParams(-1, -1))

        clock.onLayoutChanged = { whisper.position(clock, pins) }
        stage.addView(clock, LayoutParams(-1, -1))
        stage.addView(pins, LayoutParams(-1, -1))
        addView(stage, LayoutParams(-1, -1))

        addView(whisper, LayoutParams(-1, -1))

        buildSearch()
        addView(search, LayoutParams(-1, -1))
        search.visibility = GONE

        addView(compass, LayoutParams(-1, -1))
        addView(hud, LayoutParams(-1, -1))

        HeroFlash.onChange = { bg.flash() }
        pins.onLaunch = { e, v -> index.launch(e, v) }
        pins.onLongPress = { e -> pinMenu(e) }

        setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topInset = bars.top
            bottomInset = bars.bottom
            if (!imeAnimating) setImeFrom(ins)
            metrics()
            ins
        }
        setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
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
        })

        Studio.markInert("pins.app")
        Type.uiFaceId = tokens.s(K.typeFace)
        tokens.addListener { keys -> onTokensChanged(keys) }
        refreshTheme()
        setProgress(0f)
        teachOnce()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SEARCH FIELD
    // ════════════════════════════════════════════════════════════════════════

    private fun buildSearch() {
        input.apply {
            background = null
            setTextColor(ink.primary)
            setHintTextColor(ink.tertiary)
            hint = tokens.s(K.searchHint)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine()
            minWidth = dp(40f).toInt()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
            highlightColor = 0x33FFFFFF
            textCursorDrawable = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                setSize(dp(1.6f).toInt(), 0)
            }
            isFocusable = false
            isFocusableInTouchMode = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!isSettingsFocused) pinnedCol = null
                    refreshResults()
                }
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) {
                    if (isSettingsFocused) true
                    else {
                        val r = focusId?.let { cols[it] }?.first
                        if (r != null) { r.run(null); true } else { closeSearch(); true }
                    }
                } else false
            }
        }

        glyph.apply {
            setTextColor(ink.tertiary)
            typeface = Type.ui(context, 400)
            includeFontPadding = false
            gravity = Gravity.CENTER
            visibility = GONE
            setPadding(0, 0, dp(7f).toInt(), 0)
        }

        inputRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(glyph, LinearLayout.LayoutParams(-2, -2))
            addView(input, LinearLayout.LayoutParams(-2, -2))
        }

        fieldRule.setBackgroundColor(ink.hairline)
        fieldBox.addView(inputRow, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        fieldBox.addView(fieldRule, FrameLayout.LayoutParams(-1, max(1, dp(1f).toInt()), Gravity.BOTTOM))
        search.gravity = Gravity.BOTTOM
        search.addView(colsBox, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addView(fieldBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun styleSearch() {
        val d = Density(tokens)
        val size = 25f * d.base * d.search
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        input.typeface = Type.ui(context, 400)
        input.letterSpacing = -0.014f
        input.setPadding(dp(2f).toInt(), dp(6f).toInt(), dp(2f).toInt(), dp(6f).toInt())
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * 0.9f)
        glyph.typeface = Type.ui(context, 300)
        val g = tokens.s(K.searchGlyph)
        glyph.visibility = if (g == "none") GONE else VISIBLE
        glyph.text = when (g) {
            "chevron" -> "›"
            "slash" -> "/"
            "search" -> "⌕"
            "dot" -> "●"
            "plus" -> "+"
            else -> ""
        }
        glyph.alpha = 0.5f
        glyph.setTextColor(ink.primary)
        val hintRaw = tokens.s(K.searchHint)
        input.hint = when (tokens.s(K.searchHintCase)) {
            "upper" -> hintRaw.uppercase(Locale.getDefault())
            "lower" -> hintRaw.lowercase(Locale.getDefault())
            else -> hintRaw
        }
        input.setTextColor(ink.primary)
        input.setHintTextColor(ink.tertiary)

        // the field's horizontal gutter is fixed; the inset token only moves it
        // off the bottom edge, which is what the gesture promises
        val gutter = dp(Inset.l * tokens.f(K.typeGutter).coerceIn(0.5f, 2f)).toInt()
        val inset = (dp(10f) * tokens.f(K.searchPad)).toInt()
        search.setPadding(gutter, 0, gutter, bottomInset + inset)

        inputRow.gravity = when (tokens.s(K.searchAlign)) {
            "start" -> Gravity.START or Gravity.CENTER_VERTICAL
            "end" -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.CENTER
        }

        val style = tokens.s(K.searchStyle)
        // the rule under the text, visible for underlined and ruled fields
        val ruleOn = style == "underline" || style == "rule" || tokens.b(K.searchRule)
        fieldRule.visibility = if (ruleOn) View.VISIBLE else View.GONE
        fieldRule.setBackgroundColor(withAlpha(ink.primary, if (style == "rule") 0.22f else 0.16f))
        (fieldRule.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.height = max(1, dp(1f).toInt())
            fieldRule.layoutParams = lp
        }

        when (style) {
            "pill", "box" -> {
                fieldBg.setShape(GradientDrawable.RECTANGLE)
                fieldBg.setCornerRadius(if (style == "pill") dp(40f) else dp(10f))
                fieldBg.setColor(ink.surface)
                fieldBg.setStroke(max(1, dp(1f).toInt()), ink.hairline)
                inputRow.background = fieldBg
                inputRow.setPadding(dp(20f).toInt(), dp(8f).toInt(), dp(20f).toInt(), dp(8f).toInt())
            }
            else -> {
                inputRow.background = null
                inputRow.setPadding(dp(2f).toInt(), dp(6f).toInt(), dp(2f).toInt(),
                    dp(if (ruleOn) 10f else 6f).toInt())
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  THEME
    // ════════════════════════════════════════════════════════════════════════

    private fun refreshTheme() {
        if (bgRefreshing) return
        bgRefreshing = true
        try { refreshThemeInner() } finally { bgRefreshing = false }
    }

    private var bgRefreshing = false

    private fun refreshThemeInner() {
        Type.uiFaceId = tokens.s(K.typeFace)
        ink = Ink(tokens)
        bg.refresh()
        clock.refresh(false)
        pins.refresh()
        styleSearch()
        hud.setPalette(ink.primary)
        compass.setPalette(ink.primary)
        whisper.setColor(ink.tertiary)
        settingsCol.deck.setPalette(ink)
        for (c in cols.values) {
            styleHeader(c)
            for (i in 0 until c.itemsLayout.childCount) {
                (c.itemsLayout.getChildAt(i) as? ResultRow)?.refresh()
            }
        }
        settings.applyBars()
        context.getSharedPreferences("atrium_tokens", Context.MODE_PRIVATE).edit()
            .putBoolean("haptics", tokens.b(K.motionHaptics)).apply()
        whisper.position(clock, pins)
    }

    private fun onTokensChanged(keys: Set<String>) {
        if (keys.isEmpty()) return
        var all = false
        var bgDirty = false
        var clockDirty = false
        var pinsDirty = false
        var listDirty = false
        var searchDirty = false
        var deckDirty = false
        var system = false
        for (k in keys) {
            if (k.isEmpty()) { all = true; continue }
            when {
                k.startsWith("bg.") -> { bgDirty = true; deckDirty = deckDirty || k == K.bgMode }
                k.startsWith("clock.") -> { clockDirty = true; if (k == K.clockOn) deckDirty = true }
                k.startsWith("pins.") -> { pinsDirty = true; if (k == K.pinsOn) deckDirty = true }
                k.startsWith("list.") -> listDirty = true
                k.startsWith("search.") -> searchDirty = true
                k.startsWith("type.") -> {
                    all = true
                    if (k == K.typeGutter) metrics()
                    if (k == K.typeFace) { Type.uiFaceId = tokens.s(K.typeFace); deckDirty = true }
                }
                k.startsWith("motion.") -> {
                    if (k == K.motionSpring) {
                        val d = dampFor(tokens)
                        spring.feel(d)
                        for (c in cols.values) c.pos.feel(d)
                        settingsCol.pos.feel(d)
                    }
                }
                k.startsWith("sys.") -> {
                    system = true
                    if (k == K.sysColumnContacts || k == K.sysColumnWeb) refreshResults()
                }
            }
        }
        if (all) {
            bgDirty = true; clockDirty = true; pinsDirty = true
            listDirty = true; searchDirty = true; deckDirty = true
        }
        if (bgDirty) bg.refresh()
        if (all) {
            ink = Ink(tokens)
            hud.setPalette(ink.primary)
            compass.setPalette(ink.primary)
            whisper.setColor(ink.tertiary)
            settingsCol.deck.setPalette(ink)
        }
        if (clockDirty) { clock.refresh(true); whisper.position(clock, pins) }
        if (pinsDirty) pins.refresh()
        if (all) { ink = Ink(tokens); hud.setPalette(ink.primary); compass.setPalette(ink.primary) }
        if (searchDirty) styleSearch()
        if (listDirty) {
            ink = Ink(tokens)
            for (c in cols.values) {
                styleHeader(c)
                for (i in 0 until c.itemsLayout.childCount) {
                    (c.itemsLayout.getChildAt(i) as? ResultRow)?.refresh()
                }
            }
        }
        if (system) settings.applyBars()
        if (deckDirty) settingsCol.deck.rebuild() else settingsCol.deck.syncValues()
    }

    fun refresh() {
        pins.refresh()
        refreshResults()
        settingsCol.deck.invalidateDeck()
    }

    fun applyTheme() = refreshTheme()

    private fun styleHeader(c: Col) {
        val d = Density(tokens)
        c.header.visibility = if (tokens.b(K.listHeader)) View.VISIBLE else View.GONE
        c.header.text = c.titleName.uppercase(Locale.getDefault())
        c.header.letterSpacing = 0.22f
        c.header.typeface = Type.ui(context, 520)
        c.header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * d.base * d.header)
        c.header.setTextColor(ink.tertiary)
        c.header.includeFontPadding = false
        c.header.gravity = when (tokens.s(K.listAlign)) {
            "start" -> Gravity.START
            "end" -> Gravity.END
            else -> Gravity.CENTER
        }
        c.header.setPadding(0, 0, 0, dp(7f).toInt())
    }

    // ════════════════════════════════════════════════════════════════════════
    //  RESULTS
    // ════════════════════════════════════════════════════════════════════════

    private fun refreshResults() {
        val query = input.text.toString()
        settingsCol.updateQuery(query)

        val columns = engine.query(query, 60)
        liveOrder = columns.map { it.id }

        if (pinnedCol != null && pinnedCol !in liveOrder && !isSettingsFocused) pinnedCol = null
        if (!isSettingsFocused) focusId = pinnedCol ?: liveOrder.firstOrNull()

        val focusIndex = if (isSettingsFocused) -1 else max(0, liveOrder.indexOf(focusId))
        val targetSettingsSlot = (-1 - focusIndex).toFloat()

        if (settingsCol.fade.value < 0.05f) {
            settingsCol.pos.snap(targetSettingsSlot)
            settingsCol.view.visibility = VISIBLE
            settingsCol.fade.animateTo(1f)
        } else {
            settingsCol.pos.animateTo(targetSettingsSlot)
        }

        for (c in cols.values) {
            if (c.id !in liveOrder && c.live) {
                c.live = false
                c.fade.animateTo(0f)
            }
        }

        for ((i, column) in columns.withIndex()) {
            val c = cols.getOrPut(column.id) { makeCol(column.id, column.title) }
            styleHeader(c)
            val wasLive = c.live
            c.fill(column.results, animateIn = !wasLive)
            val tgt = (i - focusIndex).toFloat()
            if (!c.live) {
                c.live = true
                c.view.visibility = VISIBLE
                if (c.fade.value < 0.05f) c.pos.snap(tgt + 0.38f)
                c.fade.animateTo(1f)
            }
            c.pos.animateTo(tgt)
        }
    }

    private fun makeCol(id: String, title: String): Col {
        val c = Col(id, title)
        colsBox.addView(c.view, LayoutParams(max(colW, 1), -1, Gravity.CENTER_HORIZONTAL))
        styleHeader(c)
        return c
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GEOMETRY
    // ════════════════════════════════════════════════════════════════════════

    /**
     * One token controls how much every spring overshoots. Mapped so the range
     * runs from "no bounce at all" to "noticeably elastic".
     */
    private fun dampFor(t: Tokens): Float = 1.35f - t.f(K.motionSpring).coerceIn(0.2f, 1f) * 0.75f

    private fun setImeFrom(i: WindowInsets) {
        val ime = i.getInsets(WindowInsets.Type.ime()).bottom
        imeShift = -max(0, ime - bottomInset).toFloat()
        applySearchY(spring.value)
    }

    private fun metrics() {
        if (height == 0) return
        val g = dp(Inset.gutter * tokens.f(K.typeGutter).coerceIn(0.5f, 2f)).toInt()
        stage.setPadding(g, topInset + dp(Inset.l).toInt(), g, bottomInset + dp(Inset.xl).toInt())
        val nw = (width * 0.87f).toInt()
        if (nw != colW) {
            colW = nw
            settingsCol.view.layoutParams = LayoutParams(colW, -1, Gravity.CENTER_HORIZONTAL)
            for (c in cols.values) {
                c.view.layoutParams = LayoutParams(colW, -1, Gravity.CENTER_HORIZONTAL)
                applyCol(c)
            }
            applySettingsCol()
        }
        styleSearch()
        whisper.position(clock, pins)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        metrics()
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
        val depth = tokens.f(K.motionDepth).coerceIn(0f, 2f)
        v.translationX = p * width
        val edgeFade = (1f - a * 1.45f).coerceIn(0f, 1f)
        v.alpha = c.fade.value.coerceIn(0f, 1f) * edgeFade
        val s = 1f - (0.06f * a * depth).coerceIn(0f, 0.12f)
        v.scaleX = s
        v.scaleY = s
        v.pivotY = height.toFloat()
        v.visibility = if (v.alpha <= 0.006f || !c.live) GONE else VISIBLE
    }

    private fun applySettingsCol() {
        val p = settingsCol.pos.value + scrollOff
        val a = min(abs(p), 1f)
        val v = settingsCol.view
        v.translationX = p * width
        val edgeFade = (1f - a * 1.45f).coerceIn(0f, 1f)
        v.alpha = settingsCol.fade.value.coerceIn(0f, 1f) * edgeFade
        val s = 1f - (0.06f * a).coerceIn(0f, 0.12f)
        v.scaleX = s
        v.scaleY = s
        v.pivotY = height.toFloat()
        v.visibility = if (v.alpha <= 0.006f) GONE else VISIBLE
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SEARCH SPRING
    // ════════════════════════════════════════════════════════════════════════

    private fun setProgress(p: Float) {
        val pc = p.coerceIn(0f, 1f)
        val depth = tokens.f(K.motionDepth).coerceIn(0f, 2f)
        // INVISIBLE skips drawing entirely; use alpha so the ghost survives
        stage.alpha = (1f - pc * 0.84f).coerceIn(0f, 1f)
        stage.visibility = VISIBLE
        stage.translationY = pc * dp(30f) * (0.35f + depth * 0.65f)
        val s = 1f - pc * 0.028f * depth
        stage.scaleX = s
        stage.scaleY = s
        stage.pivotY = height.toFloat()

        whisper.alpha = whisper.alpha.coerceAtMost((1f - pc * 2f).coerceIn(0f, 1f))
        scrim.alpha = pc * 0.58f
        applySearchY(p)
        for (c in cols.values) applyCol(c)
        applySettingsCol()
    }

    private fun applySearchY(p: Float) {
        search.translationY = -(1f - p) * dp(44f) + imeShift
        search.alpha = ((p * 1.6f - 0.15f).coerceIn(0f, 1f)) * tokens.f(K.searchOpacity)
    }

    private fun settled(p: Float) {
        if (p <= 0f) closeCleanup()
    }

    private fun closeCleanup() {
        hintDeck(false)
        search.visibility = GONE
        input.setText("")
        setInputFocusable(false)
        pinnedCol = null
        scrollOff = 0f
        liveOrder = emptyList()
        focusId = null
        isSettingsFocused = false
        settingsCol.fade.snap(0f)
        settingsCol.pos.snap(-1f)
        settingsCol.view.visibility = GONE
        for (c in cols.values) {
            c.live = false
            c.fade.snap(0f)
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
        if (open) { closeSearch(); return true }
        return false
    }

    /**
     * Reveal the settings deck without a keyboard. The deck lives to the left of
     * the app column, so it is summoned by swiping right from the home screen —
     * the same physical direction as the column it shows.
     */
    fun openSettings() {
        if (open) return
        open = true
        isSettingsFocused = true
        focusId = null
        pinnedCol = null
        settingsCol.deck.rebuild()
        prepareSearch()
        spring.animateTo(1f, 2.2f)
        retarget(2.2f)
        hintDeck(true)
        Haptics.hold(context)
        dismissWhisper()
    }

    fun reset() {
        spring.stop()
        open = false
        spring.snap(0f)
        hideIme()
        closeCleanup()
        hud.hideNow()
        compass.hide()
        held = null
        awaitingHold = false
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TOUCH
    //
    //  Three outcomes, decided by what the finger does rather than by where the
    //  press started, so the whole screen stays one continuous surface:
    //
    //    · a quick touch on an app          → launch it
    //    · a quick touch on an adjustable   → flash its grips and its HUD
    //    · a hold                           → take hold and scrub
    //    · otherwise                        → page gesture (vertical or horizontal)
    // ════════════════════════════════════════════════════════════════════════

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L

    private var vert = false
    private var horiz = false
    private var startX = 0f
    private var startY = 0f
    private var baseP = 0f
    private var listStart = 0

    private var held: Resolved? = null
    private var awaitingHold = false
    private var downZone: Resolved? = null

    private val range get() = min(height * 0.3f, dp(260f))

    private val holdRunnable = Runnable {
        if (held != null || !awaitingHold) return@Runnable
        awaitingHold = false
        Haptics.hold(context)
        if (pinHoldMenu(downX, downY)) return@Runnable
        val zone = downZone
        if (zone != null) {
            held = zone
            showHudFor(zone, downX, downY)
            compass.show(downX, downY,
                if (zone.target.onScrubX != null) axisXLabel(zone.id) else "",
                if (zone.target.onScrubY != null) axisYLabel(zone.id) else "",
                ink.primary)
            deckJump(sectionFor(zone.id))
        } else if (open && downY > search.top + dp(60f) && focusId != null) {
            grabListSurface(downX, downY)
        } else if (open) {
            grabSearchSurface(downX, downY)
        } else {
            grabBackground(downX, downY)
        }
        dismissWhisper()
    }

    private fun sectionFor(id: String) = when {
        id.startsWith("clock") -> "Clock"
        id.startsWith("pins") -> "Apps"
        id.startsWith("search") -> "Search"
        id.startsWith("list") -> "Results"
        id.startsWith("bg") -> "Background"
        else -> "Clock"
    }

    private fun axisXLabel(id: String) = when {
        id.startsWith("clock.move") -> "place"
        id.startsWith("clock.scale") -> "size"
        id.startsWith("clock") -> "format"
        id.startsWith("pins.weight") -> "weight"
        id.startsWith("pins") -> "align"
        id.startsWith("bg") -> "hue"
        id.startsWith("search") -> "size"
        else -> "size"
    }

    private fun axisYLabel(id: String) = when {
        id.startsWith("clock.move") -> "place"
        id.startsWith("clock.scale") -> "size"
        id.startsWith("clock") -> "weight"
        id.startsWith("pins.weight") -> "weight"
        id.startsWith("pins") -> "spacing"
        id.startsWith("bg") -> "depth"
        id.startsWith("search") -> "inset"
        else -> "spacing"
    }

    private fun grabBackground(x: Float, y: Float) {
        val spec = Schema.byKey[K.bgMode]!!
        val t = Target(
            id = "bg",
            label = "Atmosphere",
            verb = "drag",
            handle = Target.HANDLE_FRAME,
            value = { spec.display(tokens.s(K.bgMode)) },
            hint = { "Hold, then drag ↔ for hue and ↕ for depth" },
            onClick = { cycleBgMaterial() },
            onScrubX = { d ->
                tokens.set(K.bgHue, (tokens.f(K.bgHue) + d * 120f + 360f) % 360f)
                tokens.commit()
            },
            onScrubY = { d ->
                tokens.set(K.bgTone, (tokens.f(K.bgTone) + d * 0.2f).coerceIn(0f, 0.7f))
                tokens.commit()
            },
            ink = { ink.primary }
        )
        val fake = Resolved(bg, t, x, y, ArrayList())
        held = fake
        showHudFor(fake, x, y)
        compass.show(x, y, "hue", "depth", ink.primary)
        deckJump("Background")
    }

    private fun cycleBgMaterial() {
        val spec = Schema.byKey[K.bgMode]!!
        val cur = spec.options.indexOfFirst { it.id == tokens.s(K.bgMode) }.coerceAtLeast(0)
        tokens.set(K.bgMode, spec.options[(cur + 1) % spec.options.size].id)
        tokens.commit()
        Haptics.tick(context)
        hud.setValue(spec.display(tokens.s(K.bgMode)))
    }

    private fun grabSearchSurface(x: Float, y: Float) {
        val spec = Schema.byKey[K.searchStyle]!!
        val t = Target(
            id = "search.surface",
            label = "Search",
            verb = "drag",
            handle = Target.HANDLE_BAR,
            value = { spec.display(tokens.s(K.searchStyle)) },
            hint = { "Hold, then drag ↔ for type size · ↕ for inset" },
            onClick = {
                val opts = spec.options
                val cur = opts.indexOfFirst { it.id == tokens.s(K.searchStyle) }.coerceAtLeast(0)
                tokens.set(K.searchStyle, opts[(cur + 1) % opts.size].id)
                tokens.commit()
                hud.setValue(spec.display(tokens.s(K.searchStyle)))
            },
            onScrubX = { d ->
                tokens.set(K.searchSize, (tokens.f(K.searchSize) + d * 0.75f).coerceIn(0.6f, 1.8f))
                tokens.commit()
            },
            onScrubY = { d ->
                tokens.set(K.searchPad, (tokens.f(K.searchPad) + d * 0.75f).coerceIn(0.2f, 2.5f))
                tokens.commit()
            },
            ink = { ink.primary }
        )
        held = Resolved(inputRow, t, x, y, ArrayList())
        showHudFor(held!!, x, y)
        compass.show(x, y, "size", "inset", ink.primary)
        deckJump("Search")
    }

    private fun grabListSurface(x: Float, y: Float) {
        val host: View = focusId?.let { cols[it] }?.itemsLayout ?: colsBox
        val t = Target(
            id = "list.surface",
            label = "Results",
            verb = "drag",
            handle = Target.HANDLE_BAR,
            value = { String.format(Locale.US, "%.2f×", tokens.f(K.listSize)) },
            hint = { "Hold, then drag ↔ for type size · ↕ for spacing" },
            onScrubX = { d ->
                tokens.set(K.listSize, (tokens.f(K.listSize) + d * 0.85f).coerceIn(0.6f, 1.7f))
                tokens.commit()
            },
            onScrubY = { d ->
                tokens.set(K.listRowGap, (tokens.f(K.listRowGap) + d * 0.75f).coerceIn(0f, 2f))
                tokens.commit()
            },
            ink = { ink.primary }
        )
        held = Resolved(host, t, x, y, ArrayList())
        showHudFor(held!!, x, y)
        compass.show(x, y, "size", "spacing", ink.primary)
        deckJump("Results")
    }

    /** Enter the deck on the section that owns whatever was just grabbed. */
    /**
     * Holding an app name is how you curate the block: release and it is pinned
     * or unpinned. Dragging from there still shapes the block, which is why the
     * hold reports the zone rather than the app.
     */
    private fun pinHoldMenu(x: Float, y: Float): Boolean {
        if (open || pins.visibility != VISIBLE) return false
        val entry = pinAt(x, y) ?: return false
        pins.appAt(x, y) ?: return false
        held = null
        compass.hide()
        hud.hide()
        Haptics.hold(context)
        pinMenu(entry)
        return true
    }

    private fun deckJump(section: String) {
        settingsCol.deck.jumpTo(section)
    }

    /**
     * Two fingers on something scalable takes hold of it immediately, the way a
     * photo behaves — no hold required, because the gesture is unambiguous.
     */
    private var pinchDist = 0f

    private fun onPointerDown(e: MotionEvent) {
        val zone = downZone ?: return
        if (zone.target.onPinch == null) return
        if (e.pointerCount < 2) return
        removeCallbacks(holdRunnable)
        awaitingHold = false
        held = zone
        pinchDist = span(e)
        showHudFor(zone, downX, downY)
        compass.show(downX, downY, "", "", ink.primary)
        Haptics.hold(context)
        hud.setHint("Pinch to scale")
    }

    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return hypot(dx, dy).coerceAtLeast(1f)
    }

    private fun trackPinch(e: MotionEvent): Boolean {
        val r = held ?: return false
        val pinch = r.target.onPinch ?: return false
        val d = span(e)
        if (pinchDist <= 0f) { pinchDist = d; return true }
        val factor = d / pinchDist
        if (abs(factor - 1f) > 0.0015f) {
            pinch(factor)
            pinchDist = d
            showHudFor(r, e.x, e.y)
        }
        return true
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastX = x; lastY = y
                downTime = System.currentTimeMillis()
                vert = false; horiz = false
                held = null
                awaitingHold = true
                downZone = Studio.targetAt(this, x, y)
                pinnedCol = null
                listStart = (focusId?.let { cols[it] }?.scrollView?.scrollY ?: 0)
                removeCallbacks(holdRunnable)
                postDelayed(holdRunnable, longPressMs)
                if (spring.running) beginVertical(e)
            }

            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)

            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2 && trackPinch(e)) {
                    lastX = x; lastY = y
                    return true
                }
                val dx = x - downX
                val dy = y - downY
                val r = held
                if (r != null) {
                    val gx = (x - lastX) / (56f * resources.displayMetrics.density)
                    val gy = -(y - lastY) / (56f * resources.displayMetrics.density)
                    if (abs(gx) > 0.0002f) r.target.onScrubX?.invoke(gx)
                    if (abs(gy) > 0.0002f) r.target.onScrubY?.invoke(gy)
                    showHudFor(r, x, y)
                } else if (awaitingHold) {
                    if (hypot(dx, dy) > slop) {
                        removeCallbacks(holdRunnable)
                        awaitingHold = false
                        decideGesture(e, dx, dy)
                    }
                } else if (vert) {
                    val raw = baseP + (y - startY) / range
                    spring.set(if (raw > 1f) 1f + (raw - 1f) * 0.2f else max(0f, raw))
                } else if (horiz) {
                    setScroll((x - startX) / step)
                    if (abs(x - startX) > dp(400f)) {
                        // long drags: let the row scroll give way to the page
                    }
                } else if (open && focusId != null) {
                    if (abs(dy) > slop * 0.6f && abs(dy) > abs(dx)) {
                        scrollList((listStart + (downY - y)).toInt())
                    }
                }
                lastX = x
                lastY = y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                val dt = (System.currentTimeMillis() - downTime).coerceAtLeast(1L)
                val moved = hypot(x - downX, y - downY) > slop
                val r = held
                if (r != null) {
                    if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                        r.target.onClick?.invoke()
                        hud.setValue(r.value())
                        postDelayed({ if (held === r) { hud.hide(); compass.hide(); held = null } }, 1300L)
                    } else {
                        if (r.id == "clock.move") (r.view as? ClockView)?.endFreeMove()
                        hud.hide()
                        compass.hide()
                    }
                } else if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    onTap(x, y)
                } else if (vert) {
                    val v = (y - startY) / range
                    val goOpen = when {
                        v > 0.22f -> true
                        v < -0.22f -> false
                        else -> spring.value > 0.5f
                    }
                    open = goOpen
                    if (goOpen) { showIme(); spring.animateTo(1f, v * 7f) }
                    else { hideIme(); spring.animateTo(0f, v * 7f) }
                } else if (horiz) {
                    endScroll((x - startX) / step / dt * 240f)
                }
                awaitingHold = false
                downZone = null
                held = null
                vert = false
                horiz = false
            }
        }
        return true
    }

    private fun decideGesture(e: MotionEvent, dx: Float, dy: Float) {
        if (!open) {
            if (dy > slop && dy > abs(dx) * 1.1f) beginVertical(e)
            else if (dx > slop * 1.4f && dx > abs(dy) * 1.3f) openSettings()
            return
        }
        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) beginHorizontal(e)
        else if (dy > slop && dy > abs(dx) * 1.2f && isAboveColumns(e.y)) beginVertical(e)
    }

    private fun isAboveColumns(y: Float): Boolean = y < search.top + dp(86f)

    private fun pinAt(x: Float, y: Float): AppEntry? {
        if (pins.visibility != VISIBLE) return null
        val loc = IntArray(2)
        val rloc = IntArray(2)
        pins.getLocationInWindow(loc)
        getLocationInWindow(rloc)
        return pins.appAt(x + rloc[0] - loc[0], y + rloc[1] - loc[1])
    }

    /** The row under the finger, so a tap launches it directly. */
    private fun rowAt(x: Float, y: Float): ResultRow? {
        val c = focusId?.let { cols[it] } ?: return null
        val sv = c.scrollView
        val loc = IntArray(2)
        val rloc = IntArray(2)
        sv.getLocationInWindow(loc)
        getLocationInWindow(rloc)
        val lx = x + rloc[0] - loc[0]
        val ly = y + rloc[1] - loc[1]
        if (lx < 0 || lx > sv.width || ly < 0 || ly > sv.height) return null
        val cy = (ly + sv.scrollY - c.itemsLayout.translationY).toInt()
        for (i in 0 until c.itemsLayout.childCount) {
            val row = c.itemsLayout.getChildAt(i) as? ResultRow ?: continue
            if (cy >= row.top && cy <= row.bottom) return row
        }
        return null
    }

    private fun onTap(x: Float, y: Float) {
        if (open) {
            if (isAboveColumns(y)) { closeSearch(); return }
            rowAt(x, y)?.let { it.tap() }
            return
        }
        // A tap names what it touched for a beat; it never changes anything and
        // it never launches. Launching is the one thing that must stay explicit,
        // and the studio's vocabulary (tap = next variant) has to stay coherent.
        val zone = downZone
        if (zone != null) {
            held = zone
            showHudFor(zone, x, y)
            compass.show(x, y,
                if (zone.target.onScrubX != null) axisXLabel(zone.id) else "",
                if (zone.target.onScrubY != null) axisYLabel(zone.id) else "", ink.primary)
            Haptics.soft(context)
            dismissWhisper()
            postDelayed({ if (held === zone) { hud.hide(); compass.hide(); held = null } }, 1500L)
            return
        }
        val pin = pinAt(x, y)
        if (pin != null) {
            index.launch(pin, pins)
            return
        }
        dismissWhisper()
    }

    private fun beginVertical(e: MotionEvent) {
        vert = true
        horiz = false
        spring.stop()
        baseP = spring.value
        startY = e.y
        prepareSearch()
        if (open) hideIme()
        compass.hide()
    }

    private fun beginHorizontal(e: MotionEvent) {
        horiz = true
        vert = false
        startX = e.x
        compass.hide()
    }

    private fun scrollList(y: Int) {
        val c = focusId?.let { cols[it] } ?: return
        val sv = c.scrollView
        val maxScroll = max(0, c.itemsLayout.height - sv.height)
        val over = when {
            y < 0 -> -y
            y > maxScroll -> y - maxScroll
            else -> 0
        }
        sv.scrollTo(0, y.coerceIn(0, maxScroll))
        c.itemsLayout.translationY = -(over * 0.2f).coerceIn(-dp(24f), dp(24f))
    }

    private fun setScroll(raw: Float) {
        val focusIndex = if (isSettingsFocused) -1 else max(0, liveOrder.indexOf(focusId))
        val hi = (1 + focusIndex).toFloat()
        val lo = -(liveOrder.size - 1 - focusIndex).toFloat()
        scrollOff = when {
            raw > hi -> hi + (raw - hi) * 0.24f
            raw < lo -> lo + (raw - lo) * 0.24f
            else -> raw
        }
        applySettingsCol()
        for (c in cols.values) applyCol(c)
    }

    private fun endScroll(vSlots: Float) {
        val proj = scrollOff + vSlots.coerceIn(-4f, 4f) * 0.2f
        var bestId: String? = null
        var bestIsSettings = false
        var bestDist = Float.MAX_VALUE

        val settingsDist = abs(settingsCol.pos.value + proj)
        if (settingsDist < bestDist) { bestDist = settingsDist; bestIsSettings = true }
        for (id in liveOrder) {
            val d = abs(cols[id]!!.pos.value + proj)
            if (d < bestDist) { bestDist = d; bestIsSettings = false; bestId = id }
        }

        val off = scrollOff
        scrollOff = 0f
        settingsCol.pos.set(settingsCol.pos.value + off)
        for (c in cols.values) c.pos.set(c.pos.value + off)

        if (bestIsSettings) {
            isSettingsFocused = true
            pinnedCol = null
            focusId = null
            settingsCol.deck.rebuild()
        } else {
            isSettingsFocused = false
            pinnedCol = bestId
            focusId = bestId
        }
        if (tokens.b(K.motionHaptics)) Haptics.tick(context)
        retarget(vSlots * 10f)
        hintDeck(isSettingsFocused)
    }

    /** When the deck owns the screen, the home surface shows what it can grip. */
    private fun hintDeck(on: Boolean) {
        val target = if (on) 1f else 0f
        val dur = (420f * tokens.f(K.motionSpeed)).toLong().coerceAtLeast(1L)
        Ramp(clockHint, target, dur, ExpoOut, { v ->
            clockHint = v
            clock.hint(v)
            pins.hint(v)
        }).start()
    }

    private var clockHint = 0f

    /** Apply a pinch factor to whatever is currently held. */
    private fun pinch(factor: Float) {
        val r = held ?: return
        val pinchTarget = r.target.onPinch ?: return
        pinchTarget.invoke(factor)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HUD PLUMBING
    // ════════════════════════════════════════════════════════════════════════

    private fun showHudFor(h: Resolved, px: Float, py: Float) {
        val inHud = h.regionIn(this)
        // rows read better as a stadium than as a box
        val radius = if ((h.view as? PinsView)?.isRow(h.target) == true) inHud.height() * 0.5f else dp(2f)
        hud.setValue(h.value())
        hud.show(h, px, py, inHud, max(dp(2f), radius))
    }

    private fun pinMenu(e: AppEntry) {
        val options = arrayOf(
            if (index.isPinned(e)) "Unpin from home" else "Pin to home",
            "App info"
        )
        android.app.AlertDialog.Builder(context)
            .setTitle(e.label)
            .setItems(options) { _, which ->
                if (which == 0) { index.togglePin(e); pins.refresh() }
                else {
                    try {
                        val i = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        i.data = android.net.Uri.fromParts("package", e.component.packageName, null)
                        context.startActivity(i)
                    } catch (ex: Exception) {}
                }
            }
            .show()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TEACHING
    // ════════════════════════════════════════════════════════════════════════

    private fun teachOnce() {
        val prefs = context.getSharedPreferences("atrium", Context.MODE_PRIVATE)
        if (prefs.getBoolean("studio_taught", false)) return
        whisper.position(clock, pins)
        whisper.reveal()
        postDelayed({
            if (!whisper.dismissed) whisper.fade()
        }, 11000L)
    }

    private fun dismissWhisper() {
        if (whisper.dismissed) return
        whisper.fade()
        context.getSharedPreferences("atrium", Context.MODE_PRIVATE)
            .edit().putBoolean("studio_taught", true).apply()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  FOCUS / INTRO
    // ════════════════════════════════════════════════════════════════════════

    private var fv = 0f
    private var focusAnim: ValueAnimator? = null

    private fun setFocus(f: Float) {
        fv = f
        val k = 1f - f
        alpha = (f * 2.2f).coerceIn(0f, 1f)
        val s = 1f + 0.045f * k
        scaleX = s
        scaleY = s
        if (Build.VERSION.SDK_INT >= 31 && tokens.b(K.motionBlur)) {
            val r = k * tokens.f(K.motionBlurAmount)
            setRenderEffect(if (r < 0.6f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        } else if (Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(null)
        }
    }

    private fun animateFocus(to: Float, ms: Long, interp: android.animation.TimeInterpolator) {
        focusAnim?.cancel()
        val duration = (ms * tokens.f(K.motionSpeed)).toLong().coerceAtLeast(1L)
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
        // the stage is revealed before the entrance curve starts, so the very
        // first frame is a composed frame and not an empty one
        stage.alpha = 1f
        clock.entrance(0f, 0f)
        pins.entrance(0f, 1f)
        animateFocus(1f, 580, ExpoOut)
        val mode = tokens.s(K.motionEntrance)
        if (mode == "none") return
        val dur = (820 * tokens.f(K.motionSpeed)).toLong().coerceAtLeast(1L)
        val stagger = if (tokens.b(K.motionStagger)) 0.18f else 0f
        if (tokens.b(K.pinsIcons) && tokens.b(K.pinsFadeIn)) pins.revealIcons()
        Ramp(0f, 1f, dur, ExpoOut, { p ->
            clock.entrance(p, 0f)
            pins.entrance(p, stagger)
        }).start()
    }

    fun blurOut() {
        animateFocus(0f, 320, AccelerateInterpolator(1.4f))
        postDelayed({ if (hasWindowFocus() && fv < 1f) playIntro() }, 1500L)
    }

    companion object {
        const val MAX_VISIBLE_ROWS = 12
    }
}

/**
 * The single piece of guidance in the product: one line, positioned under
 * whatever it is talking about, shown once, then never again.
 */
@SuppressLint("ViewConstructor")
class WhisperView(ctx: Context) : TextView(ctx) {

    private val d = ctx.resources.displayMetrics.density
    var dismissed = false
        private set

    init {
        includeFontPadding = false
        typeface = Type.ui(ctx, 440)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        letterSpacing = 0.15f
        setTextColor(withAlpha(0xFFFFFFFF.toInt(), 0.34f))
        text = "HOLD TO SHAPE  ·  SWIPE RIGHT FOR SETTINGS"
        alpha = 0f
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setColor(c: Int) = setTextColor(withAlpha(c, 0.34f))

    fun position(clock: View, pins: View) {
        post {
            val parent = parent as? View ?: return@post
            val loc = IntArray(2)
            clock.getLocationInWindow(loc)
            val root = IntArray(2)
            parent.getLocationInWindow(root)
            val clockBottom = loc[1] - root[1] + clock.height
            val top = clockBottom + (8 * d).toInt()
            translationY = top.toFloat().coerceAtMost(parent.height * 0.55f)
            val lp = layoutParams
            if (lp != null) {
                lp.height = (46 * d).toInt()
                layoutParams = lp
            }
        }
    }

    fun reveal() {
        if (dismissed) return
        animate().alpha(0.9f).setStartDelay(1200L).setDuration(1100L).start()
    }

    fun fade() {
        dismissed = true
        animate().alpha(0f).setDuration(900L).start()
    }
}
