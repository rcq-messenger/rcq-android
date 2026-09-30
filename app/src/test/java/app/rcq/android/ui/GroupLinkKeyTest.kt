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
}
