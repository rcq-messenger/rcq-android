package app.rcq.android.net

import app.rcq.android.crypto.GuestProof
import app.rcq.android.model.GroupMember

/**
 * The decisions around guest copies on paid and invite islands (spec
 * 2026-09-15, sections 4, 5, 9, 11, 12.1 and 12.5), kept pure so the same table
 * every client tests is tested here on the JVM. Session does the requests; this
 * decides which request, and what a refusal means.
 *
 * ⚠ Codes, never substrings, for every new refusal: `detail.code` is read out
 * of the JSON by [RcqApi.refusalOf]. The only substrings left are the three
 * prose details a native `POST /members` has always answered with.
 */
object GuestPath {

    enum class Path {
        /** Today's recover-first then `/auth/register` (join), or `uin-for-key`
         *  then `/auth/register` then `/members` (owner-add). */
        LEGACY,

        /** `POST /auth/guest` (join) or `POST /groups/{id}/guests` (owner-add). */
        GUEST,
    }

    /** Guest only when the island says `guest_accounts_v1: true`. An island
     *  that did not answer, an island too old for the field, and an island
     *  that admits no guests right now all get the legacy path, which is
     *  exactly what they got before this existed. */
    fun decide(capability: Boolean?): Path = if (capability == true) Path.GUEST else Path.LEGACY

    fun decide(info: RcqApi.ServerInfoResponse?): Path {
        // ⚠ Read through a nullable local: Gson leaves a JSON `null` in a field
        // Kotlin believes can never be null.
        val caps: RcqApi.ServerCapabilities? = info?.capabilities
        return decide(caps?.guest_accounts_v1)
    }

    /** The neutral name a copy or a seat gets when the person has no name the
     *  island may see. No digits: the server requires a nickname
     *  (`min_length=1` on both `GuestIn` and `GuestAddIn`), so it cannot simply
     *  be left out. */
    const val NEUTRAL_NICKNAME = "Guest"

    /** The name a guest copy or a seat on ANOTHER island is minted or renamed
     *  under ([own] is the person's own nickname, [homeUin] their number on
     *  their HOME island).
     *
     *  ⚠⚠ Never the home number (decision D1). "user-<uin>" was the legacy
     *  fallback, and a phrase sign-in whose profile read failed stores exactly
     *  that as the nickname, so a name whose digits spell the home number is
     *  treated as no name at all. Compared as whole digit runs: "Anna1985" is
     *  not number 198. */
    fun nicknameFor(own: String?, homeUin: Int? = null): String {
        val name = own?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: return NEUTRAL_NICKNAME
        return if (homeUin != null && carriesNumber(name, homeUin)) NEUTRAL_NICKNAME else name
    }

    /** True when [name] spells [uin] as one of its digit runs. */
    fun carriesNumber(name: String, uin: Int): Boolean {
        val digits = uin.toString()
        return Regex("\\d+").findAll(name).any { it.value == digits }
    }

    /** What to do after `/auth/guest/challenge` or `/auth/guest` failed. */
    enum class JoinStep {
        /** `invalid_challenge`, `guest_replayed`, `guest_busy`: once more with a
         *  fresh challenge. */
        RETRY_FRESH_CHALLENGE,

        /** `identity_rotated`: the key we hold was retired on that island. The
         *  rotated-elsewhere notice (P0.2), never a wipe. */
        ROTATED,

        /** 404 or 405 without a code: the route is not there after all. */
        LEGACY,

        /** 5xx, or no answer at all: one recover-first attempt. */
        TRANSIENT,

        /** Anything else: its sentence, and no legacy fallback. */
        REFUSED,
    }

    fun joinStep(message: String?): JoinStep {
        val r = RcqApi.refusalOf(message)
        val status = r.status ?: return JoinStep.TRANSIENT
        return when {
            r.code == "invalid_challenge" || r.code == "guest_replayed" || r.code == "guest_busy" ->
                JoinStep.RETRY_FRESH_CHALLENGE
            r.code == AuthRefusal.IDENTITY_ROTATED -> JoinStep.ROTATED
            (status == 404 || status == 405) && r.code == null -> JoinStep.LEGACY
            status >= 500 -> JoinStep.TRANSIENT
            else -> JoinStep.REFUSED
        }
    }

    /** True when a failed `/auth/recover` (or its challenge) means "this key has
     *  no account there": a 404. With [rotatedIsError], a 404 that says
     *  `identity_rotated` is NOT that: the key was retired there by a key change
     *  made elsewhere, and the guest flow must hear it as the rotated-elsewhere
     *  flow (decision D2) instead of registering a fresh row beside it. */
    fun recoverAbsent(message: String?, rotatedIsError: Boolean): Boolean {
        if (message?.startsWith("HTTP 404") != true) return false
        return !(rotatedIsError && RcqApi.refusalOf(message).code == AuthRefusal.IDENTITY_ROTATED)
    }

