package app.rcq.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which island a number means, when a card is about to be resolved by it
 * (reports #433, #429 and the 1:1 half of #1024).
 *
 * Every case here is silent when it goes wrong: an island answers for whoever
 * holds that number on it, so the wrong island does not 404, it draws a
 * stranger's name, presence and picture, and the old code also sent that
 * stranger a visit ping.
 */
class PeerIslandTest {

    private val ours = "api.rcq.app"

    @Test fun a_caller_naming_our_own_island_means_our_own_island() {
        // #433 / #429: this must not fall back to a same-numbered contact
        // somewhere else, which is what "the row said is2, the card showed api"
        // was made of.
        assertNull(
            PeerIsland.cardHost(
                callerHost = ours, ourIsland = ours,
                rosterMatched = true, rosterHost = "is2.rcq.app", storeHost = "is2.rcq.app",
            )
        )
    }

    @Test fun our_own_island_is_recognised_whatever_the_casing() {
        assertNull(
            PeerIsland.cardHost(
                callerHost = "API.RCQ.APP", ourIsland = ours,
                rosterMatched = false, rosterHost = null, storeHost = "is2.rcq.app",
            )
        )
    }

    @Test fun a_caller_naming_a_foreign_island_is_believed() {
        // A room member who is not a contact has no row anywhere, so the room's
        // host is the only thing that knows.
        assertEquals(
            "ams.rcq.app",
            PeerIsland.cardHost(
                callerHost = "ams.rcq.app", ourIsland = ours,
                rosterMatched = false, rosterHost = null, storeHost = null,
            )
        )
    }

    @Test fun a_contact_s_own_island_outranks_the_room_the_tap_came_from() {
        assertEquals(
            "is2.rcq.app",
            PeerIsland.cardHost(
                callerHost = "ams.rcq.app", ourIsland = ours,
                rosterMatched = true, rosterHost = "is2.rcq.app", storeHost = null,
            )
        )
    }

    /** The 1:1 case: a thread is opened by number alone, so nobody names a host. */
    @Test fun a_matched_same_island_row_resolves_here() {
        assertNull(
            PeerIsland.cardHost(
                callerHost = null, ourIsland = ours,
                rosterMatched = true, rosterHost = null, storeHost = null,
            )
        )
    }

    @Test fun a_matched_foreign_row_resolves_there() {
        assertEquals(
            "is2.rcq.app",
            PeerIsland.cardHost(
                callerHost = null, ourIsland = ours,
                rosterMatched = true, rosterHost = "is2.rcq.app", storeHost = "is2.rcq.app",
            )
        )
    }

    /** #1024, the line that was missing: the store knows before the visible
     *  roster does (an accept that arrived from another device, a roster still
     *  answering 304), and without this the card resolved on OUR island. */
    @Test fun the_store_answers_when_the_roster_has_no_row_yet() {
        assertEquals(
            "is2.rcq.app",
            PeerIsland.cardHost(
                callerHost = null, ourIsland = ours,
                rosterMatched = false, rosterHost = null, storeHost = "is2.rcq.app",
            )
        )
    }

    /** ⚠ A matched same-island row is an answer and is not second-guessed: were
     *  the store consulted here, a local contact who happens to share a number
     *  with a foreign one would be drawn from the wrong island. */
    @Test fun the_store_does_not_override_a_row_the_roster_matched() {
        assertNull(
            PeerIsland.cardHost(
                callerHost = null, ourIsland = ours,
                rosterMatched = true, rosterHost = null, storeHost = "is2.rcq.app",
            )
        )
    }

    @Test fun nobody_knows_anything_means_our_own_island() {
        assertNull(
            PeerIsland.cardHost(
                callerHost = null, ourIsland = ours,
                rosterMatched = false, rosterHost = null, storeHost = null,
            )
        )
    }

    /** A store row pointing at our own island is not "elsewhere": sending it
     *  down the cross-island path would draw the card from an open-card fetch
     *  and hide the actions a local contact has. */
    @Test fun a_store_host_equal_to_our_island_resolves_here() {
        assertNull(
            PeerIsland.cardHost(
                callerHost = null, ourIsland = ours,
                rosterMatched = false, rosterHost = null, storeHost = "API.rcq.app",
            )
        )
    }

    /** Before the first server info lands there is no "our island" to compare
     *  against. The caller is still believed, since the alternative is
     *  resolving a foreign number locally. */
    @Test fun an_unknown_own_island_still_believes_the_caller() {
        assertEquals(
            "ams.rcq.app",
            PeerIsland.cardHost(
                callerHost = "ams.rcq.app", ourIsland = null,
                rosterMatched = false, rosterHost = null, storeHost = null,
            )
        )
    }
}
