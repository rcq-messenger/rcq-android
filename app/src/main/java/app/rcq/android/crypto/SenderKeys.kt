package app.rcq.android.crypto

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * RCQ Sender Keys (custom v=1) — group encrypt-once. Byte-for-byte compatible
 * with the web (sender-keys.ts) + iOS implementations: same HMAC-SHA256 chain
 * ratchet, same ChaCha20-Poly1305 framing, same AAD string, same gmsg JSON.
 * Canonical spec: RCQ/docs/sender-keys-design.md.
 *
 * Built only on primitives already cross-platform in RCQ v=1 (HMAC-SHA256,
 * ChaCha20-Poly1305, Ed25519) so groups can't break on libsignal version skew.
 */
object SenderKeys {

    /** Out-of-order tolerance: derive at most this many keys forward of a
     *  chain's position before NACKing. Must match web/iOS. */
    const val MAX_SKIP = 512

    // ── ratchet ──────────────────────────────────────────────────────
    // mk_i = HMAC-SHA256(ck_i, 0x01); ck_{i+1} = HMAC-SHA256(ck_i, 0x02).

    fun deriveMessageKey(chainKey: ByteArray): ByteArray = hmac(chainKey, byteArrayOf(0x01))
    fun nextChainKey(chainKey: ByteArray): ByteArray = hmac(chainKey, byteArrayOf(0x02))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    // ── gmsg seal / open ─────────────────────────────────────────────

    private fun gmsgAad(gid: Int, kid: String, e: Int, i: Int): ByteArray =
        "rcq.gmsg.v1|$gid|$kid|$e|$i".toByteArray(Charsets.UTF_8)

    /** Encrypt one envelope under message key [mk]. Returns the gmsg wire JSON
     *  bytes (base64'd by the caller for the broadcast payload). The Ed25519
     *  signature lives INSIDE the AEAD so members (who all hold the chain key)
     *  can still verify who actually posted. */
    fun sealGmsg(
        envelope: Envelope,
        gid: Int,
        kid: String,
        epoch: Int,
        index: Int,
        mk: ByteArray,
        signingPriv: ByteArray,
    ): String {
        val envBytes = envelope.toJsonBytes()
        val aad = gmsgAad(gid, kid, epoch, index)
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(signingPriv, 0))
            update(aad, 0, aad.size)
            update(envBytes, 0, envBytes.size)
        }
        val sig = signer.generateSignature()
        val plaintext = JsonObject().apply {
            addProperty("env", b64(envBytes))
            addProperty("sig", b64(sig))
        }.toString().toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ct = aeadSeal(mk, nonce, aad, plaintext)
        val wire = JsonObject().apply {
            addProperty("v", 1)
            addProperty("kid", kid)
            addProperty("e", epoch)
            addProperty("i", index)
            addProperty("n", b64(nonce))
            addProperty("ct", b64(ct))
        }.toString().toByteArray(Charsets.UTF_8)
        return b64(wire)
    }

    data class OpenedGmsg(val envelope: Envelope, val verified: Boolean)

    data class GmsgHeader(val kid: String, val epoch: Int, val index: Int)

    /** Peek the routing header of a gmsg payload without the chain key. */
    fun parseGmsgHeader(payloadB64: String): GmsgHeader? = runCatching {
        val w = JsonParser.parseString(String(unb64(payloadB64), Charsets.UTF_8)).asJsonObject
        if (w.get("v")?.asInt != 1) return null
        GmsgHeader(w.get("kid").asString, w.get("e").asInt, w.get("i").asInt)
    }.getOrNull()

    /** Decrypt + verify a gmsg payload under [mk], checking the signature
     *  against [expectedSpub]. Throws on AEAD failure (wrong key / tamper). */
    fun openGmsg(payloadB64: String, gid: Int, mk: ByteArray, expectedSpub: ByteArray): OpenedGmsg {
        val w = JsonParser.parseString(String(unb64(payloadB64), Charsets.UTF_8)).asJsonObject
        val kid = w.get("kid").asString
        val e = w.get("e").asInt
        val i = w.get("i").asInt
        val nonce = unb64(w.get("n").asString)
        val ct = unb64(w.get("ct").asString)
        val aad = gmsgAad(gid, kid, e, i)
        val plaintext = aeadOpen(mk, nonce, aad, ct)
        val inner = JsonParser.parseString(String(plaintext, Charsets.UTF_8)).asJsonObject
        val envBytes = unb64(inner.get("env").asString)
        val sig = unb64(inner.get("sig").asString)
        val verified = runCatching {
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(expectedSpub, 0))
                update(aad, 0, aad.size)
                update(envBytes, 0, envBytes.size)
            }.verifySignature(sig)
        }.getOrDefault(false)
        return OpenedGmsg(Envelope.fromJsonBytes(envBytes), verified)
    }

    /** 16 random bytes, base64 — a fresh distribution id. */
    fun newKid(): String = b64(ByteArray(16).also { SecureRandom().nextBytes(it) })

    fun randomChainKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    // ── AEAD (ChaCha20-Poly1305, nonce(12)||ct||tag — matches SealedSender) ──

    private fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    private fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ctTag: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ctTag.size))
        var len = cipher.processBytes(ctTag, 0, ctTag.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
