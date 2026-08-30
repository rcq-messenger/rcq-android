package app.rcq.android.model

/** A group member, mirrors the iOS RCQGroupMember / server GroupMemberOut. */
data class GroupMember(
    val uin: Int,
    val nickname: String,
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
        uin == ownerUin || members.firstOrNull { it.uin == uin }
            ?.let { it.role == "admin" || it.permissions.isNotEmpty() } == true

    fun memberName(uin: Int): String =
        members.firstOrNull { it.uin == uin }?.nickname ?: "$uin"
}
