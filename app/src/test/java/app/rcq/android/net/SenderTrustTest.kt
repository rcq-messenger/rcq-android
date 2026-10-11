package app.rcq.android.net

import app.rcq.android.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who sent a row (sender authentication, wave 4).
 *
 * What each group of cases pins:
 *   self    — our own number is ours only under our own key (or a v=2
 *             identity our island vouched for), whatever host it stamps.
 *   pinned  — a key we hold for the number is who they are, and the host the
 *             row is attributed to is that row's, never the unsigned from_host.
 *   forged  — a number we hold a key for, under another key, including the
 *             host-less copy of a contact on another island (threads are filed
 *             by bare number), is never filed as theirs.
 *   room    — only a member proven by key acts in a room; no roster or no key
 *             means asking the island, not believing the number.
 */
class SenderTrustTest {

    private val keyA = ByteArray(32) { it.toByte() }
    private val keyB = ByteArray(32) { (it + 1).toByte() }
    private val keyMe = ByteArray(32) { (it + 7).toByte() }
    private val v2 = ByteArray(0)
    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)

    private val me = 100
    private val own = listOf("rcq.app", "cdn.rcq.app")

    private fun decide(
        uin: Int,
        spub: ByteArray,
        host: String? = null,
        same: String? = null,
        keyless: Boolean = false,
        ci: List<SenderTrust.Pin> = emptyList(),
        vouched: Boolean = spub.isEmpty(),
    ) = SenderTrust.decide(uin, spub, me, keyMe, host, own, same, keyless, ci, vouched)

    // ── self ──

    @Test fun our_number_is_ours_only_under_our_key() {
        assertEquals(SenderTrust.Level.SELF, decide(me, keyMe).level)
        assertNull(decide(me, keyMe, host = "rcq.app").host)
        assertEquals(SenderTrust.Level.FORGED, decide(me, keyA).level)
        // The host changes nothing: it is as unsigned as the number.
        assertEquals(SenderTrust.Level.FORGED, decide(me, keyA, host = "evil.example").level)
        assertEquals(SenderTrust.Level.FORGED, decide(me, keyA, host = "rcq.app").level)
    }

    @Test fun our_number_over_v2_needs_a_vouched_identity_from_our_island() {
        val d = decide(me, v2)
        assertEquals(SenderTrust.Level.SELF, d.level)
        assertTrue(d.v2)
        assertEquals(SenderTrust.Level.FORGED, decide(me, v2, vouched = false).level)
        assertEquals(SenderTrust.Level.FORGED, decide(me, v2, host = "is2.rcq.app").level)
    }

    @Test fun no_own_key_vouches_for_nothing() {
        val d = SenderTrust.decide(me, keyMe, me, null, null, own, null, false, emptyList(), false)
        assertEquals(SenderTrust.Level.FORGED, d.level)
    }

    // ── pinned ──

    @Test fun a_same_island_contact_is_proven_by_the_pinned_key_and_filed_here() {
        val d = decide(7, keyA, same = b64(keyA))
        assertEquals(SenderTrust.Level.PINNED, d.level)
        assertNull(d.host)
        // A foreign from_host on their row does not move them off our island.
        val moved = decide(7, keyA, host = "is2.rcq.app", same = b64(keyA))
        assertEquals(SenderTrust.Level.PINNED, moved.level)
        assertNull(moved.host)
    }

    @Test fun a_cross_island_contact_is_proven_by_key_even_without_a_host() {
        val ci = listOf(SenderTrust.Pin("is2.rcq.app", b64(keyA)))
        val d = decide(7, keyA, ci = ci)
        assertEquals(SenderTrust.Level.PINNED, d.level)
        assertEquals("is2.rcq.app", d.host)
        // A third island named on the row: still the contact we hold, filed there.
        assertEquals("is2.rcq.app", decide(7, keyA, host = "is3.example", ci = ci).host)
    }

    @Test fun the_claimed_address_wins_when_one_key_is_filed_under_two_islands() {
        val ci = listOf(SenderTrust.Pin("a.example", b64(keyA)), SenderTrust.Pin("b.example", b64(keyA)))
        assertEquals("b.example", decide(7, keyA, host = "b.example", ci = ci).host)
        assertEquals("a.example", decide(7, keyA, ci = ci).host)
    }

    // ── forged ──

    @Test fun a_contact_number_under_another_key_is_forged_and_asks_the_card() {
        val d = decide(7, keyB, same = b64(keyA))
        assertEquals(SenderTrust.Level.FORGED, d.level)
        assertTrue("a phrase change re-keys an account: the card may absorb it", d.askCard)
        assertNull(d.host)
    }

    @Test fun dropping_from_host_does_not_impersonate_a_contact_on_another_island() {
        // V is a contact on is2 only; the forger writes as V with no host, so
        // the row would land in thread peer:V under V's name.
        val ci = listOf(SenderTrust.Pin("is2.rcq.app", b64(keyA)))
        val d = decide(7, keyB, ci = ci)
        assertEquals(SenderTrust.Level.FORGED, d.level)
        assertFalse(d.askCard)
        assertEquals(SenderTrust.Level.FORGED, decide(7, keyB, host = "rcq.app", ci = ci).level)
    }

    @Test fun at_a_foreign_address_only_the_contact_held_there_can_be_forged() {
        val ci = listOf(SenderTrust.Pin("is2.rcq.app", b64(keyA)))
        val atAddress = decide(7, keyB, host = "IS2.rcq.app", ci = ci)
        assertEquals(SenderTrust.Level.FORGED, atAddress.level)
        assertEquals("IS2.rcq.app", atAddress.host)
        // Somebody holding the same number on a third island is a stranger
        // there, held as a request under their own address.
        val elsewhere = decide(7, keyB, host = "is3.example", ci = ci, same = b64(keyA))
        assertEquals(SenderTrust.Level.UNPINNED, elsewhere.level)
        assertEquals("is3.example", elsewhere.host)
    }

    @Test fun a_contact_row_without_a_key_at_the_address_is_not_a_match() {
        val ci = listOf(SenderTrust.Pin("is2.rcq.app", null))
        assertEquals(SenderTrust.Level.FORGED, decide(7, keyA, host = "is2.rcq.app", ci = ci).level)
    }

    // ── unpinned ──

    @Test fun nobody_we_hold_a_key_for_is_a_stranger() {
        val d = decide(7, keyA)
        assertEquals(SenderTrust.Level.UNPINNED, d.level)
        assertFalse(d.askCard)
        assertNull(d.host)
        assertEquals("is2.rcq.app", decide(7, keyA, host = "is2.rcq.app").host)
    }

    @Test fun a_roster_contact_without_a_key_asks_the_card_first() {
        val d = decide(7, keyA, keyless = true)
        assertEquals(SenderTrust.Level.UNPINNED, d.level)
        assertTrue(d.askCard)
        // Even next to a contact elsewhere holding the number: the card for
        // the one here decides, and pins them.
        val ci = listOf(SenderTrust.Pin("is2.rcq.app", b64(keyB)))
        assertTrue(decide(7, keyA, keyless = true, ci = ci).askCard)
    }

    // ── v=2 ──

    @Test fun a_v2_row_speaks_for_the_number_on_our_island_only() {
        val contact = decide(7, v2, same = b64(keyA))
        assertEquals(SenderTrust.Level.PINNED, contact.level)
        assertTrue(contact.v2)
        assertNull(contact.host)
        assertEquals(SenderTrust.Level.PINNED, decide(7, v2, keyless = true).level)
        assertEquals(SenderTrust.Level.UNPINNED, decide(7, v2).level)
        // The number is a contact on another island only: not theirs.
        val ci = listOf(SenderTrust.Pin("is2.rcq.app", b64(keyA)))
        assertEquals(SenderTrust.Level.FORGED, decide(7, v2, ci = ci).level)
        // Attributed to another island (a guest mailbox): never pinned.
        assertEquals(SenderTrust.Level.UNPINNED, decide(7, v2, host = "is2.rcq.app", same = b64(keyA)).level)
        assertEquals(SenderTrust.Level.UNPINNED, decide(7, v2, same = b64(keyA), vouched = false).level)
    }

    // ── rooms ──

    private fun member(uin: Int, key: String?) =
        GroupMember(uin = uin, nickname = "m$uin", role = "member", identityKey = "ik", signingKey = key)

    private fun room(
        uin: Int,
        spub: ByteArray,
        member: GroupMember?,
        rosterKnown: Boolean = true,
        pinned: String? = null,
        foreign: Boolean = false,
        vouched: Boolean = spub.isEmpty(),
    ) = SenderTrust.decideRoom(uin, spub, me, keyMe, foreign, member, rosterKnown, pinned, vouched)

    @Test fun a_member_acts_only_under_the_key_held_for_them() {
        assertEquals(SenderTrust.Room.PINNED, room(7, keyA, member(7, b64(keyA))))
        assertEquals(SenderTrust.Room.FORGED, room(7, keyB, member(7, b64(keyA))))
    }

    @Test fun nobody_outside_the_roster_acts_and_no_roster_means_asking() {
        assertEquals(SenderTrust.Room.NOT_MEMBER, room(9, keyA, null))
        assertEquals(SenderTrust.Room.ASK_ROSTER, room(9, keyA, null, rosterKnown = false))
    }

    @Test fun a_member_without_a_key_is_no_longer_believed_by_number() {
        assertEquals(SenderTrust.Room.ASK_CARD, room(8, keyB, member(8, null)))
        // On another island there is no card of ours to ask.
        assertEquals(SenderTrust.Room.FORGED, room(8, keyB, member(8, null), foreign = true))
        // A pin we hold for the number stands in for the missing roster key.
        assertEquals(SenderTrust.Room.PINNED, room(8, keyB, member(8, null), pinned = b64(keyB)))
    }

    @Test fun the_pin_comes_before_the_rooms_roster_on_our_island() {
        assertEquals(SenderTrust.Room.FORGED, room(7, keyB, member(7, b64(keyB)), pinned = b64(keyA)))
        assertEquals(SenderTrust.Room.PINNED, room(7, keyA, member(7, b64(keyB)), pinned = b64(keyA)))
        // A room on another island: its own roster decides, our pin is about
        // somebody else who holds that number here.
        assertEquals(SenderTrust.Room.PINNED, room(7, keyB, member(7, b64(keyB)), pinned = b64(keyA), foreign = true))
    }

    @Test fun v2_in_a_room_needs_our_island_and_a_vouched_identity() {
        assertEquals(SenderTrust.Room.PINNED, room(7, v2, member(7, b64(keyA))))
        assertEquals(SenderTrust.Room.FORGED, room(7, v2, member(7, b64(keyA)), foreign = true))
        assertEquals(SenderTrust.Room.FORGED, room(7, v2, member(7, b64(keyA)), vouched = false))
        assertEquals(SenderTrust.Room.NOT_MEMBER, room(9, v2, null))
    }

    @Test fun our_own_number_in_a_room_is_ours_only_under_our_key() {
        assertEquals(SenderTrust.Room.SELF, room(me, keyMe, member(me, b64(keyMe))))
        assertEquals(SenderTrust.Room.FORGED, room(me, keyA, member(me, b64(keyMe))))
        assertEquals(SenderTrust.Room.SELF, room(me, v2, null))
        assertEquals(SenderTrust.Room.FORGED, room(me, v2, null, foreign = true))
    }

    // ── pins ──

    @Test fun a_pin_is_set_once_and_a_different_key_is_a_change() {
        assertEquals(SenderTrust.PinChange.FIRST, SenderTrust.pinChange(null, b64(keyA)))
        assertEquals(SenderTrust.PinChange.SAME, SenderTrust.pinChange(b64(keyA), b64(keyA)))
        assertEquals(SenderTrust.PinChange.SAME, SenderTrust.pinChange(b64(keyA), " " + b64(keyA) + "\n"))
        assertEquals(SenderTrust.PinChange.CHANGED, SenderTrust.pinChange(b64(keyA), b64(keyB)))
        assertEquals(SenderTrust.PinChange.NONE, SenderTrust.pinChange(b64(keyA), null))
        assertEquals(SenderTrust.PinChange.NONE, SenderTrust.pinChange(null, "not base64!"))
        assertEquals(SenderTrust.PinChange.NONE, SenderTrust.pinChange(null, b64(ByteArray(16))))
    }
}
