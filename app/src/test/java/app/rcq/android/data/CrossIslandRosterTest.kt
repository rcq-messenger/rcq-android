package app.rcq.android.data

import app.rcq.android.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The cross-island half of the visible roster, report #1024.
 *
 * The bug was that this fold only ever ADDED, which was invisible for as long as
 * it ran right after a full roster body (the body overwrote the list, so a
 * dropped row never came back anyway) and became permanent the moment the roster
 * went to a conditional GET: a cross-island add or remove moves nothing on our
 * island, the ETag never moves, the answer is 304 forever. So the rule is pinned
 * here in both directions, and the same-island half is pinned as untouchable,
 * because that half is the island's and a fold that trims it would delete real
 * contacts off the screen.
 */
class CrossIslandRosterTest {

    /** A row the island served: no host, by definition. */
    private fun local(uin: Int, nick: String = "n$uin") =
        Contact(uin = uin, nickname = nick, identityKey = "", signingKey = null)

    /** A row that came out of CrossIslandStore. */
    private fun cross(uin: Int, host: String, nick: String = "n$uin@$host") =
        Contact(uin = uin, nickname = nick, identityKey = "", signingKey = null, host = host)

    @Test fun adds_a_store_row_the_list_is_missing() {
        val next = CrossIslandRoster.fold(listOf(local(1)), listOf(cross(2, "is2.rcq.app")))
        assertEquals(listOf(local(1), cross(2, "is2.rcq.app")), next)
    }

    /** The half that did not exist before: removal has to be visible without an
     *  account switch. */
    @Test fun drops_a_foreign_row_the_store_no_longer_has() {
        val current = listOf(local(1), cross(2, "is2.rcq.app"), cross(3, "ams.rcq.app"))
        val next = CrossIslandRoster.fold(current, listOf(cross(3, "ams.rcq.app")))
        assertEquals(listOf(local(1), cross(3, "ams.rcq.app")), next)
    }

    @Test fun an_empty_store_clears_every_foreign_row_and_keeps_the_island_s() {
        val current = listOf(local(1), cross(2, "is2.rcq.app"))
        assertEquals(listOf(local(1)), CrossIslandRoster.fold(current, emptyList()))
    }

    /** ⚠ The island's rows are not this function's to manage, in either
     *  direction: it may not drop them and it may not reorder them. */
    @Test fun same_island_rows_keep_their_order_and_contents() {
        val current = listOf(local(5), local(1), local(9))
        assertSame(current, CrossIslandRoster.fold(current, emptyList()))
    }

    /** Identity, not equality: the result goes into a StateFlow that a presence
     *  frame drives, and a fresh-but-equal list would wake every collector on
     *  every frame. */
    @Test fun no_change_returns_the_same_instance() {
        val current = listOf(local(1), cross(2, "is2.rcq.app"))
        assertSame(current, CrossIslandRoster.fold(current, listOf(cross(2, "is2.rcq.app"))))
    }

    /** One number is one thread in the message store, so a store row whose
     *  number a same-island contact already holds stays out, exactly as the old
     *  merge kept it out (Session.clashesWithKnownNumber stops the pair being
     *  created in the first place; this is the second line). */
    @Test fun a_number_our_own_island_already_holds_is_not_added_twice() {
        val current = listOf(local(7))
        assertSame(current, CrossIslandRoster.fold(current, listOf(cross(7, "is2.rcq.app"))))
    }

    /** Islands number independently, so two different islands can both have a
     *  #5 and both rows are real. ChatListModel keys rows by (uin, host) for
     *  this reason, and dropping one here would hide a person. */
    @Test fun the_same_number_on_two_islands_gives_two_rows() {
        val store = listOf(cross(5, "is2.rcq.app"), cross(5, "ams.rcq.app"))
        val next = CrossIslandRoster.fold(emptyList(), store)
        assertEquals(store, next)
    }

    /** A row already on screen is kept as it is rather than rebuilt from the
     *  store's copy: the displayed fields are refreshed by their own path, and
     *  swapping the instance under the user buys nothing. */
    @Test fun a_row_already_shown_is_kept_over_the_store_s_copy() {
        val shown = cross(2, "is2.rcq.app", nick = "Renamed by §5e")
        val next = CrossIslandRoster.fold(listOf(shown), listOf(cross(2, "is2.rcq.app")))
        assertSame(shown, next.single())
    }

    /** Hosts are compared case-insensitively, because the store keys on a
     *  lowercased host while a card or a room can hand us any casing. Getting
     *  this wrong would show the person twice, then throw in the LazyColumn. */
    @Test fun host_casing_does_not_duplicate_a_row() {
        val shown = cross(2, "IS2.rcq.app")
        val next = CrossIslandRoster.fold(listOf(shown), listOf(cross(2, "is2.rcq.app")))
        assertSame(shown, next.single())
    }

    @Test fun foreign_rows_follow_the_island_s_in_the_list() {
        val next = CrossIslandRoster.fold(
            listOf(cross(2, "is2.rcq.app"), local(1)),
            listOf(cross(2, "is2.rcq.app")),
        )
        assertEquals(listOf(local(1), cross(2, "is2.rcq.app")), next)
    }
}
