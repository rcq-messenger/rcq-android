package app.rcq.android.net

import app.rcq.android.net.BurnCascade.IslandBurnResult
import app.rcq.android.net.BurnCascade.RecoverAnswer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * F2 ordering, over a fake session and a fake transport: what runs before
 * what in Phases H and W and in the wipe PIN. No request leaves the JVM.
 */
class BurnPhasesTest {

    /** A session whose stores are a flag and whose calls are a log. */
    private class FakeHost(
        var homeAnswers: ArrayDeque<Boolean> = ArrayDeque(listOf(true)),
        var siblingsResult: IslandBurnResult = IslandBurnResult.Confirmed(1),
    ) : BurnPhases.Host {
        val log = mutableListOf<String>()
        var storesReadable = true
        var storesReadableAtHomeDelete: Boolean? = null

        override suspend fun burnDecoy(): Int? {
            log += "decoy"
            return 9
        }

        override suspend fun deleteHome(): Boolean {
            log += "home"
            storesReadableAtHomeDelete = storesReadable
            return homeAnswers.removeFirstOrNull() ?: false
        }

        override suspend fun burnHomeSiblings(): IslandBurnResult {
            log += "homeSiblings"
            assertTrue("keys are needed to prove the sibling rows", storesReadable)
            return siblingsResult
        }

        override fun wipeSibling(id: String, uin: Int?) {
            log += "wipe:$id"
        }

        override suspend fun eraseActive(): Int? {
            log += "erase"
            storesReadable = false
            return 42
        }
    }

    private fun sib(id: String, onHome: Boolean) = BurnPhases.Sibling(id, id.hashCode() and 0xffff, "h.example", onHome)

    /** Counts every request; answers identity_not_found. */
    private class CountingTransport(private val hang: Boolean = false) : BurnCascade.Transport {
        val calls = AtomicInteger()
        val firstCallAt = Collections.synchronizedList(mutableListOf<Long>())

        override suspend fun deleteAccount(host: String, token: String): Int {
            firstCallAt += System.nanoTime()
            calls.incrementAndGet()
            if (hang) delay(60_000)
            return 204
        }

        override suspend fun recover(host: String, key: BurnCascade.BurnKey): RecoverAnswer {
            firstCallAt += System.nanoTime()
            calls.incrementAndGet()
            if (hang) delay(60_000)
            return RecoverAnswer.NotFound(AuthRefusal.IDENTITY_NOT_FOUND)
        }
    }

    private fun key() = BurnCascade.BurnKey(ByteArray(32) { 7 }, ByteArray(32) { 1 })

    @Test fun keys_and_stores_are_readable_when_the_home_delete_starts() = runBlocking {
        val h = FakeHost()
        val out = BurnPhases.finish(decoy = false, siblings = emptyList(), host = h)
        assertEquals(true, h.storesReadableAtHomeDelete)
        assertEquals(listOf("home", "erase"), h.log)
        assertEquals(BurnPhases.Outcome.Burned(42), out)
    }

    @Test fun a_home_failure_keeps_local_data_after_one_retry() = runBlocking {
        val h = FakeHost(homeAnswers = ArrayDeque(listOf(false, false)))
        val out = BurnPhases.finish(decoy = false, siblings = listOf(sib("s1", onHome = false)), host = h)
        assertEquals(BurnPhases.Outcome.HomeFailed, out)
        assertEquals(listOf("home", "home"), h.log)
        assertTrue(h.storesReadable)
        // One retry is enough when it lands.
        val g = FakeHost(homeAnswers = ArrayDeque(listOf(false, true)))
        assertTrue(BurnPhases.finish(false, emptyList(), g) is BurnPhases.Outcome.Burned)
    }

    @Test fun a_decoy_plans_nothing_and_sends_nothing() = runBlocking {
        val t = CountingTransport()
        val target = BurnCascade.BurnTarget("b.example", 5, listOf("tok"), listOf(key()))
        assertTrue(BurnPhases.remote(decoy = true, targets = listOf(target), transport = t).isEmpty())
        val h = FakeHost()
        val out = BurnPhases.finish(decoy = true, siblings = listOf(sib("s1", onHome = true)), host = h)
        assertEquals(listOf("decoy"), h.log)
        assertEquals(BurnPhases.Outcome.Burned(9), out)
        assertEquals(0, t.calls.get())
        // And the real path does reach the transport, so the count above means something.
        BurnPhases.remote(decoy = false, targets = listOf(target), transport = t)
        assertTrue(t.calls.get() > 0)
    }

