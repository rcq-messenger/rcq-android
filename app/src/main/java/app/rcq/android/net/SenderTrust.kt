package app.rcq.android.net

import app.rcq.android.model.GroupMember

/**
 * Who sent a decrypted row, decided ONCE, right after decrypt and before any
 * branch or interceptor looks at it. Pure, so the rules are checked on the JVM.
 *
 * ⚠⚠ Identity is a number plus the Ed25519 signing key pinned for it; the island
 * is only where the row came from. In a v=1 envelope `from` and `from_host` sit
 * OUTSIDE the signature (it covers ek||env), and a deposit into `/messages/sealed`
 * or `/messages/group-sealed` is anonymous, so the only authenticated fact about
 * a v=1 row is its key. Every row used to be believed by number on our own island:
 * anybody holding a recipient's public card could write as any of their contacts,
 * or as any member of a room, by leaving `from_host` out. Nothing below trusts the
 * number or the host on their own.
 *
 * A v=2 row carries no Ed25519 key. Its sender is the ratchet identity of one
 * device, which [app.rcq.android.Session] admits only when the island publishes
 * it for that number and device (a new contact, or a changed identity), and
 * which is pinned per device from then on. So a v=2 row speaks for the person
 * our OWN island holds at that number, and for nobody on another island.
 */
object SenderTrust {

    enum class Level {
        /** Our own account: a v=1 row under our own signing key, or a v=2 row
         *  from a device of ours the island vouches for. */
        SELF,
        /** The person we hold a key for at this number, proven by that key. */
        PINNED,
        /** Nobody we hold a key for: stranger rules. */
        UNPINNED,
        /** Names a number we hold a key for, under a different key. Content
         *  is held as a request marked "key changed"; nothing else is applied. */
        FORGED,
    }

    data class Decision(
        val level: Level,
        /** The island the sender is attributed to, null for our own. For
         *  PINNED it is the pinned row's island, never the unsigned
         *  `from_host`; for SELF always ours. */
        val host: String?,
        /** A v=2 row: proven by an island-vouched ratchet identity rather than
         *  by an Ed25519 key. Nothing that pins or hands out keys takes one. */
        val v2: Boolean = false,
        /** The island's key card for this number may still change the answer:
         *  FORGED against a same-island pin (a phrase change re-keys an
         *  account), somebody we talk to whose key we never had, or a number
         *  on our island we hold nothing for yet. */
        val askCard: Boolean = false,
    )

    /** A key we hold for a number on another island. */
    data class Pin(val host: String, val signingKey: String?)

    /**
     * One 1:1 row.
     *
     * [claimedHost]: the row's `from_host` as attributed (a guest mailbox row
     * is attributed to that island, see [CrossIslandGate.attributedHost]).
     * [sameIsland]: the key pinned for this number on our own island, or the
     * roster's when nothing is pinned yet. [sameIslandKeyless]: somebody on
     * our island we already talk to (a roster contact, an existing chat) whose
     * key we do not hold. [crossIsland]: every cross-island
     * contact we hold under this number. [v2Vouched]: the ratchet identity of a
     * v=2 row was admitted against our island's published bundle.
     */
    fun decide(
        senderUin: Int,
        spub: ByteArray,
        meUin: Int,
        ownSpub: ByteArray?,
        claimedHost: String?,
        ownHosts: Collection<String>,
        sameIsland: String?,
        sameIslandKeyless: Boolean,
        crossIsland: List<Pin>,
        v2Vouched: Boolean,
    ): Decision {
        val claimedOwn = isOwn(claimedHost, ownHosts)
        val attributed = if (claimedOwn) null else claimedHost
        val v2 = spub.isEmpty()
        if (meUin != 0 && senderUin == meUin) {
            val ours = if (v2) v2Vouched && claimedOwn else CrossIslandGate.signingKeyMatches(b64(ownSpub), spub)
            return if (ours) Decision(Level.SELF, null, v2 = v2) else Decision(Level.FORGED, attributed, v2 = v2)
        }
        if (v2) {
            // Only our own island publishes the bundle a v=2 identity was
            // checked against, so a v=2 row is never anybody on another one.
            if (!v2Vouched || !claimedOwn) return Decision(Level.UNPINNED, attributed, v2 = true)
            if (sameIsland != null || sameIslandKeyless) return Decision(Level.PINNED, null, v2 = true)
            // The thread is keyed by the bare number: a local person writing
            // under a number we hold on another island must not land in it.
            if (crossIsland.isNotEmpty()) return Decision(Level.FORGED, null, v2 = true)
            return Decision(Level.UNPINNED, null, v2 = true)
        }
        // v=1: a key we hold for this number is who they are, wherever the
        // row says it came from. The row at the claimed address wins a tie
        // (the same key filed under two of their islands).
        val local = sameIsland?.takeIf { CrossIslandGate.signingKeyMatches(it, spub) }
        val remote = crossIsland.filter { CrossIslandGate.signingKeyMatches(it.signingKey, spub) }
        if (local != null || remote.isNotEmpty()) {
            val host = when {
                claimedOwn && local != null -> null
                remote.any { it.host.equals(claimedHost, ignoreCase = true) } -> remote.first { it.host.equals(claimedHost, ignoreCase = true) }.host
                local != null -> null
                else -> remote.first().host
            }
            return Decision(Level.PINNED, host)
        }
        if (!claimedOwn) {
            // An address on another island is only impersonated where we hold
            // a contact at THAT address. Anyone else there is a stranger, held
            // as a request under their own address (and refused on accept if
            // the number is taken here, #1061).
            val atAddress = crossIsland.any { it.host.equals(claimedHost, ignoreCase = true) }
            return Decision(if (atAddress) Level.FORGED else Level.UNPINNED, attributed)
        }
        return when {
            sameIsland != null -> Decision(Level.FORGED, null, askCard = true)
            sameIslandKeyless -> Decision(Level.UNPINNED, null, askCard = true)
            // No island named, and the number is a contact on another one: the
            // thread `peer:<uin>` is theirs, so this is somebody wearing it.
            crossIsland.isNotEmpty() -> Decision(Level.FORGED, null)
            // Nobody we hold a key for at this number: one read of the
            // island's key card before believing the unsigned `from`, so a
            // first message cannot wear a number it does not own.
            else -> Decision(Level.UNPINNED, null, askCard = true)
        }
    }

