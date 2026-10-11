package app.rcq.android.data

import app.rcq.android.push.PushMuteSync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A mute names one person: a number on one island. It used to be the bare
 * `peer:<uin>`, so `1234` here and `1234@is2` shared one mute, and muting the
 * one on another island put 1234 into OUR island's push-suppression list,
 * silencing whoever holds that number here.
 */
class IslandMutesTest {

    private val acct = "5d4c3b2a-1908-7f6e-5d4c-3b2a19087f6e"
    private lateinit var prefs: FakePrefs

    private fun field(name: String) = LocalStores::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before fun setUp() {
        prefs = FakePrefs()
        field("prefs").set(null, prefs)
    }

    @After fun tearDown() {
        LocalStores.bindAccount(null)
        field("prefs").set(null, null)
    }

    @Test fun ourIslandKeepsTheBareKeyAndAnotherIslandIsAnAddress() {
        assertEquals("peer:1234", LocalStores.peerMuteKey(1234, null))
        assertEquals("peer:1234", LocalStores.peerMuteKey(1234, ""))
        assertEquals("peer:1234@is2.example", LocalStores.peerMuteKey(1234, "IS2.example"))
    }

    @Test fun anotherIslandsNumberNeverReachesOurIslandsList() {
        val local = setOf("peer:1234@is2.example", "peer:5", "group:7", "group:-1003")
        assertEquals(setOf("peer:5", "group:7"), PushMuteSync.syncable(local))
    }

    @Test fun aMuteOfSomebodyElsewhereMovesToTheirAddress() {
        val out = LocalStores.withIslandMutes(
            muted = setOf("peer:1234", "group:7"),
            crossIsland = listOf(1234 to "is2.example"),
            ownIsland = setOf(5),
        )
        assertEquals(setOf("peer:1234@is2.example", "group:7"), out)
    }

    @Test fun aNumberHeldOnBothIslandsKeepsTheMuteOnBoth() {
        val out = LocalStores.withIslandMutes(
            muted = setOf("peer:1234"),
            crossIsland = listOf(1234 to "is2.example"),
            ownIsland = setOf(1234),
        )
        assertEquals(setOf("peer:1234", "peer:1234@is2.example"), out)
    }

    @Test fun oneNumberOnTwoOtherIslandsIsMutedOnBoth() {
        val out = LocalStores.withIslandMutes(
            muted = setOf("peer:1234"),
            crossIsland = listOf(1234 to "is2.example", 1234 to "is3.example"),
            ownIsland = emptySet(),
        )
        assertEquals(setOf("peer:1234@is2.example", "peer:1234@is3.example"), out)
    }

    @Test fun aStrangerHereKeepsTheirMute() {
        val muted = setOf("peer:99", "peer:1234")
        val out = LocalStores.withIslandMutes(muted, crossIsland = listOf(55 to "is2.example"), ownIsland = emptySet())
        assertEquals(muted, out)
    }

    @Test fun withNoRosterYetNothingIsDropped() {
        val out = LocalStores.withIslandMutes(
            muted = setOf("peer:1234"),
            crossIsland = listOf(1234 to "is2.example"),
            ownIsland = null,
        )
        assertEquals(setOf("peer:1234", "peer:1234@is2.example"), out)
    }

    @Test fun theMoveTakesTheNumberOffOurIslandOnTheNextSync() {
        LocalStores.bindAccount(acct)
        LocalStores.toggleMute("peer:1234")
        // An install that has never delivered a set: the island got peer:1234
        // from an older build's full PUT.
        LocalStores.migrateIslandMutes(acct, listOf(1234 to "is2.example"), ownIsland = emptySet())

        assertEquals(setOf("peer:1234@is2.example"), LocalStores.muted.value)
        val local = PushMuteSync.syncable(LocalStores.muted.value)
        val next = PushMuteSync.merge(
            local, LocalStores.pushMutesSent(acct), server = setOf("peer:1234", "group:7"),
            legacy = LocalStores.pushMutesLegacy(acct),
        )
        assertEquals(setOf("group:7"), next)
    }

    @Test fun theMoveRunsOnceAndOnlyWithARoster() {
        LocalStores.bindAccount(acct)
        LocalStores.toggleMute("peer:1234")
        LocalStores.migrateIslandMutes(acct, listOf(1234 to "is2.example"), ownIsland = null)
        assertEquals(setOf("peer:1234", "peer:1234@is2.example"), LocalStores.muted.value)
        LocalStores.migrateIslandMutes(acct, listOf(1234 to "is2.example"), ownIsland = emptySet())
        assertEquals(setOf("peer:1234@is2.example"), LocalStores.muted.value)
        // Done: a bare mute set afterwards, for somebody here, stays bare.
        LocalStores.toggleMute("peer:1234")
        LocalStores.migrateIslandMutes(acct, listOf(1234 to "is2.example"), ownIsland = emptySet())
        assertEquals(setOf("peer:1234@is2.example", "peer:1234"), LocalStores.muted.value)
    }

    @Test fun anotherAccountsMovesNothing() {
        LocalStores.bindAccount(acct)
        LocalStores.toggleMute("peer:1234")
        LocalStores.migrateIslandMutes("someone-else", listOf(1234 to "is2.example"), ownIsland = emptySet())
        assertEquals(setOf("peer:1234"), LocalStores.muted.value)
    }

    @Test fun aColdWakeBeforeTheMoveStillHonoursTheBareMute() {
        LocalStores.bindAccount(acct)
        LocalStores.toggleMute("peer:1234")
        // Not moved yet: the bare number spoke for both islands, and still does.
        assertTrue(LocalStores.isPeerMutedFor(acct, 1234, "is2.example"))
        assertTrue(LocalStores.isPeerMutedFor(acct, 1234, null))
        LocalStores.migrateIslandMutes(acct, listOf(1234 to "is2.example"), ownIsland = emptySet())
        // Moved: each address answers for itself.
        assertTrue(LocalStores.isPeerMutedFor(acct, 1234, "is2.example"))
        assertFalse(LocalStores.isPeerMutedFor(acct, 1234, null))
        assertFalse(LocalStores.isPeerMutedFor(acct, 1234, "is3.example"))
    }
}
