package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.ShareIntake
import app.rcq.android.data.LocalStores
import app.rcq.android.data.Sections
import app.rcq.android.model.Contact
import app.rcq.android.model.RcqGroup
import app.rcq.android.security.PanicPinService

/**
 * "Send to…" — the chat picker another app's share sheet lands on.
 *
 * RCQ used to be absent from the system share sheet altogether, so sending a
 * picture from any other app (or forwarding one between two RCQ chats) meant
 * saving it to storage first and re-attaching it with the paperclip — the
 * round trip described in report #443. [ShareIntake] captures the ACTION_SEND
 * payload, this screen chooses where it goes, and the chat itself does the
 * actual send through the same paths the paperclip uses.
 *
 * Text rides along as a composer draft rather than being sent on its own, so a
 * shared link can still be introduced with a sentence before it goes.
 *
 * #987: the list is the home roster, not a run of names. Same sections in the
 * same order (Saved Messages, Favourites, the user's own sections, Groups,
 * Online, Offline, Archive last and folded), the same rows, the same fold
 * state to start from, and one search across all of it by name or number. The
 * slicing is [buildHomeLists] and the rows are the ones in ChatListRows.kt, so
 * a chat is found here where it is found at home. A PIN-gated section stays a
 * locked header until its PIN is answered; see [shareSections].
 */
@Composable
fun ShareTargetScreen(
    session: Session,
    req: ShareIntake.Req,
    onPick: (ChatTarget) -> Unit,
    onCancel: () -> Unit,
) {
    val c = RcqTheme.colors
    val contacts by session.contacts.collectAsState()
    val groups by session.groups.collectAsState()
    val messages by session.messages.collectAsState()
    val unread by LocalStores.unread.collectAsState()
    val favorites by LocalStores.favorites.collectAsState()
    val archived by LocalStores.archived.collectAsState()
    val aliases by LocalStores.aliases.collectAsState()
    val sectionsTree by LocalStores.sections.collectAsState()
    val sectionsOk by session.vaultAvailable.collectAsState()
    val sectionFlags by LocalStores.sectionFlags.collectAsState()
    val ownUin = session.uin
    var query by remember { mutableStateOf("") }
    var overrides by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var unlocked by rememberShareUnlocks()
    var pinPrompt by remember { mutableStateOf<ShareSection?>(null) }
    val inDecoy = remember { PanicPinService.inDecoySession }

    // Filtering the input before slicing gives the same result as slicing and
    // then filtering, because every chat's section depends on that chat alone.
    val lists = remember(contacts, groups, unread, messages, favorites, archived, sectionsTree, sectionsOk, aliases, query) {
        buildHomeLists(
            filterShareContacts(contacts, query, aliases),
            filterShareGroups(groups, query),
            unread, favorites, archived, sectionsTree, sectionsOk,
        ) { peer -> messages[peer]?.lastOrNull()?.sentAt ?: 0L }
    }
    val showSaved = ownUin != null && shareQueryMatches(
        query, stringResource(R.string.home_menu_saved), stringResource(R.string.chat_saved_title),
    )
    val sections = remember(lists, sectionsTree, sectionsOk, inDecoy, unlocked, showSaved) {
        shareSections(sectionsTree, lists, sectionsOk, inDecoy, unlocked, showSaved)
    }
    val titles = shareSectionTitles()
    val onHeader: (ShareSection) -> Unit = { sec ->
        when {
            sec.locked -> pinPrompt = sec
            // Folding a section the user got past the PIN for puts the gate
            // back, as it does at home.
            sec.gated -> unlocked = unlocked - sec.id
            // Everything is open while a search is typed; see [shareCollapsed].
            query.isNotBlank() -> Unit
            else -> overrides = overrides + (sec.id to !shareCollapsed(sec, query, sectionFlags, overrides))
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_cancel),
                tint = c.accent,
                modifier = Modifier.size(26.dp).clickable(onClick = onCancel),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.share_to_title),
                    color = c.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(payloadSummary(req), color = c.textSecondary, fontSize = 12.sp, maxLines = 1)
            }
        }

        RcqField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = stringResource(R.string.share_to_search),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        if (sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    stringResource(if (query.isBlank()) R.string.share_to_empty else R.string.search_no_match),
                    color = c.textSecondary,
                    fontSize = 14.sp,
                )
            }
        } else {
            // Only locked headers left under a typed search: say so under them
            // rather than leave a list that looks like it is still loading.
            val noMatch = if (query.isNotBlank() && sections.none { it.id == Sections.SYS_SAVED || it.size > 0 }) {
                stringResource(R.string.search_no_match)
            } else null
            LazyColumn(Modifier.fillMaxSize()) {
                shareList(
                    sections = sections,
                    titles = titles,
                    isCollapsed = { shareCollapsed(it, query, sectionFlags, overrides) },
                    onHeader = onHeader,
                    savedCount = ownUin?.let { messages[it]?.size } ?: 0,
                    ownUin = ownUin,
                    unread = unread,
                    noMatch = noMatch,
                    session = session,
                    onPick = onPick,
                )
            }
        }
    }

    pinPrompt?.let { sec ->
        SectionPinSheet(
            title = titles[sec.id] ?: sec.name.orEmpty(),
            onUnlocked = { unlocked = unlocked + sec.id },
            onDismiss = { pinPrompt = null },
        )
    }
}

