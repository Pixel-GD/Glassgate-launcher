package com.pixelgd.glassgate.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.pixelgd.glassgate.R
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.sp
import com.pixelgd.glassgate.util.withAlpha

/**
 * The iOS 26 glass "Search" pill above the dock. While pages are being swiped it turns
 * into page dots, then settles back to the magnifier and label.
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
    private val settle = Runnable { dotsSpring.animateTo(0f) }

    init {
        background = GlassDrawable(this, GlassStyle.PILL)
        isClickable = true
        contentDescription = label
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textW = textPaint.measureText(label)
        val w = (context.dp(18f) * 2 + context.dp(16f) + context.dp(6f) + textW).toInt()
        setMeasuredDimension(w, context.dp(34f).toInt())
    }

    override fun onPageScroll(position: Float, pageCount: Int, isDragging: Boolean) {
        this.position = position
        this.pageCount = pageCount
        removeCallbacks(settle)
        if (pageCount > 1) {
            dotsSpring.animateTo(1f)
            postDelayed(settle, 900)
        }
        invalidate()
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
