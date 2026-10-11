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
 * per gap, not one per row. A row is never held for ever: after one read that
 * ANSWERED, or [maxWaitMs], it is decided on what we have.
 *
 * ⚠ A read that failed (a timeout, a 5xx, no network) is not an answer. It
 * used to count as one, and a new member's post was then decided against a
 * roster the island never got to serve, dropped and acked. A failed read only
 * spaces the next one; [Step.answered] tells the caller whether the decision
 * rests on the island's word or on the clock alone, and [Step.unheardRounds]
 * how many times in a row the clock decided for this row. Pure apart from the
 * clock, so it is checked on the JVM.
 */
class IslandRecheck(
    private val minGapMs: Long,
    private val maxWaitMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind { FETCH, WAIT, DECIDE }

    /** [delayMs]: for FETCH, how long the read waits before it starts.
     *  [answered]: for DECIDE, a read that started after the row was held
     *  came back with the island's answer; false when [maxWaitMs] ran out
     *  without one. [unheardRounds]: for DECIDE without an answer, how many
     *  such decisions this row has had in a row, this one included. */
    data class Step(val kind: Kind, val delayMs: Long = 0L, val answered: Boolean = false, val unheardRounds: Int = 0)

    private val heldSince = ConcurrentHashMap<String, Long>()
    /** resource -> when the read in flight was granted. */
    private val inFlight = ConcurrentHashMap<String, Long>()
    /** resource -> when the last read that the island ANSWERED started. */
    private val answeredFrom = ConcurrentHashMap<String, Long>()
    /** resource -> when the last finished read started, answered or not:
     *  what the next read is spaced from. */
    private val lastTried = ConcurrentHashMap<String, Long>()
    /** "$resource|$subject" -> (decisions on the clock alone in a row, when the last was). */
    private val unheard = ConcurrentHashMap<String, Pair<Int, Long>>()

    fun step(resource: String, subject: String): Step = synchronized(this) {
        val now = clock()
        val k = "$resource|$subject"
        // A row that never came back (acked through another road) leaves its
        // entry behind; a forger minting a fresh key per row would grow this
        // without bound otherwise.
        if (heldSince.size > MAX_HELD) heldSince.entries.removeIf { now - it.value >= maxWaitMs }
        // A row decided on the clock comes back on the next drain or not at
        // all; a count nobody asked for in a while is stale.
        if (unheard.size > MAX_HELD) unheard.entries.removeIf { now - it.value.second >= UNHEARD_MEMORY * maxWaitMs }
        val since = heldSince.getOrPut(k) { now }
        val answered = answeredFrom[resource]
        if (answered != null && answered >= since) {
            heldSince.remove(k)
            unheard.remove(k)
            return Step(Kind.DECIDE, answered = true)
        }
        if (now - since >= maxWaitMs) {
            heldSince.remove(k)
            val rounds = (unheard[k]?.first ?: 0) + 1
            unheard[k] = rounds to now
            return Step(Kind.DECIDE, answered = false, unheardRounds = rounds)
        }
        if (inFlight.containsKey(resource)) return Step(Kind.WAIT)
        // Spaced from the last ATTEMPT: a failing island costs one read per
        // gap, not a loop of them.
        val last = listOfNotNull(answered, lastTried[resource]).maxOrNull()
        val delay = if (last == null) 0L else (last + minGapMs - now).coerceAtLeast(0L)
        inFlight[resource] = now + delay
        return Step(Kind.FETCH, delay)
    }

    /** The read granted by [step] for [resource] finished. [answered]: the
     *  island answered it (a 4xx that names the subject is an answer too); a
     *  failed read only spaces the next one. */
    fun finished(resource: String, answered: Boolean) = synchronized(this) {
        val started = inFlight.remove(resource) ?: return@synchronized
        lastTried[resource] = started
        if (answered) answeredFrom[resource] = started
    }

    /** Forget everything (an account switch: another account's rows). */
    fun clear() = synchronized(this) {
        heldSince.clear()
        inFlight.clear()
        answeredFrom.clear()
        lastTried.clear()
        unheard.clear()
    }

    companion object {
        private const val MAX_HELD = 512
        /** An unheard count is kept this many [maxWaitMs] after its last use. */
        private const val UNHEARD_MEMORY = 6

        /** Whether a read that threw still carries the island's answer: a 4xx
         *  names the subject (no such user, no such room, not a member, a
         *  closed island refusing the lookup). A timeout, a 5xx, no network,
         *  our own expired token (401) and a rate limit (408, 425, 429) say
         *  nothing about it. */
        fun failureIsAnswer(e: Throwable): Boolean {
            val m = e.message.orEmpty()
            if (!m.startsWith("HTTP 4")) return false
            return listOf("HTTP 401", "HTTP 408", "HTTP 425", "HTTP 429").none { m.startsWith(it) }
        }
    }
}
