package app.rcq.android.net

import app.rcq.android.crypto.Envelope
import app.rcq.android.model.GroupMember

/**
 * The rules of the cross-island consent gate, pure so they are checked on the
 * JVM (#985).
 *
 * ⚠⚠ In a v=1 sealed envelope `from` and `from_host` sit OUTSIDE the Ed25519
 * signature (it covers ek||env only), so a sender can write any address into
 * them. The verified sender key (`spub`) is the only authenticated fact about
 * who sent a row. Every decision below that says "this is the contact we pinned"
 * therefore asks whether `spub` equals that contact's pinned signing key, and
 * never trusts (uin, host) alone.
 */
object CrossIslandGate {

    /** What the gate does with one decrypted 1:1 envelope. */
    enum class Verdict {
        /** Not a cross-island stranger: the normal ingest runs. */
        PASS,
        /** Content from a stranger: quarantined as a request, no receipt. */
        HOLD,
        /** Control traffic from a stranger: not held, not applied, row done. */
        DROP,
    }

    /** The kinds a person would read as a message. Same set as the same-island
     *  stranger quarantine, and the ONLY kinds a request row may hold: control
     *  traffic that co-members of a room on another island send to our guest
     *  mailbox (visit pings, receipts, key asks) opened phantom requests with an
     *  empty preview when every kind was held (#985(1)). */
    fun isContentKind(env: Envelope): Boolean =
        env is Envelope.Text || env is Envelope.Photo || env is Envelope.Video ||
            env is Envelope.File || env is Envelope.Voice || env is Envelope.Location

    /**
     * [crossIsland]: the row names another island in `from_host`.
     * [verifiedSelf]: it names our own number AND is signed by our own key (a
     * carbon from a sibling device). A foreign row that merely claims our
     * number is a stranger, or anyone could file a "sent by me" message.
     * [verifiedContact]: an accepted cross-island contact whose pinned signing
     * key equals the envelope's verified key.
     *
     * ⚠ DROP is not "fall through". A control envelope from somebody we never
     * accepted must never reach delete, edit, secure-screen or any other
     * handler below the gate.
     */
    fun verdict(crossIsland: Boolean, verifiedSelf: Boolean, verifiedContact: Boolean, env: Envelope): Verdict = when {
        !crossIsland || verifiedSelf || verifiedContact -> Verdict.PASS
        isContentKind(env) -> Verdict.HOLD
        else -> Verdict.DROP
    }

    /** Does the envelope's verified Ed25519 key [spub] equal [pinnedB64]? A
     *  missing pin, an undecodable one or a key of the wrong length is NOT a
     *  match: an unknown key is never treated as the pinned person. Constant
     *  time, because it is cheap to be. */
    fun signingKeyMatches(pinnedB64: String?, spub: ByteArray): Boolean {
        if (pinnedB64.isNullOrBlank() || spub.size != ED25519_PUB_LEN) return false
        val pinned = runCatching { java.util.Base64.getDecoder().decode(pinnedB64.trim()) }.getOrNull()
            ?: return false
        return pinned.size == ED25519_PUB_LEN && java.security.MessageDigest.isEqual(pinned, spub)
    }

    /** How a cross-island sender relates to the contact pinned at its address. */
    enum class ContactMatch {
        /** No accepted contact at this (uin, host). */
        NONE,
        /** The accepted contact, proven by its signing key. */
        VERIFIED,
        /** A row claims the address of an accepted contact but is signed by a
         *  different key: a stranger, and shown as one, never merged. */
        KEY_MISMATCH,
    }

    fun contactMatch(pinnedSigningKeyB64: String?, contactExists: Boolean, spub: ByteArray): ContactMatch = when {
        !contactExists -> ContactMatch.NONE
        signingKeyMatches(pinnedSigningKeyB64, spub) -> ContactMatch.VERIFIED
        else -> ContactMatch.KEY_MISMATCH
    }

    /** The roster member of a room on ANOTHER island that sent this row: the
     *  number must be in that room's roster and the envelope must be signed by
     *  the key that roster lists for it. Null otherwise, including a member the
     *  island served without a signing key: for a cross-island sender the
     *  number alone proves nothing. */
    fun verifiedMember(members: List<GroupMember>, senderUin: Int, spub: ByteArray): GroupMember? =
        members.firstOrNull { it.uin == senderUin }?.takeIf { signingKeyMatches(it.signingKey, spub) }

    /** The roster member of one of OUR OWN rooms that sent a room-key row. A
     *  v=2 row (empty [spub]) is authenticated by the ratchet, so the number is
     *  enough. A v=1 row must be signed by the key the roster lists for that
     *  number: `from` is outside the signature, and a bare number check let
     *  anyone who can deposit a row replace a room's state key. Only a roster
     *  that carries no signing key for the member falls back to the number,
     *  because there is nothing to compare against. */
    fun ownRoomMember(members: List<GroupMember>, senderUin: Int, spub: ByteArray): GroupMember? {
        val m = members.firstOrNull { it.uin == senderUin } ?: return null
        if (spub.isEmpty() || m.signingKey.isNullOrBlank()) return m
        return m.takeIf { signingKeyMatches(it.signingKey, spub) }
    }

    /** How a 1:1 row relates to OUR OWN number. */
    enum class OwnNumber {
        /** Names somebody else. */
        NOT_OURS,
        /** Names our number and is signed by our own key (a sibling device). */
        OURS_SIGNED,
        /** Names our number in a v=2 row, which the ratchet authenticates. */
        OURS_V2,
        /** Names our number under a key that is not ours: forged, dropped. */
        FORGED,
    }

