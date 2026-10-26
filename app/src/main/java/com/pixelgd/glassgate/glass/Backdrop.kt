package com.pixelgd.glassgate.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.util.Log
import com.pixelgd.glassgate.util.isDarkMode
import java.io.File

/**
 * What sits behind the home screen. Glass can only refract what Glassgate itself draws,
 * because Android 14+ no longer lets apps read the system wallpaper. So the launcher
 * draws its own wallpaper (the Celosia shader or a photo the user picked) and every
 * glass surface samples the same [Backdrop] at its own screen position.
 */
sealed class Backdrop {
    /** Draws the whole backdrop for a [width] x [height] screen at canvas origin. */
    abstract fun draw(canvas: Canvas, width: Int, height: Int)

    /** A representative colour, used to tint glass and Tinted icons. */
    abstract val accent: Int

    /** Glassgate's live Celosia artwork. */
    class Celosia(private val dark: Boolean) : Backdrop() {
        private val shader: RuntimeShader? = try {
            RuntimeShader(Shaders.CELOSIA)
        } catch (e: Exception) {
            Log.e(TAG, "Celosia shader failed to compile", e)
            null
        }
        private val paint = Paint()
        private val fallback = Paint()
        var phase = 0f

        override val accent: Int = if (dark) Color.rgb(46, 42, 107) else Color.rgb(208, 160, 170)

        override fun draw(canvas: Canvas, width: Int, height: Int) {
            val s = shader
            if (s != null && canvas.isHardwareAccelerated) {
                s.setFloatUniform("size", width.toFloat(), height.toFloat())
                s.setFloatUniform("dark", if (dark) 1f else 0f)
                s.setFloatUniform("phase", phase)
                paint.shader = s
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            } else {
                fallback.shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    if (dark) Color.rgb(40, 42, 96) else Color.rgb(247, 222, 173),
                    if (dark) Color.rgb(13, 10, 36) else Color.rgb(133, 102, 204),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallback)
            }
        }
    }

    /** A user-picked image, centre-cropped to the screen. */
    class Photo(val bitmap: Bitmap) : Backdrop() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()
        override val accent: Int = averageColor(bitmap)

        override fun draw(canvas: Canvas, width: Int, height: Int) {
            val scale = maxOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
            matrix.setScale(scale, scale)
            matrix.postTranslate((width - bitmap.width * scale) / 2f, (height - bitmap.height * scale) / 2f)
            canvas.drawBitmap(bitmap, matrix, paint)
        }
    }

    companion object {
        private const val TAG = "Backdrop"

        fun averageColor(b: Bitmap): Int {
            val tiny = Bitmap.createScaledBitmap(b, 1, 1, true)
            return tiny.getPixel(0, 0)
        }
    }
}

/**
 * Owns the current [Backdrop] and notifies views when it changes.
 * Stored choice: "celosia" (default), "photo" (file in app storage) or "system"
 * (show the system wallpaper through; glass then uses a plain frosted tint).
 */
class WallpaperSource(private val context: Context) {

    fun interface Listener {
        fun onBackdropChanged(backdrop: Backdrop?)
    }

    private val prefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
    private val listeners = LinkedHashSet<Listener>()
    private val photoFile get() = File(context.filesDir, "wallpaper.jpg")

    /** null means the system wallpaper shows through and glass falls back to tint only. */
    var current: Backdrop? = null
        private set

    var mode: String
        get() = prefs.getString("mode", MODE_CELOSIA) ?: MODE_CELOSIA
        set(value) {
            prefs.edit().putString("mode", value).apply()
            refresh()
        }

    init {
        refresh()
    }

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /** Saves a photo the user picked and switches to it. */
    fun setPhoto(bitmap: Bitmap) {
        photoFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        mode = MODE_PHOTO
    }

    /** Rebuilds the backdrop, e.g. after a light/dark switch. */
    fun refresh() {
        current = when (mode) {
            MODE_PHOTO -> BitmapFactory.decodeFile(photoFile.path)?.let { Backdrop.Photo(it) }
                ?: Backdrop.Celosia(context.isDarkMode)
            MODE_SYSTEM -> null
            else -> Backdrop.Celosia(context.isDarkMode)
        }
        listeners.toList().forEach { it.onBackdropChanged(current) }
    }

    companion object {
        const val MODE_CELOSIA = "celosia"
        const val MODE_PHOTO = "photo"
        const val MODE_SYSTEM = "system"
    }
}
