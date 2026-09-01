package app.rcq.android.net

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * The Ed25519 keys this build will accept a signature from, by what the
 * signature authorises.
 *
 * ## Why a SET, and why it is compiled in
 *
 * Until now every client pinned exactly one key, in six separate places across
 * three codebases. That makes rotation impossible rather than merely awkward:
 * a client that knows one key cannot be handed a payload signed by any other,
 * so the day that key has to change is the day every installed client stops
 * receiving relay updates. Which is the same as saying we could never respond
 * to the key leaking — and the private half lives on one laptop and, for the
 * relay role, on the production droplet as well.
 *
 * Accepting a set fixes the part that matters. Ship the successor now, keep
 * signing with the incumbent, and the switch becomes a signing-side decision
 * with no release and no flag day: clients already accept both. Retiring the
 * old key then needs a release, but retiring is never the urgent direction.
 *
 * ## Why the set does NOT come from the signed payload
 *
 * It is tempting to let the config carry its own future keys, since that would
 * make even introducing a key releaseless. It is the wrong trade. An attacker
 * holding the current key can already sign anything, so nothing stops them
 * signing a payload that adds a key of their own — and from then on rotating
 * away from the stolen key would no longer evict them, because their key is
 * pinned in every client's cache and every client honours it. Rotation would
 * become theatre.
 *
 * Keeping the set compiled in bounds a key compromise in time: it lasts until
 * we sign with the successor, and not one payload longer.
 *
 * ## Roles
 *
 * [Role.RELAY_CONFIG] and [Role.ISLAND_LIST] are listed apart because they
 * authorise different things — where traffic is tunnelled versus which island
 * an account is silently given a backup mailbox on — and one key currently
 * covers both, so a leak of it costs both at once. Both roles list the same
 * incumbent today plus a role-specific successor, which is what lets them be
 * pulled apart later without a release.
 */
object SigningKeys {

    enum class Role { RELAY_CONFIG, ISLAND_LIST }

    /** In use since 2026-05. Signs relay-config AND auto-islands, which is the
     *  overlap the role split exists to end. */
    private const val INCUMBENT = "TY834OFcBvtUqHcnVw/QrPBOaEAZo7a1GAmABMhjkT8="

    /** Generated 2026-08-05, held offline, never yet used to sign anything.
     *  Present so switching to it costs a signing decision, not a release. */
    private const val RELAY_SUCCESSOR = "sr0g2D8rXZiEdU8cA6gaIWKxA34QIsysUJQsEeloL1o="

    /** Generated 2026-08-05 for the island role alone, so the day the relay key
     *  is rotated (or leaks) the island list does not have to move with it. */
    private const val ISLAND_SUCCESSOR = "YsA429yi8BeQKQVvi0HSykrK0SVsJlhNKhFwC+g7VWo="

    private val accepted: Map<Role, List<ByteArray>> = mapOf(
        Role.RELAY_CONFIG to listOf(INCUMBENT, RELAY_SUCCESSOR),
        Role.ISLAND_LIST to listOf(INCUMBENT, ISLAND_SUCCESSOR),
    ).mapValues { (_, keys) -> keys.map { Base64.decode(it, Base64.DEFAULT) } }

    /**
     * True when [sigB64] is a valid signature over [message] by ANY key this
     * build accepts for [role].
     *
     * Every candidate is tried even after one fails, so which key signed a
     * payload is not observable from how long verification took. Any malformed
     * input is a failed verification, never an exception: a corrupt payload
     * must read as unsigned, not crash the caller that was about to fall back
     * to its bundled list.
     */
    /**
     * Verify [message] against [sigB64] under a key that came WITH the payload.
     *
     * ⚠ This is deliberately not a [Role], and adding one for it would be the
     * mistake the doc above argues against. It exists for `.rcq` site
     * manifests, where the signing key is the site owner's and this build has
     * never heard of it: what the signature proves there is not "we authorised
     * this" but "one key signed every byte of this bundle", and the key itself
     * is anchored by [app.rcq.android.sites.SitePins] on first use, the way a
     * safety number is. Never call this with a key from a payload that is
     * supposed to carry OUR authority — for those the answer is a Role, and
     * the set is compiled in for the reasons above.
     */
    fun verifyWith(pubKey: ByteArray, message: ByteArray, sigB64: String): Boolean = runCatching {
        val pub = Ed25519PublicKeyParameters(pubKey, 0)
        Ed25519Signer().apply { init(false, pub); update(message, 0, message.size) }
            .verifySignature(Base64.decode(sigB64, Base64.DEFAULT))
    }.getOrDefault(false)

    fun verify(role: Role, message: ByteArray, sigB64: String): Boolean = runCatching {
        val sig = Base64.decode(sigB64, Base64.DEFAULT)
        var ok = false
        for (key in accepted[role].orEmpty()) {
            val verified = runCatching {
                val pub = Ed25519PublicKeyParameters(key, 0)
                Ed25519Signer().apply { init(false, pub); update(message, 0, message.size) }
                    .verifySignature(sig)
            }.getOrDefault(false)
            ok = ok || verified
        }
        ok
    }.getOrDefault(false)
}
