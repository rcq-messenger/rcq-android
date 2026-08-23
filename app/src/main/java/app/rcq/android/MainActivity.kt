package app.rcq.android

import android.os.Build
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import app.rcq.android.ui.ChatLockGate
import app.rcq.android.ui.ChatScreen
import app.rcq.android.ui.ChatTarget
import app.rcq.android.ui.ContactInfoScreen
import app.rcq.android.ui.GroupInfoScreen
import app.rcq.android.ui.HomeScreen
import app.rcq.android.ui.ManageAccountsScreen
import app.rcq.android.ui.OnboardingScreen
import app.rcq.android.ui.ProfileEditScreen
import app.rcq.android.ui.RcqAskSheet
import app.rcq.android.ui.RcqSheet
import app.rcq.android.ui.SheetAction
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

/** A pending add-contact from a scanned/opened contact link — `rcq://add/<uin>`
 *  or `https://rcq.app/u/<uin>`, with the spec-§5 federation query `?h=<island>`
 *  (k/i are advisory and ignored here: the add flow fetches the peer's key card
 *  from their island anyway). RcqApp confirms, then routes: cross-island host →
 *  addCrossIslandContact + open the chat; same island → contact request. */
object ContactAddLink {
    data class Req(val uin: Int, val host: String?)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Req?>(null)

    fun fromUri(uri: android.net.Uri?): Req? {
        if (uri == null) return null
        // Both spellings of the same intent. /u/ is "add this contact", /r/ is
        // "a friend invited you" — the second is what someone who does not have
        // RCQ yet is sent, and until now no client claimed that link at all, so
        // tapping an invite opened a browser and stopped there.
        val isRcq = uri.scheme == "rcq" && (uri.host == "add" || uri.host == "r")
        val isWeb = (uri.scheme == "https" || uri.scheme == "http") &&
            uri.host == "rcq.app" && uri.pathSegments.firstOrNull() in setOf("u", "r")
        if (!isRcq && !isWeb) return null
        val uin = uri.lastPathSegment?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Req(uin, uri.getQueryParameter("h")?.takeIf { it.isNotBlank() })
    }
}

/** A pending connect-to-web from a scanned `rcq://link?t=<token>&k=<webEphPub>`
 *  QR (shown on chat.rcq.app). Parsed from the VIEW intent like [ServerJoinLink];
 *  RcqApp shows a confirm dialog, then seals this account into the relay slot so
 *  the web logs in as the same identity. */
object WebLinkRequest {
    /** [clientLabel] = the QR's `c` param ("Desktop"/"Web") shown in the phone's
     *  Linked-devices list; "Web" for old QRs that carry no hint. */
    data class Req(val token: String, val webPub: String, val clientLabel: String = "Web")
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Req?>(null)

    fun fromUri(uri: android.net.Uri?): Req? {
        if (uri == null) return null
        val isRcq = uri.scheme == "rcq" && uri.host == "link"
        // https://rcq.app/link?t=…&k=… — the camera-friendly form (stock cameras
        // often won't deep-link the rcq:// scheme but do open https).
        val isWeb = (uri.scheme == "https" || uri.scheme == "http") &&
            uri.host == "rcq.app" && uri.pathSegments.firstOrNull() == "link"
        if (!isRcq && !isWeb) return null
        val token = uri.getQueryParameter("t")?.takeIf { it.isNotBlank() } ?: return null
        val webPub = uri.getQueryParameter("k")?.takeIf { it.isNotBlank() } ?: return null
        val label = uri.getQueryParameter("c")?.trim()?.takeIf { it.isNotBlank() }?.take(24) ?: "Web"
        return Req(token, webPub, label)
    }
}

/** A pending group-join from an opened invite link — `rcq://group/<id>@<host>`
 *  or `https://rcq.app/g/<id>@<host>`. Reuses the in-chat GroupLinkParser. RcqApp
 *  confirms, then joins: foreign host → joinForeignGroup (guest-registers there),
 *  same island → joinGroup; then opens the group chat. The privacy-safe path —
 *  the user holds the link (no discovery). */
object GroupJoinLink {
    data class Req(val id: Int, val host: String?)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Req?>(null)

    fun fromUri(uri: android.net.Uri?): Req? {
        val ref = app.rcq.android.ui.GroupLinkParser.parse(uri?.toString() ?: return null) ?: return null
        return Req(ref.id, ref.host)
    }
}

/** Content another app handed us through the system share sheet
 *  (ACTION_SEND / ACTION_SEND_MULTIPLE).
 *
 *  RCQ was missing from that sheet entirely, so "share this picture to RCQ"
 *  did not exist and moving a picture between two RCQ chats meant saving it to
 *  storage and re-attaching it with the paperclip (report #443).
 *
 *  Two hops, because a share has to choose a thread before it can be sent:
 *  [pending] drives the "Send to…" picker, and once a chat is picked the same
 *  payload moves to [deliver], which ChatScreen consumes on open and pushes
 *  through the ordinary attachment paths.
 *
 *  ⚠ The URI grants that come with the intent live only as long as this task
 *  holds it, so nothing here may be parked for later — the payload is read the
 *  moment the chat opens, in the same session as the tap. */
object ShareIntake {
    data class Req(val text: String?, val uris: List<android.net.Uri>)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Req?>(null)
    val deliver = kotlinx.coroutines.flow.MutableStateFlow<Req?>(null)

