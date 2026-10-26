package com.pixelgd.glassgate.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.withAlpha

/** Tunables for one kind of glass surface. Radii and distances are in dp. */
data class GlassStyle(
    val cornerRadius: Float,
    val blur: Float = 28f,
    val refraction: Float = 14f,
    val bezel: Float = 18f,
    val lightTint: Float = 0.14f,
    val darkTint: Float = 0.20f,
    val sheen: Float = 0.05f,
) {
    companion object {
        val DOCK = GlassStyle(cornerRadius = 34f)
        val PILL = GlassStyle(cornerRadius = 999f, refraction = 8f, bezel = 12f, blur = 20f)
        val FOLDER_ICON = GlassStyle(cornerRadius = 16f, refraction = 6f, bezel = 8f, blur = 18f)
        val FOLDER_OPEN = GlassStyle(cornerRadius = 40f, blur = 36f, lightTint = 0.20f, darkTint = 0.36f)
        val SHEET = GlassStyle(cornerRadius = 0f, blur = 48f, refraction = 0f, bezel = 1f, lightTint = 0.30f, darkTint = 0.45f, sheen = 0f)
        val MENU = GlassStyle(cornerRadius = 22f, blur = 40f, refraction = 6f, bezel = 10f, lightTint = 0.45f, darkTint = 0.50f)
        val BUTTON = GlassStyle(cornerRadius = 999f, refraction = 5f, bezel = 8f, blur = 16f)
        val LIBRARY_TILE = GlassStyle(cornerRadius = 30f, refraction = 10f, bezel = 14f, blur = 24f)
    }
}

/**
 * iOS 26 "Liquid Glass" as a Drawable: the launcher's backdrop, blurred, bent at the rim by an
 * AGSL lens, tinted, with a tilt-driven specular edge. Set it as any view's background:
 *
 *     view.background = GlassDrawable(view, GlassStyle.DOCK)
 *
 * The drawable samples the backdrop at the host view's position in the window, so a host that
 * moves without redrawing (e.g. a parent's translation) must call invalidate() as it moves.
 * Falls back to a frosted tint when the system wallpaper is in use or the shader fails.
 */
class GlassDrawable(private val host: View, var style: GlassStyle) : Drawable() {

    private val launcher = Launcher.of(host.context)
    private val density = host.context.dp(1f)
    private val node = RenderNode("glass")
    private val path = Path()
    private val rect = RectF()
    private val loc = IntArray(2)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shader: RuntimeShader? = glassShaderOrNull()

    /** 0..1 multiplier for the whole effect, for fade-in/out animations. */
    var progress = 1f
        set(value) { field = value; invalidateSelf() }

    private val tiltListener = Tilt.Listener { _, _ -> invalidateSelf() }
    private val backdropListener = WallpaperSource.Listener { invalidateSelf() }

    init {
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                launcher.tilt.addListener(tiltListener)
                launcher.wallpaper.addListener(backdropListener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                launcher.tilt.removeListener(tiltListener)
                launcher.wallpaper.removeListener(backdropListener)
            }
        })
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width()
        val h = b.height()
        val radius = minOf(style.cornerRadius * density, minOf(w, h) / 2f)
        val dark = host.context.isDarkMode
        // Settings > Liquid Glass: 0.5 is the stock look, lower is clearer, higher more frosted.
        val intensity = launcher.prefs.glassIntensity
        val tintAlpha = ((if (dark) style.darkTint else style.lightTint) * (0.3f + intensity * 1.4f)).coerceAtMost(0.85f)
        val tintColor = if (dark) Color.BLACK else Color.WHITE
        val backdrop = launcher.wallpaper.current
        val s = shader

        if (backdrop != null && s != null && canvas.isHardwareAccelerated && progress > 0f) {
            host.getLocationInWindow(loc)
            val root = host.rootView
            node.setPosition(0, 0, w, h)
            val rc = node.beginRecording(w, h)
            rc.translate(-(loc[0] + b.left).toFloat(), -(loc[1] + b.top).toFloat())
            backdrop.draw(rc, root.width, root.height)
            node.endRecording()

            val tilt = launcher.tilt
            s.setFloatUniform("size", w.toFloat(), h.toFloat())
            s.setFloatUniform("radius", radius)
            s.setFloatUniform("bezel", style.bezel * density)
            s.setFloatUniform("refraction", style.refraction * density * progress)
            s.setFloatUniform("light", -0.55f + tilt.x * 0.7f, -0.85f + tilt.y * 0.5f)
            s.setFloatUniform("tint", Color.red(tintColor) / 255f, Color.green(tintColor) / 255f,
                Color.blue(tintColor) / 255f, tintAlpha)
            s.setFloatUniform("sheen", style.sheen * (if (dark) 0.5f else 1f))
            val blurPx = style.blur * density * progress * (0.6f + intensity * 0.8f)
            val lens = RenderEffect.createRuntimeShaderEffect(s, "content")
            node.setRenderEffect(
                if (blurPx > 0.5f) {
                    RenderEffect.createChainEffect(lens,
                        RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP))
                } else lens,
            )
            node.alpha = progress
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.drawRenderNode(node)
            canvas.restore()
            drawRim(canvas, radius, 0.35f)
        } else {
            // Frosted fallback: no refraction, just tint, sheen and rim.
            rect.set(b)
            Squircle.roundRect(rect, radius, path)
            fillPaint.color = tintColor.withAlpha((tintAlpha + 0.12f) * progress)
            canvas.drawPath(path, fillPaint)
            sheenPaint.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                Color.WHITE.withAlpha(0.10f * progress), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawPath(path, sheenPaint)
            drawRim(canvas, radius, 1f)
        }
    }

    /** The crisp 1px highlight on the lit edge, fading around the shape. */
    private fun drawRim(canvas: Canvas, radius: Float, strength: Float) {
        val b = bounds
        rect.set(b)
        rect.inset(density * 0.5f, density * 0.5f)
        Squircle.roundRect(rect, radius, path)
        val tilt = launcher.tilt
        val shift = tilt.x * b.width() * 0.3f
        rimPaint.strokeWidth = density
        rimPaint.shader = LinearGradient(
            b.left + shift, b.top.toFloat(), b.right + shift, b.bottom.toFloat(),
            intArrayOf(
                Color.WHITE.withAlpha(0.70f * strength * progress),
                Color.WHITE.withAlpha(0.12f * strength * progress),
                Color.WHITE.withAlpha(0.35f * strength * progress),
            ),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, rimPaint)
    }

    override fun setAlpha(alpha: Int) { progress = alpha / 255f }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private var shaderBroken = false

        private fun glassShaderOrNull(): RuntimeShader? {
            if (shaderBroken) return null
            return try {
                RuntimeShader(Shaders.GLASS)
            } catch (e: Exception) {
                Log.e("GlassDrawable", "Glass shader failed to compile; using frosted fallback", e)
                shaderBroken = true
                null
            }
        }
    }
}
