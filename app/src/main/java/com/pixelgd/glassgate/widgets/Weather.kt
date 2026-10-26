package com.pixelgd.glassgate.widgets

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.text.format.DateFormat
import android.util.Log
import com.pixelgd.glassgate.util.Main
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Weather conditions, from WMO weather codes. */
enum class Condition(val label: String) {
    CLEAR("Clear"), MOSTLY_CLEAR("Mostly Clear"), PARTLY_CLOUDY("Partly Cloudy"), CLOUDY("Cloudy"),
    FOG("Foggy"), DRIZZLE("Drizzle"), RAIN("Rain"), HEAVY_RAIN("Heavy Rain"), SNOW("Snow"),
    SLEET("Sleet"), THUNDER("Thunderstorms");

    companion object {
        fun ofWmo(code: Int): Condition = when (code) {
            0 -> CLEAR
            1 -> MOSTLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55 -> DRIZZLE
            56, 57, 66, 67 -> SLEET
            61, 63, 80, 81 -> RAIN
            65, 82 -> HEAVY_RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> THUNDER
            else -> CLOUDY
        }
    }
}

data class Weather(
    val place: String,
    val temp: Int,
    val condition: Condition,
    val isDay: Boolean,
    val high: Int,
    val low: Int,
    val hours: List<Hour>,
    val days: List<Day>,
    val fetchedAt: Long,
) {
    data class Hour(val label: String, val condition: Condition, val isDay: Boolean, val temp: Int)
    data class Day(val label: String, val condition: Condition, val low: Int, val high: Int)
}

/**
 * Current weather for where the phone is, from Open-Meteo (free, no key). Coarse location
 * only; the result is cached for half an hour and shared by every Weather widget.
 */
class WeatherRepository private constructor(private val app: Context) {

    fun interface Listener {
        fun onWeather(weather: Weather?, state: State)
    }

    enum class State { OK, NEEDS_PERMISSION, NO_LOCATION, ERROR, LOADING }

