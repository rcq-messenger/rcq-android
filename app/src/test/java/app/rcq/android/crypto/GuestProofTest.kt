package app.rcq.android.crypto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * `rcq-guest-v1` against the island's own vector: `fixtures/guest-proof-v1.json`
 * from rcq-server-ref, copied verbatim into test resources (spec 2026-09-15,
 * sections 4.3 and 12.2). If this fails, the island refuses every guest join
 * this build makes with `bad_signature`.
 */
class GuestProofTest {

    private val fx: JsonObject = javaClass.classLoader!!.getResourceAsStream("guest-proof-v1.json")!!
        .use { JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject }

    private fun s(k: String) = fx.get(k).asString
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val seed = unhex(s("signing_seed_hex"))
    private val signingPub = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    private val identityPub = GuestProof.decodeKey32(s("identity_key_b64_unpadded"))!!

    private fun fixtureBytes() = GuestProof.proofBytes(
        s("host_input"), fx.get("group_id").asInt, identityPub, signingPub, s("challenge"),
    )

    @Test
    fun signingKeyComesFromTheSeed() {
        assertEquals(s("signing_key_b64"), GuestProof.canonicalKey(signingPub))
    }

    @Test
    fun proofBytesMatchTheFixtureByteForByte() {
        val bytes = fixtureBytes()
        assertEquals(s("proof_bytes_hex"), hex(bytes))
        assertEquals(s("proof_bytes_text"), bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun signatureMatchesTheFixtureAndVerifies() {
        val bytes = fixtureBytes()
        val sig = GuestProof.sign(seed, bytes)
        assertEquals(s("signature_b64"), sig)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PrivateKeyParameters(seed, 0).generatePublicKey())
            update(bytes, 0, bytes.size)
        }
        assertTrue(verifier.verifySignature(Base64.getDecoder().decode(sig)))
    }

    @Test
    fun hostSpellingsBindTheSameIsland() {
        assertEquals(s("canonical_host"), GuestProof.canonicalHost(s("host_input")))
        for (spelling in fx.getAsJsonArray("host_spellings_same_binding")) {
            assertEquals(spelling.asString, s("canonical_host"), GuestProof.canonicalHost(spelling.asString))
        }
    }

    @Test
    fun hostRulesFollowTheIsland() {
        // The port is part of the binding unless it is 443.
        assertEquals("island.example:8443", GuestProof.canonicalHost("Island.Example:8443"))
        assertEquals("island.example", GuestProof.canonicalHost(" island.example.:443 "))
        // IPv6 literals keep their brackets; a bare IPv6 has many colons and no port.
        assertEquals("[::1]", GuestProof.canonicalHost("[::1]:443"))
        assertEquals("[::1]:8443", GuestProof.canonicalHost("[::1]:8443"))
        assertEquals("::1", GuestProof.canonicalHost("::1"))
    }

    @Test
    fun paddedAndUnpaddedKeysAreOneKey() {
        assertArrayEquals(
            GuestProof.decodeKey32(s("identity_key_b64")),
            GuestProof.decodeKey32(s("identity_key_b64_unpadded")),
        )
        assertEquals(s("identity_key_b64"), GuestProof.canonicalKey(identityPub))
        assertNull(GuestProof.decodeKey32("AAAA"))
        assertNull(GuestProof.decodeKey32(""))
        assertNull(GuestProof.decodeKey32(null))
    }

    @Test
    fun inputThatCannotBeOneLineIsRefused() {
        val ik = identityPub
        val sk = signingPub
        fun refused(block: () -> Unit) {
            val thrown = runCatching(block).exceptionOrNull()
            assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
        }
        refused { GuestProof.proofBytes("api.rcq.app", 0, ik, sk, "c") }
        refused { GuestProof.proofBytes("api.rcq.app", -41, ik, sk, "c") }
        refused { GuestProof.proofBytes("api.rcq.app", 41, ik.copyOf(31), sk, "c") }
        refused { GuestProof.proofBytes("api.rcq.app", 41, ik, sk.copyOf(33), "c") }
        refused { GuestProof.proofBytes("api.rcq.app", 41, ik, sk, "") }
        refused { GuestProof.proofBytes("api.rcq.app", 41, ik, sk, "a\nb") }
        refused { GuestProof.proofBytes("api.rcq.app", 41, ik, sk, "a\rb") }
        refused { GuestProof.proofBytes("", 41, ik, sk, "c") }
        refused { GuestProof.proofBytes("api.rcq.app\nevil", 41, ik, sk, "c") }
    }

    @Test
    fun everyBoundFieldChangesTheBytes() {
        val base = hex(fixtureBytes())
        val gid = fx.get("group_id").asInt
        val other = ByteArray(32) { 7 }
        assertTrue(base != hex(GuestProof.proofBytes("api.rcq.app:8443", gid, identityPub, signingPub, s("challenge"))))
        assertTrue(base != hex(GuestProof.proofBytes(s("host_input"), gid + 1, identityPub, signingPub, s("challenge"))))
        assertTrue(base != hex(GuestProof.proofBytes(s("host_input"), gid, other, signingPub, s("challenge"))))
        assertTrue(base != hex(GuestProof.proofBytes(s("host_input"), gid, identityPub, other, s("challenge"))))
        assertTrue(base != hex(GuestProof.proofBytes(s("host_input"), gid, identityPub, signingPub, s("challenge") + "x")))
    }
}
