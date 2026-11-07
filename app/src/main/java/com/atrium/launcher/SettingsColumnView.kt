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

/**
 * ATRIUM · SETTINGS DECK
 * ────────────────────────────────────────────────────────────────────────────
 * The rail column of the launcher. It is a *map* of the interface, not a form:
 * every row names a thing you can also see and touch on the home screen, and
 * every row can be manipulated here with the same gestures as out there.
 *
 * It is built to scale to hundreds of options:
 *   · three-level hierarchy   (Section → Group → Token)
 *   · a second enterable page (so the column never scrolls horizontally)
 *   · a live search that scores title, help, unit and keywords with FZF
 *   · non-default rows only, when you want a diff
 *   · presets to re-seed, and a one-tap reset per row and for everything
 */
@SuppressLint("ViewConstructor")
class SettingsColumnView(
    context: Context,
    private val registry: SettingsRegistry,
    private val tokens: Tokens
) : ScrollView(context) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
    }

    /** Keep a short deck pinned just above the search bar. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val h = height
        val content = getChildAt(0)?.height ?: 0
        container.translationY = if (content < h) (h - content).toFloat() else 0f
    }

    private val open = HashSet<String>()
    private val entered = HashSet<String>()

    private var page = 0
    private var currentQuery = ""
    private var defaultOnly = false
    private var showAdvanced = true
    private var builtSignature = ""

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipChildren = false
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rebuild()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PUBLIC
    // ════════════════════════════════════════════════════════════════════════

    var onNeedRefresh: (() -> Unit)? = null
    var deckChanged: (() -> Unit)? = null
    private var palette: Ink? = null

    fun setPalette(ink: Ink) { palette = ink }

    fun invalidateDeck() {
        builtSignature = ""
        rebuild()
    }

    /** Cheap: refresh live values on existing rows without touching structure. */
    fun syncValues() {
        refreshValues()
    }

    /** Enter a section by title (used when you grab something on the home screen). */
    fun jumpTo(sectionTitle: String) {
        val sections = registry.sections()
        val i = sections.indexOfFirst { it.id == sectionTitle }
        if (i < 0) return
        if (page == i + 1 && currentQuery.isEmpty()) return
        page = i + 1
        currentQuery = ""
        filtered = false
        builtSignature = ""
        rebuild()
    }

    private fun enterSection(sectionId: String) {
        val i = registry.sections().indexOfFirst { it.id == sectionId }
        if (i < 0) return
        page = i + 1
        currentQuery = ""
        filtered = false
        builtSignature = ""
        rebuild()
    }

    fun setQuery(q: String) {
        if (currentQuery == q) return
        currentQuery = q
        filtered = false
        rebuild()
    }

    /**
     * As the query narrows to a single token whose title it matches exactly,
     * jump into that token's section — settings are navigable by typing alone.
     */
    private var filtered = false

    fun setDefaultOnly(v: Boolean) {
        if (defaultOnly == v) return
        defaultOnly = v
        rebuild()
    }

    fun resetAll() {
        tokens.resetAll()
        tokens.commit()
        defaultOnly = false
        open.clear()
        entered.clear()
        builtSignature = ""
        rebuild()
        onNeedRefresh?.invoke()
    }

    fun rebuild() {
        val sig = signature()
        if (sig == builtSignature) {
            refreshValues()
            return
        }
        builtSignature = sig
        container.removeAllViews()
        val q = currentQuery.trim()

        if (q.isNotEmpty()) {
            val hits = registry.search(q)
            if (hits.isEmpty()) {
                container.addView(emptyRow("Nothing matches “$q”"))
            } else {
                container.addView(caption("${hits.size} MATCHES", 0.9f, 0.38f))
                var lastSection = ""
                for (leaf in hits) {
                    if (leaf.section != lastSection) {
                        lastSection = leaf.section
                        // the group heading is the door into that section
                        container.addView(sectionLabel(leaf.section) { enterSection(leaf.section) })
                    }
                    container.addView(TokenRow(context, tokens, leaf) { rebuild() })
                }
            }
            scrollToTop()
            return
        }

        if (page == 0) buildIndex() else buildSection(page - 1)
        scrollToTop()
    }

    private fun scrollToTop() {
        post { fullScroll(FOCUS_UP) }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PAGES
    // ════════════════════════════════════════════════════════════════════════

    private fun buildIndex() {
        val sections = registry.sections()
        container.addView(DeckHead(context, tokens))

        // presets
        container.addView(caption("STARTING POINTS", 0.92f, 0.34f))
        for (p in Presets.all) {
            container.addView(PresetRow(context, tokens, p) { applyPreset(p) })
        }

        // sections
        container.addView(caption("SECTIONS", 0.92f, 0.34f))
        for (s in sections) {
            val count = registry.countIn(s.id)
            container.addView(NavRow(
                context = context,
                title = s.title,
                sub = s.note,
                badge = count.toString(),
                onClick = {
                    page = sections.indexOf(s) + 1
                    rebuild()
                }
            ))
        }

        // recent shelf: the fastest way back to what you were just shaping
        val recent = tokens.recentKeys(5).filter { !tokens.isDefault(it.key) }
        if (recent.isNotEmpty()) {
            container.addView(caption("RECENT", 0.92f, 0.34f))
            for (t in recent) {
                container.addView(TokenRow(context, tokens, t) { rebuild() })
            }
        }

        // craft: build the surface from a blank sheet
        container.addView(caption("CRAFT", 0.92f, 0.34f))
        for (wb in Workbenches.all) {
            container.addView(NavRow(context, wb.name, wb.note, chevron = false) {
                val base = HashMap<String, String>()
                for (t in Schema.all) base[t.key] = t.def
                for ((k, v) in wb.patch) base[k] = v
                tokens.applyAll(base)
                builtSignature = ""
                rebuild()
                onNeedRefresh?.invoke()
                HeroFlash.flash(HashMap(), tokens)
            })
        }

        // tools
        container.addView(caption("STUDIO", 0.92f, 0.34f))
        container.addView(SimpleRow(
            context = context,
            title = "Changed only",
            value = "",
            onClick = { setDefaultOnly(!defaultOnly) },
            isToggle = true,
            valueText = if (defaultOnly) "On" else "Off",
            toggleOn = defaultOnly
        ))
        container.addView(SimpleRow(
            context = context,
            title = "Reset everything",
            value = "${tokens.changedCount()} changed",
            onClick = { resetAll() },
            danger = true
        ))
        container.addView(SimpleRow(
            context = context,
            title = "Surprise me",
            value = "shuffle",
            onClick = {
                tokens.shuffle()
                builtSignature = ""
                rebuild()
                onNeedRefresh?.invoke()
            }
        ))
        container.addView(SimpleRow(
            context = context,
            title = "Copy token sheet",
            value = "${Schema.all.size} keys",
            onClick = {
                try {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("atrium tokens", tokens.exportText())
                    )
                    android.widget.Toast.makeText(context, "Token sheet copied", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {}
            }
        ))
        for ((label, action) in registry.systemActions()) {
            container.addView(SimpleRow(context, label, "", { action() }))
        }
    }

    private fun buildSection(index: Int) {
        val sections = registry.sections()
        val s = sections.getOrNull(index) ?: return
        if (!entered.contains(s.id)) {
            entered.add(s.id)
            for (g in registry.groupsIn(s.id)) open.add(s.id + "/" + g)
        }

        container.addView(NavRow(context, "‹  All sections", s.title.uppercase(), chevron = false, back = true,
            onClick = { page = 0; rebuild() }))

        for (g in registry.groupsIn(s.id)) {
            val leaves = registry.leavesIn(s.id, g)
            val key = s.id + "/" + g
            val isOpen = open.contains(key) || entered.contains(s.id)
            container.addView(GroupHeader(
                context = context,
                title = g,
                count = leaves.size,
                open = isOpen,
                onToggle = {
                    if (isOpen) open.remove(key) else open.add(key)
                    rebuild()
                }
            ))
            if (isOpen) {
                var lastAdvanced = false
                for (leaf in leaves) {
                    if (defaultOnly && tokens.isDefault(leaf.key)) continue
                    if (!showAdvanced && leaf.advanced) continue
                    if (leaf.advanced && !lastAdvanced) {
                        container.addView(caption("FINE TUNING", 0.82f, 0.26f))
                        lastAdvanced = true
                    }
                    container.addView(TokenRow(context, tokens, leaf) { rebuild() })
                }
            }
        }
    }

    /**
     * A preset is a complete look, not an overlay: applying one starts from the
     * defaults so nothing stray from the previous arrangement survives.
     */
    private fun applyPreset(p: Preset) {
        val before = tokens.snapshot()
        val base = HashMap<String, String>()
        for (t in Schema.all) base[t.key] = t.def
        for ((k, v) in p.patch) base[k] = v
        tokens.applyAll(base)
        builtSignature = ""
        rebuild()
        onNeedRefresh?.invoke()
        HeroFlash.flash(before, tokens)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ROWS
    // ════════════════════════════════════════════════════════════════════════

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun caption(text: String, scale: Float, alpha: Float): View {
        val v = android.widget.TextView(context).apply {
            this.text = text
            includeFontPadding = false
            typeface = Type.ui(context, 560)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * scale)
            letterSpacing = 0.22f
            setTextColor(withAlpha(0xFFFFFFFF.toInt(), alpha))
            setPadding(dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt(), dp(5f).toInt())
        }
        return v
    }

    private fun sectionLabel(text: String, onTap: (() -> Unit)? = null): View {
        val v = android.widget.TextView(context).apply {
            this.text = text.uppercase()
            includeFontPadding = false
            typeface = Type.ui(context, 560)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.2f
            setTextColor(withAlpha(0xFFFFFFFF.toInt(), 0.34f))
            setPadding(dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt(), dp(4f).toInt())
            if (onTap != null) {
                // tap the heading to walk into that section
                setOnClickListener { onTap() }
                setTextColor(withAlpha(0xFFFFFFFF.toInt(), 0.5f))
            }
        }
        return v
    }

    private fun emptyRow(text: String): View {
        val v = android.widget.TextView(context).apply {
            this.text = text
            includeFontPadding = false
            typeface = Type.ui(context, 400)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(withAlpha(0xFFFFFFFF.toInt(), 0.35f))
            setPadding(dp(10f).toInt(), dp(24f).toInt(), dp(10f).toInt(), dp(24f).toInt())
        }
        return v
    }

    private fun signature(): String {
        val sb = StringBuilder()
        sb.append(page).append('|').append(currentQuery).append('|').append(defaultOnly)
        sb.append('|').append(open.joinToString(",")).append('|').append(entered.joinToString(","))
        sb.append('|').append(showAdvanced)
        return sb.toString()
    }

    private fun refreshValues() {
        // cheap path: only values changed, structure is identical
        for (i in 0 until container.childCount) {
            (container.getChildAt(i) as? TokenRow)?.sync()
        }
    }
}

/** Fires the atmospheric glint when a preset or a shuffle lands. */
