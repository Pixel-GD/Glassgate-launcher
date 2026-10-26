package com.pixelgd.glassgate.widgets

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.SizeF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.tick
import com.pixelgd.glassgate.util.withAlpha
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An iOS widget stack: several widgets of one size in one slot. Swipe up or down to flip
 * through them; the page dots on the right edge show while you swipe and then fade.
 * Horizontal swipes still go to the home pages.
 */
@SuppressLint("ViewConstructor")
class WidgetStackView(
    context: Context,
    spec: WidgetSpec.Stack,
    size: WidgetSize,
    private val item: HomeItem.Widget?,
) : FrameLayout(context) {

    private val pages: List<View> = spec.entries.map { WidgetViews.content(context, it, size) }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    /** Fractional position: 1.5 is halfway from the second widget to the third. */
    private var position = 0f
    var current = 0
        private set

    private val scroll = Spring(Spring.STIFF_SNAP, Spring.DAMP_UI) { v -> position = v; arrange() }
    private var dotsAlpha = 0f
    private val dotsSpring = Spring(Spring.STIFF_UI, 1f) { v -> dotsAlpha = v; invalidate() }
    private val hideDots = Runnable { dotsSpring.animateTo(0f) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        for (p in pages) addView(p, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setWillNotDraw(false)
        current = (item?.let { remembered[key(it)] } ?: 0).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        scroll.snapTo(current.toFloat())
        contentDescription = "Widget stack, ${pages.size} widgets"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = context.dp(1f)
        for (p in pages) (p as? AppWidgetHostView)?.updateAppWidgetSize(Bundle(), listOf(SizeF(w / d, h / d)))
        arrange()
    }

    /** Neighbours slide vertically with the drag; only the ones in view are drawn. */
    private fun arrange() {
        val h = height.toFloat()
        for ((i, p) in pages.withIndex()) {
            val offset = i - position
            p.translationY = offset * h
            p.visibility = if (abs(offset) < 1f) VISIBLE else INVISIBLE
            // iOS fades and shrinks the outgoing widget a touch.
            val f = (1f - abs(offset)).coerceIn(0f, 1f)
            p.alpha = 0.4f + 0.6f * f
            p.scaleX = 0.94f + 0.06f * f
            p.scaleY = p.scaleX
        }
        invalidate()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (pages.size < 2) return false
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastY = ev.y
                dragging = scroll.isRunning
                if (dragging) scroll.cancel()
                // Keep the pager (and its swipe-down-for-Search) off until we know the direction.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (!dragging && dx > slop && dx > dy) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                } else if (!dragging && dy > slop && dy > dx) {
                    startDrag(ev)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endTracking()
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (pages.size < 2) return false
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastY = ev.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (!dragging && dx > slop && dx > dy) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                if (!dragging && dy > slop && dy > dx) startDrag(ev)
                if (dragging) {
                    val delta = (lastY - ev.y) / height
                    lastY = ev.y
                    var next = position + delta
                    val max = (pages.size - 1).toFloat()
                    if (next < 0f || next > max) next = position + delta * 0.35f // rubber band
                    scroll.snapTo(next)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle()
                endTracking()
            }
        }
        return true
    }

    private fun startDrag(ev: MotionEvent) {
        dragging = true
        lastY = ev.y
        parent?.requestDisallowInterceptTouchEvent(true)
        removeCallbacks(hideDots)
        dotsSpring.animateTo(1f)
    }

    private fun settle() {
        val vt = velocity
        vt?.computeCurrentVelocity(1000)
        val vy = vt?.yVelocity ?: 0f
        val target = when {
            vy < -minFling -> kotlin.math.floor(position).toInt() + 1
            vy > minFling -> kotlin.math.ceil(position).toInt() - 1
            else -> position.roundToInt()
        }.coerceIn(0, pages.size - 1)
        if (target != current) tick()
        current = target
        item?.let { remembered[key(it)] = target }
        scroll.animateTo(target.toFloat(), -vy / height.coerceAtLeast(1))
        postDelayed(hideDots, 1200)
    }

    private fun track(ev: MotionEvent) {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(ev)
    }

    private fun endTracking() {
        dragging = false
        velocity?.recycle()
        velocity = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (dotsAlpha < 0.01f || pages.size < 2) return
        val r = context.dp(2.5f)
        val gap = context.dp(8f)
        val x = width - context.dp(8f)
        val top = height / 2f - gap * (pages.size - 1) / 2f
        for (i in pages.indices) {
            val near = (1f - abs(i - position)).coerceIn(0f, 1f)
            dotPaint.color = Color.WHITE.withAlpha(dotsAlpha * (0.35f + 0.65f * near))
            canvas.drawCircle(x, top + gap * i, r, dotPaint)
        }
    }

    companion object {
        /** Which widget each stack shows, so a page rebuild doesn't flip it back to the first. */
        private val remembered = HashMap<String, Int>()
        private fun key(item: HomeItem.Widget) = "${item.x},${item.y},${item.provider}"
    }
}
