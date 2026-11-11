package net.blanchard.frameagent

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager

object Effect {
    // Not "none": HA's MQTT select reads that payload as "no value" and shows unknown.
    const val OFF = "off"
    const val HALLOWEEN = "halloween"
    const val HALLOWEEN_SPOOKY = "halloween_spooky"
    const val THANKSGIVING = "thanksgiving"
    val ALL = listOf(OFF, HALLOWEEN, HALLOWEEN_SPOOKY, THANKSGIVING)

    /** Maps the old "none" (0.1.0 prefs, retained commands) to [OFF]. */
    fun parse(s: String): String = if (s == "none") OFF else s
}

/**
 * Bottom overlay layer: seasonal effects drawn over Aura's photos. The layer is
 * emptied when no effect is on, so "off" costs nothing.
 *
 * Halloween is ghosts; Thanksgiving is the turkey cast. Both take the same visitor
 * command (cmd/ghost), so HA's tour script works for either; the style names differ.
 */
class EffectsOverlay(private val context: Context, private val layer: OverlayLayer) {

    private var view: View? = null

    var current: String = Effect.OFF
        private set

    fun set(effect: String) {
        current = effect
        when (effect) {
            Effect.OFF -> detach()
            Effect.THANKSGIVING -> attach { ThanksgivingView(context) }
            else -> attach { HalloweenView(context) }.spooky = effect == Effect.HALLOWEEN_SPOOKY
        }
    }

    /** HA's cmd/ghost peek, or the adb hook. `kind`: Thanksgiving corner/side/bottom/walk; Halloween ghost/fly/spider. */
    fun summon(style: String? = null, kind: String? = null) {
        when (val v = view) {
            is HalloweenView -> v.summonGhost(GhostStyle.parse(style), kind)
            is ThanksgivingView -> v.summon(TurkeyStyle.parse(style), kind)
        }
    }

    /** Ignored while no effect is on: the window doesn't exist. Unknown styles mean random. */
    fun visit(
        style: String?, from: GhostEdge, to: GhostEdge, fromPos: Float, toPos: Float, durationMs: Long,
        holdMs: Long = -1, returnMs: Long = -1,
    ) {
        when (val v = view) {
            is HalloweenView -> v.visit(GhostStyle.parse(style), from, to, fromPos, toPos, durationMs)
            is ThanksgivingView -> v.visit(TurkeyStyle.parse(style), from, to, fromPos, toPos, durationMs, holdMs, returnMs)
        }
    }

    /** HA's tour is starting: no visitors of the frame's own for [ms] (Thanksgiving also hurries one off). */
    fun quiet(ms: Long) {
        when (val v = view) {
            is HalloweenView -> v.quiet(ms)
            is ThanksgivingView -> v.quiet(ms)
        }
    }

    /** Reuses the current view if it is already the right kind, else swaps it in. */
    private inline fun <reified T : View> attach(make: () -> T): T {
        (view as? T)?.let { return it }
        val v = make()
        layer.show(v)
        view = v
        return v
    }

    private fun detach() {
        layer.clear()
        view = null
    }

    companion object {
        /** Full-screen, see-through, never takes touches or focus from Aura. */
        fun overlayParams() = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Windows added from a Service only get the GPU renderer if asked.
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
    }
}