    @Test fun same_key_siblings_are_removed_before_the_active_account() = runBlocking {
        val h = FakeHost()
        val out = BurnPhases.finish(false, listOf(sib("remote", onHome = false), sib("home", onHome = true)), h)
        assertEquals(listOf("home", "homeSiblings", "wipe:remote", "wipe:home", "erase"), h.log)
        assertEquals(BurnPhases.Outcome.Burned(42), out)
        // No home sibling: the home island is not asked a second time.
        val g = FakeHost()
        BurnPhases.finish(false, listOf(sib("remote", onHome = false)), g)
        assertEquals(listOf("home", "wipe:remote", "erase"), g.log)
    }

    @Test fun a_home_sibling_the_island_did_not_confirm_stays_on_the_device() = runBlocking {
        for (r in listOf(IslandBurnResult.Failed(BurnCascade.Reason.OFFLINE), IslandBurnResult.NotTried)) {
            val home = sib("home", onHome = true)
            val h = FakeHost(siblingsResult = r)
            val out = BurnPhases.finish(false, listOf(sib("remote", onHome = false), home), h)
            assertFalse("wipe:home" in h.log)
            assertTrue("wipe:remote" in h.log)
            assertEquals(listOf(home), (out as BurnPhases.Outcome.Burned).kept)
        }
        // Already gone counts as done.
        val h = FakeHost(siblingsResult = IslandBurnResult.AlreadyGone)
        BurnPhases.finish(false, listOf(sib("home", onHome = true)), h)
        assertTrue("wipe:home" in h.log)
    }

    @Test fun a_home_sibling_with_a_stale_token_is_found_by_recover() = runBlocking {
        // The session's burnHomeSiblings is BurnCascade over our island: the
        // stored token is refused, recover with our key opens the sibling row
        // (the active row is gone), and that row is deleted.
        var rows = 1
        val t = object : BurnCascade.Transport {
            override suspend fun deleteAccount(host: String, token: String): Int =
                if (token == "fresh" && rows > 0) { rows--; 204 } else 401

            override suspend fun recover(host: String, key: BurnCascade.BurnKey): RecoverAnswer =
                if (rows > 0) RecoverAnswer.Token("fresh") else RecoverAnswer.NotFound(AuthRefusal.IDENTITY_NOT_FOUND)
        }
        val r = BurnCascade.run(listOf(BurnCascade.BurnTarget("a.example", null, listOf("stale"), listOf(key()))), t)
        assertEquals(IslandBurnResult.Confirmed(1), r["a.example"])
        assertEquals(0, rows)
    }

    @Test fun the_wipe_pin_with_the_flag_off_reads_nothing_and_sends_nothing() = runBlocking {
        val snapshots = AtomicInteger()
        val launched = AtomicInteger()
        var wiped = false
        BurnPhases.wipeLocalFirst(
            alsoServer = false,
            snapshot = { snapshots.incrementAndGet(); BurnCascade.Snapshot(emptyList()) },
            wipeLocal = { wiped = true },
            launch = { launched.incrementAndGet() },
        )
        assertTrue(wiped)
        assertEquals(0, snapshots.get())
        assertEquals(0, launched.get())
    }

    @Test fun the_wipe_pin_with_the_flag_on_finishes_the_local_wipe_before_the_first_request() = runBlocking {
        val t = CountingTransport(hang = true)
        val priv = ByteArray(32) { 5 }
        val remote = BurnCascade.BurnTarget("b.example", 5, listOf("tok"), listOf(BurnCascade.BurnKey(priv, ByteArray(32) { 1 })))
        val snap = BurnCascade.Snapshot(listOf(BurnCascade.HomeTarget("a.example", "home-token")), listOf(remote))
        var wipedAt = 0L
        var job: kotlinx.coroutines.Job? = null
        val started = System.nanoTime()
        BurnPhases.wipeLocalFirst(
            alsoServer = true,
            snapshot = { snap },
            wipeLocal = {
                // Nothing may have been sent while the stores still exist.
                assertEquals(0, t.calls.get())
                wipedAt = System.nanoTime()
            },
            launch = { s ->
                job = BurnCascade.runDetached(
                    s,
                    deadlineMs = 300,
                    delete = { h -> t.deleteAccount(h.host, h.token) },
                    remote = { r -> BurnCascade.burnOne(r, t, retry = false) },
                )
            },
        )
        // The wipe did not wait for a transport that hangs.
        val returnedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("wipe blocked for $returnedMs ms", returnedMs < 250)
        withTimeout(3_000) { job!!.join() }
        assertTrue(wipedAt != 0L)
        assertTrue(t.firstCallAt.isNotEmpty())
        assertTrue(t.firstCallAt.all { it > wipedAt })
        // The snapshot lived in memory only: never serializable, keys zeroed at
        // the end, and no token in its text.
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(snap.javaClass))
        assertTrue(priv.all { it == 0.toByte() })
        assertFalse(snap.toString().contains("tok"))
    }
}
