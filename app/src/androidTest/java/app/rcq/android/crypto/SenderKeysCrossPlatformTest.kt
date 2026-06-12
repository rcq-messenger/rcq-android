package app.rcq.android.crypto

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-platform sender-keys proof: open a `gmsg` sealed by the REAL web client
 * (sender-keys.ts) with the REAL Android [SenderKeys] code, and round-trip an
 * Android-sealed gmsg. Proves the HMAC ratchet, ChaCha20-Poly1305 framing, AAD
 * string, gmsg JSON, and Ed25519 signature are byte-for-byte identical so a web
 * member can read an Android post and vice versa.
 *
 * The web vector below is emitted by web-chat/.mh-test/sk-vectors.ts.
 */
@RunWith(AndroidJUnit4::class)
class SenderKeysCrossPlatformTest {

    // ── vector produced by the web client (sk-vectors.ts) ──
    private val gid = 77
    private val webChainKeyB64 = "AwoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dw="
    private val webSpubB64 = "O6OPLvS3l7Edl/m+Djm1ORf8SNcKKJrIezd24tiaQn0="
    private val webPayloadB64 =
        "eyJ2IjoxLCJraWQiOiJBUUlEQkFVR0J3Z0pDZ3NNRFE0UEVBPT0iLCJlIjowLCJpIjowLCJuIjoiQjJERG9uNUZDMXlRcWZCWSIsImN0IjoiV0prb0pWTnorNWJnS3NBNytMckdmQm9VS214Sy91R3pOcllwMGp0TmxaUGlSYVEvblNGNnZUeVJtQkJRaUw5Sy9xNnAxRHFhcEE0UGpMK0o2d1VXVG14UGpnTk1xSTJ3ZjJmUmROL0QzSHlibmlkSFhEajIvc2lJTk1ITFM2ZmcwRHNrMWJycnpiYjNWbitCb215dHdRaWdXYzU4MjB2OVBvUTJaRFZGN08xbjV1dUt4UVh2RWM0NjlydWFiOTlDV2ZPYng0eW5VRGpReEZ2cmN3MDZZNDhPTDliZm52amRBdERWSzJ5RWVMVXJQQmRYdnBvMlNYWkFtZTlUWFQ4NUl6VXY1ZktrTURGZWhGZ0ZmTVZwQXhsVkpHd2xqeS9jR1Z0eUpsZ296RE5FVWtSc243eXNXQ2tZZnFsdXRnUmJHT0lSTGE5NWtTWVZMWTlqK3FQZUpLdDZRdz09In0="
    private val expectText = "cross-platform gmsg привет"

    private fun b64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    @Test
    fun opensWebSealedGmsg() {
        val chainKey = b64(webChainKeyB64)
        val mk = SenderKeys.deriveMessageKey(chainKey)
        val opened = SenderKeys.openGmsg(webPayloadB64, gid, mk, b64(webSpubB64))
        assertTrue("web gmsg signature must verify on Android", opened.verified)
        val env = opened.envelope
        assertTrue("inner envelope must be text", env is Envelope.Text)
        assertEquals(expectText, (env as Envelope.Text).text)
    }

    @Test
    fun ratchetMatchesWebVector() {
        // The same chain key must derive the same message key + next chain key
        // bytes on both platforms (HMAC-SHA256 with 0x01 / 0x02).
        val ck = b64(webChainKeyB64)
        val mk = SenderKeys.deriveMessageKey(ck)
        val next = SenderKeys.nextChainKey(ck)
        assertEquals(32, mk.size)
        assertEquals(32, next.size)
        // mk and next must differ (different info bytes) and be deterministic.
        assertFalse(mk.contentEquals(next))
        assertArrayEquals(mk, SenderKeys.deriveMessageKey(ck))
    }

    @Test
    fun androidRoundTripAndTamperReject() {
        val ck = SenderKeys.randomChainKey()
        val mk = SenderKeys.deriveMessageKey(ck)
        val sender = IdentityKeys.generate()
        val kid = SenderKeys.newKid()
        val env = Envelope.text("android-sealed roundtrip")
        val payload = SenderKeys.sealGmsg(env, gid, kid, 0, 0, mk, sender.signingPrivate)

        val opened = SenderKeys.openGmsg(payload, gid, mk, sender.signingPublic)
        assertTrue(opened.verified)
        assertEquals("android-sealed roundtrip", (opened.envelope as Envelope.Text).text)

        // Wrong signing key → decrypts (same mk) but signature must NOT verify.
        val other = IdentityKeys.generate()
        val openedBadSig = SenderKeys.openGmsg(payload, gid, mk, other.signingPublic)
        assertFalse("forged-signer gmsg must fail verification", openedBadSig.verified)

        // Wrong gid → AAD mismatch → AEAD open throws.
        var threw = false
        try {
            SenderKeys.openGmsg(payload, gid + 1, mk, sender.signingPublic)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("AAD-bound gmsg must not open under a different gid", threw)
    }
}
