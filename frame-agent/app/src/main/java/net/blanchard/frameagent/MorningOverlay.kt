package net.blanchard.frameagent

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.Instant
import java.time.ZoneId

/**
 * Top overlay layer: the morning band, countdown cards and "Time to go!" alert,
 * rendered from the retained cmd/morning payload. Countdowns are computed here
 * from leave_at, so HA only republishes when something changes. The layer is only
 * filled while something is showing.
 */
class MorningOverlay(
    private val context: Context,
    private val layer: OverlayLayer,
    private val layout: String,
    private val zone: ZoneId,
    private val onStateChanged: () -> Unit,
    private val onDismiss: (kid: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var view: MorningView? = null
    private var plan: MorningPlan? = null

    // Survives HA republishing the same kid before it has seen our dismiss event.
    private val dismissed = mutableSetOf<Pair<String, Instant>>()

    var state: String = MorningState.IDLE
        private set

    private val tick = Runnable { refresh() }

    fun setPlan(newPlan: MorningPlan?) {
        plan = newPlan
        refresh()
    }

    /** A touch bar was pressed. Only means something while an alert is up. */
    fun dismissAlerting() {
        val now = Instant.now()
        val alerting = plan?.kids.orEmpty().filter { stageOf(it, now) == KidStage.ALERT }
        alerting.forEach { dismissed += it.name to it.leaveAt }
        alerting.forEach { onDismiss(it.name) }
        if (alerting.isNotEmpty()) refresh()
    }

    fun stop() {
        main.removeCallbacks(tick)
        detach()
    }

    private fun stageOf(kid: MorningKid, now: Instant) =
        MorningClock.stage(kid, now, (kid.name to kid.leaveAt) in dismissed)

    private fun refresh() {
        main.removeCallbacks(tick)
        val now = Instant.now()
        val current = plan
        val stages = current?.kids.orEmpty().associateWith { stageOf(it, now) }
        val newState = MorningClock.state(layout, stages.values)

        if (newState == MorningState.IDLE) {
            detach()
        } else {
            attach().show(now, current?.events.orEmpty(), stages)
        }
        if (newState != state) {
            state = newState
            onStateChanged()
        }
        if (stages.values.any { it != KidStage.DONE }) {
            main.postDelayed(tick, 1000 - now.toEpochMilli() % 1000)
        }
    }

    private fun attach(): MorningView {
        view?.let { return it }
        val v = MorningView(context, layout, zone)
        layer.show(v)
        view = v
        return v
    }

    private fun detach() {
        layer.clear()
        view = null
    }
}
