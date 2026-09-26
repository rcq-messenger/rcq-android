package app.rcq.android.call

import android.content.Context

/**
 * The calls this phone turned down, by call id, so a caller's later
 * "you missed my call" for one of them is not believed (#1047 review).
 *
 * ⚠ Why the callee has to remember this itself. A call that rang through a
 * PUSH lives in [IncomingCallStore], not in a [CallController], and Decline on
 * that ring (the notification's button, the full-screen screen) only took the
 * ring down: nothing reached the caller. Their call ran on to its timeout as
 * "no answer", or they gave up, and since 0.207 a caller who was told
 * `call_offline` leaves a `call_missed` marker in that case. So the person who
 * pressed Decline opened the app to a "Missed call" notification and an unread
 * row for the call they had just refused. The marker comes from whatever
 * client the caller runs, so the only place that can tell "missed" from
 * "declined" for every caller is here.
 *
 * On disk and not in memory: the marker is read by the queue drain, which can
 * run in a later process than the one that declined (the ring came from a
 * push into a process that died again). Bounded, oldest out: a marker older
 * than the last [CAP] declines is not worth keeping a list for.
 */
object DeclinedCalls {
    private const val PREFS = "rcq_calls"
    private const val KEY = "declined"
    internal const val CAP = 32

    @Synchronized
    fun remember(ctx: Context, callId: String) {
        if (callId.isBlank()) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putString(KEY, appended(p.getString(KEY, null), callId)).apply()
    }

    /** [raw] with [callId] moved to the newest end, trimmed to [CAP]. */
    internal fun appended(raw: String?, callId: String): String {
        val ids = read(raw).filter { it != callId }.toMutableList()
        ids.add(callId)
        while (ids.size > CAP) ids.removeAt(0)
        return ids.joinToString(",")
    }

    @Synchronized
    fun wasDeclined(ctx: Context, callId: String): Boolean {
        if (callId.isBlank()) return false
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return callId in read(p.getString(KEY, null))
    }

    /** The panic wipe. `commit`, not `apply`: the phone may be taken away
     *  before a queued write lands. */
    @Synchronized
    fun wipeAll(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Call ids are UUIDs, so a comma never appears inside one. */
    internal fun read(raw: String?): List<String> =
        raw.orEmpty().split(',').filter { it.isNotBlank() }
}
