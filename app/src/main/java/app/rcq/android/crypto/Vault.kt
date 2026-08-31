package app.rcq.android.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * The vault, crypto half: opaque, versioned, client-sealed slots on the island
 * (spec §4.9; web `src/lib/vault.ts`, which this matches byte for byte, see
 * VaultParityTest).
 *
 * Slot name and key both come from the account's long-term X25519 identity
 * private key, not from the recovery seed: a browser linked from a phone, a
 * legacy raw-key account and anyone who chose "forget the phrase" have no
 * seed, while every device of an account holds identity_priv. Same HKDF shape
 * as [IdentityKeys.fromSeed] (HKDF-SHA256, null salt == 32 zero bytes, a fixed
 * info string), which is already proven identical across the three clients.
 *
 *   slot = hex( HKDF(identity_priv, zeros, "rcq.vault.slot.v1|" + name, 16) )
 *   key  =      HKDF(identity_priv, zeros, "rcq.vault.key.v1|"  + slot, 32)
 *   blob = 0x01 || nonce(12) || ChaCha20-Poly1305(key, nonce, padded,
 *                                aad = "rcq.vault.v1|" + slot + "|" + version)
 *
 * The island sees a random-looking slot name rather than "contacts". The
 * version is in the AAD so the island cannot relabel one version as another.
 * `padded` is a 4-byte big-endian length, the plaintext, and zero fill to the
 * next 512-byte boundary: the island learns a size class, not a size.
 */
object Vault {
    const val CONTACTS = "contacts"

    /** The chat-list sections (founder item 1 of 23.08). A second slot rather
     *  than a field in [CONTACTS]: the two are written by different code paths
     *  at different moments, and one 409 loop must not stall the other. Derived
     *  the same way, so the island still sees only 32 hex characters. */
    const val SECTIONS = "sections"

    /** My profile key: the AES key my avatar blob is sealed under, mirrored
     *  here so a second install of this account reuses it instead of minting a
     *  rival one (docs/profile-key-design.md).
     *
     *  ⚠⚠ The literal is load-bearing and must stay exactly "pkey". [slotId]
     *  hashes the NAME, and the web derives the same 16 bytes from the same
     *  literal (web-chat/src/lib/profile-key.ts, VAULT_PKEY). A different
     *  spelling does not fail - it succeeds against a slot nobody else reads,
     *  so a phone and a browser on one account mint two keys and half the
     *  contacts end up holding one that opens nothing. */
    const val PKEY = "pkey"

    private const val FORMAT_V1: Byte = 0x01
    private const val NONCE_LEN = 12
    private const val BLOCK = 512

    class BadSeal(msg: String) : Exception(msg)

    fun slotId(identityPriv: ByteArray, name: String): String =
        hkdf(identityPriv, "rcq.vault.slot.v1|$name", 16).joinToString("") { "%02x".format(it) }

    fun slotKey(identityPriv: ByteArray, slot: String): ByteArray =
        hkdf(identityPriv, "rcq.vault.key.v1|$slot", 32)

    private fun aad(slot: String, version: Long): ByteArray =
        "rcq.vault.v1|$slot|$version".toByteArray(Charsets.UTF_8)

    /** Seal [plaintext] for [slot] as [version]. Returns the raw blob bytes
     *  (the caller base64s them for the wire). */
    fun seal(identityPriv: ByteArray, slot: String, version: Long, plaintext: ByteArray): ByteArray =
        seal(identityPriv, slot, version, plaintext, ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) })

    internal fun seal(identityPriv: ByteArray, slot: String, version: Long, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        val padded = pad(plaintext)
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(slotKey(identityPriv, slot)), 128, nonce, aad(slot, version)))
        val out = ByteArray(cipher.getOutputSize(padded.size))
        var len = cipher.processBytes(padded, 0, padded.size, out, 0)
        len += cipher.doFinal(out, len)
        return byteArrayOf(FORMAT_V1) + nonce + out.copyOf(len)
    }

    /** Open a blob the island served as [version]. Throws [BadSeal] on any
     *  mismatch: wrong identity, wrong slot, wrong version, tampering. */
    fun open(identityPriv: ByteArray, slot: String, version: Long, blob: ByteArray): ByteArray {
        if (blob.size < 1 + NONCE_LEN + 16 || blob[0] != FORMAT_V1) throw BadSeal("format")
        val nonce = blob.copyOfRange(1, 1 + NONCE_LEN)
        val ct = blob.copyOfRange(1 + NONCE_LEN, blob.size)
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(slotKey(identityPriv, slot)), 128, nonce, aad(slot, version)))
        val out = ByteArray(cipher.getOutputSize(ct.size))
        val padded = try {
            var len = cipher.processBytes(ct, 0, ct.size, out, 0)
            len += cipher.doFinal(out, len)
            out.copyOf(len)
        } catch (e: Exception) {
            throw BadSeal("seal")
        }
        return unpad(padded)
    }

    private fun pad(p: ByteArray): ByteArray {
        val total = maxOf(BLOCK, ((4 + p.size + BLOCK - 1) / BLOCK) * BLOCK)
        val out = ByteArray(total)
        ByteBuffer.wrap(out).putInt(p.size)
        System.arraycopy(p, 0, out, 4, p.size)
        return out
    }

    private fun unpad(b: ByteArray): ByteArray {
        if (b.size < 4) throw BadSeal("format")
        val n = ByteBuffer.wrap(b).int
        if (n < 0 || 4 + n > b.size) throw BadSeal("format")
        return b.copyOfRange(4, 4 + n)
    }

    private fun hkdf(ikm: ByteArray, info: String, len: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, null, info.toByteArray(Charsets.UTF_8)))
        return ByteArray(len).also { gen.generateBytes(it, 0, len) }
    }
}
