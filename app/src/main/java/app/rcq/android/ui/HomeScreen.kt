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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.material.icons.filled.QrCodeScanner
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.CrossIslandRequestsStore
import app.rcq.android.security.PanicPinService
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
import kotlinx.coroutines.withTimeoutOrNull

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

/** Open-state and typed query of the "Add" search sheet, kept OUTSIDE
 *  HomeScreen's composition.
 *
 *  Opening someone's profile from a search result takes HomeScreen out of
 *  composition entirely (MainActivity swaps the whole screen), so a
 *  `remember` here died and the user came back from the profile to a bare
 *  home screen with their search gone. Reported by vss: "я зашёл в профиль,
 *  нажал назад, а окна Добавить уже нет, я его не закрывал".
 *
 *  Held here rather than in LocalStores because it is transient navigation
 *  state, not a preference: it should not outlive the process.
 */
internal object AddSheet {
    val open = mutableStateOf(false)
    val query = mutableStateOf("")

    /** Leave the search for good: the user got where they were going (opened a
     *  chat, sent a request) or dismissed it. Backing out of a profile
     *  deliberately does NOT call this. */
    fun close() {
        open.value = false
        query.value = ""
    }
}

@Composable
internal fun HomeScreen(
    session: Session,
    uin: Int,
    onOpenChat: (Int) -> Unit,
    onOpenGroup: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenBackupIsland: () -> Unit = {},
    /** Tapped the "new version" badge in the header. Defaults to nothing so a
     *  preview or a test host does not have to care. */
    onUpdateBadge: (app.rcq.android.net.UpdateChecker.Update) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    // Open ANOTHER user's profile (peer). Used by add-contact so a search
    // result opens the profile preview before you send the request.
    onOpenPeerInfo: (Int) -> Unit = {},
    // Same, but pinned to OUR island. A bare number means "this island" and
    // nothing else, so it must not resolve to a same-numbered contact from
    // somewhere else — the whole of report #433.
    onOpenPeerInfoHere: (Int) -> Unit = {},
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
    // News badge: the server tells us the newest post id, we remember what was
    // shown. Refreshed on appear rather than pushed — a news post is not worth
    // a wake-up, and this is the moment the dot would be looked at anyway.
    val newsUnread by session.newsUnread.collectAsState()
    LaunchedEffect(Unit) { session.refreshNewsBadge() }
    val contacts by session.contacts.collectAsState()
    val groups by session.groups.collectAsState()
    val pending by session.pending.collectAsState()
    val ciReqs by session.ciRequests.collectAsState()
    val messages by session.messages.collectAsState()
    // Saved Messages is the thread with yourself. Counted from the map the
    // screen already has, so this costs nothing extra.
    val savedCount = messages[uin]?.size ?: 0
    val ownStatus by session.status.collectAsState()
    val connected by session.connected.collectAsState()
    val stealthActive by session.stealthActive.collectAsState()
    // Failover is otherwise invisible: mail keeps arriving through a backup
    // island while the primary is down and nothing on screen says so.
    val viaBackup by session.receivingViaBackup.collectAsState()
    val routeVerified by session.routeVerified.collectAsState()
    val bypassManual by session.bypassManual.collectAsState()
    // Push reachability nudge: a killed/swiped app only receives messages via a
    // UnifiedPush distributor (ntfy). With none installed the user silently gets
    // nothing while closed ("приложение перестало работать после закрытия").
    // Show a dismissible banner pointing at setup; the account is never lost.
    // Persisted (not remember{}): navigating into a chat and back recreated
    // HomeScreen and resurrected the dismissed banner (v0.66 regression).
    // Some vendor builds clear special access on every app update, and a call
    // that arrives without it degrades to a heads-up notification that is easy
    // to miss. We cannot hold onto the grant, but we can say it went.
    var fsiLost by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        fsiLost = app.rcq.android.push.Push.fullScreenIntentLostOnUpdate(context)
    }
    val pushNudgeDismissed by LocalStores.pushNudgeDismissed.collectAsState()
    // remember, NOT a bare call: pushState() asks the PackageManager which apps
    // can act as a UnifiedPush distributor, and this line sits in the body of a
    // screen that recomposes on every presence tick, message and unread change.
    // A package query per frame is exactly the kind of cost that makes going
    // back to the home screen feel heavy (vss: "планируется ускорить переход в
    // главное окно"). Distributors are installed and uninstalled by hand, so
    // once per visit to this screen is often enough.
    val hasDistributor = remember {
        app.rcq.android.push.Push.pushState(context) != app.rcq.android.push.Push.PushState.NO_DISTRIBUTOR
    }
    val showPushNudge = !pushNudgeDismissed && !hasDistributor
    val favorites by LocalStores.favorites.collectAsState()
    val archived by LocalStores.archived.collectAsState()
    val unread by LocalStores.unread.collectAsState()
    val storyGroups by session.stories.collectAsState()
    // Operator-toggleable features (admin console → Features); default true.
    val nearbyEnabled by session.nearbyEnabled.collectAsState()
    val randomEnabled by session.randomEnabled.collectAsState()
    val storiesEnabled by session.storiesEnabled.collectAsState()

    // Stories: a compressed JPEG awaiting the caption/anonymous sheet before
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

    var showAdd by AddSheet.open
    var showQr by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showAddAccount by remember { mutableStateOf(false) }
    // Label of a not-yet-built destination tapped from the header menu /
    // bottom bar; drives a "coming soon" sheet until the real screen lands.
    var comingSoon by remember { mutableStateOf<String?>(null) }
    var previewContact by remember { mutableStateOf<Contact?>(null) }
    var previewGroup by remember { mutableStateOf<RcqGroup?>(null) }
    var reportTarget by remember { mutableStateOf<Contact?>(null) }
    // Irreversible-on-this-device actions, each behind a confirmation.
    var clearPeerTarget by remember { mutableStateOf<Contact?>(null) }
    var clearGroupTarget by remember { mutableStateOf<RcqGroup?>(null) }
    var removeTarget by remember { mutableStateOf<Contact?>(null) }

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
    // #593: both request headers drew the same chevron as every other section
    // and then ignored the tap ("выглядят сворачиваемыми, но не сворачиваются").
    // They fold and persist like the rest now; the count in the header keeps
    // saying how many are waiting while folded.
    val collapsedRequests = "sec:req" in sectionFlags
    val collapsedCiRequests = "sec:cireq" in sectionFlags

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
    // A migrated decoy session has no roster entry at all (the decoy is not an
    // account), so the switcher is built from the session's own synthetic
    // identity instead. Without this the switcher would be empty while the
    // header showed a name, which is the kind of inconsistency the duress view
    // exists to avoid.
    val inOwnStoreDecoy = session.inDecoySession
    val accountRows = remember(accountList, activeId, session.nickname, inOwnStoreDecoy) {
        if (inOwnStoreDecoy) listOf(
            AccountRow(
                id = app.rcq.android.data.DecoyStore.STORE_ID,
                nickname = session.nickname,
                uin = session.uin,
                host = app.rcq.android.net.RcqApi.DEFAULT_HOST,
                active = true,
            )
        ) else accountList.sortedBy { it.createdAt }.map { a ->
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
                // ⚠ The header has no fill of its own — it stands on the
                // wallpaper above, not on `c.bgPrimary` — so its foregrounds
                // are chosen by what the wallpaper puts under them and not by
                // the light/dark theme (#554). No wallpaper → this IS `c`.
                chrome = homeChrome(),
                session = session,
                nickname = session.nickname,
                uin = uin,
                serverHost = session.currentServer,
                ownStatus = ownStatus,
                connected = connected,
                stealthActive = stealthActive,
                routeVerified = routeVerified,
                bypassManual = bypassManual,
                onUpdateBadge = onUpdateBadge,
                accounts = accountRows,
                canAddAccount = !inOwnStoreDecoy && accountList.size < app.rcq.android.data.AccountManager.MAX_ACCOUNTS,
                onPickStatus = { scope.launch { session.setStatus(it) } },
                onAddContact = { showAdd = true },
                onSearch = { showSearch = true },
                onOpenSettings = onOpenSettings,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenProfile = onOpenProfile,
                onOpenNews = onOpenNews,
                newsUnread = newsUnread,
                onOpenOutgoing = onOpenOutgoing,
                onOpenSaved = onOpenSaved,
                onOpenAudioRooms = onOpenAudioRooms,
                onOpenRadio = onOpenRadio,
                onPostStory = { storyPicker.launch("image/*") },
                showPostStory = storiesEnabled,
                onToggleBypass = { session.setObfuscation(it) },
                onComingSoon = { comingSoon = it },
                onSwitchAccount = onSwitchAccount,
                onAddAccount = { showAddAccount = true },
                onManageAccounts = onManageAccounts,
            )

            if (viaBackup) {
                // Tappable: the text promises that the backup can be made
                // primary "in settings" and vss could not find where. It is in
                // Settings -> Backup island, so the sentence that mentions it
                // now goes there. lineHeight is set explicitly because the
                // theme's default for this size left the wrapped banner looking
                // double-spaced (also his report).
                Text(
                    stringResource(R.string.home_via_backup),
                    color = c.statusBusy,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bgSecondary)
                        .clickable(onClick = onOpenBackupIsland)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (fsiLost) {
                    item(key = "fsi-lost") {
                        FullScreenIntentBanner(
                            onFix = {
                                fsiLost = false
                                // Not cleared here: the grant may or may not be
                                // given on the screen we are about to open, and
                                // the next read re-checks it. Sending the user
                                // to settings is not the same as fixing it.
                                app.rcq.android.push.Push.openFullScreenIntentSettings(context)
                            },
                            onDismiss = {
                                fsiLost = false
                                app.rcq.android.push.Push.clearFullScreenIntentNotice(context)
                            },
                        )
                    }
                }
                if (showPushNudge) {
                    item(key = "push-nudge") {
                        PushNudgeBanner(
                            onSetup = { app.rcq.android.push.Push.openNtfyInstall(context) },
                            onDismiss = { LocalStores.dismissPushNudge() },
                        )
                    }
                }
                if (storyGroups.isNotEmpty() && storiesEnabled) {
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
                    item(key = "req-h") {
                        SectionHeader(stringResource(R.string.home_sec_requests), pending.size, collapsedRequests, { LocalStores.setSectionFlag("sec:req", !collapsedRequests) })
                    }
                    if (!collapsedRequests) {
                        items(pending, key = { "p${it.requestId}" }) { req ->
                            PendingRow(
                                name = req.fromNickname,
                                fromUin = req.fromUin,
                                onOpenProfile = onOpenPeerInfo,
                                onAccept = { scope.launch { runCatching { session.respond(req.requestId, true) } } },
                                onDecline = { scope.launch { runCatching { session.respond(req.requestId, false) } } },
                            )
                        }
                    }
                }
                // Variant A: cross-island message requests (consent).
                if (ciReqs.isNotEmpty()) {
                    item(key = "cireq-h") {
                        SectionHeader(stringResource(R.string.home_sec_ci_requests), ciReqs.size, collapsedCiRequests, { LocalStores.setSectionFlag("sec:cireq", !collapsedCiRequests) })
                    }
                    if (!collapsedCiRequests) {
                        items(ciReqs, key = { "ci${it.uin}@${it.host}" }) { r ->
                            // §5f rows carry the requester's own name and greeting.
                            // The island tag stays visible either way: a cross-island
                            // name must never be able to pass as a local contact.
                            val address = "${r.uin}@${r.host}"
                            CiPendingRow(
                                tag = r.nickname?.takeIf { it.isNotBlank() }?.let { "$it · $address" } ?: address,
                                preview = r.preview.ifEmpty {
                                    if (r.contactReq) stringResource(R.string.ci_contact_request) else ""
                                },
                                onAccept = { scope.launch { runCatching { session.acceptCrossIslandRequest(r.uin, r.host) } } },
                                onDismiss = { session.dismissCrossIslandRequest(r.uin, r.host) },
                                onBlock = { session.blockCrossIslandRequest(r.uin, r.host) },
                            )
                        }
                    }
                }

                // All-empty either because we genuinely have nothing, OR because
                // the first connect/sync hasn't landed yet (tester #4/#9/#13). In
                // the latter case show a "connecting" state with the petal loader
                // instead of the misleading "no contacts" prompt.
                val connecting = !connected && contacts.isEmpty() && groups.isEmpty() && pending.isEmpty()
                if (contacts.isEmpty() && groups.isEmpty() && pending.isEmpty()) {
                    item(key = "empty") {
                        if (connecting) ConnectingState(stealth = stealthActive) else EmptyState(onAdd = { showAdd = true }, myUin = uin)
                    }
                } else if (contacts.isEmpty() && !connecting) {
                    // ⚠ The state above is almost never reached: every new
                    // account is joined to RCQ Beta, so `groups` is never empty
                    // and the "no contacts yet" screen a first-time user was
                    // supposed to see does not render for them at all.
                    //
                    // And having no CONTACTS is the state three quarters of all
                    // accounts are in. They are not staring at an empty app —
                    // they are in one big group chat with nobody of their own,
                    // and nothing in the interface has ever offered them a way
                    // to bring someone.
                    item(key = "no-contacts") {
                        InviteNudge(myUin = uin, onAdd = { showAdd = true })
                    }
                }

                // Saved Messages as a real row, but ONLY once there is something
                // in it (founder). An always-present row would cost a line
                // forever to the many people who never write a note; it stays
                // reachable from the overflow menu when empty.
                //
                // It sits at the TOP, above Favourites: it is your own shelf,
                // not a conversation, and it used to render after Groups, which
                // put it in the middle of the list with headers above and below
                // (vss: "why is it in the middle, it belongs at the very top
                // next to favourites").
                if (savedCount > 0) {
                    item(key = "saved") {
                        SavedRow(
                            count = savedCount,
                            unread = 0,
                            onClick = onOpenSaved,
                        )
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
                            ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, session = session, onClick = { onOpenChat(ct.uin) }, onLongPress = { previewContact = ct })
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

                contactSection(secOnline, onlineContacts, collapsedOnline, "on", unread, session, { LocalStores.setSectionFlag("sec:online", !collapsedOnline) }, onOpenChat, onLongPress = { previewContact = it })
                contactSection(secOffline, offlineContacts, collapsedOffline, "off", unread, session, { LocalStores.setSectionFlag("sec:offline", !collapsedOffline) }, onOpenChat, onLongPress = { previewContact = it })
                contactSection(secCrossIsland, crossIslandContacts, collapsedCrossIsland, "cisl", unread, session, { LocalStores.setSectionFlag("sec:ci", !collapsedCrossIsland) }, onOpenChat, onLongPress = { previewContact = it })
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
                            ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, session = session, onClick = { onOpenChat(ct.uin) }, onLongPress = { previewContact = ct })
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
                // Operator toggles Random / Nearby via the admin console (Features).
                showRandom = randomEnabled,
                showNearby = nearbyEnabled,
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
                title = session.contactName(ct.uin),
                subtitle = "#${ct.uin}",
                // No host: a PERSON's picture always lives on OUR island —
                // ours natively, a cross-island contact's because §5e DEPOSITS
                // the blob here rather than having us pull it from theirs.
                avatar = { PersonAvatar(ct.avatarMediaId, ct.avatarMediaKey, ct.presence, session, 36.dp) },
                actions = contactActions(ct, session, scope, context, onOpenChat,
                    onReport = { reportTarget = it },
                    onClearThread = { clearPeerTarget = it },
                    onRemove = { removeTarget = it }),
                onDismiss = { previewContact = null },
            )
        }
        previewGroup?.let { g ->
            PreviewOverlay(
                title = g.name,
                subtitle = pluralStringResource(R.plurals.members, g.memberCount, g.memberCount),
                avatar = { GroupAvatar(g, session, 36.dp) },
                actions = groupActions(g, uin, session, scope, context, onOpenGroup,
                    onClearThread = { clearGroupTarget = it }),
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
            onOpenChat = { u -> AddSheet.close(); onOpenChat(u) },
            // NOT closed: looking at a profile is part of deciding whether to
            // add someone, and the search should still be there on the way
            // back. Opening the profile takes this whole screen (sheet
            // included) out of composition, so leaving the flag set is enough
            // — the sheet comes back with the query when the user does. It
            // closes when they actually leave for somewhere (a chat, a group)
            // or send the request from the profile.
            onOpenProfile = { u -> onOpenPeerInfo(u) },
            onOpenProfileHere = { u -> onOpenPeerInfoHere(u) },
            onOpenGroup = { g -> AddSheet.close(); onOpenGroup(g) },
            onDismiss = { AddSheet.close() },
        )
    }
    if (showQr) {
        val links = remember { session.contactLinks() }
        QrDialog(uin = uin, qrPayload = links.first, shareLink = links.second, session = session, onDismiss = { showQr = false })
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
            name = session.contactName(ct.uin),
            onSubmit = { reason -> scope.launch { runCatching { session.report(ct.uin, reason) } }; reportTarget = null },
            onDismiss = { reportTarget = null },
        )
    }
    // "Clear conversation": local, irreversible, says so.
    clearPeerTarget?.let { ct ->
        // Saved messages reach this prompt too (the thread with your own uin),
        // and the 1:1 copy warns about a contact who is not involved. Same
        // three-way split as ChatScreen — see #413.
        //
        // `host == null` is load-bearing, not defensive noise: islands number
        // independently, so a cross-island contact can legitimately carry YOUR
        // uin. Comparing the number alone would tell someone deleting a real
        // conversation that "nothing here was sent to anyone else" — the exact
        // lie this fix exists to remove, just pointed the other way.
        val clearingSelf = ct.uin == session.uin && ct.host == null
        RcqAskSheet(
            onDismiss = { clearPeerTarget = null },
            title = stringResource(if (clearingSelf) R.string.home_clear_chat_self else R.string.home_clear_chat),
            body = if (clearingSelf) {
                stringResource(R.string.home_clear_chat_body_self)
            } else {
                stringResource(R.string.home_clear_chat_body, ct.nickname)
            },
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.home_clear_chat_confirm),
                    destructive = true,
                    onClick = { session.clearPeerThread(ct.uin); clearPeerTarget = null },
                ),
            ),
        )
    }
    clearGroupTarget?.let { g ->
        RcqAskSheet(
            onDismiss = { clearGroupTarget = null },
            title = stringResource(R.string.home_clear_chat),
            body = stringResource(R.string.home_clear_chat_body_group, g.name),
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.home_clear_chat_confirm),
                    destructive = true,
                    onClick = { session.clearGroupThread(g.id); clearGroupTarget = null },
                ),
            ),
        )
    }
    // Removing a contact asks what to do with the messages instead of guessing.
    // Shared with the profile screen so the same action cannot ask two different
    // questions depending on where it was started from.
    removeTarget?.let { ct ->
        RemoveContactDialog(
            nickname = ct.nickname,
            onDismiss = { removeTarget = null },
            onRemove = { alsoDelete ->
                scope.launch { session.removeContact(ct.uin, alsoDeleteMessages = alsoDelete) }
                removeTarget = null
            },
        )
    }
    comingSoon?.let { feature ->
        // Nothing to decide here, so OK IS the way out: it becomes the sheet's
        // own dismissal row instead of a second row that closes the same thing.
        RcqAskSheet(
            onDismiss = { comingSoon = null },
            title = feature,
            body = stringResource(R.string.home_coming_soon_body, feature),
            actions = emptyList(),
            cancelLabel = stringResource(R.string.common_ok),
        )
    }
    // Confirm + caption/anonymous before posting a picked photo as a story.
    pendingStory?.let { jpeg ->
        var caption by remember { mutableStateOf("") }
        var anon by remember { mutableStateOf(false) }
        RcqSheet(onDismiss = { pendingStory = null }, title = stringResource(R.string.story_post_title)) {
            RcqField(
                value = caption,
                onValueChange = { caption = it.take(280) },
                placeholder = stringResource(R.string.story_caption_hint),
                // A caption wraps: it was never a single-line field.
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            SheetGap(8)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { anon = !anon },
            ) {
                Checkbox(checked = anon, onCheckedChange = { anon = it })
                Text(stringResource(R.string.story_anonymous_post), color = c.textPrimary, fontSize = 14.sp)
            }
            SheetGap()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { pendingStory = null }) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
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
            }
        }
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

