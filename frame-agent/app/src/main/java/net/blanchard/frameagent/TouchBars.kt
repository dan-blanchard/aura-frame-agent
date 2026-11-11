package net.blanchard.frameagent

import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Listens to the frame's two capacitive touch bars straight from evdev. Our
 * windows can't take touches, and the bars aren't touchscreen input anyway. We
 * read without EVIOCGRAB, so Aura keeps seeing every press. The threads block in
 * read() and live as long as the process.
 */
class TouchBars(private val onPress: () -> Unit) {

    fun start() {
        val devices = findDevices()
        if (devices.isEmpty()) Log.w(TAG, "no touch bars found, dismiss won't work")
        devices.forEach { device ->
            thread(isDaemon = true, name = "touchbar-${device.name}") { readLoop(device) }
        }
    }

    private fun findDevices(): List<File> =
        File("/sys/class/input").listFiles { f -> f.name.startsWith("event") }.orEmpty()
            .filter { runCatching { File(it, "device/name").readText() }.getOrDefault("").startsWith("iqsx") }
            .map { File("/dev/input", it.name) }

    private fun readLoop(device: File) {
        // struct input_event: two longs of timestamp, then u16 type, u16 code, s32 value.
        val eventSize = if (Process.is64Bit()) 24 else 16
        val buffer = ByteArray(eventSize)
        val fields = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            try {
                FileInputStream(device).use { input ->
                    Log.i(TAG, "listening on $device")
                    while (input.read(buffer) == eventSize) {
                        val type = fields.getShort(eventSize - 8).toInt()
                        val value = fields.getInt(eventSize - 4)
                        if (type == EV_KEY && value == 1) onPress()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "can't read $device", e)
            }
            Thread.sleep(RETRY_MS)
        }
    }

    companion object {
        private const val TAG = "FrameAgent"
        private const val EV_KEY = 1
        private const val RETRY_MS = 30_000L
    }
}
