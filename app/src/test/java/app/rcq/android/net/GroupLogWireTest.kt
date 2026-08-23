package app.rcq.android.net

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Stage 5 wire shapes of [RcqApi], checked on the JVM against the
 * bodies the island's test_stage5_group_log_local.py sends and reads.
 *
 * The normal fetch must carry NO `rooms` and NO `after`: a present `rooms`
 * narrows the drain to those rooms, and a present `after` re-reads without
 * moving the stored cursor (recovery only). The answer keys `heads` and
 * `cursors` by room id, which JSON can only spell as a string; the drain
 * reads them back through [RcqApi.GroupLogFetchOut.seqOf]. And an island
 * that predates the stage omits `group_log` from /server/info, which must
 * read as false, not as a crash or a guess.
 */
class GroupLogWireTest {

    private val gson = Gson()

    @Test fun normalFetchNamesNoRoomsAndNoAfter() {
        val body = JsonParser.parseString(gson.toJson(RcqApi.GroupLogFetchIn())).asJsonObject
        assertFalse("rooms must be omitted so every room is served", body.has("rooms"))
        assertEquals(500, body.get("limit").asInt)
    }

    @Test fun recoveryRoomCarriesAfter() {
        val body = JsonParser.parseString(
            gson.toJson(RcqApi.GroupLogFetchIn(rooms = listOf(RcqApi.GroupLogRoomIn(7, after = 0L)), limit = 50))
        ).asJsonObject
        val room = body.getAsJsonArray("rooms").get(0).asJsonObject
        assertEquals(7, room.get("gid").asInt)
        assertEquals(0L, room.get("after").asLong)
        assertEquals(50, body.get("limit").asInt)
    }

    @Test fun fetchAnswerReadsBackWithStringKeys() {
        val json = """{"rows":[{"gid":7,"seq":41,"envelope_type":"gmsg","cls":1,"payload":"p1","received_at":"2026-08-23T00:00:00+00:00"},
            {"gid":7,"seq":42,"envelope_type":"skdm","cls":2,"payload":"p2","received_at":"2026-08-23T00:00:01+00:00"}],
            "heads":{"7":42,"9":3},"cursors":{"7":40,"9":3},"more":true}"""
        val out = gson.fromJson(json, RcqApi.GroupLogFetchOut::class.java)
        assertEquals(listOf(41L, 42L), out.rows.map { it.seq })
        assertEquals("skdm", out.rows[1].envelope_type)
        assertEquals(42L, RcqApi.GroupLogFetchOut.seqOf(out.heads, 7))
        assertEquals(40L, RcqApi.GroupLogFetchOut.seqOf(out.cursors, 7))
        assertEquals(3L, RcqApi.GroupLogFetchOut.seqOf(out.cursors, 9))
        assertNull(RcqApi.GroupLogFetchOut.seqOf(out.cursors, 11))
        assertTrue(out.more)
    }

    @Test fun emptyAnswerHasDefaults() {
        val out = gson.fromJson("""{"rows":[],"heads":{},"cursors":{},"more":false}""", RcqApi.GroupLogFetchOut::class.java)
        assertTrue(out.rows.isEmpty())
        assertFalse(out.more)
    }

    @Test fun ackNamesOneUptoPerRoom() {
        val body = JsonParser.parseString(
            gson.toJson(RcqApi.GroupLogAckIn(listOf(RcqApi.GroupLogAckRoomIn(7, 42L), RcqApi.GroupLogAckRoomIn(9, 3L))))
        ).asJsonObject
        val rooms = body.getAsJsonArray("rooms")
        assertEquals(2, rooms.size())
        assertEquals(7, rooms.get(0).asJsonObject.get("gid").asInt)
        assertEquals(42L, rooms.get(0).asJsonObject.get("upto").asLong)
    }

    @Test fun olderIslandReadsAsNoLog() {
        val caps = gson.fromJson("""{"uin_shop":true,"reports":true}""", RcqApi.ServerCapabilities::class.java)
        assertFalse(caps.group_log)
        val live = gson.fromJson("""{"group_log":true}""", RcqApi.ServerCapabilities::class.java)
        assertTrue(live.group_log)
    }
}