    fun fromIntent(i: android.content.Intent?): Req? {
        i ?: return null
        val uris = when (i.action) {
            android.content.Intent.ACTION_SEND ->
                listOfNotNull(streamExtra(i))
            android.content.Intent.ACTION_SEND_MULTIPLE ->
                streamListExtra(i)
            else -> return null
        }
        val text = i.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (uris.isEmpty() && text == null) return null
        // Consume, for the same reason NotificationOpen does: setIntent keeps
        // the intent sticky, and any later recreate() (a language switch) would
        // otherwise re-open the picker for something already sent.
        i.removeExtra(android.content.Intent.EXTRA_STREAM)
        i.removeExtra(android.content.Intent.EXTRA_TEXT)
        return Req(text, uris)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: android.content.Intent): android.net.Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            i.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun streamListExtra(i: android.content.Intent): List<android.net.Uri> =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            i.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
        }.orEmpty()
}

/** A pending open-this-thread request from a tapped message notification.
 *  [Push.showMessage] stamps the wake's group_id/to_uin — plus the decrypted
 *  sender, when the envelope could be opened — as intent extras; RcqApp
 *  consumes this once registered + unlocked, switching to the target account
 *  first when the wake was for a non-active local account. */
object NotificationOpen {
    data class Req(val groupId: Int?, val toUin: Int?, val reports: Boolean = false, val peerUin: Int? = null, val devices: Boolean = false)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Req?>(null)

    fun fromIntent(i: android.content.Intent?): Req? {
        i ?: return null
        val g = i.getIntExtra(app.rcq.android.push.Push.EXTRA_OPEN_GROUP_ID, -1).takeIf { it > 0 }
        val u = i.getIntExtra(app.rcq.android.push.Push.EXTRA_OPEN_TO_UIN, -1).takeIf { it > 0 }
        val rep = i.getBooleanExtra(app.rcq.android.push.Push.EXTRA_OPEN_REPORTS, false)
        val p = i.getIntExtra(app.rcq.android.push.Push.EXTRA_OPEN_PEER_UIN, -1).takeIf { it > 0 }
        val dev = i.getBooleanExtra(app.rcq.android.push.Push.EXTRA_OPEN_DEVICES, false)
        // Consume: setIntent keeps this intent sticky, so without removing the
        // extras any later activity re-create (language switch calls
        // recreate()) would re-fire the navigation and yank the user back
        // into the thread they had already left.
        i.removeExtra(app.rcq.android.push.Push.EXTRA_OPEN_GROUP_ID)
        i.removeExtra(app.rcq.android.push.Push.EXTRA_OPEN_TO_UIN)
        i.removeExtra(app.rcq.android.push.Push.EXTRA_OPEN_REPORTS)
        i.removeExtra(app.rcq.android.push.Push.EXTRA_OPEN_PEER_UIN)
        i.removeExtra(app.rcq.android.push.Push.EXTRA_OPEN_DEVICES)
        return if (g != null || u != null || rep || p != null || dev) Req(g, u, rep, p, dev) else null
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
        ContactAddLink.fromUri(intent.data)?.let { ContactAddLink.pending.value = it }
        GroupJoinLink.fromUri(intent.data)?.let { GroupJoinLink.pending.value = it }
        NotificationOpen.fromIntent(intent)?.let { NotificationOpen.pending.value = it }
        ShareIntake.fromIntent(intent)?.let { ShareIntake.pending.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Arm the launch-crash breadcrumb only when real UI starts (headless
        // process warmups must not arm it), and cap the danger window at 8s:
        // entries that never compose HomeScreen (notification straight into a
        // chat) otherwise kept the crumb armed for the whole session, turning
        // every later OS kill into a phantom crash report.
        CrashReporter.crumb(this, "activity_create")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { CrashReporter.launchComplete(applicationContext) }, 8_000,
        )
        ServerJoinLink.fromUri(intent?.data)?.let { ServerJoinLink.pending.value = it }
        WebLinkRequest.fromUri(intent?.data)?.let { WebLinkRequest.pending.value = it }
        ContactAddLink.fromUri(intent?.data)?.let { ContactAddLink.pending.value = it }
        GroupJoinLink.fromUri(intent?.data)?.let { GroupJoinLink.pending.value = it }
        NotificationOpen.fromIntent(intent)?.let { NotificationOpen.pending.value = it }
        ShareIntake.fromIntent(intent)?.let { ShareIntake.pending.value = it }
        enableEdgeToEdge()
        app.rcq.android.data.LanguageManager.init(applicationContext)
        LocalStores.init(applicationContext)
        app.rcq.android.net.SingBoxTransport.init(applicationContext)
        app.rcq.android.net.ContactRelayStore.init(applicationContext)
        app.rcq.android.net.BrokerRelayStore.init(applicationContext)
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
        // Push: request the notification runtime permission (API 33+) and ask
        // the active UnifiedPush distributor (ntfy, …) for an endpoint if one is
        // set up. A device with no distributor simply gets no push (degrades to
        // the foreground-only behaviour) — non-intrusive, never forces a picker.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0,
            )
        }
        app.rcq.android.push.Push.registerDistributor(this)
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
            // #3 Accessibility: apply the in-app text-size multiplier on TOP of
            // the OS font scale by overriding LocalDensity. Every `.sp` text in
            // the app scales from this single wrapper.
            val fontScale by LocalStores.fontScale.collectAsState()
            val base = androidx.compose.ui.platform.LocalDensity.current
            // In-app browser: every LinkAnnotation.Url auto-open and explicit
            // uriHandler.openUri in the app resolves through InAppBrowser
            // (Chrome Custom Tab for the web, ACTION_VIEW for deep links).
            val uriHandler = remember {
                object : androidx.compose.ui.platform.UriHandler {
                    override fun openUri(uri: String) =
                        app.rcq.android.ui.InAppBrowser.open(this@MainActivity, uri)
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, base.fontScale * fontScale),
                androidx.compose.ui.platform.LocalUriHandler provides uriHandler,
            ) {
                RcqTheme(mode) { RcqApp(session) }
            }
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
    // Pre-own-store decoy still in the vault: one screen, after the real PIN.
    val decoyMigrationDue by session.decoyMigrationDue.collectAsState()
    var chatTarget by remember { mutableStateOf<ChatTarget?>(null) }
    // The pending update. Declared up here with the other screen state because
    // the home header's badge sits above the update dialog in the tree and
    // hands the found version back to it.
    var update by remember { mutableStateOf<app.rcq.android.net.UpdateChecker.Update?>(null) }
    // Thread the user just unlocked via the per-chat PIN gate; reset on leaving
    // the chat so a locked chat re-prompts every time it's opened.
    var unlockedChatThread by remember { mutableStateOf<String?>(null) }
    var groupInfoId by remember { mutableStateOf<Int?>(null) }
    var peerInfoUin by remember { mutableStateOf<Int?>(null) }
    // When the peer-info screen is opened for a CROSS-ISLAND group member, the
    // group's host — so ContactInfoScreen fetches their card from there instead
    // of our own island (which 404s). null for same-island / known contacts.
    var peerInfoHost by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsToBackupIsland by remember { mutableStateOf(false) }
    // Deep-link Settings straight to Network diagnostics (Home overflow menu).
    var settingsToDiagnostics by remember { mutableStateOf(false) }
    // Deep-link Settings straight to "My reports" (a tapped report-reply push).
    var settingsToReports by remember { mutableStateOf(false) }
    var settingsToDevices by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showManageAccounts by remember { mutableStateOf(false) }
    var showNews by remember { mutableStateOf(false) }
    var showRandom by remember { mutableStateOf(false) }
    // Random chat sends the user to the profile editor to set an age; this
    // brings them back where they were instead of dumping them on the roster.
    var profileReturnsToRandom by remember { mutableStateOf(false) }
    var showAudioRooms by remember { mutableStateOf(false) }
    var showNearby by remember { mutableStateOf(false) }
    var showRadio by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var showOutgoing by remember { mutableStateOf(false) }