/** Saved Messages in the chat list. Same shape as a contact row so it does not
 *  read as a special banner, with a bookmark instead of an avatar. */
@Composable
private fun SavedRow(count: Int, unread: Int, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.bgSecondary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Bookmark, null, tint = c.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_menu_saved), color = c.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.saved_notes, count, count),
                color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (unread > 0) UnreadBadge(unread)
    }
}

private fun contactActions(
    contact: Contact,
    session: Session,
    scope: CoroutineScope,
    context: android.content.Context,
    onOpenChat: (Int) -> Unit,
    onReport: (Contact) -> Unit,
    onClearThread: (Contact) -> Unit,
    onRemove: (Contact) -> Unit,
): List<ContextAction> {
    val thread = LocalStores.peerThread(contact.uin)
    val fav = LocalStores.isFavorite(thread)
    val muted = LocalStores.isMuted(thread)
    val archived = LocalStores.isArchived(thread)
    val locked = LocalStores.isLocked(thread)
    fun s(id: Int) = context.getString(id)
    return listOfNotNull(
        ContextAction(s(R.string.home_send_message), Icons.AutoMirrored.Filled.Chat) { onOpenChat(contact.uin) },
        ContextAction(s(if (fav) R.string.home_remove_fav else R.string.home_add_fav), if (fav) Icons.Filled.Star else Icons.Filled.StarBorder) { LocalStores.toggleFavorite(thread) },
        ContextAction(s(if (muted) R.string.home_unmute else R.string.home_mute), if (muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff) { LocalStores.toggleMute(thread) },
        ContextAction(s(if (archived) R.string.home_unarchive else R.string.home_archive), if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive) { LocalStores.toggleArchive(thread) },
        // Per-chat PIN lock — only offered when an app PIN is set.
        if (PanicPinService.isConfigured(context))
            ContextAction(s(if (locked) R.string.home_unlock_chat else R.string.home_lock_chat), if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock) { LocalStores.toggleLocked(thread) }
        else null,
        ContextAction(s(if (contact.blocked) R.string.home_unblock else R.string.home_block), if (contact.blocked) Icons.Outlined.Block else Icons.Filled.Block, destructive = !contact.blocked) { scope.launch { session.toggleBlock(contact.uin) } },
        ContextAction(s(R.string.home_report), Icons.Filled.Flag, destructive = true) { onReport(contact) },
        // Clearing the conversation and removing the person are separate on
        // purpose: vss found that "remove" looked like it deleted the chat and
        // did not, so re-adding brought every message back. Now one action does
        // each, and remove ASKS before it also erases.
        ContextAction(s(R.string.home_clear_chat), Icons.Filled.DeleteSweep, destructive = true) { onClearThread(contact) },
        ContextAction(s(R.string.home_remove), Icons.Filled.PersonRemove, destructive = true) { onRemove(contact) },
    )
}

private fun groupActions(
    group: RcqGroup,
    ownUin: Int,
    session: Session,
    scope: CoroutineScope,
    context: android.content.Context,
    onOpenGroup: (Int) -> Unit,
    onClearThread: (RcqGroup) -> Unit,
): List<ContextAction> {
    val thread = LocalStores.groupThread(group.id)
    val fav = LocalStores.isFavorite(thread)
    val muted = LocalStores.isMuted(thread)
    val archived = LocalStores.isArchived(thread)
    val locked = LocalStores.isLocked(thread)
    val isOwner = group.ownerUin == ownUin
    fun s(id: Int) = context.getString(id)
    return listOfNotNull(
        ContextAction(s(R.string.home_open_chat), Icons.AutoMirrored.Filled.Chat) { onOpenGroup(group.id) },
        ContextAction(s(if (fav) R.string.home_remove_fav else R.string.home_add_fav), if (fav) Icons.Filled.Star else Icons.Filled.StarBorder) { LocalStores.toggleFavorite(thread) },
        ContextAction(s(if (muted) R.string.home_unmute else R.string.home_mute), if (muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff) { LocalStores.toggleMute(thread) },
        ContextAction(s(if (archived) R.string.home_unarchive else R.string.home_archive), if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive) { LocalStores.toggleArchive(thread) },
        if (PanicPinService.isConfigured(context))
            ContextAction(s(if (locked) R.string.home_unlock_chat else R.string.home_lock_chat), if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock) { LocalStores.toggleLocked(thread) }
        else null,
        // Wipe the local copy of a group conversation without leaving it.
        ContextAction(s(R.string.home_clear_chat), Icons.Filled.DeleteSweep, destructive = true) { onClearThread(group) },
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
 *  "coming soon" sheet. */
@Composable
private fun HomeHeader(
    /** Foregrounds for everything drawn DIRECTLY on the wallpaper. Equal to
     *  [RcqTheme.colors] when no home wallpaper is set; the dark or light
     *  variant, whichever the top of the wallpaper can be read against,
     *  when there is one. Menus and sheets are NOT chrome — they have their
     *  own Material surface under them and keep using the theme. */
    chrome: RcqColors,
    session: Session,
    nickname: String,
    uin: Int,
    serverHost: String,
    ownStatus: UserStatus,
    connected: Boolean,
    stealthActive: Boolean,
    routeVerified: Boolean,
    bypassManual: Boolean,
    onUpdateBadge: (app.rcq.android.net.UpdateChecker.Update) -> Unit,
    accounts: List<AccountRow>,
    canAddAccount: Boolean,
    onPickStatus: (UserStatus) -> Unit,
    onAddContact: () -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenNews: () -> Unit,
    newsUnread: Int,
    onOpenOutgoing: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenAudioRooms: () -> Unit,
    onOpenRadio: () -> Unit,
    onPostStory: () -> Unit,
    showPostStory: Boolean = true,
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
        // OK is the only way out of an explanation, so it is the sheet's own
        // dismissal row rather than an extra one next to it.
        RcqAskSheet(
            onDismiss = { showPresenceInfo = false },
            title = stringResource(R.string.presence_info_title),
            body = stringResource(R.string.presence_info_body),
            actions = emptyList(),
            cancelLabel = stringResource(R.string.common_ok),
        )
    }
    if (showStealthInfo) {
        // Two paragraphs with their own colours, so the bare sheet rather than
        // the ask-sheet's single body line.
        RcqSheet(onDismiss = { showStealthInfo = false }, title = stringResource(R.string.stealth_info_title)) {
            // Two questions from vss, answered in the one place he taps to
            // ask them. WHY it is on: the old text always said "the network
            // looked blocked, it turns itself on", which reads as nonsense
            // to somebody who just switched it on from the menu. And WHAT
            // THE COLOUR MEANS: green vs amber is a real distinction (is
            // traffic confirmed to arrive?) that nothing on screen stated.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        if (bypassManual) R.string.stealth_info_body_manual
                        else R.string.stealth_info_body,
                    ),
                    color = c.textSecondary,
                )
                Text(
                    stringResource(
                        if (routeVerified) R.string.stealth_shield_green
                        else R.string.stealth_shield_amber,
                    ),
                    color = if (routeVerified) c.accent else c.statusAway,
                )
            }
            SheetGap()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showStealthInfo = false }) {
                    Text(stringResource(R.string.common_ok), color = c.accent)
                }
            }
        }
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
        // Left — account switcher: tap a row to hot-swap identities, or
        // add / manage local accounts (iOS AccountManager parity).
        Box(Modifier.align(Alignment.CenterStart)) {
            Icon(
                // Black (textPrimary), not accent green, per founder — but the
                // theme's black only while the theme is what it stands on.
                Icons.Outlined.AccountCircle, "Accounts", tint = chrome.textPrimary,
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
                // The flower must not claim ONLINE while the socket is down (report:
                // all network toggles off -> flower green but no connectivity). Show
                // OFFLINE when not connected; the status-picker menu below stays bound
                // to the real ownStatus so the user's choice isn't lost.
                val effectiveStatus = if (connected) ownStatus else UserStatus.OFFLINE
                // Own picture in the header, with the status still on it and
                // still opening the status menu when tapped.
                val ownAv by session.ownAvatar.collectAsState()
                PersonAvatar(
                    ownAv?.first, ownAv?.second, effectiveStatus, session, 30.dp,
                    onStatusClick = { statusMenu = true },
                )
                // Connection indicator (#16): green = socket up, amber =
                // connecting. Drawn ONLY when there is no picture. With a
                // picture the status badge already carries this: it is fed
                // `effectiveStatus`, which goes OFFLINE the moment the socket
                // does, so a second dot on the same small circle says the same
                // thing twice and crowds the corner.
                if (ownAv == null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(chrome.bgPrimary)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(if (connected) c.statusOnline else c.statusAway),
                    )
                }
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
                Text(nickname, color = chrome.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)), modifier = Modifier.widthIn(max = 150.dp))
                Text("#$uin", color = chrome.textMono, fontSize = 12.sp, lineHeight = 12.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
            }
            // Right of the nick/UIN: a status-width slot holding the stealth
            // shield when the censorship bypass is engaged (iOS StealthHeaderBadge
            // parity). The 30dp slot balances the leading status icon so the
            // nick/UIN stays dead-centred.
            // Next to the flower and the shield, exactly where founder asked
            // for it (#520): an update the app has already found, waiting for a
            // moment that suits the user. It never interrupts — the dialog is
            // still at most once per launch — and it disappears the moment the
            // new build is installed, because the manifest stops offering it.
            val pendingUpdate by app.rcq.android.net.UpdateChecker.pending.collectAsState()
            // ⚠ Inside the SAME 30dp slot as the shield, not next to it: that
            // slot exists to balance the status icon on the other side so the
            // nick and UIN stay dead-centred, and a second one shifted the
            // whole block 18dp off centre whenever an update was pending.
            // Two marks in one slot is fine — the bypass shield and a pending
            // update are rarely both true, and when they are the update yields.
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                val up = pendingUpdate
                if (up != null && !stealthActive) {
                    Icon(
                        Icons.Filled.FileDownload,
                        stringResource(R.string.update_available_badge, up.versionName),
                        tint = chrome.accent,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { onUpdateBadge(up) },
                    )
                }
                if (stealthActive) {
                    // Honest shield: solid accent only when the route is VERIFIED to
                    // reach the backend; amber when the bypass is engaged but not yet
                    // (or no longer) carrying traffic — so it can't claim a working
                    // bypass when the chain is dead ("щит есть, связи нет").
                    Icon(
                        Icons.Filled.Shield,
                        stringResource(R.string.stealth_info_title),
                        tint = if (routeVerified) chrome.accent else c.statusAway,
                        modifier = Modifier.size(22.dp).clip(CircleShape).clickable { showStealthInfo = true },
                    )
                }
            }
        }

        // Right — overflow menu.
        Box(Modifier.align(Alignment.CenterEnd)) {
            Icon(
                Icons.Filled.MoreVert, "Menu", tint = chrome.textPrimary,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable { overflowMenu = true },
            )
            // Unread news. Sits on the corner of the glyph so the ellipsis
            // stays legible and the dot reads as a status badge, same as iOS.
            // Without it the only way to learn a post exists was to open the
            // menu and then the screen, on the off chance.
            if (newsUnread > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(chrome.bgPrimary)
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE5484D)),
                )
            }
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
                if (showPostStory) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_menu_post_story), color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Filled.AddAPhoto, null, tint = c.accent) },
                        onClick = { overflowMenu = false; onPostStory() },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            if (newsUnread > 0) {
                                stringResource(R.string.home_menu_news) + "  •  " + newsUnread
                            } else {
                                stringResource(R.string.home_menu_news)
                            },
                            color = c.textPrimary,
                        )
                    },
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
    session: Session,
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
            ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, session = session, onClick = { onOpenChat(ct.uin) }, onLongPress = { onLongPress(ct) })
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
                pluralStringResource(R.plurals.members, group.memberCount, group.memberCount) +
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
private fun ContactRowItem(contact: Contact, unread: Int, session: Session, onClick: () -> Unit, onLongPress: () -> Unit) {
    val aliases by LocalStores.aliases.collectAsState()
    // My own name for this person wins over the nickname they chose. Device-only
    // (see LocalStores.aliases) — a rename says more about the relationship than
    // the contact row does, and the island has no business holding it.
    val shownName = aliases[LocalStores.aliasKey(contact.uin, contact.host)] ?: contact.nickname
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
            // A picture when the contact has one, the status flower otherwise.
            // Cross-island rows used to keep the glyph unconditionally, because
            // the blob did not cross islands. §5e crosses it: the peer DEPOSITS
            // their encrypted picture into our island and hands us the key in a
            // sealed envelope, so there is a real picture to draw and it is
            // fetched from our own island like any other. Presence still does
            // not cross — that is what `crossIsland` keeps marking.
            PersonAvatar(
                contact.avatarMediaId, contact.avatarMediaKey,
                contact.presence, session, 28.dp, crossIsland = contact.host != null,
            )
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    shownName,
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
private fun PendingRow(name: String, fromUin: Int, onOpenProfile: (Int) -> Unit, onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgPrimary).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PersonAdd, null, tint = c.accent, modifier = Modifier.size(24.dp))
        }
        // Tap the name to see who's adding you, before deciding (iOS parity).
        Text(name, color = c.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f).clickable { onOpenProfile(fromUin) }.padding(vertical = 4.dp))
        // Icons, not words. "Принять"/"Отклонить" next to a name that is
        // itself as long as a name gets left both of them squeezed, and the
        // cross-island row below had it worse with "Заблокировать" (founder,
        // with a screenshot; #586). A tick and a cross are the two glyphs
        // nobody needs a language for; the words survive as the labels a
        // screen reader speaks.
        RequestAction(Icons.Filled.Check, stringResource(R.string.home_accept), c.accent, onAccept)
        RequestAction(Icons.Filled.Close, stringResource(R.string.home_decline), c.textSecondary, onDecline)
    }
}

