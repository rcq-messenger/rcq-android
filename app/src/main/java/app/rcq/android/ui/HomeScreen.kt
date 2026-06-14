package app.rcq.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.QrCode2
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.CrossIslandRequestsStore
import app.rcq.android.data.LocalStores
import app.rcq.android.model.Contact
import app.rcq.android.model.RcqGroup
import app.rcq.android.model.UserStatus
import app.rcq.android.net.RcqApi
import app.rcq.android.net.RcqFederation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row's worth of long-press action, mirrors iOS ContextAction. */
internal data class ContextAction(
    val title: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** A row in the account switcher: live nick/UIN peeked per local account. */
internal data class AccountRow(
    val id: String,
    val nickname: String,
    val uin: Int?,
    val host: String,
    val active: Boolean,
)

@Composable
internal fun HomeScreen(
    session: Session,
    uin: Int,
    onOpenChat: (Int) -> Unit,
    onOpenGroup: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    // Open ANOTHER user's profile (peer). Used by add-contact so a search
    // result opens the profile preview before you send the request.
    onOpenPeerInfo: (Int) -> Unit = {},
    onOpenNews: () -> Unit = {},
    onOpenOutgoing: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onOpenAudioRooms: () -> Unit = {},
    onOpenRandom: () -> Unit = {},
    onOpenNearby: () -> Unit = {},
    onOpenRadio: () -> Unit = {},
    onSwitchAccount: (String) -> Unit = {},
    onAddAccount: (String?) -> Unit = {},
    onManageAccounts: () -> Unit = {},
) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Native-crash diagnostics (#1): mark that we reached the home screen, then
    // — once we've survived a few seconds of it rendering (group rows + avatars,
    // the "beta chat loads" danger zone) — declare the launch complete so a
    // normal later kill isn't mistaken for a startup crash.
    LaunchedEffect(Unit) {
        app.rcq.android.CrashReporter.crumb(context, "home_compose")
        delay(3000)
        app.rcq.android.CrashReporter.launchComplete(context)
    }
    val contacts by session.contacts.collectAsState()
    val groups by session.groups.collectAsState()
    val pending by session.pending.collectAsState()
    val ciReqs by session.ciRequests.collectAsState()
    val messages by session.messages.collectAsState()
    val ownStatus by session.status.collectAsState()
    val connected by session.connected.collectAsState()
    val stealthActive by session.stealthActive.collectAsState()
    val favorites by LocalStores.favorites.collectAsState()
    val archived by LocalStores.archived.collectAsState()
    val unread by LocalStores.unread.collectAsState()
    val storyGroups by session.stories.collectAsState()

    // Stories: a compressed JPEG awaiting the caption/anonymous dialog before
    // posting, and the group currently open in the full-screen viewer.
    var pendingStory by remember { mutableStateOf<ByteArray?>(null) }
    var viewerGroup by remember { mutableStateOf<RcqApi.StoryGroupOut?>(null) }
    val storyPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val jpeg = withContext(Dispatchers.IO) { compressImageFor(context, uri) }
            if (jpeg != null) pendingStory = jpeg
            else android.widget.Toast.makeText(context, context.getString(R.string.story_pick_failed), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    var showAdd by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showAddAccount by remember { mutableStateOf(false) }
    // Label of a not-yet-built destination tapped from the header menu /
    // bottom bar; drives a "coming soon" dialog until the real screen lands.
    var comingSoon by remember { mutableStateOf<String?>(null) }
    var previewContact by remember { mutableStateOf<Contact?>(null) }
    var previewGroup by remember { mutableStateOf<RcqGroup?>(null) }
    var reportTarget by remember { mutableStateOf<Contact?>(null) }

    // Section fold state is persisted (LocalStores.sectionFlags) so a collapsed
    // section stays collapsed across leaving/re-entering home (report: the
    // offline section kept re-expanding because it was in-memory remember{}).
    // Set membership = "collapsed", except Archive which defaults to collapsed
    // and stores an "open" marker instead.
    val sectionFlags by LocalStores.sectionFlags.collectAsState()
    val collapsedFavorites = "sec:fav" in sectionFlags
    val collapsedGroups = "sec:grp" in sectionFlags
    val collapsedOnline = "sec:online" in sectionFlags
    val collapsedOffline = "sec:offline" in sectionFlags
    val collapsedCrossIsland = "sec:ci" in sectionFlags
    val collapsedArchive = "sec:archive:open" !in sectionFlags

    // Unread threads float to the top (iOS parity), then by recency.
    fun byRecency(list: List<Contact>) =
        list.sortedWith(
            compareByDescending<Contact> { (unread[LocalStores.peerThread(it.uin)] ?: 0) > 0 }
                .thenByDescending { messages[it.uin]?.lastOrNull()?.sentAt ?: 0L },
        )

    val nonArchived = contacts.filterNot { LocalStores.peerThread(it.uin) in archived }
    val favContacts = byRecency(nonArchived.filter { LocalStores.peerThread(it.uin) in favorites })
    // Cross-island contacts live in their own section — presence isn't tracked
    // across islands, so filing them under online/offline would be a lie.
    val crossIslandContacts = byRecency(nonArchived.filter { it.host != null })
    val onlineContacts = byRecency(nonArchived.filter { it.host == null && it.presence != UserStatus.OFFLINE })
    val offlineContacts = byRecency(nonArchived.filter { it.host == null && it.presence == UserStatus.OFFLINE })
    val archivedContacts = byRecency(contacts.filter { LocalStores.peerThread(it.uin) in archived })
    val visibleGroups = groups.filterNot { LocalStores.groupThread(it.id) in archived }
    val archivedGroups = groups.filter { LocalStores.groupThread(it.id) in archived }
    // Favorited groups — surfaced in the Favorites section (the toggle already
    // persisted, but the section only rendered contacts so a favorited group
    // never showed, reading as "favoriting does nothing").
    val favGroups = visibleGroups.filter { LocalStores.groupThread(it.id) in favorites }

    // Local account roster for the switcher (live nick/UIN peeked per account).
    // Decoy-aware roster: in decoy mode only the decoy account is visible, so
    // the account switcher never reveals the hidden real accounts.
    val accountList by app.rcq.android.data.AccountManager.visibleAccounts.collectAsState(initial = app.rcq.android.data.AccountManager.visibleNow())
    val activeId by app.rcq.android.data.AccountManager.activeId.collectAsState()
    val accountRows = remember(accountList, activeId, session.nickname) {
        accountList.sortedBy { it.createdAt }.map { a ->
            AccountRow(
                id = a.id,
                nickname = app.rcq.android.data.SecureStore.peekNickname(context, a.id) ?: "—",
                uin = app.rcq.android.data.SecureStore.peekUin(context, a.id),
                host = a.serverHost ?: app.rcq.android.net.RcqApi.DEFAULT_HOST,
                active = a.id == activeId,
            )
        }
    }

    // Section titles resolved here (LazyListScope below isn't composable).
    val secFavorites = stringResource(R.string.home_sec_favorites)
    val secOnline = stringResource(R.string.home_sec_online)
    val secOffline = stringResource(R.string.home_sec_offline)
    val secCrossIsland = stringResource(R.string.home_sec_cross_island)
    val secArchive = stringResource(R.string.home_sec_archive)

    Box(Modifier.fillMaxSize().background(c.bgPrimary)) {
        // Optional home/chat-list wallpaper (separate from the chat one). Renders
        // behind the list; transparent rows show it, headers stay opaque. No-op
        // on the default ("").
        HomeBackground()
        Column(Modifier.fillMaxSize()) {
            HomeHeader(
                nickname = session.nickname,
                uin = uin,
                serverHost = session.currentServer,
                ownStatus = ownStatus,
                connected = connected,
                stealthActive = stealthActive,
                accounts = accountRows,
                canAddAccount = accountList.size < app.rcq.android.data.AccountManager.MAX_ACCOUNTS,
                onPickStatus = { scope.launch { session.setStatus(it) } },
                onAddContact = { showAdd = true },
                onSearch = { showSearch = true },
                onOpenSettings = onOpenSettings,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenProfile = onOpenProfile,
                onOpenNews = onOpenNews,
                onOpenOutgoing = onOpenOutgoing,
                onOpenSaved = onOpenSaved,
                onOpenAudioRooms = onOpenAudioRooms,
                onOpenRadio = onOpenRadio,
                onPostStory = { storyPicker.launch("image/*") },
                onToggleBypass = { session.setObfuscation(it) },
                onComingSoon = { comingSoon = it },
                onSwitchAccount = onSwitchAccount,
                onAddAccount = { showAddAccount = true },
                onManageAccounts = onManageAccounts,
            )

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (storyGroups.isNotEmpty()) {
                    item(key = "stories") {
                        StoriesStrip(
                            groups = storyGroups,
                            ownUin = session.uin,
                            onAdd = { storyPicker.launch("image/*") },
                            onOpen = { viewerGroup = it },
                        )
                    }
                }
                if (pending.isNotEmpty()) {
                    item(key = "req-h") { SectionHeader(stringResource(R.string.home_sec_requests), pending.size, collapsed = false, onToggle = {}) }
                    items(pending, key = { "p${it.requestId}" }) { req ->
                        PendingRow(
                            name = req.fromNickname,
                            onAccept = { scope.launch { runCatching { session.respond(req.requestId, true) } } },
                            onDecline = { scope.launch { runCatching { session.respond(req.requestId, false) } } },
                        )
                    }
                }
                // Variant A: cross-island message requests (consent).
                if (ciReqs.isNotEmpty()) {
                    item(key = "cireq-h") { SectionHeader(stringResource(R.string.home_sec_ci_requests), ciReqs.size, collapsed = false, onToggle = {}) }
                    items(ciReqs, key = { "ci${it.uin}@${it.host}" }) { r ->
                        CiPendingRow(
                            tag = "${r.uin}@${r.host}",
                            preview = r.preview,
                            onAccept = { scope.launch { runCatching { session.acceptCrossIslandRequest(r.uin, r.host) } } },
                            onBlock = { session.blockCrossIslandRequest(r.uin, r.host) },
                        )
                    }
                }

                // All-empty either because we genuinely have nothing, OR because
                // the first connect/sync hasn't landed yet (tester #4/#9/#13). In
                // the latter case show a "connecting" state with the petal loader
                // instead of the misleading "no contacts" prompt.
                val connecting = !connected && contacts.isEmpty() && groups.isEmpty() && pending.isEmpty()
                if (contacts.isEmpty() && groups.isEmpty() && pending.isEmpty()) {
                    item(key = "empty") {
                        if (connecting) ConnectingState(stealth = stealthActive) else EmptyState(onAdd = { showAdd = true })
                    }
                }

                // Favorites holds BOTH favorited contacts AND groups (mirrors
                // the Archive section). A favorited group used to vanish because
                // this section rendered only contacts.
                if (favContacts.isNotEmpty() || favGroups.isNotEmpty()) {
                    val favUnread = favContacts.sumOf { unread[LocalStores.peerThread(it.uin)] ?: 0 } +
                        favGroups.sumOf { unread[LocalStores.groupThread(it.id)] ?: 0 }
                    item(key = "h_fav") {
                        SectionHeader(secFavorites, favContacts.size + favGroups.size, collapsedFavorites, { LocalStores.setSectionFlag("sec:fav", !collapsedFavorites) }) {
                            UnreadBadge(favUnread)
                        }
                    }
                    if (!collapsedFavorites) {
                        items(favContacts, key = { "fav_${it.uin}" }) { ct ->
                            ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, onClick = { onOpenChat(ct.uin) }, onLongPress = { previewContact = ct })
                        }
                        items(favGroups, key = { "favg_${it.id}" }) { g ->
                            GroupRow(group = g, ownUin = uin, session = session, unread = unread[LocalStores.groupThread(g.id)] ?: 0, onClick = { onOpenGroup(g.id) }, onLongPress = { previewGroup = g })
                        }
                    }
                }

                // Groups — header always shows a "+" to create, like iOS. Hidden
                // while connecting so the "create a group" prompt doesn't flash
                // before the real groups arrive (tester #13).
                if (!connecting) {
                    item(key = "grp-h") {
                        SectionHeader(stringResource(R.string.home_sec_groups), visibleGroups.size, collapsedGroups, { LocalStores.setSectionFlag("sec:grp", !collapsedGroups) }) {
                            Icon(Icons.Filled.Add, "New group", tint = c.accent, modifier = Modifier.size(20.dp).clip(CircleShape).clickable { showCreateGroup = true })
                        }
                    }
                    if (!collapsedGroups) {
                        if (visibleGroups.isEmpty()) {
                            item(key = "grp-empty") {
                                Row(Modifier.fillMaxWidth().clickable { showCreateGroup = true }.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Filled.Add, null, tint = c.accent, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.home_create_group), color = c.textPrimary, fontSize = 13.sp)
                                }
                            }
                        } else {
                            items(items = visibleGroups, key = { it.id }) { g: RcqGroup ->
                                GroupRow(group = g, ownUin = uin, session = session, unread = unread[LocalStores.groupThread(g.id)] ?: 0, onClick = { onOpenGroup(g.id) }, onLongPress = { previewGroup = g })
                            }
                        }
                    }
                }

                contactSection(secOnline, onlineContacts, collapsedOnline, "on", unread, { LocalStores.setSectionFlag("sec:online", !collapsedOnline) }, onOpenChat, onLongPress = { previewContact = it })
                contactSection(secOffline, offlineContacts, collapsedOffline, "off", unread, { LocalStores.setSectionFlag("sec:offline", !collapsedOffline) }, onOpenChat, onLongPress = { previewContact = it })
                contactSection(secCrossIsland, crossIslandContacts, collapsedCrossIsland, "cisl", unread, { LocalStores.setSectionFlag("sec:ci", !collapsedCrossIsland) }, onOpenChat, onLongPress = { previewContact = it })
                // Archive holds BOTH archived contacts AND archived groups.
                // (Bug fix: an archived group was filtered out of the main list
                // but never rendered here, so it vanished entirely and couldn't
                // be un-archived. Now it shows here, long-press to unarchive.)
                if (archivedContacts.isNotEmpty() || archivedGroups.isNotEmpty()) {
                    val archUnread = archivedContacts.sumOf { unread[LocalStores.peerThread(it.uin)] ?: 0 } +
                        archivedGroups.sumOf { unread[LocalStores.groupThread(it.id)] ?: 0 }
                    item(key = "h_arch") {
                        SectionHeader(secArchive, archivedContacts.size + archivedGroups.size, collapsedArchive, { LocalStores.setSectionFlag("sec:archive:open", collapsedArchive) }) {
                            UnreadBadge(archUnread)
                        }
                    }
                    if (!collapsedArchive) {
                        items(archivedContacts, key = { "arch_${it.uin}" }) { ct ->
                            ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, onClick = { onOpenChat(ct.uin) }, onLongPress = { previewContact = ct })
                        }
                        items(archivedGroups, key = { "archg_${it.id}" }) { g ->
                            GroupRow(group = g, ownUin = uin, session = session, unread = unread[LocalStores.groupThread(g.id)] ?: 0, onClick = { onOpenGroup(g.id) }, onLongPress = { previewGroup = g })
                        }
                    }
                }

                item(key = "tail") { Spacer(Modifier.height(8.dp)) }
            }

            BottomBar(
                onAdd = { showAdd = true },
                onQr = { showQr = true },
                onRandom = onOpenRandom,
                onNearby = onOpenNearby,
                onSettings = onOpenSettings,
                // Hide Random on org islands / self-host; keep it on the public server.
                showRandom = session.currentServer == app.rcq.android.net.RcqApi.DEFAULT_HOST,
            )
        }

        if (showSearch) {
            BackHandler { showSearch = false }
            SearchOverlay(
                contacts = contacts,
                onClose = { showSearch = false },
                onSelect = { showSearch = false; onOpenChat(it.uin) },
            )
        }

        previewContact?.let { ct ->
            PreviewOverlay(
                title = ct.nickname,
                subtitle = "#${ct.uin}",
                avatar = { StatusIcon(ct.presence, size = 36.dp) },
                actions = contactActions(ct, session, scope, context, onOpenChat, onReport = { reportTarget = it }),
                onDismiss = { previewContact = null },
            )
        }
        previewGroup?.let { g ->
            PreviewOverlay(
                title = g.name,
                subtitle = pluralStringResource(R.plurals.members, g.members.size, g.members.size),
                avatar = { GroupAvatar(g, session, 36.dp) },
                actions = groupActions(g, uin, session, scope, context, onOpenGroup),
                onDismiss = { previewGroup = null },
            )
        }
        // Full-screen story viewer overlays everything (incl. the bottom bar).
        viewerGroup?.let { g ->
            StoryViewer(session = session, group = g, onClose = { viewerGroup = null })
        }
    }

    if (showAdd) {
        AddContactDialog(
            session = session,
            contacts = contacts,
            onAddUin = { target -> scope.launch { runCatching { session.addContact(target) } } },
            onOpenChat = { u -> showAdd = false; onOpenChat(u) },
            onOpenProfile = { u -> showAdd = false; onOpenPeerInfo(u) },
            onOpenGroup = { g -> showAdd = false; onOpenGroup(g) },
            onDismiss = { showAdd = false },
        )
    }
    if (showQr) {
        val links = remember { session.contactLinks() }
        QrDialog(uin = uin, qrPayload = links.first, shareLink = links.second, onDismiss = { showQr = false })
    }
    if (showAddAccount) {
        AddAccountDialog(
            onAdd = { host -> showAddAccount = false; onAddAccount(host) },
            onDismiss = { showAddAccount = false },
        )
    }
    if (showCreateGroup) {
        CreateGroupDialog(
            contacts = contacts,
            onCreate = { name, members ->
                showCreateGroup = false
                scope.launch {
                    runCatching { session.createGroup(name, members) }
                        .onSuccess { onOpenGroup(it.id) }
                        .onFailure { e ->
                            // A 403 here can mean THREE different things (the owner
                            // blocked the invitee / the invitee only accepts invites
                            // from contacts / the invitee accepts no invites). Inspect
                            // the body instead of collapsing them into one message.
                            val em = e.message ?: ""
                            val msg = when {
                                em.contains("the group owner has blocked this user") -> context.getString(R.string.gi_add_blocked)
                                em.contains("only accepts group invites from their contacts") -> context.getString(R.string.gi_add_contacts_only)
                                em.contains("does not accept group invites") -> context.getString(R.string.gi_add_nobody)
                                em.contains("403") -> context.getString(R.string.group_create_blocked)
                                else -> context.getString(R.string.group_create_failed)
                            }
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                        }
                }
            },
            onDismiss = { showCreateGroup = false },
        )
    }
    reportTarget?.let { ct ->
        ReportDialog(
            name = ct.nickname,
            onSubmit = { reason -> scope.launch { runCatching { session.report(ct.uin, reason) } }; reportTarget = null },
            onDismiss = { reportTarget = null },
        )
    }
    comingSoon?.let { feature ->
        AlertDialog(
            onDismissRequest = { comingSoon = null },
            containerColor = c.bgSecondary,
            title = { Text(feature, color = c.textPrimary) },
            text = { Text(stringResource(R.string.home_coming_soon_body, feature), color = c.textSecondary) },
            confirmButton = { TextButton(onClick = { comingSoon = null }) { Text(stringResource(R.string.common_ok), color = c.accent) } },
        )
    }
    // Confirm + caption/anonymous before posting a picked photo as a story.
    pendingStory?.let { jpeg ->
        var caption by remember { mutableStateOf("") }
        var anon by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingStory = null },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.story_post_title), color = c.textPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it.take(280) },
                        placeholder = { Text(stringResource(R.string.story_caption_hint), color = c.textSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { anon = !anon },
                    ) {
                        Checkbox(checked = anon, onCheckedChange = { anon = it })
                        Text(stringResource(R.string.story_anonymous_post), color = c.textPrimary, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cap = caption.trim()
                    val a = anon
                    pendingStory = null
                    scope.launch {
                        runCatching { session.postPhotoStory(jpeg, cap, a) }
                            .onSuccess { android.widget.Toast.makeText(context, context.getString(R.string.story_posted), android.widget.Toast.LENGTH_SHORT).show() }
                            .onFailure { android.widget.Toast.makeText(context, context.getString(R.string.story_post_failed), android.widget.Toast.LENGTH_SHORT).show() }
                    }
                }) { Text(stringResource(R.string.story_post), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { pendingStory = null }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }
}

/** Horizontal ring strip at the top of Home (iOS stories row parity). First
 *  tile adds a story; each following tile is one poster's group — an accent
 *  ring while it has an unwatched story, grey once all are seen. */
@Composable
private fun StoriesStrip(
    groups: List<RcqApi.StoryGroupOut>,
    ownUin: Int?,
    onAdd: () -> Unit,
    onOpen: (RcqApi.StoryGroupOut) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        item(key = "story-add") { StoryTile(label = stringResource(R.string.story_add), initial = "+", ring = false, onClick = onAdd) }
        items(groups, key = { it.owner_uin ?: (it.stories.firstOrNull()?.id ?: "anon") }) { g ->
            val isOwn = g.owner_uin != null && g.owner_uin == ownUin
            val unwatched = g.stories.any { !it.viewed }
            val name = when {
                isOwn -> stringResource(R.string.story_you)
                g.is_anonymous || g.owner_uin == null -> stringResource(R.string.story_anonymous)
                else -> g.owner_nickname ?: "${g.owner_uin}"
            }
            StoryTile(label = name, initial = name.take(1).uppercase(), ring = unwatched, onClick = { onOpen(g) })
        }
    }
}

