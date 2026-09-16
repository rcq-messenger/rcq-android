package app.rcq.android.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64
import java.util.Locale

/**
 * The signed bytes of an `rcq-guest-v1` proof (spec 2026-09-15, section 4.3),
 * the same bytes the island builds in `app/services/guest_proof.py`.
 *
 * `POST /auth/guest` gives this identity a guest copy on a paid or invite
 * island, together with its first room, without a voucher. All the island can
 * check is that we hold the private half of the signing key, so the signature
 * pins everything a relay, a front or a replaying stranger could change: the
 * island (canonical host), the room (its id on THAT island, never our alias),
 * both public keys, and a single-use challenge.
 *
 * Pure on purpose (no android.util, no Context): `GuestProofTest` checks it
 * byte for byte against `fixtures/guest-proof-v1.json` from rcq-server-ref.
 *
 * ⚠ Keys go in re-encoded as standard padded base64 of their raw bytes and the
 * host canonicalised. Only the challenge goes in verbatim: it is the island's
 * own JWT, and the island signs exactly what it handed out.
 */
object GuestProof {
    const val PREFIX = "rcq-guest-v1"
    const val VERSION = 1

    /**
     * Lowercase host, `:port` kept only when it is not 443, trailing dots
     * dropped, brackets around an IPv6 literal kept. A line-for-line port of
     * `reissue_proof.canonical_host`: two spellings of "which island" are two
     * chances for a correct proof to be refused.
     */
    fun canonicalHost(value: String): String {
        var host = value.trim().lowercase(Locale.ROOT)
        var port = ""
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            if (end != -1 && host.getOrNull(end + 1) == ':') {
                port = host.substring(end + 2)
                host = host.substring(0, end + 1)
            }
        } else if (host.count { it == ':' } == 1) {
            val i = host.indexOf(':')
            port = host.substring(i + 1)
            host = host.substring(0, i)
        }
        host = host.trimEnd('.')
        return if (port.isNotEmpty() && port != "443") "$host:$port" else host
    }

    /** Standard padded base64 of raw key bytes: the spelling that is signed. */
    fun canonicalKey(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)

    /** A 32-byte key from any base64 spelling a card may carry (padded or not,
     *  standard or url-safe), or null. For comparing cards, never for signing. */
    fun decodeKey32(value: String?): ByteArray? {
        val s = value?.trim()?.trimEnd('=') ?: return null
        if (s.isEmpty()) return null
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        val raw = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
            ?: return null
        return raw.takeIf { it.size == 32 }
    }

    /**
     * The exact bytes the signing key signs. UTF-8, six fields joined by a
     * single `\n`, no trailing newline:
     *
     *     rcq-guest-v1
     *     <canonical host>
     *     <group id, decimal>
     *     <identity key, standard padded base64>
     *     <signing key, standard padded base64>
     *     <challenge, verbatim>
     *
     * Throws IllegalArgumentException for input that cannot be one line of
     * that layout, exactly where the island raises ValueError: a field with a
     * newline in it would shift every field after it.
     */
    fun proofBytes(
        host: String,
        groupId: Int,
        identityKey: ByteArray,
        signingKey: ByteArray,
        challenge: String,
    ): ByteArray {
        require(groupId > 0) { "group_id must be positive" }
        require(identityKey.size == 32 && signingKey.size == 32) { "keys must be 32 bytes" }
        val hostLine = canonicalHost(host)
        require(hostLine.isNotEmpty() && '\n' !in hostLine && '\r' !in hostLine) { "bad host" }
        require(challenge.isNotEmpty() && '\n' !in challenge && '\r' !in challenge) { "bad challenge" }
        return listOf(
            PREFIX,
            hostLine,
            groupId.toString(),
            canonicalKey(identityKey),
            canonicalKey(signingKey),
            challenge,
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /** Ed25519 over [bytes] under the 32-byte signing seed, standard base64. */
    fun sign(signingPrivate: ByteArray, bytes: ByteArray): String {
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(signingPrivate, 0))
            update(bytes, 0, bytes.size)
        }
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }
}
