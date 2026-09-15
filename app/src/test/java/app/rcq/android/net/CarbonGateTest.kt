package app.rcq.android.net

import app.rcq.android.crypto.Envelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.1 of the 15.09 cross-island spec: a carbon is ours only under our own key,
 * with the same transitional allowance for a keyless carbon that iOS makes.
 *
 * `from` sits outside the v=1 signature and deposits are open, so anybody who
 * reads our key card can seal a "carbon" naming our number. Before this, one was
 * applied like a real one: a ciack `accept` pinned the sender's keys for any
 * address the device did not hold, and edits and deletes rewrote our own rows.
 * Each case runs the two pure steps the ingest runs, in order:
 * [CrossIslandGate.ownNumberRow] then [CrossIslandGate.carbonAccepted].
 *
 * The rule, by what the row carries:
 *  - a `spub` that is our own key: every kind passes;
 *  - a `spub` that is any other key: nothing passes;
 *  - no `spub` (v=2) and a device id: every kind but a ciack passes, because
 *    earlier Android builds sealed their carbons that way and a refused carbon
 *    is acked and lost;
 *  - a ciack without our own key: never.
 *
 * ⚠ That last line refuses real ciacks too, for as long as older Android builds
 * are around: those sealed EVERY carbon, the ciack included, as a keyless v=2
 * copy. A request answered on such a phone stays pending on the account's other
 * installs and has to be answered again there. That is accepted: a keyless
 * ciack cannot be told apart from a forged one, and refusing it is the fix.
 */
class CarbonGateTest {

    private val me = 1000
    private val mine = ByteArray(32) { it.toByte() }
    private val other = ByteArray(32) { (it + 7).toByte() }
    private val v2 = ByteArray(0)

    private val ciack = Envelope.CiAck(42, "b.example", "accept", Envelope.CiCard("x", "ik", "sk", null, null, null))
    private val ciackBlock = Envelope.CiAck(42, "b.example", "block")
    private val nonCiack = listOf<Envelope>(
        Envelope.Text("id", "hi"),
        Envelope.Edit("id", "hi again"),
        Envelope.Delete("x"),
        Envelope.ReadMark(1L),
    )

    private fun accepted(senderUin: Int, spub: ByteArray, ownSpub: ByteArray?, device: Int?, inner: Envelope): Boolean =
        CrossIslandGate.carbonAccepted(CrossIslandGate.ownNumberRow(senderUin, me, spub, ownSpub), device, inner)

    @Test
    fun carbonClaimingOurUinUnderAnotherKeyIsRefused() {
        for (inner in nonCiack + ciack + ciackBlock) {
            assertFalse("$inner", accepted(me, other, mine, null, inner))
            assertFalse("$inner with a device id", accepted(me, other, mine, 3, inner))
        }
    }

    @Test
    fun ciackIsNeverAppliedWithoutOurKey() {
        // Under a foreign key.
        assertFalse(accepted(me, other, mine, null, ciack))
        // v=2: no signature at all, even over a real-looking session row.
        assertFalse(accepted(me, v2, mine, 2, ciack))
        assertFalse(accepted(me, v2, mine, 2, ciackBlock))
        assertFalse(accepted(me, v2, mine, 1, ciack))
        assertFalse(accepted(me, v2, mine, null, ciack))
        // Our own key is unknown to this install: nothing can vouch.
        assertFalse(accepted(me, mine, null, null, ciack))
        // A key of the wrong length never equals ours.
        assertFalse(accepted(me, mine.copyOf(31), mine, null, ciack))
    }

    @Test
    fun ourOwnSignatureCarriesEveryKind() {
        for (inner in nonCiack + ciack + ciackBlock) {
            assertTrue("$inner", accepted(me, mine.copyOf(), mine, null, inner))
        }
    }

    @Test
    fun keylessCarbonFromOurOwnDeviceStillSyncs() {
        // The regression this pins: a message sent, edited, deleted or read on
        // an Android build that seals carbons over v=2 must keep reaching the
        // account's other installs. The primary slot, a linked install, and an
        // id no cached list names (freshly linked, or re-keyed in place) alike:
        // nothing about the device list is consulted.
        for (inner in nonCiack) {
            for (device in listOf(1, 2, 7)) {
                assertTrue("$inner from device $device", accepted(me, v2, mine, device, inner))
            }
            // The allowance never rested on our own key being known here.
            assertTrue("$inner, own key unknown", accepted(me, v2, null, 2, inner))
            // A v=2 row always names its device; one that does not is no session row.
            assertFalse("$inner without a device", accepted(me, v2, mine, null, inner))
        }
    }

    @Test
    fun anotherNumberIsNeverACarbon() {
        for (inner in nonCiack + ciack) {
            assertFalse(accepted(me + 1, mine, mine, null, inner))
            assertFalse(accepted(me + 1, v2, mine, 2, inner))
        }
        // No own number yet (meUin 0) is never ours either.
        assertFalse(CrossIslandGate.carbonAccepted(CrossIslandGate.ownNumberRow(0, 0, mine, mine), null, ciack))
    }
}
