package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ack decision of the guest-mailbox drains (#986(a)) and the island list
 * the nickname push walks (#985(2)).
 *
 * The drains used to ack every row after the loop, whatever ingest said, and
 * the island deletes what is acked. These cases pin that only rows handled or
 * written off are named, and that a failure which is not the row's fault (a
 * closed store, a switch, the duress view) never counts toward a write-off.
 */
class GuestDrainAckTest {

    @Test fun a_handled_row_is_done_and_forgotten() {
        assertEquals(GroupLogPage.RowFate(done = true, record = null), GroupLogPage.fate(null, null))
        assertEquals(GroupLogPage.RowFate(done = true, record = null), GroupLogPage.fate("2:Boom", null))
    }

    @Test fun a_failing_row_pins_the_ack_until_it_is_written_off() {
        val first = GroupLogPage.fate(null, "Boom")
        assertFalse(first.done)
        val second = GroupLogPage.fate(first.record, "Boom")
        assertFalse(second.done)
        val third = GroupLogPage.fate(second.record, "Boom")
        assertTrue(third.done)
        assertNull(third.record)
    }

    @Test fun a_new_failure_starts_over() {
        val a = GroupLogPage.fate(GroupLogPage.fate(null, "Boom").record, "Boom")
        val b = GroupLogPage.fate(a.record, "Other")
        assertFalse(b.done)
        assertEquals("1:Other", b.record)
    }

    @Test fun not_the_rows_fault_is_never_booked_or_acked() {
        for (why in listOf("duress", "db_closed", "switched")) {
            var prev: String? = "2:Boom"
            repeat(5) {
                val f = GroupLogPage.fate(prev, why)
                assertFalse(why, f.done)
                assertEquals(why, "2:Boom", f.record)
                prev = f.record
            }
        }
    }

    @Test fun queue_acks_name_only_done_rows_in_their_own_table() {
        val acks = GroupLogPage.QueueAcks()
        assertTrue(acks.isEmpty)
        acks.row(1, isGroup = false, done = true)
        acks.row(1, isGroup = true, done = true)
        acks.row(2, isGroup = true, done = false)
        acks.row(3, isGroup = false, done = false)
        assertEquals(listOf(1), acks.direct)
        assertEquals(listOf(1), acks.group)
        assertFalse(acks.isEmpty)
    }

    @Test fun nickname_targets_are_this_accounts_islands_once_each() {
        val visited = listOf(
            VisitedIslandsStore.Visited("b.example", 11, "jb", 0L),
            VisitedIslandsStore.Visited("own.example", 12, "jo", 0L),
        )
        val backups = listOf(
            MultihomeStore.Home(5, "B.example", 11, "jb2", 0L),
            MultihomeStore.Home(5, "c.example", 13, "jc", 0L),
            MultihomeStore.Home(5, "cdn.example", 14, "jf", 0L),
        )
        val t = GuestCopies.targets(visited, backups, ownHost = "own.example", skipHost = { it == "cdn.example" })
        assertEquals(listOf("b.example", "c.example"), t.map { it.host })
        // The host both stores hold is written once, through the visited entry.
        assertEquals(GuestCopies.Source.VISITED, t[0].source)
        assertEquals("jb", t[0].jwt)
        assertEquals(GuestCopies.Source.BACKUP, t[1].source)
    }
}