    /** Which 12.5 sentence a refusal gets. Session turns it into a string. */
    enum class Sentence {
        CLOSED,
        ROOM_CLOSED,
        ROOM_FULL,
        ROOM_LIMIT,
        RATE,
        GROUP_LIMIT,
        OLD_PAID,
        OLD_INVITE,
        UNAVAILABLE,
        RESTRICTED,
        /** `guest_restricted` on `POST /contacts/respond`: answer from home. */
        RESTRICTED_CONTACTS,
        ADD_LIMIT,
        ADD_SEAT_LIMIT,
        STALE_KEY,
        GUEST_ADDER,
        BLOCKED,
        CONTACTS_ONLY,
        NOBODY,
        /** `identity_rotated`: the rotated-elsewhere notice says it; no sentence
         *  of its own, and never the generic failure (decision D2). */
        ROTATED,
        /** `group_closed` from the foreign island's self-join: the room takes
         *  members by invite only. Not [CLOSED] (the island takes no guests at
         *  all) and not [ROOM_CLOSED] (the owner's `allow_guests` is off). */
        INVITE_ONLY,
        /** `blocked` on a join: the room's owner blocked ME. The add path's
         *  [BLOCKED] is the mirror image of it and says the other thing. */
        JOIN_BLOCKED,
        /** `group_not_found`: the room is gone. */
        GONE,
        /** `target_guest`: a copy from another island never owns a room (spec
         *  8.1), so the island refuses the handover. Mapped on every path, not
         *  only on the transfer screen, because the web maps it on all three
         *  (decision E2, one table). */
        TARGET_GUEST,
        /** The island does not know this number at all. Only the native
         *  `POST /members` says it, and only in prose. */
        NO_USER,
        /** The join failed for a reason with no sentence of its own. */
        JOIN_FAILED,
        /** The add failed for a reason with no sentence of its own. */
        ADD_FAILED,

        // ── POST /auth/guest/settle (9.1) ──
        /** `not_a_guest`: the row is a resident's already. Success for the person. */
        SETTLED,
        /** `invite_has_number`: `guest.settle.number_invite`. */
        NUMBER_INVITE,
        /** `entry_required`: the island's existing paid-door sentence. */
        ENTRY_REQUIRED,
        /** `invite_required`: the island's existing invite-door sentence. */
        INVITE_REQUIRED,
        /** `invite_invalid`, `voucher_other_island`, `voucher_expired`. */
        INVITE_INVALID,
        /** `voucher_spent`. */
        VOUCHER_SPENT,
        /** The settle failed for a reason with no sentence of its own. */
        SETTLE_FAILED,
    }

    /** The codes a join and an add share (decision D3, the same table on every
     *  client). Null for anything else. */
    private fun shared(code: String?, status: Int?): Sentence? = when (code) {
        "guest_closed" -> Sentence.CLOSED
        // The owner switch `allow_guests` is off.
        "guest_room_closed" -> Sentence.ROOM_CLOSED
        // The room's member ceiling for guests.
        "guest_room_full" -> Sentence.ROOM_FULL
        "guest_room_limit" -> Sentence.ROOM_LIMIT
        "guest_group_limit" -> Sentence.GROUP_LIMIT
        // Every guest route's limiter answers `rate_limited`.
        "rate_limited" -> Sentence.RATE
        // "Not right now", whichever part of the island said it: the mint is
        // busy, the island ceiling in front of it, or a challenge that went
        // stale again after its one silent retry.
        "guest_unavailable", "island_busy", "guest_busy", "invalid_challenge", "guest_replayed" ->
            Sentence.UNAVAILABLE
        // A limiter that lost its body (a proxy, an older limiter).
        else -> if (status == 429) Sentence.RATE else null
    }

