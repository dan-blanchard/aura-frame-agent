package net.blanchard.frameagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * Long-running host for the overlays and the MQTT link. Everything we show is an
 * overlay window, never an activity: Aura's updater puts its own app back on top
 * about once a minute if anything else takes the foreground.
 */
class AgentService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var config: Config
    private lateinit var layers: List<OverlayLayer>
    private lateinit var effects: EffectsOverlay
    private var media: MediaOverlay? = null
    private var morning: MorningOverlay? = null
    private var screen: ScreenControl? = null
    private var mqtt: MqttLink? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        config = Config.load(this)
        // Bottom to top, so a scene can't hide a countdown.
        val effectsLayer = OverlayLayer(this)
        val mediaLayer = OverlayLayer(this)
        val morningLayer = OverlayLayer(this)
        layers = listOf(effectsLayer, mediaLayer, morningLayer)
        effects = EffectsOverlay(this, effectsLayer)
        effects.set(Effect.parse(prefs().getString(PREF_EFFECT, Effect.OFF)!!))
        if (FEATURE_MORNING in config.features) {
            morning = MorningOverlay(
                this, morningLayer, config.morningLayout, config.timezone,
                onStateChanged = { mqtt?.publishState() },
                onDismiss = ::publishDismiss,
            )
            TouchBars { main.post { morning?.dismissAlerting() } }.start()
        }
        if (FEATURE_MEDIA in config.features) {
            media = MediaOverlay(
                this, mediaLayer,
                onStateChanged = { mqtt?.publishState() },
                onError = ::publishMediaError,
            )
        }
        if (FEATURE_SCREEN in config.features) {
            screen = ScreenControl(this) {
                main.post(::syncMediaPause)
                mqtt?.publishState()
            }.also {
                it.start(prefs().getString(PREF_SCREEN, ScreenMode.AUTO)!!)
            }
        }
        if (config.brokerUri != null) {
            mqtt = MqttLink(config, ::onCommand, ::stateJson).also { it.start() }
        }
        Log.i(TAG, "started as ${config.frameId}, mqtt=${config.brokerUri != null}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_EFFECT)?.let { setEffect(it) }
        if (intent?.getBooleanExtra(EXTRA_GHOST, false) == true) {
            effects.summon(intent.getStringExtra(EXTRA_GHOST_STYLE), intent.getStringExtra(EXTRA_GHOST_KIND))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mqtt?.stop()
        morning?.stop()
        media?.stop()
        screen?.stop()
        effects.set(Effect.OFF)
        layers.forEach { it.destroy() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Called on the MQTT client thread. */
    private fun onCommand(command: String, payload: String) {
        main.post {
            when (command) {
                "effect" -> setEffect(payload.trim())
                "ghost" -> sendGhost(payload)
                "media" -> setMedia(payload)
                "morning" -> setMorning(payload)
                "screen" -> setScreen(payload.trim().uppercase())
                else -> Log.w(TAG, "ignoring unknown command $command")
            }
        }
    }

    private fun setScreen(mode: String) {
        val control = screen ?: return
        if (mode !in ScreenMode.ALL) {
            Log.w(TAG, "unknown screen mode '$mode'")
            return
        }
        prefs().edit().putString(PREF_SCREEN, mode).apply()
        control.set(mode)
    }

    private fun sendGhost(payload: String) {
        try {
            val json = JSONObject(payload)
            // Sent to every frame just before a tour: keep the frame's own visitors out of it.
            if (json.has("quiet_ms")) {
                effects.quiet(json.getLong("quiet_ms"))
                return
            }
            // HA's peek: one visitor on this frame only (Thanksgiving; HA spaces them out).
            if (json.optBoolean("peek")) {
                effects.summon(json.optString("style").ifEmpty { null }, json.optString("kind").ifEmpty { null })
                return
            }
            effects.visit(
                style = json.optString("style").ifEmpty { null },
                from = GhostEdge.parse(json.optString("from")) ?: GhostEdge.LEFT,
                to = GhostEdge.parse(json.optString("to")) ?: GhostEdge.RIGHT,
                fromPos = json.optDouble("from_pos", 0.5).toFloat(),
                toPos = json.optDouble("to_pos", 0.5).toFloat(),
                durationMs = json.optLong("duration_ms", 4_500L),
                // Hooty only (Thanksgiving): how long his body stays across, then how long
                // his head takes to come back. -1 lets the frame choose.
                holdMs = json.optLong("hold_ms", -1L),
                returnMs = json.optLong("return_ms", -1L),
            )
        } catch (e: Exception) {
            Log.w(TAG, "bad cmd/ghost payload", e)
        }
    }

    private fun setMedia(payload: String) {
        val overlay = media ?: return
        try {
            val request = MediaRequest.parse(payload)
            if (request == null) {
                overlay.clear()
            } else {
                syncMediaPause()
                overlay.show(request)
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad cmd/media payload", e)
        }
    }

    private fun syncMediaPause() {
        media?.paused = screen?.isOn == false
    }

    private fun publishMediaError(url: String, reason: String) {
        mqtt?.publishEvent(JSONObject().put("type", "media_error").put("url", url).put("reason", reason).toString())
    }

    private fun setMorning(payload: String) {
        val overlay = morning ?: return
        try {
            overlay.setPlan(MorningPlan.parse(payload))
        } catch (e: Exception) {
            // Keep showing the last good plan; a kid's countdown matters more than this one.
            Log.w(TAG, "bad cmd/morning payload", e)
        }
    }

    private fun publishDismiss(kid: String) {
        mqtt?.publishEvent(JSONObject().put("type", "dismiss").put("kid", kid).toString())
    }

    private fun setEffect(raw: String) {
        val effect = Effect.parse(raw)
        if (effect !in Effect.ALL) {
            Log.w(TAG, "unknown effect '$effect'")
            return
        }
        effects.set(effect)
        prefs().edit().putString(PREF_EFFECT, effect).apply()
        mqtt?.publishState()
    }

    private fun stateJson(): String = JSONObject()
        .put("effect", effects.current)
        .put("media", media?.currentUrl ?: JSONObject.NULL)
        .put("morning", morning?.state ?: MorningState.IDLE)
        .apply {
            screen?.let {
                put("screen", if (it.isOn) "on" else "off")
                put("screen_control", if (it.mode == ScreenMode.AUTO) "aura" else "ha")
                put("screen_mode", it.mode)
            }
        }
        .toString()

    private fun prefs() = getSharedPreferences("agent", MODE_PRIVATE)

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Frame agent", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Frame agent running")
            .setSmallIcon(android.R.drawable.star_on)
            .build()
    }

    companion object {
        private const val TAG = "FrameAgent"
        private const val CHANNEL = "agent"
        private const val NOTIFICATION_ID = 1
        private const val FEATURE_MORNING = "morning"
        private const val FEATURE_SCREEN = "screen"
        private const val FEATURE_MEDIA = "media"
        private const val PREF_SCREEN = "screen"
        private const val PREF_EFFECT = "effect"
        private const val EXTRA_EFFECT = "effect"
        private const val EXTRA_GHOST = "ghost"
        private const val EXTRA_GHOST_STYLE = "ghost_style"
        private const val EXTRA_GHOST_KIND = "ghost_kind"

        fun start(context: Context, effect: String? = null, ghost: Boolean = false, ghostStyle: String? = null, ghostKind: String? = null) {
            val intent = Intent(context, AgentService::class.java)
            effect?.let { intent.putExtra(EXTRA_EFFECT, it) }
            if (ghost) intent.putExtra(EXTRA_GHOST, true)
            ghostStyle?.let { intent.putExtra(EXTRA_GHOST_STYLE, it) }
            ghostKind?.let { intent.putExtra(EXTRA_GHOST_KIND, it) }
            context.startForegroundService(intent)
        }
    }
}
