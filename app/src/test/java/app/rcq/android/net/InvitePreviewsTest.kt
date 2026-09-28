package app.rcq.android.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Invite cards of a pinned message: a burst that must not turn into twenty
 *  placeholders (#918, #1051). */
class InvitePreviewsTest {

    private fun card(id: Int) = RcqApi.GroupPreviewOut(id = id, name = "room $id", member_count = 3)

    @Test
    fun `a success is kept per island, a failure is not`() = runBlocking {
        val p = InvitePreviews()
        var calls = 0
        assertNull(p.get("", 57) { calls++; throw IOException("HTTP 502: bad gateway") })
        assertEquals("room 57", p.get("", 57) { calls++; card(57) }?.name)
        assertEquals("room 57", p.get("", 57) { calls++; card(99) }?.name)
        assertEquals(2, calls)
        assertEquals("room 57", p.cached("", 57)?.name)
        // Same number on another island is another room.
        assertNull(p.cached("is2.rcq.app", 57))
        p.clear()
        assertNull(p.cached("", 57))
    }

    @Test
    fun `twenty links never mean twenty requests in flight`() = runBlocking {
        val p = InvitePreviews(parallel = 3)
        val inFlight = AtomicInteger(0)
        var peak = 0
        (1..20).map { id ->
            async {
                p.get("", id) {
                    val now = inFlight.incrementAndGet()
                    synchronized(this@InvitePreviewsTest) { peak = maxOf(peak, now) }
                    delay(20)
                    inFlight.decrementAndGet()
                    card(id)
                }
            }
        }.awaitAll()
        assertTrue("peak $peak", peak <= 3)
        assertEquals("room 20", p.cached("", 20)?.name)
    }

    @Test
    fun `a 429 is believed for that island only`() = runBlocking {
        // A frozen clock: the cool-down is then exactly the second the island asked for.
        val clock = 1_000_000L
        val p = InvitePreviews(now = { clock })
        assertNull(p.get("", 1) {
            throw IOException("HTTP 429: {\"detail\":{\"code\":\"rate_limited\",\"retry_after\":1}}")
        })
        // Another island is not held back by our own island's limit.
        val t0 = System.nanoTime()
        assertEquals("room 2", p.get("is2.rcq.app", 2) { card(2) }?.name)
        assertTrue((System.nanoTime() - t0) < 500_000_000L)
        // Our own island waits the second out before it asks again.
        val t1 = System.nanoTime()
        assertEquals("room 1", p.get("", 1) { card(1) }?.name)
        assertTrue((System.nanoTime() - t1) >= 900_000_000L)
    }

    @Test
    fun `a room that is gone is not asked about again for a while`() = runBlocking {
        var clock = 1_000_000L
        val p = InvitePreviews(now = { clock })
        var calls = 0
        assertNull(p.get("", 9) { calls++; throw IOException("HTTP 404: no such group") })
        assertNull(p.get("", 9) { calls++; card(9) })
        assertEquals(1, calls)
        clock += InvitePreviews.GONE_MS + 1
        assertEquals("room 9", p.get("", 9) { calls++; card(9) }?.name)
        assertEquals(2, calls)
    }

    @Test
    fun `retry-after is read from the refusal and bounded`() {
        assertEquals(37_000L, InvitePreviews.retryAfterMs(IOException("HTTP 429: {\"detail\":{\"retry_after\": 37}}")))
        assertEquals(10_000L, InvitePreviews.retryAfterMs(IOException("HTTP 429: slow down")))
        assertEquals(60_000L, InvitePreviews.retryAfterMs(IOException("HTTP 429: {\"retry_after\":3600}")))
        assertNull(InvitePreviews.retryAfterMs(IOException("HTTP 404: no such group")))
        assertNull(InvitePreviews.retryAfterMs(IOException("timeout")))
    }
}
