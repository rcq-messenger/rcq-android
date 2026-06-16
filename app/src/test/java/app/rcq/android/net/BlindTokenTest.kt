package app.rcq.android.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * JVM unit test for the F3 deposit-auth client crypto ([BlindToken]). Runs on the
 * host JVM (`./gradlew testDebugUnitTest`) — no emulator. Proves the blind-RSA
 * chain is internally correct AND interoperates with the Python issuer:
 *
 *  1. `gen-deposit-auth-vectors.py` writes /tmp/depauth_vectors.txt (key + a
 *     Python-issued token).
 *  2. this test verifies that token (python -> java) and emits its own to
 *     /tmp/depauth_java_token.txt, playing the issuer locally via d.
 *  3. `verify-client-token.py` checks the java token (java -> python).
 *
 * The interop test self-skips if the vectors file is absent (so a bare
 * `gradlew test` still runs the internal roundtrip).
 */
class BlindTokenTest {

    @Test
    fun internalRoundtrip() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val priv = kp.private as RSAPrivateKey
        val n = pub.modulus
        val e = pub.publicExponent
        val d = priv.privateExponent

        val prepared = BlindToken.prepare()
        val b = BlindToken.blind(n, e, prepared)
        val sig = BlindToken.finalize(localBlindSign(b.blinded, d, n), b.blindInv, n)
        assertTrue("java blind-RSA roundtrip verifies as standard RSA-PSS", BlindToken.verify(n, e, prepared, sig))

        val bad = sig.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertFalse("a tampered signature is rejected", BlindToken.verify(n, e, prepared, bad))
        assertFalse("a random signature is rejected", BlindToken.verify(n, e, prepared, ByteArray(sig.size)))

        val challenge = "epoch-abc:deadbeef"
        val nonce = BlindToken.solvePow(challenge, 12)
        assertTrue("solved PoW verifies", BlindToken.verifyPow(challenge, nonce, 12))
        assertFalse("PoW solution for a different challenge is rejected", BlindToken.verifyPow("epoch-abc:other", nonce, 12))
    }

    @Test
    fun interopWithPython() {
        val vf = File("/tmp/depauth_vectors.txt")
        assumeTrue("python vectors present (run gen-deposit-auth-vectors.py first)", vf.exists())
        val v = parseKv(vf.readText())
        val n = BigInteger(v.getValue("n_hex"), 16)
        val e = BigInteger(v.getValue("e"))
        val d = BigInteger(v.getValue("d_hex"), 16)

        // python -> java: the Python-issued token must verify under our verifier.
        val pyPrepared = Base64.getDecoder().decode(v.getValue("py_prepared_b64"))
        val pySig = Base64.getDecoder().decode(v.getValue("py_sig_b64"))
        assertTrue("python-issued token verifies in java (python -> java interop)", BlindToken.verify(n, e, pyPrepared, pySig))

        // java -> python: mint a token (issuer played locally via d), emit for python to verify.
        val prepared = BlindToken.prepare()
        val b = BlindToken.blind(n, e, prepared)
        val sig = BlindToken.finalize(localBlindSign(b.blinded, d, n), b.blindInv, n)
        assertTrue("java token self-verifies", BlindToken.verify(n, e, prepared, sig))
        File("/tmp/depauth_java_token.txt").writeText(
            "prepared_b64=${Base64.getEncoder().encodeToString(prepared)}\n" +
                "sig_b64=${Base64.getEncoder().encodeToString(sig)}\n",
        )
    }

    /** Issuer side (test only): blind_sig = blinded^d mod n. */
    private fun localBlindSign(blinded: ByteArray, d: BigInteger, n: BigInteger): ByteArray =
        BlindToken.i2osp(BlindToken.os2ip(blinded).modPow(d, n), (n.bitLength() + 7) / 8)

    private fun parseKv(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
        }.toMap()
}
