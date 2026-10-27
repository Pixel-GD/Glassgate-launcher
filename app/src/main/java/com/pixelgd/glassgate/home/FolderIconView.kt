package com.pixelgd.glassgate.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.util.Main
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.sp

/**
 * A folder on a home page: a glass squircle with a 3x3 preview of its first nine apps,
 * and the folder name underneath. The folders module owns opening and editing.
 */
@SuppressLint("ViewConstructor")
class FolderIconView(
    context: Context,
    val iconSizePx: Int,
    var showLabel: Boolean = true,
) : View(context) {

    private val launcher = Launcher.of(context)
    private val glass = GlassDrawable(this, GlassStyle.FOLDER_ICON)
    private val miniPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.sp(Launcher.of(context).prefs.labelSizeSp)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(context.dp(3f), 0f, context.dp(0.5f), Color.argb(90, 0, 0, 0))
    }
    private val labelGap = context.dp(5f)

    var folder: HomeItem.Folder? = null
        set(value) { field = value; contentDescription = value?.title; invalidate() }

    init {
        isClickable = true
        isLongClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelH = if (showLabel) (labelGap + labelPaint.fontSpacing).toInt() else 0
        setMeasuredDimension(
            resolveSize(iconSizePx, widthMeasureSpec),
            resolveSize(iconSizePx + labelH, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val f = folder ?: return
        val left = (width - iconSizePx) / 2
        glass.setBounds(left, 0, left + iconSizePx, iconSizePx)
        glass.draw(canvas)

        val pad = iconSizePx * 0.14f
        val gap = iconSizePx * 0.06f
        val mini = (iconSizePx - pad * 2 - gap * 2) / 3f
        val miniPx = mini.toInt().coerceAtLeast(1)
        f.keys.take(9).forEachIndexed { i, key ->
            val app = launcher.apps[key] ?: return@forEachIndexed
            val bmp = launcher.icons.cached(app, miniPx) ?: run {
                Main.post { launcher.icons.render(app, miniPx); invalidate() }
                return@forEachIndexed
            }
            val x = left + pad + (i % 3) * (mini + gap)
            val y = pad + (i / 3) * (mini + gap)
            canvas.drawBitmap(bmp, x, y, miniPaint)
        }

        if (showLabel) {
            val text = TextUtils.ellipsize(f.title, labelPaint, width.toFloat(), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, width / 2f, iconSizePx + labelGap - labelPaint.ascent(), labelPaint)
        }
    }
}
