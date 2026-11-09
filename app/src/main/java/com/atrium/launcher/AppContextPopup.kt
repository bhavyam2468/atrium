package com.atrium.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("ViewConstructor")
class AppContextPopup(
    context: Context,
    private val app: AppEntry,
    private val isFavorite: Boolean,
    private val onAppInfo: (AppEntry) -> Unit,
    private val onToggleFavorite: (AppEntry) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable().apply {
            setColor(0xFF202020.toInt())
            cornerRadius = context.dp(16f)
            setStroke(context.dp(1f).toInt(), 0x33FFFFFF)
        }
        background = bg
        val pad = context.dp(10f).toInt()
        setPadding(pad, pad, pad, pad)
    }

    private var isDismissing = false

    init {
        setBackgroundColor(0x66000000)
        isClickable = true
        isFocusable = true
        setOnClickListener { dismiss() }

        // App Label Header
        val header = TextView(context).apply {
            text = app.label
            setTextColor(0x88FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            includeFontPadding = false
            setPadding(context.dp(12f).toInt(), context.dp(6f).toInt(), context.dp(12f).toInt(), context.dp(8f).toInt())
            gravity = Gravity.CENTER
        }
        card.addView(header)

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(0x1FFFFFFF)
        }
        card.addView(divider, LinearLayout.LayoutParams(-1, context.dp(1f).toInt()).apply {
            bottomMargin = context.dp(6f).toInt()
        })

        // 1. App Info option
        val appInfoBtn = makeOptionRow("App info") {
            dismiss { onAppInfo(app) }
        }
        card.addView(appInfoBtn)

        // 2. Favorite toggle option
        val favLabel = if (isFavorite) "Remove from favorites" else "Set as favorite"
        val favBtn = makeOptionRow(favLabel) {
            dismiss { onToggleFavorite(app) }
        }
        card.addView(favBtn)

        val cardLp = LayoutParams(context.dp(240f).toInt(), LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        addView(card, cardLp)

        card.isClickable = true
        card.setOnClickListener { /* prevent dismissal when tapping card background */ }

        // Pop-in entrance animation
        alpha = 0f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        animate().alpha(1f).setDuration(160).setInterpolator(ExpoOut).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(ExpoOut).start()
    }

    private fun makeOptionRow(title: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            includeFontPadding = false
            val hPad = context.dp(14f).toInt()
            val vPad = context.dp(12f).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            val rowBg = GradientDrawable().apply {
                setColor(0x00000000)
                cornerRadius = context.dp(10f)
            }
            background = rowBg
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        rowBg.setColor(0x22FFFFFF)
                        v.scaleX = 0.98f; v.scaleY = 0.98f
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        rowBg.setColor(0x00000000)
                        v.scaleX = 1f; v.scaleY = 1f
                    }
                }
                false
            }
            setOnClickListener { onClick() }
        }
    }

    fun dismiss(then: (() -> Unit)? = null) {
        if (isDismissing) return
        isDismissing = true
        animate().alpha(0f).setDuration(140).setInterpolator(ExpoOut).start()
        card.animate().scaleX(0.9f).scaleY(0.9f).setDuration(140).setInterpolator(ExpoOut)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    then?.invoke()
                    onDismiss()
                }
            }).start()
    }
}
