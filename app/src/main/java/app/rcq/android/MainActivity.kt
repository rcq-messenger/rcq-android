package app.rcq.android

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.data.LocalStores
import app.rcq.android.net.RcqApi
import app.rcq.android.ui.CapsuleButton
import app.rcq.android.ui.ChatScreen
import app.rcq.android.ui.ChatTarget
import app.rcq.android.ui.ContactInfoScreen
import app.rcq.android.ui.GroupInfoScreen
import app.rcq.android.ui.HomeScreen
import app.rcq.android.ui.ManageAccountsScreen
import app.rcq.android.ui.OnboardingScreen
import app.rcq.android.ui.ProfileEditScreen
import app.rcq.android.ui.RcqTheme
import app.rcq.android.ui.SettingsScreen
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.launch

/** A pending server-join from a scanned/opened `rcq://server/<host>?invite=<code>`
 *  deep link. The Activity parses incoming intents into this; RcqApp observes it
 *  and shows a confirm dialog. (Scanning the QR with any camera fires the VIEW
 *  intent for the custom scheme — no in-app scanner needed.) */
object ServerJoinLink {
    data class Req(val host: String, val invite: String?)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Req?>(null)

    fun fromUri(uri: android.net.Uri?): Req? {
        if (uri == null || uri.scheme != "rcq" || uri.host != "server") return null
        val host = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        return Req(host, uri.getQueryParameter("invite"))
    }
}

/** A pending connect-to-web from a scanned `rcq://link?t=<token>&k=<webEphPub>`
 *  QR (shown on chat.rcq.app). Parsed from the VIEW intent like [ServerJoinLink];
 *  RcqApp shows a confirm dialog, then seals this account into the relay slot so
 *  the web logs in as the same identity. */
object WebLinkRequest {
    data class Req(val token: String, val webPub: String)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Req?>(null)

    fun fromUri(uri: android.net.Uri?): Req? {
        if (uri == null || uri.scheme != "rcq" || uri.host != "link") return null
        val token = uri.getQueryParameter("t")?.takeIf { it.isNotBlank() } ?: return null
        val webPub = uri.getQueryParameter("k")?.takeIf { it.isNotBlank() } ?: return null
        return Req(token, webPub)
    }
}

// FragmentActivity (not the bare ComponentActivity) so BiometricPrompt can host
// its dialog for the panic-PIN biometric unlock. FragmentActivity is itself a
// ComponentActivity, so setContent / enableEdgeToEdge still apply.
class MainActivity : androidx.fragment.app.FragmentActivity() {
    private lateinit var session: Session
    // android.app.Activity.ScreenCaptureCallback on API 34+ (held as Any? so the
    // API-34 type is never referenced in a field signature on older devices).
    private var screenCaptureCallback: Any? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        // Apply the user's chosen app language before any resources resolve.
        super.attachBaseContext(app.rcq.android.data.LanguageManager.wrap(newBase))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ServerJoinLink.fromUri(intent.data)?.let { ServerJoinLink.pending.value = it }
        WebLinkRequest.fromUri(intent.data)?.let { WebLinkRequest.pending.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerJoinLink.fromUri(intent?.data)?.let { ServerJoinLink.pending.value = it }
        WebLinkRequest.fromUri(intent?.data)?.let { WebLinkRequest.pending.value = it }
        enableEdgeToEdge()
        app.rcq.android.data.LanguageManager.init(applicationContext)
        LocalStores.init(applicationContext)
        app.rcq.android.data.VisitStore.init(applicationContext)
        app.rcq.android.media.SoundService.init(applicationContext)
        // Load the account roster (migrating a pre-multi-account install to
        // Account[0]) before binding the active account's per-account stores
        // and building the session against it.
        app.rcq.android.data.AccountManager.init(applicationContext)
        val activeAccountId = app.rcq.android.data.AccountManager.activeId.value
        LocalStores.bindAccount(activeAccountId)
        app.rcq.android.data.VisitStore.bindAccount(activeAccountId)
        session = Session(applicationContext)
        // Apply screenshot-blocking before the first frame if it's already on.
        if (LocalStores.screenSecurityOn()) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            val mode by LocalStores.themeMode.collectAsState()
            val secure by LocalStores.screenSecurity.collectAsState()
            LaunchedEffect(secure) {
                if (secure) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            RcqTheme(mode) { RcqApp(session) }
        }
    }

