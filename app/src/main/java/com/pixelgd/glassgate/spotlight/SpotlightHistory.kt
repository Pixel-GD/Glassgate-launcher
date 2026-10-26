package com.pixelgd.glassgate.spotlight

import android.content.Context
import com.pixelgd.glassgate.apps.AppEntry
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Remembers which apps get opened and when, to fill Spotlight's "Siri Suggestions" row.
 * An app scores higher the more often and the more recently it was opened, and higher
 * again when it tends to be opened around this time of day, which is roughly how iOS
 * picks its suggestions. Everything stays on the device in one SharedPreferences string.
 */
class SpotlightHistory private constructor(context: Context) {

    private class Launch(val key: String, val time: Long)

    private val prefs = context.getSharedPreferences("spotlight", Context.MODE_PRIVATE)
    private val launches = ArrayDeque<Launch>()

    init {
        prefs.getString(KEY_LAUNCHES, null)?.split('\n')?.forEach { line ->
            val tab = line.lastIndexOf('\t')
            if (tab > 0) line.substring(tab + 1).toLongOrNull()?.let { launches += Launch(line.substring(0, tab), it) }
        }
    }

    /** Called for every app launch, wherever it came from. */
    fun record(app: AppEntry, now: Long = System.currentTimeMillis()) {
        launches += Launch(app.key, now)
        while (launches.size > MAX_LAUNCHES) launches.removeFirst()
        prefs.edit().putString(KEY_LAUNCHES, launches.joinToString("\n") { "${it.key}\t${it.time}" }).apply()
    }

    /** Score per app key; higher is a better suggestion right now. */
    fun scores(now: Long = System.currentTimeMillis()): Map<String, Double> {
        val nowHour = hourOfDay(now)
        val out = HashMap<String, Double>()
        for (l in launches) {
            val ageDays = (now - l.time).coerceAtLeast(0) / DAY_MS
            var s = exp(-ageDays * LN2 / HALF_LIFE_DAYS)
            val hourDiff = abs(hourOfDay(l.time) - nowHour).let { min(it, 24f - it) }
            if (hourDiff <= 1.5f) s *= 2.0
            out[l.key] = (out[l.key] ?: 0.0) + s
        }
        return out
    }

    /**
     * Up to [count] suggestions: the best-scoring apps first, topped up with recently
     * installed apps and then the rest A to Z, so the row is full on a fresh install.
     */
    fun suggestions(apps: List<AppEntry>, count: Int): List<AppEntry> {
        val scores = scores()
        val out = LinkedHashSet<AppEntry>()
        apps.filter { scores.containsKey(it.key) }
            .sortedByDescending { scores[it.key] }
            .forEach { if (out.size < count) out += it }
        if (out.size < count) {
            apps.filter { !it.isSystem }.sortedByDescending { it.firstInstallTime }
                .take(count).forEach { if (out.size < count) out += it }
        }
        apps.forEach { if (out.size < count) out += it }
        return out.toList()
    }

    private fun hourOfDay(time: Long): Float {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f
    }

    companion object {
        private const val KEY_LAUNCHES = "launches"
        private const val MAX_LAUNCHES = 400
        private const val DAY_MS = 86_400_000.0
        private const val HALF_LIFE_DAYS = 4.0
        private const val LN2 = 0.6931471805599453

        @Volatile private var instance: SpotlightHistory? = null

        fun of(context: Context): SpotlightHistory =
            instance ?: synchronized(this) {
                instance ?: SpotlightHistory(context.applicationContext).also { instance = it }
            }
    }
}
