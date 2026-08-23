package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Stage 5 drain rules the review of the first Android commit turned on:
 * a page's ack stops short of the first row of a room that may still pass,
 * and only there; a row that fails the same way on three drains is written
 * off; a live frame is acked only as the next seq, a gap is left to the
 * drain; and a drain that ran to its end levels a room with its HEAD, not
 * with the last seq it was served, so the rule can fire at all in a room
 * that holds one row sealed to somebody else.
 */
class GroupLogPageTest {

    @Test fun ackStopsShortOfTheFirstFailedRowPerRoom() {
        val a = GroupLogPage.Acks()
        a.row(7, 41, done = true)
        a.row(7, 42, done = false)   // may pass next time
        a.row(7, 43, done = true)    // ingested all the same, not acked past
        a.row(9, 3, done = true)
        assertEquals(41L, a.upto[7])
        assertEquals(3L, a.upto[9])
        assertEquals(setOf(7), a.blocked)
    }

    @Test fun aRoomFailingOnItsFirstRowIsNotAckedAtAll() {
        val a = GroupLogPage.Acks()
        a.row(7, 41, done = false)
        a.row(7, 42, done = true)
        assertNull(a.upto[7])
        assertTrue(7 in a.blocked)
    }

    @Test fun strikesCountOnlyTheSameFailure() {
        val s1 = GroupLogPage.strike(null, "NoSessionException")
        assertEquals(1, s1.count)
        assertFalse(s1.writtenOff)
        val s2 = GroupLogPage.strike(GroupLogPage.encode(s1), "NoSessionException")
        assertEquals(2, s2.count)
        assertFalse(s2.writtenOff)
        // A different failure starts over.
        val other = GroupLogPage.strike(GroupLogPage.encode(s2), "hold_full")
        assertEquals(1, other.count)
        val s3 = GroupLogPage.strike(GroupLogPage.encode(s2), "NoSessionException")
        assertEquals(GroupLogPage.FAIL_DRAINS, s3.count)
        assertTrue(s3.writtenOff)
    }

    @Test fun strikeSurvivesAColonInTheReason() {
        val s = GroupLogPage.strike("2:a:b", "a:b")
        assertEquals(3, s.count)
        assertTrue(s.writtenOff)
        assertEquals(1, GroupLogPage.strike("junk", "x").count)
    }

    @Test fun liveFrameIsAckedOnlyAsTheNextSeq() {
        assertTrue(GroupLogPage.liveAckable(41, 42))
        assertFalse("a gap is left to the drain", GroupLogPage.liveAckable(41, 44))
        assertFalse("a replay is not acked", GroupLogPage.liveAckable(41, 41))
        assertFalse("a room never fetched is left alone", GroupLogPage.liveAckable(null, 1))
    }

    @Test fun aCompleteDrainLevelsTheRoomWithItsHead() {
        // The page served seq 41 and 43 (42 is sealed to another member);
        // the island's head is 45 (44 and 45 are addressed elsewhere too).
        assertEquals(45L, GroupLogPage.levelAfterDrain(43, 45, blocked = false))
        assertTrue(GroupLogPage.liveAckable(45, 46))
        // A lower head never moves the baseline down.
        assertEquals(50L, GroupLogPage.levelAfterDrain(50, 45, blocked = false))
        // A room pinned below a failed row stays where its ack left it.
        assertEquals(41L, GroupLogPage.levelAfterDrain(41, 45, blocked = true))
        assertNull(GroupLogPage.levelAfterDrain(null, 45, blocked = true))
        assertEquals(45L, GroupLogPage.levelAfterDrain(null, 45, blocked = false))
    }
}