    LaunchedEffect(state, locked) {
        // Only start (which opens the message DB) once unlocked.
        if (state is UiState.Registered && !locked) session.start()
    }


    // Push-woken incoming call: the user already tapped Accept on the
    // lock-screen IncomingCallActivity. Once the WS is connected, feed the
    // parked offer into the live CallController and accept it — that's the only
    // place that can run the WebRTC answer + send replies + fetch TURN. Keyed on
    // the observable acceptedCallId so a second call re-fires this even when
    // nothing else changed; bound to that exact call id + an age bound so a late
    // unlock/connect doesn't answer a different or already-dead call.
    val wsConnected by session.connected.collectAsState()
    // ⚠ The accepted id is COLLECTED here, not used as an effect key. Keying on
    // it would make this effect cancel itself: the first thing the body does is
    // clear() the very value it is keyed on, so Compose tears the coroutine down
    // mid-handoff and whether the call gets answered comes down to where the
    // suspension points happen to fall. Same shape as the Compose key race noted
    // for the transport toggle. Collecting inside an effect keyed only on the
    // gates means clear() merely delivers a null we ignore.
    LaunchedEffect(state, locked, wsConnected) {
        if (!(state is UiState.Registered && !locked && wsConnected)) return@LaunchedEffect
        app.rcq.android.call.IncomingCallStore.acceptedCallId.collect { cid ->
            if (cid == null) return@collect
            val p = app.rcq.android.call.IncomingCallStore.pending
            if (p != null && p.callId == cid) {
                app.rcq.android.call.IncomingCallStore.clear()
                // Caller rings for 60s; a parked offer older than that is dead
                // (e.g. accepted after a slow unlock) — drop it, don't ghost-connect.
                if (android.os.SystemClock.elapsedRealtime() - p.ts < 60_000L) {
                    session.calls.onPushOffer(
                        com.google.gson.JsonObject().apply {
                            addProperty("from_uin", p.fromUin)
                            addProperty("call_id", p.callId)
                            addProperty("media", p.media)
                            addProperty("sdp", p.sdp)
                        },
                    )
                }
            }
        }
    }

    // Clear every secondary screen so a switch/add lands on a clean Home.
    fun resetNav() {
        chatTarget = null; groupInfoId = null; peerInfoUin = null
        showSettings = false; settingsToDiagnostics = false; settingsToReports = false; settingsToDevices = false; settingsToBackupIsland = false; showProfile = false; showManageAccounts = false; showNews = false; showRandom = false; showAudioRooms = false; showNearby = false; showRadio = false; showRestore = false; showOutgoing = false
    }

    // #655: the island said the active account no longer exists (burned from
    // another device) — Session already wiped it locally and hot-swapped;
    // this moves the UI to wherever that landed and says why out loud.
    LaunchedEffect(Unit) {
        session.accountLost.collect { ev ->
            resetNav()
            state = ev.nextUin?.let { UiState.Registered(it) } ?: UiState.Onboarding
            Toast.makeText(context, context.getString(R.string.account_burned_elsewhere), Toast.LENGTH_LONG).show()
        }
    }

    // Kept for "Try again": retrying after a transient failure must re-use the
    // ISLAND the user picked — retrying with null silently registered a
    // self-hoster's account on the flagship.
    var lastRegisterServer: String? = null
    var lastRegisterInvite: String? = null

    fun register(server: String? = null, invite: String? = null) {
        lastRegisterServer = server
        lastRegisterInvite = invite
        state = UiState.Registering
        scope.launch {
            state = try {
                UiState.Registered(session.registerNewAccount("user-${(1000..9999).random()}", server, invite))
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Registration failed")
            }
        }
    }

    fun retryRegister() = register(lastRegisterServer, lastRegisterInvite)

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

