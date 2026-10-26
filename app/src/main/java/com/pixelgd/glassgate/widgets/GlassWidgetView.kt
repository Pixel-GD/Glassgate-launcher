package com.pixelgd.glassgate.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.withAlpha

/**
 * Base for Glassgate's own widgets. They draw straight onto the glass card in white, like
 * iOS 26 widgets in the Clear look, and scale everything from [u], one iOS point at this
 * card's size (a small widget is 170pt wide on iPhone).
 */
abstract class GlassWidgetView(context: Context, val size: WidgetSize) : View(context) {

    /** One iOS point at this card's size, in px. */
    protected val u: Float
        get() {
            val smallSide = when (size) {
                WidgetSize.SMALL -> minOf(width, height).toFloat()
                WidgetSize.MEDIUM -> height.toFloat()
                WidgetSize.LARGE -> (height - context.dp(16f)) / 2f
            }
            return (smallSide / 170f).coerceAtLeast(0.1f)
        }

    /** Standard iOS widget content inset, 16pt. */
    protected val pad get() = 16f * u

    protected fun textPaint(weight: Int, alpha: Float = 1f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, weight, false)
        color = Color.WHITE.withAlpha(alpha)
        setShadowLayer(context.dp(2f), 0f, context.dp(0.5f), Color.argb(50, 0, 0, 0))
    }

    /** Draws [text] with its top (cap line) at [top], ellipsized to [maxWidth]. Returns its baseline. */
    protected fun Canvas.text(text: String, x: Float, top: Float, paint: TextPaint, maxWidth: Float = Float.MAX_VALUE): Float {
        val t = if (maxWidth < Float.MAX_VALUE) TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString() else text
        val baseline = top - paint.fontMetrics.ascent * 0.78f
        drawText(t, x, baseline, paint)
        return baseline
    }

    /** Height of a line from its cap line to just under its descenders. */
    protected fun TextPaint.lineHeight() = -fontMetrics.ascent * 0.78f + fontMetrics.descent

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
}
