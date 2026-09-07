package app.rcq.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.QrCodeScanner
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.CrossIslandRequestsStore
import app.rcq.android.security.PanicPinService
import app.rcq.android.data.LocalStores
import app.rcq.android.data.Sections
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization

/** The chat list, already sliced into its sections and sorted.
 *
 *  One object rather than nine loose values so the whole thing can hang off a
 *  single [androidx.compose.runtime.remember] in [HomeScreen]: see the comment
 *  at its call site for why recomputing these on every recomposition was worth
 *  removing. */
private data class HomeLists(
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

/** One row's worth of long-press action, mirrors iOS ContextAction. */
internal data class ContextAction(
    val title: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** A LazyColumn key for a contact row.
 *
 *  ⚠ The uin ALONE is not unique and never was. Islands number independently,
 *  so two cross-island contacts living on two different islands can both be
 *  #5, and `mergeCrossIslandContacts` only de-duplicates the foreign list
 *  against the LOCAL roster, not against itself. Two rows with the same key in
 *  one LazyColumn is not a cosmetic problem: Compose throws on the duplicate
 *  and the whole chat list goes down with it. The island is part of who the
 *  row is about, so it is part of the key. A local contact keeps an empty
 *  island, so its key is what it always was plus a trailing "@".
 */
private fun contactKey(prefix: String, contact: Contact) = "${prefix}_${contact.uin}@${contact.host ?: ""}"

/**
 * One local edit to the sections tree: patch the cache, repaint at the speed of
 * the tap, push to the island behind the paint.
 *
 * The caps throw BEFORE anything is saved, so the user is told "this section is
 * full" instead of the app writing a blob the island refuses. Deliberately a
 * top-level function: see the note at its call site in [HomeScreen].
 */
private fun applySectionEdit(
    session: Session,
    context: android.content.Context,
    defer: Boolean,
    edit: (com.google.gson.JsonObject) -> com.google.gson.JsonObject,
) {
    try {
        session.editSections(defer, edit)
    } catch (e: Sections.SectionsException) {
        val msg = when (e.code) {
            "too_many_sections" -> R.string.sections_err_too_many_sections
            "section_full" -> R.string.sections_err_section_full
            "too_many_members" -> R.string.sections_err_too_many_members
            "too_large" -> R.string.sections_err_too_large
            else -> R.string.sections_err_unreadable
        }
        android.widget.Toast.makeText(context, context.getString(msg), android.widget.Toast.LENGTH_LONG).show()
    }
}

/** A row in the account switcher: live nick/UIN peeked per local account, plus
 *  that account's cached face.
 *
 *  The picture comes from [app.rcq.android.data.AccountCards], never from the
 *  network: only ONE of these accounts has a session behind it, so asking an
 *  island for the others' profiles is not something this row can do. Same shape
 *  the desktop settled on (see the file comment on AccountCards). */
internal data class AccountRow(
    val id: String,
    val nickname: String,
    val uin: Int?,
    val host: String,
    val active: Boolean,
    val avatarMediaId: String? = null,
    val avatarMediaKey: String? = null,
    /** What that island calls itself, and which logo it is on. From
     *  [app.rcq.android.data.IslandCards], keyed by HOST rather than by
     *  account, so two accounts on one island share one entry and an island
     *  nobody is currently signed into still draws its own face. Empty when
     *  nothing on this device has ever talked to it, which draws the lettered
     *  tile: the row never waits on a network call. */
    val islandName: String = "",
    val islandLogoVersion: String = "",
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

    /** The results the sheet last showed, keyed by the query that produced
     *  them. A trip into a search hit's profile DISPOSES the sheet composable,
     *  and only the query used to survive — the return re-ran the whole search
     *  over an empty list and said "Поиск…" for a query that had not changed
     *  (#615). The list rides out here with it. */
    var resultsFor: String? = null
    var users: List<app.rcq.android.net.RcqApi.UserInfo> = emptyList()
    var groups: List<app.rcq.android.net.RcqApi.GroupPreviewOut> = emptyList()

    /** Leave the search for good: the user got where they were going (opened a
     *  chat, sent a request) or dismissed it. Backing out of a profile
     *  deliberately does NOT call this. */
    fun close() {
        open.value = false
        query.value = ""
        resultsFor = null
        users = emptyList()
        groups = emptyList()
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
    onOpenSites: () -> Unit = {},
    onSwitchAccount: (String) -> Unit = {},
    onAddAccount: (String?) -> Unit = {},
    /** Add an account from its recovery phrase. Same destination the onboarding
     *  screen and the account-management screen already reach; the add-account
     *  sheet offers it too, because "I already have an account" is the other
     *  half of the question that sheet asks (founder, 24.08, iOS parity). */
    onRestoreBySeed: () -> Unit = {},
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
    // The group threads too (same flow ChatScreen reads as groupAll): the
    // long-press preview shows a read-only window on the thread, and group
    // rows need their messages for it (L2.11).
    val groupMsgs by session.groupMessages.collectAsState()
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
    // Island trust (design §5): a host refused because its certificate
    // changed is a banner at the top of the list; a first-use pin is said
    // once, in a snackbar, and marked noticed when it goes. Both are read off
    // the trust layer's own state, so nothing here has to remember to ask.
    val trustChanged by app.rcq.android.net.IslandTrust.changed.collectAsState()
    val trustHidden by app.rcq.android.net.IslandTrust.hidden.collectAsState()
    val trustFirstUse by app.rcq.android.net.IslandTrust.firstUse.collectAsState()
    val trustNotice = remember { SnackbarHostState() }
    val pendingFirstUse = trustFirstUse.firstOrNull()
    // Held apart from the list because `noticed` empties it the moment the
    // snackbar is answered, and the fingerprint has to survive the animation
    // out; the body draws it in a fixed-width font, so it is not in the
    // message string.
    var noticeFp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingFirstUse?.key) {
        val n = pendingFirstUse ?: return@LaunchedEffect
        noticeFp = n.fp
        trustNotice.showSnackbar(
            message = context.getString(R.string.island_trust_first_use, n.hostPort),
            actionLabel = context.getString(R.string.common_ok),
            duration = SnackbarDuration.Indefinite,
        )
        // Dismissed or acknowledged: either way it was seen, never again.
        app.rcq.android.net.IslandTrust.noticed(n.key)
    }
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
    // Operator-toggleable features (admin console → Features); default true.
    val nearbyEnabled by session.nearbyEnabled.collectAsState()
    val randomEnabled by session.randomEnabled.collectAsState()

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
    // L2.11: one impact when the preview OPENS, iOS placement (ContactListView
    // fires the generator in openPreview, deliberately not on press-down).
    // Rows draw no ripple (indication = null), so this is the long-press's
    // only physical acknowledgement.
    val haptics = LocalHapticFeedback.current
    val openContactPreview: (Contact) -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        previewContact = it
    }
    val openGroupPreview: (RcqGroup) -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        previewGroup = it
    }
    var reportTarget by remember { mutableStateOf<Contact?>(null) }
    // Irreversible-on-this-device actions, each behind a confirmation.
    var clearPeerTarget by remember { mutableStateOf<Contact?>(null) }
    var clearGroupTarget by remember { mutableStateOf<RcqGroup?>(null) }
    var removeTarget by remember { mutableStateOf<Contact?>(null) }

    // Section fold state is persisted (LocalStores.sectionFlags) so a collapsed
    // section stays collapsed across leaving/re-entering home and across a cold
    // start (report: the offline section kept re-expanding because it was
    // in-memory remember{}). Device-local by design and per account, like the
    // tree it folds; iOS keeps the same thing in SectionCollapseStore and web
    // in local-store's KEYS.collapsed.
    // Set membership = "collapsed", except Archive which defaults to collapsed
    // and stores an "open" marker instead.
    // The per-section flags themselves are read once here; which key belongs to
    // which section is decided in the render loop, because the list of sections
    // is no longer a fixed six (see [Sections.orderedSections]).
    val sectionFlags by LocalStores.sectionFlags.collectAsState()
    // #593: both request headers drew the same chevron as every other section
    // and then ignored the tap ("выглядят сворачиваемыми, но не сворачиваются").
    // They fold and persist like the rest now; the count in the header keeps
    // saying how many are waiting while folded.
    val collapsedRequests = "sec:req" in sectionFlags
    val collapsedCiRequests = "sec:cireq" in sectionFlags

    // ── The user's own sections (founder item 1 of 23.08) ────────────────
    //
    // Not a seventh hardcoded bucket: they live in the account's "sections"
    // vault slot, so the same sections, in the same order, with the same chats
    // filed into them, are on the phone, the desktop and the web. The whole
    // feature is gated on `capabilities.vault`; a local-only fallback would
    // create state that syncs badly the day the island upgrades.
    val sectionsTree by LocalStores.sections.collectAsState()
    /// ⚠⚠ THREE states, not two: true, false, and **null = not answered yet**.
    /// See [Session.vaultAvailable]. Only an explicit "no vault" un-files.
    val sectionsOk by session.vaultAvailable.collectAsState()
    /// In a DECOY session no section is gated: asking for the real PIN there
    /// rejects the coercer's decoy PIN as "wrong" and thereby announces that a
    /// second PIN exists. Exactly what iOS `ContactListView.archiveLocked`
    /// already does (`if isDecoy { return false }`).
    val inDecoy = remember { PanicPinService.inDecoySession }
    /// A real PIN this device can check against. A device with none cannot
    /// honour the flag, so it does not offer to set one either (§3).
    val canPin = remember(inDecoy) { !inDecoy && PanicPinService.isConfigured(context) }
    /// Sections whose PIN has been answered, for THIS visit to the chat list.
    /// Never persisted and never in the collapse set: it resets when the
    /// section is collapsed, when the app goes to the background, and on every
    /// cold start. A gate that survives those is not a gate.
    var unlockedSections by remember { mutableStateOf(emptySet<String>()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) unlockedSections = emptySet()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var sectionMenu by remember { mutableStateOf<SectionMenuTarget?>(null) }
    var sectionRename by remember { mutableStateOf<SectionMenuTarget?>(null) }
    var sectionDelete by remember { mutableStateOf<SectionMenuTarget?>(null) }
    var sectionPicker by remember { mutableStateOf<String?>(null) }
    var sectionPinPrompt by remember { mutableStateOf<SectionMenuTarget?>(null) }
    var creatingSection by remember { mutableStateOf(false) }
    var reorderingSections by remember { mutableStateOf(false) }
    var draggingSection by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // A trust banner that arrives while the list sits at the top is inserted
    // ABOVE the first visible row, and LazyColumn keeps that row where it was,
    // so the one message that explains why the island is offline would open
    // just out of view. Bring the list up to it when a host joins the set.
    LaunchedEffect(trustChanged.keys) {
        if (trustChanged.isNotEmpty()) runCatching { listState.animateScrollToItem(0) }
    }

    /// Every local edit goes through here: it patches the cached tree, repaints
    /// at the speed of the tap, and pushes to the island behind the paint.
    /// [defer] coalesces a burst (a drag reorder, the picker sheet) into one
    /// put against the account's 240-an-hour budget.
    /// ⚠ The try/catch lives in [applySectionEdit], OUTSIDE this composable
    /// frame, and that placement is not tidiness. A `try` in the body of a
    /// large composable is how ChatScreen produced a frame ART rejects, and the
    /// app died on opening a chat. Nothing here builds an exception table.
    fun editSections(defer: Boolean = false, edit: (com.google.gson.JsonObject) -> com.google.gson.JsonObject) =
        applySectionEdit(session, context, defer, edit)

    // The whole chat list, sliced and sorted, computed ONCE per change of the
    // things it is made of.
    //
    // ⚠ These used to be plain `val`s in the body of this composable, which
    // meant five full sorts plus nine filters over the entire roster on EVERY
    // recomposition of the home screen. That is not a rare event: this body
    // reads presence, connectivity, the relay shield, the news badge, the
    // update badge, the account roster and the stealth flag, so a single
    // presence tick from one contact, or the socket blinking, re-sorted
    // everything. The founder's iOS report is about the main screen stuttering
    // while scrolling, and a burst of presence during a scroll is exactly when
    // this cost lands. Keyed now on the six inputs that can actually change the
    // result and on nothing else. (Scrolling itself never recomposes this body,
    // which is why the fix is worth having and also why it is not the whole of
    // the answer; see the notes on the row composables.)
    val lists = remember(contacts, groups, unread, messages, favorites, archived, sectionsTree, sectionsOk) {
        // Unread threads float to the top (iOS parity), then by recency.
        fun byRecency(list: List<Contact>) =
            list.sortedWith(
                compareByDescending<Contact> { (unread[LocalStores.peerThread(it.uin)] ?: 0) > 0 }
                    .thenByDescending { messages[it.uin]?.lastOrNull()?.sentAt ?: 0L },
            )
        // Inside a user section: unread first, then favorite, then the sort
        // this client already uses. Favoriting is NOT cleared when a chat is
        // filed; it just has no section of its own to render into any more, so
        // it goes on doing the only other thing it ever did.
        fun bySectionOrder(list: List<Contact>) =
            list.sortedWith(
                compareByDescending<Contact> { (unread[LocalStores.peerThread(it.uin)] ?: 0) > 0 }
                    .thenByDescending { LocalStores.peerThread(it.uin) in favorites }
                    .thenByDescending { messages[it.uin]?.lastOrNull()?.sentAt ?: 0L },
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
        HomeLists(
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
    val favContacts = lists.fav
    val crossIslandContacts = lists.crossIsland
    val onlineContacts = lists.online
    val offlineContacts = lists.offline
    val archivedContacts = lists.archivedContacts
    val visibleGroups = lists.visibleGroups
    val archivedGroups = lists.archivedGroups
    val favGroups = lists.favGroups
    val filedContacts = lists.filedContacts
    val filedGroups = lists.filedGroups

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
    // Each account's cached face + name (founder item 7). Warmed from disk once
    // per process so a cold start draws the switcher complete on its first
    // frame, then read from memory: no island is asked anything to render this.
    remember { app.rcq.android.data.AccountCards.warm(context) }
    val accountCards by app.rcq.android.data.AccountCards.cards.collectAsState()
    // The same trick for the ISLANDS those accounts live on: warmed from disk
    // once, then a map lookup per row. `Session.refreshCaps` fills it in from
    // every `/server/info` the app makes, so an island answers once and every
    // screen that names it is complete on its first frame afterwards.
    remember { app.rcq.android.data.IslandCards.warm(context) }
    val islandCards by app.rcq.android.data.IslandCards.cards.collectAsState()
    val ownAvatarForCard by session.ownAvatar.collectAsState()
    // The active account describes ITSELF into the cache, and only itself: it
    // is the only one this process can speak for. Every other row then draws
    // from what that account wrote the last time it was the active one.
    //
    // ⚠ Skipped entirely in a migrated decoy session. There is no roster
    // account behind that identity, and writing a card for it would put a
    // fabricated face in a store the real switcher reads.
    LaunchedEffect(activeId, inOwnStoreDecoy, session.nickname, uin, ownAvatarForCard, session.currentServer) {
        val id = activeId
        if (id != null && !inOwnStoreDecoy) {
            // The live picture if the session has one, else the one the last
            // profile load left on disk. `ownAvatar` is null both when there is
            // no picture and for the whole stretch of a launch before the
            // island has answered, and only the persisted profile can tell
            // those apart: no profile on disk means we do not know yet, and the
            // stored card keeps whatever face it had (see AccountCards.record).
            val profile = session.cachedProfile()
            // Through the session's resolver, not by hand: the island no
            // longer returns our own avatar key (profile-key model), so the
            // inline version here recorded a BLANK face over a good one.
            val fromDisk = session.ownAvatarOf(profile)
            val avatar = ownAvatarForCard ?: fromDisk
            app.rcq.android.data.AccountCards.record(
                context = context,
                accountId = id,
                nickname = session.nickname,
                uin = session.uin,
                avatarMediaId = avatar?.first,
                avatarMediaKey = avatar?.second,
                avatarKnown = ownAvatarForCard != null || profile != null,
                host = session.currentServer,
            )
        }
    }
    // A card outlives its account otherwise: the account manager forgets one on
    // its own delete, but burning an account from Privacy and a duress wipe do
    // not pass through that screen. Skipped while any decoy mode is on, where
    // the real roster is not a thing this screen may act on.
    LaunchedEffect(accountList) {
        if (!app.rcq.android.data.AccountManager.isDecoyMode) {
            app.rcq.android.data.AccountCards.prune(
                context,
                app.rcq.android.data.AccountManager.accounts.value.map { it.id }.toSet(),
            )
        }
    }
    val accountRows = remember(accountList, activeId, session.nickname, inOwnStoreDecoy, accountCards, islandCards) {
        if (inOwnStoreDecoy) listOf(
            AccountRow(
                id = app.rcq.android.data.DecoyStore.STORE_ID,
                nickname = session.nickname,
                uin = session.uin,
                host = app.rcq.android.net.RcqApi.DEFAULT_HOST,
                active = true,
            )
        ) else accountList.sortedBy { it.createdAt }.map { a ->
            val card = accountCards[a.id]
            AccountRow(
                id = a.id,
                // SecureStore stays the first source for the name: it is
                // written on every rename by the account itself and the card is
                // only the fallback for a roster entry that predates the cache.
                nickname = app.rcq.android.data.SecureStore.peekNickname(context, a.id)
                    ?: card?.nickname ?: "—",
                uin = app.rcq.android.data.SecureStore.peekUin(context, a.id) ?: card?.uin,
                host = a.serverHost ?: card?.host ?: app.rcq.android.net.RcqApi.DEFAULT_HOST,
                active = a.id == activeId,
                avatarMediaId = card?.avatarMediaId,
                avatarMediaKey = card?.avatarMediaKey,
                islandName = islandCards[(a.serverHost ?: card?.host ?: app.rcq.android.net.RcqApi.DEFAULT_HOST).lowercase()]?.name.orEmpty(),
                islandLogoVersion = islandCards[(a.serverHost ?: card?.host ?: app.rcq.android.net.RcqApi.DEFAULT_HOST).lowercase()]?.logoVersion.orEmpty(),
            )
        }
    }

    // Section titles resolved here (LazyListScope below isn't composable).
    val secFavorites = stringResource(R.string.home_sec_favorites)
    val secOnline = stringResource(R.string.home_sec_online)
    val secOffline = stringResource(R.string.home_sec_offline)
    val secCrossIsland = stringResource(R.string.home_sec_cross_island)
    val secArchive = stringResource(R.string.home_sec_archive)
    val secGroups = stringResource(R.string.home_sec_groups)

    // ── Which sections render, in which order ────────────────────────────
    //
    // `o` ascending, ties by id: one total order every device agrees on. The
    // built-ins are records in the SAME array as the user's own sections (that
    // is how their order syncs), so this is one list and not two.
    //
    // All-empty either because we genuinely have nothing, OR because the first
    // connect/sync hasn't landed yet (tester #4/#9/#13). Hoisted out of the
    // list because the Groups section's own visibility turns on it.
    val connecting = !connected && contacts.isEmpty() && groups.isEmpty() && pending.isEmpty()
    /// ⚠ `!= false` again: unknown renders the cached tree as it is.
    val gatingOn = sectionsOk != false
    val userSectionIds = remember(sectionsTree) {
        Sections.userSections(sectionsTree).map { Sections.idOf(it) }.toSet()
    }
    val orderedSections = remember(sectionsTree) { Sections.orderedSections(sectionsTree) }
    val renderedSections: List<com.google.gson.JsonObject> = orderedSections.filter { rec ->
        val id = Sections.idOf(rec)
        when {
            id == Sections.SYS_SAVED -> savedCount > 0
            Sections.kindOf(rec) == "u" -> gatingOn && id in userSectionIds
            // A section behind a PIN keeps its header whether or not it holds
            // anything: a header that appears only when there is something
            // inside announces exactly what the user asked to hide.
            gatingOn && Sections.isPinnedRecord(rec) -> true
            id == Sections.SYS_FAV -> favContacts.isNotEmpty() || favGroups.isNotEmpty()
            id == Sections.SYS_CI -> crossIslandContacts.isNotEmpty()
            id == Sections.SYS_GROUPS -> !connecting
            id == Sections.SYS_ONLINE -> onlineContacts.isNotEmpty()
            id == Sections.SYS_OFFLINE -> offlineContacts.isNotEmpty()
            id == Sections.SYS_ARCHIVE -> archivedContacts.isNotEmpty() || archivedGroups.isNotEmpty()
            // A built-in id from a newer client: keep the record, draw nothing.
            else -> false
        }
    }

    /// The rendered order as one string, so a drag gesture can be keyed on it.
    val sectionOrderKey = renderedSections.joinToString(",") { Sections.idOf(it) }

    /// Move [id] next to [anchorId] and write the new order once.
    ///
    /// `o` moves in steps of 1024 and a drop between two neighbours takes the
    /// midpoint. When the neighbours are less than 2 apart there is no room
    /// left, so every section is renormalised to `index * 1024`: a normal
    /// last-writer-wins write, rare, and it converges.
    fun placeSection(id: String, anchorId: String, after: Boolean) {
        val rest = orderedSections.filter { Sections.idOf(it) != id }
        val ai = rest.indexOfFirst { Sections.idOf(it) == anchorId }
        if (ai < 0) return
        val at = if (after) ai + 1 else ai
        val before = rest.getOrNull(at - 1)
        val next = rest.getOrNull(at)
        val lo = before?.let { Sections.orderOf(it) }
            ?: next?.let { Sections.orderOf(it) - 2 * Sections.ORDER_STEP } ?: 0L
        val hi = next?.let { Sections.orderOf(it) }
            ?: before?.let { Sections.orderOf(it) + 2 * Sections.ORDER_STEP } ?: Sections.ORDER_STEP
        if (hi - lo < 2L) {
            val ids = rest.take(at).map { Sections.idOf(it) } + id + rest.drop(at).map { Sections.idOf(it) }
            editSections(defer = true) { t ->
                Sections.setOrder(t, ids.mapIndexed { i, x -> x to i * Sections.ORDER_STEP }.toMap())
            }
            return
        }
        editSections(defer = true) { t -> Sections.setOrder(t, mapOf(id to (lo + hi) / 2L)) }
    }
    fun moveSection(id: String, dir: Int) {
        val at = renderedSections.indexOfFirst { Sections.idOf(it) == id }
        if (at < 0) return
        val neighbour = renderedSections.getOrNull(at + dir) ?: return
        placeSection(id, Sections.idOf(neighbour), after = dir > 0)
    }
    fun dropSection(from: String, over: String) {
        val a = renderedSections.indexOfFirst { Sections.idOf(it) == from }
        val b = renderedSections.indexOfFirst { Sections.idOf(it) == over }
        if (a < 0 || b < 0 || a == b) return
        placeSection(from, over, after = a < b)
    }
    /// A drag ends on whichever HEADER the dragged one now sits closest to.
    /// The list is a LazyColumn, so the positions come from its own layout
    /// rather than from anything this screen tracks.
    fun commitSectionDrag(id: String) {
        val info = listState.layoutInfo
        val me = info.visibleItemsInfo.firstOrNull { it.key == "h_$id" }
        if (me != null) {
            val centre = me.offset + dragDy + me.size / 2f
            val target = renderedSections
                .mapNotNull { r ->
                    val k = "h_" + Sections.idOf(r)
                    info.visibleItemsInfo.firstOrNull { it.key == k }?.let { r to (it.offset + it.size / 2f) }
                }
                .minByOrNull { kotlin.math.abs(it.second - centre) }
            if (target != null && Sections.idOf(target.first) != id) dropSection(id, Sections.idOf(target.first))
        }
        draggingSection = null
        dragDy = 0f
    }

    // Founder item 18: with a wallpaper set, everything the list draws over it
    // goes translucent so the picture is actually visible through the chat list
    // instead of being covered edge to edge by opaque rows. 1f (and therefore
    // no change at all) when there is no wallpaper. See [LocalHomeVeil].
    val veil = homeVeil()
    // Л2.12: frosted slices under the section headers. Built from the same
    // inputs HomeBackground() reads, so the frost can never disagree with the
    // sharp wallpaper it aligns itself to. Inactive with no wallpaper, and
    // then everything below renders exactly as before.
    val homeBgSel by LocalStores.homeBackground.collectAsState()
    val slices = rememberWallpaperSlices(homeBgSel, remember { LocalStores.homeBgFile(context) })
    // L2.11: while a row preview is up, the content behind it blurs, the iOS
    // material-scrim analog. RenderEffect exists only on API 31+; below that
    // this stays a plain Modifier and the overlay's heavier dim separates on
    // its own (Modifier.blur/RenderEffect are silent no-ops before 31). The
    // effect only ever runs while the STATIC overlay is open; it is never a
    // per-frame blur over scrolling content, which ChatBackground.kt rejects.
    val previewOpen = previewContact != null || previewGroup != null
    val previewBlur =
        if (previewOpen && android.os.Build.VERSION.SDK_INT >= 31)
            Modifier.graphicsLayer { renderEffect = androidx.compose.ui.graphics.BlurEffect(24f, 24f) }
        else Modifier
    androidx.compose.runtime.CompositionLocalProvider(
        LocalHomeVeil provides veil,
        LocalWallpaperSlices provides slices,
    ) {
    Box(Modifier.fillMaxSize().background(c.bgPrimary)) {
        // Optional home/chat-list wallpaper (separate from the chat one).
        // Renders behind the list; every container above it is veiled rather
        // than opaque so it shows through. No-op on the default ("").
        // Both static layers get the preview blur; the overlay itself is a
        // later sibling of this Box and stays sharp.
        Box(Modifier.fillMaxSize().then(previewBlur).wallpaperSliceLayer(slices)) { HomeBackground() }
        Column(Modifier.fillMaxSize().then(previewBlur)) {
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
                onOpenSites = onOpenSites,
                onOpenRandom = onOpenRandom,
                // Random chat is a secondary destination and an operator can
                // switch it off entirely (admin console -> Features).
                showRandom = randomEnabled,
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
                        .background(c.bgSecondary.copy(alpha = LocalHomeVeil.current))
                        .clickable(onClick = onOpenBackupIsland)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                // Above everything else: an island refused at the trust layer
                // is offline until one of these is answered.
                for ((k, ch) in trustChanged) if (k !in trustHidden) {
                    item(key = "island-trust-$k") { IslandTrustBanner(ch) }
                }
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
                            // host "" = a same-island stranger from the Privacy
                            // quarantine: a plain #uin, not a dangling "@".
                            val address = if (r.host.isEmpty()) "${r.uin}" else "${r.uin}@${r.host}"
                            CiPendingRow(
                                tag = r.nickname?.takeIf { it.isNotBlank() }?.let { "$it · $address" } ?: address,
                                preview = r.preview.ifEmpty {
                                    if (r.contactReq) stringResource(R.string.ci_contact_request) else ""
                                },
                                onAccept = {
                                    scope.launch {
                                        // Accepting a same-island stranger releases their
                                        // held messages into a normal thread; open it.
                                        runCatching { session.acceptCrossIslandRequest(r.uin, r.host) }
                                            .onSuccess { ok -> if (ok && r.host.isEmpty()) onOpenChat(r.uin) }
                                    }
                                },
                                onDismiss = { session.dismissCrossIslandRequest(r.uin, r.host) },
                                onBlock = { session.blockCrossIslandRequest(r.uin, r.host) },
                            )
                        }
                    }
                }

                // In the "connecting" case show a "connecting" state with the
                // petal loader instead of the misleading "no contacts" prompt.
                if (contacts.isEmpty() && groups.isEmpty() && pending.isEmpty()) {
                    item(key = "empty") {
                        if (connecting) ConnectingState(stealth = stealthActive) else EmptyState(onAdd = { showAdd = true }, myUin = uin, session = session, onOpenGroup = onOpenGroup)
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

                // ── The sections, in the order the vault slot says ──────
                //
                // One loop over `orderedSections`, built-ins and the user's own
                // together, because that is the only way their ORDER can sync:
                // a built-in is a record in the same array as a user section
                // and carries nothing but its `o` (and, since 23.08, its `p`).
                //
                // ⚠ A chat filed into a user section has already left every
                // derived one, in `lists` above. It renders once, where the user
                // put it, and nowhere else.
                if (reorderingSections) {
                    item(key = "reorder-bar") { SectionReorderBar(onDone = { reorderingSections = false }) }
                }
                for (rec in renderedSections) {
                    val sid = Sections.idOf(rec)
                    val isUser = Sections.kindOf(rec) == "u"
                    // Saved Messages is a ROW here, not a section: `sys.saved`
                    // holds its ORDER only (design §7), which is exactly why it
                    // is inside this loop rather than pinned above it.
                    //
                    // It has been at the TOP since vss asked ("why is it in the
                    // middle, it belongs at the very top next to favourites"),
                    // and its default `o` of 0 is what keeps it there.
                    if (sid == Sections.SYS_SAVED) {
                        item(key = "saved") { SavedRow(count = savedCount, unread = 0, onClick = onOpenSaved) }
                        continue
                    }
                    val title = when (sid) {
                        Sections.SYS_FAV -> secFavorites
                        Sections.SYS_CI -> secCrossIsland
                        Sections.SYS_GROUPS -> secGroups
                        Sections.SYS_ONLINE -> secOnline
                        Sections.SYS_OFFLINE -> secOffline
                        Sections.SYS_ARCHIVE -> secArchive
                        else -> Sections.nameOf(rec) ?: ""
                    }
                    // ⚠ The gate is per DEVICE and it is a collapsed header, not
                    // a key. In a decoy session nothing is gated at all: asking
                    // for the real PIN there rejects the coercer's decoy PIN as
                    // "wrong" and announces that a second PIN exists.
                    val pinnedRec = gatingOn && !inDecoy && Sections.isPinnedRecord(rec)
                    val unlockedNow = pinnedRec && sid in unlockedSections
                    val locked = pinnedRec && !unlockedNow
                    val isArchive = sid == Sections.SYS_ARCHIVE
                    val collapseKey = when (sid) {
                        Sections.SYS_FAV -> "sec:fav"
                        Sections.SYS_GROUPS -> "sec:grp"
                        Sections.SYS_ONLINE -> "sec:online"
                        Sections.SYS_OFFLINE -> "sec:offline"
                        Sections.SYS_CI -> "sec:ci"
                        else -> "sec:u:$sid"
                    }
                    val collapsed = when {
                        locked -> true
                        pinnedRec -> false
                        isArchive -> "sec:archive:open" !in sectionFlags
                        else -> collapseKey in sectionFlags
                    }
                    val target = SectionMenuTarget(sid, title, isUser, Sections.isPinnedRecord(rec))
                    val onToggle: () -> Unit = {
                        when {
                            locked -> sectionPinPrompt = target
                            // Collapsing a section the user got past the PIN for
                            // puts the gate back. The unlocked set is view
                            // memory and nothing else.
                            pinnedRec -> unlockedSections = unlockedSections - sid
                            isArchive -> LocalStores.setSectionFlag("sec:archive:open", collapsed)
                            else -> LocalStores.setSectionFlag(collapseKey, !collapsed)
                        }
                    }
                    // ⚠⚠ NO MENU ON A LOCKED SECTION, and this is the gate
                    // itself rather than a nicety. The menu carries "stop asking
                    // for a PIN" and "delete section", and neither asks for the
                    // PIN: on a locked header they turn the gate off in two
                    // taps, with no verify call, no failure counter and no
                    // cooldown, and then sync p:0 to every other device. The
                    // plus button and the rename are suppressed for the same
                    // reason. Unlock first, then the menu.
                    val onLongPress: (() -> Unit)? =
                        if (sectionsOk == true && !locked && !reorderingSections) ({ sectionMenu = target }) else null
                    val cs = filedContacts[sid].orEmpty()
                    val gs = filedGroups[sid].orEmpty()
                    val count = when (sid) {
                        Sections.SYS_FAV -> favContacts.size + favGroups.size
                        Sections.SYS_CI -> crossIslandContacts.size
                        Sections.SYS_GROUPS -> visibleGroups.size
                        Sections.SYS_ONLINE -> onlineContacts.size
                        Sections.SYS_OFFLINE -> offlineContacts.size
                        Sections.SYS_ARCHIVE -> archivedContacts.size + archivedGroups.size
                        else -> cs.size + gs.size
                    }
                    // ⚠ While locked, no member count and no unread badge. A
                    // badge is a leak of exactly what the user hid.
                    val headerUnread = if (locked) 0 else when (sid) {
                        Sections.SYS_FAV -> favContacts.sumOf { unread[LocalStores.peerThread(it.uin)] ?: 0 } +
                            favGroups.sumOf { unread[LocalStores.groupThread(it.id)] ?: 0 }
                        Sections.SYS_CI -> crossIslandContacts.sumOf { unread[LocalStores.peerThread(it.uin)] ?: 0 }
                        Sections.SYS_GROUPS -> 0
                        Sections.SYS_ONLINE -> onlineContacts.sumOf { unread[LocalStores.peerThread(it.uin)] ?: 0 }
                        Sections.SYS_OFFLINE -> offlineContacts.sumOf { unread[LocalStores.peerThread(it.uin)] ?: 0 }
                        Sections.SYS_ARCHIVE -> archivedContacts.sumOf { unread[LocalStores.peerThread(it.uin)] ?: 0 } +
                            archivedGroups.sumOf { unread[LocalStores.groupThread(it.id)] ?: 0 }
                        else -> cs.sumOf { unread[LocalStores.peerThread(it.uin)] ?: 0 } +
                            gs.sumOf { unread[LocalStores.groupThread(it.id)] ?: 0 }
                    }
                    item(key = "h_$sid") {
                        val dragging = draggingSection == sid
                        Box(
                            Modifier
                                .graphicsLayer { translationY = if (dragging) dragDy else 0f }
                                .then(
                                    // ⚠⚠ KEYED ON THE ORDER, not on the id alone.
                                    // `pointerInput` does NOT restart its
                                    // coroutine when the key is unchanged, so
                                    // the running gesture goes on using the
                                    // lambdas captured by the composition that
                                    // started it -- including the section list
                                    // as it stood THEN. Keyed on `sid` only,
                                    // the first drop after an arrow move
                                    // computed "is the target above or below
                                    // me" from the order before the arrow, put
                                    // the section back where it already was,
                                    // and looked like a drag that does nothing
                                    // (seen on the emulator, 23.08). The order
                                    // can only change between drags, never
                                    // during one, so restarting on it is free.
                                    if (!reorderingSections) Modifier else Modifier.pointerInput(sid, sectionOrderKey) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { draggingSection = sid; dragDy = 0f },
                                            onDrag = { change, amount -> change.consume(); dragDy += amount.y },
                                            onDragEnd = { commitSectionDrag(sid) },
                                            onDragCancel = { draggingSection = null; dragDy = 0f },
                                        )
                                    },
                                ),
                        ) {
                            SectionHeader(
                                title = title,
                                count = count,
                                collapsed = collapsed,
                                onToggle = onToggle,
                                onLongPress = onLongPress,
                                showCount = !locked,
                                locked = pinnedRec,
                            ) {
                                when {
                                    reorderingSections -> {
                                        Icon(
                                            Icons.Filled.KeyboardArrowUp,
                                            stringResource(R.string.sections_move_up),
                                            tint = c.accent,
                                            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { moveSection(sid, -1) },
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        Icon(
                                            Icons.Filled.KeyboardArrowDown,
                                            stringResource(R.string.sections_move_down),
                                            tint = c.accent,
                                            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { moveSection(sid, 1) },
                                        )
                                    }
                                    // The plus that files a chat into this
                                    // section. Suppressed while locked, same
                                    // rule as the menu.
                                    isUser && !locked -> Icon(
                                        Icons.Filled.Add,
                                        stringResource(R.string.sections_add),
                                        tint = c.accent,
                                        modifier = Modifier.size(20.dp).clip(CircleShape).clickable { sectionPicker = sid },
                                    )
                                    sid == Sections.SYS_GROUPS -> Icon(
                                        Icons.Filled.Add,
                                        "New group",
                                        tint = c.accent,
                                        modifier = Modifier.size(20.dp).clip(CircleShape).clickable { showCreateGroup = true },
                                    )
                                    else -> UnreadBadge(headerUnread)
                                }
                            }
                        }
                    }
                    if (collapsed) continue
                    when (sid) {
                        // Favorites holds BOTH favorited contacts AND groups
                        // (mirrors Archive). A favorited group used to vanish
                        // because this section rendered only contacts.
                        Sections.SYS_FAV -> {
                            items(favContacts, key = { contactKey("fav", it) }) { ct ->
                                ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, session = session, onClick = { onOpenChat(ct.uin) }, onLongPress = { openContactPreview(ct) })
                            }
                            items(favGroups, key = { "favg_${it.id}" }) { g ->
                                GroupRow(group = g, ownUin = uin, session = session, unread = unread[LocalStores.groupThread(g.id)] ?: 0, onClick = { onOpenGroup(g.id) }, onLongPress = { openGroupPreview(g) })
                            }
                        }
                        Sections.SYS_GROUPS -> {
                            if (visibleGroups.isEmpty()) {
                                item(key = "grp-empty") {
                                    Row(Modifier.fillMaxWidth().clickable { showCreateGroup = true }.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Filled.Add, null, tint = c.accent, modifier = Modifier.size(18.dp))
                                        Text(stringResource(R.string.home_create_group), color = c.textPrimary, fontSize = 13.sp)
                                    }
                                }
                            } else {
                                items(items = visibleGroups, key = { it.id }) { g: RcqGroup ->
                                    GroupRow(group = g, ownUin = uin, session = session, unread = unread[LocalStores.groupThread(g.id)] ?: 0, onClick = { onOpenGroup(g.id) }, onLongPress = { openGroupPreview(g) })
                                }
                            }
                        }
                        Sections.SYS_ONLINE -> items(onlineContacts, key = { contactKey("on", it) }) { ct ->
                            ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, session = session, onClick = { onOpenChat(ct.uin) }, onLongPress = { openContactPreview(ct) })
                        }
                        Sections.SYS_OFFLINE -> items(offlineContacts, key = { contactKey("off", it) }) { ct ->
                            ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, session = session, onClick = { onOpenChat(ct.uin) }, onLongPress = { openContactPreview(ct) })
                        }
                        Sections.SYS_CI -> items(crossIslandContacts, key = { contactKey("cisl", it) }) { ct ->
                            ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, session = session, onClick = { onOpenChat(ct.uin) }, onLongPress = { openContactPreview(ct) })
                        }
                        // Archive holds BOTH archived contacts AND archived
                        // groups. (An archived group was filtered out of the
                        // main list but never rendered here, so it vanished
                        // entirely and could not be un-archived.)
                        Sections.SYS_ARCHIVE -> {
                            items(archivedContacts, key = { contactKey("arch", it) }) { ct ->
                                ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, session = session, onClick = { onOpenChat(ct.uin) }, onLongPress = { openContactPreview(ct) })
                            }
                            items(archivedGroups, key = { "archg_${it.id}" }) { g ->
                                GroupRow(group = g, ownUin = uin, session = session, unread = unread[LocalStores.groupThread(g.id)] ?: 0, onClick = { onOpenGroup(g.id) }, onLongPress = { openGroupPreview(g) })
                            }
                        }
                        else -> {
                            // An EMPTY user section still renders, header, plus
                            // button and hint: the user made it on purpose. This
                            // is where it differs from Archive and Favorites,
                            // which hide when empty.
                            if (cs.isEmpty() && gs.isEmpty()) {
                                item(key = "u-empty-$sid") { SectionEmptyHint() }
                            } else {
                                items(gs, key = { "u${sid}g${it.id}" }) { g ->
                                    GroupRow(group = g, ownUin = uin, session = session, unread = unread[LocalStores.groupThread(g.id)] ?: 0, onClick = { onOpenGroup(g.id) }, onLongPress = { openGroupPreview(g) })
                                }
                                items(cs, key = { contactKey("u$sid", it) }) { ct ->
                                    ContactRowItem(ct, unread = unread[LocalStores.peerThread(ct.uin)] ?: 0, session = session, onClick = { onOpenChat(ct.uin) }, onLongPress = { openContactPreview(ct) })
                                }
                            }
                        }
                    }
                }

                item(key = "tail") { Spacer(Modifier.height(8.dp)) }
            }

            BottomBar(
                onAdd = { showAdd = true },
                onQr = { showQr = true },
                onNearby = onOpenNearby,
                onSettings = onOpenSettings,
                // Operator toggles Nearby via the admin console (Features).
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
                subtitle = "${ct.uin}",
                // No host: a PERSON's picture always lives on OUR island —
                // ours natively, a cross-island contact's because §5e DEPOSITS
                // the blob here rather than having us pull it from theirs.
                avatar = { PersonAvatar(ct.avatarMediaId, ct.avatarMediaKey, ct.presence, session, 24.dp) },
                messages = messages[ct.uin] ?: emptyList(),
                isGroup = false,
                senderName = { session.contactName(it) },
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
                subtitle = memberCountLabel(g.memberCount),
                avatar = { GroupAvatar(g, session, 24.dp) },
                messages = groupMsgs[g.id] ?: emptyList(),
                isGroup = true,
                // Roster nick first (the name the room sees), contactName
                // after: same order the chat's mention resolver uses. The
                // roster may not be cached yet on the home screen; contactName
                // still lands on a name or "#uin".
                senderName = { u -> g.members.firstOrNull { it.uin == u }?.nickname ?: session.contactName(u) },
                actions = groupActions(g, uin, session, scope, context, onOpenGroup,
                    onClearThread = { clearGroupTarget = it }),
                onDismiss = { previewGroup = null },
            )
        }
        // The first-use notice (design §5.1): non-blocking, dismissible, once
        // per host. Not a modal: onboarding must not stop on a dialog most
        // people cannot evaluate; the careful person types `host#fp` instead.
        SnackbarHost(trustNotice, Modifier.align(Alignment.BottomCenter)) { data ->
            IslandFirstUseSnackbar(data, noticeFp)
        }
    }
    } // CompositionLocalProvider(LocalHomeVeil)

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
            onRestore = { showAddAccount = false; onRestoreBySeed() },
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
    // ── The sections sheets (founder item 1 of 23.08) ─────────────────────
    sectionMenu?.let { t ->
        SectionMenuSheet(
            target = t,
            canPin = canPin,
            onReorder = { reorderingSections = true },
            onTogglePin = {
                val on = !t.pinned
                editSections { tree -> Sections.setPinned(tree, t.id, on) }
                // Turning the gate ON closes it here and now; leaving it on an
                // open section until the next cold start is a gate the user
                // watched not happen.
                if (on) unlockedSections = unlockedSections - t.id
            },
            onNew = { creatingSection = true },
            onRename = { sectionRename = t },
            onDelete = { sectionDelete = t },
            onDismiss = { sectionMenu = null },
        )
    }
    if (creatingSection) {
        SectionNameSheet(
            title = stringResource(R.string.sections_new_title),
            initial = "",
            saveLabel = stringResource(R.string.sections_new_save),
            onSave = { name -> editSections { tree -> Sections.createSection(tree, name) } },
            onDismiss = { creatingSection = false },
        )
    }
    sectionRename?.let { t ->
        SectionNameSheet(
            title = stringResource(R.string.sections_rename_title),
            initial = t.title,
            saveLabel = stringResource(R.string.sections_rename_save),
            onSave = { name -> editSections { tree -> Sections.renameSection(tree, t.id, name) } },
            onDismiss = { sectionRename = null },
        )
    }
    sectionDelete?.let { t ->
        RcqAskSheet(
            onDismiss = { sectionDelete = null },
            title = t.title,
            body = stringResource(R.string.sections_delete_confirm),
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.sections_menu_delete),
                    destructive = true,
                    onClick = {
                        // Deleting a section does not touch the chats: its
                        // members fall back into their derived sections on the
                        // next render.
                        editSections { tree -> Sections.deleteSection(tree, t.id) }
                        // Its fold flag goes with it; nothing else refers to
                        // the id once the record is gone.
                        LocalStores.forgetSectionFlag(t.id)
                        unlockedSections = unlockedSections - t.id
                        sectionDelete = null
                    },
                ),
            ),
        )
    }
    sectionPinPrompt?.let { t ->
        SectionPinSheet(
            title = t.title,
            onUnlocked = { unlockedSections = unlockedSections + t.id },
            onDismiss = { sectionPinPrompt = null },
        )
    }
    sectionPicker?.let { id ->
        val rec = remember(sectionsTree, id) { Sections.recordFor(sectionsTree, id) }
        // ⚠ Seeded ONCE per opening of the sheet, keyed on the section and not
        // on the tree. The sheet hands back what the USER did, relative to the
        // membership it opened on: re-seeding this when the tree moves (the
        // desktop files a chat here, the nudge folds it into the cache) turns a
        // row the user never touched into a removal, with a tombstone newer
        // than the other device's add, and the merge keeps the undo.
        val initial = remember(id) { Sections.membersOf(sectionsTree, id) }
        val candidates = remember(contacts, groups) {
            val byKey = LinkedHashMap<String, SectionCandidate>()
            for (ct in contacts) {
                val key = Sections.peerKey(ct.uin, ct.host)
                if (byKey.containsKey(key)) continue
                byKey[key] = SectionCandidate(
                    key = key,
                    title = session.contactName(ct.uin).ifBlank { "${ct.uin}" },
                    subtitle = if (ct.host != null) "${ct.uin} \u00b7 ${ct.host}" else "${ct.uin}",
                    group = false,
                    avatar = { PersonAvatar(ct.avatarMediaId, ct.avatarMediaKey, ct.presence, session, 30.dp) },
                )
            }
            for (g in groups) {
                // ⚠⚠ Never the local id for a foreign group: it is a negative
                // alias this device made up.
                val key = app.rcq.android.data.SectionsVault.keyForGroup(g) ?: continue
                if (byKey.containsKey(key)) continue
                byKey[key] = SectionCandidate(
                    key = key,
                    title = g.name,
                    subtitle = g.host ?: secGroups,
                    group = true,
                    avatar = { GroupAvatar(g, session, 30.dp) },
                )
            }
            byKey.values.toList()
        }
        SectionPickerSheet(
            sectionName = Sections.nameOf(rec ?: com.google.gson.JsonObject()) ?: "",
            candidates = candidates,
            initial = initial,
            onSave = { added, removed ->
                // ⚠ What the USER did, never "the membership is now exactly
                // this list": the sheet's checkboxes are seeded once and the
                // tree moves under an open sheet.
                if (added.isNotEmpty() || removed.isNotEmpty()) {
                    editSections { tree ->
                        var out = if (added.isNotEmpty()) Sections.addMembers(tree, id, added) else tree
                        for (k in removed) out = Sections.removeMemberFrom(out, id, k)
                        out
                    }
                }
            },
            onDismiss = { sectionPicker = null },
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
}

/**
 * One account's face in the switcher and in the account manager (founder
 * item 7).
 *
 * The picture, when that account has one cached, otherwise the generic account
 * glyph this slot has always shown. Nothing here can fail into a blank: the
 * glyph is drawn first and the picture covers it, so an account whose blob is
 * not on this device yet looks exactly like it did before this existed.
 *
 * ⚠ The island is passed down to the fetch, and it matters. The desktop hands
 * its avatar component the ROW's own `apiBase` for the same reason: a row for
 * an account living on another island must not ask the ACTIVE island for a
 * picture it has never held. Skipped when the row is already on our island, so
 * the common single-island case keeps using the session's own configured client
 * rather than a second one built for the same host. Media reads are
 * unauthenticated, so no token belonging to any account is involved either way.
 */
@Composable
internal fun AccountAvatar(
    mediaId: String?,
    mediaKey: String?,
    host: String?,
    active: Boolean,
    session: Session,
    size: Dp,
) {
    val c = RcqTheme.colors
    val foreign = host?.takeIf { it.isNotBlank() && it != session.currentServer }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Outlined.AccountCircle, null,
            tint = if (active) c.accent else c.textSecondary,
            modifier = Modifier.size(size),
        )
        // Presence is deliberately absent: this is a list of MY identities, and
        // a status flower on an account that is not the mounted one would be
        // reporting a presence nothing is keeping up to date. `showStatus =
        // false` also makes the no-picture case draw nothing at all, which is
        // what lets the glyph above show through.
        PersonAvatar(
            mediaId, mediaKey, UserStatus.OFFLINE, session, size,
            host = foreign, showStatus = false,
        )
        if (active) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .size(size * 0.42f)
                    .clip(CircleShape)
                    .background(c.bgSecondary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, null, tint = c.accent, modifier = Modifier.size(size * 0.34f))
            }
        }
    }
}

