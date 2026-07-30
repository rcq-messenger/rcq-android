package app.rcq.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64 as JBase64

/** Proves the seed→identity derivation is byte-for-byte reproducible OUTSIDE
 *  the app, so an operator recovery script (server-side reissue of a lost
 *  account's keys from a freshly generated phrase) mints exactly the keypair
 *  this client would. The expected base64 values are computed independently in
 *  Python (HKDF-SHA256 + X25519/Ed25519). If this ever fails, DO NOT run an
 *  operator recovery — the derivations have diverged. */
class IdentityKeysParityTest {

    // 32 bytes of 0x01.
    private val fixedSeed = ByteArray(32) { 1 }

    @Test fun fromSeed_matches_python() {
        val id = IdentityKeys.fromSeed(fixedSeed)
        val x = JBase64.getEncoder().encodeToString(id.identityPublic)
        val e = JBase64.getEncoder().encodeToString(id.signingPublic)
        assertEquals("QNj6L1ghrmCB6m0v7NoLmTkENLqATaGLzuaQfceGgBc=", x)
        assertEquals("BL6cGb2IBUz+oMHoU6U7hnE53SDqxf9fZzABWOAQADc=", e)
    }
}
