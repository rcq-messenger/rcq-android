package app.rcq.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #990 step 2: a room link carries the room's key and hands it on. */
class GroupLinkKeyTest {

    @Test fun a_shared_link_carries_the_key() {
        assertEquals("https://rcq.app/g/42@is2.rcq.app?k=AbCdEf123_-x", GroupLinkParser.canonicalUrl(42, "is2.rcq.app", "AbCdEf123_-x"))
    }

    @Test fun no_key_no_query_and_a_malformed_key_is_dropped() {
        assertEquals("https://rcq.app/g/42@is2.rcq.app", GroupLinkParser.canonicalUrl(42, "is2.rcq.app", null))
        assertEquals("https://rcq.app/g/42@is2.rcq.app", GroupLinkParser.canonicalUrl(42, "is2.rcq.app", "a b"))
    }

    @Test fun a_pinned_link_with_a_key_is_parsed_and_remembered() {
        val refs = GroupLinkParser.parseAll("join https://rcq.app/g/77@api.rcq.app?k=KeyKeyKey123 and https://rcq.app/g/78")
        assertEquals(2, refs.size)
        assertEquals("KeyKeyKey123", refs[0].k)
        assertNull(refs[1].k)
        assertEquals("KeyKeyKey123", RoomLinkKeys.find(77, listOf(null, "api.rcq.app")))
        assertNull(RoomLinkKeys.find(78, listOf(null, "api.rcq.app")))
    }

    // #990 step 2 review: the key is part of GroupRef, and `.distinct()` kept
    // the old and the new link of a reset room as two pin rows with one key.
    @Test fun two_links_to_one_room_are_one_pin_row_the_first_one() {
        val refs = GroupLinkParser.parseAll(
            "old https://rcq.app/g/4101@is2.rcq.app?k=OldOldOld1 new https://rcq.app/g/4101@is2.rcq.app?k=NewNewNew1",
        )
        assertEquals(1, refs.size)
        assertEquals("OldOldOld1", refs[0].k)
    }

    // A bare link in a pin of a room on another island is THAT island's room:
    // its key is remembered there, never in the slot of our own island.
    @Test fun a_bare_pinned_link_takes_the_rooms_island() {
        val refs = GroupLinkParser.parseAll("https://rcq.app/g/4102?k=KeyKeyKey4102", defaultHost = "Is2.Rcq.App")
        assertEquals("is2.rcq.app", refs.single().host)
        assertEquals("KeyKeyKey4102", RoomLinkKeys.find(4102, listOf("is2.rcq.app")))
        assertNull(RoomLinkKeys.find(4102, listOf(null)))
    }

    @Test fun an_account_switch_forgets_the_keys() {
        GroupLinkParser.parseAll("https://rcq.app/g/4103?k=KeyKeyKey4103")
        assertEquals("KeyKeyKey4103", RoomLinkKeys.find(4103, listOf(null)))
        RoomLinkKeys.clear()
        assertNull(RoomLinkKeys.find(4103, listOf(null)))
    }
}
