package app.rcq.android.call

import android.content.Context

/**
 * Whether calls must go through our relay, and what to do when they cannot.
 *
 * ⚠ The default is ON, which is the opposite of most messengers: WebRTC opens
 * its own UDP sockets outside our transport, so a direct call hands the other
 * side your real address before a word is spoken, and no privacy setting
 * elsewhere in the app can prevent it. Relaying costs bandwidth and a little
 * latency; being findable costs more.
 *
 * ★ The setting exists because that choice has a real failure mode, and it was
 * hidden. With relay-only forced, a relay that cannot be reached (or that is
 * out of capacity, as on 2026-08-12) leaves the connection with NO candidates
 * at all, and the user gets silence until the connect timeout. Whether to trade
 * the address for the call is the user's call to make, not ours to make
 * silently — so the fallback is offered, never taken on its own.
 */
object CallPrivacy {
    private const val PREFS = "rcq_calls"
    private const val KEY_RELAY_ALWAYS = "relay_always"

    /** Route every call through the relay so the peer never learns your address. */
    fun alwaysRelay(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RELAY_ALWAYS, true)

    fun setAlwaysRelay(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RELAY_ALWAYS, on).apply()
    }
}
