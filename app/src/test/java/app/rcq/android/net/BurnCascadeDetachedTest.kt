package app.rcq.android.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0.3: the wipe PIN's server deletes run detached, after the local wipe, and
 * never block it. The transport is injected, so no request leaves the JVM.
 */
class BurnCascadeDetachedTest {

    private fun snapshot(n: Int) = BurnCascade.Snapshot((1..n).map { BurnCascade.HomeTarget("h$it.example", "token-$it") })

    @Test
    fun anEmptySnapshotSendsNothing() {
        val calls = AtomicInteger()
        val job = BurnCascade.runDetached(BurnCascade.Snapshot(emptyList()), delete = { calls.incrementAndGet() })
        assertNull(job)
        assertEquals(0, calls.get())
    }

    @Test
    fun returnsAtOnceWhileTheIslandHangsAndStopsAtTheDeadline() = runBlocking {
        val finished = AtomicBoolean(false)
        val started = System.nanoTime()
        val job = BurnCascade.runDetached(
            snapshot(3),
            deadlineMs = 300,
            delete = { delay(60_000) },
            onFinished = { finished.set(true) },
        )!!
        val returnedAfterMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("runDetached blocked for $returnedAfterMs ms", returnedAfterMs < 250)
        assertFalse(job.isCompleted)
        withTimeout(3_000) { job.join() }
        assertTrue(finished.get())
    }

    @Test
    fun oneFailingIslandDoesNotStopTheOthers() = runBlocking {
        val calls = AtomicInteger()
        val finished = AtomicBoolean(false)
        val job = BurnCascade.runDetached(
            snapshot(3),
            delete = { t ->
                calls.incrementAndGet()
                if (t.host.startsWith("h1")) throw java.io.IOException("offline")
            },
            onFinished = { finished.set(true) },
        )!!
        withTimeout(3_000) { job.join() }
        assertEquals(3, calls.get())
        assertTrue(finished.get())
    }

    @Test
    fun aTransportThatIgnoresCancellationDoesNotStretchTheDeadline() = runBlocking {
        // A blocking execute() does not see coroutine cancellation. The wait
        // must end at the deadline anyway, not when the thread gives up.
        val finishedAt = java.util.concurrent.atomic.AtomicLong(0)
        val started = System.nanoTime()
        val job = BurnCascade.runDetached(
            snapshot(2),
            deadlineMs = 300,
            delete = { Thread.sleep(5_000) },
            onFinished = { finishedAt.set(System.nanoTime()) },
        )!!
        withTimeout(3_000) { job.join() }
        val tookMs = (finishedAt.get() - started) / 1_000_000
        assertTrue("onFinished after $tookMs ms", finishedAt.get() != 0L && tookMs < 1_000)
    }

    private val pinFp = "a".repeat(64)
    private val otherFp = "b".repeat(64)

    @Test
    fun anIslandWithNoAuthorityAndNoPinOnFileIsNeverTrusted() {
        // After the wipe this is exactly what a man in the middle presents: live,
        // it would be a first use and the bearer token would follow.
        assertFalse(BurnCascade.detachedTrusts(null, caOnly = false, caValid = false, leafFp = otherFp))
        // A `ca` record never takes a self-signed leaf either.
        val ca = IslandTrust.Record(IslandTrust.Mode.CA)
        assertFalse(BurnCascade.detachedTrusts(ca, caOnly = false, caValid = false, leafFp = otherFp))
        assertTrue(BurnCascade.detachedTrusts(ca, caOnly = false, caValid = true, leafFp = otherFp))
    }

    @Test
    fun aPinOnFileIsHonouredTheWayTheLiveRuleHonoursIt() {
        val tofu = IslandTrust.Record(IslandTrust.Mode.PINNED, fp = pinFp, source = IslandTrust.Source.TOFU)
        assertTrue(BurnCascade.detachedTrusts(tofu, caOnly = false, caValid = false, leafFp = pinFp))
        assertFalse(BurnCascade.detachedTrusts(tofu, caOnly = false, caValid = false, leafFp = otherFp))
        // A typed fingerprint wins over an authority, as live.
        val typed = IslandTrust.Record(IslandTrust.Mode.PINNED, fp = pinFp, source = IslandTrust.Source.TYPED)
        assertFalse(BurnCascade.detachedTrusts(typed, caOnly = false, caValid = true, leafFp = otherFp))
        assertTrue(BurnCascade.detachedTrusts(typed, caOnly = false, caValid = false, leafFp = pinFp))
        // A host that is never pinned takes an authority and nothing else.
        assertFalse(BurnCascade.detachedTrusts(tofu, caOnly = true, caValid = false, leafFp = pinFp))
        assertTrue(BurnCascade.detachedTrusts(null, caOnly = true, caValid = true, leafFp = otherFp))
    }

    @Test
    fun aTokenNeverReachesToString() {
        val s = snapshot(2)
        assertFalse(s.toString().contains("token"))
        assertFalse(s.homes.first().toString().contains("token"))
        assertFalse(s.homes.first().toString().contains("h1"))
    }
}