    /**
     * ⚠⚠ Decided WITHOUT the host, on purpose. `from_host` is as unsigned as
     * `from`, so a check that only ran when the host named another island let
     * a v=1 row that uses our number and stamps our own host (or none) reach
     * the carbon branch, which files "sent by me" rows and applies request
     * answers (a forged accept pinned an attacker's key to any address).
     *
     * [spub] empty = v=2 (no signing key on the wire, the ratchet vouches). Any
     * other length is v=1 and must equal [ownSpub] byte for byte; a missing own
     * key cannot vouch for anything.
     */
    fun ownNumberRow(senderUin: Int, meUin: Int, spub: ByteArray, ownSpub: ByteArray?): OwnNumber = when {
        meUin == 0 || senderUin != meUin -> OwnNumber.NOT_OURS
        spub.isEmpty() -> OwnNumber.OURS_V2
        ownSpub != null && ownSpub.size == ED25519_PUB_LEN &&
            java.security.MessageDigest.isEqual(ownSpub, spub) -> OwnNumber.OURS_SIGNED
        else -> OwnNumber.FORGED
    }

    /**
     * May a carbon (an envelope another of OUR devices sealed to our own
     * number) be applied? P0.1 of the 15.09 cross-island spec, the same
     * transitional rule iOS MessageService applies in its carbon branch
     * (isSignedByMe).
     *
     * ⚠⚠ A carbon files "sent by me" rows, applies edits and deletes to our own
     * messages, and a ciack `accept` PINS a peer's keys for an address this
     * device does not hold yet. `from` is outside the v=1 signature and
     * deposits are open, so a carbon that merely names our number let anybody
     * who reads our key card do all of that. Only these pass:
     *  - [OwnNumber.OURS_SIGNED]: the row carries a `spub` and it is our own
     *    account key, which every install of ours holds. Any inner kind. A row
     *    that carries any other key is [OwnNumber.FORGED] and nothing passes.
     *  - [OwnNumber.OURS_V2]: a keyless v=2 row, for non-ciack kinds only. The
     *    device-id condition below never refuses in practice (a missing `dev`
     *    becomes the primary), so this is an allowance, not a check. NEVER a ciack: it pins keys, so it is
     *    taken only under our own signature.
     *
     * ⚠⚠ The v=2 allowance is transitional and weaker than it looks. The spec's
     * premise, "the session vouches for the sender", does NOT hold on Android:
     * `from` and `dev` sit inside the outer ECIES with no signature, the Signal
     * identity store trusts every identity on first use
     * (SignalStores.isTrustedIdentity is always true), and unwrapV2 defaults a
     * missing `dev` to the primary, so the device check never fires. Anyone
     * holding our prekey bundle can open a fresh session that claims our number
     * and file or rewrite our own rows through it. The allowance stays because
     * Android builds before this one sealed EVERY carbon over v=2 without a
     * key, and a carbon refused here is acked and gone: without it, whatever is
     * sent, edited, deleted or read on such a phone would never reach this
     * install again. This client now seals every carbon v=1 (`sendOwnCarbon` in
     * Session), as iOS and web do, so the allowance can be retired once those
     * builds have aged out. TODO(strict carbons): switch to OURS_SIGNED only,
     * together with web (crossisland-gate.ts) and iOS (MessageService carbon
     * branch); tracked in the 2026-09-15 cross-island spec addendum.
     *
     * ⚠ Those older builds sealed the ciack keyless as well, and it gets no
     * allowance, here or on web and iOS: a cross-island request answered on
     * such a phone stays pending on this install and has to be answered again.
     * That is the price of P0.1, not a bug to relax: a keyless ciack cannot be
     * told apart from a forged one. The release notes must say so.
     *
     * A cached-registry check was tried here and taken out: refuse a v=2 row
     * whose session identity differs from the one our cached device list
     * publishes for that id. The island overwrites the primary slot's identity
     * in place on a reinstall or a phrase restore, so a list cached before that
     * refused the re-keyed install's real carbons, while a forger could pick a
     * device id the list does not name and pass anyway.
     *
     * A refused carbon applies nothing; the caller still treats the row as done
     * so the island stops serving it.
     */
    fun carbonAccepted(own: OwnNumber, senderDeviceId: Int?, inner: Envelope): Boolean = when (own) {
        OwnNumber.OURS_SIGNED -> true
        OwnNumber.OURS_V2 -> senderDeviceId != null && inner !is Envelope.CiAck
        OwnNumber.NOT_OURS, OwnNumber.FORGED -> false
    }

    /**
     * The island a 1:1 row is attributed to. [mailboxHost] is set only for a
     * row drained from our GUEST mailbox on another island (or re-ingested
     * after being held under that island): everyone who can write there is on
     * that island or deposits to it, and a row there whose `from_host` is
     * missing or names our own island would otherwise get same-island trust
     * (own-room keys, control kinds applied) on the strength of an unsigned
     * field. A row naming a third island keeps its own host, which is
     * cross-island either way.
     */
    fun attributedHost(senderHost: String?, mailboxHost: String?, ownHosts: Collection<String>): String? = when {
        mailboxHost.isNullOrBlank() -> senderHost
        senderHost.isNullOrBlank() || ownHosts.any { it.equals(senderHost, ignoreCase = true) } -> mailboxHost
        else -> senderHost
    }

    private const val ED25519_PUB_LEN = 32
}
