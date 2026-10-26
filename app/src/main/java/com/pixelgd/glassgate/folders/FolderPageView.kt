package com.pixelgd.glassgate.folders

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import com.pixelgd.glassgate.home.PagedView
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.withAlpha
import kotlin.math.abs
import kotlin.math.min

/** One page of an open folder: up to [cols] x [rows] icons, filled row by row. */
class FolderPageView(context: Context, private val cols: Int, private val rows: Int) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = MeasureSpec.makeMeasureSpec(w / cols, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(h / rows, MeasureSpec.AT_MOST)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l) / cols
        val ch = (b - t) / rows
        for (i in 0 until min(childCount, cols * rows)) {
            val c = getChildAt(i)
            val left = (i % cols) * cw
            val top = (i / cols) * ch + (ch - c.measuredHeight) / 2
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }
}

/** iOS page dots under an open folder; hidden when there is only one page. */
class PageDotsView(context: Context) : View(context), PagedView.ScrollListener {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = context.dp(7f)
    private val gap = context.dp(9f)
    private var count = 0
    private var position = 0f

    override fun onPageScroll(position: Float, pageCount: Int, isDragging: Boolean) {
        this.position = position
        count = pageCount
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(dot.toInt() * 2, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val total = count * dot + (count - 1) * gap
        var x = (width - total) / 2f + dot / 2f
        val y = height / 2f
        for (i in 0 until count) {
            val near = 1f - min(1f, abs(position - i))
            paint.color = Color.WHITE.withAlpha(0.35f + 0.65f * near)
            canvas.drawCircle(x, y, dot / 2f, paint)
            x += dot + gap
        }
    }
}
