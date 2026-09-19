package app.rcq.android.ui

import app.rcq.android.data.LocalStores
import app.rcq.android.data.Sections
import app.rcq.android.model.Contact
import app.rcq.android.model.RcqGroup
import app.rcq.android.model.UserStatus
import com.google.gson.JsonObject

/*
 * The chat list as data: which chat sits in which section, in what order, and
 * which sections are drawn at all. No Compose in here.
 *
 * Lifted out of [HomeScreen] for the share picker (report #987). The picker
 * used to be one run of every person followed by every group, so the chat a
 * tester was looking for sat wherever the roster happened to put it, with none
 * of the sections they find it by at home. Reading the functions the home
 * screen reads is what keeps the two lists from disagreeing about where a chat
 * lives: a favourite, a chat filed into the user's own section, an archived one.
 */

/** The chat list, already sliced into its sections and sorted.
 *
 *  One object rather than nine loose values so the whole thing can hang off a
 *  single [androidx.compose.runtime.remember] in [HomeScreen]: see the comment
 *  at its call site for why recomputing these on every recomposition was worth
 *  removing. */
internal data class HomeLists(
    val fav: List<Contact>,
    val crossIsland: List<Contact>,
    val online: List<Contact>,
    val offline: List<Contact>,
    val archivedContacts: List<Contact>,
    val visibleGroups: List<RcqGroup>,
    val archivedGroups: List<RcqGroup>,
    val favGroups: List<RcqGroup>,
    /// Chats filed into one of the user's OWN sections, by section id (founder
    /// item 1 of 23.08). A chat in here has already left every derived section
    /// above: it renders once, where the user put it, and nowhere else.
    val filedContacts: Map<String, List<Contact>> = emptyMap(),
    val filedGroups: Map<String, List<RcqGroup>> = emptyMap(),
)

/** A LazyColumn key for a contact row.
 *
 *  ⚠ The uin ALONE is not unique and never was. Islands number independently,
 *  so two cross-island contacts living on two different islands can both be
 *  #5, and `syncCrossIslandContacts` only de-duplicates the foreign list
 *  against the LOCAL roster, not against itself. Two rows with the same key in
 *  one LazyColumn is not a cosmetic problem: Compose throws on the duplicate
 *  and the whole chat list goes down with it. The island is part of who the
 *  row is about, so it is part of the key. A local contact keeps an empty
 *  island, so its key is what it always was plus a trailing "@".
 */
internal fun contactKey(prefix: String, contact: Contact) = "${prefix}_${contact.uin}@${contact.host ?: ""}"

/**
 * Slice [contacts] and [groups] into the home screen's sections.
 *
 * Pure apart from [app.rcq.android.data.SectionsVault.keyForGroup], which reads
 * the visited-islands alias table for a foreign (negative id) group.
 * [lastActivity] is the sent-at of the newest message with a peer, 0 when there
 * is none: a function rather than the message map, so a test does not have to
 * build chat history to check an order.
 */
