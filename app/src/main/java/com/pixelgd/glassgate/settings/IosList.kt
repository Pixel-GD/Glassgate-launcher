package com.pixelgd.glassgate.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi

/**
 * Builders for an iOS "inset grouped" list: grey page, white rounded cards, 17sp rows with
 * hairline separators, small grey headers and footers. Plain Views only.
 */
class IosList(private val context: Context, val colors: IosColors) {

    private val side = context.dpi(16f)

    /** Small grey caps above a card. */
    fun header(text: String) = TextView(context).apply {
        this.text = text.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(colors.secondary)
        setPadding(side + context.dpi(4f), context.dpi(26f), side, context.dpi(7f))
    }

    /** Grey explanatory text under a card. */
    fun footer(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(colors.secondary)
        setLineSpacing(0f, 1.1f)
        setPadding(side + context.dpi(4f), context.dpi(7f), side, 0)
    }

    /**
     * A rounded card; rows added to it get separators between them, starting [separatorInsetDp]
     * from the left so they line up with the row titles.
     */
    fun card(separatorInsetDp: Float = 16f) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(this@IosList.colors.card)
            cornerRadius = context.dp(26f)
        }
        clipToOutline = true
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = Separator(colors, Math.max(1, context.dpi(0.33f)), context.dpi(separatorInsetDp))
    }

    /** A row: optional leading view, title (and subtitle), then a trailing view or value text. */
    fun row(
        title: String,
        subtitle: String? = null,
        leading: View? = null,
        trailing: View? = null,
        value: String? = null,
        chevron: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): Row = Row(title, subtitle, leading, trailing, value, chevron, onClick)

    inner class Row(
        title: String,
        subtitle: String?,
        leading: View?,
        trailing: View?,
        value: String?,
        chevron: Boolean,
        onClick: (() -> Unit)?,
    ) : LinearLayout(context) {

        val titleView = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(colors.label)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitleView: TextView? = subtitle?.let {
            TextView(context).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(colors.secondary)
            }
        }
        val valueView: TextView? = value?.let {
            TextView(context).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(colors.secondary)
                maxLines = 1
            }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dpi(52f)
            setPadding(side, context.dpi(8f), side, context.dpi(8f))
            leading?.let {
                addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = context.dpi(14f)
                })
            }
            val texts = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(titleView)
                subtitleView?.let { addView(it) }
            }
            addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            valueView?.let {
                addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = context.dpi(8f)
                })
            }
            trailing?.let {
                addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = context.dpi(8f)
                })
            }
            if (chevron) {
                addView(Chevron(context, colors), LayoutParams(context.dpi(14f), context.dpi(20f)).apply {
                    marginStart = context.dpi(8f)
                })
            }
            if (onClick != null) {
                isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(colors.cardPressed))
                    addState(intArrayOf(), ColorDrawable(0))
                }
                setOnClickListener { onClick() }
            }
        }
    }

    /** A blue checkmark for list selections. */
    fun check(): View = Check(context, colors)

    /** The hairline between rows, inset to the row's text. */
    private class Separator(colors: IosColors, private val h: Int, private val inset: Int) : Drawable() {
        private val paint = Paint().apply { color = colors.separator }
        override fun getIntrinsicHeight() = h
        override fun draw(canvas: Canvas) {
            canvas.drawRect(bounds.left + inset.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    @SuppressLint("ViewConstructor")
    private class Chevron(context: Context, colors: IosColors) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2.2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colors.separator
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()
            path.moveTo(w * 0.35f, h * 0.28f)
            path.lineTo(w * 0.68f, h * 0.5f)
            path.lineTo(w * 0.35f, h * 0.72f)
            canvas.drawPath(path, paint)
        }
    }

    @SuppressLint("ViewConstructor")
    private class Check(context: Context, colors: IosColors) : View(context) {
        private val size = context.dpi(22f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2.4f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colors.accent
        }
        private val path = Path()
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size, size)
        override fun onDraw(canvas: Canvas) {
            val s = size.toFloat()
            path.reset()
            path.moveTo(s * 0.2f, s * 0.52f)
            path.lineTo(s * 0.42f, s * 0.74f)
            path.lineTo(s * 0.82f, s * 0.28f)
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    }
}
