package app.rcq.android.net

import app.rcq.android.crypto.Envelope
import app.rcq.android.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-island consent gate (#985(1)) and the verified-sender check it
 * rests on.
 *
 * What each group of cases pins:
 *   kinds    — only what a person reads as a message may open a request row;
 *              control traffic from co-members of a room on another island
 *              used to open phantom requests with an empty preview.
 *   drop     — control from a stranger is DROPPED, never let through to the
 *              handlers that would apply a delete, an edit or secure-screen.
 *   spub     — an address is believed only under the signing key pinned for
 *              it: from/from_host sit outside the v=1 signature.
 */
class CrossIslandGateTest {

    private val keyA = ByteArray(32) { it.toByte() }
    private val keyB = ByteArray(32) { (it + 1).toByte() }
    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)

    private val content = listOf<Envelope>(
        Envelope.Text("id", "hi"),
        Envelope.Photo("id", "m", "k", null),
        Envelope.Location("id", 1.0, 2.0, null),
    )
    private val control = listOf<Envelope>(
        Envelope.Visit(1.0),
        Envelope.ReadReceipt(listOf("x")),
        Envelope.DeliveredReceipt(listOf("x")),
        Envelope.Reaction("x", "a"),
        Envelope.Delete("x"),
        Envelope.Edit("x", "y"),
        Envelope.SecureScreen(true),
        Envelope.ScreenshotTaken("x"),
        Envelope.PKey("k"),
        Envelope.PKeyAsk,
        Envelope.GsKey(1, 1L, "k"),
        Envelope.GsKnack(1),
        Envelope.Unknown("future"),
    )

    @Test fun content_kinds_are_held_from_a_stranger() {
        content.forEach {
            assertTrue(CrossIslandGate.isContentKind(it))
            assertEquals(CrossIslandGate.Verdict.HOLD, CrossIslandGate.verdict(true, false, false, it))
        }
    }

    @Test fun control_kinds_from_a_stranger_are_dropped_not_held() {
        control.forEach {
            assertFalse("$it", CrossIslandGate.isContentKind(it))
            assertEquals("$it", CrossIslandGate.Verdict.DROP, CrossIslandGate.verdict(true, false, false, it))
        }
    }

    @Test fun same_island_rows_are_not_this_gates_business() {
        (content + control).forEach {
            assertEquals(CrossIslandGate.Verdict.PASS, CrossIslandGate.verdict(false, false, false, it))
        }
    }

    @Test fun a_verified_contact_or_our_own_key_passes_everything() {
        (content + control).forEach {
            assertEquals(CrossIslandGate.Verdict.PASS, CrossIslandGate.verdict(true, false, true, it))
            assertEquals(CrossIslandGate.Verdict.PASS, CrossIslandGate.verdict(true, true, false, it))
        }
    }

    @Test fun signing_key_must_match_the_pin_exactly() {
        assertTrue(CrossIslandGate.signingKeyMatches(b64(keyA), keyA))
        assertTrue(CrossIslandGate.signingKeyMatches("  ${b64(keyA)}\n", keyA))
        assertFalse(CrossIslandGate.signingKeyMatches(b64(keyA), keyB))
    }

    @Test fun no_pin_or_a_broken_one_is_never_a_match() {
        assertFalse(CrossIslandGate.signingKeyMatches(null, keyA))
        assertFalse(CrossIslandGate.signingKeyMatches("", keyA))
        assertFalse(CrossIslandGate.signingKeyMatches("not base64 !!", keyA))
        assertFalse(CrossIslandGate.signingKeyMatches(b64(ByteArray(16)), ByteArray(16)))
        // A v=2 row carries no signing key at all.
        assertFalse(CrossIslandGate.signingKeyMatches(b64(keyA), ByteArray(0)))
    }

    @Test fun contact_match_tells_a_stranger_from_an_impostor() {
        assertEquals(CrossIslandGate.ContactMatch.NONE, CrossIslandGate.contactMatch(null, false, keyA))
        assertEquals(CrossIslandGate.ContactMatch.VERIFIED, CrossIslandGate.contactMatch(b64(keyA), true, keyA))
        assertEquals(CrossIslandGate.ContactMatch.KEY_MISMATCH, CrossIslandGate.contactMatch(b64(keyA), true, keyB))
        assertEquals(CrossIslandGate.ContactMatch.KEY_MISMATCH, CrossIslandGate.contactMatch(null, true, keyA))
    }

    @Test fun a_room_member_on_another_island_is_proven_by_the_roster_key() {
        val roster = listOf(
            GroupMember(uin = 7, nickname = "a", role = "member", identityKey = "ik", signingKey = b64(keyA)),
            GroupMember(uin = 8, nickname = "b", role = "member", identityKey = "ik", signingKey = null),
        )
        assertEquals(7, CrossIslandGate.verifiedMember(roster, 7, keyA)?.uin)
        // Right number, wrong key: somebody wrote the member's address.
        assertNull(CrossIslandGate.verifiedMember(roster, 7, keyB))
        // A roster row without a key proves nothing cross-island.
        assertNull(CrossIslandGate.verifiedMember(roster, 8, keyA))
        assertNull(CrossIslandGate.verifiedMember(roster, 9, keyA))
    }

    // The host is not an input to this check at all, which is the point: a row
    // that uses our number with a foreign key is refused whether it stamps our
    // own island, another island or no island.
    @Test fun our_own_number_under_a_foreign_key_is_forged_whatever_the_host() {
        val me = 1000
        assertEquals(CrossIslandGate.OwnNumber.FORGED, CrossIslandGate.ownNumberRow(me, me, keyB, keyA))
        assertEquals(CrossIslandGate.OwnNumber.OURS_SIGNED, CrossIslandGate.ownNumberRow(me, me, keyA, keyA))
        // v=2 carries no signing key; the ratchet authenticates it.
        assertEquals(CrossIslandGate.OwnNumber.OURS_V2, CrossIslandGate.ownNumberRow(me, me, ByteArray(0), keyA))
        // Ed25519 verification reads only the first 32 bytes, so a longer spub
        // that starts with our key must not pass as ours.
        assertEquals(CrossIslandGate.OwnNumber.FORGED, CrossIslandGate.ownNumberRow(me, me, keyA + keyB, keyA))
        // No own key: nothing can vouch for a v=1 row.
        assertEquals(CrossIslandGate.OwnNumber.FORGED, CrossIslandGate.ownNumberRow(me, me, keyA, null))
        assertEquals(CrossIslandGate.OwnNumber.NOT_OURS, CrossIslandGate.ownNumberRow(7, me, keyB, keyA))
        assertEquals(CrossIslandGate.OwnNumber.NOT_OURS, CrossIslandGate.ownNumberRow(0, 0, keyB, keyA))
    }

    @Test fun own_room_key_needs_the_roster_key_on_a_signed_row() {
        val roster = listOf(
            GroupMember(uin = 7, nickname = "a", role = "member", identityKey = "ik", signingKey = b64(keyA)),
            GroupMember(uin = 8, nickname = "b", role = "member", identityKey = "ik", signingKey = null),
        )
        assertEquals(7, CrossIslandGate.ownRoomMember(roster, 7, keyA)?.uin)
        assertNull(CrossIslandGate.ownRoomMember(roster, 7, keyB))
        // v=2: the ratchet vouches for the number.
        assertEquals(7, CrossIslandGate.ownRoomMember(roster, 7, ByteArray(0))?.uin)
        // A roster without a key for the member leaves only the number.
        assertEquals(8, CrossIslandGate.ownRoomMember(roster, 8, keyB)?.uin)
        assertNull(CrossIslandGate.ownRoomMember(roster, 9, keyA))
    }

    @Test fun a_guest_mailbox_row_without_a_foreign_host_belongs_to_that_island() {
        val own = listOf("home.example", "cdn.rcq.app")
        assertEquals("b.example", CrossIslandGate.attributedHost(null, "b.example", own))
        assertEquals("b.example", CrossIslandGate.attributedHost("HOME.example", "b.example", own))
        assertEquals("b.example", CrossIslandGate.attributedHost("cdn.rcq.app", "b.example", own))
        assertEquals("c.example", CrossIslandGate.attributedHost("c.example", "b.example", own))
        // Our own mailboxes (primary, backup homes) keep what the row says.
        assertNull(CrossIslandGate.attributedHost(null, null, own))
        assertEquals("home.example", CrossIslandGate.attributedHost("home.example", null, own))
    }
}
