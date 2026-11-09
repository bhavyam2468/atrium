package com.atrium.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Minimalist Settings View.
 * Displays collapsible submenus (closed by default) with finished button cards
 * and a top back button.
 */
class SettingsColumnView(
    context: Context,
    private val registry: SettingsRegistry
) : FrameLayout(context) {

    var onBack: (() -> Unit)? = null

    private val expandedCategories = HashSet<String>() // closed by default
    private var currentQuery: String = ""

    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private val listContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top Header Bar with Back Button
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = context.dp(16f).toInt()
            val padV = context.dp(14f).toInt()
            setPadding(padH, padV, padH, padV)
        }

        val backBtn = TextView(context).apply {
            text = "←  Settings"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            includeFontPadding = false
            val pad = context.dp(8f).toInt()
            setPadding(pad, pad, pad, pad)
            val btnBg = GradientDrawable().apply {
                setColor(0x00000000)
                cornerRadius = context.dp(8f)
            }
            background = btnBg
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> btnBg.setColor(0x22FFFFFF)
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> btnBg.setColor(0x00000000)
                }
                false
            }
            setOnClickListener { onBack?.invoke() }
        }
        topBar.addView(backBtn)
        mainLayout.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // Content ScrollView
        val scrollLp = LinearLayout.LayoutParams(-1, 0, 1f)
        val padH = context.dp(16f).toInt()
        val padB = context.dp(32f).toInt()
        scroll.setPadding(padH, 0, padH, padB)
        scroll.addView(listContainer, FrameLayout.LayoutParams(-1, -2))
        mainLayout.addView(scroll, scrollLp)

        addView(mainLayout, LayoutParams(-1, -1))

        rebuild()
    }

    fun setQuery(q: String) {
        if (currentQuery != q) {
            currentQuery = q
            rebuild()
        }
    }

    fun rebuild() {
        listContainer.removeAllViews()

        if (currentQuery.isBlank()) {
            for (node in registry.rootTree) {
                renderNode(node)
            }
        } else {
            val matches = registry.search(currentQuery)
            if (matches.isEmpty()) {
                val empty = TextView(context).apply {
                    text = "No settings found"
                    setTextColor(0x66FFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    gravity = Gravity.CENTER
                    setPadding(0, context.dp(40f).toInt(), 0, context.dp(40f).toInt())
                }
                listContainer.addView(empty)
            } else {
                for (leaf in matches) {
                    renderLeaf(leaf, isChild = false)
                }
            }
        }
    }

    private fun renderNode(node: SettingNode) {
        when (node) {
            is SettingNode.Category -> {
                val isExpanded = expandedCategories.contains(node.id)

                // Category Card Header
                val catCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val bg = GradientDrawable().apply {
                        setColor(0xFF161616.toInt())
                        cornerRadius = context.dp(12f)
                        setStroke(context.dp(1f).toInt(), 0xFF2A2A2A.toInt())
                    }
                    background = bg
                    val hPad = context.dp(16f).toInt()
                    val vPad = context.dp(14f).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (isExpanded) expandedCategories.remove(node.id) else expandedCategories.add(node.id)
                        rebuild()
                    }
                }

                val rowHeader = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val catTitle = TextView(context).apply {
                    text = node.title
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    includeFontPadding = false
                }
                rowHeader.addView(catTitle, LinearLayout.LayoutParams(0, -2, 1f))

                val chevron = TextView(context).apply {
                    text = if (isExpanded) "▾" else "›"
                    setTextColor(0x88FFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    includeFontPadding = false
                }
                rowHeader.addView(chevron, LinearLayout.LayoutParams(-2, -2))
                catCard.addView(rowHeader)

                if (node.description.isNotEmpty()) {
                    val catDesc = TextView(context).apply {
                        text = node.description
                        setTextColor(0x66FFFFFF.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        includeFontPadding = false
                        setPadding(0, context.dp(4f).toInt(), 0, 0)
                    }
                    catCard.addView(catDesc)
                }

                val catLp = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = context.dp(10f).toInt()
                }
                listContainer.addView(catCard, catLp)

                // Render children if expanded
                if (isExpanded) {
                    val childrenContainer = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        val childPadL = context.dp(12f).toInt()
                        setPadding(childPadL, 0, 0, context.dp(8f).toInt())
                    }
                    for (child in node.children) {
                        renderLeaf(child, isChild = true, targetContainer = childrenContainer)
                    }
                    listContainer.addView(childrenContainer)
                }
            }
            else -> renderLeaf(node, isChild = false)
        }
    }

    private fun renderLeaf(
        node: SettingNode,
        isChild: Boolean,
        targetContainer: LinearLayout = listContainer
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = context.dp(10f)
                setStroke(context.dp(1f).toInt(), 0xFF2D2D2D.toInt())
            }
            background = bg
            val hPad = context.dp(14f).toInt()
            val vPad = context.dp(12f).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            text = node.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            includeFontPadding = false
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        topRow.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))

        val badgeView = TextView(context).apply {
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val hP = context.dp(8f).toInt()
            val vP = context.dp(3f).toInt()
            setPadding(hP, vP, hP, vP)
        }

        when (node) {
            is SettingNode.Toggle -> {
                val isOn = node.get()
                badgeView.text = if (isOn) "ON" else "OFF"
                badgeView.setTextColor(if (isOn) Color.WHITE else 0x66FFFFFF.toInt())
                badgeView.background = GradientDrawable().apply {
                    setColor(if (isOn) 0xFF355E3B.toInt() else 0xFF2A2A2A.toInt())
                    cornerRadius = context.dp(6f)
                }
                row.setOnClickListener {
                    node.set(!node.get())
                    rebuild()
                }
            }
            is SettingNode.Choice -> {
                badgeView.text = node.getCurrent()
                badgeView.setTextColor(Color.WHITE)
                badgeView.background = GradientDrawable().apply {
                    setColor(0xFF2A2A2A.toInt())
                    cornerRadius = context.dp(6f)
                }
                row.setOnClickListener {
                    val opts = node.options
                    val curIdx = opts.indexOf(node.getCurrent())
                    val nextOpt = opts[(curIdx + 1) % opts.size]
                    node.onSelect(nextOpt)
                    rebuild()
                }
            }
            is SettingNode.Action -> {
                badgeView.text = "→"
                badgeView.setTextColor(0x88FFFFFF.toInt())
                row.setOnClickListener {
                    node.action()
                    rebuild()
                }
            }
            is SettingNode.Category -> {}
        }
        topRow.addView(badgeView, LinearLayout.LayoutParams(-2, -2))
        row.addView(topRow)

        if (node.description.isNotEmpty()) {
            val descView = TextView(context).apply {
                text = node.description
                setTextColor(0x77FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                includeFontPadding = false
                setPadding(0, context.dp(4f).toInt(), 0, 0)
            }
            row.addView(descView)
        }

        val rowLp = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = context.dp(8f).toInt()
        }
        targetContainer.addView(row, rowLp)
    }
}
