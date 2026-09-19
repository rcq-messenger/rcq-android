package app.rcq.android.crypto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * `rcq-reissue-v1` against the island's own vector:
 * `fixtures/reissue-proof-v1.json` from rcq-server-ref, copied verbatim into
 * test resources (spec 2026-09-15, F3).
 *
 * If this fails, every key rotation this build makes is refused with
 * `bad_signature`, and a rotation refused halfway is the worst outcome there
 * is: the home island holds keys the copies do not.
 */
class ReissueProofTest {

    private val fx: JsonObject = javaClass.classLoader!!.getResourceAsStream("reissue-proof-v1.json")!!
        .use { JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject }

    private fun s(k: String) = fx.get(k).asString
    private fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun key(k: String): ByteArray = Base64.getDecoder().decode(s(k))

    private val oldSeed = unhex(s("old_signing_seed_hex"))
    private val oldPub = Ed25519PrivateKeyParameters(oldSeed, 0).generatePublicKey().encoded

    private fun fixtureBytes() = ReissueProof.proofBytes(
        s("host"), fx.get("uin").asInt, oldPub,
        key("new_identity_key"), key("new_signing_key"),
        fx.get("ts").asLong, s("nonce"),
    )

    @Test
    fun theOldPublicKeyComesFromTheSeed() {
        assertEquals(s("old_signing_key"), ReissueProof.canonicalKey(oldPub))
    }

    @Test
    fun bytesMatchTheIslandsVectorExactly() {
        assertEquals(s("bytes_text"), fixtureBytes().toString(Charsets.UTF_8))
        assertArrayEquals(unhex(s("bytes_hex")), fixtureBytes())
    }

    @Test
    fun ourSignatureIsTheVectorsSignature() {
        // Ed25519 is deterministic, so the same key over the same bytes gives
        // the same 64 bytes as the island's vector. A mismatch here is a
        // different message, not a different signer.
        assertEquals(s("signature"), ReissueProof.sign(oldSeed, fixtureBytes()))
    }

    @Test
    fun theIslandWouldVerifyIt() {
        val sig = Base64.getDecoder().decode(ReissueProof.sign(oldSeed, fixtureBytes()))
        val v = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(oldPub, 0))
            val m = fixtureBytes()
            update(m, 0, m.size)
        }
        assertTrue(v.verifySignature(sig))
    }

    @Test
    fun spellingsOfTheSameIslandAreTheSameBinding() {
        val canonical = fixtureBytes()
        fx.getAsJsonArray("host_spellings_same_binding").forEach { spelling ->
            val other = ReissueProof.proofBytes(
                spelling.asString, fx.get("uin").asInt, oldPub,
                key("new_identity_key"), key("new_signing_key"),
                fx.get("ts").asLong, s("nonce"),
            )
            assertArrayEquals("spelling ${spelling.asString}", canonical, other)
        }
    }

    @Test
    fun aPortThatIsNotFourFourThreeStaysInTheBinding() {
        val ex = fx.getAsJsonObject("host_with_port_example")
        assertEquals(ex.get("canonical").asString, ReissueProof.canonicalHost(ex.get("input").asString))
    }

    @Test
    fun anotherIslandIsAnotherMessage() {
        // ⚠ The point of the whole layout: a proof made for the home island
        // must not authorise the copy on somebody else's island.
        val elsewhere = ReissueProof.proofBytes(
            "island.example", fx.get("uin").asInt, oldPub,
            key("new_identity_key"), key("new_signing_key"),
            fx.get("ts").asLong, s("nonce"),
        )
        assertNotEquals(hex(fixtureBytes()), hex(elsewhere))
    }

    @Test
    fun anotherNumberIsAnotherMessage() {
        // The guest copy on another island has its OWN number there, and the
        // proof names it: one cascade, one proof per target.
        val otherUin = ReissueProof.proofBytes(
            s("host"), fx.get("uin").asInt + 1, oldPub,
            key("new_identity_key"), key("new_signing_key"),
            fx.get("ts").asLong, s("nonce"),
        )
        assertNotEquals(hex(fixtureBytes()), hex(otherUin))
    }

    @Test
    fun aFreshNonceIsTwentyTwoUrlSafeCharactersAndNotTheLastOne() {
        val a = ReissueProof.newNonce()
        val b = ReissueProof.newNonce()
        assertEquals(22, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(a, b)
    }

    @Test
    fun impossibleInputIsRefusedHereRatherThanOnTheWire() {
        val ik = key("new_identity_key")
        val sk = key("new_signing_key")
        val n = s("nonce")
        val ts = fx.get("ts").asLong
        val uin = fx.get("uin").asInt
        listOf<() -> Unit>(
            { ReissueProof.proofBytes(s("host"), 0, oldPub, ik, sk, ts, n) },
            { ReissueProof.proofBytes(s("host"), uin, ByteArray(31), ik, sk, ts, n) },
            { ReissueProof.proofBytes(s("host"), uin, oldPub, ByteArray(0), sk, ts, n) },
            { ReissueProof.proofBytes("", uin, oldPub, ik, sk, ts, n) },
            { ReissueProof.proofBytes(s("host"), uin, oldPub, ik, sk, 0, n) },
            { ReissueProof.proofBytes(s("host"), uin, oldPub, ik, sk, ts, "too-short") },
            { ReissueProof.proofBytes(s("host"), uin, oldPub, ik, sk, ts, n.dropLast(1) + "+") },
        ).forEachIndexed { i, bad ->
            val threw = runCatching { bad() }.isFailure
            assertTrue("case $i should have been refused", threw)
        }
    }
}
