package app.rcq.android.net

import app.rcq.android.crypto.SkdmRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authorization half of sender authentication: once the sender is known,
 * what may they touch.
 *
 *   ids     — an id on the wire is the sender's choice and never decides on its
 *             own: edits, deletes and reactions act only in the thread of whoever
 *             may act there.
 *   chains  — a sender-key chain stays bound to its sender, its key and its room.
 *   roomkey — one envelope cannot wedge a room's key with a runaway version.
 */
class MessageAuthorityTest {

    private val me = 100
    private fun peerRow(peer: Int, fromMe: Boolean = false) = MessageAuthority.Row(peer, fromMe, null, null)
    private fun roomRow(gid: Int, sender: Int?, fromMe: Boolean = false) = MessageAuthority.Row(0, fromMe, gid, sender)

    // ── ids ──

    @Test fun a_peer_retracts_only_their_own_message_in_their_own_thread() {
        assertTrue(MessageAuthority.peerMayRetract(peerRow(7), 7))
        // Somebody else's thread, our own message, a room's message, nothing at all.
        assertFalse(MessageAuthority.peerMayRetract(peerRow(8), 7))
        assertFalse(MessageAuthority.peerMayRetract(peerRow(7, fromMe = true), 7))
        assertFalse(MessageAuthority.peerMayRetract(roomRow(5, sender = 7), 7))
        assertFalse(MessageAuthority.peerMayRetract(null, 7))
    }

    @Test fun our_other_device_retracts_in_our_notes_thread_only() {
        assertTrue(MessageAuthority.selfMayRetract(peerRow(me, fromMe = true), me))
        assertFalse(MessageAuthority.selfMayRetract(peerRow(7), me))
        assertFalse(MessageAuthority.selfMayRetract(peerRow(7, fromMe = true), me))
        assertFalse(MessageAuthority.selfMayRetract(roomRow(5, sender = me, fromMe = true), me))
        assertFalse(MessageAuthority.selfMayRetract(peerRow(0, fromMe = true), 0))
    }

    @Test fun a_carbon_acts_in_the_thread_it_names() {
        assertTrue(MessageAuthority.carbonMayTouch(peerRow(7, fromMe = true), to = 7, gid = null))
        assertFalse(MessageAuthority.carbonMayTouch(peerRow(8, fromMe = true), to = 7, gid = null))
        assertFalse(MessageAuthority.carbonMayTouch(roomRow(5, sender = me), to = 7, gid = null))
        assertTrue(MessageAuthority.carbonMayTouch(roomRow(5, sender = me), to = null, gid = 5))
        assertFalse(MessageAuthority.carbonMayTouch(roomRow(6, sender = me), to = null, gid = 5))
        assertFalse(MessageAuthority.carbonMayTouch(peerRow(7), to = null, gid = null))
        // Not here yet: our own delete leaves its tombstone.
        assertTrue(MessageAuthority.carbonMayTouch(null, to = 7, gid = null))
    }

    @Test fun a_peer_reacts_only_inside_their_thread() {
        assertTrue(MessageAuthority.peerMayReact(peerRow(7), 7))
        assertTrue(MessageAuthority.peerMayReact(peerRow(7, fromMe = true), 7))
        assertFalse(MessageAuthority.peerMayReact(peerRow(8), 7))
        assertFalse(MessageAuthority.peerMayReact(roomRow(5, sender = 7), 7))
        assertFalse(MessageAuthority.peerMayReact(null, 7))
    }

    @Test fun a_room_delete_stays_in_its_room() {
        assertTrue(MessageAuthority.groupMayDelete(roomRow(5, sender = 7), 5, 7, me, moderator = false))
        assertFalse(MessageAuthority.groupMayDelete(roomRow(5, sender = 8), 5, 7, me, moderator = false))
        assertTrue(MessageAuthority.groupMayDelete(roomRow(5, sender = 8), 5, 7, me, moderator = true))
        assertTrue(MessageAuthority.groupMayDelete(roomRow(5, sender = null, fromMe = true), 5, 7, me, moderator = true))
        // A moderator of room 5 has no say over room 6 or over a 1:1 thread.
        assertFalse(MessageAuthority.groupMayDelete(roomRow(6, sender = 8), 5, 7, me, moderator = true))
        assertFalse(MessageAuthority.groupMayDelete(peerRow(8), 5, 7, me, moderator = true))
        assertFalse(MessageAuthority.groupMayDelete(null, 5, 7, me, moderator = true))
    }

