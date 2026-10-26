package com.pixelgd.glassgate.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.badges.BadgeDrawer
import com.pixelgd.glassgate.util.Main
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.sp
import java.util.concurrent.Executors

/**
 * One app icon with its label, iOS style: squircle icon, small white label with a soft
 * shadow, shrink-on-press with a springy release. Used on pages, in the dock (no label),
 * in folders and in the App Library.
 */
@SuppressLint("ViewConstructor")
open class AppIconView(
    context: Context,
    val iconSizePx: Int,
    var showLabel: Boolean = true,
) : View(context) {

    private val launcher = Launcher.of(context)
    var app: AppEntry? = null
        set(value) {
            if (field == value) return
            field = value
            icon = null
            label = value?.label.orEmpty()
            contentDescription = value?.label
            loadIcon()
        }

    private var icon: Bitmap? = null
    private var label = ""
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.sp(12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(context.dp(3f), 0f, context.dp(0.5f), Color.argb(90, 0, 0, 0))
    }
    private val labelGap = context.dp(5f)

    /** Extra badge drawing (notification dots); set by the badges module. */
    var badgeDrawer: ((Canvas, iconLeft: Float, iconTop: Float, iconSize: Float) -> Unit)? = null

    private val pressSpring = Spring(Spring.STIFF_BOUNCY, Spring.DAMP_BOUNCY) { v ->
        scaleX = v; scaleY = v
    }.also { it.snapTo(1f) }

    init {
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
        BadgeDrawer.attach(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelH = if (showLabel) (labelGap + labelPaint.fontSpacing).toInt() else 0
        setMeasuredDimension(
            resolveSize(iconSizePx, widthMeasureSpec),
            resolveSize(iconSizePx + labelH, heightMeasureSpec),
        )
    }

    /** Where the icon sits, so overlays (folders, menus) can animate from it. */
    fun iconLeft() = (width - iconSizePx) / 2f
    fun iconTop() = 0f

    override fun onDraw(canvas: Canvas) {
        val left = iconLeft()
        val top = iconTop()
        icon?.let { canvas.drawBitmap(it, left, top, iconPaint) }
        badgeDrawer?.invoke(canvas, left, top, iconSizePx.toFloat())
        if (showLabel && label.isNotEmpty()) {
            val text = TextUtils.ellipsize(label, labelPaint, width.toFloat() - dp(4f), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, width / 2f, top + iconSizePx + labelGap - labelPaint.ascent(), labelPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressSpring.animateTo(0.86f, 0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressSpring.animateTo(1f)
        }
        return super.onTouchEvent(event)
    }

    /** Re-renders after a style or tint change. */
    fun refreshIcon() {
        icon = null
        loadIcon()
    }

    private fun loadIcon() {
        val a = app ?: run { invalidate(); return }
        launcher.icons.cached(a, iconSizePx)?.let { icon = it; invalidate(); return }
        val size = iconSizePx
        ICON_THREAD.execute {
            val bmp = runCatching { launcher.icons.render(a, size) }.getOrNull()
            Main.post {
                if (app == a && bmp != null) {
                    icon = bmp
                    invalidate()
                }
            }
        }
    }

    private fun dp(v: Float) = context.dp(v)

    companion object {
        private val ICON_THREAD = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "icon-render").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    }
}
