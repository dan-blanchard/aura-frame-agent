package net.blanchard.frameagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AgentService.start(context)
    }
}

/**
 * adb-only test hooks (the manifest requires the sender to hold DUMP):
 *   am broadcast -n net.blanchard.frameagent/.DebugReceiver -a net.blanchard.frameagent.SET_EFFECT --es effect halloween
 *   am broadcast -n net.blanchard.frameagent/.DebugReceiver -a net.blanchard.frameagent.GHOST [--es style boo]
 *     Thanksgiving: --es style hooty|pumpkin|franklin [--es kind corner|side|bottom|walk]
 *   am broadcast -n net.blanchard.frameagent/.DebugReceiver -a net.blanchard.frameagent.START
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_EFFECT -> AgentService.start(context, effect = intent.getStringExtra("effect"))
            ACTION_GHOST -> AgentService.start(
                context, ghost = true, ghostStyle = intent.getStringExtra("style"), ghostKind = intent.getStringExtra("kind"),
            )
            else -> AgentService.start(context)
        }
    }

    companion object {
        const val ACTION_SET_EFFECT = "net.blanchard.frameagent.SET_EFFECT"
        const val ACTION_GHOST = "net.blanchard.frameagent.GHOST"
    }
}