/// One glyph-sized action on a request row: 40dp of tappable area around a
/// 22dp icon, which is the floor for something you press by mistake at your
/// peril.
@Composable
private fun RequestAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        label,
        tint = tint,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(9.dp).size(22.dp),
    )
}

@Composable
private fun CiPendingRow(
    tag: String,
    preview: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onBlock: () -> Unit,
) {
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
        // Three, because two were not enough: accept or block left no way to
        // say "not now" without silencing a stranger permanently (#586). The
        // middle one just drops the request — they can write again.
        RequestAction(Icons.Filled.Check, stringResource(R.string.home_accept), c.accent, onAccept)
        RequestAction(Icons.Filled.Close, stringResource(R.string.home_decline), c.textSecondary, onDismiss)
        RequestAction(Icons.Filled.Block, stringResource(R.string.ci_block), c.statusBusy, onBlock)
    }
}

/// Shown once, after an update that took the full-screen-intent grant with it.
/// Same shape as the push nudge so it reads as the same kind of message.
@Composable
private fun FullScreenIntentBanner(onFix: () -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.bgSecondary).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.fsi_lost_title),
                color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.fsi_lost_body), color = c.textSecondary, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.push_nudge_dismiss), color = c.textSecondary)
            }
            TextButton(onClick = onFix) {
                Text(stringResource(R.string.fsi_lost_fix), color = c.accent)
            }
        }
    }
}