/**
 * The list itself, outside the composable so [ShareTargetScreen]'s own frame
 * stays small (the lesson ChatScreen taught about ART's method size limit).
 * Rows are the home screen's, with no long press: there is nothing to preview.
 */
private fun LazyListScope.shareList(
    sections: List<ShareSection>,
    titles: Map<String, String>,
    isCollapsed: (ShareSection) -> Boolean,
    onHeader: (ShareSection) -> Unit,
    savedCount: Int,
    ownUin: Int?,
    unread: Map<String, Int>,
    noMatch: String?,
    session: Session,
    onPick: (ChatTarget) -> Unit,
) {
    for (sec in sections) {
        // Saved Messages is a row, not a section, exactly as at home.
        if (sec.id == Sections.SYS_SAVED) {
            if (ownUin != null) {
                item(key = "saved") {
                    SavedRow(count = savedCount, unread = 0, onClick = { onPick(ChatTarget.Peer(ownUin)) })
                }
            }
            continue
        }
        val collapsed = isCollapsed(sec)
        item(key = "h_${sec.id}") {
            SectionHeader(
                title = titles[sec.id] ?: sec.name.orEmpty(),
                count = sec.size,
                collapsed = collapsed,
                onToggle = { onHeader(sec) },
                // ⚠ No count on a locked header: it is a leak of exactly what
                // the user hid.
                showCount = !sec.locked,
                locked = sec.gated,
            )
        }
        if (collapsed) continue
        // Home draws a user section's groups before its people, and every
        // built-in section people first. Kept, so the two lists read alike.
        if (sec.user) shareGroupRows(sec.id, sec.groups, ownUin, unread, session, onPick)
        items(sec.contacts, key = { contactKey("s_${sec.id}", it) }) { ct ->
            ContactRowItem(
                ct,
                unread = unread[LocalStores.peerThread(ct.uin)] ?: 0,
                session = session,
                onClick = { onPick(ChatTarget.Peer(ct.uin)) },
            )
        }
        if (!sec.user) shareGroupRows(sec.id, sec.groups, ownUin, unread, session, onPick)
    }
    if (noMatch != null) {
        item(key = "no-match") {
            Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                Text(noMatch, color = RcqTheme.colors.textSecondary, fontSize = 14.sp)
            }
        }
    }
}

private fun LazyListScope.shareGroupRows(
    sid: String,
    groups: List<RcqGroup>,
    ownUin: Int?,
    unread: Map<String, Int>,
    session: Session,
    onPick: (ChatTarget) -> Unit,
) {
    items(groups, key = { "s_${sid}_g${it.id}" }) { g ->
        GroupRow(
            group = g,
            ownUin = ownUin ?: 0,
            session = session,
            unread = unread[LocalStores.groupThread(g.id)] ?: 0,
            onClick = { onPick(ChatTarget.Group(g.id)) },
        )
    }
}

/** Titles of the built-in sections. A user section brings its own name. */
@Composable
private fun shareSectionTitles(): Map<String, String> = mapOf(
    Sections.SYS_FAV to stringResource(R.string.home_sec_favorites),
    Sections.SYS_CI to stringResource(R.string.home_sec_cross_island),
    Sections.SYS_GROUPS to stringResource(R.string.home_sec_groups),
    Sections.SYS_ONLINE to stringResource(R.string.home_sec_online),
    Sections.SYS_OFFLINE to stringResource(R.string.home_sec_offline),
    Sections.SYS_ARCHIVE to stringResource(R.string.home_sec_archive),
)

/**
 * Sections whose PIN was answered in this picker. Never persisted, and cleared
 * when the app goes to the background, the same rule as the home screen's own
 * set: a gate that survives leaving the app is not a gate.
 */
@Composable
private fun rememberShareUnlocks(): MutableState<Set<String>> {
    val state = remember { mutableStateOf(emptySet<String>()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) state.value = emptySet()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return state
}

/** One line naming what is about to be sent, so the picker isn't a bare list
 *  of names with no clue what tapping one would do. */
@Composable
private fun payloadSummary(req: ShareIntake.Req): String =
    if (req.uris.isNotEmpty()) pluralStringResource(R.plurals.share_to_files, req.uris.size, req.uris.size)
    else req.text.orEmpty()