    /** The sentence for a failed join ([message] is what RcqApi threw). A door
     *  refusal can only come from the legacy register, and names an island too
     *  old to take guests. */
    fun joinSentence(message: String?): Sentence {
        val r = RcqApi.refusalOf(message)
        if (r.code == AuthRefusal.IDENTITY_ROTATED) return Sentence.ROTATED
        shared(r.code, r.status)?.let { return it }
        when (r.code) {
            "guest_restricted" -> return Sentence.RESTRICTED
            // ⚠ These four are not guest codes, and they are the ones this
            // table used to lose. A foreign join is two requests, and BOTH can
            // refuse in the island's own words: `/auth/guest` answers
            // `group_not_found` for a room that is gone (auth.py), and the
            // room's own self-join answers `group_closed` and `blocked`
            // (groups.py). The web named all of them from the start; here they
            // fell through to the generic "couldn't join", so one refusal said
            // two different things depending on which client the person held
            // (decision D3, one table on every client).
            "group_closed" -> return Sentence.INVITE_ONLY
            "blocked" -> return Sentence.JOIN_BLOCKED
            "group_not_found" -> return Sentence.GONE
            // A join mints no seat, so only the owner-add route can really
            // answer this; mapped so the two tables match one for one.
            "guest_key_retired" -> return Sentence.STALE_KEY
            "target_guest" -> return Sentence.TARGET_GUEST
            // ⚠ The door, BY CODE (decision E2). The web and iOS read these
            // three off `detail.code` alone; this client used to reach them
            // only through [BackupIslandPick.doorRefusal], which also demands
            // the 403, so the same refusal could say two different things
            // depending on which client the person held. The strict reading
            // stays below as the fallback for a body no code could be read
            // from, which is the case it was written for.
            "entry_required" -> return Sentence.OLD_PAID
            "invite_required", "invite_invalid" -> return Sentence.OLD_INVITE
        }
        return when (BackupIslandPick.doorRefusal(message)) {
            BackupIslandPick.Door.ENTRY -> Sentence.OLD_PAID
            BackupIslandPick.Door.INVITE -> Sentence.OLD_INVITE
            null -> Sentence.JOIN_FAILED
        }
    }

    /** The sentence for a refused `POST /contacts/respond`, or null when the
     *  refusal has none. Only a guest copy accepting a request has one: the
     *  island refuses it (section 10), and the person answers from home. */
    fun respondSentence(message: String?): Sentence? =
        if (RcqApi.refusalOf(message).code == "guest_restricted") Sentence.RESTRICTED_CONTACTS else null

    /** The sentence for a failed add, by code first, then by the three prose
     *  details a native `POST /members` answers with. */
    fun addSentence(message: String?): Sentence {
        val r = RcqApi.refusalOf(message)
        when (r.code) {
            "blocked" -> return Sentence.BLOCKED
            "invite_contacts_only" -> return Sentence.CONTACTS_ONLY
            "invite_nobody" -> return Sentence.NOBODY
            "guest_add_limit" -> return if (r.scope == "seat") Sentence.ADD_SEAT_LIMIT else Sentence.ADD_LIMIT
            "guest_key_retired" -> return Sentence.STALE_KEY
            // On an add, a restricted caller is a guest adder (12.5 mapping).
            "guest_restricted" -> return Sentence.GUEST_ADDER
            "target_guest" -> return Sentence.TARGET_GUEST
        }
        shared(r.code, r.status)?.let { return it }
        val m = message.orEmpty()
        return when {
            m.contains("the group owner has blocked this user") -> Sentence.BLOCKED
            m.contains("only accepts group invites from their contacts") -> Sentence.CONTACTS_ONLY
            m.contains("does not accept group invites") -> Sentence.NOBODY
            // The island has no row for this number. Said in prose only, and
            // named on the web's table, so it is named here too (decision E2).
            m.contains("no such user") -> Sentence.NO_USER
            else -> Sentence.ADD_FAILED
        }
    }

    /** The sentence for a failed `POST /auth/guest/settle` (9.1). [SETTLED] is
     *  not a failure: the row was a resident's already (another device, the
     *  operator), and the caller treats it as done. */
    fun settleSentence(message: String?): Sentence {
        val r = RcqApi.refusalOf(message)
        return when (r.code) {
            "not_a_guest" -> Sentence.SETTLED
            "invite_has_number" -> Sentence.NUMBER_INVITE
            "entry_required" -> Sentence.ENTRY_REQUIRED
            "invite_required" -> Sentence.INVITE_REQUIRED
            // `bad_signature` is a voucher that does not verify, which for the
            // person is the same thing as a code that is not good here (iOS
            // names it, so this table does too).
            "invite_invalid", "voucher_other_island", "voucher_expired", "bad_signature" ->
                Sentence.INVITE_INVALID
            "voucher_spent" -> Sentence.VOUCHER_SPENT
            "guest_unavailable", "island_busy", "guest_busy" -> Sentence.UNAVAILABLE
            // The row is restricted, not settled: the person is told what a
            // guest copy is, not that something failed (decision E2, iOS).
            "guest_restricted" -> Sentence.RESTRICTED
            "rate_limited" -> Sentence.RATE
            else -> if (r.status == 429) Sentence.RATE else Sentence.SETTLE_FAILED
        }
    }

    /** True when the card the contact's home island serves now carries other
     *  keys than the one we pinned: the add stops (`group.add.foreign.stale_key`).
     *  Compared as bytes, so a padded and an unpadded spelling of one key are
     *  the same key. */
    fun cardStale(pinnedIk: String, pinnedSk: String, freshIk: String, freshSk: String): Boolean =
        !sameKey(pinnedIk, freshIk) || !sameKey(pinnedSk, freshSk)

