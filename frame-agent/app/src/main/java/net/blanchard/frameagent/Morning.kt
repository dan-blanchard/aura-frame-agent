package net.blanchard.frameagent

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

data class MorningEvent(val start: String, val title: String)

data class MorningKid(
    val name: String,
    val color: String,
    val leaveAt: Instant,
    val note: String,
    val cleared: Boolean,
    /** False keeps the band up for this kid's morning, with no chip, card or alert. */
    val countdown: Boolean = true,
)

/** An active `cmd/morning` payload. */
data class MorningPlan(val events: List<MorningEvent>, val kids: List<MorningKid>) {
    companion object {
        /** Null means "show nothing": inactive, or an empty payload clearing the retained one. */
        fun parse(payload: String): MorningPlan? {
            if (payload.isBlank()) return null
            val json = JSONObject(payload)
            if (!json.optBoolean("active", false)) return null
            val events = json.optJSONArray("events")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    MorningEvent(e.optString("start"), e.optString("title"))
                }
            } ?: emptyList()
            val kids = json.optJSONArray("kids")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val k = arr.getJSONObject(i)
                    MorningKid(
                        name = k.getString("name"),
                        color = k.optString("color"),
                        leaveAt = OffsetDateTime.parse(k.getString("leave_at")).toInstant(),
                        note = k.optString("note"),
                        cleared = k.optBoolean("cleared", false),
                        countdown = k.optBoolean("countdown", true),
                    )
                }
            } ?: emptyList()
            return MorningPlan(events, kids)
        }
    }
}

enum class KidStage { WAITING, COUNTDOWN, FINAL, ALERT, DONE }

object MorningLayout {
    const val BAND = "band"
    const val CARD = "card"
}

/** Values of the `morning` field in the `state` topic. */
object MorningState {
    const val IDLE = "idle"
    const val BAND = "band"
    const val ALERT = "alert"
}

object MorningClock {
    val COUNTDOWN_FROM: Duration = Duration.ofMinutes(15)
    val FINAL_FROM: Duration = Duration.ofMinutes(5)
    val ALERT_UNTIL: Duration = Duration.ofMinutes(10)

    /**
     * The frame times out T+10 by itself, so a retained payload HA never cleared
     * can't leave an alert up or bring one back after a reboot.
     */
    fun stage(kid: MorningKid, now: Instant, dismissed: Boolean = false): KidStage {
        if (kid.cleared || dismissed) return KidStage.DONE
        val left = Duration.between(now, kid.leaveAt)
        return when {
            left <= ALERT_UNTIL.negated() -> KidStage.DONE
            !kid.countdown -> KidStage.WAITING
            left <= Duration.ZERO -> KidStage.ALERT
            left <= FINAL_FROM -> KidStage.FINAL
            left <= COUNTDOWN_FROM -> KidStage.COUNTDOWN
            else -> KidStage.WAITING
        }
    }

    fun state(layout: String, stages: Collection<KidStage>): String = when {
        KidStage.ALERT in stages -> MorningState.ALERT
        stages.all { it == KidStage.DONE } -> MorningState.IDLE
        // A kid's own frame stays on photos until the corner card is due.
        layout == MorningLayout.CARD &&
            stages.none { it == KidStage.COUNTDOWN || it == KidStage.FINAL } -> MorningState.IDLE
        else -> MorningState.BAND
    }

    /** "23 min" while there's time, "4:32" in the final five minutes. Rounds up. */
    fun countdown(now: Instant, leaveAt: Instant): String {
        val seconds = (Duration.between(now, leaveAt).toMillis() + 999).floorDiv(1000).coerceAtLeast(0)
        return if (seconds > FINAL_FROM.seconds) {
            "${(seconds + 59) / 60} min"
        } else {
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}