    private val prefs = app.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val listeners = LinkedHashSet<Listener>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "weather") }
    private var loading = false

    var weather: Weather? = loadCached()
        private set
    var state = if (weather != null) State.OK else State.LOADING
        private set

    val hasPermission: Boolean
        get() = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /** Fetches new weather if the cached one is older than [maxAgeMs]. */
    fun refreshIfStale(maxAgeMs: Long = 30 * 60_000L) {
        val w = weather
        if (!hasPermission) return publish(w, State.NEEDS_PERMISSION)
        if (loading) return
        if (w != null && System.currentTimeMillis() - w.fetchedAt < maxAgeMs && state == State.OK) return
        loading = true
        if (w == null) publish(null, State.LOADING)
        locate { loc ->
            if (loc == null) {
                loading = false
                publish(weather, if (weather != null) State.OK else State.NO_LOCATION)
                return@locate
            }
            io.execute {
                val result = runCatching { fetch(loc) }
                    .onFailure { Log.w(TAG, "Weather fetch failed", it) }
                    .getOrNull()
                Main.post {
                    loading = false
                    if (result != null) {
                        weather = result
                        publish(result, State.OK)
                    } else publish(weather, if (weather != null) State.OK else State.ERROR)
                }
            }
        }
    }

    private fun publish(w: Weather?, s: State) {
        state = s
        listeners.toList().forEach { it.onWeather(w, s) }
    }

    @Suppress("MissingPermission")
    private fun locate(done: (Location?) -> Unit) {
        val lm = app.getSystemService(LocationManager::class.java) ?: return done(null)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) || it == LocationManager.PASSIVE_PROVIDER }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 3 * 3600_000L) return done(last)
        val live = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
        if (live == null) return done(last)
        try {
            lm.getCurrentLocation(live, CancellationSignal(), app.mainExecutor) { loc -> done(loc ?: last) }
        } catch (e: Exception) {
            done(last)
        }
    }

    /** Runs on the io thread. */
    private fun fetch(loc: Location): Weather {
        val fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(Locale.US, loc.latitude, loc.longitude) +
                "&current=temperature_2m,weather_code,is_day" +
                "&hourly=temperature_2m,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=6" +
                if (fahrenheit) "&temperature_unit=fahrenheit" else "",
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val place = placeName(loc)
        val now = System.currentTimeMillis()
        prefs.edit().putString("json", body).putString("place", place).putLong("at", now).apply()
        return parse(JSONObject(body), place, now)
    }

    @Suppress("DEPRECATION")
    private fun placeName(loc: Location): String {
        if (!Geocoder.isPresent()) return prefs.getString("place", null) ?: "My Location"
        return runCatching {
            // The blocking call is fine here: we're already on the io thread.
            Geocoder(app).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull() ?: prefs.getString("place", null) ?: "My Location"
    }

    private fun loadCached(): Weather? {
        val json = prefs.getString("json", null) ?: return null
        return runCatching {
            parse(JSONObject(json), prefs.getString("place", null) ?: "My Location", prefs.getLong("at", 0))
        }.getOrNull()
    }

    private fun parse(o: JSONObject, place: String, at: Long): Weather {
        val cur = o.getJSONObject("current")
        val hourly = o.getJSONObject("hourly")
        val daily = o.getJSONObject("daily")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        val isoDay = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val hourFmt = SimpleDateFormat(if (DateFormat.is24HourFormat(app)) "HH" else "h a", Locale.getDefault())
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())

        // Times are local to the forecast spot; we only use them as labels and to find "now".
        val curHour = cur.getString("time").take(13)
        val hTimes = hourly.getJSONArray("time")
        val hTemps = hourly.getJSONArray("temperature_2m")
        val hCodes = hourly.getJSONArray("weather_code")
        val hDay = hourly.getJSONArray("is_day")
        var start = 0
        for (i in 0 until hTimes.length()) if (hTimes.getString(i).take(13) == curHour) { start = i; break }
        val hours = (start until minOf(start + 6, hTimes.length())).map { i ->
            Weather.Hour(
                if (i == start) "Now" else hourFmt.format(iso.parse(hTimes.getString(i))!!),
                Condition.ofWmo(hCodes.getInt(i)), hDay.optInt(i, 1) == 1, hTemps.getDouble(i).roundToInt(),
            )
        }
        val dTimes = daily.getJSONArray("time")
        val dCodes = daily.getJSONArray("weather_code")
        val dMax = daily.getJSONArray("temperature_2m_max")
        val dMin = daily.getJSONArray("temperature_2m_min")
        val days = (0 until dTimes.length()).map { i ->
            Weather.Day(
                if (i == 0) "Today" else dayFmt.format(isoDay.parse(dTimes.getString(i))!!),
                Condition.ofWmo(dCodes.getInt(i)), dMin.getDouble(i).roundToInt(), dMax.getDouble(i).roundToInt(),
            )
        }
        return Weather(
            place = place,
            temp = cur.getDouble("temperature_2m").roundToInt(),
            condition = Condition.ofWmo(cur.getInt("weather_code")),
            isDay = cur.optInt("is_day", 1) == 1,
            high = days.firstOrNull()?.high ?: 0,
            low = days.firstOrNull()?.low ?: 0,
            hours = hours,
            days = days,
            fetchedAt = at,
        )
    }

    companion object {
        private const val TAG = "Weather"
        private var instance: WeatherRepository? = null

        fun of(context: Context): WeatherRepository =
            instance ?: WeatherRepository(context.applicationContext).also { instance = it }
    }
}

