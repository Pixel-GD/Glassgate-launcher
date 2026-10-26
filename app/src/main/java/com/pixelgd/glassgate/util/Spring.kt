package com.pixelgd.glassgate.util

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A damped spring driven by Choreographer. Every iOS-style motion in the launcher
 * (page snapping, icon press, folder open, App Library zoom) should use this so the
 * whole UI shares one feel. Main thread only.
 */
class Spring(
    private val stiffness: Float = STIFF_UI,
    private val dampingRatio: Float = DAMP_UI,
    private val onUpdate: (Float) -> Unit,
) : Choreographer.FrameCallback {

    var value = 0f
        private set
    var target = 0f
        private set
    var velocity = 0f
        private set
    var isRunning = false
        private set

    private var lastFrameNanos = 0L
    private var endAction: (() -> Unit)? = null

    /** Jumps to [v] without animating. */
    fun snapTo(v: Float) {
        cancel()
        value = v
        target = v
        velocity = 0f
        onUpdate(v)
    }

    /** @param startVelocity units per second, e.g. a fling velocity in px/s. */
    fun animateTo(t: Float, startVelocity: Float = velocity, onEnd: (() -> Unit)? = null) {
        target = t
        velocity = startVelocity
        endAction = onEnd
        if (!isRunning) {
            isRunning = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun cancel() {
        if (isRunning) {
            isRunning = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
        endAction = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        var dt = if (lastFrameNanos == 0L) 1f / 120f else (frameTimeNanos - lastFrameNanos) / 1e9f
        lastFrameNanos = frameTimeNanos
        dt = min(dt, 1f / 30f)
        val damping = 2f * dampingRatio * sqrt(stiffness)
        // Semi-implicit Euler in sub-steps keeps stiff springs stable at 60-120Hz.
        val h = dt / SUBSTEPS
        repeat(SUBSTEPS) {
            val accel = -stiffness * (value - target) - damping * velocity
            velocity += accel * h
            value += velocity * h
        }
        val scale = max(1f, abs(target))
        if (abs(velocity) < 0.02f * scale && abs(value - target) < 0.002f * scale) {
            value = target
            velocity = 0f
            isRunning = false
            onUpdate(value)
            val end = endAction
            endAction = null
            end?.invoke()
            return
        }
        onUpdate(value)
        Choreographer.getInstance().postFrameCallback(this)
    }

    companion object {
        private const val SUBSTEPS = 4
        /** General UI motion: slight overshoot, like UIKit's default spring. */
        const val STIFF_UI = 380f
        const val DAMP_UI = 0.82f
        /** Snappy, no overshoot: page snapping and drags. */
        const val STIFF_SNAP = 520f
        const val DAMP_SNAP = 1f
        /** Bouncy: icon press release, jiggle. */
        const val STIFF_BOUNCY = 700f
        const val DAMP_BOUNCY = 0.55f
    }
}
