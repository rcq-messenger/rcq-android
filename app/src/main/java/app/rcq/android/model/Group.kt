package app.rcq.android.model

/** A group member, mirrors the iOS RCQGroupMember / server GroupMemberOut. */
data class GroupMember(
    val uin: Int,
    val nickname: String,
    val badge: String? = null,
    val role: String,            // owner | admin | member
    val status: String? = null,  // live presence
    val identityKey: String,     // base64 raw X25519 public — we encrypt to this
    val signingKey: String? = null,
    // Granular moderator caps the owner granted (subset of delete|members|info).
    // Owner implicitly has all; a non-owner with any cap is a moderator.
    val permissions: List<String> = emptyList(),
    // This member's client(s) understand the sender-keys group path (gmsg
    // broadcast + skdm). False → only the legacy per-member fan-out reaches
    // them (dual-send migration). See RCQ/docs/sender-keys-design.md.
    val senderKeys: Boolean = false,
    // Profile picture, gated by MEMBERSHIP rather than by the contact list:
    // sharing a group is the relationship here, the same one that already
    // exposes the nickname on this row.
    val avatarMediaId: String? = null,
    val avatarMediaKey: String? = null,
) {
    val presence: UserStatus get() = UserStatus.from(status)
    /** True if this member may delete anyone's message: the owner, an ADMIN,
     *  or a member the owner granted the `delete` cap. Role "admin" joined the
     *  rule in founder batch 21.08, item 3 ("админ не может удалить чужое
     *  сообщение") — the web shipped it first (incoming-store.ts
     *  groupModerator / Chat.tsx canModerate); the granted cap stays so
     *  existing delete-moderators keep the power they were given. */
    fun canDelete(ownerUin: Int): Boolean =
        uin == ownerUin || role == "admin" || "delete" in permissions
    /** The WIDE moderator set: role "admin" or ANY granted cap. Somebody
     *  trusted with part of the room is trusted with all of it for two
     *  purposes that must agree - what the room rules exempt
     *  ([RcqGroup.roomExempt]) and who the roster ranks right after the owner
     *  ([rosterTier]). One definition, so the two cannot drift. NOT
     *  [canDelete], which is the narrower may-retract-others check. */
    val isModerator: Boolean get() = role == "admin" || permissions.isNotEmpty()
    /** True if this member may manage group info — pin, rename, etc. (owner OR
     *  `info` cap). Used to gate pinning a message from the chat. */
    fun canManageInfo(ownerUin: Int): Boolean = uin == ownerUin || "info" in permissions
}

/**
 * A group, mirrors the iOS RCQGroup / server GroupOut. RCQ groups have no
 * group key — a group message is just a per-recipient fan-out of the same
 * v=1 sealed envelope used for 1:1 (rcq-spec 6.4). So all the model needs
 * is the roster (each member's identity key) plus settings.
 */