/** [AccountAvatar] for a switcher row that has already been assembled. */
@Composable
private fun AccountAvatar(row: AccountRow, session: Session, size: Dp) =
    AccountAvatar(row.avatarMediaId, row.avatarMediaKey, row.host, row.active, session, size)

/** Saved Messages in the chat list. Same shape as a contact row so it does not
 *  read as a special banner, with a bookmark instead of an avatar. */
@Composable
private fun SavedRow(count: Int, unread: Int, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        // A fill of its own, because it is a list row and every other list row
        // has one. Identical to the screen's background without a wallpaper;
        // with one it takes the same veil as its neighbours instead of leaving
        // its two lines of text standing on the picture (founder item 18b).
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(c.bgPrimary.copy(alpha = LocalHomeVeil.current))
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
 *  overflow menu of the things you can do (add contact, search, news, saved,
 *  and the secondary destinations: audio rooms, radio, random chat). Items
 *  whose screens aren't built yet route to a "coming soon" sheet. */
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
    onOpenSites: () -> Unit,
    onOpenRandom: () -> Unit,
    showRandom: Boolean = true,
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
    var showStealthInfo by remember { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

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
                // The sheet names the relay, so it also has to say where to
                // find out what one is.
                RelayLearnMore()
            }
            SheetGap()
            // #737: the shield names the relays and explains them, so it is
            // also the natural place to switch them off - without a trip back
            // through the overflow menu.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showStealthInfo = false; onToggleBypass(false) }) {
                    Text(stringResource(R.string.stealth_shield_turn_off), color = c.textSecondary)
                }
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
            // Was a bare account glyph: the same picture whichever island you
            // were on, which made the one control that says WHERE YOU ARE say
            // nothing at all. It carries the island's own logo now, or its
            // lettered tile, straight off the cache: no fetch, and a cold start
            // on a plane still knows which island it is on. Same fix iOS made
            // to its switcher pill (`accountSwitcherPill`).
            val here = accounts.firstOrNull { it.active }
            IslandAvatar(
                host = here?.host ?: session.currentServer,
                logoVersion = here?.islandLogoVersion,
                name = here?.islandName,
                size = 28.dp,
                modifier = Modifier.clickable(onClickLabel = "Accounts") { accountMenu = true },
            )
            DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                accounts.forEach { a ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(a.nickname, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                                // The island, drawn as an island: its picture
                                // and the name its operator typed, falling back
                                // to the lettered tile and the bare host for one
                                // that has never answered. The row used to carry
                                // the hostname alone, which is the one line here
                                // that a person cannot read at a glance.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    IslandAvatar(a.host, a.islandLogoVersion, a.islandName, size = 14.dp)
                                    Text(
                                        a.islandName.ifBlank { a.host },
                                        color = c.textSecondary, fontSize = 12.sp,
                                    )
                                }
                                a.uin?.let { Text("$it", color = c.textMono, fontSize = 12.sp) }
                            }
                        },
                        // The account's own face, from the cache it wrote when it
                        // was last active (founder item 7). The tick that used to
                        // be the whole of this slot moves onto the picture as a
                        // corner badge, so "which one am I on" is still answered
                        // without spending the only place a face can go.
                        leadingIcon = { AccountAvatar(a, session, 30.dp) },
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
                    // ⚠ The "stay visible after you leave" countdown used to sit
                    // here and is gone with the feature (founder, 23.08): Privacy
                    // no longer offers the switch, so nothing anchors a window and
                    // the chip could only ever draw nothing.
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
                Text("$uin", color = chrome.textMono, fontSize = 12.sp, lineHeight = 12.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
            }
            // Right of the nick/UIN: a status-width slot holding the shield
            // while the app is going through RCQ relays (iOS StealthHeaderBadge
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
            // Two marks in one slot is fine: the relay shield and a pending
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
                    // reach the backend; amber when the relays are engaged but not yet
                    // (or no longer) carrying traffic, so it can't claim a working
                    // relay route when the chain is dead ("щит есть, связи нет").
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
                // Bare red, no ring. The ring was `bgPrimary`, so over a home
                // wallpaper it drew a white (or near-black) halo around a 6dp
                // dot and the halo was bigger than the dot (founder, 24.08).
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE5484D)),
                )
            }
            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                // RCQ relays: manual override, back by request. It also
                // engages automatically when a direct connection looks blocked,
                // but auto-detection can be wrong ("green" indicator yet no real
                // traffic), so the manual on/off lives here too — it engages/drops
                // sing-box LIVE (setObfuscation) without an app restart.
                DropdownMenuItem(
                    // #738: the row's NAME stays put and the action moves to the
                    // subtitle - same rule the screenshot notice follows. A menu
                    // item that renames itself reads as two different items to a
                    // screen reader and to the person who memorised the menu.
                    text = {
                        Column {
                            Text(stringResource(R.string.home_menu_bypass_title), color = c.textPrimary)
                            Text(
                                stringResource(if (stealthActive) R.string.home_menu_bypass_disable else R.string.home_menu_bypass_enable),
                                color = c.textSecondary, fontSize = 12.sp,
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.Shield, null, tint = if (stealthActive) c.accent else c.textSecondary) },
                    onClick = { overflowMenu = false; onToggleBypass(!stealthActive) },
                )
                // The menu is the one place that names the relays while they are
                // OFF, and there is no shield to tap for the explanation then, so
                // the way to find out what a relay is has to sit right here.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.relay_what_is), color = c.textSecondary) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, null, tint = c.textSecondary) },
                    onClick = { overflowMenu = false; runCatching { uriHandler.openUri(RELAYS_FAQ_URL) } },
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
                // `.rcq` pages. Here rather than in the bottom bar for the same
                // reason random chat is: it is a place you go to now and then,
                // not one of the four you reach every day. `Icons.Filled.Public`
                // is spoken for on this screen as the cross-island glyph.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_sites), color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.Language, null, tint = c.accent) },
                    onClick = { overflowMenu = false; onOpenSites() },
                )
                // Random chat used to sit in the bottom bar, next to the things
                // you reach every day. It is a side attraction, not one of
                // those, so it lives here with the other optional destinations
                // (founder's call). The operator flag still decides whether it
                // is offered at all.
                if (showRandom) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_menu_random), color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Filled.Shuffle, null, tint = c.accent) },
                        onClick = { overflowMenu = false; onOpenRandom() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(group: RcqGroup, ownUin: Int, session: Session, unread: Int, onClick: () -> Unit, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // NOT `by`: the animated value is read inside the graphicsLayer block
    // below, in the draw phase, so a press animates without recomposing the row.
    val scale = animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
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
        Modifier.fillMaxWidth()
            // graphicsLayer, not Modifier.scale: `scale` is read here in the
            // modifier chain, so every frame of the press animation invalidated
            // this row's COMPOSITION. Read inside the layer block instead and
            // the animation costs a redraw, which is what it is.
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLongPress)
            .background(c.bgPrimary.copy(alpha = LocalHomeVeil.current))
            .padding(horizontal = 10.dp, vertical = 7.dp),
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
                BadgeMark(group.badge)
                if (group.ownerUin == ownUin) Icon(Icons.Filled.Star, "Owner", tint = c.accent, modifier = Modifier.size(12.dp))
                if (muted) Icon(Icons.Filled.NotificationsOff, null, tint = c.textSecondary, modifier = Modifier.size(11.dp))
            }
            Text(
                memberCountLabel(group.memberCount) + (group.host?.let { " · $it" } ?: ""),
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
    // NOT `by`: the animated value is read inside the graphicsLayer block
    // below, in the draw phase, so a press animates without recomposing the row.
    val scale = animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
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
            // See [GroupRow]: read the press animation in the draw phase, not
            // in composition.
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLongPress)
            .background(c.bgPrimary.copy(alpha = LocalHomeVeil.current))
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
                BadgeMark(contact.badge)
                GenderIcon(contact.gender)
                if (contact.blocked) Icon(Icons.Outlined.Block, null, tint = c.statusBusy, modifier = Modifier.size(11.dp))
                if (muted) Icon(Icons.Filled.NotificationsOff, null, tint = c.textSecondary, modifier = Modifier.size(11.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${contact.uin}", color = c.textMono, fontSize = 12.sp)
                val ctx = LocalContext.current
                // ⚠ Order matters, and it used to be the other way round: a
                // status message won outright, so an OFFLINE contact who had
                // one never showed when they were last around. Measured on
                // prod 31.08: of 1498 contact rows genuinely offline, 455
                // (30%) carry a status message, so for nearly a third of
                // people the last seen was invisible everywhere - most
                // visibly in Favourites and user sections, where there is no
                // Online/Offline heading to read it off instead. A status
                // message is text somebody left behind; when they are not
                // here, WHEN they were here is the more useful half, so it
                // goes first and the message keeps whatever room is left.
                val seen = if (contact.presence == UserStatus.OFFLINE && contact.lastSeen != null) {
                    lastSeenPhrase(contact.lastSeen, contact.gender, ctx)
                } else null
                val msg = contact.statusMessage?.takeIf { it.isNotEmpty() }
                // Both worth saying, room for one: they take turns (founder).
                if (contact.host == null && seen != null && msg != null) {
                    Text("·", color = c.textSecondary, fontSize = 12.sp)
                    AltText(seen, msg, c.textSecondary, 12.sp)
                    return@Row
                }
                val sub = when {
                    // §5c: a cross-island peer shows its island (presence/last_seen
                    // don't cross islands), then any status message.
                    contact.host != null -> contact.host + (msg?.let { " · $it" } ?: "")
                    seen != null -> seen
                    else -> msg
                }
                if (sub != null) {
                    Text(
                        "· $sub",
                        color = c.textSecondary,
                        fontSize = 12.sp,
                        // Italic marked "this is their own words". Now that a
                        // last seen can share the line, italics would be a lie
                        // about half of it, so it is kept only when the line is
                        // nothing BUT their words.
                        fontStyle = if (seen == null && msg != null) FontStyle.Italic else FontStyle.Normal,
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
        Modifier.fillMaxWidth().background(c.bgPrimary.copy(alpha = LocalHomeVeil.current)).padding(horizontal = 10.dp, vertical = 8.dp),
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
        Modifier.fillMaxWidth().background(c.bgPrimary.copy(alpha = LocalHomeVeil.current)).padding(horizontal = 10.dp, vertical = 8.dp),
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

/// The §5.2 banner and its fingerprint rows moved to IslandTrustUi.kt: the
/// list here is no longer the only surface that draws them (onboarding,
/// restore and the settings forms take island addresses too).

/// Shown once, after an update that took the full-screen-intent grant with it.
/// Same shape as the push nudge so it reads as the same kind of message.
@Composable
private fun FullScreenIntentBanner(onFix: () -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.bgSecondary.copy(alpha = LocalHomeVeil.current)).padding(14.dp),
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
            .clip(RoundedCornerShape(12.dp)).background(c.bgSecondary.copy(alpha = LocalHomeVeil.current)).padding(14.dp),
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
        // Veiled like a row rather than left bare on the wallpaper: this block
        // is prose, and prose is the first thing a busy picture eats.
        Modifier.fillMaxWidth()
            .background(c.bgPrimary.copy(alpha = LocalHomeVeil.current))
            .padding(horizontal = 20.dp, vertical = 18.dp),
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
private fun EmptyState(onAdd: () -> Unit, myUin: Int, session: Session, onOpenGroup: (Int) -> Unit) {
    // Fills the list area with nothing but the wallpaper behind it, so it takes
    // the wallpaper's foregrounds and not the theme's, same as the header (#554).
    val c = homeChrome()
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Rooms to walk into, before anything about contacts: nobody arrives
        // with friends already here, and every new account used to be dropped
        // into one beta room for exactly this reason. Now it is a choice.
        DiscoverGroupsRow(session, onOpenGroup)
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

/** Open rooms, biggest first, each joinable in one tap. Drawn only when the
 *  island answered with something; an empty or failed answer draws nothing, so
 *  the screen never carries a heading over an empty strip. */
@Composable
private fun DiscoverGroupsRow(session: Session, onOpenGroup: (Int) -> Unit) {
    val ctx = LocalContext.current
    val c = homeChrome()
    val scope = rememberCoroutineScope()
    var rooms by remember { mutableStateOf<List<RcqApi.GroupPreviewOut>>(emptyList()) }
    var joining by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { rooms = session.discoverGroups(12) }
    if (rooms.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.home_discover_title).uppercase(),
            color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        )
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
        ) {
            items(rooms, key = { it.id }) { g ->
                Column(
                    Modifier.width(132.dp).clip(RoundedCornerShape(16.dp)).background(c.bgSecondary).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GroupAvatarMedia(g.avatar_media_id, g.avatar_media_key, session, 48.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            g.name ?: "#${g.id}", color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        BadgeMark(g.badge, size = 12.dp)
                    }
                    Text(
                        pluralStringResource(R.plurals.members, g.member_count, g.member_count),
                        color = c.textSecondary, fontSize = 11.sp,
                    )
                    val busy = joining == g.id
                    Text(
                        stringResource(R.string.home_discover_join),
                        color = if (busy) c.textSecondary else c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = joining == null) {
                                joining = g.id
                                scope.launch {
                                    val joined = session.joinGroup(g.id)
                                    joining = null
                                    // A failed join keeps the card and says so:
                                    // dropping it read as "the room is gone".
                                    if (joined != null) onOpenGroup(g.id)
                                    else android.widget.Toast.makeText(ctx, R.string.home_discover_join_failed, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
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
        // When the app had to go through the relays, say so (iOS "engaging
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
private fun BottomBar(onAdd: () -> Unit, onQr: () -> Unit, onNearby: () -> Unit, onSettings: () -> Unit, showNearby: Boolean = true) {
    val c = RcqTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(c.bgSecondary.copy(alpha = LocalHomeVeil.current))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.Filled.PersonAdd, stringResource(R.string.home_bar_add), onAdd)
        BarButton(Icons.Filled.QrCode2, stringResource(R.string.home_bar_qr), onQr)
        // Nearby is a mesh feature and stays on the bar; the operator can still
        // switch it off (admin console -> Features), and then the bar is three
        // buttons wide instead of four. Each one takes an equal share of the
        // row, so a hidden entry closes up rather than leaving a hole.
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
        // One line, always. Up to four labels share the width, and the longest
        // of them ("Настройки") wrapped onto a second line on a device with the
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

// The row long-press preview (PreviewOverlay) lives in HomePreview.kt: an
// iOS-style read-only thread window + separate actions card (L2.11).

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
            items(filtered, key = { contactKey("search", it) }) { ct ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(ct) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusIcon(ct.presence, size = 26.dp)
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ct.nickname, color = c.textPrimary, fontSize = 15.sp)
                            BadgeMark(ct.badge)
                        }
                        Text("${ct.uin}", color = c.textMono, fontSize = 12.sp)
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
    // Seeded from AddSheet so the trip to a profile and back lands on the
    // results that were already found, not on "Поиск…" over an empty list.
    val restored = AddSheet.resultsFor != null && AddSheet.resultsFor == query.trim()
    var users by remember { mutableStateOf(if (restored) AddSheet.users else emptyList()) }
    var groups by remember { mutableStateOf(if (restored) AddSheet.groups else emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // The query whose results are currently on screen — the search effect
    // relaunches on every re-entry into composition and must not re-run for
    // a query it already answered (#615).
    var lastSearched by remember { mutableStateOf(if (restored) AddSheet.resultsFor else null) }
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
        if (q.isEmpty() || GroupLinkParser.parse(q) != null) { users = emptyList(); groups = emptyList(); searching = false; lastSearched = null; AddSheet.resultsFor = null; return@LaunchedEffect }
        // Same query, results already on screen: nothing to search (#615).
        if (q == lastSearched && (users.isNotEmpty() || groups.isNotEmpty())) { searching = false; return@LaunchedEffect }
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
            lastSearched = q
            AddSheet.resultsFor = q; AddSheet.users = users; AddSheet.groups = groups
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        users = session.searchUsers(q).filter { it.uin != session.uin }
        // Don't surface CLOSED groups in open search — they're not joinable this
        // way (join only via invite link); iOS already hides them (#11).
        groups = session.searchGroups(q).filter { !it.is_closed }
        searching = false
        lastSearched = q
        AddSheet.resultsFor = q; AddSheet.users = users; AddSheet.groups = groups
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
    // ⏭ THE KEYBOARD IS STILL WRONG HERE (#867), and this is what an evening on
    // a device actually established, so the next attempt does not start over.
    //
    // Both reported symptoms REPRODUCE on API 35, every time:
    //   * dragged to the top, opening the keyboard drops the sheet back to the
    //     half anchor (top edge measured at y=0 before, y=520 after);
    //   * ONE back press hides the keyboard AND dismisses the sheet, so you
    //     cannot put the keyboard away and keep looking at your results.
    //
    // ⚠⚠ What did NOT work, so nobody spends another evening on it:
    //   * `LaunchedEffect(imeUp) { sheetState.expand() }` plus a
    //     `BackHandler(enabled = imeUp)`, with `imeUp` read as
    //     `WindowInsets.ime.getBottom(density) > 0`. Placed BESIDE the sheet it
    //     is obviously wrong (the keyboard belongs to the sheet's own window),
    //     but placed INSIDE the sheet content it still reads zero: neither the
    //     expand nor the back handler ever fired, verified by both symptoms
    //     surviving unchanged. `contentWindowInsets = rcqSheetInsets` alongside
    //     made no visible difference either.
    //   * So the next thing to try is a different source of truth for "the
    //     keyboard is up" in a sheet window — Material3's own
    //     `WindowInsets.isImeVisible`, or the inset read before this sheet
    //     consumes it — not another arrangement of the same reads.
    //
    // The reverted attempt is not in the tree on purpose: half-working code on
    // a surface this fragile is worse than none. `skipPartiallyExpanded` is
    // still not the answer, because the half-open opening position IS #524.
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
                                    pv != null -> memberCountLabel(pv.member_count)
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
                            AddResultRow(known?.nickname?.takeIf { it.isNotBlank() } ?: "$digits", stringResource(R.string.add_on_own_island, session.currentServer), accent = true) {
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
                                            Session.CiAdd.CLOSED_ISLAND ->
                                                android.widget.Toast.makeText(context, context.getString(R.string.ci_closed_island), android.widget.Toast.LENGTH_LONG).show()
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
                            AddResultRow("${u.uin}", sub) {
                                // Contact → open chat; not yet a contact → open the
                                // profile preview where you can send the request.
                                if (already) onOpenChat(u.uin) else onOpenProfile(u.uin)
                            }
                        }
                        groups.forEach { g ->
                            AddResultRow(
                                g.name ?: "${g.id}",
                                memberCountLabel(g.member_count),
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
                    preview.name?.takeIf { it.isNotBlank() } ?: "${preview.id}",
                    color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    memberCountLabel(preview.member_count),
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
private fun AddAccountDialog(onAdd: (String?) -> Unit, onRestore: () -> Unit, onDismiss: () -> Unit) {
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
            // The same deck of islands onboarding draws. Adding an account is
            // the same question -- which island -- and this sheet was asking it
            // with a bare host field, so the catalogue existed in one place and
            // not the other (founder, 24.08).
            val ctxLocal = androidx.compose.ui.platform.LocalContext.current
            val islands by produceState(initialValue = app.rcq.android.data.IslandCatalog.cached().orEmpty()) {
                value = app.rcq.android.data.IslandCatalog.load(ctxLocal)
            }
            var manual by remember { mutableStateOf(false) }
            if (!manual && islands.isNotEmpty()) {
                IslandCarousel(current = "", islands = islands, onPick = { onAdd(it) })
                // Two doors under the deck, the same pair iOS offers: type an
                // address, or bring an account that already exists. Restoring
                // by phrase lived only in onboarding and in account management,
                // so somebody adding an account from here had no way to say "I
                // already have one" (founder, 24.08).
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AddAccountDoor(
                        title = stringResource(R.string.restore_title),
                        modifier = Modifier.weight(1f),
                        onClick = onRestore,
                    )
                    AddAccountDoor(
                        title = stringResource(R.string.island_manual_entry),
                        modifier = Modifier.weight(1f),
                        onClick = { manual = true },
                    )
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
                return@Column
            }
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
                    // The `#fp` fragment is judged BEFORE anything is dialled
                    // (design §3): not a fingerprint, or on a host that is
                    // never pinned, is an address error here; a fragment the
                    // store disagrees with raises the banner on the main
                    // screen and stops here too. What goes on to the gate
                    // and the registration is the bare host:port, with the
                    // typed pin already on file.
                    val entry = app.rcq.android.net.IslandTrust.adopt(host)
                    islandAddressError(ctx, entry)?.let { err = it; return@TextButton }
                    val h = (entry as? app.rcq.android.net.IslandTrust.Entry.Ok)?.hostPort
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

/** One of the two doors under the island deck: a glyph over a word, both halves
 *  the same width so neither reads as the main action. */
@Composable
private fun AddAccountDoor(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = RcqTheme.colors
    // Founder 30.08: the icon-over-label tiles read as two oversized buttons
    // crowding the deck. One line of text in a capsule says the same and
    // leaves the island the room.
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(c.bgPrimary).clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = c.textSecondary, fontSize = 13.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// ⚠⚠ Hands back CONTACTS, not bare uins. A uin alone does not say which island
// its owner lives on, and the same number belongs to a different person on
// every island — so a list of numbers is exactly the wrong thing to carry out
// of a picker that can show both kinds of row.
private fun CreateGroupDialog(contacts: List<Contact>, onCreate: (String, List<Contact>) -> Unit, onDismiss: () -> Unit) {
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
                items(contacts, key = { contactKey("grp", it) }) { ct ->
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
                val picked = selected.filterValues { it }.keys.toSet()
                val members = contacts.filter { it.uin in picked }
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
internal fun ReportDialog(name: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
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
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