    /** What a row may do in a room. */
    enum class Room {
        /** Our own account. */
        SELF,
        /** A member of the room, proven by the key the roster (or our pin)
         *  holds for them, or by a vouched v=2 identity in a room of our own
         *  island. */
        PINNED,
        /** Not in the roster we hold. Final only once the island answered for
         *  the roster after the row arrived. */
        NOT_MEMBER,
        /** No roster for the room at all: ask the island before deciding. */
        ASK_ROSTER,
        /** A member of a room on our island whose key we never had: the key
         *  card decides. */
        ASK_CARD,
        /** A member's number under another key, or a row that cannot be proven
         *  in this room at all (a v=2 row in a room on another island). */
        FORGED,
    }

    /**
     * One row in a room. [member]: the roster row for [senderUin], null when
     * absent; [rosterKnown]: a roster is held for the room at all. [pinned]:
     * the same-island pin for the number, used for a room on our own island
     * ([foreignRoom] false) ahead of the roster's key, since both come from the
     * island and the pin is what we saw first. [meUin] is OUR number in that
     * room's island.
     */
    fun decideRoom(
        senderUin: Int,
        spub: ByteArray,
        meUin: Int,
        ownSpub: ByteArray?,
        foreignRoom: Boolean,
        member: GroupMember?,
        rosterKnown: Boolean,
        pinned: String?,
        v2Vouched: Boolean,
    ): Room {
        val v2 = spub.isEmpty()
        if (meUin != 0 && senderUin == meUin) {
            val ours = if (v2) v2Vouched && !foreignRoom else CrossIslandGate.signingKeyMatches(b64(ownSpub), spub)
            return if (ours) Room.SELF else Room.FORGED
        }
        if (member == null) return if (rosterKnown) Room.NOT_MEMBER else Room.ASK_ROSTER
        if (v2) return if (v2Vouched && !foreignRoom) Room.PINNED else Room.FORGED
        val key = (if (foreignRoom) null else pinned?.takeIf { it.isNotBlank() })
            ?: member.signingKey?.takeIf { it.isNotBlank() }
        if (key == null) return if (foreignRoom) Room.FORGED else Room.ASK_CARD
        return if (CrossIslandGate.signingKeyMatches(key, spub)) Room.PINNED else Room.FORGED
    }

    /** How a key the island just served for a number relates to our pin. */
    enum class PinChange {
        /** Nothing pinned yet: this becomes the pin. */
        FIRST,
        /** The pin, again. */
        SAME,
        /** A different key than the pin: re-pinned, and it has to be SAID. A
         *  phrase change re-keys an account and the island serves the new key;
         *  so would an island swapping keys. The person sees the difference
         *  either way, never a silent swap. */
        CHANGED,
        /** Nothing usable was offered. */
        NONE,
    }

    fun pinChange(current: String?, offered: String?): PinChange {
        val o = canonical(offered) ?: return PinChange.NONE
        val c = canonical(current) ?: return PinChange.FIRST
        return if (c == o) PinChange.SAME else PinChange.CHANGED
    }

