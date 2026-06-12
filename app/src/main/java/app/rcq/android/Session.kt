package app.rcq.android

import android.content.Context
import android.util.Base64
import app.rcq.android.crypto.Envelope
import app.rcq.android.crypto.IdentityKeys
import app.rcq.android.crypto.MediaCrypto
import app.rcq.android.crypto.Reply
import app.rcq.android.crypto.SealedSender
import app.rcq.android.crypto.SignalBootstrap
import app.rcq.android.crypto.SignalSession
import app.rcq.android.crypto.SignalStoreDb
import app.rcq.android.crypto.SignalStores
import org.signal.libsignal.protocol.DuplicateMessageException
import app.rcq.android.data.AccountManager
import app.rcq.android.data.LocalStores
import app.rcq.android.data.MessageDb
import app.rcq.android.data.SecureStore
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.Contact
import app.rcq.android.model.DeliveryState
import app.rcq.android.model.GroupMember
import app.rcq.android.model.OutgoingRequest
import app.rcq.android.model.PendingRequest
import app.rcq.android.model.RcqGroup
import app.rcq.android.model.UserStatus
import app.rcq.android.net.CrossIslandSender
import app.rcq.android.net.CrossIslandStore
import app.rcq.android.net.VisitedIslandsStore
import app.rcq.android.net.Multihome
import app.rcq.android.net.CrossIslandRequestsStore
import app.rcq.android.net.MultihomeStore
import app.rcq.android.net.RcqApi
import app.rcq.android.net.RcqFederation
import app.rcq.android.net.RcqSocket
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.rcq.android.security.PanicPinService
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/** Random-chat (stranger roulette) UI state. */
sealed interface RandomState {
    /** Not in random chat. */
    data object Idle : RandomState
    /** Queued, waiting for a match (or a match in flight). */
    data object Searching : RandomState
    /** Paired with a stranger until [expiresAtMs] (epoch ms). */
    data class Matched(val peerUin: Int, val peerNickname: String, val expiresAtMs: Long) : RandomState
    /** The pair ended ([reason] = peer_left/peer_skipped/peer_disconnected/expired). */
    data class Ended(val reason: String) : RandomState
    /** Couldn't queue ([code] = age_required/under_18/limit/other). */
    data class Error(val code: String) : RandomState
}

/**
 * The app's single coordinator: identity, REST, WebSocket, encrypted
 * storage, local message DB, and crypto. Exposes observable state
 * (StateFlows) the Compose UI collects. Models the iOS client's
 * AppState/MessageService/ContactService roles, scoped to 1:1 text.
 */
class Session(context: Context) {
    private val appCtx = context.applicationContext

