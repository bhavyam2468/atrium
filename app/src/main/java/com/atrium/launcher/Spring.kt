package com.atrium.launcher

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tiny damped spring driven by Choreographer. Preserves finger velocity on release,
 * so a fling continues exactly where the finger left off. Zero allocations per frame.
 */
internal class Spring(
    private val onUpdate: (Float) -> Unit,
    private val onEnd: (Float) -> Unit,
    stiffness: Float = 380f,
    damping: Float = 0.92f,
) {
    var value = 0f
        private set
    var running = false
        private set
    private var vel = 0f
    private var target = 0f
    private var last = 0L
    private val k = stiffness
    private val c = 2f * sqrt(stiffness) * damping
    private val cb = Choreographer.FrameCallback { step(it) }

    fun snap(v: Float) { stop(); value = v; onUpdate(v) }

    fun stop() {
        if (running) { running = false; Choreographer.getInstance().removeFrameCallback(cb) }
        vel = 0f
    }

    fun animateTo(t: Float, velocity: Float = 0f) {
        target = t; vel = velocity
        if (!running) { running = true; last = 0L; Choreographer.getInstance().postFrameCallback(cb) }
    }

    /** Drag overrides value directly. */
    fun set(v: Float) { value = v; onUpdate(v) }

    private fun step(now: Long) {
        if (!running) return
        val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.032f)
        last = now
        val h = dt / 2f
        repeat(2) {
            vel += (-k * (value - target) - c * vel) * h
            value += vel * h
        }
        if (abs(value - target) < 0.0004f && abs(vel) < 0.01f) {
            running = false; value = target; vel = 0f
            onUpdate(value); onEnd(value); return
        }
        onUpdate(value)
        Choreographer.getInstance().postFrameCallback(cb)
    }
}
