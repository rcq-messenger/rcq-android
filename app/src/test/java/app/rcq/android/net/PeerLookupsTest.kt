package app.rcq.android.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The client half of the 28.09 island stalls: a room roster that fired 29 card
 *  reads and as many device lists at the island inside one second. */
class PeerLookupsTest {

    @Test
    fun `twenty-nine lookups to one island never put more than four on the wire`() = runBlocking {
        val gate = PeerLookupGate()
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        // On the IO pool, as Session's scope launches them: real parallelism,
        // not interleaving on one thread.
        val answers = (1..29).map { uin ->
            async(Dispatchers.IO) {
                gate.run("api.rcq.app") {
                    val now = inFlight.incrementAndGet()
                    peak.accumulateAndGet(now, ::maxOf)
                    delay(15)
                    inFlight.decrementAndGet()
                    uin
                }
            }
        }.awaitAll()
        assertTrue("peak ${peak.get()}", peak.get() <= PeerLookupGate.PARALLEL)
        // Queued, never dropped: every caller got its own answer.
        assertEquals((1..29).toList(), answers)
    }

    @Test
    fun `islands queue separately, and the host is compared without case`() = runBlocking {
        val gate = PeerLookupGate(parallel = 1)
        val aIn = CompletableDeferred<Unit>()
        val holdA = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.IO) { gate.run("api.rcq.app") { aIn.complete(Unit); holdA.await() } }
        aIn.await()
        // Another island is not behind api.rcq.app's queue...
        withTimeout(2_000) { gate.run("is2.rcq.app") { "is2" } }
        // ...but the same island spelled differently is.
        val sameRan = java.util.concurrent.atomic.AtomicBoolean(false)
        val same = launch(Dispatchers.IO) { gate.run("API.RCQ.APP") { sameRan.set(true) } }
        delay(100)
        assertTrue("ran past a full gate", !sameRan.get())
        holdA.complete(Unit)
        a.join(); same.join()
        assertTrue(sameRan.get())
    }

    @Test
    fun `a failed lookup gives its permit back`() = runBlocking {
        val gate = PeerLookupGate(parallel = 1)
        repeat(3) {
            try {
                gate.run("api.rcq.app") { throw IOException("HTTP 503: island_busy") }
                fail("should have thrown")
            } catch (_: IOException) {
            }
        }
        assertEquals("ok", withTimeout(2_000) { gate.run("api.rcq.app") { "ok" } })
    }

    @Test
    fun `callers that overlap on one key share one request`() = runBlocking {
        val flights = SingleFlight<Pair<Int, Int>, String?>()
        val calls = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val waiters = (1..10).map {
            async(Dispatchers.IO) {
                flights.run(1 to 555) {
                    calls.incrementAndGet()
                    release.await()
                    "ik-555"
                }
            }
        }
        // Let all ten reach the flight before it answers.
        while (flights.inFlight == 0) delay(5)
        delay(50)
        release.complete(Unit)
        assertEquals(List(10) { "ik-555" }, waiters.awaitAll())
        assertEquals(1, calls.get())
        // Nothing is kept afterwards: the next caller asks again.
        assertEquals(0, flights.inFlight)
        assertEquals("ik-555b", flights.run(1 to 555) { calls.incrementAndGet(); "ik-555b" })
        assertEquals(2, calls.get())
    }

    @Test
    fun `another number or another account epoch is another request`() = runBlocking {
        val flights = SingleFlight<Pair<Int, Int>, String?>()
        val calls = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val keys = listOf(1 to 555, 1 to 556, 2 to 555)
        val answers = keys.map { k ->
            async(Dispatchers.IO) {
                flights.run(k) { calls.incrementAndGet(); release.await(); "${k.first}:${k.second}" }
            }
        }
        while (flights.inFlight < 3) delay(5)
        release.complete(Unit)
        assertEquals(listOf("1:555", "1:556", "2:555"), answers.awaitAll())
        assertEquals(3, calls.get())
    }

    @Test
    fun `a failure reaches every waiter and is not remembered`() = runBlocking {
        val flights = SingleFlight<Pair<Int, Int>, String?>()
        val release = CompletableDeferred<Unit>()
        val waiters = (1..5).map {
            async(Dispatchers.IO) {
                runCatching {
                    flights.run(1 to 7) { release.await(); throw IOException("HTTP 404: no such user") }
                }
            }
        }
        while (flights.inFlight == 0) delay(5)
        delay(50)
        release.complete(Unit)
        val results = waiters.awaitAll()
        // The 404 itself, not a stand-in: peerDevices reads "HTTP 404" off it.
        assertTrue(results.all { it.exceptionOrNull()?.message?.startsWith("HTTP 404") == true })
        assertEquals("found", flights.run(1 to 7) { "found" })
    }

    @Test
    fun `a waiter whose leader is cancelled takes the read over, once for all of them`() = runBlocking {
        val flights = SingleFlight<Pair<Int, Int>, String?>()
        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        // The composer's scope that sent first, torn down as the user backs out.
        val owner = launch(Dispatchers.IO) {
            flights.run(1 to 9) { calls.incrementAndGet(); started.complete(Unit); awaitCancellation() }
        }
        started.await()
        val release = CompletableDeferred<Unit>()
        // A delivered receipt and a few queued messages to the same stranger.
        val waiters = (1..5).map {
            async(Dispatchers.IO) {
                flights.run(1 to 9) { calls.incrementAndGet(); release.await(); "ik-9" }
            }
        }
        delay(50)
        assertEquals("all five joined the owner's read", 1, calls.get())
        owner.cancelAndJoin()
        withTimeout(2_000) { while (calls.get() < 2) delay(5) }
        delay(50)
        release.complete(Unit)
        // Nobody got "lookup cancelled": the send and the receipt both went.
        assertEquals(List(5) { "ik-9" }, waiters.awaitAll())
        assertEquals("one read took over, the rest joined it", 2, calls.get())
        assertEquals(0, flights.inFlight)
    }

    @Test
    fun `a waiter's own cancellation still ends it, and the read goes on for the rest`() = runBlocking {
        val flights = SingleFlight<Pair<Int, Int>, String?>()
        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async(Dispatchers.IO) {
            flights.run(1 to 9) { calls.incrementAndGet(); started.complete(Unit); release.await(); "ik-9" }
        }
        started.await()
        val quitter = async(Dispatchers.IO) { flights.run(1 to 9) { calls.incrementAndGet(); "never" } }
        val stayer = async(Dispatchers.IO) { flights.run(1 to 9) { calls.incrementAndGet(); "never" } }
        delay(50)
        quitter.cancel()
        try {
            quitter.await()
            fail("a cancelled waiter must not get an answer")
        } catch (_: CancellationException) {
        }
        release.complete(Unit)
        assertEquals("ik-9", owner.await())
        assertEquals("ik-9", stayer.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun `a cancelled leader with nobody waiting leaves nothing behind`() = runBlocking {
        val flights = SingleFlight<Pair<Int, Int>, String?>()
        val started = CompletableDeferred<Unit>()
        val owner = launch(Dispatchers.IO) {
            flights.run(1 to 9) { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        owner.cancelAndJoin()
        assertEquals(0, flights.inFlight)
        assertEquals("fresh", withTimeout(2_000) { flights.run(1 to 9) { "fresh" } })
    }

    /** Session.peerDevices in miniature: a cache in front of a shared read,
     *  keyed and written back the way Session does it. */
    private class DeviceLists(private val read: suspend (Int) -> List<Int>) {
        val cache = ConcurrentHashMap<Int, List<Int>>()
        val gens = CacheGenerations<Int>()
        val flights = SingleFlight<Triple<Int, Int, Long>, List<Int>>()

        suspend fun devices(uin: Int): List<Int> {
            cache[uin]?.let { return it }
            val gen = gens.of(uin)
            val list = flights.run(Triple(1, uin, gen)) { read(uin) }
            gens.store(cache, uin, gen, list)
            return list
        }

        /** A message from an install the list does not have. */
        fun newDeviceSeen(uin: Int) = gens.invalidate(cache, uin)
    }

    @Test
    fun `a send after the new-device signal does not join the read from before it`() = runBlocking {
        // The island's truth for peer 555: one install, until a second links.
        val island = ConcurrentHashMap(mapOf(555 to listOf(1)))
        val reads = AtomicInteger(0)
        val firstReadIn = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val lists = DeviceLists { uin ->
            val n = reads.incrementAndGet()
            val answer = island.getValue(uin)
            if (n == 1) { firstReadIn.complete(Unit); releaseFirst.await() }
            answer
        }
        // A send to 555 with the cache cold: its list read is on the wire.
        val before = async(Dispatchers.IO) { lists.devices(555) }
        firstReadIn.await()
        // Install 2 links and writes to us; ingest sees an unknown device id.
        island[555] = listOf(1, 2)
        lists.newDeviceSeen(555)
        // Our reply goes out now, while the old read is still in flight.
        val after = withTimeout(2_000) { lists.devices(555) }
        assertEquals("sealed to the new install too", listOf(1, 2), after)
        assertEquals(2, reads.get())
        // The old read lands late: its caller gets what it asked for...
        releaseFirst.complete(Unit)
        assertEquals(listOf(1), before.await())
        // ...and it is not what the next send finds.
        assertEquals(listOf(1, 2), lists.cache[555])
        assertEquals(listOf(1, 2), lists.devices(555))
        assertEquals(2, reads.get())
    }

    @Test
    fun `a read that was on the wire across a drop is not written back`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lists = DeviceLists { started.complete(Unit); release.await(); listOf(1) }
        val one = async(Dispatchers.IO) { lists.devices(555) }
        started.await()
        // An account switch, a burn or a new identity drops everything.
        lists.gens.invalidateAll(lists.cache)
        release.complete(Unit)
        assertEquals(listOf(1), one.await())
        assertTrue("stale list cached: ${lists.cache}", lists.cache.isEmpty())
    }

    @Test
    fun `generations count per number, and an untouched entry is stored as usual`() {
        val gens = CacheGenerations<Int>()
        val cache = ConcurrentHashMap<Int, String>()
        val g555 = gens.of(555)
        val g556 = gens.of(556)
        gens.invalidate(cache, 556)
        assertTrue(gens.store(cache, 555, g555, "a"))
        assertTrue("dropped since it was read", !gens.store(cache, 556, g556, "b"))
        assertEquals(mapOf(555 to "a"), cache)
        assertTrue(gens.store(cache, 556, gens.of(556), "b"))
        val g = gens.of(555)
        gens.invalidateAll(cache)
        assertTrue(cache.isEmpty())
        assertTrue(!gens.store(cache, 555, g, "a"))
        assertTrue(gens.of(555) != g && gens.of(556) != g556)
    }
}
