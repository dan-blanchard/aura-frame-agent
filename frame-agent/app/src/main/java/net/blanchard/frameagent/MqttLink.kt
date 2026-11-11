package net.blanchard.frameagent

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MQTT connection to Home Assistant's broker. Topics live under aura/<frame_id>/;
 * the contract is in home-assistant/README.md.
 */
class MqttLink(
    private val config: Config,
    private val onCommand: (command: String, payload: String) -> Unit,
    private val state: () -> String,
) {
    private val base = config.base
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val client = MqttClient(config.brokerUri, "frame-agent-${config.frameId}", MemoryPersistence())

    fun start() {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                Log.i(TAG, "connected to $serverURI (reconnect=$reconnect)")
                io.execute { onConnected() }
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "connection lost", cause)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val command = topic.removePrefix("$base/cmd/")
                onCommand(command, String(message.payload))
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        io.execute(::connect)
    }

    fun stop() {
        io.execute {
            runCatching {
                publish("$base/availability", "offline", retained = true)
                client.disconnect()
            }
            io.shutdown()
        }
    }

    fun publishState() {
        io.execute { publish("$base/state", state(), retained = true) }
    }

    fun publishEvent(json: String) {
        io.execute { runCatching { publish("$base/event", json, retained = false) } }
    }

    private fun connect() {
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            keepAliveInterval = 30
            config.username?.let { userName = it }
            config.password?.let { password = it.toCharArray() }
            setWill("$base/availability", "offline".toByteArray(), 1, true)
        }
        try {
            client.connect(options)
        } catch (e: Exception) {
            // Automatic reconnect only covers drops after a first success.
            Log.w(TAG, "connect failed, retrying in 30s", e)
            io.schedule(::connect, 30, TimeUnit.SECONDS)
        }
    }

    private fun onConnected() {
        runCatching {
            client.subscribe("$base/cmd/#", 1)
            publishDiscovery()
            publish("$base/availability", "online", retained = true)
            publish("$base/state", state(), retained = true)
        }.onFailure { Log.w(TAG, "post-connect setup failed", it) }
    }

    private fun publishDiscovery() {
        val id = config.frameId
        val device = JSONObject()
            .put("identifiers", JSONArray().put(id))
            .put("name", config.frameName)
            .put("manufacturer", "Aura + frame-agent")
            .put("model", "Walden")
            .put("sw_version", BuildConfig.VERSION_NAME)

        // default_entity_id replaced object_id; current HA ignores object_id.
        fun entity(platform: String, uniqueSuffix: String) = JSONObject()
            .put("unique_id", "${id}_$uniqueSuffix")
            .put("default_entity_id", "$platform.${id}_$uniqueSuffix")
            .put("availability_topic", "$base/availability")
            .put("device", device)

        val effect = entity("select", "effect")
            .put("name", "Effect")
            .put("icon", "mdi:ghost")
            .put("command_topic", "$base/cmd/effect")
            .put("retain", true) // a rebooted frame gets the current effect on reconnect
            .put("state_topic", "$base/state")
            .put("value_template", "{{ value_json.effect }}")
            .put("options", JSONArray(Effect.ALL))
        publish("homeassistant/select/$id/effect/config", effect.toString(), retained = true)

        if ("screen" in config.features) {
            val screen = entity("select", "screen")
                .put("name", "Screen")
                .put("icon", "mdi:monitor")
                .put("command_topic", "$base/cmd/screen")
                .put("state_topic", "$base/state")
                .put("value_template", "{{ value_json.screen_mode }}")
                .put("options", JSONArray(ScreenMode.ALL))
            publish("homeassistant/select/$id/screen/config", screen.toString(), retained = true)
        }

        if ("media" in config.features) {
            val clearMedia = entity("button", "clear_media")
                .put("name", "Clear media")
                .put("icon", "mdi:image-off")
                .put("command_topic", "$base/cmd/media")
                .put("payload_press", MediaRequest.CLEAR)
            publish("homeassistant/button/$id/clear_media/config", clearMedia.toString(), retained = true)
        }

        val stateSensor = entity("sensor", "state")
            .put("name", "State")
            .put("icon", "mdi:school")
            .put("state_topic", "$base/state")
            .put("value_template", "{{ value_json.morning }}")
            .put("json_attributes_topic", "$base/state")
        publish("homeassistant/sensor/$id/state/config", stateSensor.toString(), retained = true)
    }

    private fun publish(topic: String, payload: String, retained: Boolean) {
        if (!client.isConnected) return
        client.publish(topic, payload.toByteArray(), 1, retained)
    }

    companion object {
        private const val TAG = "FrameAgent"
    }
}
