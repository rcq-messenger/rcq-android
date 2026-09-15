package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1: rows a guest poll brings into the cross-island request store, through
 *  the store's pure halves. */
class ServerRequestStoreTest {

    private val S = CrossIslandRequestsStore

    private fun merge(
        map: MutableMap<String, CrossIslandRequestsStore.Request>,
        uin: Int = 7,
        id: Int = 42,
        blocked: Set<String> = emptySet(),
        answered: Set<String> = emptySet(),
        nick: String? = "from island",
        now: Long = 5_000L,
    ) = S.mergeServerRow(map, blocked, answered, 100, uin, "b.example", id, 900, nick, now)

    @Test fun a_5f_row_and_a_server_row_stay_one_row_with_first_at_unchanged() {
        val map = linkedMapOf(
            "100:7@b.example" to CrossIslandRequestsStore.Request(
                100, 7, "b.example", 1_000L, mutableListOf(), nickname = "signed name", note = "hi", contactReq = true,
            ),
        )
        assertTrue(merge(map))
        assertEquals(1, map.size)
        val r = map.values.single()
        assertEquals(1_000L, r.firstAt)
        assertEquals("signed name", r.nickname)
        assertEquals("hi", r.note)
        assertTrue(r.contactReq)
        assertEquals(42, r.srvReqId)
        assertEquals(900, r.srvGuestUin)
        // Listed again: nothing changes.
        assertFalse(merge(map))
    }

    @Test fun a_new_server_row_takes_the_islands_name() {
        val map = linkedMapOf<String, CrossIslandRequestsStore.Request>()
        assertTrue(merge(map, nick = "from island"))
        assertEquals("from island", map["100:7@b.example"]?.nickname)
        assertEquals(5_000L, map["100:7@b.example"]?.firstAt)
    }

    @Test fun blocked_and_answered_rows_are_not_stored() {
        val map = linkedMapOf<String, CrossIslandRequestsStore.Request>()
        assertFalse(merge(map, blocked = setOf("100:7@b.example")))
        assertFalse(merge(map, answered = setOf(S.answeredKey(100, "B.example", 42))))
        assertTrue(map.isEmpty())
    }

    @Test fun the_cap_is_respected_but_an_existing_row_still_merges() {
        val map = linkedMapOf<String, CrossIslandRequestsStore.Request>()
        repeat(CrossIslandRequestsStore.MAX_REQUESTS) { i ->
            map["100:${i + 1}@c.example"] = CrossIslandRequestsStore.Request(100, i + 1, "c.example", 0L, mutableListOf())
        }
        assertFalse(merge(map, uin = 7))
        map["100:7@b.example"] = map.remove("100:1@c.example")!!.copy(uin = 7, host = "b.example")
        assertTrue(merge(map, uin = 7))
    }

    @Test fun relisting_keeps_accept_tries_and_a_new_id_resets_them() {
        val map = linkedMapOf<String, CrossIslandRequestsStore.Request>()
        merge(map, id = 42)
        map["100:7@b.example"] = map["100:7@b.example"]!!.copy(srvAcceptTries = 2, viaKeyChanged = true)
        assertFalse(merge(map, id = 42))
        assertEquals(2, map["100:7@b.example"]?.srvAcceptTries)
        assertTrue(merge(map, id = 43))
        assertEquals(0, map["100:7@b.example"]?.srvAcceptTries)
        assertFalse(map["100:7@b.example"]!!.viaKeyChanged)
    }

    @Test fun reconcile_strips_a_vanished_id() {
        val held = CrossIslandRequestsStore.Held("payload", "hello")
        val map = linkedMapOf(
            "100:7@b.example" to CrossIslandRequestsStore.Request(100, 7, "b.example", 0L, mutableListOf(held), srvReqId = 1, srvGuestUin = 900),
            "100:8@b.example" to CrossIslandRequestsStore.Request(100, 8, "b.example", 0L, mutableListOf(), srvReqId = 2, srvGuestUin = 900),
            "100:9@b.example" to CrossIslandRequestsStore.Request(100, 9, "b.example", 0L, mutableListOf(), srvReqId = 3, srvGuestUin = 900),
            "100:8@c.example" to CrossIslandRequestsStore.Request(100, 8, "c.example", 0L, mutableListOf(), srvReqId = 2, srvGuestUin = 5),
        )
        assertTrue(S.reconcileRows(map, 100, "B.example", liveIds = setOf(3)))
        // Held messages keep the row; the server fields go.
        assertNull(map["100:7@b.example"]?.srvReqId)
        assertEquals(1, map["100:7@b.example"]?.msgs?.size)
        // A row that was only the server request goes with it.
        assertFalse("100:8@b.example" in map)
        assertEquals(3, map["100:9@b.example"]?.srvReqId)
        // Another island is not touched.
        assertEquals(2, map["100:8@c.example"]?.srvReqId)
        assertFalse(S.reconcileRows(map, 100, "b.example", liveIds = setOf(3)))
    }

    @Test fun answered_ids_the_island_no_longer_lists_are_forgotten() {
        val set = setOf(S.answeredKey(100, "b.example", 1), S.answeredKey(100, "b.example", 3), S.answeredKey(100, "c.example", 1), S.answeredKey(200, "b.example", 1))
        val out = S.pruneAnswered(set, 100, "b.example", liveIds = setOf(3))
        assertEquals(setOf("100:b.example#3", "100:c.example#1", "200:b.example#1"), out)
    }

    @Test fun the_move_rekey_carries_the_new_fields_and_the_answered_set() {
        val map = mapOf(
            "100:7@b.example" to CrossIslandRequestsStore.Request(100, 7, "b.example", 0L, mutableListOf(), srvReqId = 42, srvGuestUin = 900, srvAcceptTries = 1, viaKeyChanged = true),
        )
        val moved = S.rekeyRequests(map, 100, 200)["200:7@b.example"]!!
        assertEquals(200, moved.ownUin)
        assertEquals(42, moved.srvReqId)
        assertEquals(900, moved.srvGuestUin)
        assertEquals(1, moved.srvAcceptTries)
        assertTrue(moved.viaKeyChanged)
        assertEquals(setOf("200:b.example#42", "1000:b.example#1"), S.rekeyBlocked(setOf("100:b.example#42", "1000:b.example#1"), 100, 200))
    }
}
