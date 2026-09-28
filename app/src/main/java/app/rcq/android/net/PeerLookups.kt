package app.rcq.android.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * At most [parallel] per-peer lookups in flight to one island at a time: a
 * profile card (`GET /users/{uin}/info`), a device list (`GET /keys/{uin}/devices`)
 * and the two bundle reads. Shared by every [RcqApi] built for the same host,
 * so a second instance (a guest session, the route watchdog's rebuild) queues
 * behind the first instead of adding its own burst.
 *
 * ⚠⚠ THIS IS WHAT TOOK THE ISLAND DOWN (28.09). Opening the 2271-member beta
 * room asked every member with a picture for their profile key, one sealed
 * `pkeyask` each, and each seal to somebody outside the roster first read their
 * card and their device list. Every one of those was launched at once on the IO
 * pool, so one phone put 29 `/users/{uin}/info` and as many `/keys/{uin}/devices`
 * on the island inside a second. On the server each of those holds a pool
 * connection while it waits for a second one (a settings or guest cache
 * refresh), and 29 of them at once is more than the whole pool: every worker
 * stalled for 2 to 11 minutes, 24 times in 30 days. The asks are gone now (see
 * [app.rcq.android.data.ProfileKeyAsk]); this gate is the guard for the next
 * loop nobody notices, and it costs a lone lookup nothing.
 *
 * ★ Queued, never dropped: kotlinx's [Semaphore] is fair, so a burst drains in
 * arrival order at [parallel] at a time and every caller still gets its answer.
 * The wait happens before the request is built, so OkHttp's own timeouts only
 * start once the call is actually on the wire.
 */
class PeerLookupGate(private val parallel: Int = PARALLEL) {
    private val gates = ConcurrentHashMap<String, Semaphore>()

    suspend fun <T> run(host: String, block: suspend () -> T): T =
        gates.getOrPut(host.lowercase()) { Semaphore(parallel) }.withPermit { block() }

    companion object {
        /** Four: a send to a peer with two installs needs a list and two
         *  bundles, and a person opening a card should not wait behind a
         *  background burst for long. */
        const val PARALLEL = 4

        /** The one every [RcqApi] uses. */
        val shared = PeerLookupGate()
    }
}

/**
 * One request per key at a time: a caller that arrives while the same lookup
 * is already on the wire waits for that answer instead of sending its own.
 * Nothing is kept once the call returns; the caching belongs to the caller,
 * which knows how long its answer stays true.
 *
 * ⚠ The key must carry everything the answer depends on. Session keys these by
 * account epoch, number AND the entry's [CacheGenerations] count, so a lookup
 * started for one account is never handed to a caller on the account switched
 * to while it was in flight, and a read started before the cache was declared
 * stale is never handed to a caller who arrived after that.
 *
 * A failure reaches every waiter and is not remembered: the next call asks
 * again, which is what a timeout during a bad minute has to mean.
 *
 * ⚠ Except the leader being CANCELLED, which is not a failure of the lookup at
 * all: the composer's scope that sent the first message went away, say. A
 * waiter that is still running then takes the read over itself (one of them;
 * the rest join it), exactly as it would have run its own read before there
 * was any sharing. Only a waiter's OWN cancellation ends it.
 */
class SingleFlight<K : Any, V> {
    private val flights = ConcurrentHashMap<K, CompletableDeferred<V>>()

    /** How a cancelled leader's flight ends for the callers waiting on it.
     *  Not a CancellationException: awaited, that would read to a waiter's
     *  own coroutine as "you were cancelled" and end it quietly. Never leaves
     *  this class. */
    private class LeaderCancelled : Exception("peer lookup leader cancelled")

    suspend fun run(key: K, block: suspend () -> V): V {
        while (true) {
            val mine = CompletableDeferred<V>()
            val running = flights.putIfAbsent(key, mine)
            if (running != null) {
                try {
                    return running.await()
                } catch (_: LeaderCancelled) {
                    // Somebody else's cancellation: go round and lead (or join
                    // whoever got there first).
                    continue
                }
            }
            val v = try {
                block()
            } catch (e: Throwable) {
                // Out of the map BEFORE the waiters wake, so the one that takes
                // over finds the key free instead of this finished flight.
                flights.remove(key, mine)
                mine.completeExceptionally(if (e is CancellationException) LeaderCancelled() else e)
                throw e
            }
            flights.remove(key, mine)
            mine.complete(v)
            return v
        }
    }

    /** How many lookups are on the wire right now (tests). */
    val inFlight: Int get() = flights.size
}

/**
 * A change count per key, for a cache whose entries can be declared stale
 * while a read for them is still on the wire.
 *
 * ⚠ Why. `Session.peerDevices` drops a peer's cached device list the moment a
 * message arrives from an install the list does not have. With [SingleFlight]
 * alone, a send made right after that joined a read that had started BEFORE
 * the drop, sealed to the old list (the new install got nothing), and cached
 * that list for the full TTL. So: read [of] before the lookup and put it in the
 * [SingleFlight] key, invalidate through [invalidate]/[invalidateAll], and
 * write the answer back through [store], which refuses an answer the cache was
 * told is stale while it was being fetched.
 */
class CacheGenerations<K : Any> {
    private val perKey = ConcurrentHashMap<K, Long>()
    private val all = AtomicLong(0)

    /** Read BEFORE the lookup starts. Both counts only ever grow, so their sum
     *  moves on every bump of either. */
    fun of(key: K): Long = all.get() + (perKey[key] ?: 0L)

    /** Drop [key] from [cache]. ⚠ The bump comes first: a [store] racing this
     *  either has its write removed here or sees the bump on its re-check. */
    fun <V> invalidate(cache: MutableMap<K, V>, key: K) {
        perKey.merge(key, 1L, Long::plus)
        cache.remove(key)
    }

    /** Drop every entry of [cache] (an account switch, a new identity). */
    fun <V> invalidateAll(cache: MutableMap<K, V>) {
        all.incrementAndGet()
        cache.clear()
    }

    /** Put [value] under [key] unless the entry was invalidated since [gen]
     *  was read. False when it was: the caller still has its answer, it just
     *  is not remembered for anybody else. */
    fun <V : Any> store(cache: ConcurrentHashMap<K, V>, key: K, gen: Long, value: V): Boolean {
        if (of(key) != gen) return false
        cache[key] = value
        // An invalidation between the check and the write bumped the count
        // before its remove, so either that remove ran after our write, or
        // this re-check sees the bump.
        if (of(key) != gen) {
            cache.remove(key, value)
            return false
        }
        return true
    }
}