    @Test fun a_room_edit_is_the_authors_own_row_of_that_room() {
        assertTrue(MessageAuthority.groupMayEdit(roomRow(5, sender = 7), 5, 7, me))
        assertFalse(MessageAuthority.groupMayEdit(roomRow(5, sender = 8), 5, 7, me))
        assertFalse(MessageAuthority.groupMayEdit(roomRow(6, sender = 7), 5, 7, me))
        assertFalse(MessageAuthority.groupMayEdit(roomRow(5, sender = me, fromMe = true), 5, 7, me))
        assertTrue(MessageAuthority.groupMayEdit(roomRow(5, sender = null, fromMe = true), 5, me, me))
        assertFalse(MessageAuthority.groupMayEdit(peerRow(7), 5, 7, me))
    }

    // ── chains ──

    @Test fun a_chain_never_changes_key_sender_or_room() {
        val v = SkdmRule.Chain(senderUin = 7, spub = "KEY-V", room = 5, gid = 5)
        assertTrue(SkdmRule.accepts(null, 7, "KEY-V", 5, 5))
        assertTrue(SkdmRule.accepts(v, 7, "KEY-V", 5, 5))
        // Member M re-signing V's chain with M's key.
        assertFalse(SkdmRule.accepts(v, 7, "KEY-M", 5, 5))
        assertFalse(SkdmRule.accepts(v, 8, "KEY-V", 5, 5))
        assertFalse(SkdmRule.accepts(v, 7, "KEY-V", 6, 6))
        assertFalse(SkdmRule.accepts(null, 7, "", 5, 5))
    }

    @Test fun a_chain_opens_only_the_room_it_came_in() {
        assertTrue(SkdmRule.fits(chainRoom = -3, chainGid = 42, room = -3, wireGid = 42))
        assertFalse(SkdmRule.fits(chainRoom = -3, chainGid = 42, room = 42, wireGid = 42))
        // A chain stored before rooms were kept is held to the id it was sealed under.
        assertTrue(SkdmRule.fits(chainRoom = null, chainGid = 42, room = -3, wireGid = 42))
        assertFalse(SkdmRule.fits(chainRoom = null, chainGid = 42, room = 43, wireGid = 43))
    }

    // ── room keys ──

    @Test fun a_room_key_moves_one_version_at_a_time_unless_it_opens_the_room() {
        assertEquals(RoomKeyRule.Verdict.ACCEPT, RoomKeyRule.accept(null, 1L shl 53, null, null))
        assertEquals(RoomKeyRule.Verdict.ACCEPT, RoomKeyRule.accept(3, 4, null, null))
        assertEquals(RoomKeyRule.Verdict.ACCEPT, RoomKeyRule.accept(3, 3, null, null))
        assertEquals(RoomKeyRule.Verdict.REFUSE, RoomKeyRule.accept(3, 1L shl 53, null, null))
        assertEquals(RoomKeyRule.Verdict.REFUSE, RoomKeyRule.accept(3, 2, null, null))
        // Never trade a key that opens the room's sealed state for one that does not.
        assertEquals(RoomKeyRule.Verdict.REFUSE, RoomKeyRule.accept(3, 4, opensNew = false, opensHeld = true))
        assertEquals(RoomKeyRule.Verdict.REFUSE, RoomKeyRule.accept(4, 4, opensNew = false, opensHeld = true, blobVer = 4))
        assertEquals(RoomKeyRule.Verdict.REFUSE, RoomKeyRule.accept(3, 4, opensNew = false, opensHeld = true, blobVer = 4))
        // A rotation lands before its re-seal: the blob is still sealed under
        // the old version and says nothing about the new key.
        assertEquals(RoomKeyRule.Verdict.ACCEPT, RoomKeyRule.accept(3, 4, opensNew = false, opensHeld = true, blobVer = 3))
        // The step rule still bounds it.
        assertEquals(RoomKeyRule.Verdict.REFUSE, RoomKeyRule.accept(3, 9, opensNew = false, opensHeld = true, blobVer = 3))
        // A key that opens it wins whatever its version, which repairs a wedge.
        assertEquals(RoomKeyRule.Verdict.FORCE, RoomKeyRule.accept(1L shl 53, 2, opensNew = true, opensHeld = false))
        // Neither opens a stale blob: the step rule decides.
        assertEquals(RoomKeyRule.Verdict.ACCEPT, RoomKeyRule.accept(3, 4, opensNew = false, opensHeld = false))
        assertEquals(RoomKeyRule.Verdict.REFUSE, RoomKeyRule.accept(3, 9, opensNew = false, opensHeld = false))
    }
}