    private fun sameKey(a: String, b: String): Boolean {
        val ra = GuestProof.decodeKey32(a)
        val rb = GuestProof.decodeKey32(b)
        return if (ra != null && rb != null) ra.contentEquals(rb) else a.trim() == b.trim()
    }

    /** Add and the invite entry points beside it are hidden wherever OUR
     *  session on the ROOM'S island is a guest copy: the island refuses every
     *  add from a guest, so offering it would only lead to the refusal.
     *
     *  ⚠ [roomHost] null is a room on our own island, and that is NOT the same
     *  thing as "we live there": the app's own primary session can itself be
     *  signed in to a guest copy ([primaryIsGuest], spec 12.1), and then the
     *  rooms it has here refuse its adds exactly like a foreign island's do.
     *  This guard used to stop at foreign rooms, so that account still saw Add
     *  in every room on its own island and only found out at the refusal.
     *
     *  [copyIsGuest] is our visited copy's flag ON [roomHost]; it says nothing
     *  about our own island, and is not read for a room there. */
    fun hideAddInRoom(roomHost: String?, copyIsGuest: Boolean?, primaryIsGuest: Boolean = false): Boolean =
        if (roomHost == null) primaryIsGuest else copyIsGuest == true

    /** What tapping a room member may open (decision D5). */
    enum class MemberCard {
        /** The ordinary profile. */
        FULL,

        /** A guest copy from another island: its name and one "Add", which
         *  sends the ordinary contact request to that member's number on the
         *  ROOM's island (the copy; the person's home client picks it up
         *  there). No Message, no Call, no visit ping, nothing else. */
        ADD_ONLY,

        /** A seat nobody has claimed yet: nothing to open. */
        NONE,
    }

    /** [member] is the tapped person's row in the room's roster, or null when
     *  the roster does not have them (not loaded, or they left). */
    fun memberCard(member: GroupMember?): MemberCard = when {
        member == null -> MemberCard.FULL
        member.invited -> MemberCard.NONE
        member.guest -> MemberCard.ADD_ONLY
        else -> MemberCard.FULL
    }

    /** What the roster in hand says about leaving (decision E4). */
    enum class LeaveCheck {
        /** Somebody who lives on the room's island stays: leave, no question. */
        SAFE,

        /** We are the last one who lives there, and the island deletes the room
         *  for the copies still in it (section 8.1): say so and ask first. */
        WARN,

        /** The roster we hold cannot answer it. ⚠ Fetch it (one request) and
         *  ask again; never leave silently because the roster was missing, and
         *  never warn on a guess. */
        NEED_ROSTER,
    }

    /** [members] is the roster as we hold it, [memberCount] what the island
     *  says the room's real size is, [ownUin] our number IN THAT ROOM (on a
     *  foreign island, our copy's number there).
     *
     *  The order is what makes a partial roster useful: one other member who
     *  lives on the island is proof enough that leaving strands nobody, even
     *  when the rest of the page is missing. Only the other direction needs the
     *  whole roster, because "everyone I can see is a copy" says nothing about
     *  the members I cannot see. */
    fun leaveCheck(members: List<GroupMember>, ownUin: Int, memberCount: Int): LeaveCheck {
        val me = members.firstOrNull { it.uin == ownUin }
        // A copy leaving strands nobody: the island deletes the room when the
        // last RESIDENT goes, and that is not us.
        if (me != null && (me.guest || me.invited)) return LeaveCheck.SAFE
        val others = members.filter { it.uin != ownUin }
        if (others.any { !it.guest && !it.invited }) return LeaveCheck.SAFE
        // Not in the roster we hold, or holding a page of it: it cannot answer.
        if (me == null || members.isEmpty() || memberCount > members.size) return LeaveCheck.NEED_ROSTER
        // Alone in the room: nothing is taken from anybody.
        if (others.isEmpty()) return LeaveCheck.SAFE
        return LeaveCheck.WARN
    }

    /** True when leaving would leave a room with no resident of its island
     *  (decision D8): we live there ourselves, and everyone else in the room is
     *  a guest copy or an unclaimed seat. The island then deletes the room for
     *  everyone (section 8.1), so the person is told first.
     *
     *  ⚠ Only on a roster that is all there; [leaveCheck] is what the callers
     *  use, because a roster that cannot answer is fetched rather than read as
     *  "nothing to warn about" (decision E4). */
    fun lastResidentLeaving(members: List<GroupMember>, ownUin: Int, memberCount: Int): Boolean =
        leaveCheck(members, ownUin, memberCount) == LeaveCheck.WARN
}