/** Multicolour weather symbols in the spirit of SF Symbols, drawn with paths. */
object WeatherGlyph {
    private val sun = Color.rgb(255, 204, 0)
    private val drop = Color.rgb(90, 200, 250)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    /** Draws [c] centred at ([cx], [cy]) in a box [s] px across. */
    fun draw(canvas: Canvas, c: Condition, isDay: Boolean, cx: Float, cy: Float, s: Float) {
        when (c) {
            Condition.CLEAR -> if (isDay) sun(canvas, cx, cy, s * 0.5f) else moon(canvas, cx, cy, s * 0.42f)
            Condition.MOSTLY_CLEAR, Condition.PARTLY_CLOUDY -> {
                if (isDay) sun(canvas, cx + s * 0.14f, cy - s * 0.14f, s * 0.34f) else moon(canvas, cx + s * 0.14f, cy - s * 0.16f, s * 0.28f)
                cloud(canvas, cx - s * 0.06f, cy + s * 0.1f, s * 0.8f)
            }
            Condition.CLOUDY -> cloud(canvas, cx, cy, s)
            Condition.FOG -> {
                cloud(canvas, cx, cy - s * 0.12f, s * 0.85f)
                lines(canvas, cx, cy + s * 0.3f, s)
            }
            Condition.DRIZZLE, Condition.RAIN, Condition.HEAVY_RAIN -> {
                cloud(canvas, cx, cy - s * 0.14f, s * 0.9f)
                rain(canvas, cx, cy + s * 0.28f, s, if (c == Condition.DRIZZLE) 2 else 3, c == Condition.HEAVY_RAIN)
            }
            Condition.SNOW, Condition.SLEET -> {
                cloud(canvas, cx, cy - s * 0.14f, s * 0.9f)
                snow(canvas, cx, cy + s * 0.3f, s)
            }
            Condition.THUNDER -> {
                cloud(canvas, cx, cy - s * 0.14f, s * 0.9f)
                bolt(canvas, cx, cy + s * 0.26f, s * 0.36f)
            }
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = sun
        c.drawCircle(cx, cy, r * 0.5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.12f
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            c.drawLine(cx + dx * r * 0.7f, cy + dy * r * 0.7f, cx + dx * r * 0.92f, cy + dy * r * 0.92f, paint)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        path.reset()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        val cut = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW) }
        path.op(cut, Path.Op.DIFFERENCE)
        c.drawPath(path, paint)
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        path.reset()
        val base = cy + s * 0.18f
        rect.set(cx - s * 0.42f, base - s * 0.26f, cx + s * 0.42f, base)
        path.addRoundRect(rect, s * 0.13f, s * 0.13f, Path.Direction.CW)
        path.addCircle(cx - s * 0.14f, base - s * 0.24f, s * 0.18f, Path.Direction.CW)
        path.addCircle(cx + s * 0.1f, base - s * 0.3f, s * 0.24f, Path.Direction.CW)
        c.drawPath(path, paint)
    }

    private fun rain(c: Canvas, cx: Float, top: Float, s: Float, n: Int, heavy: Boolean) {
        paint.style = Paint.Style.STROKE
        paint.color = drop
        paint.strokeWidth = s * (if (heavy) 0.08f else 0.06f)
        paint.strokeCap = Paint.Cap.ROUND
        val gap = s * 0.2f
        val start = cx - gap * (n - 1) / 2f
        for (i in 0 until n) {
            val x = start + gap * i
            c.drawLine(x + s * 0.04f, top, x - s * 0.04f, top + s * 0.16f, paint)
        }
    }

    private fun snow(c: Canvas, cx: Float, top: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        for (i in -1..1) c.drawCircle(cx + i * s * 0.2f, top + s * 0.08f + (if (i == 0) s * 0.06f else 0f), s * 0.05f, paint)
    }

    private fun lines(c: Canvas, cx: Float, top: Float, s: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = s * 0.06f
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - s * 0.36f, top, cx + s * 0.3f, top, paint)
        c.drawLine(cx - s * 0.28f, top + s * 0.13f, cx + s * 0.38f, top + s * 0.13f, paint)
    }

    private fun bolt(c: Canvas, cx: Float, cy: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.color = sun
        path.reset()
        path.moveTo(cx + s * 0.1f, cy - s * 0.5f)
        path.lineTo(cx - s * 0.3f, cy + s * 0.08f)
        path.lineTo(cx, cy + s * 0.08f)
        path.lineTo(cx - s * 0.1f, cy + s * 0.5f)
        path.lineTo(cx + s * 0.3f, cy - s * 0.08f)
        path.lineTo(cx, cy - s * 0.08f)
        path.close()
        c.drawPath(path, paint)
    }
}
