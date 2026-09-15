package app.rcq.android.net

/**
 * When to ask a visited island for the contact requests addressed to our guest
 * copy there (F1 of the 15.09 cross-island spec). Pure: the clock is passed in
 * and the jitter source is injected, so every rule below is checked on the JVM.
 *
 * The island allows 120 `GET /contacts/pending` a minute per account. One ask
 * every five minutes is far inside that, and it is less than the 30 s queue
 * drain the same island already sees from the same token.
 *  - A host never asked is due at once.
 *  - After an answer: 5 min, jittered by ±20 % so several islands are not asked
 *    in the same second on every tick.
 *  - After a failure: 5, 10, 20, 40, then 60 min, reset by the next answer.
 *  - A 429's Retry-After is honoured, never shorter than 5 min, and nothing
 *    (not even [forceSoon]) asks inside it.
 *  - [forceSoon] (the requests list was opened) makes a host due now, at most
 *    once a minute per host.
 *
 * Keys are opaque to the schedule. The session passes "own number @ host", so
 * two accounts on one device never share a clock for the same island.
 */
class PendingPollSchedule(private val random: () -> Double = { Math.random() }) {

    private class State(
        var nextAt: Long = 0L,
        var fails: Int = 0,
        /** Hard floor from a Retry-After: nothing asks before it. */
        var floorAt: Long = 0L,
        var lastForceAt: Long? = null,
    )

    private val states = HashMap<String, State>()

    @Synchronized
    fun due(key: String, now: Long): Boolean {
        val s = states[key] ?: return true
        return now >= s.nextAt && now >= s.floorAt
    }

    /** [ok] the island answered the list. [retryAfterSec] is set only for a 429. */
    @Synchronized
    fun onResult(key: String, now: Long, ok: Boolean, retryAfterSec: Int? = null) {
        val s = states.getOrPut(key) { State() }
        if (ok) {
            s.fails = 0
            s.floorAt = 0L
            s.nextAt = now + jittered(BASE_MS)
            return
        }
        s.fails++
        s.nextAt = now + BACKOFF_MS[minOf(s.fails, BACKOFF_MS.size) - 1]
        if (retryAfterSec != null) {
            val floor = now + maxOf(retryAfterSec.toLong() * 1000L, MIN_RETRY_AFTER_MS)
            s.floorAt = floor
            s.nextAt = maxOf(s.nextAt, floor)
        }
    }

    /** Pull [key] forward to now. False when the debounce or a Retry-After
     *  keeps it where it is. */
    @Synchronized
    fun forceSoon(key: String, now: Long): Boolean {
        val s = states.getOrPut(key) { State() }
        val last = s.lastForceAt
        if (last != null && now - last < FORCE_DEBOUNCE_MS) return false
        s.lastForceAt = now
        if (now < s.floorAt) return false
        s.nextAt = minOf(s.nextAt, now)
        return true
    }

    @Synchronized
    fun clear() = states.clear()

    private fun jittered(base: Long): Long {
        val r = random().coerceIn(0.0, 1.0)
        return (base * (1.0 - JITTER + 2 * JITTER * r)).toLong()
    }

    companion object {
        const val BASE_MS = 5 * 60_000L
        const val JITTER = 0.2
        val BACKOFF_MS = longArrayOf(5 * 60_000L, 10 * 60_000L, 20 * 60_000L, 40 * 60_000L, 60 * 60_000L)
        const val MIN_RETRY_AFTER_MS = 5 * 60_000L
        const val FORCE_DEBOUNCE_MS = 60_000L
    }
}
