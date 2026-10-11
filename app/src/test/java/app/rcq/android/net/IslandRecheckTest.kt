package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Ask the island once more before deciding against a row": a row is decided
 * only on a read that started after it was held (a roster fetched a second
 * before somebody joined must not drop their first post), one read per
 * resource at a time, reads of one resource spaced out, and nothing waits for
 * ever. Plus the drain side: a row waiting on that read books no strike.
 */
class IslandRecheckTest {

    private var now = 1_000_000L
    private val gate = IslandRecheck(minGapMs = 20_000L, maxWaitMs = 300_000L) { now }

    @Test fun a_held_row_waits_for_one_read_and_is_then_decided() {
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("roster:5", "7").kind)
        now += 100
        assertEquals(IslandRecheck.Kind.WAIT, gate.step("roster:5", "7").kind)
        now += 100
        gate.finished("roster:5")
        assertEquals(IslandRecheck.Kind.DECIDE, gate.step("roster:5", "7").kind)
    }

    @Test fun a_read_that_started_before_the_row_was_held_does_not_answer_it() {
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("roster:5", "7").kind)
        now += 1_000
        // Member 8 posts while the read for 7 is on the wire.
        assertEquals(IslandRecheck.Kind.WAIT, gate.step("roster:5", "8").kind)
        now += 1_000
        gate.finished("roster:5")
        assertEquals(IslandRecheck.Kind.DECIDE, gate.step("roster:5", "7").kind)
        // 8 needs a read of its own, spaced from the last one.
        val next = gate.step("roster:5", "8")
        assertEquals(IslandRecheck.Kind.FETCH, next.kind)
        assertTrue(next.delayMs > 0)
        now += next.delayMs + 500
        gate.finished("roster:5")
        assertEquals(IslandRecheck.Kind.DECIDE, gate.step("roster:5", "8").kind)
    }

    @Test fun resources_are_independent() {
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("card:7", "k").kind)
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("card:8", "k").kind)
        assertEquals(0L, gate.step("roster:5", "7").delayMs)
    }

    @Test fun nothing_waits_for_ever() {
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("ident:7", "1:x").kind)
        // The read never finishes (a dead island, a killed coroutine).
        now += 300_000L
        assertEquals(IslandRecheck.Kind.DECIDE, gate.step("ident:7", "1:x").kind)
    }

    @Test fun a_flood_costs_one_read_per_gap() {
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("card:7", "k1").kind)
        gate.finished("card:7")
        now += 1_000
        val second = gate.step("card:7", "k2")
        assertEquals(IslandRecheck.Kind.FETCH, second.kind)
        assertEquals(19_000L, second.delayMs)
        // Every other forged key meanwhile waits on that one read.
        assertEquals(IslandRecheck.Kind.WAIT, gate.step("card:7", "k3").kind)
        assertEquals(IslandRecheck.Kind.WAIT, gate.step("card:7", "k4").kind)
    }

    @Test fun clear_forgets_everything() {
        gate.step("card:7", "k")
        gate.clear()
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("card:7", "k").kind)
    }

    @Test fun a_row_waiting_on_the_island_books_no_strike() {
        for (tag in GroupLogPage.WAITING_ON_ISLAND) {
            assertFalse(GroupLogPage.strikes(tag))
            val fate = GroupLogPage.fate("2:$tag", tag)
            assertFalse(fate.done)
            assertEquals("2:$tag", fate.record)
        }
        assertTrue(GroupLogPage.strikes("identity_refused"))
        assertTrue(GroupLogPage.strikes("NoSessionException"))
    }

    @Test fun a_same_island_request_is_retracted_only_under_its_own_key() {
        assertTrue(CrossIslandRequestsStore.sameIslandRetracts("KEY-A", "KEY-A"))
        assertFalse(CrossIslandRequestsStore.sameIslandRetracts("KEY-A", "KEY-B"))
        // A v=2 delete (vouched by our island), and rows held under no key.
        assertTrue(CrossIslandRequestsStore.sameIslandRetracts("KEY-A", ""))
        assertTrue(CrossIslandRequestsStore.sameIslandRetracts("", "KEY-B"))
        assertTrue(CrossIslandRequestsStore.sameIslandRetracts(null, "KEY-B"))
    }
}
