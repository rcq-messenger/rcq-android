package app.rcq.android.net

import app.rcq.android.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A member who posted and then left: what they said while they were in stays
 * readable for whoever drains the room late, and nothing deposited after we
 * saw them go, nothing without an island stamp and nothing under another key.
 */
class RoomDeparturesTest {

    private val keyC = ByteArray(32) { 3 }
    private val keyX = ByteArray(32) { 9 }
    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun m(uin: Int, key: ByteArray?) =
        GroupMember(uin = uin, nickname = "m$uin", role = "member", identityKey = "ik", signingKey = key?.let(::b64))

    private val D = RoomDepartures
    private val before = listOf(m(1, keyX), m(5, keyC))
    private val after = listOf(m(1, keyX))

    @Test fun a_departure_is_recorded_once_with_the_key_they_held() {
        var recs = D.afterRosterChange(emptyMap(), 42, before, after, now = 10_000L)
        val rec = recs[D.recordKey(42, 5)]!!
        assertEquals(b64(keyC), rec.key)
        assertEquals(10_000L, rec.atMs)
        // Seen gone again later: the first sighting stands.
        recs = D.afterRosterChange(recs, 42, before, after, now = 20_000L)
        assertEquals(10_000L, recs[D.recordKey(42, 5)]!!.atMs)
        // Another room is another record.
        assertNull(recs[D.recordKey(43, 5)])
    }

    @Test fun a_rejoin_clears_it_and_an_unknown_roster_says_nothing() {
        val recs = D.afterRosterChange(emptyMap(), 42, before, after, now = 10_000L)
        assertTrue(D.afterRosterChange(recs, 42, after, before, now = 11_000L).isEmpty())
        // A cold roster, or a frame that came without one.
        assertTrue(D.afterRosterChange(emptyMap(), 42, null, after, now = 10_000L).isEmpty())
        assertTrue(D.afterRosterChange(emptyMap(), 42, emptyList(), after, now = 10_000L).isEmpty())
        assertTrue(D.afterRosterChange(emptyMap(), 42, before, emptyList(), now = 10_000L).isEmpty())
    }

    @Test fun records_older_than_the_island_keeps_rows_are_pruned() {
        val recs = D.afterRosterChange(emptyMap(), 42, before, after, now = 10_000L)
        assertTrue(D.afterRosterChange(recs, 7, null, null, now = 10_000L + D.MEMORY_MS).isEmpty())
    }

    @Test fun only_what_was_deposited_before_they_left_under_their_key() {
        val rec = RoomDepartures.Left(b64(keyC), atMs = 10_000L)
        assertTrue(D.leftAfterDeposit(rec, depositAtMs = 9_000L, spub = keyC, v2 = false, ownRoom = true))
        // Deposited after we saw them go.
        assertFalse(D.leftAfterDeposit(rec, depositAtMs = 10_000L, spub = keyC, v2 = false, ownRoom = true))
        // A live frame: no island stamp, no grace.
        assertFalse(D.leftAfterDeposit(rec, depositAtMs = null, spub = keyC, v2 = false, ownRoom = true))
        // Another key wearing their number.
        assertFalse(D.leftAfterDeposit(rec, depositAtMs = 9_000L, spub = keyX, v2 = false, ownRoom = true))
        // Somebody we never saw in the room.
        assertFalse(D.leftAfterDeposit(null, depositAtMs = 9_000L, spub = keyC, v2 = false, ownRoom = true))
        // A key we never had for them proves nothing.
        assertFalse(D.leftAfterDeposit(rec.copy(key = null), depositAtMs = 9_000L, spub = keyC, v2 = false, ownRoom = true))
        // v=2: vouched by our island, so in a room of our island only.
        assertTrue(D.leftAfterDeposit(rec, depositAtMs = 9_000L, spub = ByteArray(0), v2 = true, ownRoom = true))
        assertFalse(D.leftAfterDeposit(rec, depositAtMs = 9_000L, spub = ByteArray(0), v2 = true, ownRoom = false))
        // An empty key that is not a vouched v=2 row is no key.
        assertFalse(D.leftAfterDeposit(rec, depositAtMs = 9_000L, spub = ByteArray(0), v2 = false, ownRoom = true))
    }

    @Test fun records_survive_the_round_trip() {
        val recs = mapOf(
            D.recordKey(42, 5) to RoomDepartures.Left(b64(keyC), 10_000L),
            D.recordKey(-3, 8) to RoomDepartures.Left(null, 12_000L),
        )
        assertEquals(recs, D.decode(recs.map { (k, v) -> D.encode(k, v) }))
        assertTrue(D.decode(listOf("junk", "1:2=x:y", "=5:k")).isEmpty())
    }
}
