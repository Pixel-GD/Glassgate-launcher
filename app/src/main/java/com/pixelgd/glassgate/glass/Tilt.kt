package com.pixelgd.glassgate.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.pixelgd.glassgate.util.lerp

/**
 * Device tilt, smoothed, in [-1, 1] on both axes. Glass surfaces use it to slide their
 * specular highlight as the phone moves, like the iOS 26 dock and folders.
 * Call [start]/[stop] from the activity's resume/pause to save battery.
 */
class Tilt(context: Context) : SensorEventListener {

    fun interface Listener {
        fun onTilt(x: Float, y: Float)
    }

    private val sensors = context.getSystemService(SensorManager::class.java)!!
    private val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val listeners = LinkedHashSet<Listener>()

    var x = 0f
        private set
    var y = 0f
        private set

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    fun start() {
        gravity?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Upright phone reads roughly (0, 9.8, z); map side-to-side and forward tilt.
        val nx = (-event.values[0] / 6f).coerceIn(-1f, 1f)
        val ny = ((event.values[1] - 7f) / 5f).coerceIn(-1f, 1f)
        val nx2 = lerp(x, nx, 0.12f)
        val ny2 = lerp(y, ny, 0.12f)
        if (kotlin.math.abs(nx2 - x) < 0.002f && kotlin.math.abs(ny2 - y) < 0.002f) return
        x = nx2
        y = ny2
        listeners.forEach { it.onTilt(x, y) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
