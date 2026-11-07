package com.atrium.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.hypot

/**
 * ATRIUM · STUDIO
 * ────────────────────────────────────────────────────────────────────────────
 * The apparatus that lets you edit the launcher *by touching the launcher*.
 *
 * There is exactly one HUD in the whole app. Elements register themselves with a
 * [Studio]; the Studio routes their hover/drag reports to the HUD and to the
 * token store, and arbitrates between a tap, a horizontal scrub and a vertical
 * scrub on the same target (so a handle never steals the page's own gesture).
 *
 * The vocabulary, borrowed deliberately from direct-manipulation tools:
 *
 *   tap    → advance to the next variant (a "shuffle through this control")
 *   scrubX → the control's primary continuous dimension
 *   scrubY → its secondary dimension (usually weight / density)
 *   pinch  → scale
 */

/** What a draggable region on screen means. */
class Target(
    val id: String,
    val label: String,
    val verb: String,
    val handle: Int = HANDLE_CORNER,
    val value: () -> String,
    val hint: () -> String,
    val onClick: (() -> Unit)? = null,
    val onScrubX: ((Float) -> Unit)? = null,
    val onScrubY: ((Float) -> Unit)? = null,
    val onPinch: ((Float) -> Unit)? = null,
    /** Optional live outline in the region's own coordinate space. */
    val outline: ((Canvas, Paint, RectF) -> Unit)? = null,
    val ink: () -> Int = { 0xFFFFFFFF.toInt() }
) {
    companion object {
        const val HANDLE_CORNER = 0
        const val HANDLE_BAR = 1
        const val HANDLE_SWATCH = 2
        const val HANDLE_FRAME = 3
        const val HANDLE_DOT = 4
        const val HANDLE_NUDGE = 5
    }
}

/** Implemented by any view that hosts live-editable regions. */
interface LiveTarget {
    fun targets(): List<Target>
}

/** Global registry so the HUD always knows what is under the finger. */
object Studio {

    /** Targets that should never answer a hit test (visual only). */
    private val inert = HashSet<String>(4)

    fun markInert(id: String) { inert.add(id) }

    fun targetAt(root: ViewGroup, x: Float, y: Float): Resolved? {
        val loc = IntArray(2)
        val rloc = IntArray(2)
        root.getLocationInWindow(rloc)
        return walk(root, x + rloc[0], y + rloc[1], loc, 0)
    }

    private fun walk(v: View, wx: Float, wy: Float, loc: IntArray, depth: Int): Resolved? {
        if (depth > 14) return null
        if (v.visibility != View.VISIBLE || v.alpha < 0.06f) return null
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                walk(v.getChildAt(i), wx, wy, loc, depth + 1)?.let { return it }
            }
        }
        val live = v as? LiveTarget ?: return null
        v.getLocationInWindow(loc)
        val lx = wx - loc[0]
        val ly = wy - loc[1]
        if (lx < -v.width * 0.25f || ly < -v.height * 0.25f ||
            lx > v.width * 1.25f || ly > v.height * 1.25f) return null
        for (t in live.targets()) {
            if (t.id in inert) continue
            if (t.hits(v, lx, ly)) return Resolved(v, t, lx, ly, ArrayList())
        }
        return null
    }
}

/** Resolve the exact region a target occupies inside its view. */
internal fun regionFor(v: View, t: Target): RectF =
    (v as? RegionHost)?.regionOf(t) ?: RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())

internal fun Target.hits(v: View, lx: Float, ly: Float): Boolean {
    val b = regionFor(v, this)
    val pad = v.resources.displayMetrics.density * 2f
    return lx >= b.left - pad && lx <= b.right + pad && ly >= b.top - pad && ly <= b.bottom + pad
}

/** A view that can report precise regions for each of its targets. */
interface RegionHost {
    fun regionOf(t: Target): RectF?
}

/** A resolved hit: the view, the target, and pointer position in that view. */
class Resolved(
    val view: View,
    val target: Target,
    val lx: Float,
    val ly: Float,
    val trail: ArrayList<Float>
) {
    val id get() = target.id
    fun value() = target.value()
    fun hint() = target.hint()
}

/** Where a target's region sits, in root coordinates. */
fun Resolved.regionIn(root: View): RectF {
    val loc = IntArray(2)
    val rloc = IntArray(2)
    view.getLocationInWindow(loc)
    root.getLocationInWindow(rloc)
    val ox = (loc[0] - rloc[0]).toFloat()
    val oy = (loc[1] - rloc[1]).toFloat()
    val r = regionFor(view, target)
    return RectF(ox + r.left, oy + r.top, ox + r.right, oy + r.bottom)
}

/**
 * A grip a view paints so the user knows it is adjustable. Pure Canvas — a
 * small chevron-ish notch that fades in when the target is live.
 */
object Grip {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    fun draw(c: Canvas, x: Float, y: Float, r: Float, color: Int, kind: Int, v: Float) {
        p.color = color
        p.alpha = (255 * v).toInt().coerceIn(0, 255)
        if (p.alpha == 0) return
        p.strokeWidth = r * 0.22f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        when (kind) {
            Target.HANDLE_CORNER -> {
                c.drawLine(x - r, y, x, y, p)
                c.drawLine(x, y, x, y + r, p)
            }
            Target.HANDLE_BAR -> {
                c.drawLine(x - r, y, x + r, y, p)
                c.drawLine(x - r * 0.4f, y - r * 0.5f, x - r * 0.4f, y + r * 0.5f, p)
                c.drawLine(x + r * 0.4f, y - r * 0.5f, x + r * 0.4f, y + r * 0.5f, p)
            }
            Target.HANDLE_SWATCH -> c.drawCircle(x, y, r, p)
            Target.HANDLE_FRAME -> {
                path.reset()
                path.moveTo(x + r * 0.3f, y - r); path.lineTo(x + r, y - r * 0.3f)
                path.moveTo(x + r, y + r * 0.3f); path.lineTo(x + r * 0.3f, y + r)
                c.drawPath(path, p)
            }
            Target.HANDLE_DOT -> {
                p.style = Paint.Style.FILL
                c.drawCircle(x, y, r * 0.34f, p)
                p.style = Paint.Style.STROKE
            }
            else -> {
                c.drawCircle(x, y, r * 0.6f, p)
                c.drawCircle(x, y, r * 1.1f, p)
            }
        }
    }
}

