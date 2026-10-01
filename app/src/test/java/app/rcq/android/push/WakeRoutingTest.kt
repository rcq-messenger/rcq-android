package app.rcq.android.push

import app.rcq.android.net.RcqApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Which account a wake is for, and which numbers a registration says are here
 * (report 1066).
 *
 * A removed account's push row kept waking the phone, and the receiver handed
 * the wake to whichever account was in front: "RCQ / New message" with a sound,
 * a full-screen ring, a lit screen, all for an account no longer on the phone.
 */
class WakeRoutingTest {

    private fun n(id: String, uin: Int?, host: String? = null) = Push.LocalNumber(id, host, uin)

    @Test fun a_number_held_here_goes_to_its_account_not_the_active_one() {
        val t = Push.resolveWakeTarget(200, listOf(n("a", 100), n("b", 200)), activeId = "a")
        assertEquals(Push.WakeTarget.Known("b"), t)
    }

    @Test fun a_number_nobody_here_holds_is_a_stranger() {
        val t = Push.resolveWakeTarget(976446092, listOf(n("a", 100), n("b", 200)), activeId = "a")
        assertSame(Push.WakeTarget.Stranger, t)
        assertNull(t.accountId)
    }

    @Test fun no_to_uin_is_an_old_island_and_falls_back_to_the_active_account() {
        val t = Push.resolveWakeTarget(null, listOf(n("a", 100)), activeId = "a")
        assertEquals(Push.WakeTarget.Fallback("a"), t)
    }

    /** It might be the one we could not read: a banner too many is better
     *  than a message nobody hears about. */
    @Test fun an_unreadable_roster_is_not_proof_of_a_stranger() {
        val t = Push.resolveWakeTarget(300, listOf(n("a", 100), n("b", null)), activeId = "a")
        assertEquals(Push.WakeTarget.Fallback("a"), t)
    }

    @Test fun a_readable_match_wins_over_an_unreadable_neighbour() {
        val t = Push.resolveWakeTarget(100, listOf(n("a", 100), n("b", null)), activeId = "b")
        assertEquals(Push.WakeTarget.Known("a"), t)
    }

    /** A wiped phone, or one whose last account was removed: nothing here may
     *  speak for any number. */
    @Test fun an_empty_roster_has_no_one_to_wake() {
        assertSame(Push.WakeTarget.Stranger, Push.resolveWakeTarget(100, emptyList(), activeId = null))
    }

    @Test fun accounts_here_groups_by_island_spelled_one_way() {
        val here = Push.accountsHereByIsland(
            listOf(
                n("a", 300, "Island.Example:443"),
                n("b", 100, "island.example."),
                n("c", 200, null),
                n("d", 400, RcqApi.DEFAULT_HOST),
                n("e", 100, "localhost:9444"),
            ),
        )
        assertEquals(
            mapOf(
                "island.example" to listOf(100, 300),
                RcqApi.DEFAULT_HOST to listOf(200, 400),
                "localhost:9444" to listOf(100),
            ),
            here,
        )
    }

    /** A number left out reads to the island as "gone": one unreadable account
     *  means no list at all, which the island treats like an old client. */
    @Test fun accounts_here_is_all_or_nothing() {
        assertNull(Push.accountsHereByIsland(listOf(n("a", 100, "x.example"), n("b", null, "y.example"))))
    }

    @Test fun the_same_number_twice_on_one_island_is_listed_once() {
        assertEquals(
            mapOf("x.example" to listOf(100)),
            Push.accountsHereByIsland(listOf(n("a", 100, "x.example"), n("b", 100, "X.example"))),
        )
    }
}