internal fun buildHomeLists(
    contacts: List<Contact>,
    groups: List<RcqGroup>,
    unread: Map<String, Int>,
    favorites: Set<String>,
    archived: Set<String>,
    sectionsTree: JsonObject,
    sectionsOk: Boolean?,
    lastActivity: (Int) -> Long,
): HomeLists {
    // Unread threads float to the top (iOS parity), then by recency.
    fun byRecency(list: List<Contact>) =
        list.sortedWith(
            compareByDescending<Contact> { (unread[LocalStores.peerThread(it.uin)] ?: 0) > 0 }
                .thenByDescending { lastActivity(it.uin) },
        )
    // Inside a user section: unread first, then favorite, then the sort
    // this client already uses. Favoriting is NOT cleared when a chat is
    // filed; it just has no section of its own to render into any more, so
    // it goes on doing the only other thing it ever did.
    fun bySectionOrder(list: List<Contact>) =
        list.sortedWith(
            compareByDescending<Contact> { (unread[LocalStores.peerThread(it.uin)] ?: 0) > 0 }
                .thenByDescending { LocalStores.peerThread(it.uin) in favorites }
                .thenByDescending { lastActivity(it.uin) },
        )
    fun groupsBySectionOrder(list: List<RcqGroup>) =
        list.sortedWith(
            compareByDescending<RcqGroup> { (unread[LocalStores.groupThread(it.id)] ?: 0) > 0 }
                .thenByDescending { LocalStores.groupThread(it.id) in favorites }
                .thenBy { it.name.lowercase() },
        )

    // ⚠⚠ `!= false`, not `== true`. An unanswered /server/info keeps the
    // filing exactly as the cache has it: a chat can only BE filed if the
    // island had a vault when it was filed, so "we have not asked yet" is
    // never a reason to spill one. Treating unknown as "no vault" takes the
    // members of a PIN-gated section and draws them, by name and with their
    // unread badges, in Online / Offline / Cross-island while the section's
    // own header disappears. Only an explicit "no vault" un-files anything.
    val filing = if (sectionsOk == false) emptyMap() else Sections.memberIndex(sectionsTree)
    val userSecIds = Sections.userSections(sectionsTree).map { Sections.idOf(it) }.toSet()
    // A membership pointing at a section this build does not hold (deleted
    // elsewhere, not synced yet) reads as "not filed" and the chat falls
    // back to its derived section. Rendering is where a stale membership is
    // forgiven, NEVER where it is deleted.
    fun sectionOfContact(ct: Contact): String? =
        // ⚠ The key carries the HOST. LocalStores.peerThread does not, and
        // two people numbered the same on two islands have bitten this
        // project twice already.
        filing[Sections.peerKey(ct.uin, ct.host)]?.takeIf { it in userSecIds }
    fun sectionOfGroup(g: RcqGroup): String? =
        app.rcq.android.data.SectionsVault.keyForGroup(g)?.let { filing[it] }?.takeIf { it in userSecIds }

    val nonArchived = contacts.filterNot { LocalStores.peerThread(it.uin) in archived }
    val visible = groups.filterNot { LocalStores.groupThread(it.id) in archived }
    // Archive beats a user section, which beats every derived one. The
    // membership is KEPT in the slot while a chat is archived, so
    // un-archiving puts it straight back where the user filed it.
    val filedContacts = LinkedHashMap<String, MutableList<Contact>>()
    val looseContacts = ArrayList<Contact>()
    for (ct in nonArchived) {
        val sid = sectionOfContact(ct)
        if (sid != null) filedContacts.getOrPut(sid) { ArrayList() }.add(ct) else looseContacts.add(ct)
    }
    val filedGroups = LinkedHashMap<String, MutableList<RcqGroup>>()
    val looseGroups = ArrayList<RcqGroup>()
    for (g in visible) {
        val sid = sectionOfGroup(g)
        if (sid != null) filedGroups.getOrPut(sid) { ArrayList() }.add(g) else looseGroups.add(g)
    }
    return HomeLists(
        fav = byRecency(looseContacts.filter { LocalStores.peerThread(it.uin) in favorites }),
        // Cross-island contacts live in their own section: presence isn't
        // tracked across islands, so filing them under online/offline would
        // be a lie.
        crossIsland = byRecency(looseContacts.filter { it.host != null && LocalStores.peerThread(it.uin) !in favorites }),
        // ⚠ A favourited CONTACT lives in Favourites and only there, the
        // same rule #748 gave favourited groups four lines below. It was
        // never applied here, so a favourite appeared twice: once at the
        // top and once again under Online or Offline. The web has always
        // filed a contact into exactly one bucket.
        online = byRecency(looseContacts.filter { it.host == null && it.presence != UserStatus.OFFLINE && LocalStores.peerThread(it.uin) !in favorites }),
        offline = byRecency(looseContacts.filter { it.host == null && it.presence == UserStatus.OFFLINE && LocalStores.peerThread(it.uin) !in favorites }),
        archivedContacts = byRecency(contacts.filter { LocalStores.peerThread(it.uin) in archived }),
        // A favorited group lives in Favorites and ONLY there (#748) —
        // the desktop has deduplicated this way from the start, and the
        // double row was the reason people avoided favoriting groups.
        visibleGroups = looseGroups.filter { LocalStores.groupThread(it.id) !in favorites },
        archivedGroups = groups.filter { LocalStores.groupThread(it.id) in archived },
        // Favorited groups are surfaced in the Favorites section (the toggle
        // already persisted, but the section only rendered contacts so a
        // favorited group never showed, reading as "favoriting does
        // nothing").
        favGroups = looseGroups.filter { LocalStores.groupThread(it.id) in favorites },
        filedContacts = filedContacts.mapValues { bySectionOrder(it.value) },
        filedGroups = filedGroups.mapValues { groupsBySectionOrder(it.value) },
    )
}

/** The persisted fold flag of a section in [LocalStores.sectionFlags]: in the
 *  set means collapsed. Archive is not covered by this: it is folded by
 *  default and stores `sec:archive:open` when the user unfolds it. */
internal fun sectionCollapseKey(sid: String): String = when (sid) {
    Sections.SYS_FAV -> "sec:fav"
    Sections.SYS_GROUPS -> "sec:grp"
    Sections.SYS_ONLINE -> "sec:online"
    Sections.SYS_OFFLINE -> "sec:offline"
    Sections.SYS_CI -> "sec:ci"
    else -> "sec:u:$sid"
}

// ── The share picker ─────────────────────────────────────────────────────

/** One section of the share picker, already filtered and gated. */
internal data class ShareSection(
    val id: String,
    /** One of the user's own sections, titled [name], rather than a built-in. */
    val user: Boolean,
    val name: String?,
    /** Behind a PIN on this device: the header draws the key glyph. */
    val gated: Boolean,
    /** Gated and not answered on this visit. [contacts] and [groups] are EMPTY
     *  then, not merely hidden, so nothing downstream can draw a member, count
     *  one, or let a search match one. */
    val locked: Boolean,
    val contacts: List<Contact>,
    val groups: List<RcqGroup>,
) {
    val size: Int get() = contacts.size + groups.size
}

