package net.blanchard.frameagent

import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * One permanent overlay window. Android stacks an app's overlays in the order they were
 * added, so the layers are created once, bottom first, and their contents come and go.
 * While empty the host is GONE, which leaves the window with no surface to composite.
 */
class OverlayLayer(context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val host = FrameLayout(context).apply { visibility = View.GONE }

    init {
        wm.addView(host, EffectsOverlay.overlayParams())
    }

    fun show(view: View) {
        host.removeAllViews()
        host.addView(view)
        host.visibility = View.VISIBLE
    }

    fun clear() {
        host.visibility = View.GONE
        host.removeAllViews()
    }

    fun destroy() {
        wm.removeView(host)
    }
}