    // Notify-mode secure chats (iOS parity): on Android 14+ detect a screenshot
    // and let Session tell the peer. Registered only while the activity is
    // visible. Older Android has no reliable screenshot-detection API.
    override fun onStart() {
        super.onStart()
        if (android.os.Build.VERSION.SDK_INT >= 34) registerScreenshotDetector()
    }

    override fun onStop() {
        if (android.os.Build.VERSION.SDK_INT >= 34) unregisterScreenshotDetector()
        super.onStop()
    }

    @androidx.annotation.RequiresApi(34)
    private fun registerScreenshotDetector() {
        val cb = (screenCaptureCallback as? android.app.Activity.ScreenCaptureCallback)
            ?: android.app.Activity.ScreenCaptureCallback {
                if (::session.isInitialized) session.onLocalScreenshot()
            }.also { screenCaptureCallback = it }
        runCatching { registerScreenCaptureCallback(mainExecutor, cb) }
    }

    @androidx.annotation.RequiresApi(34)
    private fun unregisterScreenshotDetector() {
        (screenCaptureCallback as? android.app.Activity.ScreenCaptureCallback)?.let {
            runCatching { unregisterScreenCaptureCallback(it) }
        }
    }
}

private sealed interface UiState {
    data object Onboarding : UiState
    data object Registering : UiState
    data class Registered(val uin: Int) : UiState
    data class Failed(val message: String) : UiState
}

