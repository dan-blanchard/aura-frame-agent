package net.blanchard.frameagent

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

object ScreenMode {
    const val AUTO = "AUTO"
    const val ON = "ON"
    const val OFF = "OFF"
    val ALL = listOf(ON, OFF, AUTO)
}

/**
 * Holds the panel on or off for HA by writing ctrl_lcd. zz-aura-screen.rc makes the node
 * writable at boot; without it the frame stays under Aura's control. Aura only writes the
 * node when its own idea of on/off changes, and never reads it, so while HA holds a state
 * we rewrite whatever Aura sets, and AUTO has to put back what Aura believes.
 */
class ScreenControl(context: Context, private val onChange: () -> Unit) {

    private val node = File("/sys/devices/virtual/disp/disp/attr/ctrl_lcd")
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("screen-control").apply { start() }
    // Aura treats these writes as slow, so they stay off the main thread.
    private val handler = Handler(thread.looper)
    private val poll = Runnable { tick() }

    @Volatile
    var mode = ScreenMode.AUTO
        private set

    @Volatile
    var isOn = true
        private set

    fun start(initial: String) {
        handler.post {
            mode = usable(initial)
            tick()
        }
    }

    fun set(newMode: String) {
        handler.post {
            val wanted = usable(newMode)
            val handingBack = wanted == ScreenMode.AUTO && mode != ScreenMode.AUTO
            mode = wanted
            if (handingBack) restoreAuraState() else tick()
            onChange()
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun usable(wanted: String): String {
        if (wanted != ScreenMode.AUTO && !node.canWrite()) {
            Log.w(TAG, "can't write $node, leaving the screen to Aura")
            return ScreenMode.AUTO
        }
        return wanted
    }

    private fun tick() {
        handler.removeCallbacks(poll)
        var current = read()
        if (current != null) {
            val wanted = when (mode) {
                ScreenMode.ON -> true
                ScreenMode.OFF -> false
                else -> current
            }
            if (current != wanted && write(wanted)) current = wanted
            if (current != isOn) {
                isOn = current
                onChange()
            }
        }
        handler.postDelayed(poll, POLL_MS)
    }

    /** Aura turns the panel off below about 3 lux; match that, or it could stay lit all night. */
    private fun restoreAuraState() {
        val light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (light == null) {
            write(true)
            tick()
            return
        }
        var done = false
        fun finish(on: Boolean) {
            if (done) return
            done = true
            if (mode == ScreenMode.AUTO) write(on)
            tick()
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sensors.unregisterListener(this)
                finish(event.values[0] >= AURA_OFF_LUX)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensors.registerListener(listener, light, SensorManager.SENSOR_DELAY_NORMAL, handler)
        handler.postDelayed({
            sensors.unregisterListener(listener)
            finish(true)
        }, SENSOR_TIMEOUT_MS)
    }

    private fun read(): Boolean? = try {
        node.readText().substringAfter(':').trim() == "1"
    } catch (e: Exception) {
        null
    }

    private fun write(on: Boolean): Boolean = try {
        node.writeText(if (on) "1" else "0")
        Log.i(TAG, "ctrl_lcd -> ${if (on) "on" else "off"} ($mode)")
        true
    } catch (e: Exception) {
        Log.w(TAG, "couldn't write $node", e)
        false
    }

    companion object {
        private const val TAG = "FrameAgent"
        private const val POLL_MS = 2_000L
        private const val SENSOR_TIMEOUT_MS = 3_000L
        private const val AURA_OFF_LUX = 3.5f
    }
}
