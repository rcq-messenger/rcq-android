package app.rcq.android.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1: how often a visited island is asked for the requests addressed to our
 * guest copy. The island allows 120 asks a minute; the schedule must stay at
 * one per five minutes or slower, and never inside a Retry-After.
 */
class PendingPollScheduleTest {

    private val k = "100@b.example"
    private val min = 60_000L

    @Test fun a_host_never_asked_is_due_at_once() {
        assertTrue(PendingPollSchedule().due(k, 0L))
    }

    @Test fun an_answer_waits_five_minutes_within_twenty_percent() {
        for ((r, wait) in listOf(0.0 to 4 * min, 0.5 to 5 * min, 1.0 to 6 * min)) {
            val s = PendingPollSchedule { r }
            s.onResult(k, 0L, ok = true)
            assertFalse("r=$r", s.due(k, wait - 1))
            assertTrue("r=$r", s.due(k, wait))
        }
    }

    @Test fun failures_back_off_and_an_answer_resets() {
        val s = PendingPollSchedule { 0.5 }
        var now = 0L
        for (step in listOf(5, 10, 20, 40, 60, 60).map { it * min }) {
            s.onResult(k, now, ok = false)
            assertFalse(s.due(k, now + step - 1))
            assertTrue(s.due(k, now + step))
            now += step
        }
        s.onResult(k, now, ok = true)
        assertFalse(s.due(k, now + 5 * min - 1))
        assertTrue(s.due(k, now + 5 * min))
        // The next failure starts from the first step again.
        now += 5 * min
        s.onResult(k, now, ok = false)
        assertTrue(s.due(k, now + 5 * min))
    }

    @Test fun retry_after_is_honoured_and_never_shorter_than_five_minutes() {
        val short = PendingPollSchedule { 0.5 }
        short.onResult(k, 0L, ok = false, retryAfterSec = 10)
        assertFalse(short.due(k, 5 * min - 1))
        assertTrue(short.due(k, 5 * min))

        val long = PendingPollSchedule { 0.5 }
        long.onResult(k, 0L, ok = false, retryAfterSec = 20 * 60)
        assertFalse(long.due(k, 20 * min - 1))
        assertTrue(long.due(k, 20 * min))
    }

    @Test fun force_is_debounced_to_once_a_minute() {
        val s = PendingPollSchedule { 0.5 }
        s.onResult(k, 0L, ok = true)
        assertFalse(s.due(k, 1_000L))
        assertTrue(s.forceSoon(k, 1_000L))
        assertTrue(s.due(k, 1_000L))
        s.onResult(k, 1_000L, ok = true)
        assertFalse(s.forceSoon(k, 30_000L))
        assertFalse(s.due(k, 30_000L))
        assertTrue(s.forceSoon(k, 61_000L))
        assertTrue(s.due(k, 61_000L))
    }

    @Test fun force_never_breaks_a_retry_after() {
        val s = PendingPollSchedule { 0.5 }
        s.onResult(k, 0L, ok = false, retryAfterSec = 600)
        assertFalse(s.forceSoon(k, 1_000L))
        assertFalse(s.due(k, 1_000L))
        assertTrue(s.due(k, 10 * min))
    }

    @Test fun two_accounts_do_not_share_a_clock() {
        val s = PendingPollSchedule { 0.5 }
        s.onResult("100@b.example", 0L, ok = true)
        assertFalse(s.due("100@b.example", 1_000L))
        assertTrue(s.due("200@b.example", 1_000L))
    }
}
