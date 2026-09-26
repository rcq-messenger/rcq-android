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
 * it holds the call, signals the caller that they were declined. Both run; both
 * are idempotent. This one guarantees that pressing Decline always stops the
 * ringing and drops the offer, which without it would sit there until the 60s
 * watchdog — the caller keeps ringing, the callee has already said no. It also
 * records the decline ([DeclinedCalls]) and, for a call that only ever lived in
 * [IncomingCallStore], tells the caller through the live session if there is one.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Push.ACTION_DECLINE_CALL) return
        val ctx = context?.applicationContext ?: return
        val callId = intent.getStringExtra(Push.EXTRA_CALL_ID) ?: return
        // Remembered first, whatever happens next: a caller's missed-call
        // marker for this call must not be believed (see [DeclinedCalls]).
        DeclinedCalls.remember(ctx, callId)
        // Read before the dismiss below clears it.
        val parked = IncomingCallStore.pending?.takeIf { it.callId == callId }
        Push.dismissIncomingCall(ctx, callId)
        // And the caller hears "declined" instead of ringing on to their own
        // timeout, when there is a session here to say it with. There is none
        // when the push woke a process with no window; the marker check above
        // covers that case on this side, and the caller simply rings out.
        parked?.let { p -> runCatching { app.rcq.android.Session.live?.declineParkedCall(p) } }
    }
}
