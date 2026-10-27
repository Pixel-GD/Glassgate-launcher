package com.pixelgd.glassgate.home

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.pixelgd.glassgate.util.Spring
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Horizontally paged container with iOS feel: one page per fling, rubber-band at the ends,
 * spring snapping. A downward swipe is reported through [onSwipeDown] (Spotlight).
 * Children are full-size pages laid side by side.
 */
class PagedView(context: Context) : ViewGroup(context) {

    fun interface ScrollListener {
        /** [position] is fractional, e.g. 1.5 means halfway between page 1 and 2. */
        fun onPageScroll(position: Float, pageCount: Int, isDragging: Boolean)
    }

    /** A downward swipe; the argument is where it started across the screen, 0 (left) to 1. */
    var onSwipeDown: ((Float) -> Unit)? = null
    var onLongPressEmpty: (() -> Unit)? = null
    private val scrollListeners = LinkedHashSet<ScrollListener>()

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var verticalGesture = false

    var currentPage = 0
        private set

    private val spring = Spring(Spring.STIFF_SNAP, Spring.DAMP_SNAP) { v ->
        scrollTo(v.roundToInt(), 0)
    }

    private val longPress = Runnable {
        if (!dragging && !verticalGesture) {
            performLongClick()
            onLongPressEmpty?.invoke()
        }
    }

    val pageCount get() = childCount

    fun addScrollListener(l: ScrollListener) { scrollListeners += l }

    fun snapToPage(page: Int, animate: Boolean = true, velocityPx: Float = 0f) {
        val p = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        currentPage = p
        val target = (p * width).toFloat()
        if (animate && width > 0) {
            spring.snapToValueIfIdle(scrollX.toFloat())
            spring.animateTo(target, -velocityPx)
        } else {
            spring.snapTo(target)
        }
    }

    private fun Spring.snapToValueIfIdle(v: Float) {
        if (!isRunning) snapTo(v)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val pos = if (width == 0) 0f else l / width.toFloat()
        scrollListeners.forEach { it.onPageScroll(pos, childCount, dragging || spring.isRunning) }
        // Glass children sample the wallpaper at their window position, so they must
        // redraw as the page moves under them; plain icons don't need to.
        for (i in 0 until childCount) {
            val page = getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until page.childCount) {
                val c = page.getChildAt(j)
                if (c is FolderIconView) c.invalidate()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (changed && !spring.isRunning) {
            currentPage = currentPage.coerceIn(0, (childCount - 1).coerceAtLeast(0))
            scrollTo(currentPage * w, 0)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                dragging = spring.isRunning
                verticalGesture = false
                if (dragging) spring.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) {
                    dragging = true
                    lastX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (!dragging && dy > slop * 2 && dy > abs(dx) * 1.5f) {
                    verticalGesture = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endTracking()
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(longPress)
                if (verticalGesture) return true
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) dragging = true
                if (!dragging && dy > slop * 2 && dy > abs(dx) * 1.5f) { verticalGesture = true; return true }
                if (dragging) {
                    val delta = lastX - ev.x
                    lastX = ev.x
                    val max = ((childCount - 1) * width).toFloat()
                    var next = scrollX + delta
                    if (next < 0 || next > max) next = scrollX + delta * 0.35f // rubber band
                    scrollTo(next.roundToInt(), 0)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (verticalGesture) {
                    if (ev.y - downY > slop * 4) onSwipeDown?.invoke(if (width > 0) downX / width else 0.5f)
                } else if (dragging) {
                    settle()
                }
                endTracking()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) settle()
                endTracking()
            }
        }
        return true
    }

    private fun settle() {
        val vt = velocity ?: return snapToPage(currentPage)
        vt.computeCurrentVelocity(1000)
        val vx = vt.xVelocity
        val pos = scrollX / width.toFloat()
        val target = when {
            vx < -minFling -> kotlin.math.floor(pos).toInt() + 1
            vx > minFling -> kotlin.math.ceil(pos).toInt() - 1
            else -> pos.roundToInt()
        }
        dragging = false
        snapToPage(target, true, vx)
    }

    private fun track(ev: MotionEvent) {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(ev)
    }

    private fun endTracking() {
        dragging = false
        verticalGesture = false
        velocity?.recycle()
        velocity = null
    }

    fun pageAt(index: Int): View? = getChildAt(index)
}
