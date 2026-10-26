package com.pixelgd.glassgate.util

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View

/** Units and small helpers shared by every module. */
fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.dpi(value: Float): Int = Math.round(dp(value))

fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

val Context.isDarkMode: Boolean
    get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

fun Int.withAlpha(alpha: Float): Int =
    (Math.round(alpha.coerceIn(0f, 1f) * 255) shl 24) or (this and 0x00FFFFFF)

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun View.tick() {
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

fun View.thud() {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

object Main {
    private val handler = Handler(Looper.getMainLooper())
    fun post(block: () -> Unit) { handler.post(block) }
    fun postDelayed(ms: Long, block: () -> Unit) { handler.postDelayed(block, ms) }
}
