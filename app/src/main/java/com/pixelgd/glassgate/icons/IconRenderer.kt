package com.pixelgd.glassgate.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.glass.Squircle
import com.pixelgd.glassgate.util.withAlpha

/** The four iOS 26 icon appearances. */
enum class IconStyle { DEFAULT, DARK, CLEAR, TINTED }

/**
 * Turns any Android app icon into an iOS-style squircle bitmap in the chosen [IconStyle].
 * Results are cached; call [clear] when the style, tint or icon pack changes.
 * Rendering is CPU-only, so it is safe to call from a background thread.
 */
class IconRenderer(private val context: Context) {

    private val density = context.resources.displayMetrics.densityDpi
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    var style: IconStyle = IconStyle.DEFAULT
        set(value) { if (field != value) { field = value; clear() } }

    /** Wallpaper accent, used by TINTED. */
    var tint: Int = Color.rgb(140, 110, 210)
        set(value) { if (field != value) { field = value; if (style == IconStyle.TINTED) clear() } }

    fun clear() = cache.evictAll()

    fun cached(app: AppEntry, sizePx: Int): Bitmap? = cache.get(cacheKey(app, sizePx))

    fun render(app: AppEntry, sizePx: Int): Bitmap {
        val key = cacheKey(app, sizePx)
        cache.get(key)?.let { return it }
        val bmp = render(app.loadRawIcon(density), sizePx, style, tint)
        cache.put(key, bmp)
        return bmp
    }

    private fun cacheKey(app: AppEntry, sizePx: Int) = "${app.key}|$sizePx|$style|$tint"

    companion object {
        /** Renders [icon] into a [size]px squircle in [style]. */
        fun render(icon: Drawable, size: Int, style: IconStyle, tint: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val s = size.toFloat()
            val mask = Squircle.icon(s, s)
            c.save()
            c.clipPath(mask)

            val adaptive = icon as? AdaptiveIconDrawable
            when (style) {
                IconStyle.DEFAULT -> {
                    if (adaptive != null) {
                        drawLayer(c, adaptive.background, size)
                        drawLayer(c, adaptive.foreground, size)
                    } else {
                        drawLegacy(c, icon, size, Color.WHITE)
                    }
                    topHighlight(c, s, 0.16f)
                }
                IconStyle.DARK -> {
                    c.drawPaint(Paint().apply {
                        shader = LinearGradient(0f, 0f, 0f, s, Color.rgb(44, 44, 48), Color.rgb(18, 18, 20), Shader.TileMode.CLAMP)
                    })
                    if (adaptive != null) {
                        drawLayer(c, adaptive.foreground, size)
                    } else {
                        drawLegacy(c, icon, size, Color.TRANSPARENT)
                    }
                    topHighlight(c, s, 0.08f)
                }
                IconStyle.TINTED -> {
                    c.drawPaint(Paint().apply {
                        shader = LinearGradient(0f, 0f, 0f, s, darken(tint, 0.55f), darken(tint, 0.35f), Shader.TileMode.CLAMP)
                    })
                    drawGlyph(c, icon, adaptive, size, lighten(tint, 0.75f))
                    topHighlight(c, s, 0.10f)
                }
                IconStyle.CLEAR -> {
                    c.drawColor(Color.WHITE.withAlpha(0.18f))
                    drawGlyph(c, icon, adaptive, size, Color.WHITE.withAlpha(0.92f))
                    topHighlight(c, s, 0.22f)
                }
            }
            c.restore()
            // Hairline edge so light icons don't melt into light wallpapers.
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = s / 90f
                shader = LinearGradient(0f, 0f, s, s,
                    Color.WHITE.withAlpha(if (style == IconStyle.CLEAR) 0.65f else 0.28f),
                    Color.WHITE.withAlpha(0.05f), Shader.TileMode.CLAMP)
            }
            val inset = Squircle.icon(s - strokeInset(s) * 2, s - strokeInset(s) * 2, Path(), left = strokeInset(s), top = strokeInset(s))
            c.drawPath(inset, rim)
            return bmp
        }

        private fun strokeInset(s: Float) = s / 180f

        /** Adaptive layers are 108dp with the visible 72dp in the middle: overscan by 25%. */
        private fun drawLayer(c: Canvas, d: Drawable?, size: Int) {
            d ?: return
            val pad = size / 4
            d.setBounds(-pad, -pad, size + pad, size + pad)
            d.draw(c)
        }

        /** Old square/round icons: shrink onto a plate so the squircle doesn't crop them. */
        private fun drawLegacy(c: Canvas, d: Drawable, size: Int, plate: Int) {
            if (plate != Color.TRANSPARENT) c.drawColor(plate)
            val inset = (size * 0.14f).toInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(c)
        }

        /** Single-colour glyph for TINTED/CLEAR: monochrome layer when the app ships one. */
        private fun drawGlyph(c: Canvas, icon: Drawable, adaptive: AdaptiveIconDrawable?, size: Int, color: Int) {
            val mono = adaptive?.monochrome
            val glyph = mono ?: adaptive?.foreground
            if (glyph != null) {
                val d = glyph.mutate()
                d.colorFilter = if (mono != null) {
                    PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                } else luminanceToColor(color)
                drawLayer(c, d, size)
                d.colorFilter = null
            } else {
                val d = icon.mutate()
                d.colorFilter = luminanceToColor(color)
                drawLegacy(c, d, size, Color.TRANSPARENT)
                d.colorFilter = null
            }
        }

        /** Keeps the glyph's shape and shading as alpha, recoloured to [color]. */
        private fun luminanceToColor(color: Int): ColorMatrixColorFilter {
            val r = Color.red(color).toFloat()
            val g = Color.green(color).toFloat()
            val b = Color.blue(color).toFloat()
            val a = Color.alpha(color) / 255f
            return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, r,
                0f, 0f, 0f, 0f, g,
                0f, 0f, 0f, 0f, b,
                0.30f * a, 0.59f * a, 0.11f * a, 0.35f * a, -40f * a,
            )))
        }

        private fun topHighlight(c: Canvas, s: Float, strength: Float) {
            c.drawRect(RectF(0f, 0f, s, s * 0.55f), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, s * 0.55f, Color.WHITE.withAlpha(strength), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
        }

        private fun darken(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
        private fun lighten(c: Int, f: Float) = Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
            (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
            (Color.blue(c) + (255 - Color.blue(c)) * f).toInt(),
        )
    }
}
