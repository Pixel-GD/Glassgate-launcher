package com.pixelgd.glassgate.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.LinearLayout
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.glass.Backdrop
import com.pixelgd.glassgate.glass.Squircle
import com.pixelgd.glassgate.icons.IconRenderer
import com.pixelgd.glassgate.icons.IconStyle
import com.pixelgd.glassgate.util.Main
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.sp
import com.pixelgd.glassgate.util.tick
import java.util.concurrent.Executors

/** Draws [backdrop] into [rect], or a neutral gradient for the system wallpaper. */
internal fun drawBackdrop(canvas: Canvas, backdrop: Backdrop?, rect: RectF, path: Path, radius: Float) {
    Squircle.roundRect(rect, radius, path)
    canvas.save()
    canvas.clipPath(path)
    canvas.translate(rect.left, rect.top)
    if (backdrop != null) {
        backdrop.draw(canvas, rect.width().toInt(), rect.height().toInt())
    } else {
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, rect.height(), Color.rgb(120, 128, 150), Color.rgb(52, 56, 72), Shader.TileMode.CLAMP)
        })
    }
    canvas.restore()
}

/**
 * The four iOS 26 icon looks side by side, each a sample icon over the current wallpaper,
 * like the Customize sheet on an iPhone home screen. Tap one to choose it.
 */
@SuppressLint("ViewConstructor")
class IconStylePicker(context: Context, private val colors: IosColors) : LinearLayout(context) {

    var onSelect: ((IconStyle) -> Unit)? = null
    private val launcher = Launcher.of(context)
    private val tiles = IconStyle.entries.map { Tile(it) }

    init {
        orientation = HORIZONTAL
        val pad = context.dpi(12f)
        setPadding(pad, context.dpi(16f), pad, context.dpi(12f))
        for (t in tiles) addView(t, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        refresh()
    }

    fun select(style: IconStyle) = tiles.forEach { it.setSelected(it.style == style); it.invalidate() }

    private fun sampleIcon(): Drawable {
        val l = launcher.layout.layout
        val app = l.dock.firstNotNullOfOrNull { launcher.apps[it] } ?: launcher.apps.all.firstOrNull()
        val density = resources.displayMetrics.densityDpi
        return app?.loadRawIcon(density) ?: context.packageManager.getApplicationIcon(context.packageName)
    }

    /** Re-renders the samples, e.g. after the wallpaper (and so the tint) changed. */
    fun refresh() {
        val size = context.dpi(52f)
        val tint = launcher.icons.tint
        val dark = launcher.prefs.iconsDark()
        WORKER.execute {
            val icon = runCatching { sampleIcon() }.getOrNull() ?: return@execute
            val bitmaps = IconStyle.entries.associateWith {
                runCatching { IconRenderer.render(icon.constantState?.newDrawable()?.mutate() ?: icon, size, it, tint, dark) }.getOrNull()
            }
            Main.post { tiles.forEach { t -> t.icon = bitmaps[t.style]; t.invalidate() } }
        }
    }

    private inner class Tile(val style: IconStyle) : View(context) {
        var icon: Bitmap? = null
        private val rect = RectF()
        private val path = Path()
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = context.dp(2.5f)
            color = colors.accent
        }
        private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = context.sp(13f)
            textAlign = Paint.Align.CENTER
        }
        private val swatchH = context.dp(92f)
        private val gap = context.dp(8f)

        init {
            isClickable = true
            contentDescription = label()
            setOnClickListener {
                if (!isSelected) {
                    tick()
                    select(style)
                    onSelect?.invoke(style)
                }
            }
        }

        fun label() = style.name.lowercase().replaceFirstChar { it.uppercase() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                Math.round(swatchH + gap + text.fontSpacing + context.dp(4f)))
        }

        override fun onDraw(canvas: Canvas) {
            val inset = context.dp(5f)
            rect.set(inset, inset, width - inset, swatchH - inset)
            drawBackdrop(canvas, launcher.wallpaper.current, rect, path, context.dp(14f))
            if (style == IconStyle.DARK || launcher.prefs.iconsDark()) {
                // iOS dims the wallpaper in Dark mode too.
                canvas.save()
                canvas.clipPath(path)
                canvas.drawColor(Color.argb(90, 0, 0, 0))
                canvas.restore()
            }
            icon?.let { canvas.drawBitmap(it, (width - it.width) / 2f, (swatchH - it.height) / 2f, iconPaint) }
            if (isSelected) {
                rect.set(ring.strokeWidth / 2, ring.strokeWidth / 2, width - ring.strokeWidth / 2, swatchH - ring.strokeWidth / 2)
                Squircle.roundRect(rect, context.dp(18f), path)
                canvas.drawPath(path, ring)
            }
            text.color = if (isSelected) colors.accent else colors.label
            text.typeface = if (isSelected) IosList.BOLD else Typeface.DEFAULT
            canvas.drawText(label(), width / 2f, swatchH + gap - text.ascent(), text)
        }
    }

    companion object {
        private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-preview") }
    }
}

/** A small phone-shaped wallpaper thumbnail for the Wallpaper rows. */
@SuppressLint("ViewConstructor")
class WallpaperSwatch(context: Context, var backdrop: Backdrop?) : View(context) {
    private val rect = RectF()
    private val path = Path()
    private val w = context.dpi(30f)
    private val h = context.dpi(50f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(w, h)

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        drawBackdrop(canvas, backdrop, rect, path, context.dp(7f))
    }
}
