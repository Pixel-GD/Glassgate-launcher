package com.pixelgd.glassgate.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import com.pixelgd.glassgate.R
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.sp
import com.pixelgd.glassgate.util.tick
import com.pixelgd.glassgate.util.withAlpha

/**
 * The iOS 26 glass "Search" pill above the dock. While pages are being swiped it turns
 * into page dots, then settles back to the magnifier and label.
 *
 * Touch and hold, then slide, to scrub through pages like the iOS page dots. With Search off
 * the Home Screen ([dotsOnly]) it is just the dots, and any horizontal slide scrubs.
 */
class SearchPillView(context: Context) : View(context), PagedView.ScrollListener {

    private val label = context.getString(R.string.search)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.sp(13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.8f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var position = 0f
    private var pageCount = 1
    private var dotsAmount = 0f
    private val dotsSpring = Spring(Spring.STIFF_UI, 1f) { v -> dotsAmount = v; invalidate() }
    private val settle = Runnable { if (!dotsOnly && !scrubbing) dotsSpring.animateTo(0f) }
    private val glass = GlassDrawable(this, GlassStyle.PILL)

    /** Called with a page index while the user scrubs. */
    var onScrub: ((Int) -> Unit)? = null

    /** iOS with "Show on Home Screen" off for Search: page dots only, no glass until touched. */
    var dotsOnly = false
        set(value) {
            field = value
            contentDescription = if (value) "Page dots" else label
            dotsSpring.snapTo(if (value) 1f else 0f)
            glass.progress = if (value) 0f else 1f
            requestLayout()
        }

    private var scrubbing = false
    private var downX = 0f
    private val scrubScale = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { v ->
        scaleX = v; scaleY = v
        if (dotsOnly) glass.progress = (v - 1f) / 0.12f
    }.also { it.snapTo(1f) }
    private val startScrub = Runnable { beginScrub() }

    init {
        background = glass
        isClickable = true
        contentDescription = label
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textW = textPaint.measureText(label)
        val searchW = context.dp(18f) * 2 + context.dp(16f) + context.dp(6f) + textW
        val dotsW = context.dp(18f) * 2 + pageCount * context.dp(7f) + (pageCount - 1).coerceAtLeast(0) * context.dp(9f)
        val w = if (dotsOnly) dotsW else maxOf(searchW, dotsW)
        setMeasuredDimension(w.toInt(), context.dp(34f).toInt())
    }

    override fun onPageScroll(position: Float, pageCount: Int, isDragging: Boolean) {
        this.position = position
        if (this.pageCount != pageCount) {
            this.pageCount = pageCount
            requestLayout()
        }
        removeCallbacks(settle)
        if (pageCount > 1) {
            dotsSpring.animateTo(1f)
            postDelayed(settle, 900)
        }
        invalidate()
    }

    fun setPageCount(n: Int) {
        if (pageCount == n) return
        pageCount = n
        requestLayout()
        invalidate()
    }

    // ---- scrubbing ----

    private fun beginScrub() {
        if (pageCount < 2) return
        scrubbing = true
        isPressed = false
        parent?.requestDisallowInterceptTouchEvent(true)
        removeCallbacks(settle)
        dotsSpring.animateTo(1f)
        scrubScale.animateTo(1.12f)
        tick()
    }

    private fun scrubTo(rawX: Float) {
        // Like iOS, the whole screen width maps onto the pages, not just the pill.
        val screenW = rootView.width.coerceAtLeast(1)
        val page = (rawX / screenW * pageCount).toInt().coerceIn(0, pageCount - 1)
        if (page != Math.round(position)) {
            tick()
            onScrub?.invoke(page)
        }
    }

    private fun endScrub() {
        scrubbing = false
        scrubScale.animateTo(1f)
        removeCallbacks(settle)
        postDelayed(settle, 600)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                postDelayed(startScrub, if (dotsOnly) 120L else 300L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing && dotsOnly && kotlin.math.abs(event.x - downX) > context.dp(6f)) {
                    removeCallbacks(startScrub)
                    beginScrub()
                }
                if (scrubbing) { scrubTo(event.rawX); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startScrub)
                if (scrubbing) { endScrub(); return true }
                if (dotsOnly) return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val searchAlpha = 1f - dotsAmount
        if (searchAlpha > 0.01f) {
            val textW = textPaint.measureText(label)
            val r = context.dp(5.5f)
            val total = r * 2 + context.dp(6f) + textW
            val left = cx - total / 2f
            iconPaint.color = Color.WHITE.withAlpha(searchAlpha)
            canvas.drawCircle(left + r, cy - context.dp(1f), r, iconPaint)
            canvas.drawLine(left + r * 1.7f, cy + r * 0.7f - context.dp(1f), left + r * 2.3f, cy + r * 1.3f - context.dp(1f), iconPaint)
            textPaint.color = Color.WHITE.withAlpha(searchAlpha)
            canvas.drawText(label, left + r * 2 + context.dp(6f), cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
        if (dotsAmount > 0.01f) {
            val d = context.dp(7f)
            val gap = context.dp(9f)
            val n = pageCount
            val total = n * d + (n - 1) * gap
            var x = cx - total / 2f + d / 2f
            for (i in 0 until n) {
                val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
                dotPaint.color = Color.WHITE.withAlpha(dotsAmount * (0.35f + 0.65f * near))
                canvas.drawCircle(x, cy, d / 2f, dotPaint)
                x += d + gap
            }
        }
    }
}
