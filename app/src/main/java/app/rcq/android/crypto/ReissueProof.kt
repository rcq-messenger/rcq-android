package app.rcq.android.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/**
 * The signed bytes of an `rcq-reissue-v1` proof (spec 2026-09-15, F3), the same
 * bytes the island builds in `app/services/reissue_proof.py`.
 *
 * `POST /auth/reissue` rewrites an account's keys. Until this existed the
 * bearer token alone authorised it, so anybody holding a stolen token could
 * rotate the row onto keys of their own and lock the owner out for good. The
 * proof is a signature by the OLD signing key over the exact change being made,
 * bound to the island and the number it is made on, with a timestamp and a
 * nonce against replay.
 *
 * ⚠ THE BINDING IS PER ISLAND AND PER NUMBER. A rotation that reaches five
 * islands signs five different proofs: the same bytes sent to a second island
 * name the first one, and that island refuses them. This is the whole reason a
 * copy on another island cannot be rotated with the home island's proof.
 *
 * Pure on purpose (no android.util, no Context): `ReissueProofTest` checks it
 * byte for byte against `fixtures/reissue-proof-v1.json` from rcq-server-ref.
 */
object ReissueProof {
    const val PREFIX = "rcq-reissue-v1"
    const val VERSION = 1

    /** Lowercase host, `:port` only when it is not 443. The same rule as
     *  [GuestProof.canonicalHost] and `reissue_proof.canonical_host`; shared
     *  rather than copied, because two spellings of "which island" are two
     *  chances for a correct proof to be refused. */
    fun canonicalHost(value: String): String = GuestProof.canonicalHost(value)

    /** Standard padded base64 of raw key bytes: the spelling that is signed. */
    fun canonicalKey(raw: ByteArray): String = GuestProof.canonicalKey(raw)

    /** base64url without padding: the spelling that is signed AND the replay
     *  key the island files the nonce under. */
    fun canonicalNonce(raw: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    /** A fresh 16-byte nonce, already spelled the way it is signed. */
    fun newNonce(): String = canonicalNonce(ByteArray(16).also { SecureRandom().nextBytes(it) })

    /**
     * The exact bytes the OLD signing key signs. UTF-8, eight fields joined by
     * a single `\n`, no trailing newline:
     *
     *     rcq-reissue-v1
     *     <canonical host>
     *     <uin on THIS island, decimal>
     *     <old signing key, standard padded base64>
     *     <new identity key, standard padded base64>
     *     <new signing key, standard padded base64>
     *     <ts, unix SECONDS, decimal>
     *     <nonce, 16 bytes base64url, no padding>
     *
     * Throws IllegalArgumentException for input that cannot be one line of
     * that layout, exactly where the island raises ValueError.
     */
    fun proofBytes(
        host: String,
        uin: Int,
        oldSigningKey: ByteArray,
        newIdentityKey: ByteArray,
        newSigningKey: ByteArray,
        ts: Long,
        nonce: String,
    ): ByteArray {
        require(uin > 0) { "uin must be positive" }
        require(oldSigningKey.size == 32 && newIdentityKey.size == 32 && newSigningKey.size == 32) {
            "keys must be 32 bytes"
        }
        val hostLine = canonicalHost(host)
        require(hostLine.isNotEmpty() && '\n' !in hostLine && '\r' !in hostLine) { "bad host" }
        // 22 base64url characters, the one spelling the island files under.
        require(nonce.length == 22 && nonce.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "nonce must be 22 base64url characters"
        }
        require(ts > 0) { "ts must be positive" }
        return listOf(
            PREFIX,
            hostLine,
            uin.toString(),
            canonicalKey(oldSigningKey),
            canonicalKey(newIdentityKey),
            canonicalKey(newSigningKey),
            ts.toString(),
            nonce,
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /** Ed25519 over [bytes] under the 32-byte OLD signing seed, standard
     *  base64 — what goes in `ReissueRequest.proof_sig`. */
    fun sign(oldSigningPrivate: ByteArray, bytes: ByteArray): String {
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(oldSigningPrivate, 0))
            update(bytes, 0, bytes.size)
        }
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }
}
