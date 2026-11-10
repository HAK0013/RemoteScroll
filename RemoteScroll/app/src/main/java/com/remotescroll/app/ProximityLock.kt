package com.remotescroll.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches the proximity sensor: "covered" means the phone is in a pocket or lying face
 * down. Reports [onCovered] once it has been covered for [COVER_MS], and [onUncovered]
 * once it has been clear for [UNCOVER_MS]. The delays stop a passing hand from flickering
 * pocket lock on and off. Main thread only.
 */
class ProximityLock(
    context: Context,
    private val onCovered: () -> Unit,
    private val onUncovered: () -> Unit
) {

    companion object {
        private const val TAG = "ProximityLock"
        private const val COVER_MS = 1_500L
        private const val UNCOVER_MS = 800L
        /** Most sensors report 0 (near) or their max range (far); some report centimetres. */
        private const val NEAR_CM = 3f

        private const val PREFS = "remote_scroll"
        private const val KEY_ENABLED = "proximity_lock"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, on).apply()
        }

        fun hasSensor(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)
                ?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
    }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var covered: Boolean? = null

    private val reportCovered = Runnable { onCovered() }
    private val reportUncovered = Runnable { onUncovered() }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val s = sensor ?: return
            val distance = event.values.firstOrNull() ?: return
            val near = distance < minOf(NEAR_CM, s.maximumRange)
            if (near == covered) return
            covered = near
            handler.removeCallbacks(reportCovered)
            handler.removeCallbacks(reportUncovered)
            handler.postDelayed(if (near) reportCovered else reportUncovered, if (near) COVER_MS else UNCOVER_MS)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Start or stop listening. Cheap to call repeatedly. */
    fun setActive(active: Boolean) {
        if (active == listening) return
        val s = sensor
        if (active && s != null) {
            listening = sensorManager?.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL) == true
            Log.i(TAG, "Proximity listening: $listening")
        } else {
            sensorManager?.unregisterListener(listener)
            handler.removeCallbacks(reportCovered)
            handler.removeCallbacks(reportUncovered)
            covered = null
            listening = false
        }
    }
}