    /** A 32-byte Ed25519 key as standard base64, or null. */
    fun canonical(key: String?): String? {
        if (key.isNullOrBlank()) return null
        val raw = runCatching { java.util.Base64.getDecoder().decode(key.trim()) }.getOrNull() ?: return null
        return if (raw.size == 32) java.util.Base64.getEncoder().encodeToString(raw) else null
    }

    private fun b64(key: ByteArray?): String? =
        key?.takeIf { it.size == 32 }?.let { java.util.Base64.getEncoder().encodeToString(it) }

    private fun isOwn(host: String?, ownHosts: Collection<String>): Boolean =
        host.isNullOrBlank() || ownHosts.any { it.equals(host, ignoreCase = true) }
}

/**
 * Which stored row an edit, a delete or a reaction may touch. The message id
 * on the wire is chosen by the sender, so it never decides on its own: the row
 * it names has to sit in the thread of whoever is allowed to act on it. An id
 * used to be looked up across the whole database, so any 1:1 sender could
 * rewrite or erase a message of any thread, a room's included, by naming it.
 */
object MessageAuthority {

    /** Where a stored row lives. [senderUin] is the author of a room row;
     *  null on a 1:1 row, whose author is [peerUin] or us. */
    data class Row(val peerUin: Int, val fromMe: Boolean, val groupId: Int?, val senderUin: Int?)

    /** A 1:1 edit or delete for everyone from [sender]: their own message in
     *  their own thread, nothing else. */
    fun peerMayRetract(row: Row?, sender: Int): Boolean =
        row != null && row.groupId == null && row.peerUin == sender && !row.fromMe

    /** The same sent from another device of ours outside a carbon: only our
     *  own notes thread (Saved Messages), where our rows are filed as ours. */
    fun selfMayRetract(row: Row?, me: Int): Boolean =
        row != null && me != 0 && row.groupId == null && row.peerUin == me

    /** A carbon names the thread it belongs to and acts in that thread only.
     *  A row not on this device yet is fair game: our own delete then leaves
     *  the tombstone that keeps a late copy of it out. */
    fun carbonMayTouch(row: Row?, to: Int?, gid: Int?): Boolean = when {
        row == null -> true
        gid != null -> row.groupId == gid
        to != null -> row.groupId == null && row.peerUin == to
        else -> false
    }

    /** A 1:1 reaction from [sender] attaches to a message of their thread only. */
    fun peerMayReact(row: Row?, sender: Int): Boolean =
        row != null && row.groupId == null && row.peerUin == sender

    /** A delete in room [groupId]: a row of THAT room, by its author or by a
     *  moderator of that room. */
    fun groupMayDelete(row: Row?, groupId: Int, sender: Int, me: Int, moderator: Boolean): Boolean {
        if (row == null || row.groupId != groupId) return false
        return authorOf(row, me) == sender || moderator
    }

    /** An edit in room [groupId]: the author's own row of that room. */
    fun groupMayEdit(row: Row?, groupId: Int, sender: Int, me: Int): Boolean =
        row != null && row.groupId == groupId && authorOf(row, me) == sender

    private fun authorOf(row: Row, me: Int): Int? = if (row.fromMe) me else row.senderUin
}

/**
 * Whether a room key may replace the one held. The sender is already proven a
 * member; this bounds what one envelope can do to the version, which only ever
 * moves up: a key with a huge version used to replace the right one and refuse
 * every legitimate key after it, for good.
 *
 * [opensNew]/[opensHeld]: whether the offered and the held key open the room's
 * sealed state as the island serves it (null when there is no sealed state).
 * A key that opens it is taken whatever its version ([force]), which is also
 * how a room wedged by such a key before this rule repairs itself. [blobVer]:
 * the key version the blob names in its open header (null for the
 * pre-versioned format).
 */
object RoomKeyRule {
    enum class Verdict { REFUSE, ACCEPT, FORCE }

    fun accept(heldVer: Long?, offeredVer: Long, opensNew: Boolean?, opensHeld: Boolean?, blobVer: Long? = null): Verdict = when {
        opensNew == true -> Verdict.FORCE
        // Never trade a key that opens the room for one that does not, unless
        // the blob predates the offered key: a rotation lands before its
        // re-seal (web and desktop fan the new key out first and only then
        // write the sealed state), and a blob sealed under an older version
        // says nothing about a newer key. The step rule below still bounds it.
        opensHeld == true && (blobVer == null || blobVer >= offeredVer) -> Verdict.REFUSE
        heldVer == null -> Verdict.ACCEPT
        // One step at a time; an equal version is the wedge-repair rule.
        offeredVer < heldVer || offeredVer > heldVer + 1 -> Verdict.REFUSE
        else -> Verdict.ACCEPT
    }
}
