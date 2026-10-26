package com.pixelgd.glassgate.edit

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import com.pixelgd.glassgate.DismissibleOverlay
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.LauncherActivity
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.glass.Squircle
import com.pixelgd.glassgate.icons.IconRenderer
import com.pixelgd.glassgate.icons.IconStyle
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.sp
import com.pixelgd.glassgate.util.tick
import com.pixelgd.glassgate.util.withAlpha

/**
 * The iOS 18+ "Customize" panel from jiggle mode's Edit menu: a glass sheet at the bottom with
 * the four icon looks (Default, Dark, Clear, Tinted), each previewed on one of your own apps.
 * Picking one restyles the home screen at once.
 */
@SuppressLint("ViewConstructor")
class CustomizeSheet private constructor(private val activity: LauncherActivity) : ViewGroup(activity), DismissibleOverlay {

    private val launcher = Launcher.of(activity)
    private val card = Card()
    private var shown = 0f
    private val spring = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { shown = it; requestLayout(); updateDim() }
    private var leaving = false

    init {
        addView(card)
        isClickable = true
        updateDim()
    }

    private fun updateDim() = setBackgroundColor(Color.BLACK.withAlpha(0.12f * shown.coerceIn(0f, 1f)))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
        card.measure(MeasureSpec.makeMeasureSpec(w - context.dpi(12f) * 2, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val bottomInset = rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())?.bottom ?: 0
        val h = card.measuredHeight
        val restTop = b - t - bottomInset - context.dpi(10f) - h
        val top = restTop + ((1f - shown) * (h + bottomInset + context.dpi(30f))).toInt()
        val left = context.dpi(12f)
        card.layout(left, top, left + card.measuredWidth, top + h)
        card.invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spring.animateTo(1f, 0f)
    }

    override fun onDetachedFromWindow() {
        spring.cancel()
        super.onDetachedFromWindow()
    }

    override fun dismiss() {
        if (leaving) { (parent as? ViewGroup)?.removeView(this); return }
        leaving = true
        spring.animateTo(0f) { (parent as? ViewGroup)?.removeView(this) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) dismiss()
        return true
    }

    private fun choose(style: IconStyle) {
        if (launcher.prefs.iconStyle == style) return
        launcher.prefs.iconStyle = style
        launcher.icons.style = style
        activity.home.rebuild()
        card.tick()
        card.invalidate()
    }

    /** Title and the four style swatches. */
    private inner class Card : View(activity) {
        private val styles = IconStyle.values()
        private val names = mapOf(IconStyle.DEFAULT to "Default", IconStyle.DARK to "Dark", IconStyle.CLEAR to "Clear", IconStyle.TINTED to "Tinted")
        private val iconPx = context.dpi(56f)
        private val samples = HashMap<IconStyle, Bitmap>()
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = context.sp(20f)
            typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            letterSpacing = -0.01f
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = context.sp(13f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2.5f)
            color = Color.WHITE
        }
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val pad = context.dp(20f)
        private val titleH = context.dp(48f)

        init {
            background = GlassDrawable(this, GlassStyle.FOLDER_OPEN.copy(cornerRadius = 34f))
            isClickable = true
            val app = launcher.layout.layout.dock.firstNotNullOfOrNull { launcher.apps[it] } ?: launcher.apps.all.firstOrNull()
            if (app != null) {
                val raw = runCatching { app.loadRawIcon(resources.displayMetrics.densityDpi) }.getOrNull()
                if (raw != null) for (s in styles) {
                    runCatching { IconRenderer.render(raw.constantState?.newDrawable(resources)?.mutate() ?: raw, iconPx, s, launcher.icons.tint) }
                        .getOrNull()?.let { samples[s] = it }
                }
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = pad + titleH + iconPx + context.dp(34f) + pad
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
        }

        private fun slot(i: Int): Float {
            val w = width - pad * 2
            return pad + w / styles.size * (i + 0.5f)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawText("Customize", pad, pad + titleH / 2f - (title.ascent() + title.descent()) / 2f, title)
            val top = pad + titleH
            styles.forEachIndexed { i, s ->
                val cx = slot(i)
                rect.set(cx - iconPx / 2f, top, cx + iconPx / 2f, top + iconPx)
                samples[s]?.let { canvas.drawBitmap(it, null, rect, bmpPaint) }
                if (launcher.prefs.iconStyle == s) {
                    val g = context.dp(4f)
                    rect.inset(-g, -g)
                    canvas.drawPath(Squircle.icon(rect.width(), rect.height(), left = rect.left, top = rect.top), ring)
                }
                canvas.drawText(names[s] ?: s.name, cx, top + iconPx + context.dp(24f), label)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP && event.y > pad + titleH - context.dp(8f)) {
                val w = (width - pad * 2) / styles.size
                val i = ((event.x - pad) / w).toInt()
                styles.getOrNull(i)?.let { choose(it) }
            }
            return true
        }
    }

    companion object {
        fun open(activity: LauncherActivity) {
            activity.overlay.addView(CustomizeSheet(activity), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }
}