@Composable
private fun PushNudgeBanner(onSetup: () -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.bgSecondary).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.push_nudge_title),
                color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.push_nudge_body), color = c.textSecondary, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.push_nudge_dismiss), color = c.textSecondary)
            }
            TextButton(onClick = onSetup) {
                Text(stringResource(R.string.push_nudge_setup), color = c.accent)
            }
        }
    }
}

/// Shown to someone who has groups but not a single contact of their own —
/// the majority state of this user base. Deliberately a quiet row rather than a
/// full-screen takeover: it sits above a chat list that is not empty.
@Composable
private fun InviteNudge(myUin: Int, onAdd: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.home_no_contacts_title),
            color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.home_no_contacts_body),
            color = c.textSecondary, fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(
                stringResource(R.string.home_empty_invite),
                color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable { app.rcq.android.net.UpdateChecker.shareInvite(context, myUin) }
                    .padding(vertical = 4.dp),
            )
            Text(
                stringResource(R.string.home_empty_cta),
                color = c.textSecondary, fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onAdd).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, myUin: Int) {
    // Fills the list area with nothing but the wallpaper behind it, so it takes
    // the wallpaper's foregrounds and not the theme's, same as the header (#554).
    val c = homeChrome()
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusIcon(UserStatus.ONLINE, size = 44.dp)
        Text(stringResource(R.string.home_empty_title), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.home_empty_body), color = c.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        CapsuleButton(stringResource(R.string.home_empty_cta), onClick = onAdd)
        // The screen three quarters of accounts never leave. "Add a contact"
        // asks for a UIN, which assumes the other person already has RCQ and is
        // standing next to you — so for anyone whose friends are not here yet,
        // this screen was a dead end with a button that could not help.
        Text(
            stringResource(R.string.home_empty_invite),
            color = c.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable { app.rcq.android.net.UpdateChecker.shareInvite(context, myUin) }
                .padding(vertical = 6.dp, horizontal = 12.dp),
        )
    }
}

