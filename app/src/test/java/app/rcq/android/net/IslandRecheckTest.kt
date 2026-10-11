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
        gate.finished("roster:5", answered = true)
        assertEquals(IslandRecheck.Kind.DECIDE, gate.step("roster:5", "7").kind)
    }

    @Test fun a_read_that_started_before_the_row_was_held_does_not_answer_it() {
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("roster:5", "7").kind)
        now += 1_000
        // Member 8 posts while the read for 7 is on the wire.
        assertEquals(IslandRecheck.Kind.WAIT, gate.step("roster:5", "8").kind)
        now += 1_000
        gate.finished("roster:5", answered = true)
        assertEquals(IslandRecheck.Kind.DECIDE, gate.step("roster:5", "7").kind)
        // 8 needs a read of its own, spaced from the last one.
        val next = gate.step("roster:5", "8")
        assertEquals(IslandRecheck.Kind.FETCH, next.kind)
        assertTrue(next.delayMs > 0)
        now += next.delayMs + 500
        gate.finished("roster:5", answered = true)
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
        val d = gate.step("ident:7", "1:x")
        assertEquals(IslandRecheck.Kind.DECIDE, d.kind)
        // Decided on the clock, not on the island's word.
        assertFalse(d.answered)
        assertEquals(1, d.unheardRounds)
    }

    @Test fun a_read_the_island_answered_says_so() {
        gate.step("roster:5", "7")
        gate.finished("roster:5", answered = true)
        val d = gate.step("roster:5", "7")
        assertEquals(IslandRecheck.Kind.DECIDE, d.kind)
        assertTrue(d.answered)
        assertEquals(0, d.unheardRounds)
    }

    @Test fun a_failed_read_is_not_an_answer_and_spaces_the_next_one() {
        // Bob joined and posted; the roster read times out.
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("roster:5", "9").kind)
        now += 1_000
        gate.finished("roster:5", answered = false)
        // Not decided against a roster the island never served: read again,
        // spaced from the failed attempt, not at once and not in a loop.
        val again = gate.step("roster:5", "9")
        assertEquals(IslandRecheck.Kind.FETCH, again.kind)
        assertEquals(19_000L, again.delayMs)
        assertEquals(IslandRecheck.Kind.WAIT, gate.step("roster:5", "9").kind)
        now += again.delayMs + 500
        gate.finished("roster:5", answered = true)
        val d = gate.step("roster:5", "9")
        assertEquals(IslandRecheck.Kind.DECIDE, d.kind)
        assertTrue(d.answered)
    }

    @Test fun every_read_failing_ends_on_the_clock_and_counts_the_rounds() {
        repeat(2) { round ->
            var d = gate.step("card:7", "k")
            val start = now
            while (d.kind != IslandRecheck.Kind.DECIDE) {
                if (d.kind == IslandRecheck.Kind.FETCH) {
                    now += d.delayMs + 1_000
                    gate.finished("card:7", answered = false)
                } else {
                    now += 1_000
                }
                d = gate.step("card:7", "k")
            }
            assertFalse(d.answered)
            assertEquals(round + 1, d.unheardRounds)
            assertTrue(now - start >= 300_000L)
        }
        // One read that answers resets the count.
        val f = gate.step("card:7", "k")
        assertEquals(IslandRecheck.Kind.FETCH, f.kind)
        now += f.delayMs + 1_000
        gate.finished("card:7", answered = true)
        assertTrue(gate.step("card:7", "k").answered)
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("card:7", "k").kind)
        now += 300_000L
        assertEquals(1, gate.step("card:7", "k").unheardRounds)
    }

    @Test fun only_a_4xx_naming_the_subject_is_an_answer() {
        assertTrue(IslandRecheck.failureIsAnswer(java.io.IOException("HTTP 404: no such user")))
        assertTrue(IslandRecheck.failureIsAnswer(java.io.IOException("HTTP 403: not a member")))
        assertFalse(IslandRecheck.failureIsAnswer(java.io.IOException("HTTP 401: token expired")))
        assertFalse(IslandRecheck.failureIsAnswer(java.io.IOException("HTTP 408")))
        assertFalse(IslandRecheck.failureIsAnswer(java.io.IOException("HTTP 429: slow down")))
        assertFalse(IslandRecheck.failureIsAnswer(java.io.IOException("HTTP 502")))
        assertFalse(IslandRecheck.failureIsAnswer(java.net.SocketTimeoutException("timeout")))
        assertFalse(IslandRecheck.failureIsAnswer(RuntimeException()))
    }

    @Test fun a_flood_costs_one_read_per_gap() {
        assertEquals(IslandRecheck.Kind.FETCH, gate.step("card:7", "k1").kind)
        gate.finished("card:7", answered = true)
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

    @Test fun a_row_the_island_never_answered_for_strikes_and_is_kept_a_few_drains() {
        for (tag in listOf(GroupLogPage.ROSTER_UNREACHABLE, GroupLogPage.KEY_UNREACHABLE, GroupLogPage.IDENTITY_UNREACHABLE)) {
            assertFalse(tag in GroupLogPage.WAITING_ON_ISLAND)
            assertTrue(GroupLogPage.strikes(tag))
            var prev: String? = null
            repeat(GroupLogPage.FAIL_DRAINS - 1) {
                val fate = GroupLogPage.fate(prev, tag)
                assertFalse("kept while the island may still answer", fate.done)
                prev = fate.record
            }
            assertTrue(GroupLogPage.fate(prev, tag).done)
        }
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
