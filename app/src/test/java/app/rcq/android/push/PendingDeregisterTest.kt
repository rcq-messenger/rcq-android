package app.rcq.android.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue of push rows still to be taken off the islands of accounts this
 * device removed (report 1066): what is kept, what replaces what, when an
 * entry is finished with.
 */
class PendingDeregisterTest {

    private val day = 24L * 3600 * 1000

    private fun e(
        id: String = "gone",
        host: String = "island.example",
        uin: Int = 976446092,
        endpoint: String = "https://push.example/up/abc",
        at: Long = 1_000_000L,
    ) = PendingDeregister.Entry(id, host, uin, "tok-$id", endpoint, at)

    @Test fun round_trips() {
        val list = listOf(e(), e(id = "other", uin = 5))
        assertEquals(list, PendingDeregister.decode(PendingDeregister.encode(list)))
    }

    @Test fun nothing_pending_is_no_key_at_all() {
        assertNull(PendingDeregister.encode(emptyList()))
        assertEquals(emptyList<PendingDeregister.Entry>(), PendingDeregister.decode(null))
    }

    /** Garbage or a half-written row is dropped, never a crash in the
     *  receiver or at process start. */
    @Test fun unreadable_rows_are_dropped() {
        assertEquals(emptyList<PendingDeregister.Entry>(), PendingDeregister.decode("{not json"))
        val raw = """[{"accountId":"a","host":"h","uin":1,"token":"t","endpoint":"e","at":1},
            {"accountId":"b","host":"h","uin":2,"endpoint":"e","at":1}, null]"""
        assertEquals(listOf(PendingDeregister.Entry("a", "h", 1, "t", "e", 1)), PendingDeregister.decode(raw))
    }

    @Test fun the_same_row_recorded_again_replaces_the_old_record() {
        val old = e(at = 1)
        val fresh = e(id = "again", host = "Island.Example:443", at = 2)
        assertEquals(listOf(fresh), PendingDeregister.plus(listOf(old), fresh))
    }

    /** The endpoint is part of the row: an old endpoint's row is still on the
     *  island after the device moved to a new one. */
    @Test fun another_endpoint_is_another_row() {
        val a = e(endpoint = "https://push.example/up/old")
        val b = e(endpoint = "https://push.example/up/new")
        assertEquals(listOf(a, b), PendingDeregister.plus(listOf(a), b))
    }

    @Test fun done_means_gone_or_beyond_this_token() {
        for (code in listOf(200, 204, 401, 403, 404)) assertTrue("$code", PendingDeregister.isDone(code))
        for (code in listOf(400, 408, 422, 429, 500, 502, 503)) assertFalse("$code", PendingDeregister.isDone(code))
    }

    @Test fun a_week_is_the_limit_either_way_on_the_clock() {
        val x = e(at = 10 * day)
        assertFalse(PendingDeregister.expired(x, 10 * day + 6 * day))
        assertTrue(PendingDeregister.expired(x, 10 * day + 7 * day))
        // A clock set back a little is not an expiry; a week back is.
        assertFalse(PendingDeregister.expired(x, 10 * day - 1000))
        assertTrue(PendingDeregister.expired(x, 3 * day))
    }

    /** Added back under a new id: the retry would delete the row it has just
     *  registered. The leaver itself, still in the roster for a moment, does
     *  not count. */
    @Test fun only_another_account_holding_the_row_stops_it() {
        val x = e(id = "gone", host = "island.example", uin = 7)
        assertFalse(PendingDeregister.heldByAnother(x, listOf(Push.LocalNumber("gone", "island.example", 7))))
        assertTrue(PendingDeregister.heldByAnother(x, listOf(Push.LocalNumber("back", "ISLAND.example:443", 7))))
        // Same number, another island: a different row, a different person.
        assertFalse(PendingDeregister.heldByAnother(x, listOf(Push.LocalNumber("back", "other.example", 7))))
        assertFalse(PendingDeregister.heldByAnother(x, listOf(Push.LocalNumber("back", "island.example", null))))
    }

    @Test fun the_default_island_is_one_island_however_it_is_written() {
        val x = e(host = app.rcq.android.net.RcqApi.DEFAULT_HOST, uin = 9)
        assertTrue(PendingDeregister.sameRow(x, null, 9))
        assertFalse(PendingDeregister.sameRow(x, null, 10))
    }
}
