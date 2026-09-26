package app.rcq.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [LockoutClock]: the lockout neither lifts nor stretches with the clock in
 *  Settings, and a reboot, announced or not, never leaves more than a step. */
class LockoutClockTest {
    private val hour = 3_600_000L
    private val wall = 1_790_000_000_000L

    @Test fun sameBootCountsDownOnTheMonotonicClock() {
        // Locked at uptime 10 min for an hour; 20 minutes later.
        val r = LockoutClock.read(wall + hour, 600_000 + hour, 7, hour, wall + 1_200_000, 1_800_000, 7)
        assertEquals(LockoutClock.Read(hour - 1_200_000, reanchor = false), r)
    }

    @Test fun clockMovedForwardDoesNotLiftIt() {
        val r = LockoutClock.read(wall + hour, 600_000 + hour, 7, hour, wall + 30 * 24 * hour, 660_000, 7)
        assertEquals(hour - 60_000, r!!.leftMs)
    }

    @Test fun clockMovedBackDoesNotStretchIt() {
        val r = LockoutClock.read(wall + hour, 600_000 + hour, 7, hour, wall - 30 * 24 * hour, 660_000, 7)
        assertEquals(hour - 60_000, r!!.leftMs)
    }

    @Test fun overOnTheSameBoot() {
        assertNull(LockoutClock.read(wall + hour, 600_000 + hour, 7, hour, wall + 2 * hour, 600_000 + hour + 1, 7))
    }

    @Test fun announcedRebootUsesTheWallClockCappedAndReanchors() {
        // Rebooted (counter 7 -> 8), 10 minutes of lockout left by the wall clock.
        val r = LockoutClock.read(wall + hour, 10 * 24 * hour + hour, 7, hour, wall + hour - 600_000, 30_000, 8)
        assertEquals(LockoutClock.Read(600_000, reanchor = true), r)
    }

    @Test fun unannouncedRebootDoesNotLockForTheOldUptime() {
        // Ten days of uptime at the 1 h step, rebooted, counter not bumped yet:
        // the old monotonic deadline is ten days away on the new uptime.
        val r = LockoutClock.read(wall + hour, 10 * 24 * hour + hour, 7, hour, wall + hour - 600_000, 30_000, 7)
        assertEquals(LockoutClock.Read(600_000, reanchor = true), r)
    }

    @Test fun unannouncedRebootWithTheWallClockBackIsStillOneStepAtMost() {
        val r = LockoutClock.read(wall + hour, 10 * 24 * hour + hour, 7, hour, wall - 5 * 24 * hour, 30_000, 7)
        assertEquals(LockoutClock.Read(hour, reanchor = true), r)
    }

    @Test fun noBootCounterStillUsesTheMonotonicDeadline() {
        val r = LockoutClock.read(wall + hour, 600_000 + hour, null, hour, wall + 30 * 24 * hour, 660_000, null)
        assertEquals(LockoutClock.Read(hour - 60_000, reanchor = false), r)
    }

    @Test fun recordFromBeforeTheMonotonicFieldIsCappedAndReanchored() {
        val r = LockoutClock.read(wall + 5 * hour, null, null, hour, wall, 30_000, 7)
        assertEquals(LockoutClock.Read(hour, reanchor = true), r)
    }

    @Test fun noLockoutBelowTheFirstStep() {
        assertNull(LockoutClock.read(wall + hour, hour, 7, 0, wall, 0, 7))
    }
}
