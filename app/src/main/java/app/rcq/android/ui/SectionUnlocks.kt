package app.rcq.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.rcq.android.data.LocalStores
import app.rcq.android.data.Sections
import app.rcq.android.model.Contact
import app.rcq.android.model.RcqGroup
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The sections whose PIN has been answered on this visit, for the one account
 * they were answered in.
 *
 * ⚠ One set for the whole app (#1045 review). It used to live inside the home
 * screen, and the chat gate, the share picker and the section picker each had
 * their own idea or none: a chat filed in a locked section opened with no PIN
 * from every door that was not the home list (the Add sheet, a contact's card,
 * a link, a notification), and a section opened in the share picker asked
 * again at the chat. Never persisted. Cleared when the app goes to the
 * background, when it relocks and on an account switch (MainActivity), and a
 * section is taken out of it when its header is folded again. Keyed by
 * account because built-in section ids are the same in every account.
 */
internal object SectionUnlocks {
    private val state = MutableStateFlow<Pair<Int?, Set<String>>>(null to emptySet())

    fun add(ownUin: Int?, sid: String) = state.update { (uin, set) ->
        ownUin to (if (uin == ownUin) set else emptySet()) + sid
    }

    fun remove(ownUin: Int?, sid: String) = state.update { (uin, set) ->
        if (uin == ownUin) uin to set - sid else uin to set
    }

    fun clear() { state.value = null to emptySet() }

    fun of(ownUin: Int?): Set<String> = state.value.let { (uin, set) -> if (uin == ownUin) set else emptySet() }

    @Composable
    fun collect(ownUin: Int?): Set<String> {
        val s by state.collectAsState()
        return if (s.first == ownUin) s.second else emptySet()
    }
}

/** Who a set of PIN-gated sections hides right now: every contact and group
 *  filed in one that is not open on this visit, by the keys the section tree
 *  uses ([Sections.peerKey], [app.rcq.android.data.SectionsVault.keyForGroup])
 *  plus the plain ids, and which user sections are locked. */
internal data class SectionHidden(
    val contactKeys: Set<String> = emptySet(),
    val groupIds: Set<Int> = emptySet(),
    val groupKeys: Set<String> = emptySet(),
    val lockedIds: Set<String> = emptySet(),
) {
    fun hides(ct: Contact): Boolean = Sections.peerKey(ct.uin, ct.host) in contactKeys
    fun hides(g: RcqGroup): Boolean = g.id in groupIds
    fun hidesKey(key: String): Boolean = key in contactKeys || key in groupKeys
}

/** [SectionHidden] for the home screen's own slicing ([lists]): the same rule
 *  its section loop draws by, a PIN-gated record ([Sections.isPinnedRecord])
 *  with the gate on and not answered. Nothing is hidden in a decoy session,
 *  where nothing is gated. */
internal fun sectionHidden(
    tree: JsonObject,
    lists: HomeLists,
    gatingOn: Boolean,
    inDecoy: Boolean,
    unlocked: Set<String>,
): SectionHidden {
    if (!gatingOn || inDecoy) return SectionHidden()
    val cKeys = HashSet<String>()
    val gIds = HashSet<Int>()
    val gKeys = HashSet<String>()
    val locked = HashSet<String>()
    for (rec in Sections.orderedSections(tree)) {
        if (!Sections.isPinnedRecord(rec)) continue
        val sid = Sections.idOf(rec)
        if (sid in unlocked) continue
        if (Sections.kindOf(rec) == "u") locked += sid
        val (cs, gs) = sectionMembers(sid, lists)
        cs.forEach { cKeys += Sections.peerKey(it.uin, it.host) }
        gs.forEach { g ->
            gIds += g.id
            app.rcq.android.data.SectionsVault.keyForGroup(g)?.let { gKeys += it }
        }
    }
    return SectionHidden(cKeys, gIds, gKeys, locked)
}

/** The contacts and groups the home screen draws under section [sid]. */
internal fun sectionMembers(sid: String, lists: HomeLists): Pair<List<Contact>, List<RcqGroup>> = when (sid) {
    Sections.SYS_FAV -> lists.fav to lists.favGroups
    Sections.SYS_CI -> lists.crossIsland to emptyList()
    Sections.SYS_ONLINE -> lists.online to emptyList()
    Sections.SYS_OFFLINE -> lists.offline to emptyList()
    Sections.SYS_ARCHIVE -> lists.archivedContacts to lists.archivedGroups
    Sections.SYS_GROUPS -> emptyList<Contact>() to lists.visibleGroups
    Sections.SYS_SAVED -> emptyList<Contact>() to emptyList()
    else -> lists.filedContacts[sid].orEmpty() to lists.filedGroups[sid].orEmpty()
}

/** The PIN-gated section [target] is drawn in on the home screen, or null when
 *  it is in none (or gating is off, or this is a decoy session). The chat gate
 *  asks this section's PIN before the chat opens, whichever door it was opened
 *  from ([SectionUnlocks]). */
internal fun lockingSectionOf(
    target: ChatTarget,
    tree: JsonObject,
    lists: HomeLists,
    gatingOn: Boolean,
    inDecoy: Boolean,
): String? {
    if (!gatingOn || inDecoy) return null
    for (rec in Sections.orderedSections(tree)) {
        if (!Sections.isPinnedRecord(rec)) continue
        val sid = Sections.idOf(rec)
        val (cs, gs) = sectionMembers(sid, lists)
        val found = when (target) {
            is ChatTarget.Peer -> cs.any { it.uin == target.uin }
            is ChatTarget.Group -> gs.any { it.id == target.id }
        }
        if (found) return sid
    }
    return null
}

/** [lockingSectionOf] from the session's live state, for the chat gate. */
internal fun lockingSectionNow(session: app.rcq.android.Session, target: ChatTarget): String? {
    val tree = LocalStores.sections.value
    val sectionsOk = session.vaultAvailable.value
    val lists = buildHomeLists(
        session.contacts.value, session.groups.value, emptyMap(),
        LocalStores.favorites.value, LocalStores.archived.value, tree, sectionsOk,
    ) { 0L }
    return lockingSectionOf(target, tree, lists, sectionsOk != false, app.rcq.android.security.PanicPinService.inDecoySession)
}

/** The gate in front of a chat filed in a locked section: the section's name,
 *  and the PIN ([PinGate]: counted, off the main thread, biometric where the
 *  app takes one). */
@Composable
internal fun SectionLockGate(sid: String, onBack: () -> Unit, onUnlocked: () -> Unit) {
    val tree by LocalStores.sections.collectAsState()
    val title = when (sid) {
        Sections.SYS_FAV -> androidx.compose.ui.res.stringResource(app.rcq.android.R.string.home_sec_favorites)
        Sections.SYS_CI -> androidx.compose.ui.res.stringResource(app.rcq.android.R.string.home_sec_cross_island)
        Sections.SYS_GROUPS -> androidx.compose.ui.res.stringResource(app.rcq.android.R.string.home_sec_groups)
        Sections.SYS_ONLINE -> androidx.compose.ui.res.stringResource(app.rcq.android.R.string.home_sec_online)
        Sections.SYS_OFFLINE -> androidx.compose.ui.res.stringResource(app.rcq.android.R.string.home_sec_offline)
        Sections.SYS_ARCHIVE -> androidx.compose.ui.res.stringResource(app.rcq.android.R.string.home_sec_archive)
        else -> Sections.recordFor(tree, sid)?.let { Sections.nameOf(it) }.orEmpty()
    }
    PinGate(
        title = title,
        hint = androidx.compose.ui.res.stringResource(app.rcq.android.R.string.chat_locked_hint),
        onBack = onBack,
        onUnlocked = onUnlocked,
    )
}
