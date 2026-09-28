package app.rcq.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A room roster asks only the people who can answer (28.09: the asks to
 *  everybody else in the beta room are what stalled the island). */
class ProfileKeyAskTest {

    private val contacts = setOf(555, 666)

    @Test
    fun `a contact in a room on our island is asked`() {
        assertTrue(ProfileKeyAsk.worthAsking(555, null, contacts))
    }

    @Test
    fun `a room member who is not a contact is never asked`() {
        // The answerer refuses anybody outside its roster, on every client,
        // so this ask could only ever cost the island a card and a key read.
        assertFalse(ProfileKeyAsk.worthAsking(777, null, contacts))
        assertFalse(ProfileKeyAsk.worthAsking(777, null, emptySet()))
    }

    @Test
    fun `nobody in a room on another island is asked, contact digits or not`() {
        // Another numbering space: 555 there is not our 555.
        assertFalse(ProfileKeyAsk.worthAsking(555, "is2.rcq.app", contacts))
    }

    @Test
    fun `the beta room with two contacts in it asks two, not everybody with a picture`() {
        val room = (100_000 until 102_269).toList() + listOf(555, 666)
        assertEquals(2, room.count { ProfileKeyAsk.worthAsking(it, null, contacts) })
    }
}