@Composable
private fun RcqApp(session: Session) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var state by remember {
        mutableStateOf<UiState>(session.uin?.let { UiState.Registered(it) } ?: UiState.Onboarding)
    }
    // Panic-PIN lock gate: while locked, the message DB stays closed and the
    // lock screen replaces the Registered UI (see the `when` below).
    val locked by app.rcq.android.security.PanicPinService.locked.collectAsState()
    var chatTarget by remember { mutableStateOf<ChatTarget?>(null) }
    var groupInfoId by remember { mutableStateOf<Int?>(null) }
    var peerInfoUin by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showManageAccounts by remember { mutableStateOf(false) }
    var showNews by remember { mutableStateOf(false) }
    var showRandom by remember { mutableStateOf(false) }
    var showAudioRooms by remember { mutableStateOf(false) }
    var showNearby by remember { mutableStateOf(false) }
    var showRadio by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var showOutgoing by remember { mutableStateOf(false) }

    LaunchedEffect(state, locked) {
        // Only start (which opens the message DB) once unlocked.
        if (state is UiState.Registered && !locked) session.start()
    }

    // Clear every secondary screen so a switch/add lands on a clean Home.
    fun resetNav() {
        chatTarget = null; groupInfoId = null; peerInfoUin = null
        showSettings = false; showProfile = false; showManageAccounts = false; showNews = false; showRandom = false; showAudioRooms = false; showNearby = false; showRadio = false; showRestore = false; showOutgoing = false
    }

    fun register(server: String? = null, invite: String? = null) {
        state = UiState.Registering
        scope.launch {
            state = try {
                UiState.Registered(session.registerNewAccount("user-${(1000..9999).random()}", server, invite))
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Registration failed")
            }
        }
    }

    // Add a further account from the switcher. Register-first means a
    // failure leaves the current account intact, so we surface a toast and
    // stay put rather than dropping to the full-screen Failed state.
    fun addAccount(server: String? = null, invite: String? = null) {
        val current = (state as? UiState.Registered)?.uin
        resetNav()
        state = UiState.Registering
        scope.launch {
            state = try {
                UiState.Registered(session.registerNewAccount("user-${(1000..9999).random()}", server, invite))
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't add account", Toast.LENGTH_LONG).show()
                current?.let { UiState.Registered(it) } ?: UiState.Onboarding
            }
        }
    }

    fun switchAccount(id: String) {
        resetNav()
        state = UiState.Registering
        scope.launch { state = UiState.Registered(session.switchToAccount(id)) }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(RcqTheme.colors.bgPrimary).systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val s = state
        val target = chatTarget
        val infoId = groupInfoId
        val peerInfo = peerInfoUin
        // Hardware/system Back pops the topmost in-app screen (same precedence
        // as the `when` below) instead of finishing the Activity. On Home with
        // nothing open it's disabled, so Back exits the app as usual.
        val backPopsOverlay = (s is UiState.Registered && !locked && (
            groupInfoId != null || peerInfoUin != null || chatTarget != null ||
                showManageAccounts || showNews || showOutgoing || showRandom ||
                showAudioRooms || showNearby || showRadio || showProfile || showSettings || showRestore
            )) || (s is UiState.Onboarding && showRestore)
        BackHandler(enabled = backPopsOverlay) {
            when {
                // peerInfo first to match the render precedence (a profile
                // opened from group-info sits on top of it).
                peerInfoUin != null -> peerInfoUin = null
                groupInfoId != null -> groupInfoId = null
                chatTarget != null -> chatTarget = null
                showManageAccounts -> showManageAccounts = false
                showNews -> showNews = false
                showOutgoing -> showOutgoing = false
                showRandom -> showRandom = false
                showAudioRooms -> showAudioRooms = false
                showNearby -> showNearby = false
                showRadio -> showRadio = false
                showProfile -> showProfile = false
                showSettings -> showSettings = false
                showRestore -> showRestore = false
            }
        }
        when {
            s is UiState.Registered && locked -> app.rcq.android.ui.PinLockScreen(
                session,
                onWiped = { resetNav(); state = UiState.Onboarding },
                onAccountChanged = { newUin -> resetNav(); state = UiState.Registered(newUin) },
            )
            // Add an account by recovery phrase (from onboarding OR from the
            // account-management screen). recoverAccount() adds a NEW local
            // account slot and switches to it, so onRestored lands on its Home.
            !locked && showRestore -> app.rcq.android.ui.RestoreScreen(
                session,
                onBack = { showRestore = false },
                onRestored = { uin -> resetNav(); state = UiState.Registered(uin) },
            )
            // peerInfo is checked BEFORE groupInfo so that opening a member's
            // profile FROM the group-info screen (which leaves groupInfoId set)
            // shows the profile, and backing out of it returns to group-info.
            s is UiState.Registered && peerInfo != null -> ContactInfoScreen(
                session, peerInfo,
                onBack = { peerInfoUin = null },
                onRemoved = { peerInfoUin = null; chatTarget = null },
            )
            s is UiState.Registered && infoId != null -> GroupInfoScreen(
                session, infoId,
                onBack = { groupInfoId = null },
                onLeft = { groupInfoId = null; chatTarget = null },
                onOpenPeerInfo = { peerInfoUin = it },
            )
            s is UiState.Registered && target != null -> ChatScreen(
                session, target,
                onBack = { chatTarget = null },
                onOpenGroupInfo = { groupInfoId = it },
                onOpenPeerInfo = { peerInfoUin = it },
                onOpenGroup = { chatTarget = ChatTarget.Group(it) },
            )
            s is UiState.Registered && showManageAccounts -> ManageAccountsScreen(
                session,
                onBack = { showManageAccounts = false },
                onAddBySeed = { showManageAccounts = false; showRestore = true },
            )
            s is UiState.Registered && showNews -> app.rcq.android.ui.NewsScreen(
                session,
                onBack = { showNews = false },
            )
            s is UiState.Registered && showOutgoing -> app.rcq.android.ui.OutgoingRequestsScreen(
                session,
                onBack = { showOutgoing = false },
            )
            s is UiState.Registered && showRandom -> app.rcq.android.ui.RandomScreen(
                session,
                onBack = { showRandom = false },
            )
            s is UiState.Registered && showAudioRooms -> app.rcq.android.ui.AudioRoomsScreen(
                session,
                onBack = { showAudioRooms = false },
            )
            s is UiState.Registered && showNearby -> app.rcq.android.ui.NearbyScreen(
                session,
                onBack = { showNearby = false },
            )
            s is UiState.Registered && showRadio -> app.rcq.android.ui.RadioScreen(
                session,
                onBack = { showRadio = false },
            )
            s is UiState.Registered && showProfile -> ProfileEditScreen(
                session,
                onBack = { showProfile = false },
            )
            s is UiState.Registered && showSettings -> SettingsScreen(
                session, s.uin,
                onBack = { showSettings = false },
                onBurned = { next -> resetNav(); state = next?.let { UiState.Registered(it) } ?: UiState.Onboarding },
                onMigrated = { newUin -> chatTarget = null; state = UiState.Registered(newUin) },
            )
            s is UiState.Registered -> HomeScreen(
                session, s.uin,
                onOpenChat = { chatTarget = ChatTarget.Peer(it) },
                onOpenGroup = { chatTarget = ChatTarget.Group(it) },
                onOpenSettings = { showSettings = true },
                onOpenProfile = { showProfile = true },
                onOpenNews = { showNews = true },
                onOpenOutgoing = { showOutgoing = true },
                onOpenSaved = { session.uin?.let { chatTarget = ChatTarget.Peer(it) } },
                onOpenAudioRooms = { showAudioRooms = true },
                onOpenNearby = { showNearby = true },
                onOpenRadio = { showRadio = true },
                onOpenRandom = { showRandom = true },
                onSwitchAccount = ::switchAccount,
                onAddAccount = ::addAccount,
                onManageAccounts = { showManageAccounts = true },
            )
            s is UiState.Onboarding -> OnboardingScreen(onStart = ::register, onRestore = { showRestore = true })
            s is UiState.Registering -> Registering()
            s is UiState.Failed -> Failed(s.message, onRetry = { register(null) })
        }

        // Active 1:1 call overlay, drawn above everything while registered +
        // unlocked. Incoming calls only ring here while the app is alive (no
        // FCM/VoIP push yet).
        val callState by session.calls.state.collectAsState()
        if (s is UiState.Registered && !locked && callState !is app.rcq.android.call.CallController.State.Idle) {
            app.rcq.android.ui.CallScreen(session.calls)
        }

        // In-app update prompt: the APK ships from the website, so we self-check
        // a version manifest once per launch and offer a one-tap update.
        var update by remember { mutableStateOf<app.rcq.android.net.UpdateChecker.Update?>(null) }
        val updateDownload by app.rcq.android.net.UpdateChecker.downloadState.collectAsState()
        LaunchedEffect(s is UiState.Registered) {
            if (s is UiState.Registered) {
                app.rcq.android.net.UpdateChecker.cleanupOldApks(context)
                update = app.rcq.android.net.UpdateChecker.check()
            }
        }
        update?.let { up ->
            if (s is UiState.Registered && !locked) UpdateDialog(
                update = up,
                downloadState = updateDownload,
                onUpdate = { app.rcq.android.net.UpdateChecker.startDownload(context, up) },
                onDismiss = { update = null },
            )
        }

        // A crash captured on the previous run (CrashReporter). Offered to the
        // user with EXPLICIT consent — RCQ never uploads it silently (privacy
        // posture). The report is technical only (stack + device), no content.
        var crashReport by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(s is UiState.Registered) {
            if (s is UiState.Registered) {
                // Suspected-NATIVE launch-crash breadcrumbs are content-free and
                // auto-submitted by Session (the consent prompt can't show during
                // a crash loop) — skip them here so we only prompt for real JVM
                // crash stacks.
                crashReport = CrashReporter.pending(context)?.takeUnless { it.startsWith("RCQ launch crash") }
            }
        }
        crashReport?.let { report ->
            if (s is UiState.Registered && !locked) CrashConsentDialog(
                onSend = {
                    scope.launch { runCatching { session.submitBugReport("[CRASH]\n$report") } }
                    CrashReporter.clear(context)
                    crashReport = null
                },
                onDismiss = {
                    CrashReporter.clear(context)
                    crashReport = null
                },
            )
        }

        // Thin progress strip at the very top while an update downloads — so
        // closing the dialog "minimizes" the download here instead of blocking.
        (updateDownload as? app.rcq.android.net.UpdateChecker.DownloadState.Active)?.let { a ->
            val barMod = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp)
            if (a.progress < 0f) androidx.compose.material3.LinearProgressIndicator(color = RcqTheme.colors.accent, modifier = barMod)
            else androidx.compose.material3.LinearProgressIndicator(progress = { a.progress }, color = RcqTheme.colors.accent, modifier = barMod)
        }

        // Server-join from a scanned rcq://server/<host>?invite=<code> deep link:
        // confirm, then register a fresh account on that host with the invite.
        val joinReq by ServerJoinLink.pending.collectAsState()
        joinReq?.let { req ->
            if (!locked) ServerJoinDialog(
                host = req.host,
                hasInvite = !req.invite.isNullOrBlank(),
                onConfirm = {
                    ServerJoinLink.pending.value = null
                    if (s is UiState.Onboarding) register(req.host, req.invite) else addAccount(req.host, req.invite)
                },
                onDismiss = { ServerJoinLink.pending.value = null },
            )
        }

        // Connect-to-web from a scanned rcq://link?t=&k= QR (shown on
        // chat.rcq.app). Only meaningful once registered. Confirm first — it
        // hands web access to THIS account — then seal the account into the
        // one-time relay slot for the web to pick up + log in.
        val linkReq by WebLinkRequest.pending.collectAsState()
        if (s is UiState.Registered && !locked) {
            linkReq?.let { req ->
                WebLinkDialog(
                    onConfirm = {
                        WebLinkRequest.pending.value = null
                        scope.launch {
                            val ok = runCatching { session.linkWeb(req.token, req.webPub) }.isSuccess
                            Toast.makeText(
                                context,
                                context.getString(if (ok) R.string.weblink_done else R.string.weblink_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    onDismiss = { WebLinkRequest.pending.value = null },
                )
            }
        }
    }
}

@Composable
private fun WebLinkDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.weblink_title), color = c.textPrimary) },
        text = { Text(stringResource(R.string.weblink_body), color = c.textSecondary, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.weblink_confirm), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
    )
}

