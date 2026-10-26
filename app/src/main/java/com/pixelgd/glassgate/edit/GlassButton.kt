package com.pixelgd.glassgate.edit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.sp

/** A glass capsule button with a short bold label: jiggle mode's "Edit" and "Done". */
@SuppressLint("ViewConstructor")
class GlassButton(context: Context, private val label: String) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.sp(15f)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        textAlign = Paint.Align.CENTER
        setShadowLayer(context.dp(2f), 0f, context.dp(0.5f), Color.argb(50, 0, 0, 0))
    }
    private val press = Spring(Spring.STIFF_BOUNCY, Spring.DAMP_BOUNCY) { scaleX = it; scaleY = it }.also { it.snapTo(1f) }

    init {
        background = GlassDrawable(this, GlassStyle.BUTTON)
        isClickable = true
        contentDescription = label
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = paint.measureText(label) + context.dp(18f) * 2
        setMeasuredDimension(w.toInt(), context.dp(36f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText(label, width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> press.animateTo(0.9f, 0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press.animateTo(1f)
        }
        return super.onTouchEvent(event)
    }
}
