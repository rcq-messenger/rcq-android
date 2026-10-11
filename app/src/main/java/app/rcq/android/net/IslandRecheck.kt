package app.rcq.android.net

import java.util.concurrent.ConcurrentHashMap

/**
 * "Ask the island once more before deciding against a row."
 *
 * A row that names a contact under a key we do not hold for them, a room member
 * the roster we hold does not list, or a ratchet identity the island has not
 * published to us, may be legitimate: the person changed their phrase (new
 * keys), joined the room a minute ago, or reinstalled. The island's answer
 * read AFTER the row arrived decides; one read before it does not, or a new
 * member's first post would be dropped by a roster fetched a second before
 * they joined.
 *
 * [step] is asked per row: [resource] is what has to be read (a roster, a key
 * card, a device list), [subject] what about it this row depends on. Until a
 * read of [resource] that STARTED after the row was first held has finished,
 * the row waits (the caller leaves it on the island, without a strike). One
 * read per resource is in flight at a time, and reads of one resource are at
 * least [minGapMs] apart: a stream of forged rows costs the island one read
 * per gap, not one per row. A read that fails still counts as asked, so a
 * row is never held for ever: after one finished read, or [maxWaitMs], it is
 * decided on what we have. Pure apart from the clock, so it is checked on the
 * JVM.
 */
class IslandRecheck(
    private val minGapMs: Long,
    private val maxWaitMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind { FETCH, WAIT, DECIDE }

    /** [delayMs]: for FETCH, how long the read waits before it starts. */
    data class Step(val kind: Kind, val delayMs: Long = 0L)

    private val heldSince = ConcurrentHashMap<String, Long>()
    /** resource -> when the read in flight was granted. */
    private val inFlight = ConcurrentHashMap<String, Long>()
    /** resource -> when the last finished read started. */
    private val answeredFrom = ConcurrentHashMap<String, Long>()

    fun step(resource: String, subject: String): Step = synchronized(this) {
        val now = clock()
        val k = "$resource|$subject"
        // A row that never came back (acked through another road) leaves its
        // entry behind; a forger minting a fresh key per row would grow this
        // without bound otherwise.
        if (heldSince.size > MAX_HELD) heldSince.entries.removeIf { now - it.value >= maxWaitMs }
        val since = heldSince.getOrPut(k) { now }
        val answered = answeredFrom[resource]
        if ((answered != null && answered >= since) || now - since >= maxWaitMs) {
            heldSince.remove(k)
            return Step(Kind.DECIDE)
        }
        if (inFlight.containsKey(resource)) return Step(Kind.WAIT)
        val delay = if (answered == null) 0L else (answered + minGapMs - now).coerceAtLeast(0L)
        inFlight[resource] = now + delay
        return Step(Kind.FETCH, delay)
    }

    /** The read granted by [step] for [resource] finished, answered or not. */
    fun finished(resource: String) = synchronized(this) {
        val started = inFlight.remove(resource) ?: return@synchronized
        answeredFrom[resource] = started
    }

    /** Forget everything (an account switch: another account's rows). */
    fun clear() = synchronized(this) {
        heldSince.clear()
        inFlight.clear()
        answeredFrom.clear()
    }

    private companion object {
        const val MAX_HELD = 512
    }
}
