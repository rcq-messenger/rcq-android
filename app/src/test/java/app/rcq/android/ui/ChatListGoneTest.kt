package app.rcq.android.ui

import app.rcq.android.data.Sections
import app.rcq.android.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report 1067: a contact on another island whose account is gone sinks to the
 *  end of its section, and only the address that was marked does. */
class ChatListGoneTest {

    private fun person(uin: Int, nick: String, host: String?) =
        Contact(uin = uin, nickname = nick, identityKey = "", signingKey = null, status = "offline", host = host)

    private val ann = person(20, "ann", "is2.rcq.app")
    private val ben = person(21, "ben", "is2.rcq.app")
    private val cid = person(22, "cid", "x.example")

    @Test fun a_gone_row_goes_below_the_rest_of_its_section_even_unread() {
        val gone = setOf("20@is2.rcq.app")
        val lists = buildHomeLists(
            listOf(ann, ben, cid), emptyList(),
            unread = mapOf("peer:20" to 3),
            favorites = emptySet(), archived = emptySet(),
            sectionsTree = Sections.emptyTree(), sectionsOk = true,
            isGone = { goneInList(it, gone) },
        ) { uin -> if (uin == 20) 300L else if (uin == 21) 200L else 100L }
        assertEquals(listOf("ben", "cid", "ann"), lists.crossIsland.map { it.nickname })
    }

    @Test fun the_mark_is_per_address_not_per_number() {
        val gone = setOf("20@is2.rcq.app")
        assertTrue(goneInList(ann, gone))
        assertFalse(goneInList(person(20, "same digits elsewhere", "x.example"), gone))
        // Our own island is never drawn as gone: it drops a burned contact itself.
        assertFalse(goneInList(person(20, "neighbour", null), setOf("20")))
    }
}