    // A tapped message notification: open the thread it points at, switching to
    // the target account first when the wake was for a non-active local account.
    // The request STAYS pending across switchAccount (resetNav clears chatTarget)
    // and is consumed only once the right account is registered + unlocked — the
    // effect re-fires when state/locked flip, so a tap while PIN-locked routes
    // correctly after the unlock.
    val notifOpen by NotificationOpen.pending.collectAsState()
    // A share sheet handoff waits the same way a notification tap does: it sits
    // pending until there IS an account and the PIN is off, rather than being
    // dropped because the app happened to be launched cold and locked.
    val sharePending by ShareIntake.pending.collectAsState()
    LaunchedEffect(state, locked, notifOpen) {
        val req = notifOpen ?: return@LaunchedEffect
        if (state !is UiState.Registered || locked) return@LaunchedEffect
        if (req.toUin != null && req.toUin != session.uin) {
            // Find the local account owning the wake's to_uin. The active
            // account was already handled by the != check above, so a
            // cross-island numeric collision resolves active-first, then to
            // the first roster match.
            val acct = app.rcq.android.data.AccountManager.accounts.value.firstOrNull {
                app.rcq.android.data.SecureStore(context, it.id).uin == req.toUin
            }
            if (acct == null) {
                // Unknown account (stale notification after a burn) — land on Home.
                NotificationOpen.pending.value = null
                return@LaunchedEffect
            }
            if (acct.id != app.rcq.android.data.AccountManager.activeId.value) {
                switchAccount(acct.id)
                return@LaunchedEffect
            }
        }
        NotificationOpen.pending.value = null
        // ⚠ Everything else first, for BOTH deep links. Settings renders
        // BELOW an open chat, a room, a profile or an info screen in the
        // `when` that draws them, so setting the flag while one of those was
        // up did nothing at the time and then dropped the user into that
        // section when they pressed Back minutes later. Reported for the
        // report-answer notification (#716) and true of the device one too.
        if (req.reports || req.devices) {
            chatTarget = null
            groupInfoId = null
            peerInfoUin = null
            showAudioRooms = false
            showNews = false
            showRandom = false
            showNearby = false
            showRadio = false
            showProfile = false
            showManageAccounts = false
            showOutgoing = false
            settingsToReports = req.reports
            settingsToDevices = req.devices
            showSettings = true
            return@LaunchedEffect
        }
        // Group first: a group wake never carries a peer, and a peer wake never
        // carries a group, so the order only decides which wins if the server
        // ever sends both. The peer branch exists because the receiver can now
        // open the envelope and name the sender ([PushEnvelope]); wakes that
        // stayed sealed carry no peer and still land on Home, as before.
        // Explicitly, not only via the chatTarget effect: the wake may name
        // the chat that is already open under the room screen, and a value
        // set to itself does not re-run the effect.
        if (req.groupId != null || req.peerUin != null) showAudioRooms = false
        req.groupId?.let { chatTarget = ChatTarget.Group(it) }
            ?: req.peerUin?.let { chatTarget = ChatTarget.Peer(it) }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(RcqTheme.colors.bgPrimary).systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val s = state
        val target = chatTarget
        val infoId = groupInfoId
        val peerInfo = peerInfoUin
        val shareReq = sharePending
        // Hardware/system Back pops the topmost in-app screen (same precedence
        // as the `when` below) instead of finishing the Activity. On Home with
        // nothing open it's disabled, so Back exits the app as usual.
        val backPopsOverlay = (s is UiState.Registered && !locked && (
            shareReq != null || groupInfoId != null || peerInfoUin != null || chatTarget != null ||
                showManageAccounts || showNews || showOutgoing || showRandom ||
                showAudioRooms || showNearby || showRadio || showProfile || showSettings || showRestore
            )) || (s is UiState.Onboarding && showRestore)
        BackHandler(enabled = backPopsOverlay) {
            when {
                // The share picker renders above everything, so Back leaves it
                // first — and leaving it means abandoning the share, not
                // falling through to whatever was open underneath.
                shareReq != null -> ShareIntake.pending.value = null
                // The room screen renders above a chat and the profiles (see
                // the `when` below), so Back must pop it first: otherwise a
                // room opened from the strip over a chat ate one Back to close
                // the INVISIBLE chat underneath and needed a second to leave.
                showAudioRooms -> showAudioRooms = false
                // peerInfo first to match the render precedence (a profile
                // opened from group-info sits on top of it).
                peerInfoUin != null -> peerInfoUin = null
                groupInfoId != null -> groupInfoId = null
                chatTarget != null -> chatTarget = null
                showManageAccounts -> showManageAccounts = false
                showNews -> showNews = false
                showOutgoing -> showOutgoing = false
                showRandom -> showRandom = false
                showNearby -> showNearby = false
                showRadio -> showRadio = false
                showProfile -> showProfile = false
                // ⚠ The flags go with it. Leaving them set meant the NEXT
                // time Settings was opened by hand it jumped straight back
                // into the deep-linked section.
                showSettings -> {
                    showSettings = false
                    settingsToDiagnostics = false
                    settingsToReports = false
                    settingsToDevices = false
                    settingsToBackupIsland = false
                }
                showRestore -> showRestore = false
            }
        }
        // Preserve each screen's scroll (and other rememberSaveable state) across
        // the when-branch swaps. Leaving a branch tore the screen down and
        // discarded its LazyListState, so Back reset the chat list / Settings to
        // the TOP and a chat to the bottom. Keying home/chat(per-thread)/settings
        // lets re-entry restore where the user was. (NB: the composition still
        // rebuilds on return, so the slow re-sort #9 is a separate keep-composed
        // change; this fixes the scroll-position #2.)
        val callMinimizedNow by session.calls.minimized.collectAsState()
        // In a room, but not looking at it: Back leaves the SCREEN and the room
        // keeps running with the microphone open (#684). The strip says so and
        // takes one tap to get back.
        val activeRoomNow by session.audioRooms.activeRoomId.collectAsState()
        // Not over the PIN lock (its tap would be swallowed there and the
        // padding would shift the lock screen), same rule as the call overlay.
        // And never while a 1:1 call is up, full screen or minimised: the
        // call outranks the room (single-busy), and the full-screen call
        // screen is an overlay this strip used to be drawn on top of.
        val callStateNow by session.calls.state.collectAsState()
        val roomBarVisible = activeRoomNow != null && !showAudioRooms &&
            state is UiState.Registered && !locked &&
            callStateNow is app.rcq.android.call.CallController.State.Idle
        // A chat opened from a banner or a notification lands ON TOP of the
        // room screen, and the strip must be there to get back; with
        // showAudioRooms still set it would not be. Opening a chat leaves the
        // room SCREEN (not the room).
        LaunchedEffect(chatTarget) { if (chatTarget != null) showAudioRooms = false }
        val stateHolder = rememberSaveableStateHolder()
        // Everything below the minimised-call bar moves down by exactly its
        // height, so the bar never covers a screen's own header — which on the
        // chat is where Back lives.
        Box(
            Modifier.fillMaxSize().padding(
                top = if (callMinimizedNow || roomBarVisible) {
                    app.rcq.android.ui.MINIMIZED_CALL_BAR_HEIGHT
                } else {
                    0.dp
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
        when {
            s is UiState.Registered && locked -> app.rcq.android.ui.PinLockScreen(
                session,
                onWiped = { resetNav(); state = UiState.Onboarding },
                onAccountChanged = { newUin -> resetNav(); state = UiState.Registered(newUin) },
            )
            // One-time decoy rebuild (panic-PIN): shown straight after a REAL
            // unlock when a pre-own-store decoy is still in the vault. Sits
            // directly under the lock screen so it is the first thing after
            // the PIN, and is dismissible — the old decoy PIN keeps working
            // until it is finished.
            s is UiState.Registered && !locked && decoyMigrationDue ->
                app.rcq.android.ui.DecoyMigrationScreen(session, onDone = { })
            // Add an account by recovery phrase (from onboarding OR from the
            // account-management screen). recoverAccount() adds a NEW local
            // account slot and switches to it, so onRestored lands on its Home.
            !locked && showRestore -> app.rcq.android.ui.RestoreScreen(
                session,
                onBack = { showRestore = false },
                onRestored = { uin -> resetNav(); state = UiState.Registered(uin) },
            )
            // Another app shared something into RCQ: pick the thread before
            // anything else is drawn. It sits above the rest of the stack on
            // purpose — the user came here from a different app to do exactly
            // one thing, and whatever screen they had left open is not it.
            s is UiState.Registered && !locked && shareReq != null -> app.rcq.android.ui.ShareTargetScreen(
                session = session,
                req = shareReq,
                onPick = { picked ->
                    ShareIntake.pending.value = null
                    // Text goes in as a draft (the user may want to say
                    // something around a shared link); files are handed to the
                    // chat, which sends them the way the paperclip does.
                    shareReq.text?.let { app.rcq.android.ui.seedDraft(picked, it) }
                    if (shareReq.uris.isNotEmpty()) ShareIntake.deliver.value = shareReq
                    chatTarget = picked
                },
                onCancel = { ShareIntake.pending.value = null },
            )
            // ⚠ The room screen outranks a chat, a profile and the other
            // secondary screens. It used to sit below them, which made the
            // "you are in a room" strip a lie from a chat: the tap set
            // showAudioRooms, the chat branch still won, and all that happened
            // was the strip disappearing (found in review, 0.142). Up here the
            // strip always opens the room, and Back from the room returns to
            // whatever was underneath.
            s is UiState.Registered && showAudioRooms -> app.rcq.android.ui.AudioRoomsScreen(
                session,
                onBack = { showAudioRooms = false },
            )
            // peerInfo is checked BEFORE groupInfo so that opening a member's
            // profile FROM the group-info screen (which leaves groupInfoId set)
            // shows the profile, and backing out of it returns to group-info.
            s is UiState.Registered && peerInfo != null -> ContactInfoScreen(
                session, peerInfo,
                onBack = { peerInfoUin = null },
                onRemoved = { peerInfoUin = null; chatTarget = null },
                onOpenChat = { peerInfoUin = null; chatTarget = ChatTarget.Peer(it) },
                groupHost = peerInfoHost,
            )
            s is UiState.Registered && infoId != null -> GroupInfoScreen(
                session, infoId,
                onBack = { groupInfoId = null },
                onLeft = { groupInfoId = null; chatTarget = null },
                // Carry the group's host so a cross-island member's profile resolves
                // from the group's island, not ours.
                onOpenPeerInfo = { peerInfoUin = it; peerInfoHost = session.groups.value.firstOrNull { g -> g.id == infoId }?.host },
                onOpenGroup = { groupInfoId = null; chatTarget = ChatTarget.Group(it) },
            )
            s is UiState.Registered && target != null -> {
                val chatThread = when (target) {
                    is ChatTarget.Peer -> app.rcq.android.data.LocalStores.peerThread(target.uin)
                    is ChatTarget.Group -> app.rcq.android.data.LocalStores.groupThread(target.id)
                }
                val lockCtx = androidx.compose.ui.platform.LocalContext.current
                if (app.rcq.android.data.LocalStores.isLocked(chatThread) &&
                    app.rcq.android.security.PanicPinService.isConfigured(lockCtx) &&
                    unlockedChatThread != chatThread
                ) {
                    ChatLockGate(
                        onBack = { chatTarget = null },
                        onUnlocked = { unlockedChatThread = chatThread },
                    )
                } else {
                    stateHolder.SaveableStateProvider("chat:$chatThread") {
                        ChatScreen(
                            session, target,
                            onBack = { chatTarget = null; unlockedChatThread = null },
                            onOpenGroupInfo = { groupInfoId = it },
                            onOpenPeerInfo = { peerInfoUin = it; peerInfoHost = null },
                            onOpenGroup = { chatTarget = ChatTarget.Group(it); unlockedChatThread = null },
                        )
                    }
                }
            }
            s is UiState.Registered && showManageAccounts -> ManageAccountsScreen(
                session,
                onBack = { showManageAccounts = false },
                onAddBySeed = { showManageAccounts = false; showRestore = true },
                onSwitchAccount = { id -> showManageAccounts = false; switchAccount(id) },
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
                // Set-your-age leads straight into the profile editor and comes
                // back here when it closes (iOS parity).
                onEditProfile = { showRandom = false; profileReturnsToRandom = true; showProfile = true },
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
                onBack = {
                    showProfile = false
                    if (profileReturnsToRandom) { profileReturnsToRandom = false; showRandom = true }
                },
            )
            s is UiState.Registered && showSettings -> stateHolder.SaveableStateProvider("settings") {
                SettingsScreen(
                    session, s.uin,
                    onBack = { showSettings = false; settingsToDiagnostics = false; settingsToReports = false; settingsToDevices = false; settingsToBackupIsland = false },
                    onBurned = { next -> resetNav(); state = next?.let { UiState.Registered(it) } ?: UiState.Onboarding },
                    onMigrated = { newUin -> chatTarget = null; state = UiState.Registered(newUin) },
                    openDiagnostics = settingsToDiagnostics,
                    openMyReports = settingsToReports,
                    openBackupIsland = settingsToBackupIsland,
                    openLinkedDevices = settingsToDevices,
                )
            }
            s is UiState.Registered -> stateHolder.SaveableStateProvider("home") {
                HomeScreen(
                    session, s.uin,
                    onOpenChat = { chatTarget = ChatTarget.Peer(it) },
                    onOpenGroup = { chatTarget = ChatTarget.Group(it) },
                    onOpenSettings = { settingsToDiagnostics = false; settingsToReports = false; settingsToDevices = false; settingsToBackupIsland = false; showSettings = true },
                    onOpenDiagnostics = { settingsToDiagnostics = true; showSettings = true },
                    onOpenBackupIsland = { settingsToBackupIsland = true; showSettings = true },
                    // The header badge asks for the same dialog the launch
                    // check raises, at a moment the user picked.
                    onUpdateBadge = { update = it },
                    onOpenProfile = { showProfile = true },
                    onOpenPeerInfo = { peerInfoUin = it; peerInfoHost = null },
                    onOpenPeerInfoHere = { peerInfoUin = it; peerInfoHost = session.currentServer },
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
            }
            s is UiState.Onboarding -> OnboardingScreen(onStart = ::register, onRestore = { showRestore = true })
            s is UiState.Registering -> Registering()
            s is UiState.Failed -> Failed(s.message, onRetry = { retryRegister() })
        }
        }

        // Active 1:1 call overlay, drawn above everything while registered +
        // unlocked. Incoming calls only ring here while the app is alive (no
        // FCM/VoIP push yet).
        val callState by session.calls.state.collectAsState()
        val callMinimized = callMinimizedNow
        val callVisible = s is UiState.Registered && !locked &&
            callState !is app.rcq.android.call.CallController.State.Idle
        if (callVisible && !callMinimized) {
            app.rcq.android.ui.CallScreen(session.calls, session)
        }
        // The way back to a call that was put aside. Pinned to the top so it
        // is in the same place on every screen; the content below is padded
        // by the same height so it covers nothing (see `topInset`).
        if (callVisible && callMinimized) {
            app.rcq.android.ui.MinimizedCallBar(
                controller = session.calls,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        // Same strip, same place, for a room left behind. A call outranks it
        // (roomBarVisible is false while any call is up): the two cannot both
        // be live (single-busy), and if they ever are, the call is the one
        // with a countdown running.
        if (roomBarVisible) {
            app.rcq.android.ui.MinimizedRoomBar(
                controller = session.audioRooms,
                onOpen = { showAudioRooms = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // In-app update prompt: the APK ships from the website, so we self-check
        // a version manifest once per launch and offer a one-tap update.

        val updateDownload by app.rcq.android.net.UpdateChecker.downloadState.collectAsState()
        LaunchedEffect(s is UiState.Registered) {
            if (s !is UiState.Registered) return@LaunchedEffect
            app.rcq.android.net.UpdateChecker.cleanupOldApks(context)
            // Keep asking. The first answer may raise the dialog; every later
            // one only lights the header badge, which is the whole difference
            // between "the app told me" and "I happened to restart it".
            var first = true
            while (true) {
                val found = app.rcq.android.net.UpdateChecker.refresh(force = first)
                if (first && found != null) update = found
                first = false
                kotlinx.coroutines.delay(6L * 60 * 60 * 1000)
            }
        }
        update?.let { up ->
            // ⚠ Never over a call. The check runs once per launch, and a call
            // arriving in that window put this dialog on top of the ringing
            // screen — squarely over Accept, so the call could not be answered
            // at all and rang out as missed. Seen while testing #478; an update
            // notice can wait, a ringing phone cannot.
            if (s is UiState.Registered && !locked &&
                callState is app.rcq.android.call.CallController.State.Idle
            ) UpdateDialog(
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

        // In-app message banner (#11): when a message lands for a chat you're
        // NOT in, slide a tappable banner down from the top so you SEE where it
        // went (Android used to give only a sound). Auto-hides after 4s.
        if (s is UiState.Registered && !locked) {
            val banner by session.banner.collectAsState()
            // Don't show a banner for the chat that's already open.
            val openThread = when (val t = chatTarget) {
                is ChatTarget.Peer -> app.rcq.android.data.LocalStores.peerThread(t.uin)
                is ChatTarget.Group -> app.rcq.android.data.LocalStores.groupThread(t.id)
                else -> null
            }
            LaunchedEffect(banner?.thread) {
                if (banner != null) { kotlinx.coroutines.delay(4000); session.dismissBanner() }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = banner != null && banner?.thread != openThread,
                enter = androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                banner?.let { b -> InAppBanner(b, onTap = {
                    session.dismissBanner()
                    if (b.groupId != null || b.peerUin != null) showAudioRooms = false
                    if (b.groupId != null) chatTarget = ChatTarget.Group(b.groupId)
                    else if (b.peerUin != null) chatTarget = ChatTarget.Peer(b.peerUin)
                }) }
            }
        }

        // ⚠ DEEP LINKS ARE DROPPED IN A DURESS SESSION.
        //
        // Every dialog below acts FOR THE REAL ACCOUNT: `rcq://link` hands a
        // browser web access to it (the whole history), `rcq://add` and
        // `/u/<uin>` fire a contact request under the real uin, `rcq://server`
        // and `rcq://group` join with the real identity. All of them are
        // reachable by a coercer WITHOUT touching RCQ's own UI — a QR code, a
        // link in another app, a scanned invite — so the in-app gating done
        // elsewhere never sees them.
        //
        // Cleared rather than merely hidden, so nothing fires the moment the
        // real session comes back. A link that does nothing is what a link
        // looks like on a phone with no network.
        if (session.inDecoySession) {
            LaunchedEffect(Unit) {
                ServerJoinLink.pending.value = null
                WebLinkRequest.pending.value = null
                ContactAddLink.pending.value = null
                GroupJoinLink.pending.value = null
            }
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
                            val err = runCatching { session.linkWeb(req.token, req.webPub, req.clientLabel) }.exceptionOrNull()
                            val msg = when {
                                err == null -> R.string.weblink_done
                                // 409 = the one-time slot is already filled: a
                                // re-confirm of an old link, not an expiry.
                                err.message?.contains("HTTP 409") == true -> R.string.weblink_taken
                                else -> R.string.weblink_failed
                            }
                            Toast.makeText(context, context.getString(msg), Toast.LENGTH_LONG).show()
                        }
                    },
                    onDismiss = { WebLinkRequest.pending.value = null },
                )
            }
        }

        // Add-contact from a scanned/tapped contact link (rcq://add/<uin>?h=…,
        // https://rcq.app/u/<uin>?h=…, spec §5). Confirm first — a tapped link
        // must not silently register a contact or fire a request. Cross-island
        // host → fetch the peer's card + add locally + open the chat; same
        // island → ordinary contact request.
        val addReq by ContactAddLink.pending.collectAsState()
        if (s is UiState.Registered && !locked) {
            addReq?.let { req ->
                if (req.uin == s.uin) {
                    ContactAddLink.pending.value = null
                } else {
                    val ci = req.host?.takeIf { it != session.currentServer }
                    ContactAddDialog2(
                        address = if (ci != null) "${req.uin}@$ci" else "#${req.uin}",
                        onConfirm = {
                            ContactAddLink.pending.value = null
                            scope.launch {
                                if (ci != null) {
                                    // §5f: the cross-island add now DEPOSITS a
                                    // contact request to the peer's island. A
                                    // scanned QR used to say "request sent" while
                                    // nothing had been sent and no pending list
                                    // would ever hold it — report the deposit,
                                    // not the local write.
                                    when (runCatching { session.addCrossIslandContactDetailed(req.uin, ci) }
                                        .getOrDefault(Session.CiAdd.FAILED)) {
                                        Session.CiAdd.SENT -> {
                                            Toast.makeText(context, context.getString(R.string.addlink_request_sent), Toast.LENGTH_SHORT).show()
                                            chatTarget = ChatTarget.Peer(req.uin)
                                        }
                                        Session.CiAdd.ADDED_ONLY -> {
                                            Toast.makeText(context, context.getString(R.string.ci_request_not_delivered), Toast.LENGTH_LONG).show()
                                            chatTarget = ChatTarget.Peer(req.uin)
                                        }
                                        Session.CiAdd.FAILED ->
                                            Toast.makeText(context, context.getString(R.string.addlink_failed), Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    val ok = runCatching { session.addContact(req.uin) }.isSuccess
                                    Toast.makeText(
                                        context,
                                        context.getString(if (ok) R.string.addlink_request_sent else R.string.addlink_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onDismiss = { ContactAddLink.pending.value = null },
                    )
                }
            }
        }

        // Group-invite deep link: confirm, then join (foreign host guest-
        // registers + joins; same island joins directly) and open the chat.
        val groupReq by GroupJoinLink.pending.collectAsState()
        if (s is UiState.Registered && !locked) {
            groupReq?.let { req ->
                val foreignHost = req.host?.takeIf { it != session.currentServer }
                GroupJoinDialog(
                    host = foreignHost,
                    onConfirm = {
                        GroupJoinLink.pending.value = null
                        scope.launch {
                            val opened = if (foreignHost != null) session.joinForeignGroup(foreignHost, req.id)
                                         else session.joinGroup(req.id)?.let { req.id }
                            if (opened != null) chatTarget = ChatTarget.Group(opened)
                            else Toast.makeText(context, context.getString(R.string.group_invite_join_failed), Toast.LENGTH_LONG).show()
                        }
                    },
                    onDismiss = { GroupJoinLink.pending.value = null },
                )
            }
        }
    }
}

/** Top in-app message banner (#11): sender/title + a one-line preview, tap to
 *  open. The media-kind fallback is resolved here so it stays localized. */
@Composable
private fun InAppBanner(b: Session.InAppBanner, onTap: () -> Unit) {
    val c = RcqTheme.colors
    val preview = b.body.ifBlank {
        stringResource(
            when (b.kind) {
                "photo" -> R.string.kind_photo
                "video" -> R.string.kind_video
                "voice" -> R.string.kind_voice
                "file" -> R.string.kind_file
                "location" -> R.string.kind_location
                else -> R.string.kind_message
            },
        )
    }
    val line = b.sender?.let { "$it: $preview" } ?: preview
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary)
                .clickable(onClick = onTap).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
            Column(Modifier.weight(1f)) {
                Text(b.title, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, color = c.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Confirm sheet for a contact deep link. Named "2" because the Add dialog
 *  composable in HomeScreen already took `AddContactDialog`. */
@Composable
private fun ContactAddDialog2(address: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    RcqAskSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.addlink_title),
        body = stringResource(R.string.addlink_body, address),
        actions = listOf(SheetAction(stringResource(R.string.common_add), onClick = onConfirm)),
    )
}

/** Confirm sheet for a group-invite deep link. [host] non-null = a group on
 *  another island (joining guest-registers your key there). */
@Composable
private fun GroupJoinDialog(host: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    RcqAskSheet(
        onDismiss = onDismiss,
        title = stringResource(if (host != null) R.string.group_invite_island else R.string.group_invite_title),
        body = if (host != null) stringResource(R.string.group_invite_island_hint, host)
               else stringResource(R.string.group_join_confirm),
        actions = listOf(SheetAction(stringResource(R.string.group_invite_join), onClick = onConfirm)),
    )
}

@Composable
private fun WebLinkDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    RcqAskSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.weblink_title),
        body = stringResource(R.string.weblink_body),
        actions = listOf(SheetAction(stringResource(R.string.weblink_confirm), onClick = onConfirm)),
    )
}

@Composable
private fun ServerJoinDialog(host: String, hasInvite: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    // The island's own name and house rules, asked of the island itself. Both
    // have been served on /server/info since islands existed and no client read
    // either, so the admin panel carried a note saying that whatever an operator
    // typed there changed nothing. This is the moment they are for: the one
    // screen where somebody decides whether to go somewhere.
    val info by produceState<app.rcq.android.net.RcqApi.ServerInfoResponse?>(initialValue = null, host) {
        value = app.rcq.android.net.RcqApi.serverInfoOf(host)
    }
    RcqSheet(
        onDismiss = onDismiss,
        title = info?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.join_server_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.join_server_body, host), color = c.textSecondary, fontSize = 14.sp)
            info?.welcome?.takeIf { it.isNotBlank() }?.let { rules ->
                Text(
                    rules, color = c.textPrimary, fontSize = 13.sp,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            if (hasInvite) Text(stringResource(R.string.join_server_invite), color = c.accent, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            CapsuleButton(stringResource(R.string.join_server_join), modifier = Modifier.fillMaxWidth(), onClick = onConfirm)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_cancel), color = c.textSecondary)
            }
        }
    }
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
    // Always dismissible: the download is process-level and keeps going.
    RcqSheet(onDismiss = onDismiss, title = stringResource(R.string.update_title, update.versionName)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The NOTES scroll within a cap (long patch notes used to push the
                // buttons off-screen). The download progress is PINNED BELOW the
                // scroll, NOT inside it — previously it sat after the notes inside
                // the same scroll area, so with long notes it was below the fold
                // and invisible after tapping Download (on a slow network it
                // looked like the indicator was gone — user report). The notes
                // cap shrinks while downloading to keep the bar comfortably in view.
                if (update.notes.isNotBlank()) {
                    Text(
                        update.notes, color = c.textSecondary, fontSize = 14.sp,
                        modifier = Modifier
                            .heightIn(max = if (active != null) 170.dp else 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            if (active != null) {
                if (active.progress < 0f) androidx.compose.material3.LinearProgressIndicator(color = c.accent, modifier = Modifier.fillMaxWidth())
                else androidx.compose.material3.LinearProgressIndicator(progress = { active.progress }, color = c.accent, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_downloading_pct, (active.progress.coerceAtLeast(0f) * 100).toInt()), color = c.textSecondary, fontSize = 13.sp)
                Text(stringResource(R.string.update_bg_hint), color = c.textSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            if (active == null) {
                CapsuleButton(stringResource(R.string.update_install), modifier = Modifier.fillMaxWidth(), onClick = onUpdate)
                Spacer(Modifier.height(4.dp))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (active != null) R.string.common_close else R.string.update_later), color = c.textSecondary)
            }
        }
    }
}


@Composable
private fun CrashConsentDialog(onSend: () -> Unit, onDismiss: () -> Unit) {
    RcqAskSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.crash_consent_title),
        body = stringResource(R.string.crash_consent_body),
        actions = listOf(SheetAction(stringResource(R.string.crash_consent_send), onClick = onSend)),
        cancelLabel = stringResource(R.string.crash_consent_dismiss),
    )
}

@Composable
private fun Registering() {
    val c = RcqTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = c.accent)
        Text(stringResource(R.string.boot_registering), color = c.textSecondary, fontSize = 14.sp)
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
        Text(stringResource(R.string.boot_connect_failed_title), color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = c.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        CapsuleButton(stringResource(R.string.boot_connect_retry), onClick = onRetry)
    }
}
