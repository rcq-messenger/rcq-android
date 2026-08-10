package app.rcq.android.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.rcq.android.push.Push

/**
 * Decline on the ringing notification, for the case where nothing else is alive to
 * handle it: the app was killed, a push woke it, and the offer is parked in
 * [IncomingCallStore] with no [CallController] anywhere.
 *
 * The live controller registers its own receiver for the same broadcast and, when
 * it exists, additionally signals the caller that they were declined. Both run; both
 * are idempotent. This one only guarantees that pressing Decline always stops the
 * ringing and drops the offer, which without it would sit there until the 60s
 * watchdog — the caller keeps ringing, the callee has already said no.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Push.ACTION_DECLINE_CALL) return
        val ctx = context?.applicationContext ?: return
        val callId = intent.getStringExtra(Push.EXTRA_CALL_ID) ?: return
        Push.dismissIncomingCall(ctx, callId)
    }
}
