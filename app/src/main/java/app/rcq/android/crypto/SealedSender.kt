package app.rcq.android.crypto

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * v=1 sealed-sender envelope, byte-compatible with the iOS client
 * (CryptoService.swift `encrypt` / `decryptV1`). This is the path used
 * whenever the recipient hasn't published a libsignal v=2 bundle, which
 * is every account today.
 *
 * Scheme:
 *   - ephemeral X25519 keypair per message
 *   - ECDH(ephemeral_priv, recipient_identity_pub) -> 32-byte secret
 *   - HKDF-SHA256(ikm=secret, salt=ephemeral_pub||recipient_pub,
 *                 info="RCQ-1to1-v1", L=32) -> AEAD key
 *   - inner plaintext JSON {from, spub, sig, env}, where
 *       env = base64(envelope JSON), sig = Ed25519(signing_priv) over
 *       (ephemeral_pub || envelope JSON bytes)
 *   - ChaCha20-Poly1305 seal, AAD = ephemeral_pub, combined = nonce(12)
 *       || ciphertext || tag(16)   (CryptoKit ChaChaPoly.combined layout)
 *   - wire JSON {v:1, ek:base64(ephemeral_pub), ct:base64(combined)},
 *       the whole thing base64'd as the `payload` sent to /messages/sealed
 */
object SealedSender {

    private val HKDF_INFO_V1 = "RCQ-1to1-v1".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO_V2 = "RCQ-1to1-v2".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO_WEBLINK = "RCQ-weblink-v1".toByteArray(Charsets.UTF_8)
    private const val WIRE_V1 = 1
    private const val WIRE_V2 = 2

    class DecryptException(message: String) : Exception(message)

    data class Decrypted(
        val senderUin: Int,
        val envelope: Envelope,
        val senderSigningPub: ByteArray,
        /** The sender's island host if they included it (v=1 `from_host`); null
         *  for pre-`from_host` senders and all v=2 (same-island). Drives Variant A. */
        val senderHost: String? = null,
    )

    /** A v=2 envelope after the outer ECIES is peeled but before the inner
     *  libsignal ciphertext is run through the Double Ratchet. [kind] is
     *  "prekey" (a PreKeySignalMessage that establishes the inbound session)
     *  or "signal" (a SignalMessage on an existing session). */
    data class UnwrappedV2(val senderUin: Int, val kind: String, val msgBytes: ByteArray)

