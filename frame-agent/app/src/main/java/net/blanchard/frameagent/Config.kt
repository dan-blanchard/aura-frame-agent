package net.blanchard.frameagent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.ZoneId

/**
 * Per-frame settings, pushed over adb to
 * /sdcard/Android/data/net.blanchard.frameagent/files/config.json
 * (the app's own external dir, so no storage permission is needed).
 */
data class Config(
    val frameId: String,
    val frameName: String,
    val brokerUri: String?,
    val username: String?,
    val password: String?,
    val features: Set<String>,
    val morningLayout: String,
    val timezone: ZoneId,
) {
    val base get() = "aura/$frameId"

    companion object {
        private const val TAG = "FrameAgent"

        // In the overlay with the system copy of the app, so a /data wipe doesn't take the login.
        private const val SYSTEM_CONFIG = "/system/etc/frame-agent/config.json"

        fun load(context: Context): Config {
            val pushed = File(context.getExternalFilesDir(null), "config.json")
            val file = if (pushed.exists()) pushed else File(SYSTEM_CONFIG)
            val json = try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "no usable config at $file, running without MQTT", e)
                JSONObject()
            }
            val features = json.optJSONArray("features")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                ?: setOf("effects")
            return Config(
                frameId = json.optString("frame_id", "frame_unconfigured"),
                frameName = json.optString("frame_name", "Aura Frame"),
                brokerUri = json.optString("broker_uri").ifEmpty { null },
                username = json.optString("username").ifEmpty { null },
                password = json.optString("password").ifEmpty { null },
                features = features,
                morningLayout = json.optString("morning_layout", MorningLayout.CARD),
                // The frames' system timezone is GMT and Aura relies on it, so wall-clock
                // times are converted here rather than changing it.
                timezone = ZoneId.of(json.optString("timezone", "America/New_York")),
            )
        }
    }
}