/** Shown on the home list while the first connect/sync is still in flight, so we
 *  don't claim "no contacts" before the roster has had a chance to load
 *  (tester #4/#9/#13). The petal loader is the branded busy indicator. */
@Composable
private fun ConnectingState(stealth: Boolean = false) {
    // Also drawn straight on the wallpaper — see [EmptyState].
    val c = homeChrome()
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
private fun BottomBar(onAdd: () -> Unit, onQr: () -> Unit, onRandom: () -> Unit, onNearby: () -> Unit, onSettings: () -> Unit, showRandom: Boolean = true, showNearby: Boolean = true) {
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
        if (showNearby) BarButton(Icons.Filled.NearMe, stringResource(R.string.home_bar_nearby), onNearby)
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
        // One line, always. Five labels share the width, and the longest of
        // them ("Настройки") wrapped onto a second line on a device with the
        // system font scaled up — the tab then stood a row taller than its
        // neighbours (vss). The cap lets the label grow with the user's font
        // setting up to a point and no further; the icon above it carries the
        // meaning anyway, and the alternative is a bar that reflows.
        Text(
            label,
            color = c.textPrimary,
            fontSize = cappedSp(9f, 1.15f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** [base] sp, but scaled by the system font setting only up to [maxScale].
 *  Returns plain sp so the text still respects accessibility below the cap. */
@Composable
private fun cappedSp(base: Float, maxScale: Float): androidx.compose.ui.unit.TextUnit {
    val scale = LocalDensity.current.fontScale
    if (scale <= maxScale) return base.sp
    return (base * maxScale / scale).sp
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
        // Search my own name for them too: renaming someone and then not
        // finding them by that name is the obvious next complaint.
        else contacts.filter {
            it.nickname.contains(query, true) ||
                (LocalStores.aliasFor(it.uin, it.host)?.contains(query, true) == true) ||
                it.uin.toString().contains(query)
        }
    }
    Column(Modifier.fillMaxSize().background(c.bgPrimary).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RcqField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = "Search contacts",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddContactDialog(
    session: Session,
    contacts: List<Contact>,
    onAddUin: (Int) -> Unit,
    onOpenChat: (Int) -> Unit,
    onOpenProfile: (Int) -> Unit,
    onOpenProfileHere: (Int) -> Unit,
    onOpenGroup: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Hoisted out of composition so a trip to a search result's profile and
    // back does not wipe what was typed (see AddSheet).
    var query by AddSheet.query
    var users by remember { mutableStateOf<List<RcqApi.UserInfo>>(emptyList()) }
    var groups by remember { mutableStateOf<List<RcqApi.GroupPreviewOut>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // Optional access token for adding a contact on a foreign PRIVATE (closed)
    // island — shown only when a uin@host on another island is detected.
    var ciToken by remember { mutableStateOf("") }
    // #589: "нажал на группу в поиске и сразу вступил без спроса" — a search hit
    // used to join on the tap. The tap now only picks the group; joining waits
    // for the confirm sheet below.
    var joinTarget by remember { mutableStateOf<RcqApi.GroupPreviewOut?>(null) }

    // Scan a contact QR right here. The https form of the link is an App Link
    // now, so a stock camera usually works too — but "usually" depends on the
    // vendor's camera app (Samsung and MIUI regularly refuse), and this is the
    // whole friend-to-friend path, so it should not rest on that. Routing goes
    // through the same pending-link objects a deep link uses, so a scanned code
    // gets the identical confirm + cross-island handling.
    val scanLauncher = rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result ->
        val text = result.contents?.trim().orEmpty()
        if (text.isEmpty()) return@rememberLauncherForActivityResult
        val uri = runCatching { android.net.Uri.parse(text) }.getOrNull()
        val contact = app.rcq.android.ContactAddLink.fromUri(uri)
        val group = GroupLinkParser.parse(text)
        when {
            contact != null -> { app.rcq.android.ContactAddLink.pending.value = contact; onDismiss() }
            group != null -> { query = text }          // the dialog already previews a group link
            text.toIntOrNull()?.let { it > 0 } == true -> query = text
            // Anything else (a web-link QR, a random code): put it in the field
            // rather than swallowing it, so the user sees what was scanned.
            else -> query = text
        }
    }
    fun launchContactScan() {
        scanLauncher.launch(
            com.journeyapps.barcodescanner.ScanOptions().apply {
                setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                setBeepEnabled(false)
                setOrientationLocked(false)
                setPrompt(context.getString(R.string.add_scan_prompt))
            },
        )
    }

    // Debounced server-side search of people AND joinable groups (iOS Add
    // overlay parity — the old dialog only accepted a raw UIN). A pasted group
    // link is handled separately (below), so don't waste a search on it.
    LaunchedEffect(query) {
        val q = query.trim()
        // One character is a real query: "а если у потенциального друга имя из
        // одной буквы?" (#518). The island takes min_length=1 and caps the
        // result set, and the 300 ms debounce below is what actually protects
        // it — the two-character floor only protected us from names we told
        // people they could have.
        if (q.isEmpty() || GroupLinkParser.parse(q) != null) { users = emptyList(); groups = emptyList(); searching = false; return@LaunchedEffect }
        // `#911` means THAT number and nothing else. Plain `911` still runs the
        // fuzzy search, which is what you want when you half-remember a number
        // or are looking for a name — the two intents needed separate syntax
        // (user report: searching a known UIN buried it under everything that
        // merely contained those digits).
        val exact = if (q.startsWith("#")) q.drop(1).trim().toIntOrNull()?.takeIf { it > 0 } else null
        if (exact != null) {
            searching = true
            delay(250)
            users = listOfNotNull(session.lookupUin(exact)).filter { it.uin != session.uin }
            groups = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        users = session.searchUsers(q).filter { it.uin != session.uin }
        // Don't surface CLOSED groups in open search — they're not joinable this
        // way (join only via invite link); iOS already hides them (#11).
        groups = session.searchGroups(q).filter { !it.is_closed }
        searching = false
    }

    // A sheet, not a centred dialog: Add is a search surface with a keyboard,
    // and a box floating in the middle of the screen fights the IME for space
    // (iOS has always had this as a sheet).
    //
    // ⚠ The content declares FULL height on purpose (#524). A ModalBottomSheet
    // can only rise as high as its content measures, so wrap-height content with
    // a capped result list left the sheet stuck two thirds down the screen no
    // matter how hard you dragged it — the drag simply had nowhere to go. It
    // still OPENS half-way (the default sheet state keeps its partial anchor);
    // only the ceiling changed.
    //
    // ⚠⚠ DO NOT "finish the job" by giving this sheet the treatment the other
    // typing sheets got (`rememberRcqSheetState()` + `rcqSheetInsets` + an outer
    // `verticalScroll`, see Sheets.kt). It is excluded on purpose, and the outer
    // scroll in particular would not merely change the look, it would EMPTY the
    // sheet: a `verticalScroll` measures its child with an INFINITE height, and
    // both `fillMaxHeight()` on the Column below and `weight(1f)` on the result
    // list resolve against the incoming max height. With that max unbounded,
    // `fillMaxHeight` becomes a no-op and every weighted child collapses to zero,
    // so the search field and all results would render at nothing tall. This
    // layout is height-driven by design (#524); it cannot live inside a scroller.
    //
    // `skipPartiallyExpanded` is separately unwanted here: the half-open opening
    // position IS the #524 decision, not an accident of the anchor.
    //
    // ⏭ What this sheet still does NOT do is move for the keyboard, because a
    // ModalBottomSheet is its own SOFT_INPUT_ADJUST_NOTHING window (Sheets.kt
    // explains the mechanism). The one change that would be safe on its own is
    // `contentWindowInsets = rcqSheetInsets`, which only shrinks the height the
    // content resolves against and so keeps `fillMaxHeight`/`weight` bounded and
    // keeps the partial anchor. Left undone deliberately: it changes a surface
    // governed by a founder decision and nobody has looked at it on a device.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Text(
                stringResource(R.string.add_title),
                color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Column(Modifier.fillMaxWidth().weight(1f)) {
                RcqField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.add_search_hint),
                    singleLine = true,
                    trailingIcon = {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            stringResource(R.string.add_scan),
                            tint = c.accent,
                            modifier = Modifier.size(24.dp).clickable { launchContactScan() },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // A bare number, OR the full address of OUR OWN island written
                // out. `134@is2.rcq.app` typed on is2 used to find nothing at
                // all: the cross-island row deliberately skips our own host,
                // and nothing picked the number back up (report #433, with a
                // screenshot of the empty result). Spelling out where you
                // already are is not an error, it is the same request as `134`.
                val digits = remember(query, session.currentServer) {
                    val t = query.trim()
                    t.toIntOrNull() ?: runCatching { RcqFederation.parseAddress(t) }.getOrNull()
                        ?.takeIf { it.host.equals(session.currentServer, true) }?.uin
                }
                // A pasted GROUP invite link (https://rcq.app/g/<id>@<host> or
                // rcq://group/<id>) → JOIN it, including a group on ANOTHER island.
                // This is the entry point for a user handed a link who isn't in any
                // shared chat (the only other joinable place was GroupLinkBubble).
                val groupRef = remember(query) { GroupLinkParser.parse(query.trim()) }
                // weight, not a fixed cap: the results take whatever the sheet
                // has left, which is what lets a long list drag the sheet to
                // the top instead of scrolling inside a 320dp window.
                Box(Modifier.weight(1f).padding(top = 8.dp)) {
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
                        // ...but never offer to add YOURSELF: searching "134" from
                        // account #134 listed "#134 · send a request" (user report
                        // with a screenshot). Own number = the Favourites chat,
                        // which lives on the home screen already.
                        // ⚠⚠ Ask the island whether the number is held by anyone
                        // before offering it. This row is drawn by the client, not
                        // returned by a search, and its condition — "the search did
                        // not find this number" — is true precisely when NOBODY has
                        // it. So typing a free number produced a card that looked
                        // like a person: it opened a profile, and it offered to send
                        // a contact request that the island answered 404 (#483).
                        //
                        // The `#238` form next door already resolves through
                        // lookupUin; only the bare-digits branch skipped it.
                        //
                        // ⚠ Absent ONLY on a definite 404. A network failure leaves
                        // the row up, because taking add-by-number away from someone
                        // whose island is unreachable would be a worse bug than the
                        // one being fixed, and because a private account answers 200
                        // here anyway — so the privacy-gated case this row exists for
                        // survives untouched.
                        // ⚠ Bounded, not "however long OkHttp waits". Making the
                        // row wait for an answer (below) is what stops it
                        // flashing for a free number, but on an unreachable
                        // island that answer is a 30-second connect timeout,
                        // and add-by-number would simply be missing for half a
                        // minute. Half a second is far longer than the island
                        // ever takes to say 404 and far shorter than anyone
                        // waits before deciding a screen is broken; past it we
                        // know nothing, which is exactly `Unknown`, and Unknown
                        // still draws the row.
                        val resolved by produceState<Session.UinLookup?>(null, digits) {
                            val d = digits
                            value = null
                            if (d != null) {
                                value = withTimeoutOrNull(500) { session.lookupUinDetailed(d) }
                                    ?: Session.UinLookup.Unknown
                            }
                        }
                        // ⚠ `resolved != null` — wait for the island's answer
                        // before drawing anything. While the lookup was in
                        // flight the state is null, which is neither Found nor
                        // Absent, so a free number got its row for the fraction
                        // of a second the request took and then lost it: "если
                        // номер свободный, он там отображается на доли секунды
                        // и исчезает, всё дёргается из-за этого" (#518). The
                        // row is worth appearing a beat late; it is not worth
                        // appearing wrong. Unknown (no answer) still shows it,
                        // per the note above.
                        if (digits != null && digits != session.uin && users.none { it.uin == digits } &&
                            resolved != null && resolved !is Session.UinLookup.Absent
                        ) {
                            // Say WHICH island a bare number reaches — a user on
                            // is2 typing an api number must see the mismatch
                            // (beta report: the request "never arrived").
                            val known = (resolved as? Session.UinLookup.Found)?.info
                            AddResultRow(known?.nickname?.takeIf { it.isNotBlank() } ?: "#$digits", stringResource(R.string.add_on_own_island, session.currentServer), accent = true) {
                                // Open the profile first so you can preview before
                                // sending the request (the profile has the button).
                                // ⚠ Pinned to our island: the row promises "on
                                // is2.rcq.app", and it used to open a contact of
                                // the same NUMBER from api instead, so the
                                // request went to a different person on a
                                // different island (#433, and #429 is the same
                                // defect seen from the other end).
                                onOpenProfileHere(digits)
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
                                // Optional access token for a foreign PRIVATE island.
                                RcqField(
                                    value = ciToken,
                                    onValueChange = { ciToken = it.trim() },
                                    placeholder = stringResource(R.string.access_token_label),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                )
                                AddResultRow("${ci.uin}@${ci.host}", stringResource(R.string.add_ci_row), accent = true) {
                                    scope.launch {
                                        // Redeem the access token for the host FIRST (stores the
                                        // durable token so fetchCard/deposit pass the gate). A bad
                                        // token aborts with a toast so the user can fix it.
                                        if (ciToken.isNotBlank()) {
                                            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                app.rcq.android.net.AccessRedeemer.redeem(context, ci.host, ciToken)
                                            }
                                            if (res is app.rcq.android.net.RedeemResult.BadToken) {
                                                android.widget.Toast.makeText(context, context.getString(R.string.access_token_bad), android.widget.Toast.LENGTH_LONG).show()
                                                return@launch
                                            }
                                        }
                                        // Same number, different island, both in
                                        // one roster: the conversation store still
                                        // keys a thread by the bare number, so the
                                        // two would share one history. Say so
                                        // instead of adding.
                                        if (session.clashesWithKnownNumber(ci.uin, ci.host)) {
                                            android.widget.Toast.makeText(context, context.getString(R.string.add_ci_number_clash, ci.uin), android.widget.Toast.LENGTH_LONG).show()
                                            return@launch
                                        }
                                        // §5f: adding `uin@host` deposits a contact
                                        // request to their island. Open the chat
                                        // either way (the row is ours), but do not
                                        // stay silent when the request never left.
                                        when (session.addCrossIslandContactDetailed(ci.uin, ci.host)) {
                                            Session.CiAdd.SENT -> onOpenChat(ci.uin)
                                            Session.CiAdd.ADDED_ONLY -> {
                                                android.widget.Toast.makeText(context, context.getString(R.string.ci_request_not_delivered), android.widget.Toast.LENGTH_LONG).show()
                                                onOpenChat(ci.uin)
                                            }
                                            Session.CiAdd.FAILED ->
                                                android.widget.Toast.makeText(context, context.getString(R.string.ci_request_failed), android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                        users.forEach { u ->
                            val already = contacts.any { it.uin == u.uin }
                            // Number first, name under it (#525). You search this
                            // window BY number far more often than by name, and the
                            // thing you are checking against what you typed should be
                            // the line your eye lands on.
                            val name = u.nickname?.trim().orEmpty()
                            // ⚠ Two hits CAN carry the same nickname, and it is not
                            // a bug in the search: nicknames are not unique (no
                            // constraint on `users.nickname`), and every client
                            // names a fresh account `user-` + four random digits —
                            // 9000 possibilities, so a repeat is expected long
                            // before a hundred accounts exist. A user typed a
                            // nickname and got two rows reading "user-5835" (#2019
                            // and #32164535) and reasonably read that as the same
                            // person listed twice.
                            //
                            // The number already separates them, but only if you
                            // read it. The real name does it at a glance, and the
                            // island has been sending it all along (public profiles
                            // only) — the row simply dropped it.
                            val realName = listOfNotNull(
                                u.first_name?.trim()?.takeIf { it.isNotEmpty() },
                                u.last_name?.trim()?.takeIf { it.isNotEmpty() },
                            ).joinToString(" ")
                            val state = when {
                                already -> stringResource(R.string.add_already_contact)
                                u.uin in sentTo -> stringResource(R.string.add_request_sent)
                                else -> ""
                            }
                            val sub = listOf(name, realName, state)
                                .filter { it.isNotEmpty() }
                                .distinct()
                                .joinToString(" · ")
                            AddResultRow("#${u.uin}", sub) {
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
                                joinTarget = g
                            }
                        }
                        if (searching) {
                            Text(stringResource(R.string.add_searching), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                            // `ci != null` matters: a `uin@host` query renders its own
                            // card above, and printing "nothing found" under it read
                            // as a contradiction (user report with a screenshot of
                            // both on screen at once).
                        } else if (query.trim().length >= 2 && users.isEmpty() && groups.isEmpty() && digits == null && groupRef == null && ci == null) {
                            Text(stringResource(R.string.add_no_results), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        } else if (query.isEmpty()) {
                            Text(stringResource(R.string.add_search_prompt), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }

    // #589: joining is a membership change other people can see, so it gets a
    // confirm. Sits OUTSIDE the sheet above (a sibling, not nested in its
    // content) so it opens as its own window on top of the search instead of
    // scrolling somewhere inside it. Failures now say so, too — the old one-tap
    // join went silent when the island refused.
    joinTarget?.let { g ->
        GroupJoinConfirmSheet(
            preview = g,
            session = session,
            onDismiss = { joinTarget = null },
            onJoin = {
                joinTarget = null
                scope.launch {
                    if (session.joinGroup(g.id) != null) onOpenGroup(g.id)
                    else android.widget.Toast.makeText(context, context.getString(R.string.group_invite_join_failed), android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

/** Ask before joining a group tapped in Add-window search (#589). The search
 *  hit IS a group preview (`GET /groups/{id}/preview` shape), so the question
 *  is asked next to what the group actually is — avatar, name, size, blurb —
 *  rather than as a bare "are you sure". Nothing is fetched again for it. */
@Composable
private fun GroupJoinConfirmSheet(
    preview: RcqApi.GroupPreviewOut,
    session: Session,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    RcqSheet(onDismiss = onDismiss, title = stringResource(R.string.group_join_confirm)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupAvatarMedia(preview.avatar_media_id, preview.avatar_media_key, session, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    preview.name?.takeIf { it.isNotBlank() } ?: "#${preview.id}",
                    color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.members, preview.member_count, preview.member_count),
                    color = c.textSecondary, fontSize = 12.sp,
                )
            }
        }
        val about = preview.description?.trim().orEmpty()
        if (about.isNotEmpty()) {
            SheetGap(10)
            Text(about, color = c.textSecondary, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        SheetGap(10)
        Text(stringResource(R.string.group_join_body), color = c.textSecondary, fontSize = 13.sp)
        SheetGap()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = c.textSecondary)
            }
            TextButton(onClick = onJoin) {
                Text(stringResource(R.string.group_invite_join), color = c.accent)
            }
        }
    }
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
            // A hit with no nickname has nothing for the second line; drawing it
            // empty leaves the row taller than the text in it.
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Create another anonymous identity. Server host is optional — blank uses
 *  the default public server; a custom host registers onto an org island /
 *  self-host. The new account is added alongside the current one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccountDialog(onAdd: (String?) -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    // Sheet: two text fields and a keyboard. A centred dialog gets shoved
    // around by the IME on a short screen and the token field ends up under it.
    // ⚠ A sheet is a window of its own and does not move for the keyboard by
    // itself — see [rememberRcqSheetState] / [rcqSheetInsets] in Sheets.kt for
    // why both of these are mandatory on anything that takes typing.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        sheetState = rememberRcqSheetState(),
        contentWindowInsets = rcqSheetInsets,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.home_menu_add_account),
                color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.add_account_body), color = c.textSecondary, fontSize = 13.sp)
            // Two hints, one slot: the field says what it is, and the default
            // host moves under it so leaving it blank still tells you where the
            // new account lands.
            RcqField(
                value = host,
                onValueChange = { host = it.trim() },
                placeholder = stringResource(R.string.csrv_host),
                supportingText = app.rcq.android.net.RcqApi.DEFAULT_HOST,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Optional access token for a private (closed/masquerade) island.
            RcqField(
                value = token,
                onValueChange = { token = it.trim(); err = null },
                placeholder = stringResource(R.string.access_token_label),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.access_token_hint), color = c.textSecondary, fontSize = 11.sp)
            err?.let { Text(it, color = c.statusBusy, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
                TextButton(enabled = !checking, onClick = {
                    val h = host.ifBlank { null }
                    if (h != null && token.isNotBlank()) {
                        // Redeem the access token for this host FIRST (stores the
                        // durable token so the registration call passes the gate),
                        // then proceed. A bad token blocks so the user can fix it.
                        checking = true; err = null
                        scope.launch {
                            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                app.rcq.android.net.AccessRedeemer.redeem(ctx, h, token)
                            }
                            checking = false
                            if (res is app.rcq.android.net.RedeemResult.BadToken) {
                                err = ctx.getString(R.string.access_token_bad)
                            } else {
                                onAdd(h)
                            }
                        }
                    } else {
                        onAdd(h)
                    }
                }) {
                    Text(stringResource(R.string.add_account_create), color = c.accent)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupDialog(contacts: List<Contact>, onCreate: (String, List<Int>) -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<Int, Boolean>() }
    // Sheet: a name field plus a scrolling member picker. The dialog version
    // capped the roster at 260dp inside an already-boxed centred surface, so
    // picking people out of a long contact list meant scrolling a small window
    // inside a small window.
    // ⚠ Sheet state + insets: see [rememberRcqSheetState] / [rcqSheetInsets].
    // The outer scroll is what keeps "Create" reachable once the keyboard has
    // taken half the screen; the member list keeps its own (capped) scroll, and
    // hands the gesture on when it runs out.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        sheetState = rememberRcqSheetState(),
        contentWindowInsets = rcqSheetInsets,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.home_new_group),
                color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            RcqField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.home_group_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.home_add_members), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
                val members = selected.filterValues { it }.keys.toList()
                val ok = name.isNotBlank()
                TextButton(enabled = ok, onClick = { onCreate(name.trim(), members) }) {
                    Text(stringResource(R.string.home_create), color = if (ok) c.accent else c.textSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportDialog(name: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    var reason by remember { mutableStateOf("") }
    // A sheet, not a centred box: this is a form with a keyboard, and a dialog
    // floating mid-screen fights the IME for room (same reasoning as the Add
    // sheet). The "are you sure" prompts that used to stay centred are sheets
    // too now, so nothing on this screen is a Material dialog any more.
    // ⚠ Sheet state + insets: see [rememberRcqSheetState] / [rcqSheetInsets].
    // This is a report — prose, several lines of it — so it is exactly the sheet
    // that used to sink a line at a time as the reason re-wrapped (#546).
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        sheetState = rememberRcqSheetState(),
        contentWindowInsets = rcqSheetInsets,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.home_report_title, name),
                color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            RcqField(
                value = reason,
                onValueChange = { reason = it },
                placeholder = stringResource(R.string.home_report_reason),
                // A reason is prose, so the field grows with it (the old field
                // simply started two lines tall).
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
                TextButton(enabled = reason.isNotBlank(), onClick = { onSubmit(reason.trim()) }) {
                    Text(
                        stringResource(R.string.home_report_submit),
                        color = if (reason.isNotBlank()) Color(0xFFE5484D) else c.textSecondary,
                    )
                }
            }
        }
    }
}