@Composable
private fun StoryTile(label: String, initial: String, ring: Boolean, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(66.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
    ) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(if (ring) c.accent else c.divider),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(53.dp).clip(CircleShape).background(c.bgSecondary), contentAlignment = Alignment.Center) {
                Text(initial, color = c.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = c.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun contactActions(
    contact: Contact,
    session: Session,
    scope: CoroutineScope,
    context: android.content.Context,
    onOpenChat: (Int) -> Unit,
    onReport: (Contact) -> Unit,
): List<ContextAction> {
    val thread = LocalStores.peerThread(contact.uin)
    val fav = LocalStores.isFavorite(thread)
    val muted = LocalStores.isMuted(thread)
    val archived = LocalStores.isArchived(thread)
    fun s(id: Int) = context.getString(id)
    return listOf(
        ContextAction(s(R.string.home_send_message), Icons.AutoMirrored.Filled.Chat) { onOpenChat(contact.uin) },
        ContextAction(s(if (fav) R.string.home_remove_fav else R.string.home_add_fav), if (fav) Icons.Filled.Star else Icons.Filled.StarBorder) { LocalStores.toggleFavorite(thread) },
        ContextAction(s(if (muted) R.string.home_unmute else R.string.home_mute), if (muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff) { LocalStores.toggleMute(thread) },
        ContextAction(s(if (archived) R.string.home_unarchive else R.string.home_archive), if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive) { LocalStores.toggleArchive(thread) },
        ContextAction(s(if (contact.blocked) R.string.home_unblock else R.string.home_block), if (contact.blocked) Icons.Outlined.Block else Icons.Filled.Block, destructive = !contact.blocked) { scope.launch { session.toggleBlock(contact.uin) } },
        ContextAction(s(R.string.home_report), Icons.Filled.Flag, destructive = true) { onReport(contact) },
        ContextAction(s(R.string.home_remove), Icons.Filled.PersonRemove, destructive = true) { scope.launch { session.removeContact(contact.uin) } },
    )
}

private fun groupActions(
    group: RcqGroup,
    ownUin: Int,
    session: Session,
    scope: CoroutineScope,
    context: android.content.Context,
    onOpenGroup: (Int) -> Unit,
): List<ContextAction> {
    val thread = LocalStores.groupThread(group.id)
    val fav = LocalStores.isFavorite(thread)
    val muted = LocalStores.isMuted(thread)
    val archived = LocalStores.isArchived(thread)
    val isOwner = group.ownerUin == ownUin
    fun s(id: Int) = context.getString(id)
    return listOf(
        ContextAction(s(R.string.home_open_chat), Icons.AutoMirrored.Filled.Chat) { onOpenGroup(group.id) },
        ContextAction(s(if (fav) R.string.home_remove_fav else R.string.home_add_fav), if (fav) Icons.Filled.Star else Icons.Filled.StarBorder) { LocalStores.toggleFavorite(thread) },
        ContextAction(s(if (muted) R.string.home_unmute else R.string.home_mute), if (muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff) { LocalStores.toggleMute(thread) },
        ContextAction(s(if (archived) R.string.home_unarchive else R.string.home_archive), if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive) { LocalStores.toggleArchive(thread) },
        if (isOwner)
            ContextAction(s(R.string.home_delete_group), Icons.Filled.Delete, destructive = true) { scope.launch { session.deleteGroup(group.id) } }
        else
            ContextAction(s(R.string.home_leave_group), Icons.AutoMirrored.Filled.ExitToApp, destructive = true) { scope.launch { session.leaveGroup(group.id) } },
    )
}

/** Home top bar, iOS ContactListView parity: left = account switcher,
 *  centre = status picker + nick + UIN (no '#', no presence dot), right =
 *  overflow menu of the things you can do (add contact, search, story,
 *  news, saved). Items whose screens aren't built yet route to a
 *  "coming soon" dialog. */
@Composable
private fun HomeHeader(
    nickname: String,
    uin: Int,
    serverHost: String,
    ownStatus: UserStatus,
    connected: Boolean,
    stealthActive: Boolean,
    accounts: List<AccountRow>,
    canAddAccount: Boolean,
    onPickStatus: (UserStatus) -> Unit,
    onAddContact: () -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenNews: () -> Unit,
    onOpenOutgoing: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenAudioRooms: () -> Unit,
    onOpenRadio: () -> Unit,
    onPostStory: () -> Unit,
    onToggleBypass: (Boolean) -> Unit,
    onComingSoon: (String) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onManageAccounts: () -> Unit,
) {
    val c = RcqTheme.colors
    var statusMenu by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var showPresenceInfo by remember { mutableStateOf(false) }
    var showStealthInfo by remember { mutableStateOf(false) }

    if (showPresenceInfo) {
        AlertDialog(
            onDismissRequest = { showPresenceInfo = false },
            confirmButton = {
                TextButton(onClick = { showPresenceInfo = false }) {
                    Text(stringResource(R.string.common_ok), color = c.accent)
                }
            },
            title = { Text(stringResource(R.string.presence_info_title), color = c.textPrimary) },
            text = { Text(stringResource(R.string.presence_info_body), color = c.textSecondary) },
            containerColor = c.bgSecondary,
        )
    }
    if (showStealthInfo) {
        AlertDialog(
            onDismissRequest = { showStealthInfo = false },
            confirmButton = {
                TextButton(onClick = { showStealthInfo = false }) {
                    Text(stringResource(R.string.common_ok), color = c.accent)
                }
            },
            title = { Text(stringResource(R.string.stealth_info_title), color = c.textPrimary) },
            text = { Text(stringResource(R.string.stealth_info_body), color = c.textSecondary) },
            containerColor = c.bgSecondary,
        )
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
        // Left — account switcher: tap a row to hot-swap identities, or
        // add / manage local accounts (iOS AccountManager parity).
        Box(Modifier.align(Alignment.CenterStart)) {
            Icon(
                // Black (textPrimary), not accent green, per founder.
                Icons.Outlined.AccountCircle, "Accounts", tint = c.textPrimary,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { accountMenu = true },
            )
            DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                accounts.forEach { a ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(a.nickname, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text(a.host, color = c.textSecondary, fontSize = 12.sp)
                                a.uin?.let { Text("#$it", color = c.textMono, fontSize = 12.sp) }
                            }
                        },
                        leadingIcon = {
                            if (a.active) Icon(Icons.Filled.Check, null, tint = c.accent)
                            else Icon(Icons.Outlined.AccountCircle, null, tint = c.textSecondary)
                        },
                        onClick = { accountMenu = false; if (!a.active) onSwitchAccount(a.id) },
                    )
                }
                if (canAddAccount) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_menu_add_account), color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Filled.Add, null, tint = c.accent) },
                        onClick = { accountMenu = false; onAddAccount() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_manage_accounts), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Outlined.AccountCircle, null, tint = c.accent) },
                    onClick = { accountMenu = false; onManageAccounts() },
                )
            }
        }

        // Centre — status picker + identity, with the "stay visible" countdown
        // chip hugging the left of the status icon. The chip is balanced by an
        // invisible copy on the right so it never shifts the centred nick/UIN
        // block (the UIN sits UNDER the nick, iOS ContactListView parity).
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box {
                StatusIcon(ownStatus, size = 30.dp, modifier = Modifier.clip(CircleShape).clickable { statusMenu = true })
                // Always-visible connection indicator (#16): a small dot on the
                // identity flower. Green = socket up, amber = connecting /
                // offline (the app auto-reconnects). Non-interactive overlay.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(c.bgPrimary)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(if (connected) c.statusOnline else c.statusAway),
                )
                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                    // "Stay visible after you leave" countdown, top-right of the
                    // status menu (moved here from the home header).
                    PresenceCountdownChip(
                        modifier = Modifier.align(Alignment.End).padding(end = 10.dp, top = 4.dp, bottom = 2.dp),
                        onClick = { statusMenu = false; showPresenceInfo = true },
                    )
                    listOf(UserStatus.ONLINE, UserStatus.AWAY, UserStatus.DND, UserStatus.INVISIBLE, UserStatus.OFFLINE).forEach { st ->
                        DropdownMenuItem(
                            text = { Text(stringResource(st.labelRes), color = c.textPrimary) },
                            leadingIcon = { StatusIcon(st, size = 18.dp) },
                            onClick = { onPickStatus(st); statusMenu = false },
                        )
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // includeFontPadding=false + tight line heights drop the built-in
                // font leading that left a big gap between the nick and the UIN
                // under it (founder: they should sit almost touching).
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenProfile).padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(nickname, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)), modifier = Modifier.widthIn(max = 150.dp))
                Text("#$uin", color = c.textMono, fontSize = 12.sp, lineHeight = 12.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
            }
            // Right of the nick/UIN: a status-width slot holding the stealth
            // shield when the censorship bypass is engaged (iOS StealthHeaderBadge
            // parity). The 30dp slot balances the leading status icon so the
            // nick/UIN stays dead-centred.
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                if (stealthActive) {
                    Icon(
                        Icons.Filled.Shield,
                        stringResource(R.string.stealth_info_title),
                        tint = c.accent,
                        modifier = Modifier.size(22.dp).clip(CircleShape).clickable { showStealthInfo = true },
                    )
                }
            }
        }

        // Right — overflow menu.
        Box(Modifier.align(Alignment.CenterEnd)) {
            Icon(
                Icons.Filled.MoreVert, "Menu", tint = c.textPrimary,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable { overflowMenu = true },
            )
            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                // Censorship bypass: manual override, back by request. It also
                // engages automatically when a direct connection looks blocked,
                // but auto-detection can be wrong ("green" indicator yet no real
                // traffic), so the manual on/off lives here too — it engages/drops
                // sing-box LIVE (setObfuscation) without an app restart.
                DropdownMenuItem(
                    text = { Text(stringResource(if (stealthActive) R.string.home_menu_bypass_disable else R.string.home_menu_bypass_enable), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.Shield, null, tint = if (stealthActive) c.accent else c.textSecondary) },
                    onClick = { overflowMenu = false; onToggleBypass(!stealthActive) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.diag_title), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.NetworkCheck, null, tint = c.accent) },
                    onClick = { overflowMenu = false; onOpenDiagnostics() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_add_contact), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.PersonAdd, null, tint = c.accent) },
                    onClick = { overflowMenu = false; onAddContact() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_outgoing), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Outlined.Schedule, null, tint = c.accent) },
                    onClick = { overflowMenu = false; onOpenOutgoing() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_search), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.accent) },
                    onClick = { overflowMenu = false; onSearch() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_post_story), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.AddAPhoto, null, tint = c.accent) },
                    onClick = { overflowMenu = false; onPostStory() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_news), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.Newspaper, null, tint = c.accent) },
                    onClick = { overflowMenu = false; onOpenNews() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_saved), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.Bookmark, null, tint = c.accent) },
                    onClick = { overflowMenu = false; onOpenSaved() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_audio_rooms), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.GraphicEq, null, tint = c.accent) },
                    onClick = { overflowMenu = false; onOpenAudioRooms() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_radio), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.Sensors, null, tint = c.accent) },
                    onClick = { overflowMenu = false; onOpenRadio() },
                )
            }
        }
    }
}

