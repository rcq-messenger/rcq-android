package app.rcq.android.net

import app.rcq.android.crypto.Envelope
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

/**
 * P0.1, send side: every carbon leaves this client as ONE v=1 copy signed by our
 * own account key, the one form every client's carbon gate takes for every kind.
 *
 * Before this NO carbon was sealed that way. Message, edit, delete, read-marker
 * and ciack carbons all went through `encryptFor(me, carbon)`, which with v=2
 * outbound on by default produced ratchet copies with no `spub`, and a client
 * that checks the key strictly (the first cut of the web gate did, for every
 * kind) drops them: whatever was done on the phone would stop syncing to the
 * account's other clients. For the ciack that refusal is kept on every client
 * on purpose (see CarbonGateTest).
 *
 * [Session][app.rcq.android.Session] cannot be built in a plain JVM test, and
 * the seal itself rides `android.util.Base64`, which this runtime lacks (see
 * SealedSenderPaddingTest), so the send path is pinned at the source: an
 * [Envelope.Carbon] is built in exactly one place, `sendOwnCarbon`, which seals
 * with `SealedSender.encryptV1` under our own signing key and never through
 * `encryptFor`, and every carbon sender calls it. What that seal carries is then
 * run through the receive gate.
 */
class CarbonSealTest {

    private fun sourceFile(rel: String): File =
        listOf(File("src/main/java/$rel"), File("app/src/main/java/$rel")).firstOrNull { it.isFile }
            ?: error("source not found: $rel (working dir ${File(".").absolutePath})")

    private val sessionFile by lazy { sourceFile("app/rcq/android/Session.kt") }
    private val session by lazy { sessionFile.readText() }

    /** The body of `fun [name]`, from its opening brace to the matching one. */
    private fun body(src: String, name: String): String {
        val at = src.indexOf("fun $name(")
        assertTrue("fun $name not found", at >= 0)
        val open = src.indexOf('{', at)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open, i + 1)
            }
        }
        error("unbalanced body of $name")
    }

    /** A constructor call of the carbon envelope, bare or qualified, but not
     *  `storeCarbon(`, `sendOwnCarbon(` and the like. */
    private val carbonCtor = Regex("""(?<![A-Za-z0-9_])Carbon\(""")

    @Test
    fun aCarbonIsBuiltOnlyInsideSendOwnCarbon() {
        val root = sessionFile.parentFile // .../app/rcq/android
        val sealer = body(session, "sendOwnCarbon")
        assertEquals("one carbon constructor in sendOwnCarbon", 1, carbonCtor.findAll(sealer).count())
        assertEquals("no carbon constructor elsewhere in Session", 1, carbonCtor.findAll(session).count())
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it != sessionFile }
            // The type itself and its wire decoder.
            .filterNot { it.invariantSeparatorsPath.endsWith("crypto/Envelope.kt") }
            .forEach { f ->
                assertFalse("carbon built in ${f.name}", carbonCtor.containsMatchIn(f.readText()))
            }
    }

    @Test
    fun sendOwnCarbonSealsOneV1CopyUnderOurOwnKey() {
        val sealer = body(session, "sendOwnCarbon")
        assertTrue(sealer.contains("SealedSender.encryptV1("))
        assertTrue(sealer.contains("recipientKey(me)"))
        assertTrue(sealer.contains("signingPriv()"))
        assertTrue(sealer.contains("signingPub()"))
        // One unaddressed copy: v=1 seals to the key every install holds.
        assertTrue(sealer.contains("SealedCopy(null,"))
        assertTrue(sealer.contains("sendSealedCopies(me,"))
        // Never the negotiating path, whose v=2 copies carry no spub.
        assertFalse(sealer.contains("encryptFor("))
        assertFalse(sealer.contains("SignalSession"))
    }

    @Test
    fun everyCarbonSenderGoesThroughIt() {
        for (sender in listOf("sendMessageCarbon", "sendReadMarker", "sendCiAck")) {
            val b = body(session, sender)
            assertTrue("$sender calls sendOwnCarbon", b.contains("sendOwnCarbon("))
            assertFalse("$sender seals on its own", b.contains("encryptFor(") || b.contains("encryptV1("))
        }
        // Nothing seals to our own number through the negotiating path.
        assertFalse(session.contains("encryptFor(me"))
    }

    @Test
    fun whatTheSealCarriesPassesTheGateForEveryKind() {
        val me = 1000
        // Our signing key, derived the way Session.signingPub() derives it.
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val spub = Ed25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded
        val kinds = listOf(
            Envelope.Text("id", "hi"),
            Envelope.Edit("id", "hi again"),
            Envelope.Delete("id"),
            Envelope.ReadMark(1L),
            Envelope.CiAck(42, "b.example", "accept", Envelope.CiCard("x", "ik", "sk", null, null, null)),
            Envelope.CiAck(42, "b.example", "block"),
        )
        // A v=1 copy names no device and carries our spub.
        val own = CrossIslandGate.ownNumberRow(me, me, spub, spub.copyOf())
        assertEquals(CrossIslandGate.OwnNumber.OURS_SIGNED, own)
        for (inner in kinds) {
            assertTrue("$inner", CrossIslandGate.carbonAccepted(own, null, inner))
        }
        // The keyless ratchet copy the old path produced (for the ciack too) is
        // not enough for a ciack on any client, which is why nothing is sealed
        // that way now.
        val keyless = CrossIslandGate.ownNumberRow(me, me, ByteArray(0), spub)
        assertFalse(CrossIslandGate.carbonAccepted(keyless, 2, kinds[4]))
    }
}