    /** §5d: a cross-island call_offer older than this (sender `ts`) is a
     *  stale offline-queue row, not a live call — file as missed, don't ring. */
    private val callOfferTtlSec = 60L
    // Per-account encrypted identity + message store. Reassigned by
    // [rebindTo] when the active account changes (switch / add / burn);
    // empty-string id is a harmless placeholder on a fresh install where
    // no account exists yet — never read until registration rebinds it.
    private var store = SecureStore(appCtx, AccountManager.activeId.value ?: "")
    // Server this session talks to: the identity's island (org/self-host)
    // or the default public server. Both clients are rebuilt from it after
    // a registration that picks a custom server.
    private fun serverHost(): String = store.serverHost ?: RcqApi.DEFAULT_HOST
    private var api = newApi()
    private var socket = newSocket()
    private fun newApi(): RcqApi = RcqApi("https://${serverHost()}").apply { if (store.isRegistered) setToken(store.token) }
    private fun newSocket(): RcqSocket = RcqSocket("wss://${serverHost()}")
    // Opened lazily by [bindDb] (in [start]) so the message DB is never opened
    // before the panic-PIN dataKey is available — opening a PIN-encrypted DB
    // with the wrong key would fail. While locked it stays closed.
    private lateinit var db: MessageDb
    // Per-account libsignal stores (v=2 forward secrecy). Rebuilt by rebindTo
    // on account switch, like store/db.
    private var signalStores = SignalStores(SignalStoreDb(appCtx, AccountManager.activeId.value ?: ""))
    private val gson = com.google.gson.Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 1:1 audio/video calls. Same-island signalling rides the WS (call_*
     *  events routed in [handleEvent]); signals to a CROSS-ISLAND peer are
     *  wrapped as an Envelope.CallSignal, v=1-sealed and deposited to the
     *  peer's island instead (spec §5d — no shared socket exists across
     *  islands). Reads store/socket/api lazily so it follows account switches. */
    val calls = app.rcq.android.call.CallController(
        appContext = appCtx,
        scope = scope,
        ownUin = { store.uin },
        send = { obj -> routeCallSignal(obj) },
        turn = { api.turnCredentials() },
        nameFor = { contactName(it) },
        appendHistory = { peer, fromMe, text -> logCallHistory(peer, fromMe, text) },
    )

    /** §5d: WS for same-island peers, sealed deposit for cross-island ones. */
    private fun routeCallSignal(obj: JsonObject) {
        val toUin = obj.get("to_uin")?.takeIf { !it.isJsonNull }?.asInt
        val ci = toUin?.let { CrossIslandStore.findByUin(it) }
        if (ci == null) {
            socket.send(obj.toString())   // same-island: unchanged plaintext WS relay
            return
        }
        val me = store.uin ?: return
        val type = obj.get("type")?.takeIf { !it.isJsonNull }?.asString ?: return
        val callId = obj.get("call_id")?.takeIf { !it.isJsonNull }?.asString ?: ""

        // §5d v1-limit fix — BATCH ICE: each trickle candidate was its own
        // sealed deposit = its own NSE banner on the peer (~12 banners/call).
        // Micro-batch a burst into ONE "call_ice" envelope carrying a
        // `candidates` JSON-array string; a short debounce trades a little
        // setup latency for far fewer pushes. Cross-island only — same-island
        // ICE rides the WS above with no banner cost, untouched.
        if (type == "call_ice") {
            val cand = obj.get("candidate")?.takeIf { !it.isJsonNull }?.asString ?: return
            synchronized(ciIceLock) {
                ciIceBuffer.getOrPut(callId) { mutableListOf() }.add(cand)
                ciIceFlushJobs[callId]?.cancel()
                ciIceFlushJobs[callId] = scope.launch {
                    delay(CI_ICE_DEBOUNCE_MS)
                    flushCrossIslandIce(ci, me, callId)
                }
            }
            return
        }
        // Any other signal (offer/answer/end/renegotiate): flush pending ICE
        // for this call first so none are stranded behind it, then deposit it.
        flushCrossIslandIce(ci, me, callId)
        val data = mutableMapOf<String, String>()
        for ((k, v) in obj.entrySet()) {
            if (k == "type" || k == "to_uin" || k == "call_id") continue
            if (v.isJsonPrimitive) data[k] = v.asString
        }
        depositCallSignal(ci, me, Envelope.callSignal(type, callId, data))
    }

    // Cross-island ICE micro-batch state (keyed by call id).
    private val ciIceLock = Any()
    private val ciIceBuffer = mutableMapOf<String, MutableList<String>>()
    private val ciIceFlushJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val CI_ICE_DEBOUNCE_MS = 350L

    /** Deposit all buffered cross-island ICE candidates for [callId] as one
     *  sealed `call_ice` envelope (`candidates` = JSON array). No-op when the
     *  buffer is empty. */
    private fun flushCrossIslandIce(ci: CrossIslandStore.Contact, me: Int, callId: String) {
        val cands = synchronized(ciIceLock) {
            ciIceFlushJobs.remove(callId)?.cancel()
            ciIceBuffer.remove(callId)
        }
        if (cands.isNullOrEmpty()) return
        val arr = com.google.gson.JsonArray().apply { cands.forEach { add(it) } }
        depositCallSignal(ci, me, Envelope.callSignal("call_ice", callId, mapOf("candidates" to arr.toString())))
    }

    private fun depositCallSignal(ci: CrossIslandStore.Contact, me: Int, env: Envelope) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                CrossIslandSender.deliverCall(ci, env, me, signingPriv(), signingPub(), serverHost())
            }.onFailure { android.util.Log.e("RCQcall", "cross-island signal failed: ${it.message}") }
        }
    }

    /** Audio rooms (mesh voice). Single-busy vs 1:1 calls; signalling routed
     *  in [handleEvent]; lazy store/api so it follows account switches. */
    val audioRooms = app.rcq.android.call.AudioRoomController(
        appContext = appCtx,
        scope = scope,
        send = { obj -> socket.send(obj.toString()) },
        turn = { api.turnCredentials() },
        api = { api },
        isInCall = { calls.state.value.active },
    )

    /** People Nearby (geohash check-in). REST-polled; no WS routing. */
    val nearby = app.rcq.android.nearby.NearbyController(appCtx, scope) { api }

    /** Hood Chat (district chat) + Hood Banners — bucket-scoped, server-backed.
     *  Borrows the Nearby display-name/anonymous for the sender label. */
    val hood = app.rcq.android.nearby.HoodController(
        scope = scope,
        api = { api },
        send = { obj -> socket.send(obj.toString()) },
        nick = { if (nearby.anonymous.value) nearby.displayName.value else (store.nickname ?: store.uin?.toString() ?: "?") },
        isAnonymous = { nearby.anonymous.value },
    )

    /** Radio — offline BLE + Wi-Fi-Direct local mesh (text/rooms/PTT voice).
     *  Fully peer-to-peer (no server); reuses the anonymous Nearby label. */
    val radio = app.rcq.android.nearby.RadioController(
        appContext = appCtx,
        scope = scope,
        displayName = { if (nearby.anonymous.value) nearby.displayName.value else (store.nickname ?: store.uin?.toString() ?: "Stranger") },
    )

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingRequest>>(emptyList())
    val pending: StateFlow<List<PendingRequest>> = _pending.asStateFlow()

    /** Requests WE sent (pending + declined), for the "Sent requests" screen. */
    private val _outgoing = MutableStateFlow<List<OutgoingRequest>>(emptyList())
    val outgoing: StateFlow<List<OutgoingRequest>> = _outgoing.asStateFlow()

    private val _messages = MutableStateFlow<Map<Int, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<Int, List<ChatMessage>>> = _messages.asStateFlow()

    private val _groups = MutableStateFlow<List<RcqGroup>>(emptyList())
    val groups: StateFlow<List<RcqGroup>> = _groups.asStateFlow()

    /** Group threads keyed by group id (separate from the 1:1 [messages]). */
    private val _groupMessages = MutableStateFlow<Map<Int, List<ChatMessage>>>(emptyMap())
    val groupMessages: StateFlow<Map<Int, List<ChatMessage>>> = _groupMessages.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** True once the obfuscated transport (censorship bypass) is engaged, so
     *  the home header can show a stealth indicator (iOS StealthHeaderBadge
     *  parity). The tunnel persists for the process once up, so this only
     *  flips on. */
    private val _stealthActive = MutableStateFlow(false)
    val stealthActive: StateFlow<Boolean> = _stealthActive.asStateFlow()

    private val _typingFrom = MutableStateFlow<Int?>(null)
    val typingFrom: StateFlow<Int?> = _typingFrom.asStateFlow()
    private var typingSeq = 0

    /** Active 24h stories feed, grouped by poster (own group first). Drives
     *  the home ring strip + the full-screen viewer. Refreshed on start, on
     *  WS story_posted/story_deleted nudges, and after post/view/delete. */
    private val _stories = MutableStateFlow<List<RcqApi.StoryGroupOut>>(emptyList())
    val stories: StateFlow<List<RcqApi.StoryGroupOut>> = _stories.asStateFlow()

    /** Random-chat (stranger roulette) state machine + the ephemeral message
     *  list for the current pair. Chat rides the normal sealed path, but a
     *  random peer is NOT a contact, so inbound from [activeRandomPeer] is
     *  routed here (never persisted to the message DB or shown on Home). */
    private val _random = MutableStateFlow<RandomState>(RandomState.Idle)
    val random: StateFlow<RandomState> = _random.asStateFlow()
    private val _randomMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val randomMessages: StateFlow<List<ChatMessage>> = _randomMessages.asStateFlow()
    @Volatile private var activeRandomPeer: Int? = null
    @Volatile private var activeRandomPairId: String? = null

    /** Own presence status, reflected in the header status picker. */
    private val _status = MutableStateFlow(UserStatus.ONLINE)
    val status: StateFlow<UserStatus> = _status.asStateFlow()

    /** Whether the active account's server advertises the UIN shop
     *  (GET /server/info → capabilities.uin_shop). Permissive default (true,
     *  matching iOS defaultLegacy + every pre-/server/info backend); refreshed
     *  on each start(). Self-host rcq-server-ref returns false → the Settings
     *  shop row hides. */
    private val _uinShopEnabled = MutableStateFlow(true)
    val uinShopEnabled: StateFlow<Boolean> = _uinShopEnabled.asStateFlow()

    val nickname: String get() = store.nickname ?: "—"

    // uin -> recipient X25519 identity public (raw), from contacts or lookup.
    private val peerIdentityCache = HashMap<Int, ByteArray>()

    // Peers known to have no v=2 bundle this session — send them v=1 without
    // re-probing /keys/{uin}/bundle on every message. Cleared on account
    // switch (a peer may publish a bundle later; we re-probe next session).
    private val noV2Peers = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    // v=2 (libsignal Double Ratchet) OUTBOUND is disabled: Android's libsignal
    // (0.86.5) and iOS's (0.93.1) don't interop on the v=2 wire yet, so a v=2
    // message sent to an iOS peer can't be decrypted there — it surfaces as a
    // generic "Message" push, never lands in the chat, and the sender stays on
    // one checkmark (no delivery ack). Until the cross-device path is verified
    // we send v=1 (sealed sender, ECIES) for every 1:1 — still E2E, the same
    // crypto groups already use. INBOUND v=2 is still decrypted (a peer that
    // sends us v=2 keeps working). Flip back to true to re-enable v=2 sending
    // once Android↔iOS v=2 is confirmed on a real device.
    private val v2OutboundEnabled = false
    // media_id -> decrypted plaintext bytes (sender seeds it; receiver caches).
    // Decrypted media blobs, BOUNDED so an image-heavy chat can't grow the
    // cache without limit — that unbounded growth was an OOM risk on low-RAM
    // devices (a leading suspect for the launch crash in active media groups).
    // Sized to a fraction of the heap so a low-RAM device gets a smaller cap;
    // LruCache evicts least-recently-used first, so a just-sent/just-viewed
    // image (most recent) is never the one dropped. Internally synchronized.
    private val imageCache = object : android.util.LruCache<String, ByteArray>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L * 1024 * 1024, 96L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    // Own read-receipt privacy ("everyone"|"contacts"|"nobody"); loaded on
    // start, gates whether we emit read receipts. Default everyone.
    @Volatile
    private var readReceiptsVisibility: String = "everyone"
    // 1:1 inbound message ids we've already acked with a read receipt
    // (in-memory; re-acking after restart is harmless/idempotent).
    private val ackedReads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var started = false

    // True once the WebSocket has connected at least once; gates the
    // reconnect-driven graph resync so the initial launch doesn't double up.
    @Volatile
    private var everConnected = false

    // Default network the device is currently routing through. When it
    // CHANGES (VPN dropped/joined, Wi-Fi ↔ cellular) the live socket is
    // bound to the old route and sits half-dead until OkHttp's protocol
    // ping notices (~40s of the home dot lying green) — redial right away
    // instead. The very first onAvailable (registration echo) is skipped:
    // start() dials on its own.
    @Volatile
    private var defaultNetwork: android.net.Network? = null
    @Volatile
    private var sawFirstNetwork = false
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            val previous = defaultNetwork
            defaultNetwork = network
            if (!sawFirstNetwork) {
                sawFirstNetwork = true
                return
            }
            if (network != previous) socket.reconnectNow()
        }

        override fun onLost(network: android.net.Network) {
            // Only when the lost network IS the current default (a lingering
            // old VPN network dying after the switch is not). Cleared so the
            // NEXT onAvailable counts as a change even if the same physical
            // network returns (airplane mode off). The redial flips the dot
            // to "connecting" at once; with no route it fails fast and the
            // normal backoff takes over until onAvailable.
            if (network == defaultNetwork) {
                defaultNetwork = null
                socket.reconnectNow()
            }
        }
    }

    init {
        // Network-path watcher for the instant reconnect above. ACCESS_NETWORK_STATE
        // is a normal permission; runCatching guards exotic ROMs only.
        runCatching {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(networkCallback)
        }
        // Federation (F2): local cross-island contact store (per-account;
        // AccountManager.init has already run in MainActivity.onCreate).
        CrossIslandStore.init(appCtx)
        CrossIslandStore.bindAccount(AccountManager.activeId.value)
        // Cross-island groups (§5c): guest registrations + foreign-group aliases.
        VisitedIslandsStore.init(appCtx)
        VisitedIslandsStore.bindAccount(AccountManager.activeId.value)
        // Multihoming (federation v1): this account's backup island homes.
        MultihomeStore.init(appCtx)
        CrossIslandRequestsStore.init(appCtx)
        // The app locking (background with a PIN set) is signalled by the
        // PanicPinService.locked flow; tear the live session down so the
        // unlocked key + plaintext history don't linger in this process.
        scope.launch {
            PanicPinService.locked.collect { isLocked ->
                if (isLocked) {
                    // Leaving decoy mode on re-lock so the next unlock starts
                    // clean (a fresh real PIN reveals all accounts again).
                    AccountManager.exitDecoyMode()
                    if (started) tearDownForLock()
                }
            }
        }
    }

    val isRegistered: Boolean get() = store.isRegistered
    val uin: Int? get() = store.uin
    /** The server this identity lives on (for display in Settings). */
    val currentServer: String get() = serverHost()

    /** True when `uin@host` is one of MY OWN homes (primary or a backup island
     *  registered via multihoming). Used to stop a user "adding"/"friending"
     *  their own other-island copy: a backup is the SAME identity, not a second
     *  account, so a request to it just hangs forever. */
    fun isOwnAddress(uin: Int, host: String): Boolean {
        if (uin == store.uin && host == serverHost()) return true
        return backupHomes.value.any { it.uin == uin && it.host == host }
    }

    /** Normalize a user-typed server into a bare host (drop scheme/path).
     *  Blank / the default host → null (= default public server). */
    private fun normalizeHost(input: String?): String? =
        input?.trim()
            ?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("wss://")?.removePrefix("ws://")
            ?.substringBefore('/')?.trim()
            ?.takeIf { it.isNotBlank() && it != RcqApi.DEFAULT_HOST }

    /** Register a brand-new anonymous identity on the chosen server (the
     *  default public one if null) and swap the session onto it. Serves
     *  BOTH first-launch onboarding (creates Account[0]) and adding a
     *  further account from the switcher (creates Account[N] without
     *  touching the others).
     *
     *  Register-FIRST ordering: we mint on the target server before
     *  creating the local account slot or tearing down the current session,
     *  so an unreachable host / typo throws here with the current account
     *  left completely intact. Throws at the roster cap. */
    /** Engage the circumvention transport (if needed) BEFORE the first network
     *  call of registration/recovery — mirrors [start]'s engage logic. No-op
     *  when a direct /health probe to [host] succeeds (healthy network, no
     *  block) or the transport is already up. Blocking work (probe + sing-box
     *  start) runs off the main thread. Best-effort: a failure to start the
     *  transport just leaves the subsequent request to go direct (and fail as
     *  before), so this never makes registration worse. */
    private suspend fun ensureTransportForHost(host: String) = withContext(Dispatchers.IO) {
        val transport = app.rcq.android.net.SingBoxTransport
        if (!transport.isActive && (transport.isEnabled(appCtx) || !transport.probeDirect(host))) {
            app.rcq.android.net.RelayConfigStore.prime(appCtx)
            transport.start()
        }
        _stealthActive.value = transport.isActive
    }

    suspend fun registerNewAccount(nickname: String, serverInput: String? = null, invite: String? = null): Int {
        if (AccountManager.isAtLimit) throw IllegalStateException("Account limit reached")
        val host = normalizeHost(serverInput)
        // Engage circumvention BEFORE the first network call (registration).
        // Without this a blocked user's very first request goes out direct,
        // times out ("Couldn't connect"), and they have to switch on a VPN
        // just to sign up. The RcqApi built next captures the SOCKS proxy.
        ensureTransportForHost(host ?: RcqApi.DEFAULT_HOST)
        val regApi = RcqApi("https://${host ?: RcqApi.DEFAULT_HOST}")
        // Derive the identity from a fresh 32-byte recovery seed so the account
        // is restorable from a BIP39 phrase (the seed is persisted below).
        val seed = IdentityKeys.newSeed()
        val identity = IdentityKeys.fromSeed(seed)
        val resp = regApi.register(
            RcqApi.RegisterRequest(
                nickname = nickname,
                identity_key = Base64.encodeToString(identity.identityPublic, Base64.NO_WRAP),
                signing_key = Base64.encodeToString(identity.signingPublic, Base64.NO_WRAP),
                invite = invite?.takeIf { it.isNotBlank() },
            )
        )
        // Server identity is live. Commit locally: create the account slot,
        // persist the identity under its prefix, then swap onto it.
        val acct = AccountManager.add(serverHost = host, displayLabel = null)
            ?: throw IllegalStateException("Account limit reached")
        SecureStore(appCtx, acct.id).saveIdentity(
            uin = resp.uin,
            token = resp.token,
            nickname = nickname,
            identityPrivate = identity.identityPrivate,
            signingPrivate = identity.signingPrivate,
            serverHost = host,
            seed = seed,
        )
        socket.disconnect()
        rebindTo(acct.id)
        start()
        return resp.uin
    }

    /** The active account's 24-word recovery phrase, or null for a legacy
     *  account whose keys predate seed-derivation (use [legacyExportPhrase]). */
    fun recoveryPhrase(): List<String>? =
        store.recoverySeed?.let { app.rcq.android.crypto.RecoveryPhrase.encode(it, appCtx) }

    /** A LEGACY account (no seed) backup: its raw identity keys exported as a
     *  48-word phrase (idPriv||signPriv = 64 bytes). null for a seed-derived
     *  account (use [recoveryPhrase]) or if keys are missing/malformed. Restore
     *  via [recoverAccount], which accepts either a 24- or 48-word phrase. */
    fun legacyExportPhrase(): List<String>? {
        if (store.recoverySeed != null) return null
        val id = store.identityPrivate ?: return null
        val sign = store.signingPrivate ?: return null
        if (id.size != 32 || sign.size != 32) return null
        return app.rcq.android.crypto.RecoveryPhrase.encode(id + sign, appCtx)
    }

    /** Restore an account from its recovery phrase on a fresh device: derive the
     *  keypair from the seed, prove ownership of the signing key to the server,
     *  and rebind onto the recovered UIN. Throws IllegalArgumentException on a
     *  bad phrase, or IllegalStateException("identity_not_found") if the server
     *  has no account for these keys. */
    suspend fun recoverAccount(words: List<String>, serverInput: String? = null): Int {
        if (AccountManager.isAtLimit) throw IllegalStateException("Account limit reached")
        val decoded = app.rcq.android.crypto.RecoveryPhrase.decode(words, appCtx)
            ?: throw IllegalArgumentException("invalid_phrase")
        // 32 bytes = a seed (new accounts); 64 bytes = a legacy account's raw
        // idPriv||signPriv export. Legacy restores carry no seed forward.
        val seed: ByteArray? = if (decoded.size == 32) decoded else null
        val identity = when (decoded.size) {
            32 -> IdentityKeys.fromSeed(decoded)
            64 -> IdentityKeys.fromRawPrivates(decoded.copyOfRange(0, 32), decoded.copyOfRange(32, 64))
            else -> throw IllegalArgumentException("invalid_phrase")
        }
        val host = normalizeHost(serverInput)
        // Same as registration: a blocked user must be able to RESTORE without
        // a manual VPN, so bring up circumvention before the challenge call.
        ensureTransportForHost(host ?: RcqApi.DEFAULT_HOST)
        val regApi = RcqApi("https://${host ?: RcqApi.DEFAULT_HOST}")
        val signingPubB64 = Base64.encodeToString(identity.signingPublic, Base64.NO_WRAP)
        val challenge = regApi.recoverChallenge(signingPubB64).challenge
        val signature = app.rcq.android.crypto.RecoveryPhrase.signChallenge(identity.signingPrivate, challenge)
        val resp = regApi.recover(RcqApi.RecoverRequest(signingPubB64, challenge, signature))
        // Fetch the real nickname back (the server kept the profile).
        regApi.setToken(resp.token)
        val nick = runCatching { regApi.userInfo(resp.uin).nickname }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "user-${resp.uin}"
        val acct = AccountManager.add(serverHost = host, displayLabel = null)
            ?: throw IllegalStateException("Account limit reached")
        SecureStore(appCtx, acct.id).saveIdentity(
            uin = resp.uin,
            token = resp.token,
            nickname = nick,
            identityPrivate = identity.identityPrivate,
            signingPrivate = identity.signingPrivate,
            serverHost = host,
            seed = seed,
        )
        socket.disconnect()
        rebindTo(acct.id)
        start()
        return resp.uin
    }

    /** Rotate the active account's long-term identity to a fresh seed/keypair
     *  while KEEPING the same UIN. Derives new X25519 + Ed25519 keys from a new
     *  recovery seed (so the recovery phrase changes), pushes the new public
     *  keys to the server via /auth/reissue, persists them locally, and rebuilds
     *  the libsignal bundle (new safety number → contacts get a "safety number
     *  changed" warning on their next key sync). Returns the NEW 24-word phrase.
     *  For users who fear key compromise or just want a fresh phrase.
     *  Throws IllegalStateException("not_registered") with no active identity. */
    suspend fun reissueKeys(): List<String> = withContext(Dispatchers.IO) {
        val uin = store.uin ?: throw IllegalStateException("not_registered")
        val nick = store.nickname ?: "user-$uin"
        val host = store.serverHost
        val seed = IdentityKeys.newSeed()
        val identity = IdentityKeys.fromSeed(seed)
        // Push the new long-term public keys (the bearer token already authorises
        // the change; UIN unchanged). Returns a fresh-but-equivalent token.
        val resp = api.reissue(
            RcqApi.ReissueRequest(
                identity_key = Base64.encodeToString(identity.identityPublic, Base64.NO_WRAP),
                signing_key = Base64.encodeToString(identity.signingPublic, Base64.NO_WRAP),
            )
        )
        // Persist the new identity + seed under the active account (same UIN /
        // nick / server). store reads prefs live, so this takes effect at once.
        store.saveIdentity(
            uin = resp.uin,
            token = resp.token,
            nickname = nick,
            identityPrivate = identity.identityPrivate,
            signingPrivate = identity.signingPrivate,
            serverHost = host,
            seed = seed,
        )
        api.setToken(resp.token)
        // Rotate the libsignal identity too (upload-first; throws on failure so
        // the UI can ask the user to retry). This is what changes the safety
        // number that warns contacts.
        SignalBootstrap.rebootstrap(signalStores, api, resp.uin)
        // Old sessions/cache referenced the previous identity — drop them.
        peerIdentityCache.clear()
        app.rcq.android.crypto.RecoveryPhrase.encode(seed, appCtx)
    }

    /** Switch the running session to an already-registered account
     *  (iOS-parity hot swap). Returns its UIN. A self-switch is a no-op. */
    suspend fun switchToAccount(accountId: String): Int {
        if (accountId == AccountManager.activeId.value) return store.uin ?: error("no active identity")
        socket.disconnect()
        AccountManager.setActive(accountId)
        rebindTo(accountId)
        start()
        return store.uin ?: error("account has no identity")
    }

    /** Tear the in-memory session state down and re-point every per-account
     *  store at [accountId]; [start] then loads its history + connects. */
    private fun rebindTo(accountId: String) {
        calls.teardown()   // drop any in-flight call before the identity swaps
        audioRooms.teardown()
        nearby.teardown()
        hood.teardown()
        radio.teardown()
        store = SecureStore(appCtx, accountId)
        // db is (re)opened by bindDb() in start(), with the current dataKey.
        if (::db.isInitialized) db.close()
        signalStores = SignalStores(SignalStoreDb(appCtx, accountId))
        LocalStores.bindAccount(accountId)
        app.rcq.android.data.VisitStore.bindAccount(accountId)
        CrossIslandStore.bindAccount(accountId)
        VisitedIslandsStore.bindAccount(accountId)
        api = newApi()
        socket = newSocket()
        peerIdentityCache.clear()
        noV2Peers.clear()
        ackedReads.clear()
        lastVisitAt.clear()
        _contacts.value = emptyList()
        _pending.value = emptyList()
        _outgoing.value = emptyList()
        _messages.value = emptyMap()
        _groups.value = emptyList()
        _groupMessages.value = emptyMap()
        _stories.value = emptyList()
        activeRandomPeer = null
        activeRandomPairId = null
        _randomMessages.value = emptyList()
        _random.value = RandomState.Idle
        _typingFrom.value = null
        _status.value = UserStatus.ONLINE
        readReceiptsVisibility = "everyone"
        activeThread = null
        started = false
        everConnected = false
    }

    /** Load local history, open the WebSocket, drain the offline queue,
     *  and refresh the contact graph. Idempotent enough to call on every
     *  app launch when already registered. */
    fun start() {
        if (started) return
        val uin = store.uin ?: return
        val token = store.token ?: return
        started = true
        CrashReporter.crumb(appCtx, "session_start")
        bindDb(AccountManager.activeId.value ?: "")
        CrashReporter.crumb(appCtx, "load_db")
        loadMessagesFromDb()
        loadCachedRoster()
        refreshBackupHomes()
        refreshCiRequests()
        // Multihoming v1: poll the backup-island mailboxes. Deliberately
        // independent of the primary socket — when the primary island is down,
        // this loop IS the delivery path.
        scope.launch {
            while (true) {
                delay(30_000)
                runCatching { drainBackupQueuesOnce() }
                runCatching { drainVisitedQueuesOnce() }
            }
        }
        scope.launch {
            // Obfuscated transport (censorship circumvention), engaged BEFORE
            // the socket/API connect so they ride the sing-box tunnel. Engage
            // when the user forced it on OR — the chicken-and-egg fix — when a
            // direct /health probe fails, since a blocked user can't reach
            // Settings to flip the toggle. Healthy network + toggle off = the
            // probe succeeds and we connect directly as before (no transport,
            // no overhead). The blocking sing-box start runs here off the main
            // thread; api/socket are rebuilt so they capture the SOCKS proxy.
            val transport = app.rcq.android.net.SingBoxTransport
            val engage = transport.isEnabled(appCtx) || !transport.probeDirect(serverHost())
            if (engage && !transport.isActive) {
                // Use the freshest known relay list (last verified payload off
                // disk) before building the transport; bundled if none yet.
                app.rcq.android.net.RelayConfigStore.prime(appCtx)
                if (transport.start()) {
                    api = newApi()
                    socket = newSocket()
                }
            }
            _stealthActive.value = transport.isActive
            connectAndSync(uin, token)
            // (Crash reports are NOT auto-sent. A captured crash is offered to
            // the user on next launch via a consent dialog in MainActivity —
            // sending technical data silently would clash with the privacy
            // posture. See CrashReporter + RcqApp's crash-consent dialog.)
            // Pull a fresh signed relay list (direct mirrors) for NEXT launch —
            // best-effort, never blocks the connect. So a rotated relay is
            // picked up without an app update.
            launch { runCatching { app.rcq.android.net.RelayConfigStore.refresh(appCtx) } }
        }
    }

    /** Quick toggle for the obfuscated transport (the home "censorship bypass"
     *  control). Unlike the Settings switch — which only persists the pref and
     *  applies on next launch — this engages/drops sing-box LIVE: rebuild the
     *  API + socket so they capture (or release) the SOCKS proxy, then
     *  reconnect. Lets a user who's being blocked turn the tunnel on and have
     *  messages start flowing without restarting the app. */
    fun setObfuscation(on: Boolean) {
        val transport = app.rcq.android.net.SingBoxTransport
        transport.setEnabled(appCtx, on)
        val uin = store.uin ?: return
        val token = store.token ?: return
        scope.launch {
            if (on && !transport.isActive) {
                app.rcq.android.net.RelayConfigStore.prime(appCtx)
                transport.start()
            } else if (!on && transport.isActive) {
                transport.stop()
            }
            // Rebuild so the captured proxy matches the new transport state,
            // then reconnect the live channel.
            socket.disconnect()
            api = newApi()
            socket = newSocket()
            _stealthActive.value = transport.isActive
            connectAndSync(uin, token)
        }
    }

    /** Open the WebSocket + pull the contact graph. Split out of [start] so the
     *  transport engage can run first on a background coroutine. */
    private fun connectAndSync(uin: Int, token: String) {
        socket.connect(
            uin = uin,
            token = token,
            onEvent = ::handleEvent,
            onState = { up ->
                _connected.value = up
                if (up) {
                    // The server replays messages missed while we were offline as
                    // a burst right after connect. Those must NOT play the receive
                    // sound (the sound is only for messages that arrive live while
                    // the app is open, iOS parity). Mute the sound for a short
                    // window covering the backfill burst.
                    soundsSuppressedUntil = System.currentTimeMillis() + 5000L
                    // A reconnect (after an offline gap) re-pulls the graph so
                    // a roster that failed to load earlier recovers without a
                    // cold start. The first connect is skipped — start() below
                    // already kicked the initial load.
                    if (everConnected) syncGraph()
                    everConnected = true
                    // Connection is back — auto-resend anything stuck in FAILED
                    // (transient network/relay death) so the user doesn't have to
                    // tap each red error by hand.
                    retryFailedSends()
                    // If the previous launch died natively during startup, the
                    // content-free breadcrumb report is now submittable — do it
                    // here (the consent prompt can't show during a crash loop).
                    maybeAutoReportNativeCrash()
                }
            },
        )
        syncGraph()
    }

    /** Pull the contact graph + offline queue, each retried and
     *  soft-failing independently. A transient failure at launch used to
     *  strand the UI with an empty roster until the next cold start; the
     *  retry (and the reconnect-driven re-call) make it recover on its own. */
    private fun syncGraph() {
        scope.launch { runCatching { withRetry { drainQueue() } } }
        // Multihoming v1: also drain the backup-island mailboxes (dedup by
        // envelope uuid collapses anything the primary already delivered).
        scope.launch { runCatching { drainBackupQueuesOnce() } }
        scope.launch { runCatching { withRetry { refreshContacts() } } }
        scope.launch { runCatching { withRetry { refreshPending() } } }
        scope.launch { runCatching { withRetry { refreshOutgoing() } } }
        scope.launch { runCatching { withRetry { refreshGroups() } } }
        scope.launch { runCatching { withRetry { loadOwnReadReceiptSetting() } } }
        scope.launch { runCatching { refreshStories() } }
        // Optional-surface flags for this server (UIN shop). Best-effort:
        // failure keeps the permissive default so the shop stays reachable.
        scope.launch { runCatching { _uinShopEnabled.value = api.serverInfo().capabilities.uin_shop } }
        // Ensure our libsignal prekey bundle is published so peers can start
        // v=2 sessions with us. Best-effort: failure leaves us on v=1.
        scope.launch {
            runCatching { store.uin?.let { SignalBootstrap.ensureBootstrapped(signalStores, api, it) } }
                .onFailure { android.util.Log.w("RCQsignal", "bootstrap failed: ${it.javaClass.simpleName}: ${it.message}") }
            // Federation F1: publish our signed home-island record now that the
            // libsignal identity exists. Best-effort; never throws upward.
            publishHomeIslandRecord()
        }
    }

    /** Federation Layer B (F1): build + publish this account's signed home-island
     *  record — the primary island plus any backup homes (multihoming v1). The
     *  same signed record is PUT to every home so senders can resolve it from
     *  whichever island survives. Fully best-effort: any failure (an island
     *  without the F1 endpoint, a missing identity, a network hiccup) is
     *  swallowed so it can never disrupt the session or login. */
    private suspend fun publishHomeIslandRecord() {
        try {
            val uin = store.uin ?: return
            val signingPriv = store.signingPrivate ?: return
            if (!signalStores.hasLocalIdentity()) return
            val ik = Base64.encodeToString(signalStores.getIdentityKeyPair().publicKey.serialize(), Base64.NO_WRAP)
            val skPub = Ed25519PrivateKeyParameters(signingPriv, 0).generatePublicKey().encoded
            val sk = Base64.encodeToString(skPub, Base64.NO_WRAP)
            val homes = listOf(RcqFederation.Home(api.islandHost(), uin)) +
                MultihomeStore.list(uin).map { RcqFederation.Home(it.host, it.uin) }
            val ts = (System.currentTimeMillis() / 1000).toInt()
            val doc = RcqFederation.buildRecord(ik, sk, signingPriv, homes, ts)
            api.publishIslandRecord(doc.toString())
            if (homes.size > 1) Multihome.publishToBackups(uin, signingPriv, skPub, doc.toString())
        } catch (e: Exception) {
            android.util.Log.w("RCQfed", "publish island record failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Federation gossip B1 (second half) — SELF-PUSH the signed home-island
     *  record to every contact as a v=1-sealed `homerec` envelope, so contacts
     *  cache where to reach us even if our island later dies (the server mirror
     *  can't cover "both my islands gone at once"). Call AFTER a record change
     *  (add/remove backup, promote) — NOT on every boot. Best-effort per
     *  contact; never throws. Only ever shares OUR OWN homes with people who are
     *  already our contacts, so nothing about the social graph leaks. */
    private suspend fun pushHomeRecordToContacts() = withContext(Dispatchers.IO) {
        runCatching {
            val uin = store.uin ?: return@withContext
            val signingPriv = store.signingPrivate ?: return@withContext
            if (!signalStores.hasLocalIdentity()) return@withContext
            val ik = Base64.encodeToString(signalStores.getIdentityKeyPair().publicKey.serialize(), Base64.NO_WRAP)
            val skPub = Ed25519PrivateKeyParameters(signingPriv, 0).generatePublicKey().encoded
            val sk = Base64.encodeToString(skPub, Base64.NO_WRAP)
            val homes = listOf(RcqFederation.Home(api.islandHost(), uin)) +
                MultihomeStore.list(uin).map { RcqFederation.Home(it.host, it.uin) }
            val ts = (System.currentTimeMillis() / 1000).toInt()
            val doc = RcqFederation.buildRecord(ik, sk, signingPriv, homes, ts)
            val env = Envelope.HomeRecord(doc)
            val ownHost = api.islandHost()
            for (c in _contacts.value) {
                if (c.blocked || c.uin == uin || c.identityKey.isEmpty()) continue
                runCatching {
                    val ci = CrossIslandStore.findByUin(c.uin)
                    if (ci != null) {
                        // Cross-island contact: deliver to their home(s) (v=1, gossip-aware).
                        CrossIslandSender.deliver(ci, env, uin, signingPriv, skPub, ownHost)
                    } else {
                        // Flagship contact: v=1-seal (NOT v=2 — the receiver binds the
                        // record to the v=1 `spub`) and deposit. Non-pushable type so
                        // it doesn't buzz their device.
                        val payload = SealedSender.encryptV1(
                            env, Base64.decode(c.identityKey, Base64.NO_WRAP), uin, signingPriv, skPub, ownHost,
                        )
                        api.sendSealed(c.uin, payload, envelopeType = "homerec")
                    }
                }
            }
        }
    }

    // ── multihoming (federation v1) ──────────────────────────────────

    /** This account's backup island homes, for the Settings surface. */
    val backupHomes = MutableStateFlow<List<MultihomeStore.Home>>(emptyList())

    private fun refreshBackupHomes() {
        backupHomes.value = store.uin?.let { MultihomeStore.list(it) } ?: emptyList()
    }

    /** Register this identity on [hostInput] as a backup home (recover-first),
     *  then republish the home-island record so senders learn the new home.
     *  Throws IllegalArgumentException("invalid_host"|"primary_island"|
     *  "already_added") or a network error; the UI maps these to strings. */
    suspend fun addBackupIsland(hostInput: String) {
        val uin = store.uin ?: error("no session")
        withContext(Dispatchers.IO) {
            Multihome.addBackupIsland(
                ownUin = uin,
                ownHost = api.islandHost(),
                hostInput = hostInput,
                identityPub = identityPub(),
                signingPriv = signingPriv(),
                signingPub = signingPub(),
                nickname = store.nickname ?: "user-$uin",
            )
        }
        refreshBackupHomes()
        scope.launch { publishHomeIslandRecord() }
        scope.launch { pushHomeRecordToContacts() }   // gossip B1: hand contacts our new homes
        // Pull whatever is already waiting in the new mailbox.
        scope.launch { drainBackupQueuesOnce() }
    }

    /** Forget a backup home (the orphan mailbox account stays on that island;
     *  re-adding recovers the same per-island uin) and republish the record. */
    fun removeBackupIsland(host: String) {
        val uin = store.uin ?: return
        MultihomeStore.remove(uin, host)
        refreshBackupHomes()
        scope.launch { publishHomeIslandRecord() }
        scope.launch { pushHomeRecordToContacts() }   // gossip B1: hand contacts our new homes
    }

    /** §5a.5: make backup [host] the PRIMARY home — one-tap disaster recovery
     *  for a dead/blocked primary island. Refreshes the target's token FIRST
     *  (recover challenge-response: possession of the signing key IS the
     *  credential, no phrase) and ABORTS if the island is unreachable — a
     *  failed promote is a no-op, never a stranded account. Only then swaps
     *  primary/backup in the stores and rebinds the running session to the new
     *  island. Chat history is local, it survives the move; the v=2 prekey
     *  bundle is per-island, so contacts see a routine safety-number change. */
    suspend fun promoteBackupToPrimary(host: String) {
        val accountId = AccountManager.activeId.value ?: error("no session")
        val oldUin = store.uin ?: error("no session")
        val oldToken = store.token ?: error("no session")
        val oldHost = api.islandHost()
        if (host == oldHost) throw IllegalArgumentException("primary_island")
        if (MultihomeStore.list(oldUin).none { it.host == host }) throw IllegalArgumentException("not_backup")
        val cred = withContext(Dispatchers.IO) {
            runCatching { Multihome.recoverOn(host, signingPriv(), signingPub()) }.getOrNull()
        } ?: throw IllegalArgumentException("unreachable")

        // Token in hand — the swap below is pure local bookkeeping.
        socket.disconnect()
        store.rebindHome(cred.uin, cred.token, normalizeHost(host))
        AccountManager.setServerHost(accountId, normalizeHost(host))
        MultihomeStore.promoteSwap(
            oldOwnUin = oldUin,
            newOwnUin = cred.uin,
            promotedHost = host,
            oldPrimary = MultihomeStore.Home(cred.uin, oldHost, oldUin, oldToken, System.currentTimeMillis()),
        )
        rebindTo(accountId)
        start()
        // start() republishes the record after the signal bootstrap, so senders
        // learn the new home order (new primary first, old primary demoted). Also
        // self-push it to contacts (gossip B1) — start() loads the cached roster
        // synchronously, so _contacts is populated by the time this runs.
        scope.launch { pushHomeRecordToContacts() }
    }

    /** The auto-backup toggle's ON action: pick a healthy island from the
     *  public catalogue and register there (recover-first, same keys). Returns
     *  the chosen host. Throws IllegalArgumentException("no_island") when the
     *  catalogue is unreachable or no flagged island responds. */
    suspend fun enableAutoBackup(): String {
        val uin = store.uin ?: error("no session")
        val host = withContext(Dispatchers.IO) {
            Multihome.autoPickHost(api.islandHost(), MultihomeStore.list(uin).map { it.host }.toSet())
        } ?: throw IllegalArgumentException("no_island")
        withContext(Dispatchers.IO) {
            Multihome.addBackupIsland(
                ownUin = uin,
                ownHost = api.islandHost(),
                hostInput = host,
                identityPub = identityPub(),
                signingPriv = signingPriv(),
                signingPub = signingPub(),
                nickname = store.nickname ?: "user-$uin",
                auto = true,
            )
        }
        refreshBackupHomes()
        scope.launch { publishHomeIslandRecord() }
        scope.launch { pushHomeRecordToContacts() }   // gossip B1: hand contacts our new homes
        scope.launch { drainBackupQueuesOnce() }
        return host
    }

    /** The toggle's OFF action: forget every auto-picked home (manually-added
     *  islands stay) and republish the record so senders stop depositing. */
    fun disableAutoBackup() {
        val uin = store.uin ?: return
        MultihomeStore.list(uin).filter { it.auto }.forEach { MultihomeStore.remove(uin, it.host) }
        refreshBackupHomes()
        scope.launch { publishHomeIslandRecord() }
        scope.launch { pushHomeRecordToContacts() }   // gossip B1: hand contacts our new homes
    }

    /** Drain every backup mailbox into the normal ingest path. Copies of
     *  messages the primary already delivered are collapsed by the
     *  INSERT-OR-IGNORE envelope-uuid dedup in [store]. Never throws. */
    private suspend fun drainBackupQueuesOnce() {
        val uin = store.uin ?: return
        if (MultihomeStore.list(uin).isEmpty()) return
        val sp = runCatching { signingPriv() }.getOrNull() ?: return
        val pp = runCatching { signingPub() }.getOrNull() ?: return
        withContext(Dispatchers.IO) {
            Multihome.drainBackupQueues(uin, sp, pp) { payload, groupId, host ->
                // A group row in a BACKUP mailbox = that island also hosts a
                // group we joined (§5c, same identity = same mailbox) — file it
                // under the local alias like the visited-island drain does.
                if (groupId != null) ingestGroup(payload, VisitedIslandsStore.aliasFor(host, groupId))
                else ingest(payload)
            }
        }
    }

    /** §5c: drain the guest mailbox on every visited island — the receive path
     *  for cross-island groups. Group rows file under the local alias; a stray
     *  1:1 row (someone on that island messaged our guest uin) goes through the
     *  normal ingest, whose cross-island consent gate quarantines unknown
     *  senders. Never throws. */
    private suspend fun drainVisitedQueuesOnce() {
        if (VisitedIslandsStore.list().isEmpty()) return
        val sp = runCatching { signingPriv() }.getOrNull() ?: return
        val pp = runCatching { signingPub() }.getOrNull() ?: return
        withContext(Dispatchers.IO) {
            Multihome.drainVisitedQueues(sp, pp) { payload, groupId, host ->
                if (groupId != null) ingestGroup(payload, VisitedIslandsStore.aliasFor(host, groupId))
                else ingest(payload)
            }
        }
    }

    /** Cache the owner's read-receipt visibility so we honour a "nobody"
     *  setting before emitting any receipts. */
    private suspend fun loadOwnReadReceiptSetting() {
        val me = store.uin ?: return
        api.getMe(me).read_receipts_visibility?.let { readReceiptsVisibility = it }
    }

    fun stop() = socket.disconnect()

    // ── panic-PIN at-rest lock ───────────────────────────────────────

    /** (Re)open the active account's message DB under the current dataKey:
     *  the unlocked PIN-vault key if a PIN is set, else the device key. On the
     *  device-key path, migrate any legacy plaintext DB to encrypted once. */
    private fun bindDb(accountId: String) {
        if (::db.isInitialized) db.close()
        val pinKey = PanicPinService.dataKey
        val key = if (pinKey != null) pinKey else {
            val deviceKey = SecureStore.deviceKey(appCtx)
            runCatching { MessageDb.migrateToEncrypted(appCtx, accountId, deviceKey) }
            deviceKey
        }
        db = MessageDb(appCtx, accountId, key)
    }

    /** Tear the live session down when the app locks (background with a PIN
     *  set, flipped by [PanicPinService.lock]): drop the socket + in-memory
     *  history and close the DB so the unlocked dataKey + plaintext history
     *  leave this process's memory. [start] reopens everything after unlock.
     *  Driven by the [PanicPinService.locked] flow (observed in init). */
    private fun tearDownForLock() {
        calls.teardown()
        audioRooms.teardown()
        nearby.teardown()
        hood.teardown()
        radio.teardown()
        socket.disconnect()
        _connected.value = false
        _messages.value = emptyMap()
        _groupMessages.value = emptyMap()
        if (::db.isInitialized) { db.close() }
        started = false
        everConnected = false
    }

    /** Set a real PIN: create the vault, then rekey EVERY account's message DB
     *  from the device key to the new vault key (the PIN locks the whole app,
     *  so all accounts' DBs move under it). */
    suspend fun setPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        val deviceKey = SecureStore.deviceKey(appCtx)
        val newKey = PanicPinService.setRealPin(appCtx, pin) ?: return@withContext false
        rekeyAllAccountDbs(from = deviceKey, to = newKey)
        true
    }

    /** Change the PIN (re-seal the vault slot; the dataKey + DBs are untouched). */
    suspend fun changePin(newPin: String): Boolean = withContext(Dispatchers.IO) {
        PanicPinService.changeRealPin(appCtx, newPin)
    }

    /** Remove the PIN: rekey every DB from the vault key back to the device key,
     *  then destroy the vault. */
    suspend fun removePin(): Boolean = withContext(Dispatchers.IO) {
        val vaultKey = PanicPinService.dataKey ?: return@withContext false
        val deviceKey = SecureStore.deviceKey(appCtx)
        rekeyAllAccountDbs(from = vaultKey, to = deviceKey)
        PanicPinService.removePin(appCtx)
        true
    }

    /** Rekey the active DB in place + every inactive account's DB (opened then
     *  closed). Each is first ensured-encrypted under [from] (handles a fresh
     *  account whose plaintext DB was never migrated). */
    private fun rekeyAllAccountDbs(from: ByteArray, to: ByteArray) {
        val activeId = AccountManager.activeId.value
        if (::db.isInitialized) db.rekey(to)
        AccountManager.accounts.value.map { it.id }.filter { it != activeId }.forEach { id ->
            runCatching {
                MessageDb.migrateToEncrypted(appCtx, id, from)
                val m = MessageDb(appCtx, id, from)
                m.rekey(to)
                m.close()
            }.onFailure { android.util.Log.e("RCQpin", "rekey failed for $id: ${it.message}") }
        }
    }

    val pinConfigured: Boolean get() = PanicPinService.isConfigured(appCtx)
    val hasWipePin: Boolean get() = PanicPinService.hasWipePin()

    /** Add a wipe PIN: entering it at the lock screen erases everything. */
    suspend fun setWipePin(pin: String): Boolean = withContext(Dispatchers.IO) { PanicPinService.setWipePin(appCtx, pin) }

    /** Remove the wipe PIN. */
    suspend fun removeWipePin(): Boolean = withContext(Dispatchers.IO) { PanicPinService.removeWipePin(appCtx) }

    /** DURESS WIPE: erase ALL local data for every account + the PIN vault, so
     *  the app drops to a fresh-install / onboarding state. Local-only (no
     *  server call) — it must work offline and instantly under coercion. The
     *  server-side accounts survive (recoverable later from another device if
     *  the keys were backed up); this is about the seized device. */
    suspend fun wipeEverything() = withContext(Dispatchers.IO) {
        runCatching { calls.teardown() }
        runCatching { audioRooms.teardown() }
        runCatching { nearby.teardown() }
        runCatching { hood.teardown() }
        runCatching { radio.teardown() }
        runCatching { socket.disconnect() }
        started = false
        everConnected = false
        if (::db.isInitialized) runCatching { db.close() }
        AccountManager.accounts.value.forEach { acc ->
            runCatching {
                SecureStore.wipeAccount(appCtx, acc.id)
                MessageDb.wipeAccount(appCtx, acc.id)
                SignalStoreDb.wipeAccount(appCtx, acc.id)
                app.rcq.android.data.VisitStore.wipeAccount(acc.id)
                CrossIslandStore.wipeAccount(acc.id)
                VisitedIslandsStore.wipeAccount(acc.id)
                LocalStores.clearAccount(acc.id)
            }
            AccountManager.remove(acc.id)
        }
        PanicPinService.removePin(appCtx)   // destroys the vault, clears the lock + dataKey
        peerIdentityCache.clear(); noV2Peers.clear(); ackedReads.clear()
        _contacts.value = emptyList(); _pending.value = emptyList(); _outgoing.value = emptyList(); _messages.value = emptyMap()
        _groups.value = emptyList(); _groupMessages.value = emptyMap(); _stories.value = emptyList()
        activeRandomPeer = null; activeRandomPairId = null; _randomMessages.value = emptyList(); _random.value = RandomState.Idle
    }

    val hasDecoyPin: Boolean get() = PanicPinService.hasDecoyPin()
    fun decoyAccountId(): String? = PanicPinService.decoyAccountId()

    /** Add a decoy PIN that unlocks into [decoyAccountId] and hides the other
     *  accounts. Requires an unlocked real session + ≥2 accounts. */
    suspend fun setDecoyPin(pin: String, decoyAccountId: String): Boolean = withContext(Dispatchers.IO) {
        PanicPinService.setDecoyPin(appCtx, pin, decoyAccountId)
    }

    suspend fun removeDecoyPin(): Boolean = withContext(Dispatchers.IO) { PanicPinService.removeDecoyPin(appCtx) }

    /** File a bug report — rides the /reports queue with context=bug_bounty
     *  (iOS parity; target is self, which the backend allows for bug_bounty).
     *  The platform + app version are tagged into the text so the admin queue
     *  shows which client a report came from. */
    suspend fun submitBugReport(text: String, attachments: List<RcqApi.ReportAttachment> = emptyList()): Boolean {
        val me = store.uin ?: return false
        val tag = "[Android ${app.rcq.android.BuildConfig.VERSION_NAME}]"
        return runCatching { api.report(me, "$tag $text", "bug_bounty", attachments) }.isSuccess
    }

    /** Encrypt [bytes] with a fresh per-blob key, upload it, and return the
     *  attachment descriptor (id + key + mime) for a bug report. Null on
     *  failure. The admin decrypts the blob with the returned key. */
    suspend fun uploadReportAttachment(bytes: ByteArray, mime: String): RcqApi.ReportAttachment? =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = app.rcq.android.crypto.MediaCrypto.newKey()
                val sealed = app.rcq.android.crypto.MediaCrypto.seal(bytes, key)
                val up = api.uploadBlob(sealed)
                RcqApi.ReportAttachment(
                    media_id = up.media_id,
                    key = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP),
                    mime = mime,
                    size = bytes.size,
                )
            }.getOrNull()
        }

    /** Auto-submit a SUSPECTED-NATIVE launch-crash breadcrumb report (stage +
     *  device only, no message content) once the socket is up. JVM crash stacks
     *  are NOT auto-sent here — those keep the MainActivity consent prompt. */
    private fun maybeAutoReportNativeCrash() {
        scope.launch {
            val report = CrashReporter.pending(appCtx) ?: return@launch
            if (!report.startsWith("RCQ launch crash")) return@launch
            if (submitBugReport("[CRASH]\n$report")) CrashReporter.clear(appCtx)
        }
    }

    /** Toggle per-conversation screen-secure mode for a 1:1 chat: set it
     *  locally and propagate to the peer so BOTH sides enforce it (iOS parity).
     *  While such a chat is open, ChatScreen adds FLAG_SECURE (Android blanks
     *  the screenshot); a screenshot by the peer arrives as a system notice. */
    fun setChatSecure(peerUin: Int, on: Boolean) {
        LocalStores.setThreadSecure(LocalStores.peerThread(peerUin), on)
        scope.launch { sendControl(peerUin, Envelope.secureScreen(on)) }
    }

    /** A screenshot was just taken on THIS device (detected by MainActivity on
     *  Android 14+). If a per-conversation secure 1:1 chat is open (notify-only
     *  mode, iOS parity), tell the peer so a "took a screenshot" notice appears
     *  on their side. No-op otherwise. */
    fun onLocalScreenshot() {
        val thread = activeThread ?: return
        if (!thread.startsWith("peer:")) return
        if (!LocalStores.isThreadSecure(thread)) return
        val peer = thread.removePrefix("peer:").toIntOrNull() ?: return
        scope.launch { sendControl(peer, Envelope.screenshotTaken()) }
    }

    // ── biometric unlock (panic-PIN phase 4) ─────────────────────────

    /** Can biometric unlock be offered now (hardware present, no duress PIN)? */
    fun biometricCanEnable(): Boolean = PanicPinService.canEnableBiometric(appCtx)
    fun biometricHardwareAvailable(): Boolean = PanicPinService.biometricHardwareAvailable(appCtx)
    val biometricEnabled: Boolean get() = PanicPinService.biometricEnabled(appCtx)

    /** The real-slot blob to seal behind the biometric key (only while
     *  unlocked-real). The UI hands it to [BiometricGate.enable]. */
    fun realPinPayloadBlob(): ByteArray? = PanicPinService.realPayloadBlob()

    fun disableBiometric() = PanicPinService.disableBiometric(appCtx)

    /** After a DECOY submit at the lock screen: switch the active account to the
     *  decoy + filter the roster down to it, so the real accounts stay hidden.
     *  The dataKey is already set (the decoy slot carries the real one). */
    suspend fun applyDecoyUnlock() {
        val decoyId = PanicPinService.consumeDecoyAccountId() ?: return
        AccountManager.enterDecoyMode(decoyId)                  // hide the real accounts FIRST
        if (AccountManager.activeId.value == decoyId) start()   // already active; just bring it up
        else switchToAccount(decoyId)                           // disconnect + rebind + start
        PanicPinService.completeUnlock()                        // only now reveal the (decoy) UI
    }

    /** Wipe local message history (both 1:1 and group threads) without
     *  touching the account. Mirrors iOS "Clear history". */
    fun clearHistory() {
        db.wipe()
        _messages.value = emptyMap()
        _groupMessages.value = emptyMap()
    }

    /** Publish own presence status. Optimistic local update, soft-fail
     *  on the network call. */
    suspend fun setStatus(status: UserStatus) {
        _status.value = status
        runCatching { api.setStatus(status.wire) }
    }

    // ── contact moderation ───────────────────────────────────────────

    /** Toggle block server-side, then refresh the roster. */
    suspend fun toggleBlock(uin: Int) {
        runCatching { api.blockContact(uin) }
        runCatching { refreshContacts() }
    }

    /** Mutual remove + local silent-drop of future sealed messages. A
     *  cross-island contact lives only in CrossIslandStore (the own island has
     *  no roster row to DELETE) — drop it there, or refreshContacts would
     *  merge it right back (beta report #207). */
    suspend fun removeContact(uin: Int) {
        LocalStores.addRemoved(uin)
        val ci = CrossIslandStore.findByUin(uin)
        if (ci != null) CrossIslandStore.remove(ci.uin, ci.host)
        else runCatching { api.removeContact(uin) }
        runCatching { refreshContacts() }
    }

    suspend fun report(uin: Int, reason: String) {
        runCatching { api.report(uin, reason) }
    }

    /** Fetch another user's profile card (GET /users/{uin}/info). The
     *  server returns only the fields that user's privacy settings allow
     *  us to see; null on failure. Used by the 1:1 contact-info screen. */
    suspend fun loadPeerProfile(uin: Int): RcqApi.MeProfile? =
        runCatching { api.getMe(uin) }.getOrNull()

    /** Fetch the admin-posted news feed (GET /news); null on failure. */
    suspend fun loadNews(): RcqApi.NewsFeed? = runCatching { api.news() }.getOrNull()

    // ── stories (24h ephemeral) ──────────────────────────────────────

    /** Pull the active stories feed into [stories]. Soft-fails (keeps the
     *  last good list) so a transient error doesn't blank the ring strip. */
    suspend fun refreshStories() {
        runCatching { api.storiesFeed() }.onSuccess { _stories.value = it.groups }
    }

    /** Encrypt + upload a JPEG, then post it as a 24h photo story. Reuses the
     *  same sealed-blob path as photo messages. Refreshes the feed on success
     *  so the poster's own ring appears immediately. Throws on failure. */
    suspend fun postPhotoStory(jpeg: ByteArray, caption: String?, anonymous: Boolean) {
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(jpeg, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = api.uploadBlob(blob)
        imageCache.put(upload.media_id, jpeg)
        api.postStory(
            RcqApi.PostStoryBody(
                media_id = upload.media_id,
                media_kind = "photo",
                media_key_b64 = keyB64,
                caption = caption?.takeIf { it.isNotBlank() },
                is_anonymous = anonymous,
                duration_sec = null,
            )
        )
        refreshStories()
    }

    /** Mark a story watched (idempotent server-side) and flip its `viewed`
     *  flag in [stories] so the ring greys out without a full refresh. */
    fun markStoryViewed(storyId: String) {
        scope.launch {
            runCatching { api.markStoryViewed(storyId) }
            _stories.value = _stories.value.map { g ->
                if (g.stories.none { it.id == storyId }) g
                else g.copy(stories = g.stories.map { if (it.id == storyId) it.copy(viewed = true) else it })
            }
        }
    }

    /** The viewer list for one of your own stories (owner-only server-side). */
    suspend fun storyViewers(storyId: String): List<RcqApi.StoryViewer> =
        runCatching { api.storyViewers(storyId).viewers }.getOrDefault(emptyList())

    /** Delete one of your own stories early, then refresh the feed. */
    suspend fun deleteStory(storyId: String) {
        runCatching { api.deleteStory(storyId) }
        refreshStories()
    }

    // ── random chat (stranger roulette) ──────────────────────────────

    /** Opt in to matching. Either matches instantly (sync response) or parks
     *  us in the queue (the WS `random_match` will arrive). Surfaces the
     *  backend age gate as a typed [RandomState.Error]. */
    suspend fun startRandom() {
        _random.value = RandomState.Searching
        runCatching { api.randomQueue() }
            .onSuccess { applyQueueResult(it) }
            .onFailure { _random.value = randomErrorFrom(it) }
    }

    /** End the current pair and immediately look for a new stranger. */
    suspend fun skipRandom() {
        _randomMessages.value = emptyList()
        _random.value = RandomState.Searching
        runCatching { api.randomSkip() }
            .onSuccess { applyQueueResult(it) }
            .onFailure { _random.value = randomErrorFrom(it) }
    }

    /** Cancel queueing or end the active pair, returning to Idle. */
    suspend fun leaveRandom() {
        runCatching { api.randomLeave() }
        clearRandom()
    }

    /** Local-only reset (Ended/Error → Idle) with no server call. */
    fun dismissRandom() = clearRandom()

    /** Send a text to the current random peer over the normal sealed path,
     *  but keep the message in the ephemeral [randomMessages] list. */
    suspend fun sendRandomText(text: String) {
        val peer = activeRandomPeer ?: return
        val env = Envelope.text(text)
        appendRandom(ChatMessage(env.id, peer, fromMe = true, body = text, sentAt = System.currentTimeMillis(), state = DeliveryState.SENDING))
        try {
            val payload = encryptFor(peer, env)
            val resp = withRetry { api.sendSealed(peer, payload) }
            updateRandomState(env.id, if (resp.delivered) DeliveryState.DELIVERED else DeliveryState.SENT)
        } catch (e: Exception) {
            updateRandomState(env.id, DeliveryState.FAILED)
        }
    }

    private fun applyQueueResult(r: RcqApi.RandomQueueOut) {
        val peer = r.peer
        if (r.status == "matched" && peer != null) enterMatch(r.pair_id, peer, r.expires_at)
        else _random.value = RandomState.Searching
    }

    /** Wire up an active pair: seed the peer's identity key (so the first
     *  encrypt skips a userInfo fetch), reset the ephemeral thread, go Matched.
     *  Idempotent on pair_id — the matcher gets both a sync response and a WS
     *  `random_match`, and we must not re-enter. */
    private fun enterMatch(pairId: String?, peer: RcqApi.RandomPeerInfo, expiresIso: String?) {
        if (pairId != null && pairId == activeRandomPairId) return
        peer.identity_key?.let { runCatching { peerIdentityCache[peer.uin] = Base64.decode(it, Base64.NO_WRAP) } }
        activeRandomPeer = peer.uin
        activeRandomPairId = pairId
        _randomMessages.value = emptyList()
        val expMs = parseIsoMs(expiresIso) ?: (System.currentTimeMillis() + 5 * 60 * 1000L)
        _random.value = RandomState.Matched(peer.uin, peer.nickname ?: "${peer.uin}", expMs)
    }

    private fun clearRandom() {
        activeRandomPeer = null
        activeRandomPairId = null
        _randomMessages.value = emptyList()
        _random.value = RandomState.Idle
    }

    private fun randomErrorFrom(e: Throwable): RandomState.Error {
        val m = e.message ?: ""
        val code = when {
            m.contains("age_required") -> "age_required"
            m.contains("under_18") -> "under_18"
            m.contains("daily") || m.contains("429") -> "limit"
            else -> "other"
        }
        return RandomState.Error(code)
    }

    private fun appendRandom(msg: ChatMessage) {
        if (_randomMessages.value.any { it.id == msg.id }) return
        _randomMessages.value = (_randomMessages.value + msg).sortedBy { it.sentAt }
    }

    private fun updateRandomState(id: String, state: DeliveryState) {
        _randomMessages.value = _randomMessages.value.map { if (it.id == id) it.copy(state = state) else it }
    }

    private fun parseIsoMs(iso: String?): Long? {
        iso ?: return null
        return runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
    }

    /** The 60-digit safety number for verifying the v=2 conversation with
     *  [uin] out-of-band (key-fingerprint verification, closes the server-MITM
     *  gap). Computed over the PINNED libsignal identities, so it verifies the
     *  key our sessions actually use, not a fresh server-supplied one. Returns
     *  null when there is nothing to verify: we aren't bootstrapped, or the
     *  peer is v=1-only (never published a libsignal bundle). Establishing the
     *  session first pins the peer's identity (TOFU). */
    suspend fun safetyNumber(uin: Int): String? {
        val me = store.uin ?: return null
        val myIdentity = SignalSession.ownIdentity(signalStores) ?: return null
        var peer = SignalSession.pinnedIdentity(signalStores, uin)
        if (peer == null) {
            runCatching { SignalSession.ensureSession(signalStores, api, uin) }
            peer = SignalSession.pinnedIdentity(signalStores, uin)
        }
        if (peer == null) return null
        return runCatching { SignalSession.safetyNumber(me, myIdentity, uin, peer) }.getOrNull()
    }

    /** True if [uin]'s libsignal identity changed since the user last verified
     *  it (re-register / new device / possible MITM) — drives the safety-number
     *  "changed" warning. */
    fun peerIdentityChanged(uin: Int): Boolean =
        runCatching { signalStores.peerIdentityChanged(uin) }.getOrDefault(false)

    /** Clear the change flag once the user has re-checked the safety number. */
    fun acknowledgePeerIdentity(uin: Int) {
        runCatching { signalStores.acknowledgePeerIdentity(uin) }
    }

    // Per-target throttle for fire-and-forget profile-view pings (1h).
    private val lastVisitAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** Fire a sealed "visit" ping so [uin] can tally a profile view (iOS
     *  parity). Throttled to once per hour per target; fire-and-forget, no
     *  bubble. Called when their contact-info screen opens. */
    suspend fun sendVisit(uin: Int) {
        val me = store.uin ?: return
        if (uin == me) return
        val now = System.currentTimeMillis()
        lastVisitAt[uin]?.let { if (now - it < 3_600_000L) return }
        lastVisitAt[uin] = now
        runCatching {
            val payload = encryptFor(uin, Envelope.visit(now))
            api.sendSealed(uin, payload, envelopeType = "visit")
        }.onFailure { lastVisitAt.remove(uin) }
    }

    // ── groups ───────────────────────────────────────────────────────

    private fun mapGroup(g: RcqApi.GroupOut): RcqGroup = RcqGroup(
        id = g.id,
        name = g.name ?: "Group ${g.id}",
        description = g.description,
        ownerUin = g.owner_uin,
        postPolicy = g.post_policy ?: "all",
        isClosed = g.is_closed,
        membersHidden = g.members_hidden,
        pinnedText = g.pinned_text,
        avatarMediaId = g.avatar_media_id,
        avatarMediaKey = g.avatar_media_key,
        members = g.members.map {
            GroupMember(
                uin = it.uin,
                nickname = it.nickname ?: "#${it.uin}",
                role = it.role ?: "member",
                status = it.status,
                identityKey = it.identity_key ?: "",
                signingKey = it.signing_key,
                permissions = it.permissions,
            )
        },
        createdAt = parseIso(g.created_at),
    )

    private suspend fun refreshGroups() {
        val own = api.groups().map(::mapGroup)
        // §5c: groups joined on other islands, fetched with the guest creds;
        // ids rewritten to the local alias at this boundary. A failing island
        // degrades to "no groups from there" — never blocks the own list (the
        // 30s drain refreshes expired guest creds; next refresh recovers).
        val foreign = VisitedIslandsStore.list().flatMap { v ->
            runCatching {
                val guest = RcqApi("https://${v.host}").apply { setToken(v.jwt) }
                guest.groups().map { mapGroup(it).copy(id = VisitedIslandsStore.aliasFor(v.host, it.id), host = v.host) }
            }.getOrElse { emptyList() }
        }
        _groups.value = (own + foreign).sortedByDescending { it.createdAt ?: 0L }
        // Persist the roster so groups are reachable offline (report #7).
        runCatching { LocalStores.setCachedGroupsJson(profileGson.toJson(_groups.value)) }
    }

    /** Upsert a group from a WS event. If the embedded roster no longer
     *  contains us (we left / were removed), drop it locally instead —
     *  mirrors the iOS GroupService.upsert rule. */
    private fun upsertGroup(g: RcqGroup) {
        // Foreign group (§5c): in ITS roster we are our per-island guest uin.
        val me = if (g.host != null) VisitedIslandsStore.get(g.host)?.uin else store.uin
        // Self-removal rule: drop a group we're no longer a member of. Guard
        // on a non-empty roster so a partial/empty WS payload (e.g. the
        // server echoing group_created back to the creator) can't nuke a
        // group we just created.
        if (me != null && g.members.isNotEmpty() && g.members.none { it.uin == me }) {
            _groups.value = _groups.value.filterNot { it.id == g.id }
            return
        }
        _groups.value = (_groups.value.filterNot { it.id == g.id } + g)
            .sortedByDescending { it.createdAt ?: 0L }
    }

    // ── Cross-island groups (§5c): guest context ─────────────────────

    /** Resolution of (api client, server-side id, island, my uin THERE) for a
     *  group op. Own-island groups pass through; a NEGATIVE id is the local
     *  alias of a foreign group → the guest client + remote id. */
    private data class GroupCtx(val api: RcqApi, val gid: Int, val host: String?, val myUin: Int)

    private fun groupCtx(groupId: Int): GroupCtx {
        if (groupId >= 0) return GroupCtx(api, groupId, null, store.uin ?: 0)
        val ref = VisitedIslandsStore.refByAlias(groupId)
        val v = ref?.let { VisitedIslandsStore.get(it.host) }
        if (ref == null || v == null) return GroupCtx(api, groupId, null, store.uin ?: 0)
        val guest = RcqApi("https://${ref.host}").apply { setToken(v.jwt) }
        return GroupCtx(guest, ref.remoteId, ref.host, v.uin)
    }

    /** The island a foreign group lives on (null for own-island groups) — for
     *  UI media fetches and labels. */
    /** (server-side id, island host) for composing a share link — new links
     *  always carry the host so they work from any island (§5c). */
    fun groupShareRef(groupId: Int): Pair<Int, String> {
        val ref = if (groupId < 0) VisitedIslandsStore.refByAlias(groupId) else null
        return if (ref != null) ref.remoteId to ref.host else groupId to serverHost()
    }

    fun groupHost(groupId: Int): String? =
        if (groupId < 0) VisitedIslandsStore.refByAlias(groupId)?.host else null

    private fun mapGroupCtx(ctx: GroupCtx, g: RcqApi.GroupOut): RcqGroup =
        if (ctx.host == null) mapGroup(g)
        else mapGroup(g).copy(id = VisitedIslandsStore.aliasFor(ctx.host, g.id), host = ctx.host)

    /** Guest credentials for [host] (§5c), registering recover-first on first
     *  use — the multihome mechanic, but PRIVATE (never published in the
     *  signed home record). Throws with a short reason on failure. */
    suspend fun ensureGuestOn(host: String): VisitedIslandsStore.Visited {
        val h = Multihome.normalizeHost(host) ?: throw IllegalArgumentException("invalid_host")
        if (h == serverHost()) throw IllegalArgumentException("own_island")
        VisitedIslandsStore.get(h)?.let { return it }
        val uin = store.uin ?: throw IllegalStateException("no identity")
        val creds = Multihome.recoverOn(h, signingPriv(), signingPub())
            ?: RcqApi("https://$h").register(
                RcqApi.RegisterRequest(
                    nickname = store.nickname ?: "user-$uin",
                    identity_key = Base64.encodeToString(identityPub(), Base64.NO_WRAP),
                    signing_key = Base64.encodeToString(signingPub(), Base64.NO_WRAP),
                ),
            )
        val v = VisitedIslandsStore.Visited(h, creds.uin, creds.token, System.currentTimeMillis())
        VisitedIslandsStore.save(v)
        return v
    }

    /** §5c join: guest-register on [host] (explicit user action — seeing a
     *  foreign link never touches the island), join the group there, merge it
     *  into the list under its local alias. Returns the alias id, or null. */
    suspend fun joinForeignGroup(host: String, remoteId: Int): Int? = runCatching {
        val v = ensureGuestOn(host)
        val guest = RcqApi("https://${v.host}").apply { setToken(v.jwt) }
        val g = guest.joinGroup(remoteId)
        val alias = VisitedIslandsStore.aliasFor(v.host, remoteId)
        upsertGroup(mapGroup(g).copy(id = alias, host = v.host))
        alias
    }.getOrNull()

    /** §5c invite-card preview for a group on another island. Privacy rule:
     *  only islands we already VISITED may be queried — an unvisited island is
     *  never touched before the user taps Join (returns null → minimal card). */
    suspend fun previewForeignGroup(host: String, remoteId: Int): RcqApi.GroupPreviewOut? {
        val v = VisitedIslandsStore.get(host) ?: return null
        return runCatching {
            RcqApi("https://${v.host}").apply { setToken(v.jwt) }.previewGroup(remoteId)
        }.getOrNull()
    }

    /** §5c: media in a group lives on the GROUP's island — upload there (the
     *  guest client for foreign groups; own api otherwise). */
    private suspend fun uploadBlobForGroup(groupId: Int, blob: ByteArray): RcqApi.UploadResponse =
        groupCtx(groupId).api.uploadBlob(blob)

    fun group(id: Int): RcqGroup? = _groups.value.firstOrNull { it.id == id }
    fun groupName(id: Int): String = group(id)?.name ?: "Group $id"

    suspend fun createGroup(name: String, memberUins: List<Int>): RcqGroup {
        val g = mapGroup(api.createGroup(name, memberUins))
        upsertGroup(g)
        return g
    }

    suspend fun addGroupMember(id: Int, uin: Int) {
        val ctx = groupCtx(id)
        runCatching { upsertGroup(mapGroupCtx(ctx, ctx.api.addGroupMember(ctx.gid, uin))) }
    }

    /** §5c owner-initiated cross-island add: put a contact who lives on ANOTHER
     *  island into a group on THIS group's island. The group's island has no
     *  account for the foreign uin (that's the "no such user" 404), so we
     *  resolve-or-register the contact's PUBLIC keys there to get a local uin,
     *  add THAT uin, then send the contact the group link so they guest-register
     *  (recover-first → the SAME uin) and start polling. Because they're added
     *  FIRST, their later /join short-circuits on "already a member" — so this
     *  works for CLOSED groups too. Returns null on success, else a reason. */
    suspend fun addCrossIslandGroupMember(groupId: Int, contact: app.rcq.android.net.CrossIslandStore.Contact): String? =
        withContext(Dispatchers.IO) {
            val ctx = groupCtx(groupId)
            // The island the group lives on (own server for a local group).
            val groupHost = ctx.host ?: serverHost()
            // If the contact already lives on the group's island, it's a normal
            // same-island add — no cross-island dance needed.
            if (contact.host.equals(groupHost, ignoreCase = true)) {
                return@withContext runCatching {
                    upsertGroup(mapGroupCtx(ctx, ctx.api.addGroupMember(ctx.gid, contact.uin))); null
                }.getOrElse { it.message ?: "add failed" }
            }
            // Resolve (or mint) the contact's uin ON the group's island.
            val localUin = CrossIslandSender.resolveUinForKey(groupHost, contact.signingKey)
                ?: CrossIslandSender.registerForeignKeys(
                    groupHost, contact.identityKey, contact.signingKey,
                    contact.nickname.takeIf { it.isNotBlank() } ?: "user-${contact.uin}",
                )
                ?: return@withContext "could not reach ${groupHost}"
            // Add them to the roster on the group's island.
            val added = runCatching { ctx.api.addGroupMember(ctx.gid, localUin) }.getOrElse {
                return@withContext it.message ?: "add failed"
            }
            withContext(Dispatchers.Main) { upsertGroup(mapGroupCtx(ctx, added)) }
            // Tell the contact via a cross-island 1:1: the group invite link
            // (carries the host) renders as a join card on their side.
            val link = "https://rcq.app/g/${ctx.gid}@$groupHost"
            withContext(Dispatchers.Main) { runCatching { sendText(contact.uin, link) } }
            null
        }

    /** Owner: kick a member (same endpoint as self-leave). */
    suspend fun removeGroupMember(id: Int, memberUin: Int) {
        val ctx = groupCtx(id)
        runCatching { ctx.api.leaveGroup(ctx.gid, memberUin) }
        // The server broadcasts group_membership_changed; refresh to reflect it.
        runCatching { upsertGroup(mapGroupCtx(ctx, ctx.api.groupInfo(ctx.gid))) }
    }

    /** Owner: grant/revoke a member's moderator caps (subset of
     *  delete|members|info). Updates local roster from the returned group. */
    suspend fun setMemberPermissions(id: Int, memberUin: Int, permissions: List<String>) {
        val ctx = groupCtx(id)
        runCatching { upsertGroup(mapGroupCtx(ctx, ctx.api.setMemberPermissions(ctx.gid, memberUin, permissions))) }
    }

    /** Fetch a group invite-card snapshot (no membership needed). */
    suspend fun previewGroup(id: Int): RcqApi.GroupPreviewOut? =
        runCatching { api.previewGroup(id) }.getOrNull()

    /** Join a group from a shared invite. Already-member is a no-op that just
     *  returns the existing group (the caller jumps into the chat). Returns
     *  null on failure (e.g. a closed group). */
    suspend fun joinGroup(id: Int): RcqGroup? {
        group(id)?.let { return it }
        return runCatching { mapGroup(api.joinGroup(id)).also { upsertGroup(it) } }.getOrNull()
    }

    suspend fun leaveGroup(id: Int) {
        val ctx = groupCtx(id)
        if (ctx.myUin == 0) return
        runCatching { ctx.api.leaveGroup(ctx.gid, ctx.myUin) }
        _groups.value = _groups.value.filterNot { it.id == id }
    }

    suspend fun deleteGroup(id: Int) {
        val ctx = groupCtx(id)
        runCatching { ctx.api.deleteGroup(ctx.gid) }
        _groups.value = _groups.value.filterNot { it.id == id }
    }

    /** Owner/admin: compress + encrypt + upload an avatar blob, then PATCH
     *  the group with the new media id + per-blob key. Throws on failure
     *  so the caller can surface it. */
    suspend fun setGroupAvatar(id: Int, jpeg: ByteArray) {
        val ctx = groupCtx(id)
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(jpeg, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = ctx.api.uploadBlob(blob)
        imageCache.put(upload.media_id, jpeg)
        upsertGroup(mapGroupCtx(ctx, ctx.api.patchGroup(ctx.gid, RcqApi.GroupPatchBody(avatar_media_id = upload.media_id, avatar_media_key = keyB64))))
    }

    /** Owner/admin: rename / re-describe / re-pin a group. */
    suspend fun patchGroup(
        id: Int,
        name: String? = null,
        description: String? = null,
        pinnedText: String? = null,
        postPolicy: String? = null,
        isClosed: Boolean? = null,
        membersHidden: Boolean? = null,
    ) {
        val ctx = groupCtx(id)
        upsertGroup(mapGroupCtx(ctx, ctx.api.patchGroup(ctx.gid, RcqApi.GroupPatchBody(
            name = name, description = description, pinned_text = pinnedText,
            post_policy = postPolicy, is_closed = isClosed, members_hidden = membersHidden,
        ))))
    }

    // ── own profile + privacy ────────────────────────────────────────

    private val profileGson = com.google.gson.Gson()

    /** Last-known-good privacy profile off disk, or null. */
    private fun cachedProfile(): RcqApi.MeProfile? =
        LocalStores.cachedProfileJson()?.let {
            runCatching { profileGson.fromJson(it, RcqApi.MeProfile::class.java) }.getOrNull()
        }

    suspend fun loadProfile(): RcqApi.MeProfile? {
        val me = store.uin ?: return null
        val cached = cachedProfile()
        val net = runCatching { api.getMe(me) }.getOrNull()
        // Transient load failure (bad/censored network) → hand back the
        // last-known-good profile rather than null, so the Privacy screen
        // never silently shows the permissive "everyone" defaults (which a
        // user reads as their restrictions having reset). See LocalStores.
        if (net == null) return cached
        // Server is the source of truth, but a reply that omits the
        // owner-self visibility fields (auth/owner-self edge) must NOT
        // clobber a known cached choice — merge field-by-field.
        val merged = net.copy(
            last_seen_visibility = net.last_seen_visibility ?: cached?.last_seen_visibility,
            gender_visibility = net.gender_visibility ?: cached?.gender_visibility,
            profile_visibility = net.profile_visibility ?: cached?.profile_visibility,
            group_invite_policy = net.group_invite_policy ?: cached?.group_invite_policy,
            read_receipts_visibility = net.read_receipts_visibility ?: cached?.read_receipts_visibility,
            hof_opt_in = net.hof_opt_in ?: cached?.hof_opt_in,
            hof_avatar = net.hof_avatar ?: cached?.hof_avatar,
        )
        LocalStores.setCachedProfileJson(profileGson.toJson(merged))
        return merged
    }

    suspend fun updateProfile(body: RcqApi.UpdateMeBody): RcqApi.MeProfile? {
        val updated = runCatching { api.updateMe(body) }.getOrNull()
        // Reflect a nickname change locally (the header reads store.nickname).
        if (updated != null && !body.nickname.isNullOrBlank()) store.updateNickname(body.nickname)
        // Keep the read-receipt gate in sync when the user changes it.
        if (updated != null) body.read_receipts_visibility?.let { readReceiptsVisibility = it }
        // The PUT response is the full owner-self profile — refresh the cache
        // so the next Privacy-screen open re-seeds from the new choice even
        // offline.
        if (updated != null) LocalStores.setCachedProfileJson(profileGson.toJson(updated))
        return updated
    }

    /** Contacts the user has blocked (for the Blocked Users settings screen). */
    fun blockedContacts(): List<Contact> = _contacts.value.filter { it.blocked }

    suspend fun sendGroupText(groupId: Int, text: String, replyTo: app.rcq.android.crypto.Reply? = null) {
        val env = Envelope.text(text, replyTo)
        sendGroupEnvelope(groupId, env, env.id, text, kind = "text", replyTo = replyTo)
    }

    suspend fun sendGroupPhoto(groupId: Int, jpeg: ByteArray, caption: String?, spoiler: Boolean = false, albumId: String? = null) {
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(jpeg, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobForGroup(groupId, blob)
        imageCache.put(upload.media_id, jpeg)
        val env = Envelope.photo(upload.media_id, keyB64, caption, spoiler, albumId)
        sendGroupEnvelope(groupId, env, env.id, caption ?: "", kind = "photo", mediaId = upload.media_id, mediaKey = keyB64, spoiler = spoiler, albumId = albumId)
    }

    /** Create a group poll: register the structural shape server-side (gets a
     *  poll_id), then fan out a "poll" envelope carrying the encrypted question
     *  + options so every member's chat renders a ballot. The poll content is
     *  stored in [ChatMessage.body] as JSON (tallies are fetched fresh, never
     *  persisted). message_id = the envelope UUID, the server's 1:1 link. */
    suspend fun sendPoll(groupId: Int, question: String, options: List<String>, singleChoice: Boolean, anonymous: Boolean) {
        val id = java.util.UUID.randomUUID().toString().uppercase()
        val created = api.createPoll(groupId, RcqApi.CreatePollBody(id, options.size, singleChoice, anonymous))
        val env = Envelope.Poll(id, created.poll_id, question, options, singleChoice, anonymous)
        val body = app.rcq.android.model.PollContent(created.poll_id, question, options, singleChoice, anonymous).toJson()
        sendGroupEnvelope(groupId, env, id, body, kind = "poll")
    }

    /** Cast/toggle a vote; returns the fresh server tallies (null on failure). */
    suspend fun votePoll(pollId: Int, optionIndex: Int): RcqApi.PollOut? =
        runCatching { api.votePoll(pollId, optionIndex) }.getOrNull()

    /** Current server-side tallies for a poll (null on failure). */
    suspend fun loadPoll(pollId: Int): RcqApi.PollOut? =
        runCatching { api.getPoll(pollId) }.getOrNull()

    /** Close a poll (creator only, server-enforced); returns fresh state. */
    suspend fun closePoll(pollId: Int): RcqApi.PollOut? =
        runCatching { api.closePoll(pollId) }.getOrNull()

    /** Encrypt the envelope once per member (skipping self) and fan out in
     *  a single /messages/group-sealed POST. No group key — each blob is a
     *  v=1 sealed envelope, identical to 1:1 (rcq-spec 6.4). */
    private suspend fun sendGroupEnvelope(
        groupId: Int,
        env: Envelope,
        id: String,
        body: String,
        kind: String,
        mediaId: String? = null,
        mediaKey: String? = null,
        replyTo: app.rcq.android.crypto.Reply? = null,
        fileName: String? = null,
        fileMime: String? = null,
        fileSize: Long? = null,
        durationSec: Int? = null,
        thumbB64: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        spoiler: Boolean = false,
        albumId: String? = null,
    ) {
        val me = store.uin ?: return
        storeGroup(
            ChatMessage(
                id = id, peerUin = 0, fromMe = true, body = body,
                sentAt = System.currentTimeMillis(), state = DeliveryState.SENDING,
                kind = kind, mediaId = mediaId, mediaKey = mediaKey,
                replyToSnippet = replyTo?.snippet, replyToAuthor = replyTo?.authorName, replyToId = replyTo?.id,
                groupId = groupId, senderUin = me,
                fileName = fileName, fileMime = fileMime, fileSize = fileSize,
                durationSec = durationSec, thumbB64 = thumbB64, lat = lat, lng = lng,
                spoiler = spoiler, albumId = albumId,
            )
        )
        fanOutGroup(groupId, env, id)
    }

    /** Encrypt [env] once per member (skipping self) and POST the fan-out;
     *  flips the local bubble's delivery state. Shared by send + resend. */
    private suspend fun fanOutGroup(groupId: Int, env: Envelope, id: String) = withContext(Dispatchers.IO) {
        // §5c: a foreign group seals AS the guest identity (the sender uin must
        // be our per-island uin so the roster resolves it; keys are identical)
        // and POSTs to the group's island with the guest jwt.
        val ctx = groupCtx(groupId)
        val me = ctx.myUin.takeIf { it != 0 } ?: return@withContext
        val group = group(groupId) ?: return@withContext
        // Encrypt once per member, off the main thread (a big group's fan-out is
        // hundreds of X25519 ops + a large POST — on the UI thread it froze the
        // chat and the retry's pool-evict even threw NetworkOnMainThread). A
        // single member whose identity key isn't a valid X25519 point (a legacy
        // or other-client key) must NOT sink the whole send: skip it and deliver
        // to everyone else, instead of failing the message for the whole group.
        var skipped = 0
        val payloads = group.members
            .filter { it.uin != me && it.identityKey.isNotEmpty() }
            .mapNotNull { m ->
                runCatching {
                    RcqApi.GroupPayload(
                        to_uin = m.uin,
                        payload = SealedSender.encryptV1(
                            envelope = env,
                            recipientIdentityPub = Base64.decode(m.identityKey, Base64.NO_WRAP),
                            ownUin = me,
                            signingPriv = signingPriv(),
                            signingPub = signingPub(),
                            ownHost = ctx.host ?: serverHost(),
                        ),
                    )
                }.getOrElse { skipped++; null }
            }
        if (skipped > 0) android.util.Log.w("RCQgroup", "group $groupId: skipped $skipped member(s) with an unusable identity key")
        try {
            val resp = if (payloads.isEmpty()) {
                // Lone member, or every other key is unusable — nothing to send.
                RcqApi.SendResponse(delivered = false)
            } else {
                // owner_only groups attach our token so the server can verify
                // we're the owner (sealed sender hides nothing here — every
                // broadcast post is known to be the owner's); 'all' groups stay
                // anonymous to preserve sealed sender.
                withRetry { ctx.api.sendGroupSealed(ctx.gid, payloads, authed = group.postPolicy == "owner_only") }
            }
            updateGroupMsgState(groupId, id, if (resp.delivered) DeliveryState.DELIVERED else DeliveryState.SENT)
            // Mirror the message to the user's other devices (best-effort).
            // NOT for foreign groups: the carbon would carry the server-side
            // group id, which another of our devices would misread as a LOCAL
            // group (alias maps are per-device) — §5c v1 limit.
            if (ctx.host == null) sendMessageCarbon(env, toPeer = null, toGroup = groupId)
        } catch (e: Exception) {
            updateGroupMsgState(groupId, id, DeliveryState.FAILED)
        }
    }

    /** Decrypt + store an inbound group message under its group thread. */
    private fun ingestGroup(payloadB64: String, groupId: Int) {
        runCatching {
            val dec = decryptInbound(payloadB64)
            val now = System.currentTimeMillis()
            when (val env = dec.envelope) {
                is Envelope.Text -> storeGroup(
                    ChatMessage(env.id, 0, false, env.text, now, kind = "text", groupId = groupId, senderUin = dec.senderUin, replyToSnippet = env.replyTo?.snippet, replyToAuthor = env.replyTo?.authorName, replyToId = env.replyTo?.id)
                )
                is Envelope.Photo -> storeGroup(
                    ChatMessage(env.id, 0, false, env.caption ?: "", now, kind = "photo", mediaId = env.mediaId, mediaKey = env.mediaKey, groupId = groupId, senderUin = dec.senderUin, spoiler = env.spoiler, albumId = env.albumId)
                )
                is Envelope.File -> storeGroup(
                    ChatMessage(env.id, 0, false, env.caption ?: "", now, kind = "file", mediaId = env.mediaId, mediaKey = env.mediaKey, fileName = env.fileName, fileMime = env.mime, fileSize = env.sizeBytes, groupId = groupId, senderUin = dec.senderUin)
                )
                is Envelope.Voice -> storeGroup(
                    ChatMessage(env.id, 0, false, "", now, kind = "voice", mediaId = env.mediaId, mediaKey = env.mediaKey, durationSec = env.durationSec.toInt(), groupId = groupId, senderUin = dec.senderUin)
                )
                is Envelope.Video -> storeGroup(
                    ChatMessage(env.id, 0, false, env.caption ?: "", now, kind = "video", mediaId = env.mediaId, mediaKey = env.mediaKey, durationSec = env.durationSec.toInt(), thumbB64 = env.thumbnailB64, groupId = groupId, senderUin = dec.senderUin, spoiler = env.spoiler, albumId = env.albumId)
                )
                is Envelope.Location -> storeGroup(
                    ChatMessage(env.id, 0, false, env.caption ?: "", now, kind = "location", lat = env.lat, lng = env.lng, groupId = groupId, senderUin = dec.senderUin)
                )
                is Envelope.Poll -> storeGroup(
                    ChatMessage(env.id, 0, false, app.rcq.android.model.PollContent(env.pollId, env.question, env.options, env.singleChoice, env.anonymous).toJson(), now, kind = "poll", groupId = groupId, senderUin = dec.senderUin)
                )
                is Envelope.Reaction -> addGroupReaction(groupId, env.targetId, dec.senderUin, env.asset)
                is Envelope.Delete -> {
                    // Honor the delete if the deleter is the message author OR a
                    // group moderator: the owner, or a member the owner granted
                    // the `delete` cap. We can check this because sealed sender
                    // still reveals the decrypted deleter (dec.senderUin) and we
                    // have the cached roster with everyone's permissions.
                    val t = _groupMessages.value[groupId]?.firstOrNull { it.id == env.targetId }
                    if (t != null) {
                        val g = group(groupId)
                        val byAuthor = t.senderUin == dec.senderUin
                        val byModerator = g?.members?.firstOrNull { it.uin == dec.senderUin }
                            ?.canDelete(g.ownerUin) == true
                        if (byAuthor || byModerator) deleteInFlow(_groupMessages, groupId, env.targetId)
                    }
                }
                is Envelope.Edit -> {
                    val t = _groupMessages.value[groupId]?.firstOrNull { it.id == env.targetId }
                    if (t != null && t.senderUin == dec.senderUin) editInFlow(_groupMessages, groupId, env.targetId, env.text)
                }
                is Envelope.ReadReceipt -> Unit  // group read receipts not surfaced per-message
                is Envelope.Visit -> Unit        // visits are 1:1 only
                is Envelope.SecureScreen -> Unit // secure mode is 1:1 only
                is Envelope.ScreenshotTaken -> Unit
                is Envelope.CallSignal -> Unit   // 1:1 only (§5d); group calls don't cross islands
                is Envelope.Carbon -> Unit       // carbons arrive 1:1 (to self), never group-sealed
                is Envelope.HomeRecord -> Unit   // self-push is 1:1 only, intercepted in ingest()
                is Envelope.Unknown -> Unit
            }
        }.onFailure { logDecryptFailure(payloadB64, it) }
    }

    /** Irreversible burn of the ACTIVE account: delete it server-side, wipe
     *  all of its local storage, drop it from the roster. If another account
     *  remains the session hot-swaps onto it and its UIN is returned;
     *  otherwise returns null (back to a fresh-install / onboarding state). */
    suspend fun burnAccount(): Int? {
        val burnedId = AccountManager.activeId.value
        runCatching { api.deleteAccount() }
        socket.disconnect()
        if (burnedId != null) {
            SecureStore.wipeAccount(appCtx, burnedId)
            MessageDb.wipeAccount(appCtx, burnedId)
            SignalStoreDb.wipeAccount(appCtx, burnedId)
            app.rcq.android.data.VisitStore.wipeAccount(burnedId)
            CrossIslandStore.wipeAccount(burnedId)
            VisitedIslandsStore.wipeAccount(burnedId)
            LocalStores.clearAccount(burnedId)
            AccountManager.remove(burnedId)   // active falls back to first remaining (or null)
        } else {
            store.wipe()
            db.wipe()
            app.rcq.android.data.VisitStore.wipe()
        }
        peerIdentityCache.clear()
        noV2Peers.clear()
        ackedReads.clear()
        _contacts.value = emptyList()
        _pending.value = emptyList()
        _outgoing.value = emptyList()
        _messages.value = emptyMap()
        _groups.value = emptyList()
        _groupMessages.value = emptyMap()
        _stories.value = emptyList()
        activeRandomPeer = null
        activeRandomPairId = null
        _randomMessages.value = emptyList()
        _random.value = RandomState.Idle
        _typingFrom.value = null
        started = false
        everConnected = false
        val next = AccountManager.activeId.value
        return if (next != null) {
            rebindTo(next)
            start()
            store.uin
        } else {
            LocalStores.bindAccount(null)
            app.rcq.android.data.VisitStore.bindAccount(null)
            CrossIslandStore.bindAccount(null)
            VisitedIslandsStore.bindAccount(null)
            null
        }
    }

    /** Local-only delete of a NON-active account (iOS ManageAccountsSheet):
     *  wipe its device storage + drop it from the roster, leaving its
     *  server-side identity alive. Refuses the active account. */
    fun deleteAccountLocal(accountId: String) {
        if (accountId == AccountManager.activeId.value) return
        SecureStore.wipeAccount(appCtx, accountId)
        MessageDb.wipeAccount(appCtx, accountId)
        SignalStoreDb.wipeAccount(appCtx, accountId)
        app.rcq.android.data.VisitStore.wipeAccount(accountId)
        CrossIslandStore.wipeAccount(accountId)
        VisitedIslandsStore.wipeAccount(accountId)
        LocalStores.clearAccount(accountId)
        AccountManager.remove(accountId)
    }

    /** Move to a freshly-allocated UIN (iOS-parity). The server keeps the
     *  profile/contacts/groups and reuses the identity keys under the new
     *  UIN; we swap uin+token locally (keys/nickname/server stay) and reboot
     *  the session under the new UIN. **Local chat history is PRESERVED** —
     *  it's keyed by the peer's UIN (which doesn't change; only ours does),
     *  so it stays valid. Contacts/groups re-sync from the server. Returns
     *  the new UIN. Throws on server refusal (e.g. cooldown). */
    suspend fun migrateToNewUin(): Int {
        val resp = api.migrateAccount()
        applyMigration(resp)
        return resp.new_uin
    }

    /** Buy [uin] from the UIN shop (mock IAP receipt) and migrate the account
     *  onto it. Server-side this is the SAME migration as [migrateToNewUin], so
     *  local handling is identical — history survives (peer-keyed),
     *  contacts/groups re-sync. A 409 (someone grabbed it first between quote
     *  and purchase) maps to [PurchaseResult.Taken] so the shop can prompt for
     *  a different number; other failures bubble up as [PurchaseResult.Other]. */
    suspend fun purchaseUin(uin: Int, receipt: String): PurchaseResult {
        val resp = try {
            api.purchaseUin(uin, receipt)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            return if (msg.contains("HTTP 409")) PurchaseResult.Taken else PurchaseResult.Other(msg)
        }
        applyMigration(resp)
        return PurchaseResult.Success(resp.new_uin)
    }

    sealed class PurchaseResult {
        data class Success(val newUin: Int) : PurchaseResult()
        object Taken : PurchaseResult()
        data class Other(val message: String?) : PurchaseResult()
    }

    /** Live availability + price preview for a candidate UIN (POST /uin/quote). */
    suspend fun quoteUin(uin: Int): RcqApi.QuoteResponse = api.uinQuote(uin)

    /** Swap uin+token locally (keys/nickname/server stay) and reboot the
     *  session under the new UIN. **Local chat history is PRESERVED** — it's
     *  keyed by the peer's UIN (which doesn't change; only ours does), so it
     *  stays valid; start() reloads it from the intact db. Contacts/groups
     *  re-sync from the server. Shared by the free migrate + the shop purchase. */
    private fun applyMigration(resp: RcqApi.MigrateResponse) {
        socket.disconnect()
        store.updateAccount(resp.new_uin, resp.token)
        api.setToken(resp.token)
        peerIdentityCache.clear()
        noV2Peers.clear()
        ackedReads.clear()
        _contacts.value = emptyList()
        _pending.value = emptyList()
        _outgoing.value = emptyList()
        _groups.value = emptyList()
        _typingFrom.value = null
        started = false
        everConnected = false
        socket = newSocket()
        start()
    }

    // NOTE: the old destructive `switchServer` (which wiped the active
    // account's history to mint a fresh identity on another server) was
    // REMOVED. With multi-identity, connecting to another server is a
    // non-destructive ADD: `registerNewAccount(nick, host)` creates a new
    // account on that server and leaves the current one (and all its
    // history/favorites/archive) intact and switchable. The Custom-server
    // settings screen routes through that path now.

    // ── messaging ────────────────────────────────────────────────────

    suspend fun sendText(toUin: Int, text: String, replyTo: Reply? = null) {
        val env = Envelope.text(text, replyTo)
        store(ChatMessage(env.id, toUin, fromMe = true, body = text, sentAt = System.currentTimeMillis(), state = DeliveryState.SENDING, replyToSnippet = replyTo?.snippet, replyToAuthor = replyTo?.authorName, replyToId = replyTo?.id))
        sendEnvelope(env, env.id, toUin)
    }

    /** Local-only delete (removes from this device; no wire message). */
    fun deleteLocal(msg: ChatMessage) {
        db.delete(msg.id)
        if (msg.groupId != null) {
            val cur = _groupMessages.value.toMutableMap()
            cur[msg.groupId] = (cur[msg.groupId] ?: emptyList()).filterNot { it.id == msg.id }
            _groupMessages.value = cur
        } else {
            val cur = _messages.value.toMutableMap()
            cur[msg.peerUin] = (cur[msg.peerUin] ?: emptyList()).filterNot { it.id == msg.id }
            _messages.value = cur
        }
    }

    /** Upload an encrypted media blob for a 1:1 send to [toUin]. Same-island
     *  peers use the normal POST /media/upload. A CROSS-ISLAND peer fetches
     *  media from their OWN island, so the blob is DEPOSITED there under a
     *  client-chosen id (deposit-the-blob — islands never talk; the message
     *  survives our island dying), plus a best-effort copy on our island for
     *  carbons + re-fetch. Mirrors web-chat media.ts uploadBlob. */
    private suspend fun uploadBlobFor(toUin: Int, blob: ByteArray): RcqApi.UploadResponse {
        val ci = CrossIslandStore.findByUin(toUin) ?: return api.uploadBlob(blob)
        return withContext(Dispatchers.IO) {
            val mediaId = java.util.UUID.randomUUID().toString().replace("-", "")
            // The peer-island copy is REQUIRED — that's the one they read.
            if (!CrossIslandSender.depositBlob(ci.host, mediaId, blob)) {
                throw java.io.IOException("cross-island media deposit failed (${ci.host})")
            }
            runCatching { api.putBlob(mediaId, blob) }
            RcqApi.UploadResponse(mediaId, blob.size)
        }
    }

    /** Encrypt+upload an already-compressed JPEG, then send a photo
     *  envelope carrying the media id + per-blob key (rcq-spec 9). The
     *  local bubble appears once the blob is uploaded. */
    suspend fun sendPhoto(toUin: Int, jpeg: ByteArray, caption: String?, spoiler: Boolean = false, albumId: String? = null) {
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(jpeg, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobFor(toUin, blob)      // throws on failure (caller catches)
        imageCache.put(upload.media_id, jpeg)            // own bubble renders without re-download
        val env = Envelope.photo(upload.media_id, keyB64, caption, spoiler, albumId)
        store(ChatMessage(env.id, toUin, true, caption ?: "", System.currentTimeMillis(), DeliveryState.SENDING, kind = "photo", mediaId = upload.media_id, mediaKey = keyB64, spoiler = spoiler, albumId = albumId))
        sendEnvelope(env, env.id, toUin)
    }

    /** Encrypt+upload arbitrary file bytes, then send a file envelope (same
     *  blob path as photos; rcq-spec 9). [fileName]/[mime]/size describe it. */
    suspend fun sendFile(toUin: Int, bytes: ByteArray, fileName: String, mime: String) {
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobFor(toUin, blob)
        imageCache.put(upload.media_id, bytes)
        val size = bytes.size.toLong()
        val env = Envelope.file(upload.media_id, keyB64, fileName, mime, size, null)
        store(ChatMessage(env.id, toUin, true, "", System.currentTimeMillis(), DeliveryState.SENDING, kind = "file", mediaId = upload.media_id, mediaKey = keyB64, fileName = fileName, fileMime = mime, fileSize = size))
        sendEnvelope(env, env.id, toUin)
    }

    /** Group file: encrypt once, fan out per member (same as group photo). */
    suspend fun sendGroupFile(groupId: Int, bytes: ByteArray, fileName: String, mime: String) {
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobForGroup(groupId, blob)
        imageCache.put(upload.media_id, bytes)
        val size = bytes.size.toLong()
        val env = Envelope.file(upload.media_id, keyB64, fileName, mime, size, null)
        sendGroupEnvelope(groupId, env, env.id, "", kind = "file", mediaId = upload.media_id, mediaKey = keyB64, fileName = fileName, fileMime = mime, fileSize = size)
    }

    /** Encrypt+upload a recorded voice clip, then send a voice envelope. */
    suspend fun sendVoice(toUin: Int, bytes: ByteArray, durationSec: Int) {
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobFor(toUin, blob)
        imageCache.put(upload.media_id, bytes)
        val env = Envelope.voice(upload.media_id, keyB64, durationSec.toDouble())
        store(ChatMessage(env.id, toUin, true, "", System.currentTimeMillis(), DeliveryState.SENDING, kind = "voice", mediaId = upload.media_id, mediaKey = keyB64, durationSec = durationSec))
        sendEnvelope(env, env.id, toUin)
    }

    /** Group voice note: encrypt once, fan out per member. */
    suspend fun sendGroupVoice(groupId: Int, bytes: ByteArray, durationSec: Int) {
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobForGroup(groupId, blob)
        imageCache.put(upload.media_id, bytes)
        val env = Envelope.voice(upload.media_id, keyB64, durationSec.toDouble())
        sendGroupEnvelope(groupId, env, env.id, "", kind = "voice", mediaId = upload.media_id, mediaKey = keyB64, durationSec = durationSec)
    }

    /** Encrypt+upload a picked video, then send a video envelope carrying a
     *  base64 poster thumbnail so the bubble renders before download. */
    suspend fun sendVideo(toUin: Int, bytes: ByteArray, thumbB64: String, durationSec: Int, caption: String?, spoiler: Boolean = false, albumId: String? = null) {
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobFor(toUin, blob)
        imageCache.put(upload.media_id, bytes)
        val env = Envelope.video(upload.media_id, keyB64, thumbB64, durationSec.toDouble(), caption, spoiler, albumId)
        store(ChatMessage(env.id, toUin, true, caption ?: "", System.currentTimeMillis(), DeliveryState.SENDING, kind = "video", mediaId = upload.media_id, mediaKey = keyB64, durationSec = durationSec, thumbB64 = thumbB64, spoiler = spoiler, albumId = albumId))
        sendEnvelope(env, env.id, toUin)
    }

    /** Group video: encrypt once, fan out per member. */
    suspend fun sendGroupVideo(groupId: Int, bytes: ByteArray, thumbB64: String, durationSec: Int, caption: String?, spoiler: Boolean = false, albumId: String? = null) {
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobForGroup(groupId, blob)
        imageCache.put(upload.media_id, bytes)
        val env = Envelope.video(upload.media_id, keyB64, thumbB64, durationSec.toDouble(), caption, spoiler, albumId)
        sendGroupEnvelope(groupId, env, env.id, caption ?: "", kind = "video", mediaId = upload.media_id, mediaKey = keyB64, durationSec = durationSec, thumbB64 = thumbB64, spoiler = spoiler, albumId = albumId)
    }

    /** Share a geographic point (no blob, just coordinates in the envelope). */
    suspend fun sendLocation(toUin: Int, lat: Double, lng: Double, caption: String?) {
        val env = Envelope.location(lat, lng, caption)
        store(ChatMessage(env.id, toUin, true, caption ?: "", System.currentTimeMillis(), DeliveryState.SENDING, kind = "location", lat = lat, lng = lng))
        sendEnvelope(env, env.id, toUin)
    }

    suspend fun sendGroupLocation(groupId: Int, lat: Double, lng: Double, caption: String?) {
        val env = Envelope.location(lat, lng, caption)
        sendGroupEnvelope(groupId, env, env.id, caption ?: "", kind = "location", lat = lat, lng = lng)
    }

    /** Rebuild the wire envelope for a stored outgoing message (resend). */
    private fun resendEnvelope(msg: ChatMessage): Envelope = when {
        msg.kind == "photo" && msg.mediaId != null && msg.mediaKey != null ->
            Envelope.Photo(msg.id, msg.mediaId, msg.mediaKey, msg.body.ifEmpty { null }, msg.spoiler, msg.albumId)
        msg.kind == "file" && msg.mediaId != null && msg.mediaKey != null ->
            Envelope.File(msg.id, msg.mediaId, msg.mediaKey, msg.fileName ?: "file", msg.fileMime ?: "application/octet-stream", msg.fileSize ?: 0L, msg.body.ifEmpty { null })
        msg.kind == "voice" && msg.mediaId != null && msg.mediaKey != null ->
            Envelope.Voice(msg.id, msg.mediaId, msg.mediaKey, (msg.durationSec ?: 0).toDouble())
        msg.kind == "video" && msg.mediaId != null && msg.mediaKey != null ->
            Envelope.Video(msg.id, msg.mediaId, msg.mediaKey, msg.thumbB64 ?: "", (msg.durationSec ?: 0).toDouble(), msg.body.ifEmpty { null }, msg.spoiler, msg.albumId)
        msg.kind == "location" && msg.lat != null && msg.lng != null ->
            Envelope.Location(msg.id, msg.lat, msg.lng, msg.body.ifEmpty { null })
        msg.kind == "poll" -> app.rcq.android.model.PollContent.fromJson(msg.body)?.let {
            Envelope.Poll(msg.id, it.pollId, it.question, it.options, it.singleChoice, it.anonymous)
        } ?: Envelope.Text(msg.id, msg.body)
        else -> Envelope.Text(msg.id, msg.body)
    }

    /** Auto-retry every message stuck in FAILED, fired on each (re)connect. A
     *  send that died on a flaky/censored connection or a relay that was down
     *  (the red "tap to try again") now recovers on its own when the channel
     *  comes back, instead of waiting for a manual tap. Duplicate-safe: the
     *  envelope UUID is stable, so the recipient dedups anything that already
     *  half-landed. Sequential so a burst of stuck sends doesn't fan out at once. */
    private fun retryFailedSends() {
        scope.launch {
            val failed = _messages.value.values.flatten()
                .filter { it.fromMe && it.state == DeliveryState.FAILED }
            for (m in failed) runCatching { resend(m) }
        }
    }

    /** Connect-to-web: seal THIS account into the one-time relay slot [token]
     *  for the web client whose ephemeral X25519 pubkey is [webPubB64] (both
     *  read from the scanned `rcq://link` QR). The web opens the sealed blob
     *  with its ephemeral private key and logs in as this same identity. The
     *  blob carries the recovery material (keys + session token), so the caller
     *  MUST confirm with the user first — it hands web access to the account. */
    suspend fun linkWeb(token: String, webPubB64: String) {
        val uin = store.uin ?: error("not registered")
        // Mint a SEPARATE session token for the web device (revocable on its
        // own, and it flips the account to multi-device → server serves v=1).
        // The web carries this, not the phone's token.
        val jwt = api.linkDevice("Web").token.ifEmpty { error("device link failed") }
        val now = System.currentTimeMillis() / 1000
        val blob = com.google.gson.JsonObject().apply {
            addProperty("uin", uin)
            addProperty("jwt", jwt)
            addProperty("api_base", "https://${serverHost()}")
            addProperty("identity_priv", Base64.encodeToString(identityPriv(), Base64.NO_WRAP))
            addProperty("identity_pub", Base64.encodeToString(identityPub(), Base64.NO_WRAP))
            addProperty("signing_priv", Base64.encodeToString(signingPriv(), Base64.NO_WRAP))
            addProperty("signing_pub", Base64.encodeToString(signingPub(), Base64.NO_WRAP))
            addProperty("iat", now)
        }.toString().toByteArray(Charsets.UTF_8)
        val webPub = Base64.decode(webPubB64, Base64.NO_WRAP)
        val sealed = SealedSender.sealForWebLink(blob, webPub)
        api.depositLink(token, sealed)
    }

    /** Linked web sessions for the Linked Devices screen. */
    suspend fun listDevices(): List<RcqApi.DeviceInfo> = api.listDevices()

    /** Disconnect (revoke) a linked web session. */
    suspend fun revokeDevice(deviceId: String) = api.revokeDevice(deviceId)

    /** Retry a previously-failed outgoing message (same UUID, so no dup). */
    suspend fun resend(msg: ChatMessage) {
        if (!msg.fromMe || msg.state != DeliveryState.FAILED) return
        val env = resendEnvelope(msg)
        if (msg.groupId != null) {
            updateGroupMsgState(msg.groupId, msg.id, DeliveryState.SENDING)
            fanOutGroup(msg.groupId, env, msg.id)
            return
        }
        updateMessageState(msg.id, msg.peerUin, DeliveryState.SENDING)
        sendEnvelope(env, msg.id, msg.peerUin)
    }

    /**
     * Encrypt [env] to [toUin], negotiating v=2 forward secrecy: when we've
     * bootstrapped a libsignal identity AND a session with the peer exists or
     * can be established, send v=2 (Double Ratchet); otherwise fall back to
     * v=1, which every account supports. Any v=2 failure degrades to v=1
     * rather than breaking the send — v=2 is strictly additive.
     *
     * Called ONCE per logical send (not inside [withRetry]) so a retry resends
     * the identical ciphertext bytes. That is required for ratchet
     * correctness: libsignal emits a self-contained PreKeySignalMessage on
     * every send until the peer first replies, so re-POSTing the same bytes is
     * always safe (the recipient dedups), whereas re-encrypting would advance
     * the ratchet on each attempt.
     */
    private suspend fun encryptFor(toUin: Int, env: Envelope): String {
        val me = store.uin ?: error("not registered")
        val recipientPub = recipientKey(toUin)
        if (v2OutboundEnabled && signalStores.hasLocalIdentity() && toUin !in noV2Peers) {
            if (SignalSession.ensureSession(signalStores, api, toUin)) {
                runCatching {
                    return SignalSession.encrypt(signalStores, env, recipientPub, toUin, me)
                }.onFailure {
                    android.util.Log.w("RCQsignal", "v2 encrypt failed for $toUin, falling back to v1: ${it.message}")
                }
            } else {
                // Peer has no bundle (or it's unreachable): stop re-probing it
                // this session, just use v=1.
                noV2Peers.add(toUin)
            }
        }
        return SealedSender.encryptV1(env, recipientPub, me, signingPriv(), signingPub(), serverHost())
    }

    private suspend fun sendEnvelope(env: Envelope, id: String, toUin: Int) {
        // Federation (F2): if this peer is a cross-island contact, resolve their
        // island and deposit there instead of the flagship. Gated strictly —
        // for every flagship peer (no cross-island entry) the path below is
        // byte-identical to before.
        val ci = CrossIslandStore.findByUin(toUin)
        if (ci != null) {
            val me = store.uin
            val ok = me != null && runCatching {
                val sp = signingPriv(); val pp = signingPub()
                withContext(Dispatchers.IO) { CrossIslandSender.deliver(ci, env, me, sp, pp, serverHost()) }
            }.getOrDefault(false)
            updateMessageState(id, toUin, if (ok) DeliveryState.SENT else DeliveryState.FAILED)
            return
        }
        try {
            val payload = encryptFor(toUin, env)
            val resp = withRetry { api.sendSealed(toUin, payload) }
            updateMessageState(id, toUin, if (resp.delivered) DeliveryState.DELIVERED else DeliveryState.SENT)
            // Multihoming v1: best-effort sealed copy into the peer's OTHER home
            // islands; no-op (cached record lookup only) for single-homed peers.
            scope.launch(Dispatchers.IO) { runCatching { depositToPeerExtraHomes(toUin, env) } }
            // Mirror the message to the user's other devices (best-effort).
            sendMessageCarbon(env, toPeer = toUin, toGroup = null)
        } catch (e: Exception) {
            // Primary island unreachable — failover: the (possibly stale-cached)
            // record may list other homes; one accepted copy = delivered.
            val rescued = runCatching {
                withContext(Dispatchers.IO) { depositToPeerExtraHomes(toUin, env) }
            }.getOrDefault(0)
            updateMessageState(id, toUin, if (rescued > 0) DeliveryState.SENT else DeliveryState.FAILED)
        }
    }

    /** Multihoming v1 sender side: v=1-seal [env] once and deposit a copy to
     *  each of the peer's homes other than our own island (resolved from their
     *  signed record, anchored to the contact's pinned signing key). Returns
     *  the number of homes that accepted; 0 for single-homed peers. */
    private fun depositToPeerExtraHomes(toUin: Int, env: Envelope): Int {
        val me = store.uin ?: return 0
        val contact = _contacts.value.firstOrNull { it.uin == toUin } ?: return 0
        return Multihome.depositToExtraHomes(
            ownHost = api.islandHost(),
            ownUin = me,
            peerUin = toUin,
            peerIdentityKeyB64 = contact.identityKey,
            peerSigningKeyB64 = contact.signingKey,
            env = env,
            signingPriv = signingPriv(),
            signingPub = signingPub(),
        )
    }

    /**
     * Network calls fail *transiently* far more often than they fail for
     * real: a stale keep-alive socket the server already closed (the
     * classic "first POST after idle resets, the very next one works"), a
     * DNS blip, a momentary 5xx from a backend worker. A single attempt
     * then giving up is why a message "sometimes needs a manual
     * tap-to-retry" — and why the contact roster sometimes comes up empty
     * until the next cold start. So retry automatically: a few quick
     * attempts with backoff. For sends this is duplicate-safe — the
     * envelope UUID is stable across attempts, so the recipient's
     * INSERT-OR-IGNORE dedups any blob that landed before a lost response;
     * for idempotent GETs (roster, queue) a retry is free.
     */
    private suspend fun <T> withRetry(attempts: Int = 3, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                android.util.Log.w("RCQnet", "attempt ${i + 1}/$attempts failed: ${e.javaClass.simpleName}: ${e.message}")
                if (i < attempts - 1) {
                    // Most cellular send failures are a dead pooled connection
                    // the server already closed. Evict the pool so the retry
                    // opens a fresh socket instead of reusing the corpse.
                    api.evictConnections()
                    delay(300L * (i + 1) * (i + 1)) // 300ms, then 1.2s
                }
            }
        }
        throw last ?: IllegalStateException("request failed")
    }

    /** React to [target] with [emoji]: optimistic local add (deduped) then
     *  a sealed `reaction` envelope to the 1:1 peer or fanned out to the
     *  group. A reaction has no bubble or delivery state of its own, so it
     *  rides the best-effort control path, not [sendEnvelope]. */
    suspend fun sendReaction(target: ChatMessage, emoji: String) {
        val me = store.uin ?: return
        // Toggle: re-tapping your current reaction clears it (asset = null =
        // remove on the wire + on the peer). Otherwise set/replace yours.
        val newAsset: String? = if (target.reactions[me] == emoji) null else emoji
        val env = Envelope.reaction(target.id, newAsset)
        if (target.groupId != null) {
            addGroupReaction(target.groupId, target.id, me, newAsset)
            fanOutControl(target.groupId, env)
        } else {
            addPeerReaction(target.peerUin, target.id, me, newAsset)
            sendControl(target.peerUin, env)
        }
        // Echo to your OWN other devices (linked web / second phone) so a
        // reaction made here also shows there. Sealed to your own identity; the
        // receiver resolves the target message by id across all threads. The
        // origin device re-receives it but applies idempotently (no-op).
        sendControl(me, env)
    }

    /** Retract [target] for everyone (iOS delete-for-everyone). Allowed for the
     *  author, OR (in a group) a moderator: the owner / a member the owner
     *  granted the `delete` cap. Recipients re-check the same rule on receipt. */
    suspend fun sendDeleteForEveryone(target: ChatMessage) {
        val gid = target.groupId
        val canModerate = gid != null && run {
            val g = group(gid)
            val me = store.uin
            g != null && me != null && (g.members.firstOrNull { it.uin == me }?.canDelete(g.ownerUin) == true)
        }
        if (!target.fromMe && !canModerate) return
        val env = Envelope.delete(target.id)
        if (gid != null) fanOutControl(gid, env)
        else sendControl(target.peerUin, env)
        deleteLocal(target)
    }

    /** Replace the body of [target] (text only, author only) and tell the
     *  other side(s) via an `edit` envelope. */
    suspend fun sendEdit(target: ChatMessage, newText: String) {
        if (!target.fromMe || target.kind != "text" || newText.isBlank()) return
        val env = Envelope.edit(target.id, newText)
        if (target.groupId != null) {
            editInFlow(_groupMessages, target.groupId, target.id, newText)
            fanOutControl(target.groupId, env)
        } else {
            editInFlow(_messages, target.peerUin, target.id, newText)
            sendControl(target.peerUin, env)
        }
    }

    /** Acknowledge every still-unacked inbound 1:1 message from [peer] with
     *  a read receipt — unless the user set read receipts to "nobody".
     *  Called when the thread is opened and when a message arrives into the
     *  open thread. In-memory [ackedReads] keeps us from re-sending. */
    fun sendReadReceipts(peer: Int) {
        if (readReceiptsVisibility == "nobody") return
        val ids = (_messages.value[peer] ?: return)
            .filter { !it.fromMe }
            .map { it.id }
            .filterNot { ackedReads.contains(it) }
        if (ids.isEmpty()) return
        ackedReads.addAll(ids)
        scope.launch { sendControl(peer, Envelope.readReceipt(ids)) }
    }

    /** Inbound read receipt: flip our own sent messages to READ once [peer]
     *  reports seeing them. Only touches `fromMe` bubbles. */
    private fun applyReadReceipt(peer: Int, ids: List<String>) {
        val idSet = ids.toHashSet()
        val cur = _messages.value.toMutableMap()
        val list = cur[peer] ?: return
        var changed = false
        val updated = list.map { m ->
            if (m.fromMe && m.state != DeliveryState.READ && idSet.contains(m.id)) {
                changed = true
                db.updateState(m.id, DeliveryState.READ)
                m.copy(state = DeliveryState.READ)
            } else m
        }
        if (changed) { cur[peer] = updated; _messages.value = cur }
    }

    /** Encrypt + send a control envelope (e.g. a reaction) to one peer.
     *  Reuses the send-retry but tracks no delivery state. */
    private suspend fun sendControl(toUin: Int, env: Envelope) {
        runCatching {
            val payload = encryptFor(toUin, env)
            withRetry { api.sendSealed(toUin, payload) }
        }
    }

    /** Message kinds we mirror to the user's other devices via a carbon.
     *  Reactions sync through their own self-echo; control/poll don't sync. */
    private fun isCarbonable(env: Envelope): Boolean = when (env) {
        is Envelope.Text, is Envelope.Photo, is Envelope.Video,
        is Envelope.Voice, is Envelope.File, is Envelope.Location -> true
        else -> false
    }

    /** Mirror a just-sent message to the user's OTHER logged-in devices: seal a
     *  Carbon (the original envelope + its destination) to our own identity and
     *  POST it to our own uin with a NON-pushable type, so it syncs over WS /
     *  the per-device queue without buzzing us about our own message. The other
     *  device files the inner message as fromMe; the origin device dedups its
     *  own carbon by id. Best-effort — the message already went out. */
    private suspend fun sendMessageCarbon(inner: Envelope, toPeer: Int?, toGroup: Int?) {
        if (!isCarbonable(inner)) return
        val me = store.uin ?: return
        runCatching {
            val carbon = Envelope.Carbon(to = toPeer, gid = toGroup, env = inner)
            val payload = encryptFor(me, carbon)
            withRetry { api.sendSealed(me, payload, envelopeType = "carbon") }
        }
    }

    /** Fan a control envelope out to every other group member, best effort. */
    private suspend fun fanOutControl(groupId: Int, env: Envelope) = withContext(Dispatchers.IO) {
        val me = store.uin ?: return@withContext
        val group = group(groupId) ?: return@withContext
        runCatching {
            // Same per-member isolation as fanOutGroup: one unusable key must not
            // drop the reaction/edit/delete for the whole group.
            val payloads = group.members
                .filter { it.uin != me && it.identityKey.isNotEmpty() }
                .mapNotNull { m ->
                    runCatching {
                        RcqApi.GroupPayload(
                            to_uin = m.uin,
                            payload = SealedSender.encryptV1(
                                envelope = env,
                                recipientIdentityPub = Base64.decode(m.identityKey, Base64.NO_WRAP),
                                ownUin = me,
                                signingPriv = signingPriv(),
                                signingPub = signingPub(),
                            ),
                        )
                    }.getOrNull()
                }
            if (payloads.isNotEmpty()) withRetry { api.sendGroupSealed(groupId, payloads) }
        }
    }

    // On-disk cache of ENCRYPTED media blobs (the exact bytes the server
    // returns). Decryption needs the per-message mediaKey, which lives only
    // in the encrypted message DB — so nothing here is sensitive at rest,
    // yet it lets a previously-loaded image render OFFLINE and survive an app
    // restart (report #5: "images vanish when the internet drops"). Bounded
    // so it can't grow without limit (the historical "Кэш 2гб" failure mode).
    private val mediaDiskDir: java.io.File by lazy {
        java.io.File(appCtx.cacheDir, "media").apply { mkdirs() }
    }
    private val mediaDiskCapBytes = 200L * 1024 * 1024
    private fun mediaDiskFile(mediaId: String): java.io.File =
        java.io.File(mediaDiskDir, mediaId.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    /** Evict oldest (by last-modified) encrypted blobs until under the cap. */
    private fun trimMediaDiskCache() {
        runCatching {
            val files = mediaDiskDir.listFiles()?.toList() ?: return
            var total = files.sumOf { it.length() }
            if (total <= mediaDiskCapBytes) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= mediaDiskCapBytes) break
                total -= f.length()
                f.delete()
            }
        }
    }

    /** Download + decrypt a media blob. Cached in memory by media id, and on
     *  disk as the still-encrypted blob so it's available offline / after a
     *  restart (decrypt key stays in the encrypted DB). */
    suspend fun fetchImage(mediaId: String, mediaKey: String, host: String? = null): ByteArray? {
        imageCache.get(mediaId)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val key = Base64.decode(mediaKey, Base64.NO_WRAP)
                val file = mediaDiskFile(mediaId)
                // Disk first (works offline), else fetch + persist the blob.
                // §5c: media in a foreign group lives on the GROUP's island.
                val blob = if (file.exists()) {
                    file.readBytes()
                } else {
                    (if (host != null) RcqApi("https://$host") else api).getBlob(mediaId).also {
                        runCatching { file.writeBytes(it); trimMediaDiskCache() }
                    }
                }
                MediaCrypto.open(blob, key).also { imageCache.put(mediaId, it) }
            }.getOrNull()
        }
    }

    fun sendTyping(toUin: Int, active: Boolean) {
        socket.send("{\"type\":\"typing\",\"to_uin\":$toUin,\"active\":$active}")
    }

    private suspend fun recipientKey(uin: Int): ByteArray {
        peerIdentityCache[uin]?.let { return it }
        val keyB64 = _contacts.value.firstOrNull { it.uin == uin }?.identityKey
            ?: api.userInfo(uin).identity_key
            ?: throw IllegalStateException("peer has no identity key")
        return Base64.decode(keyB64, Base64.NO_WRAP).also { peerIdentityCache[uin] = it }
    }

    private fun ingest(payloadB64: String) {
        runCatching {
            val dec = decryptInbound(payloadB64)
            // Removed contacts are silently dropped — sealed sender means
            // the server can't filter by sender, so we gate on receipt.
            if (LocalStores.isRemoved(dec.senderUin)) return@runCatching
            // §5d cross-island call signaling rides sealed envelopes (kind
            // "call") — route to the call state machine, never the message
            // store, and never the request quarantine (signals are ephemeral).
            // Only an ACCEPTED cross-island contact may ring us; a stale offer
            // (old ts — offline-queue drains deliver hours-old rows) is filed
            // as a missed call instead of ringing.
            (dec.envelope as? Envelope.CallSignal)?.let { cs ->
                val host = dec.senderHost ?: return@runCatching // same-island calls use the WS, not envelopes
                if (host == serverHost()) return@runCatching
                if (CrossIslandStore.get(dec.senderUin, host) == null) return@runCatching
                if (cs.sig == "call_offer" && System.currentTimeMillis() / 1000 - cs.ts > callOfferTtlSec) {
                    val media = appCtx.getString(
                        if (cs.data["media"] == "video") app.rcq.android.R.string.call_hist_video
                        else app.rcq.android.R.string.call_hist_voice,
                    )
                    logCallHistory(dec.senderUin, fromMe = false, text = "$media · ${appCtx.getString(app.rcq.android.R.string.call_out_missed)}")
                    return@runCatching
                }
                val sigObj = com.google.gson.JsonObject().apply {
                    addProperty("from_uin", dec.senderUin)
                    addProperty("call_id", cs.cid)
                    cs.data.forEach { (k, v) -> addProperty(k, v) }
                }
                calls.onSignal(cs.sig, sigObj)
                return@runCatching
            }
            // Federation gossip B1 self-push: a contact handed us their fresh
            // signed home-island record. Verify it's signed by the SAME key that
            // signed this envelope (binds it to the real sender), reject a ts
            // rollback, cache their homes. Intercepted here so it never reaches
            // the message store or the cross-island quarantine.
            (dec.envelope as? Envelope.HomeRecord)?.let { hr ->
                Multihome.applyPushedRecord(dec.senderUin, dec.senderSigningPub, hr.rec)
                return@runCatching
            }
            // Variant A consent: a 1:1 message from an un-accepted CROSS-ISLAND
            // sender (its from_host isn't ours and we haven't added them) is
            // QUARANTINED as a request instead of landing in the chat list.
            // Blocked → hold() drops it. We hold the raw payload so Accept can
            // re-ingest it (now an accepted contact → passes this gate).
            val ciHost = dec.senderHost
            val meUin = store.uin ?: 0
            if (ciHost != null && ciHost != serverHost() && dec.senderUin != meUin &&
                CrossIslandStore.get(dec.senderUin, ciHost) == null
            ) {
                CrossIslandRequestsStore.hold(meUin, dec.senderUin, ciHost, payloadB64, ciPreview(dec.envelope))
                refreshCiRequests()
                return@runCatching
            }
            val now = System.currentTimeMillis()
            // Random-chat peer: keep the conversation ephemeral (in-memory,
            // never persisted, never on Home). Text only for v=1.
            if (dec.senderUin == activeRandomPeer) {
                (dec.envelope as? Envelope.Text)?.let { appendRandom(ChatMessage(it.id, dec.senderUin, fromMe = false, body = it.text, sentAt = now)) }
                return@runCatching
            }
            when (val env = dec.envelope) {
                is Envelope.Text ->
                    store(ChatMessage(env.id, dec.senderUin, false, env.text, now, replyToSnippet = env.replyTo?.snippet, replyToAuthor = env.replyTo?.authorName, replyToId = env.replyTo?.id))
                is Envelope.Photo ->
                    store(ChatMessage(env.id, dec.senderUin, false, env.caption ?: "", now, kind = "photo", mediaId = env.mediaId, mediaKey = env.mediaKey, spoiler = env.spoiler, albumId = env.albumId))
                is Envelope.File ->
                    store(ChatMessage(env.id, dec.senderUin, false, env.caption ?: "", now, kind = "file", mediaId = env.mediaId, mediaKey = env.mediaKey, fileName = env.fileName, fileMime = env.mime, fileSize = env.sizeBytes))
                is Envelope.Voice ->
                    store(ChatMessage(env.id, dec.senderUin, false, "", now, kind = "voice", mediaId = env.mediaId, mediaKey = env.mediaKey, durationSec = env.durationSec.toInt()))
                is Envelope.Video ->
                    store(ChatMessage(env.id, dec.senderUin, false, env.caption ?: "", now, kind = "video", mediaId = env.mediaId, mediaKey = env.mediaKey, durationSec = env.durationSec.toInt(), thumbB64 = env.thumbnailB64, spoiler = env.spoiler, albumId = env.albumId))
                is Envelope.Location ->
                    store(ChatMessage(env.id, dec.senderUin, false, env.caption ?: "", now, kind = "location", lat = env.lat, lng = env.lng))
                is Envelope.Reaction -> applyReactionByTargetId(env.targetId, dec.senderUin, env.asset)
                is Envelope.Delete -> {
                    // Author-only: a peer can only retract their own message.
                    val t = _messages.value[dec.senderUin]?.firstOrNull { it.id == env.targetId }
                    if (t != null && !t.fromMe) deleteInFlow(_messages, dec.senderUin, env.targetId)
                }
                is Envelope.Edit -> {
                    val t = _messages.value[dec.senderUin]?.firstOrNull { it.id == env.targetId }
                    if (t != null && !t.fromMe) editInFlow(_messages, dec.senderUin, env.targetId, env.text)
                }
                is Envelope.ReadReceipt -> applyReadReceipt(dec.senderUin, env.targetIds)
                is Envelope.Visit -> app.rcq.android.data.VisitStore.record(dec.senderUin, env.atEpochMillis())
                is Envelope.SecureScreen ->
                    // Peer toggled per-conversation secure mode — mirror it so
                    // OUR side also enforces FLAG_SECURE for this chat.
                    LocalStores.setThreadSecure(LocalStores.peerThread(dec.senderUin), env.on)
                is Envelope.ScreenshotTaken -> {
                    // Peer took a screenshot in a secure chat — post a notice
                    // with their name (resolved + localized on our side).
                    val name = _contacts.value.firstOrNull { it.uin == dec.senderUin }?.nickname ?: "#${dec.senderUin}"
                    store(ChatMessage(env.id, dec.senderUin, fromMe = false, body = appCtx.getString(app.rcq.android.R.string.secscreen_peer_screenshot, name), sentAt = now, kind = "system"))
                }
                is Envelope.Poll -> Unit       // polls are group-only; ignore in 1:1
                is Envelope.CallSignal -> Unit // intercepted above, never reaches here
                is Envelope.HomeRecord -> Unit // intercepted above, never reaches here
                is Envelope.Carbon ->
                    // A message I sent from ANOTHER device, echoed to my own uin.
                    // Only honour my own carbon; file the inner message as fromMe
                    // in its destination thread (dedup by id; no badge/sound).
                    if (dec.senderUin == store.uin) storeCarbon(env.to, env.gid, env.env, now)
                is Envelope.Unknown -> Unit
            }
        }.onFailure { logDecryptFailure(payloadB64, it) }
    }

    /** File a carbon's inner message as a fromMe row in its destination thread
     *  (group [gid] or peer [to]). Mirrors the incoming construction but marks
     *  it ours. store()/storeGroup() dedup by id (INSERT-OR-IGNORE), so the
     *  origin device's own carbon and any queue redelivery are no-ops. */
    private fun storeCarbon(to: Int?, gid: Int?, inner: Envelope, now: Long) {
        val me = store.uin ?: return
        if (gid != null) {
            when (inner) {
                is Envelope.Text -> storeGroup(ChatMessage(inner.id, 0, true, inner.text, now, kind = "text", groupId = gid, senderUin = me, replyToSnippet = inner.replyTo?.snippet, replyToAuthor = inner.replyTo?.authorName, replyToId = inner.replyTo?.id))
                is Envelope.Photo -> storeGroup(ChatMessage(inner.id, 0, true, inner.caption ?: "", now, kind = "photo", mediaId = inner.mediaId, mediaKey = inner.mediaKey, groupId = gid, senderUin = me, spoiler = inner.spoiler, albumId = inner.albumId))
                is Envelope.File -> storeGroup(ChatMessage(inner.id, 0, true, inner.caption ?: "", now, kind = "file", mediaId = inner.mediaId, mediaKey = inner.mediaKey, fileName = inner.fileName, fileMime = inner.mime, fileSize = inner.sizeBytes, groupId = gid, senderUin = me))
                is Envelope.Voice -> storeGroup(ChatMessage(inner.id, 0, true, "", now, kind = "voice", mediaId = inner.mediaId, mediaKey = inner.mediaKey, durationSec = inner.durationSec.toInt(), groupId = gid, senderUin = me))
                is Envelope.Video -> storeGroup(ChatMessage(inner.id, 0, true, inner.caption ?: "", now, kind = "video", mediaId = inner.mediaId, mediaKey = inner.mediaKey, durationSec = inner.durationSec.toInt(), thumbB64 = inner.thumbnailB64, groupId = gid, senderUin = me, spoiler = inner.spoiler, albumId = inner.albumId))
                is Envelope.Location -> storeGroup(ChatMessage(inner.id, 0, true, inner.caption ?: "", now, kind = "location", lat = inner.lat, lng = inner.lng, groupId = gid, senderUin = me))
                else -> Unit
            }
        } else if (to != null) {
            when (inner) {
                is Envelope.Text -> store(ChatMessage(inner.id, to, true, inner.text, now, replyToSnippet = inner.replyTo?.snippet, replyToAuthor = inner.replyTo?.authorName, replyToId = inner.replyTo?.id))
                is Envelope.Photo -> store(ChatMessage(inner.id, to, true, inner.caption ?: "", now, kind = "photo", mediaId = inner.mediaId, mediaKey = inner.mediaKey, spoiler = inner.spoiler, albumId = inner.albumId))
                is Envelope.File -> store(ChatMessage(inner.id, to, true, inner.caption ?: "", now, kind = "file", mediaId = inner.mediaId, mediaKey = inner.mediaKey, fileName = inner.fileName, fileMime = inner.mime, fileSize = inner.sizeBytes))
                is Envelope.Voice -> store(ChatMessage(inner.id, to, true, "", now, kind = "voice", mediaId = inner.mediaId, mediaKey = inner.mediaKey, durationSec = inner.durationSec.toInt()))
                is Envelope.Video -> store(ChatMessage(inner.id, to, true, inner.caption ?: "", now, kind = "video", mediaId = inner.mediaId, mediaKey = inner.mediaKey, durationSec = inner.durationSec.toInt(), thumbB64 = inner.thumbnailB64, spoiler = inner.spoiler, albumId = inner.albumId))
                is Envelope.Location -> store(ChatMessage(inner.id, to, true, inner.caption ?: "", now, kind = "location", lat = inner.lat, lng = inner.lng))
                else -> Unit
            }
        }
    }

    private suspend fun drainQueue() {
        CrashReporter.crumb(appCtx, "drain_queue")
        api.drainQueue().forEach { q ->
            val payload = q.payload ?: return@forEach
            if (q.group_id != null) ingestGroup(payload, q.group_id) else ingest(payload)
        }
        CrashReporter.crumb(appCtx, "drain_done")
    }

    // ── contacts ─────────────────────────────────────────────────────

    suspend fun addContact(uin: Int) {
        api.requestContact(uin)
        runCatching { refreshContacts() }
        runCatching { refreshPending() }
        runCatching { refreshOutgoing() }
    }

    /** Federation (F2): add a cross-island contact `uin@host` — fetch their
     *  island's open key card and store it locally (no flagship contact-request;
     *  they're on another island). Returns true on success. */
    /** Spec §5: my shareable contact handles — (QR payload `rcq://add/…`,
     *  https share link). Both carry the island (`h=`, omitted on the flagship)
     *  + the advisory signing key (`k=`); a flagship account degrades to the
     *  legacy bare forms old clients already parse. */
    fun contactLinks(): Pair<String, String> {
        val uin = store.uin ?: return "" to ""
        val a = RcqFederation.Address(uin, serverHost())
        val sk = runCatching {
            android.util.Base64.encodeToString(signingPub(), android.util.Base64.NO_WRAP)
        }.getOrNull()
        return RcqFederation.buildContactQr(a, sk) to RcqFederation.buildContactLink(a, sk)
    }

    suspend fun addCrossIslandContact(uin: Int, host: String): Boolean = withContext(Dispatchers.IO) {
        val card = runCatching { CrossIslandSender.fetchCard(host, uin) }.getOrNull() ?: return@withContext false
        CrossIslandStore.save(
            CrossIslandStore.Contact(
                uin = uin, host = host,
                nickname = card.nickname?.takeIf { it.isNotBlank() } ?: "$uin@$host",
                identityKey = card.identityKey, signingKey = card.signingKey,
                signalIdentityKey = card.signalIdentityKey, addedAt = System.currentTimeMillis(),
                gender = card.gender, statusMessage = card.statusMessage,
            )
        )
        true
    }

    // ── cross-island message requests (Variant A consent) ──

    val ciRequests = MutableStateFlow<List<CrossIslandRequestsStore.Request>>(emptyList())

    fun refreshCiRequests() {
        ciRequests.value = store.uin?.let { CrossIslandRequestsStore.list(it) } ?: emptyList()
    }

    private fun ciPreview(env: Envelope): String = when (env) {
        is Envelope.Text -> env.text
        is Envelope.Photo -> env.caption?.takeIf { it.isNotEmpty() } ?: "📷"
        is Envelope.Video -> env.caption?.takeIf { it.isNotEmpty() } ?: "🎬"
        is Envelope.Voice -> "🎤"
        is Envelope.File -> "📎 ${env.fileName}"
        is Envelope.Location -> "📍"
        else -> ""
    }

    /** Cross-island contacts rendered as ordinary [Contact]s so they show in the
     *  chat list (the send path still routes them by [CrossIslandStore] host). */
    private fun crossIslandContacts(): List<Contact> = CrossIslandStore.list().map { c ->
        // callable: §5d made cross-island 1:1 calls work (signaling crosses as
        // sealed deposits; media is P2P either way).
        Contact(uin = c.uin, nickname = c.nickname, identityKey = c.identityKey, signingKey = c.signingKey, status = "offline", callable = true, host = c.host, gender = c.gender, statusMessage = c.statusMessage)
    }

    /** Append cross-island contacts to the displayed roster (skip uin already
     *  held by a same-island contact). Called after every contacts refresh
     *  (which overwrites the list) + on accept. */
    fun mergeCrossIslandContacts() {
        val extra = crossIslandContacts().filter { c -> _contacts.value.none { it.uin == c.uin } }
        if (extra.isNotEmpty()) _contacts.value = _contacts.value + extra
    }

    /** Accept a cross-island request: save the sender as a contact FIRST (so the
     *  held payloads pass the consent gate), then re-ingest them so the messages
     *  surface in the now-visible thread, and surface the contact in the roster. */
    suspend fun acceptCrossIslandRequest(uin: Int, host: String) {
        val me = store.uin ?: return
        addCrossIslandContact(uin, host)
        CrossIslandRequestsStore.clear(me, uin, host)?.msgs?.forEach { ingest(it.payload) }
        mergeCrossIslandContacts()
        refreshCiRequests()
    }

    fun blockCrossIslandRequest(uin: Int, host: String) {
        val me = store.uin ?: return
        CrossIslandRequestsStore.block(me, uin, host)
        refreshCiRequests()
    }

    /** Server-side search for the Add window (users + joinable groups). */
    suspend fun searchUsers(q: String): List<RcqApi.UserInfo> =
        runCatching { api.searchUsers(q) }.getOrNull() ?: emptyList()

    suspend fun searchGroups(q: String): List<RcqApi.GroupPreviewOut> =
        runCatching { api.searchGroups(q) }.getOrNull() ?: emptyList()

    suspend fun respond(requestId: Int, accept: Boolean) {
        api.respondContact(requestId, accept)
        runCatching { refreshContacts() }
        runCatching { refreshPending() }
    }

    private suspend fun refreshContacts() {
        // Snapshot presence before the refresh so we can play a sound on
        // online/offline transitions (iOS SoundService parity). Skipped on
        // the first populate (empty snapshot) to avoid a launch-time burst.
        val prevPresence = _contacts.value.associate { it.uin to it.presence }
        _contacts.value = api.contacts().map {
            Contact(
                uin = it.uin,
                nickname = it.nickname ?: "${it.uin}",
                identityKey = it.identity_key ?: "",
                signingKey = it.signing_key,
                status = it.status,
                statusMessage = it.status_message,
                blocked = it.blocked,
                gender = it.gender,
                lastSeen = parseIso(it.last_seen),
                callable = it.callable,
            )
        }
        if (prevPresence.isNotEmpty()) {
            _contacts.value.forEach { ct ->
                val before = prevPresence[ct.uin] ?: return@forEach
                val wasOnline = before != UserStatus.OFFLINE
                val isOnline = ct.presence != UserStatus.OFFLINE
                if (!wasOnline && isOnline) app.rcq.android.media.SoundService.contactOnline()
                else if (wasOnline && !isOnline) app.rcq.android.media.SoundService.contactOffline()
            }
        }
        // Seed the identity cache so sends to contacts skip a lookup.
        _contacts.value.forEach { c ->
            if (c.identityKey.isNotEmpty()) {
                peerIdentityCache[c.uin] = Base64.decode(c.identityKey, Base64.NO_WRAP)
            }
        }
        // Persist the roster so the chat list is reachable offline (report #7).
        runCatching { LocalStores.setCachedContactsJson(profileGson.toJson(_contacts.value)) }
        // Cross-island contacts aren't in the server roster — surface them too.
        mergeCrossIslandContacts()
    }

    private suspend fun refreshPending() {
        _pending.value = api.pending().map {
            PendingRequest(it.id, it.from_uin, it.nickname ?: "#${it.from_uin}")
        }
    }

    private suspend fun refreshOutgoing() {
        _outgoing.value = api.outgoing().map {
            OutgoingRequest(it.to_uin, it.nickname ?: "#${it.to_uin}", it.state ?: "pending")
        }
    }

    /** Pull the latest sent-requests list (call when opening the screen). */
    suspend fun loadOutgoing() { runCatching { refreshOutgoing() } }

    /** Cancel/revoke a sent request, or dismiss a declined one, then refresh. */
    suspend fun cancelOutgoing(toUin: Int) {
        runCatching { api.cancelOutgoing(toUin) }
        runCatching { refreshOutgoing() }
    }

    fun contactName(uin: Int): String =
        _contacts.value.firstOrNull { it.uin == uin }?.nickname ?: "#$uin"

    /** Append a call-summary line to the 1:1 thread (kind="call"), so a
     *  finished/missed call shows in the chat history. Called by
     *  [CallController] on every call end. */
    private fun logCallHistory(peerUin: Int, fromMe: Boolean, text: String) {
        if (!::db.isInitialized) return
        store(
            ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                peerUin = peerUin,
                fromMe = fromMe,
                body = text,
                sentAt = System.currentTimeMillis(),
                state = DeliveryState.DELIVERED,
                kind = "call",
            ),
        )
    }

    fun contact(uin: Int): Contact? = _contacts.value.firstOrNull { it.uin == uin }

    /** Parse a server ISO-8601 timestamp (with or without timezone) to
     *  epoch millis. Pydantic emits naive UTC for `last_seen`; tolerate
     *  both forms. */
    private fun parseIso(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(s).toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching {
                java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            }
            .getOrNull()
    }

    // ── WS events ────────────────────────────────────────────────────

    private fun handleEvent(type: String, obj: JsonObject) {
        when (type) {
            "message", "system" -> {
                val payload = obj.get("payload")?.asString
                val gid = obj.get("group_id")?.takeIf { !it.isJsonNull }?.asInt
                if (payload != null) {
                    if (gid != null) ingestGroup(payload, gid) else ingest(payload)
                }
            }
            "group_created", "group_membership_changed" -> {
                obj.getAsJsonObject("group")?.let { gj ->
                    scope.launch {
                        runCatching { upsertGroup(mapGroup(gson.fromJson(gj, RcqApi.GroupOut::class.java))) }
                    }
                }
            }
            "group_deleted" -> {
                obj.get("group_id")?.asInt?.let { gid -> _groups.value = _groups.value.filterNot { it.id == gid } }
            }
            "contact_request", "contact_response", "contact_removed" -> {
                scope.launch { runCatching { refreshContacts() }; runCatching { refreshPending() }; runCatching { refreshOutgoing() } }
            }
            // A request we received was revoked by its sender — drop it from
            // our incoming list (the row is already gone server-side).
            "contact_request_cancelled" -> {
                scope.launch { runCatching { refreshPending() } }
            }
            "typing" -> {
                val from = obj.get("from_uin")?.asInt
                val active = obj.get("active")?.asBoolean ?: false
                if (active && from != null) {
                    _typingFrom.value = from
                    val seq = ++typingSeq
                    scope.launch { delay(6000); if (typingSeq == seq) _typingFrom.value = null }
                } else {
                    _typingFrom.value = null
                }
            }
            "call_offer", "call_answer", "call_ice", "call_end",
            "call_renegotiate", "call_renegotiate_answer", "call_renegotiate_decline" ->
                calls.onSignal(type, obj)
            "room_roster", "room_member_entered", "room_member_left", "room_offer",
            "room_answer", "room_ice", "room_speaking", "room_enter_rejected", "room_deleted" ->
                audioRooms.onSignal(type, obj)
            "hood_message", "hood_count", "hood_delete", "hood_reaction" ->
                hood.onSignal(type, obj)
            "presence" -> scope.launch { runCatching { refreshContacts() } }
            "story_posted", "story_deleted" -> scope.launch { runCatching { refreshStories() } }
            "random_match" -> {
                val pairId = obj.get("pair_id")?.takeIf { !it.isJsonNull }?.asString
                obj.getAsJsonObject("peer")?.let { p ->
                    runCatching { gson.fromJson(p, RcqApi.RandomPeerInfo::class.java) }.getOrNull()?.let { peer ->
                        enterMatch(pairId, peer, obj.get("expires_at")?.takeIf { !it.isJsonNull }?.asString)
                    }
                }
            }
            "random_end" -> {
                val pairId = obj.get("pair_id")?.takeIf { !it.isJsonNull }?.asString
                if (pairId == null || pairId == activeRandomPairId) {
                    activeRandomPeer = null
                    activeRandomPairId = null
                    _random.value = RandomState.Ended(obj.get("reason")?.takeIf { !it.isJsonNull }?.asString ?: "ended")
                }
            }
            else -> Unit
        }
    }

    // ── persistence + flow updates ───────────────────────────────────

    private fun loadMessagesFromDb() {
        val all = db.all()
        _messages.value = all.filter { it.groupId == null }.groupBy { it.peerUin }
        _groupMessages.value = all.filter { it.groupId != null }.groupBy { it.groupId!! }
    }

    /** Seed the contact/group roster from the on-disk cache so the chat list
     *  (and its locally-stored history) is reachable OFFLINE instead of an
     *  endless "Connecting…" screen (report #7). A live refresh overwrites
     *  this once connected; contacts are forced to offline here since we
     *  can't vouch for anyone's presence while disconnected. */
    private fun loadCachedRoster() {
        if (_contacts.value.isEmpty()) {
            LocalStores.cachedContactsJson()?.let { json ->
                runCatching { profileGson.fromJson(json, Array<Contact>::class.java) }
                    .getOrNull()?.let { arr -> _contacts.value = arr.map { it.copy(status = "offline") } }
            }
        }
        if (_groups.value.isEmpty()) {
            LocalStores.cachedGroupsJson()?.let { json ->
                runCatching { profileGson.fromJson(json, Array<RcqGroup>::class.java) }
                    .getOrNull()?.let { arr -> _groups.value = arr.toList() }
            }
        }
        mergeCrossIslandContacts()
    }

    private fun store(msg: ChatMessage) {
        // INSERT OR IGNORE dedups by envelope UUID (WS vs queue overlap).
        if (!db.insert(msg)) return
        val cur = _messages.value.toMutableMap()
        cur[msg.peerUin] = ((cur[msg.peerUin] ?: emptyList()) + msg).sortedBy { it.sentAt }
        _messages.value = cur
        bumpUnreadIfInbound(msg, LocalStores.peerThread(msg.peerUin))
        // Arrived into the open thread → ack it immediately with a receipt.
        if (!msg.fromMe && LocalStores.peerThread(msg.peerUin) == activeThread) sendReadReceipts(msg.peerUin)
    }

    /** A transient in-app notification banner (#11): shown at the top while the
     *  app is open and the user is NOT in that chat, so they see WHERE a message
     *  landed (Android had only a sound). The preview line is built in the UI so
     *  the media-kind fallback stays localized. */
    data class InAppBanner(
        val thread: String,
        val title: String,
        val sender: String?,   // group sender label; null for 1:1
        val body: String,
        val kind: String,
        val peerUin: Int?,     // non-null → tapping opens this 1:1
        val groupId: Int?,     // non-null → tapping opens this group
    )
    private val _banner = MutableStateFlow<InAppBanner?>(null)
    val banner: StateFlow<InAppBanner?> = _banner.asStateFlow()
    fun dismissBanner() { _banner.value = null }

    /** True when [body] @mentions me — by `@<my nick>` or `#<my uin>` — used to
     *  gate sound/banner for groups in "mentions only" notify mode. */
    private fun bodyMentionsMe(body: String): Boolean {
        if (body.isEmpty()) return false
        store.uin?.let { if (body.contains("#$it")) return true }
        val nick = store.nickname?.takeIf { it.isNotBlank() } ?: return false
        return body.contains("@$nick", ignoreCase = true)
    }

    /** Bump the unread badge for a genuinely-new inbound message, unless
     *  the user is currently looking at that thread. Own (fromMe)
     *  messages never count. */
    private fun bumpUnreadIfInbound(msg: ChatMessage, thread: String) {
        if (msg.fromMe) return
        // Badge only counts for threads you're NOT looking at; the receive
        // SOUND plays for any inbound message that passes the notify gate —
        // including the open chat (iOS/Telegram behaviour). The old code
        // returned early for the active thread, so a tester sitting inside a
        // chat heard nothing.
        if (thread == activeThread) LocalStores.clearUnread(thread)
        else LocalStores.bumpUnread(thread)
        // Skip the receive sound during the post-connect backfill window so a
        // pile of messages missed while away doesn't ring N times on open.
        val live = System.currentTimeMillis() >= soundsSuppressedUntil
        // Notify gate (#11): NONE = silent, MENTIONS = only when @mentioned,
        // ALL = always. One authoritative read of the mute state (no second
        // push path on Android), so mute is deterministic.
        val ring = when (LocalStores.notifyMode(thread)) {
            LocalStores.NotifyMode.NONE -> false
            LocalStores.NotifyMode.MENTIONS -> msg.groupId != null && bodyMentionsMe(msg.body)
            LocalStores.NotifyMode.ALL -> true
        }
        if (live && ring) app.rcq.android.media.SoundService.message()
        // In-app banner for a thread you're NOT looking at — same gate as the
        // sound, so a muted / non-mention message never interrupts.
        if (live && ring && thread != activeThread) emitBanner(msg, thread)
    }

    private fun emitBanner(msg: ChatMessage, thread: String) {
        val gid = msg.groupId
        if (gid != null) {
            val sender = _contacts.value.firstOrNull { it.uin == msg.senderUin }?.nickname
                ?: msg.senderUin?.let { "#$it" }
            _banner.value = InAppBanner(thread, groupName(gid), sender, msg.body, msg.kind, null, gid)
        } else {
            val name = _contacts.value.firstOrNull { it.uin == msg.peerUin }?.nickname ?: "#${msg.peerUin}"
            _banner.value = InAppBanner(thread, name, null, msg.body, msg.kind, msg.peerUin, null)
        }
    }

    /** Wall-clock until which inbound receive sounds are muted — set on every
     *  WS connect to cover the offline-message backfill burst (see onState). */
    @Volatile
    private var soundsSuppressedUntil = 0L

    /** The thread the user currently has open (or null). Set by the UI so
     *  inbound messages to it don't raise a badge, and so a message that
     *  arrives while it's open is immediately marked read. */
    @Volatile
    var activeThread: String? = null
        private set

    fun openThread(thread: String) {
        activeThread = thread
        LocalStores.clearUnread(thread)
    }

    fun closeThread() {
        activeThread = null
    }

    private fun updateMessageState(id: String, peer: Int, state: DeliveryState) {
        db.updateState(id, state)
        val cur = _messages.value.toMutableMap()
        cur[peer] = (cur[peer] ?: emptyList()).map { if (it.id == id) it.copy(state = state) else it }
        _messages.value = cur
    }

    private fun storeGroup(msg: ChatMessage) {
        if (!db.insert(msg)) return
        val gid = msg.groupId ?: return
        val cur = _groupMessages.value.toMutableMap()
        cur[gid] = ((cur[gid] ?: emptyList()) + msg).sortedBy { it.sentAt }
        _groupMessages.value = cur
        bumpUnreadIfInbound(msg, LocalStores.groupThread(gid))
    }

    private fun updateGroupMsgState(groupId: Int, id: String, state: DeliveryState) {
        db.updateState(id, state)
        val cur = _groupMessages.value.toMutableMap()
        cur[groupId] = (cur[groupId] ?: emptyList()).map { if (it.id == id) it.copy(state = state) else it }
        _groupMessages.value = cur
    }

    /** Set/clear [reactorUin]'s reaction on a 1:1 message (one reaction per
     *  user): [asset] null removes it. Persists + updates the flow. No-op if
     *  the message isn't in the thread or the state is unchanged. */
    /** Apply a reaction located by its target message id across ALL threads
     *  (1:1 and group). The target id is a globally-unique UUID, so it resolves
     *  to exactly one message wherever it lives. Used for inbound 1:1 reactions
     *  AND for self-echoes (a reaction you made on another device, sealed to
     *  your own identity): such an envelope carries only the target id, not the
     *  conversation, so the thread can't be inferred from the sender. */
    private fun applyReactionByTargetId(targetId: String, reactorUin: Int, asset: String?) {
        _messages.value.keys.firstOrNull { peer -> _messages.value[peer]?.any { it.id == targetId } == true }
            ?.let { peer -> addPeerReaction(peer, targetId, reactorUin, asset); return }
        _groupMessages.value.keys.firstOrNull { gid -> _groupMessages.value[gid]?.any { it.id == targetId } == true }
            ?.let { gid -> addGroupReaction(gid, targetId, reactorUin, asset) }
    }

    private fun addPeerReaction(peer: Int, targetId: String, reactorUin: Int, asset: String?) {
        val cur = _messages.value.toMutableMap()
        val list = cur[peer] ?: return
        var changed = false
        val updated = list.map { m ->
            if (m.id == targetId) {
                val r = if (asset == null) m.reactions - reactorUin else m.reactions + (reactorUin to asset)
                if (r != m.reactions) {
                    changed = true
                    db.updateReactions(targetId, r)
                    m.copy(reactions = r)
                } else m
            } else m
        }
        if (changed) { cur[peer] = updated; _messages.value = cur }
    }

    /** Replace a message's body in a thread flow (+ DB), flagging it edited.
     *  Caller enforces who's allowed to edit (own send, or inbound author). */
    private fun editInFlow(flow: MutableStateFlow<Map<Int, List<ChatMessage>>>, key: Int, id: String, text: String) {
        val cur = flow.value.toMutableMap()
        val list = cur[key] ?: return
        if (list.none { it.id == id }) return
        db.updateBody(id, text)
        cur[key] = list.map { if (it.id == id) it.copy(body = text, edited = true) else it }
        flow.value = cur
    }

    /** Remove a message from a thread flow (+ DB). Caller enforces authority. */
    private fun deleteInFlow(flow: MutableStateFlow<Map<Int, List<ChatMessage>>>, key: Int, id: String) {
        val cur = flow.value.toMutableMap()
        val list = cur[key] ?: return
        if (list.none { it.id == id }) return
        db.delete(id)
        cur[key] = list.filterNot { it.id == id }
        flow.value = cur
    }

    /** Group analogue of [addPeerReaction]. */
    private fun addGroupReaction(groupId: Int, targetId: String, reactorUin: Int, asset: String?) {
        val cur = _groupMessages.value.toMutableMap()
        val list = cur[groupId] ?: return
        var changed = false
        val updated = list.map { m ->
            if (m.id == targetId) {
                val r = if (asset == null) m.reactions - reactorUin else m.reactions + (reactorUin to asset)
                if (r != m.reactions) {
                    changed = true
                    db.updateReactions(targetId, r)
                    m.copy(reactions = r)
                } else m
            } else m
        }
        if (changed) { cur[groupId] = updated; _groupMessages.value = cur }
    }

    /** Decrypt an inbound sealed payload, dispatching on the outer wire
     *  version: v=2 (libsignal forward secrecy) runs through the Double
     *  Ratchet — a prekey message auto-establishes the inbound session with
     *  no server round-trip — while v=1 (and anything else) uses the legacy
     *  ECIES path. Shared by 1:1 and group ingest so a v=2 message decrypts
     *  wherever it lands. Synchronous (no network on either path). */
    private fun decryptInbound(payloadB64: String): SealedSender.Decrypted =
        if (SealedSender.wireVersion(payloadB64) == 2) {
            SignalSession.decrypt(signalStores, payloadB64, identityPriv(), identityPub())
        } else {
            SealedSender.decryptV1(payloadB64, identityPriv(), identityPub())
        }

    /** Surface a failed inbound decrypt instead of swallowing it. A v=2
     *  message that won't decrypt (damaged ratchet session, malformed
     *  payload) would otherwise vanish with no trace, which makes the
     *  iOS<->Android v=2 interop pass impossible to debug. Duplicates (the
     *  same message arriving via both the live socket and the offline queue)
     *  are expected and benign, so they stay quiet. */
    private fun logDecryptFailure(payloadB64: String, e: Throwable) {
        if (e is DuplicateMessageException) return
        android.util.Log.w(
            "RCQsignal",
            "ingest decrypt failed (wire v=${SealedSender.wireVersion(payloadB64)}): ${e.javaClass.simpleName}: ${e.message}",
        )
    }

    // ── own key material (derived from stored privates) ──────────────

    private fun identityPriv(): ByteArray = store.identityPrivate ?: error("no identity key")
    private fun identityPub(): ByteArray = X25519PrivateKeyParameters(identityPriv(), 0).generatePublicKey().encoded
    private fun signingPriv(): ByteArray = store.signingPrivate ?: error("no signing key")
    private fun signingPub(): ByteArray = Ed25519PrivateKeyParameters(signingPriv(), 0).generatePublicKey().encoded
}
