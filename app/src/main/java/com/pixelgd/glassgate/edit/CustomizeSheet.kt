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
import com.pixelgd.glassgate.Prefs
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
 * The iOS 26 "Customize" panel from jiggle mode's Edit menu: a glass sheet at the bottom with
 * the four icon looks (Default, Dark, Clear, Tinted), each previewed on one of your own apps,
 * plus Small / Large, Auto / Light / Dark and the Tinted colour. Changes apply at once.
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

    private fun commit(block: () -> Unit) {
        block()
        activity.home.rebuild()
        card.tick()
        card.renderSamples()
        card.invalidate()
    }

    /**
     * iOS 26's Customize panel: Small / Large in the corner, the four looks, the Auto / Light /
     * Dark switch, and for Tinted a colour slider with a wallpaper eyedropper.
     */
    private inner class Card : View(activity) {
        private val prefs = launcher.prefs
        private val styles = IconStyle.values()
        private val names = mapOf(IconStyle.DEFAULT to "Default", IconStyle.DARK to "Dark", IconStyle.CLEAR to "Clear", IconStyle.TINTED to "Tinted")
        private val modes = Prefs.Appearance.values()
        private val modeNames = mapOf(Prefs.Appearance.AUTO to "Auto", Prefs.Appearance.LIGHT to "Light", Prefs.Appearance.DARK to "Dark")
        private val iconPx = context.dpi(56f)
        private val samples = HashMap<IconStyle, Bitmap>()
        private var raw: android.graphics.drawable.Drawable? = null
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
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val pad = context.dp(20f)
        private val titleH = context.dp(48f)
        private val swatchH = iconPx + context.dp(34f)
        private val segH = context.dp(34f)
        private val hueH = context.dp(30f)
        private val gap = context.dp(14f)
        private val sizeBtn = context.dp(34f)

        /** Hue while the slider is dragged, before it's saved. */
        private var dragHue = -1f

        init {
            background = GlassDrawable(this, GlassStyle.FOLDER_OPEN.copy(cornerRadius = 34f))
            isClickable = true
            val app = launcher.layout.layout.dock.firstNotNullOfOrNull { launcher.apps[it] } ?: launcher.apps.all.firstOrNull()
            raw = app?.let { runCatching { it.loadRawIcon(resources.displayMetrics.densityDpi) }.getOrNull() }
            renderSamples()
        }

        private fun tintNow(): Int =
            if (dragHue >= 0f) hueColor(dragHue) else prefs.tintColor.takeIf { it != 0 } ?: launcher.wallpaper.current?.accent ?: launcher.icons.tint

        fun renderSamples() {
            val r = raw ?: return
            val dark = prefs.iconsDark()
            for (s in styles) {
                runCatching { IconRenderer.render(r.constantState?.newDrawable(resources)?.mutate() ?: r, iconPx, s, tintNow(), dark) }
                    .getOrNull()?.let { samples[s] = it }
            }
        }

        private val showsHue get() = prefs.iconStyle == IconStyle.TINTED

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var h = pad + titleH + swatchH + gap + segH + pad
            if (showsHue) h += gap + hueH
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
        }

        private fun slot(i: Int): Float {
            val w = width - pad * 2
            return pad + w / styles.size * (i + 0.5f)
        }

        private val swatchTop get() = pad + titleH
        private val segTop get() = swatchTop + swatchH + gap
        private val hueTop get() = segTop + segH + gap

        /** Small and Large buttons, right-aligned in the title row. */
        private fun sizeButton(large: Boolean, out: RectF) {
            val right = width - pad - if (large) 0f else sizeBtn + context.dp(8f)
            val top = pad + (titleH - sizeBtn) / 2f
            out.set(right - sizeBtn, top, right, top + sizeBtn)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawText("Customize", pad, pad + titleH / 2f - (title.ascent() + title.descent()) / 2f, title)

            for (large in listOf(false, true)) {
                sizeButton(large, rect)
                val on = prefs.largeIcons == large
                fill.style = Paint.Style.FILL
                fill.color = Color.WHITE.withAlpha(if (on) 0.32f else 0.12f)
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, fill)
                val g = rect.width() * if (large) 0.46f else 0.30f
                val sq = RectF(rect.centerX() - g / 2, rect.centerY() - g / 2, rect.centerX() + g / 2, rect.centerY() + g / 2)
                fill.color = Color.WHITE.withAlpha(if (on) 1f else 0.7f)
                canvas.drawPath(Squircle.icon(sq.width(), sq.height(), left = sq.left, top = sq.top), fill)
            }

            styles.forEachIndexed { i, s ->
                val cx = slot(i)
                rect.set(cx - iconPx / 2f, swatchTop, cx + iconPx / 2f, swatchTop + iconPx)
                samples[s]?.let { canvas.drawBitmap(it, null, rect, bmpPaint) }
                if (prefs.iconStyle == s) {
                    val g = context.dp(4f)
                    rect.inset(-g, -g)
                    canvas.drawPath(Squircle.icon(rect.width(), rect.height(), left = rect.left, top = rect.top), ring)
                }
                canvas.drawText(names[s] ?: s.name, cx, swatchTop + iconPx + context.dp(24f), label)
            }

            // Auto / Light / Dark. Dark pins every look dark, so the switch greys out for it.
            val enabled = prefs.iconStyle != IconStyle.DARK
            rect.set(pad, segTop, width - pad, segTop + segH)
            fill.color = Color.WHITE.withAlpha(0.12f)
            canvas.drawRoundRect(rect, segH / 2, segH / 2, fill)
            val seg = rect.width() / modes.size
            val sel = modes.indexOf(prefs.iconAppearance)
            if (enabled) {
                fill.color = Color.WHITE.withAlpha(0.30f)
                val inset = context.dp(2f)
                canvas.drawRoundRect(RectF(rect.left + seg * sel + inset, rect.top + inset, rect.left + seg * (sel + 1) - inset, rect.bottom - inset),
                    segH / 2, segH / 2, fill)
            }
            label.color = Color.WHITE.withAlpha(if (enabled) 1f else 0.4f)
            modes.forEachIndexed { i, m ->
                canvas.drawText(modeNames[m] ?: m.name, rect.left + seg * (i + 0.5f), rect.centerY() - (label.ascent() + label.descent()) / 2f, label)
            }
            label.color = Color.WHITE

            if (showsHue) drawHue(canvas)
        }

        private fun hueTrack(out: RectF) = out.set(pad, hueTop, width - pad - hueH - context.dp(12f), hueTop + hueH)

        private fun drawHue(canvas: Canvas) {
            hueTrack(rect)
            val colors = IntArray(7) { hueColor(it / 6f) }
            fill.shader = android.graphics.LinearGradient(rect.left, 0f, rect.right, 0f, colors, null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, hueH / 2, hueH / 2, fill)
            fill.shader = null
            val hue = if (dragHue >= 0f) dragHue else prefs.tintColor.takeIf { it != 0 }?.let { hueOf(it) }
            if (hue != null) {
                val x = rect.left + hueH / 2 + (rect.width() - hueH) * hue
                fill.color = hueColor(hue)
                canvas.drawCircle(x, rect.centerY(), hueH / 2 - context.dp(2f), fill)
                canvas.drawCircle(x, rect.centerY(), hueH / 2 - context.dp(2f), ring)
            }
            // The wallpaper eyedropper: back to the wallpaper's own colour.
            val cx = width - pad - hueH / 2
            val cy = rect.centerY()
            fill.color = launcher.wallpaper.current?.accent ?: Color.GRAY
            canvas.drawCircle(cx, cy, hueH / 2, fill)
            if (prefs.tintColor == 0 && dragHue < 0f) canvas.drawCircle(cx, cy, hueH / 2 + context.dp(3f), ring)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            if (showsHue && y >= hueTop - context.dp(6f) && y <= hueTop + hueH + context.dp(6f)) {
                hueTrack(rect)
                if (x <= rect.right + context.dp(4f)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragHue = ((x - rect.left - hueH / 2) / (rect.width() - hueH)).coerceIn(0f, 1f)
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        val c = hueColor(dragHue)
                        dragHue = -1f
                        commit { prefs.tintColor = c }
                    } else {
                        renderSamples()
                        invalidate()
                    }
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    commit { prefs.tintColor = 0 }
                }
                return true
            }
            if (event.actionMasked != MotionEvent.ACTION_UP) return true
            for (large in listOf(false, true)) {
                sizeButton(large, rect)
                rect.inset(-context.dp(6f), -context.dp(6f))
                if (rect.contains(x, y)) {
                    if (prefs.largeIcons != large) commit { prefs.largeIcons = large }
                    return true
                }
            }
            when {
                y > swatchTop - context.dp(8f) && y < swatchTop + swatchH -> {
                    val w = (width - pad * 2) / styles.size
                    val s = styles.getOrNull(((x - pad) / w).toInt()) ?: return true
                    if (prefs.iconStyle != s) {
                        commit { prefs.iconStyle = s }
                        requestLayout()
                    }
                }
                y >= segTop && y <= segTop + segH && prefs.iconStyle != IconStyle.DARK -> {
                    val seg = (width - pad * 2) / modes.size
                    val m = modes.getOrNull(((x - pad) / seg).toInt()) ?: return true
                    if (prefs.iconAppearance != m) commit { prefs.iconAppearance = m }
                }
            }
            return true
        }
    }

    companion object {
        fun hueColor(h: Float): Int = Color.HSVToColor(floatArrayOf(h * 360f, 0.62f, 0.86f))

        fun hueOf(c: Int): Float = FloatArray(3).also { Color.colorToHSV(c, it) }[0] / 360f

        fun open(activity: LauncherActivity) {
            activity.overlay.addView(CustomizeSheet(activity), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }
}