/**
 * The people a share can go to, narrowed by what is typed in the picker's
 * search: the name they chose, the name the user gave them, or their number.
 *
 * Blocked people are dropped whatever the query. Nothing can be sent to them,
 * and a row that fails after the tap is worse than no row (the old picker had
 * the same rule).
 */
internal fun filterShareContacts(contacts: List<Contact>, query: String, aliases: Map<String, String>): List<Contact> {
    val q = query.trim()
    return contacts.filter { ct ->
        !ct.blocked && (
            q.isEmpty() ||
                ct.nickname.contains(q, ignoreCase = true) ||
                aliases[LocalStores.aliasKey(ct.uin, ct.host)]?.contains(q, ignoreCase = true) == true ||
                ct.uin.toString().contains(q)
            )
    }
}

internal fun filterShareGroups(groups: List<RcqGroup>, query: String): List<RcqGroup> {
    val q = query.trim()
    return if (q.isEmpty()) groups else groups.filter { it.name.contains(q, ignoreCase = true) }
}

/** True when [query] is blank or appears in any of [names]. */
internal fun shareQueryMatches(query: String, vararg names: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || names.any { it.contains(q, ignoreCase = true) }
}

/**
 * The picker's sections, in the order the home screen draws them.
 *
 * The same records and the same visibility rules as the section loop in
 * [HomeScreen], with two differences that come from what a picker is for. A
 * section with nothing in it is left out: home keeps an empty user section so
 * it can be filled, and there is nothing to send to in one. Saved Messages is
 * there whenever [showSaved] says so rather than only once it holds a note,
 * because it is the one target every account has.
 *
 * ⚠⚠ A PIN-gated section keeps its header whether or not anything matches,
 * and while locked it carries no members at all. The old picker listed every
 * contact in one run, which put the members of a gated section on screen by
 * name to anyone holding the unlocked phone and a share sheet. A header that
 * came and went with the search would give the same thing away one letter at
 * a time. [unlocked] is memory of this visit only, never persisted. In a decoy
 * session nothing is gated, for the reason given in [HomeScreen].
 */
internal fun shareSections(
    tree: JsonObject,
    lists: HomeLists,
    sectionsOk: Boolean?,
    inDecoy: Boolean,
    unlocked: Set<String>,
    showSaved: Boolean,
): List<ShareSection> {
    // ⚠ `!= false`: an island that has not answered yet renders the cached
    // tree as it is, the same rule as home.
    val gatingOn = sectionsOk != false
    val userIds = Sections.userSections(tree).map { Sections.idOf(it) }.toSet()
    val out = ArrayList<ShareSection>()
    for (rec in Sections.orderedSections(tree)) {
        val id = Sections.idOf(rec)
        if (id == Sections.SYS_SAVED) {
            if (showSaved) out += ShareSection(id, user = false, name = null, gated = false, locked = false, emptyList(), emptyList())
            continue
        }
        val user = Sections.kindOf(rec) == "u"
        if (user && !(gatingOn && id in userIds)) continue
        val members: Pair<List<Contact>, List<RcqGroup>> = when {
            user -> lists.filedContacts[id].orEmpty() to lists.filedGroups[id].orEmpty()
            id == Sections.SYS_FAV -> lists.fav to lists.favGroups
            id == Sections.SYS_CI -> lists.crossIsland to emptyList()
            id == Sections.SYS_GROUPS -> emptyList<Contact>() to lists.visibleGroups
            id == Sections.SYS_ONLINE -> lists.online to emptyList()
            id == Sections.SYS_OFFLINE -> lists.offline to emptyList()
            id == Sections.SYS_ARCHIVE -> lists.archivedContacts to lists.archivedGroups
            // A built-in id from a newer client: keep the record, draw nothing.
            else -> continue
        }
        val pinned = gatingOn && Sections.isPinnedRecord(rec)
        if (!pinned && members.first.isEmpty() && members.second.isEmpty()) continue
        val gated = pinned && !inDecoy
        val locked = gated && id !in unlocked
        out += ShareSection(
            id = id,
            user = user,
            name = Sections.nameOf(rec),
            gated = gated,
            locked = locked,
            contacts = if (locked) emptyList() else members.first,
            groups = if (locked) emptyList() else members.second,
        )
    }
    return out
}

/**
 * Whether a picker section is drawn folded.
 *
 * The home screen's own fold state is where it starts, so a section the user
 * keeps closed at home is closed here too, Archive included. [overrides] are
 * the header taps of this visit and they deliberately do not write back:
 * sending a file is not a reason for the chat list to come back rearranged.
 * While a search is typed every section that is not locked is open, because a
 * match inside a folded section would read as "no such chat".
 */
internal fun shareCollapsed(
    sec: ShareSection,
    query: String,
    flags: Set<String>,
    overrides: Map<String, Boolean>,
): Boolean = when {
    sec.locked -> true
    sec.gated -> false
    query.isNotBlank() -> false
    else -> overrides[sec.id]
        ?: if (sec.id == Sections.SYS_ARCHIVE) "sec:archive:open" !in flags else sectionCollapseKey(sec.id) in flags
}
