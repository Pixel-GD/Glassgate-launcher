package com.pixelgd.glassgate.library

import android.content.Context
import com.pixelgd.glassgate.apps.AppEntry
import kotlin.math.pow

/**
 * Remembers how often and how recently each app was opened from the launcher, as a decaying
 * "frecency" score. Drives the App Library's Suggestions tile and the order of icons inside each
 * category; Spotlight can use it for its suggestions row too. Main thread only.
 */
class AppUsage private constructor(context: Context) {

    private val sp = context.getSharedPreferences("app_usage", Context.MODE_PRIVATE)
    private val scores = HashMap<String, Pair<Float, Long>>()

    init {
        for ((key, v) in sp.all) {
            val s = (v as? String)?.split('|') ?: continue
            val score = s.getOrNull(0)?.toFloatOrNull() ?: continue
            val at = s.getOrNull(1)?.toLongOrNull() ?: continue
            scores[key] = score to at
        }
    }

    /** Call on every launch, wherever it came from. */
    fun record(app: AppEntry, now: Long = System.currentTimeMillis()) {
        val s = score(app.key, now) + 1f
        scores[app.key] = s to now
        sp.edit().putString(app.key, "$s|$now").apply()
    }

    /** 0 for never opened; each launch adds 1, halving every [HALF_LIFE_MS]. */
    fun score(key: String, now: Long = System.currentTimeMillis()): Float {
        val (s, at) = scores[key] ?: return 0f
        val age = (now - at).coerceAtLeast(0L)
        return s * 0.5.pow(age / HALF_LIFE_MS.toDouble()).toFloat()
    }

    /** Most-used first, only apps that have been opened. */
    fun top(apps: List<AppEntry>, n: Int, now: Long = System.currentTimeMillis()): List<AppEntry> =
        apps.map { it to score(it.key, now) }
            .filter { it.second > 0.05f }
            .sortedByDescending { it.second }
            .take(n)
            .map { it.first }

    companion object {
        private const val HALF_LIFE_MS = 3L * 24 * 60 * 60 * 1000

        @Volatile private var instance: AppUsage? = null

        fun of(context: Context): AppUsage =
            instance ?: synchronized(this) {
                instance ?: AppUsage(context.applicationContext).also { instance = it }
            }
    }
}