    fun encryptV1(
        envelope: Envelope,
        recipientIdentityPub: ByteArray,
        ownUin: Int,
        signingPriv: ByteArray,
        signingPub: ByteArray,
        ownHost: String = "api.rcq.app",
    ): String {
        // Ephemeral X25519 keypair.
        val gen = X25519KeyPairGenerator().apply {
            init(X25519KeyGenerationParameters(SecureRandom()))
        }
        val kp = gen.generateKeyPair()
        val ephPriv = kp.private as X25519PrivateKeyParameters
        val ephPub = (kp.public as X25519PublicKeyParameters).encoded

        val aeadKey = deriveKey(ephPriv, X25519PublicKeyParameters(recipientIdentityPub, 0), ephPub, recipientIdentityPub, HKDF_INFO_V1)

        val envJson = envelope.toJsonBytes()

        // Ed25519 over ephemeral_pub || envelope JSON.
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(signingPriv, 0))
            update(ephPub, 0, ephPub.size)
            update(envJson, 0, envJson.size)
        }
        val sig = signer.generateSignature()

        // `from_host` (the sender's island) lets the recipient tell a cross-island
        // sender from a local one (Variant A consent + correct labeling). Additive:
        // old decoders ignore it; the authenticated identity stays `spub`.
        val inner = JsonObject().apply {
            addProperty("from", ownUin)
            addProperty("from_host", ownHost)
            addProperty("spub", b64(signingPub))
            addProperty("sig", b64(sig))
            addProperty("env", b64(envJson))
        }.toString().toByteArray(Charsets.UTF_8)

        val combined = aeadSeal(aeadKey, aad = ephPub, plaintext = inner)

        val wire = JsonObject().apply {
            addProperty("v", WIRE_V1)
            addProperty("ek", b64(ephPub))
            addProperty("ct", b64(combined))
        }.toString().toByteArray(Charsets.UTF_8)

        return b64(wire)
    }

    /** Seal [plaintext] to a web client's ephemeral X25519 pubkey for the
     *  connect-to-web QR login. Same ECIES as [encryptV1] (ephemeral X25519 →
     *  HKDF-SHA256(salt = ephPub || recipientPub, info = "RCQ-weblink-v1") →
     *  ChaCha20-Poly1305, AAD = ephPub) but WITHOUT the inner envelope +
     *  signature — it carries a raw blob (the account LinkBlob JSON), and
     *  confidentiality (only the web's ephemeral privkey opens it) is all that's
     *  needed. Wire = base64(JSON{ek, ct}) with ct = nonce(12) || ct || tag.
     *  The web's `openLinkSeal` mirrors this byte-for-byte. */
    fun sealForWebLink(plaintext: ByteArray, recipientWebPub: ByteArray): String {
        val gen = X25519KeyPairGenerator().apply {
            init(X25519KeyGenerationParameters(SecureRandom()))
        }
        val kp = gen.generateKeyPair()
        val ephPriv = kp.private as X25519PrivateKeyParameters
        val ephPub = (kp.public as X25519PublicKeyParameters).encoded

        val aeadKey = deriveKey(
            ephPriv, X25519PublicKeyParameters(recipientWebPub, 0),
            ephPub, recipientWebPub, HKDF_INFO_WEBLINK,
        )
        val combined = aeadSeal(aeadKey, aad = ephPub, plaintext = plaintext)

        val wire = JsonObject().apply {
            addProperty("ek", b64(ephPub))
            addProperty("ct", b64(combined))
        }.toString().toByteArray(Charsets.UTF_8)
        return b64(wire)
    }

    fun decryptV1(
        payloadB64: String,
        ownIdentityPriv: ByteArray,
        ownIdentityPub: ByteArray,
    ): Decrypted {
        val wire = JsonParser.parseString(String(unb64(payloadB64), Charsets.UTF_8)).asJsonObject
        val v = wire.get("v")?.asInt ?: 0
        if (v != WIRE_V1) throw DecryptException("unsupported wire version v=$v")
        val ek = unb64(wire.get("ek").asString)
        val ct = unb64(wire.get("ct").asString)

        val ownPriv = X25519PrivateKeyParameters(ownIdentityPriv, 0)
        val aeadKey = deriveKey(ownPriv, X25519PublicKeyParameters(ek, 0), ek, ownIdentityPub, HKDF_INFO_V1)

        val inner = try {
            aeadOpen(aeadKey, aad = ek, combined = ct)
        } catch (e: Exception) {
            throw DecryptException("AEAD open failed: ${e.message}")
        }

        val obj = JsonParser.parseString(String(inner, Charsets.UTF_8)).asJsonObject
        val from = obj.get("from")?.asInt ?: throw DecryptException("missing sender")
        val spub = unb64(obj.get("spub").asString)
        val sig = unb64(obj.get("sig").asString)
        val envBytes = unb64(obj.get("env").asString)

        // Verify Ed25519 over ephemeral_pub || envelope JSON.
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(spub, 0))
            update(ek, 0, ek.size)
            update(envBytes, 0, envBytes.size)
        }
        if (!verifier.verifySignature(sig)) throw DecryptException("signature verify failed")

        return Decrypted(
            senderUin = from,
            envelope = Envelope.fromJsonBytes(envBytes),
            senderSigningPub = spub,
            senderHost = obj.get("from_host")?.asString,
        )
    }

    // ── v=2 forward-secrecy ECIES wrapper ────────────────────────────
    // v=2 keeps the exact same outer ECIES as v=1 (ephemeral X25519 + HKDF
    // + ChaCha20-Poly1305) but swaps the HKDF info to "RCQ-1to1-v2" and
    // carries a libsignal Double-Ratchet ciphertext instead of a signed
    // plaintext envelope. The inner JSON is {from, kind, msg}: no spub/sig,
    // because the libsignal session itself authenticates the sender. The
    // ratchet machinery lives in [SignalSession]; this is just the wrap.

    /** Wrap an already-serialized libsignal ciphertext in the v=2 outer
     *  ECIES, addressed to [recipientIdentityPub] (the peer's X25519
     *  messaging identity key, same one v=1 uses). [kind] is "prekey" or
     *  "signal". Byte-compatible with iOS `encryptStage3`. */
    fun wrapV2(
        libsignalBytes: ByteArray,
        kind: String,
        recipientIdentityPub: ByteArray,
        ownUin: Int,
    ): String {
        val gen = X25519KeyPairGenerator().apply {
            init(X25519KeyGenerationParameters(SecureRandom()))
        }
        val kp = gen.generateKeyPair()
        val ephPriv = kp.private as X25519PrivateKeyParameters
        val ephPub = (kp.public as X25519PublicKeyParameters).encoded

        val aeadKey = deriveKey(ephPriv, X25519PublicKeyParameters(recipientIdentityPub, 0), ephPub, recipientIdentityPub, HKDF_INFO_V2)

        val inner = JsonObject().apply {
            addProperty("from", ownUin)
            addProperty("kind", kind)
            addProperty("msg", b64(libsignalBytes))
        }.toString().toByteArray(Charsets.UTF_8)

        val combined = aeadSeal(aeadKey, aad = ephPub, plaintext = inner)

        val wire = JsonObject().apply {
            addProperty("v", WIRE_V2)
            addProperty("ek", b64(ephPub))
            addProperty("ct", b64(combined))
        }.toString().toByteArray(Charsets.UTF_8)

        return b64(wire)
    }

    /** Peel the v=2 outer ECIES, returning the inner libsignal ciphertext
     *  bytes + kind for [SignalSession] to run through the ratchet. */
    fun unwrapV2(
        payloadB64: String,
        ownIdentityPriv: ByteArray,
        ownIdentityPub: ByteArray,
    ): UnwrappedV2 {
        val wire = JsonParser.parseString(String(unb64(payloadB64), Charsets.UTF_8)).asJsonObject
        val v = wire.get("v")?.asInt ?: 0
        if (v != WIRE_V2) throw DecryptException("expected wire version v=2, got v=$v")
        val ek = unb64(wire.get("ek").asString)
        val ct = unb64(wire.get("ct").asString)

        val ownPriv = X25519PrivateKeyParameters(ownIdentityPriv, 0)
        val aeadKey = deriveKey(ownPriv, X25519PublicKeyParameters(ek, 0), ek, ownIdentityPub, HKDF_INFO_V2)

        val inner = try {
            aeadOpen(aeadKey, aad = ek, combined = ct)
        } catch (e: Exception) {
            throw DecryptException("AEAD open failed: ${e.message}")
        }

        val obj = JsonParser.parseString(String(inner, Charsets.UTF_8)).asJsonObject
        val from = obj.get("from")?.asInt ?: throw DecryptException("missing sender")
        val kind = obj.get("kind")?.asString ?: throw DecryptException("missing kind")
        val msg = unb64(obj.get("msg").asString)
        return UnwrappedV2(senderUin = from, kind = kind, msgBytes = msg)
    }

    /** Peek the outer wire version (1 or 2) without decrypting, so the
     *  ingest path can dispatch. Returns 0 on a malformed payload. */
    fun wireVersion(payloadB64: String): Int = try {
        JsonParser.parseString(String(unb64(payloadB64), Charsets.UTF_8))
            .asJsonObject.get("v")?.asInt ?: 0
    } catch (e: Exception) {
        0
    }

    // ── primitives ───────────────────────────────────────────────────

    /** HKDF-SHA256 with the X25519 ECDH secret as IKM and
     *  salt = localEphemeralOrEk || peerIdentityPub, matching iOS. [info]
     *  is the HKDF info string, "RCQ-1to1-v1" for v=1 or "RCQ-1to1-v2" for
     *  the v=2 forward-secrecy wrapper — the rest of the outer ECIES is
     *  identical between versions. */
    private fun deriveKey(
        priv: X25519PrivateKeyParameters,
        pub: X25519PublicKeyParameters,
        ek: ByteArray,
        recipientPub: ByteArray,
        info: ByteArray,
    ): ByteArray {
        val secret = ByteArray(32)
        priv.generateSecret(pub, secret, 0)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(secret, ek + recipientPub, info))
        val out = ByteArray(32)
        hkdf.generateBytes(out, 0, 32)
        return out
    }

    private fun aeadSeal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return nonce + out.copyOf(len)
    }

    private fun aeadOpen(key: ByteArray, aad: ByteArray, combined: ByteArray): ByteArray {
        val nonce = combined.copyOfRange(0, 12)
        val ctTag = combined.copyOfRange(12, combined.size)
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ctTag.size))
        var len = cipher.processBytes(ctTag, 0, ctTag.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** Encrypt→decrypt round-trip self-check. Temporary, for bring-up. */
    fun selfTest(): String = try {
        val recipient = IdentityKeys.generate()
        val sender = IdentityKeys.generate()
        val payload = encryptV1(
            envelope = Envelope.text("hello-roundtrip-42"),
            recipientIdentityPub = recipient.identityPublic,
            ownUin = 555,
            signingPriv = sender.signingPrivate,
            signingPub = sender.signingPublic,
        )
        val dec = decryptV1(payload, recipient.identityPrivate, recipient.identityPublic)
        val text = (dec.envelope as? Envelope.Text)?.text
        if (text == "hello-roundtrip-42" && dec.senderUin == 555) {
            "PASS uin=${dec.senderUin} text=$text"
        } else {
            "FAIL got uin=${dec.senderUin} text=$text"
        }
    } catch (e: Exception) {
        "FAIL ${e.javaClass.simpleName}: ${e.message}"
    }
}
