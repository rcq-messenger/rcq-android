package app.rcq.android.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report 1072, cause 2: a launch on one install took back what another install
 * of the same number had muted, because every start PUT the whole local set and
 * the island replaces its lists wholesale.
 */
class PushMuteSyncTest {

    @Test fun aStartWithNothingChangedHereSaysNothing() {
        val local = setOf("peer:5", "group:7")
        assertTrue(PushMuteSync.upToDate(local, sent = setOf("group:7", "peer:5")))
    }

    @Test fun anInstallThatNeverSpokeHasSomethingToSay() {
        assertFalse(PushMuteSync.upToDate(emptySet(), sent = null))
    }

    @Test fun anotherInstallsMuteSurvivesAMuteMadeHere() {
        // The iPhone muted 9 after this phone last spoke; this phone now mutes 5.
        val next = PushMuteSync.merge(
            local = setOf("peer:5", "group:7"),
            sent = setOf("group:7"),
            server = setOf("group:7", "peer:9"),
        )
        assertEquals(setOf("group:7", "peer:9", "peer:5"), next)
    }

    @Test fun anUnmuteHereTakesOnlyThatThreadOff() {
        val next = PushMuteSync.merge(
            local = setOf("peer:5"),
            sent = setOf("peer:5", "group:7"),
            server = setOf("peer:5", "group:7", "peer:9"),
        )
        assertEquals(setOf("peer:5", "peer:9"), next)
    }

    @Test fun theFirstSyncAfterTheUpdateTakesNothingBack() {
        // No record of what was sent: nothing the island holds is known to be a
        // removal made here, so it all stays and this phone's mutes are added.
        val next = PushMuteSync.merge(
            local = setOf("peer:5"),
            sent = null,
            server = setOf("peer:9", "group:3"),
        )
        assertEquals(setOf("peer:9", "group:3", "peer:5"), next)
    }

    @Test fun anUnmuteBeforeTheFirstSyncGotThroughStillComesOff() {
        // Older builds PUT peer:5 and group:7. The first sync after the update
        // failed (offline), and peer:5 was unmuted before the next one.
        val next = PushMuteSync.merge(
            local = setOf("group:7"),
            sent = null,
            server = setOf("peer:5", "group:7", "peer:9"),
            legacy = setOf("peer:5", "group:7"),
        )
        assertEquals(setOf("group:7", "peer:9"), next)
    }

    @Test fun theLegacyBaselineAloneTakesNothingBackAndStillAdds() {
        // The first sync, seeded with the set held right then.
        val next = PushMuteSync.merge(
            local = setOf("peer:5"),
            sent = null,
            server = setOf("peer:9"),
            legacy = setOf("peer:5"),
        )
        assertEquals(setOf("peer:9", "peer:5"), next)
        // Once a set was delivered, removals count from that, not the legacy.
        assertEquals(
            setOf("peer:9", "peer:5"),
            PushMuteSync.merge(local = setOf("peer:5"), sent = setOf("peer:5"), server = setOf("peer:9", "peer:5"), legacy = setOf("peer:9")),
        )
    }

    @Test fun anIslandThatSendsNoListsIsEmpty() {
        assertEquals(emptySet<String>(), PushMuteSync.serverThreads(null, null))
        assertEquals(setOf("peer:1", "group:2"), PushMuteSync.serverThreads(listOf(1), listOf(2)))
    }

    @Test fun onlyKeysTheIslandCanHoldAreSynced() {
        assertEquals(
            setOf("peer:5", "group:7"),
            PushMuteSync.syncable(setOf("peer:5", "group:7", "peer:x", "random:1")),
        )
    }

    @Test fun listsGoOutSortedAndSplit() {
        val threads = setOf("peer:9", "group:7", "peer:5", "group:2")
        assertEquals(listOf(5, 9), PushMuteSync.uinsOf(threads))
        assertEquals(listOf(2, 7), PushMuteSync.groupIdsOf(threads))
    }
}