@Composable
private fun ServerJoinDialog(host: String, hasInvite: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.join_server_title), color = c.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.join_server_body, host), color = c.textSecondary, fontSize = 14.sp)
                if (hasInvite) Text(stringResource(R.string.join_server_invite), color = c.accent, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.join_server_join), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
    )
}

@Composable
private fun UpdateDialog(
    update: app.rcq.android.net.UpdateChecker.Update,
    downloadState: app.rcq.android.net.UpdateChecker.DownloadState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    val active = downloadState as? app.rcq.android.net.UpdateChecker.DownloadState.Active
    AlertDialog(
        // Always dismissible: the download is process-level and keeps going.
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.update_title, update.versionName), color = c.textPrimary) },
        text = {
            // Cap the height and scroll — long patch notes used to stretch the
            // dialog so tall the buttons were pushed off-screen (founder report).
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            ) {
                if (update.notes.isNotBlank()) Text(update.notes, color = c.textSecondary, fontSize = 14.sp)
                if (active != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (active.progress < 0f) androidx.compose.material3.LinearProgressIndicator(color = c.accent, modifier = Modifier.fillMaxWidth())
                        else androidx.compose.material3.LinearProgressIndicator(progress = { active.progress }, color = c.accent, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.update_downloading_pct, (active.progress.coerceAtLeast(0f) * 100).toInt()), color = c.textSecondary, fontSize = 13.sp)
                        Text(stringResource(R.string.update_bg_hint), color = c.textSecondary, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (active == null) TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_install), color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (active != null) R.string.common_close else R.string.update_later), color = c.textSecondary)
            }
        },
    )
}


@Composable
private fun CrashConsentDialog(onSend: () -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.crash_consent_title), color = c.textPrimary) },
        text = { Text(stringResource(R.string.crash_consent_body), color = c.textSecondary, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = onSend) { Text(stringResource(R.string.crash_consent_send), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.crash_consent_dismiss), color = c.textSecondary) } },
    )
}

@Composable
private fun Registering() {
    val c = RcqTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = c.accent)
        Text("Creating your account…", color = c.textSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit) {
    val c = RcqTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Text("Couldn't connect", color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = c.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        CapsuleButton("Try again", onClick = onRetry)
    }
}