/** Compact "stay visible" countdown shown left of the home status icon:
 *  how long until presence drops back to offline after the user leaves. The
 *  window is anchored in Privacy settings (LocalStores.presenceWindow) and
 *  re-anchored whenever the user changes it; hidden when off or elapsed. */
@Composable
private fun PresenceCountdownChip(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = RcqTheme.colors
    val window by app.rcq.android.data.LocalStores.presenceWindow.collectAsState()
    val remaining by produceState<Long?>(
        initialValue = window?.minus(System.currentTimeMillis())?.takeIf { it > 0 },
        window,
    ) {
        val w = window
        if (w == null) { value = null; return@produceState }
        while (true) {
            val r = w - System.currentTimeMillis()
            value = if (r > 0) r else null
            if (r <= 0) return@produceState
            kotlinx.coroutines.delay(15_000L)
        }
    }
    val r = remaining ?: return
    val mod = modifier
        .clip(RoundedCornerShape(50))
        .background(c.bgSecondary)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 7.dp, vertical = 3.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = mod,
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = c.accent, modifier = Modifier.size(12.dp))
        Text(presenceCountdownLabel(r), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun presenceCountdownLabel(ms: Long): String {
    val totalMin = (ms / 60_000L).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> stringResource(R.string.presence_countdown_hm, h, m)
        h > 0 -> stringResource(R.string.presence_countdown_h, h)
        totalMin > 0 -> stringResource(R.string.presence_countdown_m, m)
        else -> stringResource(R.string.presence_countdown_lt1m)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.contactSection(
    title: String,
    rows: List<Contact>,
    collapsed: Boolean,
    keyPrefix: String,
    unread: Map<String, Int>,
    onToggle: () -> Unit,
    onOpenChat: (Int) -> Unit,
    onLongPress: (Contact) -> Unit,
) {
    if (rows.isEmpty()) return
    // Aggregate unread for the section header badge (shown when collapsed
    // so a folded section still signals new messages — iOS parity).
    val sectionUnread = rows.sumOf { unread[LocalStores.peerThread(it.uin)] ?: 0 }
    item(key = "h_$keyPrefix") {
        SectionHeader(title, rows.size, collapsed, onToggle) {
            UnreadBadge(sectionUnread)
        }
    }
    if (!collapsed) {
        items(rows, key = { "${keyPrefix}_${it.uin}" }) { ct ->
            ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, onClick = { onOpenChat(ct.uin) }, onLongPress = { onLongPress(ct) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(group: RcqGroup, ownUin: Int, session: Session, unread: Int, onClick: () -> Unit, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    // Observe the mute set so toggling mute reflects on the row immediately
    // (was a one-shot read → the bell only appeared after leaving + re-entering).
    val mutedSet by LocalStores.muted.collectAsState()
    val muted = LocalStores.groupThread(group.id) in mutedSet
    val reactSet by LocalStores.reactionInbox.collectAsState()
    val mentionSet by LocalStores.mentionInbox.collectAsState()
    val thread = LocalStores.groupThread(group.id)
    val hasReaction = thread in reactSet
    val hasMention = thread in mentionSet
    Row(
        Modifier.fillMaxWidth().scale(scale)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLongPress)
            .background(c.bgPrimary).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            // Animate the group's GIF avatar here too (founder: it animated in
            // the chat but not on the home list). Safe: the chat list is a
            // LazyColumn, so only the handful of on-screen group rows compose,
            // and SafeAnimatedGif memoizes its decoder per instance — far lighter
            // than the emoticon-dense-message churn that caused the old OOM.
            GroupAvatar(group, session, 28.dp, animated = true)
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(group.name, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (group.ownerUin == ownUin) Icon(Icons.Filled.Star, "Owner", tint = c.accent, modifier = Modifier.size(12.dp))
                if (muted) Icon(Icons.Filled.NotificationsOff, null, tint = c.textSecondary, modifier = Modifier.size(11.dp))
            }
            Text(
                pluralStringResource(R.plurals.members, group.members.size, group.members.size) +
                    (group.host?.let { " · $it" } ?: ""),
                color = c.textSecondary, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasMention || hasReaction) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hasMention) Icon(Icons.Filled.AlternateEmail, stringResource(R.string.home_mention_indicator), tint = c.accent, modifier = Modifier.size(14.dp))
                if (hasReaction) Icon(Icons.Filled.Favorite, stringResource(R.string.home_reaction_indicator), tint = Color(0xFFE5484D), modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRowItem(contact: Contact, unread: Int, onClick: () -> Unit, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val mutedSet by LocalStores.muted.collectAsState()
    val muted = LocalStores.peerThread(contact.uin) in mutedSet
    val reactSet by LocalStores.reactionInbox.collectAsState()
    val mentionSet by LocalStores.mentionInbox.collectAsState()
    val thread = LocalStores.peerThread(contact.uin)
    val hasReaction = thread in reactSet
    val hasMention = thread in mentionSet

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLongPress)
            .background(c.bgPrimary)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            StatusIcon(contact.presence, size = 28.dp, crossIsland = contact.host != null)
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    contact.nickname,
                    color = if (contact.presence == UserStatus.OFFLINE) c.textSecondary else c.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                GenderIcon(contact.gender)
                if (contact.blocked) Icon(Icons.Outlined.Block, null, tint = c.statusBusy, modifier = Modifier.size(11.dp))
                if (muted) Icon(Icons.Filled.NotificationsOff, null, tint = c.textSecondary, modifier = Modifier.size(11.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#${contact.uin}", color = c.textMono, fontSize = 12.sp)
                val ctx = LocalContext.current
                val sub = when {
                    // §5c: a cross-island peer shows its island (presence/last_seen
                    // don't cross islands), then any status message.
                    contact.host != null -> contact.host + (contact.statusMessage?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                    !contact.statusMessage.isNullOrEmpty() -> contact.statusMessage
                    contact.presence == UserStatus.OFFLINE && contact.lastSeen != null -> stringResource(R.string.last_seen_fmt, relativeLastSeen(contact.lastSeen, ctx))
                    else -> null
                }
                if (sub != null) {
                    Text(
                        "· $sub",
                        color = c.textSecondary,
                        fontSize = 12.sp,
                        fontStyle = if (!contact.statusMessage.isNullOrEmpty()) FontStyle.Italic else FontStyle.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (hasMention || hasReaction) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hasMention) Icon(Icons.Filled.AlternateEmail, stringResource(R.string.home_mention_indicator), tint = c.accent, modifier = Modifier.size(14.dp))
                if (hasReaction) Icon(Icons.Filled.Favorite, stringResource(R.string.home_reaction_indicator), tint = Color(0xFFE5484D), modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun PendingRow(name: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgPrimary).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PersonAdd, null, tint = c.accent, modifier = Modifier.size(24.dp))
        }
        Text(name, color = c.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.home_accept), color = c.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onAccept).padding(8.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.home_decline), color = c.textSecondary, modifier = Modifier.clickable(onClick = onDecline).padding(8.dp))
    }
}

@Composable
private fun CiPendingRow(tag: String, preview: String, onAccept: () -> Unit, onBlock: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgPrimary).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Public, null, tint = c.accent, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(tag, color = c.textPrimary, fontSize = 14.sp)
            if (preview.isNotEmpty()) Text(preview, color = c.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
        Text(stringResource(R.string.home_accept), color = c.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onAccept).padding(8.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.ci_block), color = c.textSecondary, modifier = Modifier.clickable(onClick = onBlock).padding(8.dp))
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    val c = RcqTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusIcon(UserStatus.ONLINE, size = 44.dp)
        Text(stringResource(R.string.home_empty_title), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.home_empty_body), color = c.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        CapsuleButton(stringResource(R.string.home_empty_cta), onClick = onAdd)
    }
}

/** Shown on the home list while the first connect/sync is still in flight, so we
 *  don't claim "no contacts" before the roster has had a chance to load
 *  (tester #4/#9/#13). The petal loader is the branded busy indicator. */
@Composable
private fun ConnectingState(stealth: Boolean = false) {
    val c = RcqTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 70.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PetalLoader(size = 72.dp)
        Text(stringResource(R.string.home_connecting_title), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.home_connecting_body), color = c.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        // When the censorship bypass had to engage, say so (iOS "engaging
        // stealth" parity) instead of silently looking stuck.
        if (stealth) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Shield, null, tint = c.accent, modifier = Modifier.size(15.dp))
                Text(stringResource(R.string.connecting_stealth), color = c.accent, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BottomBar(onAdd: () -> Unit, onQr: () -> Unit, onRandom: () -> Unit, onNearby: () -> Unit, onSettings: () -> Unit, showRandom: Boolean = true) {
    val c = RcqTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(c.bgSecondary)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.Filled.PersonAdd, stringResource(R.string.home_bar_add), onAdd)
        BarButton(Icons.Filled.QrCode2, stringResource(R.string.home_bar_qr), onQr)
        // Random/chat-roulette is a public-network feature; hide it on org
        // islands / self-host (founder's call). Nearby stays everywhere (mesh).
        if (showRandom) BarButton(Icons.Filled.Shuffle, stringResource(R.string.home_bar_random), onRandom)
        BarButton(Icons.Filled.NearMe, stringResource(R.string.home_bar_nearby), onNearby)
        BarButton(Icons.Filled.Settings, stringResource(R.string.home_bar_settings), onSettings)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = label, tint = c.textPrimary, modifier = Modifier.size(22.dp))
        Text(label, color = c.textPrimary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PreviewOverlay(
    title: String,
    subtitle: String,
    avatar: @Composable () -> Unit,
    actions: List<ContextAction>,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.9f, label = "preview")

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .scale(scale)
                .padding(28.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.bgSecondary)
                .clickable(enabled = false) {}
                .fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                avatar()
                Column {
                    Text(title, color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = c.textMono, fontSize = 12.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            actions.forEach { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { a.onClick(); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val tint = if (a.destructive) Color(0xFFE5484D) else c.textPrimary
                    Icon(a.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                    Text(a.title, color = tint, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun SearchOverlay(contacts: List<Contact>, onClose: () -> Unit, onSelect: (Contact) -> Unit) {
    val c = RcqTheme.colors
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, contacts) {
        if (query.isBlank()) contacts
        else contacts.filter { it.nickname.contains(query, true) || it.uin.toString().contains(query) }
    }
    Column(Modifier.fillMaxSize().background(c.bgPrimary).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search contacts", color = c.textSecondary) },
                singleLine = true,
            )
            Text("Cancel", color = c.accent, modifier = Modifier.clickable(onClick = onClose).padding(12.dp))
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.uin }) { ct ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(ct) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusIcon(ct.presence, size = 26.dp)
                    Column {
                        Text(ct.nickname, color = c.textPrimary, fontSize = 15.sp)
                        Text("#${ct.uin}", color = c.textMono, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddContactDialog(
    session: Session,
    contacts: List<Contact>,
    onAddUin: (Int) -> Unit,
    onOpenChat: (Int) -> Unit,
    onOpenProfile: (Int) -> Unit,
    onOpenGroup: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<RcqApi.UserInfo>>(emptyList()) }
    var groups by remember { mutableStateOf<List<RcqApi.GroupPreviewOut>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Debounced server-side search of people AND joinable groups (iOS Add
    // overlay parity — the old dialog only accepted a raw UIN). A pasted group
    // link is handled separately (below), so don't waste a search on it.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2 || GroupLinkParser.parse(q) != null) { users = emptyList(); groups = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(300)
        users = session.searchUsers(q)
        // Don't surface CLOSED groups in open search — they're not joinable this
        // way (join only via invite link); iOS already hides them (#11).
        groups = session.searchGroups(q).filter { !it.is_closed }
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.add_title), color = c.textPrimary) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.add_search_hint), color = c.textSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val digits = query.trim().toIntOrNull()
                // A pasted GROUP invite link (https://rcq.app/g/<id>@<host> or
                // rcq://group/<id>) → JOIN it, including a group on ANOTHER island.
                // This is the entry point for a user handed a link who isn't in any
                // shared chat (the only other joinable place was GroupLinkBubble).
                val groupRef = remember(query) { GroupLinkParser.parse(query.trim()) }
                Box(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (groupRef != null) {
                            val foreignHost = groupRef.host?.takeIf { it != session.currentServer }
                            val gp by produceState<RcqApi.GroupPreviewOut?>(initialValue = null, groupRef) {
                                value = if (foreignHost != null) session.previewForeignGroup(foreignHost, groupRef.id)
                                        else session.previewGroup(groupRef.id)
                            }
                            val pv = gp
                            val closed = pv?.is_closed == true
                            AddResultRow(
                                pv?.name ?: stringResource(if (foreignHost != null) R.string.group_invite_island else R.string.group_invite_loading),
                                when {
                                    closed -> stringResource(R.string.group_invite_closed)
                                    pv != null -> pluralStringResource(R.plurals.members, pv.member_count, pv.member_count)
                                    foreignHost != null -> foreignHost
                                    else -> stringResource(R.string.group_invite_tap_join)
                                },
                                isGroup = true, session = session,
                                avatarMediaId = pv?.avatar_media_id, avatarMediaKey = pv?.avatar_media_key,
                            ) {
                                if (closed) {
                                    android.widget.Toast.makeText(context, context.getString(R.string.group_invite_closed_hint), android.widget.Toast.LENGTH_LONG).show()
                                } else scope.launch {
                                    val opened = if (foreignHost != null) session.joinForeignGroup(foreignHost, groupRef.id)
                                                 else session.joinGroup(groupRef.id)?.let { groupRef.id }
                                    if (opened != null) onOpenGroup(opened)
                                    else android.widget.Toast.makeText(context, context.getString(R.string.group_invite_join_failed), android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        // Exact-UIN add stays possible even for users whose
                        // profile isn't searchable (privacy-gated).
                        if (digits != null && users.none { it.uin == digits }) {
                            // Say WHICH island a bare number reaches — a user on
                            // is2 typing an api number must see the mismatch
                            // (beta report: the request "never arrived").
                            AddResultRow("#$digits", stringResource(R.string.add_on_own_island, session.currentServer), accent = true) {
                                // Open the profile first so you can preview before
                                // sending the request (the profile has the button).
                                onOpenProfile(digits)
                            }
                        }
                        // Federation (F2): an explicit `uin@host` whose host is NOT
                        // our OWN island → add it as a cross-island contact. Compared
                        // to our own island, not the flagship: a self-hoster on is2
                        // adding `911@api.rcq.app` must see the flagship as cross-island.
                        val ci = remember(query) {
                            query.trim().takeIf { it.contains("@") }
                                ?.let { runCatching { RcqFederation.parseAddress(it) }.getOrNull() }
                                ?.takeIf { it.host != session.currentServer }
                        }
                        if (ci != null && groupRef == null) {
                            // A backup island is the SAME identity, not a second
                            // account — "adding" your own copy just hangs as a
                            // self-request. Surface it as you, don't add.
                            if (session.isOwnAddress(ci.uin, ci.host)) {
                                AddResultRow("${ci.uin}@${ci.host}", stringResource(R.string.add_ci_self)) {}
                            } else {
                                AddResultRow("${ci.uin}@${ci.host}", stringResource(R.string.add_ci_row), accent = true) {
                                    scope.launch {
                                        if (session.addCrossIslandContact(ci.uin, ci.host)) onOpenChat(ci.uin)
                                    }
                                }
                            }
                        }
                        users.forEach { u ->
                            val already = contacts.any { it.uin == u.uin }
                            val sub = when {
                                already -> "#${u.uin} · " + stringResource(R.string.add_already_contact)
                                u.uin in sentTo -> "#${u.uin} · " + stringResource(R.string.add_request_sent)
                                else -> "#${u.uin}"
                            }
                            AddResultRow(u.nickname ?: "#${u.uin}", sub) {
                                // Contact → open chat; not yet a contact → open the
                                // profile preview where you can send the request.
                                if (already) onOpenChat(u.uin) else onOpenProfile(u.uin)
                            }
                        }
                        groups.forEach { g ->
                            AddResultRow(
                                g.name ?: "#${g.id}",
                                pluralStringResource(R.plurals.members, g.member_count, g.member_count),
                                isGroup = true,
                                session = session,
                                avatarMediaId = g.avatar_media_id,
                                avatarMediaKey = g.avatar_media_key,
                            ) {
                                scope.launch { if (session.joinGroup(g.id) != null) onOpenGroup(g.id) }
                            }
                        }
                        if (searching) {
                            Text(stringResource(R.string.add_searching), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        } else if (query.trim().length >= 2 && users.isEmpty() && groups.isEmpty() && digits == null && groupRef == null) {
                            Text(stringResource(R.string.add_no_results), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        } else if (query.isEmpty()) {
                            Text(stringResource(R.string.add_search_prompt), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
    )
}

/** One tappable search result (user or group) in the Add window. */
@Composable
private fun AddResultRow(
    title: String,
    subtitle: String,
    accent: Boolean = false,
    isGroup: Boolean = false,
    session: Session? = null,
    avatarMediaId: String? = null,
    avatarMediaKey: String? = null,
    onClick: () -> Unit,
) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Groups show their real avatar (iOS parity, #11); users/exact-UIN keep
        // the glyph.
        if (isGroup && session != null) {
            GroupAvatarMedia(avatarMediaId, avatarMediaKey, session, 26.dp)
        } else {
            Icon(
                if (isGroup) Icons.Filled.Groups else if (accent) Icons.Filled.PersonAdd else Icons.Outlined.AccountCircle,
                null, tint = if (accent) c.accent else c.textSecondary, modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Create another anonymous identity. Server host is optional — blank uses
 *  the default public server; a custom host registers onto an org island /
 *  self-host. The new account is added alongside the current one. */
@Composable
private fun AddAccountDialog(onAdd: (String?) -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    var host by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.home_menu_add_account), color = c.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.add_account_body), color = c.textSecondary, fontSize = 13.sp)
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text(stringResource(R.string.csrv_host), color = c.textSecondary) },
                    placeholder = { Text(app.rcq.android.net.RcqApi.DEFAULT_HOST, color = c.textSecondary) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(host.ifBlank { null }) }) {
                Text(stringResource(R.string.add_account_create), color = c.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
    )
}

@Composable
private fun CreateGroupDialog(contacts: List<Contact>, onCreate: (String, List<Int>) -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<Int, Boolean>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.home_new_group), color = c.textPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.home_group_name), color = c.textSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.home_add_members), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(contacts, key = { it.uin }) { ct ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected[ct.uin] = !(selected[ct.uin] ?: false) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selected[ct.uin] ?: false, onCheckedChange = { selected[ct.uin] = it })
                            StatusIcon(ct.presence, size = 22.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(ct.nickname, color = c.textPrimary, fontSize = 15.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val members = selected.filterValues { it }.keys.toList()
            val ok = name.isNotBlank()
            TextButton(enabled = ok, onClick = { onCreate(name.trim(), members) }) {
                Text(stringResource(R.string.home_create), color = if (ok) c.accent else c.textSecondary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
    )
}

@Composable
private fun ReportDialog(name: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.home_report_title, name), color = c.textPrimary) },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.home_report_reason), color = c.textSecondary) },
                minLines = 2,
            )
        },
        confirmButton = {
            TextButton(enabled = reason.isNotBlank(), onClick = { onSubmit(reason.trim()) }) {
                Text(stringResource(R.string.home_report_submit), color = if (reason.isNotBlank()) Color(0xFFE5484D) else c.textSecondary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
    )
}