data class RcqGroup(
    val id: Int,
    val name: String,
    /** The island's mark on the room: null or a kind ("official", ...). */
    val badge: String? = null,
    val description: String? = null,
    val ownerUin: Int,
    val postPolicy: String = "all",   // "all" | "owner_only"
    val isClosed: Boolean = false,
    val membersHidden: Boolean = false,
    val pinnedText: String? = null,
    // Owner-set room policies (#755, desktop parity). Permissive defaults are
    // what every group had before the fields existed.
    val linksAllowed: Boolean = true,
    val inCatalog: Boolean = false,
    val stateBlob: String? = null,
    val stateVer: Long = 0,
    val filesAllowed: Boolean = true,
    val slowmodeSec: Int = 0,
    /** Anti-spam age floor, hours (0 = off): an account younger than this
     *  reads but cannot post. Owner-set; the island enforces it. */
    val minAccountAgeHours: Int = 0,
    val avatarMediaId: String? = null,
    val avatarMediaKey: String? = null,
    val members: List<GroupMember> = emptyList(),
    /// How many people are in the group, independent of whether [members] was
    /// fetched. The chat list needs the number and nothing else, and the roster
    /// is the expensive half of a group payload — every member with two base64
    /// keys, which on the beta group is most of a megabyte.
    val memberCount: Int = 0,
    val createdAt: Long? = null,
    // CLIENT-SIDE only (§5c): the island a cross-island group lives on. When
    // set, [id] is the local NEGATIVE alias and the server-side id lives in
    // VisitedIslandsStore's alias map. Null for own-island groups.
    val host: String? = null,
) {
    /** Broadcast mode (owner_only) is enforced client-side; the server
     *  can't see who's posting under sealed sender. */
    fun canPost(ownUin: Int): Boolean = postPolicy != "owner_only" || ownUin == ownerUin

    /** May [uin] retract OTHER people's messages here (founder batch 21.08,
     *  item 3; web precedent: incoming-store.ts groupModerator)? The owner
     *  may — checked off the group row itself, because the chat list is
     *  fetched without rosters (#650) and [members] can legitimately be
     *  empty. An admin / delete-cap member needs the cached roster; their
     *  delete arriving before any roster was cached is simply ignored, same
     *  as on an old client that never knew the rule. */
    fun moderator(uin: Int): Boolean =
        uin == ownerUin || members.firstOrNull { it.uin == uin }?.canDelete(ownerUin) == true

    /** Is [uin] exempt from the room's CONTENT rules (links/files off, #755)?
     *  A links-off room is an anti-spam rule for MEMBERS; the owner and the
     *  moderators are exempt BOTH ways: their sends pass the gate, and their
     *  links stay clickable in every reader's view (founder decision 29.08).
     *  "Moderator" here is the wide set the web uses (Chat.tsx roomExempt):
     *  role "admin" or ANY granted cap, not just `delete` - somebody trusted
     *  with part of the room is trusted with a link. NOT [moderator], which
     *  is the narrower may-retract-others check. A uin missing from the
     *  roster (or a roster not fetched yet, #650) is not exempt, owner aside:
     *  same failure mode as [moderator], the rule simply is not applied. */
    fun roomExempt(uin: Int): Boolean =
        uin == ownerUin || members.firstOrNull { it.uin == uin }?.isModerator == true

    fun memberName(uin: Int): String =
        members.firstOrNull { it.uin == uin }?.nickname ?: "$uin"
}

/** Where a member sits in the roster: 0 the owner, 1 a moderator, 2 someone
 *  who is around, 3 everyone else (founder, 02.09: the same order on every
 *  client; the web's GroupInfo has the same `rosterTier`). Around is the
 *  contact list's definition: away and do-not-disturb count, invisible and
 *  offline do not, and neither does a member the island declines to report a
 *  presence for.
 *
 *  ⚠ The crown is read off [ownerUin], not off the row's `role`. The two
 *  agree in any snapshot the island sends, but the COMPACT
 *  `group_membership_changed` a big room gets carries the owner alone, so a
 *  handover reaches the screen as a changed number over a roster that has not
 *  moved. The screen keys its memo on the owner as well for the same reason. */
fun GroupMember.rosterTier(ownerUin: Int): Int = when {
    uin == ownerUin -> 0
    isModerator -> 1
    presence == UserStatus.ONLINE || presence == UserStatus.AWAY || presence == UserStatus.DND -> 2
    else -> 3
}

/** The roster in display order: by [rosterTier], then by name
 *  case-insensitively inside a tier, then by uin.
 *
 *  ⚠ "Stable within a tier" was not an order at all. `sortedBy` keeps the
 *  order the roster arrived in, the roster query has no ORDER BY, and the
 *  rows behind it were inserted from an unordered set, so the tail of a big
 *  group looked shuffled and moved between openings (#688). A name is what
 *  people scan for; two equal names would fall back to that same arrival
 *  order, so the uin breaks the tie.
 *
 *  Decorated once, not per comparison: the group screen is built for rosters
 *  of two thousand, and `thenBy { it.nickname.lowercase() }` allocates two
 *  strings on every one of the ~n log n comparisons. */
fun orderedRoster(members: List<GroupMember>, ownerUin: Int): List<GroupMember> =
    members
        .map { m -> Triple(m.rosterTier(ownerUin), m.nickname.lowercase(), m) }
        .sortedWith(compareBy({ it.first }, { it.second }, { it.third.uin }))
        .map { it.third }
