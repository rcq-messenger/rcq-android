package app.rcq.android

import android.content.Context
import android.util.Base64
import app.rcq.android.crypto.Envelope
import app.rcq.android.crypto.IdentityKeys
import app.rcq.android.crypto.MediaCrypto
import app.rcq.android.crypto.MediaStream
import app.rcq.android.crypto.Reply
import app.rcq.android.crypto.SealedSender
import app.rcq.android.crypto.SenderKeyStore
import app.rcq.android.crypto.SenderKeys
import app.rcq.android.crypto.SignalBootstrap
import app.rcq.android.crypto.SignalSession
import app.rcq.android.crypto.SignalStoreDb
import app.rcq.android.crypto.SignalStores
import org.signal.libsignal.protocol.DuplicateMessageException
import app.rcq.android.data.AccountManager
import app.rcq.android.data.DecoyStore
import app.rcq.android.data.LocalStores
import app.rcq.android.data.ProfileKeyVault
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
import app.rcq.android.net.DeviceId
import app.rcq.android.net.JwtPeek
import app.rcq.android.net.CrossIslandStore
import app.rcq.android.net.VisitedIslandsStore
import app.rcq.android.net.GroupLogPage
import app.rcq.android.net.Multihome
import app.rcq.android.net.CrossIslandRequestsStore
import app.rcq.android.net.ContactRelayStore
import app.rcq.android.net.SingBoxTransport
import app.rcq.android.net.RelayConfigStore
import app.rcq.android.net.MultihomeStore
import app.rcq.android.net.RcqApi
import app.rcq.android.net.RcqFederation
import app.rcq.android.net.RcqSocket
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** WS event names that carry a SEALED envelope in `payload`, i.e. everything a
 *  client may declare as the outer `envelope_type` on a send (the server echoes
 *  that string back as the live event name, and files the same row in the
 *  offline queue). The outer label only drives server-side routing and push
 *  gating; what the envelope IS gets decided by the INNER kind after decrypt,
 *  which is why they all funnel into the same ingest.
 *
 *  Kept in sync with the server's accepted types (rcq-server-ref
 *  routers/messages.py) and iOS MessageService.envelopeType(for:). Anything
 *  missing here is dropped on the live socket and only surfaces on the next
 *  offline-queue drain — that was the bug where a reaction, read receipt or
 *  edit from iOS (which has always typed its control envelopes) reached an
 *  online Android peer minutes late, on reconnect. */
/** Control envelopes go out under their REAL type. Flipped for 0.133: v0.76
 *  (the first build that ingests every sealed type on the live socket, see
 *  [SEALED_WS_TYPES]) has been the common case for weeks, and an untyped
 *  receipt is one a peer's silence probe cannot attribute to a device —
 *  it cleared nothing, so a healthy phone read as a dead install
 *  (fan-out live test 2026-08-20). */
private const val TYPED_CONTROL_SENDS = true

/** Frame types that carry a sealed envelope on the live socket. Anything not
 *  listed is dropped in silence, which is why the list has to cover every type
 *  ANY client deposits, not just the ones that draw a bubble.
 *
 *  ⚠ "call" is the §5d cross-island call deposit (`envelope_type: "call"`,
 *  2026-08-15). Our own islands relabel that frame "message" exactly because
 *  this set did not contain it: a call to somebody whose app was OPEN arrived
 *  as an unlisted frame, was dropped here, and rang nothing — and the wake
 *  cannot cover it, since the wake only fires when NOTHING is connected.
 *  Listed here too so a self-hosted island that passes the deposit type
 *  straight through still reaches [ingest]. Routing is by the INNER kind
 *  either way; this only decides whether the frame is opened at all. */
private val SEALED_WS_TYPES = setOf(
    "message", "system", "secscreen", "nudge", "bounce",
    "read", "reaction", "edit", "delete", "visit",
    "carbon", "skdm", "sknack", "homerec", "relay_share",
    "call",
)

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
    // Cloudflare front for the flagship: when set (boot found direct api.rcq.app
    // blocked but the CF front reachable), the API + WS connect to this host
    // instead. It rides Cloudflare's collateral-resistant IPs and proxies to
    // api.rcq.app, so a blocked user reaches the island WITHOUT a relay. serverHost()
    // stays the island identity (api.rcq.app) — this only changes the transport URL.
    private var frontHost: String? = null
    // Which host fronts the flagship comes from the signed config, so moving
    // off the apex is a config push rather than a release.
    private val FRONT_HOST: String get() = app.rcq.android.net.RelayConfigStore.frontHost
    private fun apiHost(): String = frontHost ?: serverHost()
    // Stage 3 of the core-metadata plan: this island takes the peer key
    // lookups without a session token (`anon_keys && deposit_auth` on
    // /server/info). Seeded from the island's LAST answer so the first send
    // after a cold start does not name the pair for the second the request
    // takes; the live answer in start() overwrites it. Held here and READ
    // from here by every api object at request time (see [newApi]): that
    // object is rebuilt on every route change, and a value copied onto it
    // could be written onto an instance that is being replaced at that very
    // moment, leaving the live one on the old path for the rest of the
    // process.
    @Volatile private var anonKeyLookup: Boolean =
        capsCache(serverHost())?.let { it.anon_keys && it.deposit_auth } ?: false
    /** Stage 5: does this island keep one log per room? Seeded from the cached
     *  answer for the same reason as [anonKeyLookup]; the live answer in
     *  [refreshCaps] overwrites it and, when it turns the flag ON for the
     *  first time, runs the log drain that start() could not know to run.
     *  Read by [drainGroupLog] and by the live `gmsg` handler. */
    @Volatile private var groupLogReader: Boolean =
        capsCache(serverHost())?.group_log ?: false
    /** Stage 4: does this island keep a vault? Seeded from the cached answer
     *  like the two above. Read after every roster refresh by
     *  [mirrorContactsToVault]; an island without one is left alone. */
    @Volatile private var vaultEnabled: Boolean =
        capsCache(serverHost())?.vault ?: false

    /**
     * The same question the chat list asks, with THREE answers rather than two:
     * true, false, and **null = not answered yet**.
     *
     * ⚠⚠ The third state is not a nicety. Sections are hidden entirely on an
     * island with no vault, and treating "we have not asked yet" as "no" takes
     * the members of a PIN-gated section and draws them, by name and with their
     * unread badges, in Online / Offline / Cross-island, while the section's
     * own header disappears from the list. That is a cold start, and every
     * stretch where the island is unreachable. A chat can only BE filed if the
     * island had a vault when it was filed, so unknown keeps the cached tree
     * exactly as it is; only an explicit "no vault" un-files anything.
     *
     * A cached `true` counts as an answer (the slot was there last time and the
     * feature is not destructive); a cached `false` does NOT, because an island
     * that has since upgraded would otherwise spill a hidden section for as
     * long as it takes /server/info to answer.
     */
    private val _vaultAvailable = MutableStateFlow<Boolean?>(if (capsCache(serverHost())?.vault == true) true else null)
    val vaultAvailable: StateFlow<Boolean?> = _vaultAvailable.asStateFlow()
    /** The island whose LIVE /server/info answer this process has applied.
     *  Null until one lands: a boot with the radios off keeps the cached (or
     *  default) flags, and the first socket that comes up asks again. */
    @Volatile private var capsLiveHost: String? = null
    private val capsRefreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private var api = newApi()
    private var socket = newSocket()
    private fun newApi(): RcqApi =
        RcqApi("https://${apiHost()}", isPrimary = true, anonKeyLookup = { this@Session.anonKeyLookup }).apply {
            if (store.isRegistered) setToken(store.token)
        }
    private fun newSocket(): RcqSocket = RcqSocket("wss://${apiHost()}")
    // Opened lazily by [bindDb] (in [start]) so the message DB is never opened
    // before the panic-PIN dataKey is available — opening a PIN-encrypted DB
    // with the wrong key would fail. While locked it stays closed.
    private lateinit var db: MessageDb
    // Per-account libsignal stores (v=2 forward secrecy). Rebuilt by rebindTo
    // on account switch, like store/db.
    private var signalStores = SignalStores(SignalStoreDb(appCtx, AccountManager.activeId.value ?: ""))
    private val gson = com.google.gson.Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Which account this session is serving, as a number that changes on every
     *  [rebindTo].
     *
     *  ⚠⚠ Why this exists. [switchToAccount] is a HOT SWAP: the same Session
     *  object keeps running and `store`, `api`, `socket`, `signalStores` and
     *  every per-account singleton are re-pointed under it. [scope] is never
     *  cancelled, and `started = false` is only read on the way IN to [start],
     *  so every suspend function that was already in flight when the switch
     *  happened comes back and writes through fields that now belong to
     *  SOMEBODY ELSE. That is not hypothetical: on iOS the same shape put one
     *  account's cross-island contact requests in front of another account
     *  (fixed 2026-08-31, f50a7b0), and the audit of this file found six of
     *  them here, including an island's own token landing in another account's
     *  SecureStore.
     *
     *  The rule, for every new suspend function that writes anything:
     *  read [accountEpoch] BEFORE the first suspension point and check
     *  [stillOn] after EVERY one of them, including before an ack, which is a
     *  second network call that would otherwise ride the current token and
     *  baseURL to the wrong island.
     *
     *  ⚠ Reading the epoch AFTER the await is the exact bug this prevents:
     *  the guard then compares B with B and always passes. */
    @Volatile private var accountEpoch: Int = 0

    /** The epoch to hold on to, read before the first suspension. */
    private fun epochNow(): Int = accountEpoch

    /** True while this session still serves the account [epoch] was taken for.
     *  False means the work in hand belongs to a previous account and must be
     *  dropped, not written down. */
    private fun stillOn(epoch: Int): Boolean = accountEpoch == epoch

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
        appendHistory = { peer, fromMe, text, missed, startedAt, callId ->
            logCallHistory(peer, fromMe, text, missed, startedAt, callId)
        },
    )

    /** Same-island call signals owed to the peer while the socket is down.
     *
     *  ⚠ `socket.send` on a closed socket drops the frame and says so, and for
     *  a call that is not a lost optimization. A dropped `call_end` leaves the
     *  island believing both parties are still talking: the other end keeps
     *  counting the call it can no longer hear, and every next offer either way
     *  is answered "busy" until the registration goes stale, up to ten minutes
     *  (#699, found by calling from the desktop and hanging up on the phone).
     *  The web client has held these since August; the phone dropped them.
     *  Held here, flushed the moment the socket comes back.
     *
     *  ⚠ This catches a socket OkHttp has ALREADY noticed is dead. A socket
     *  dying silently (the case RcqSocket's own watchdog exists for) still
     *  accepts the frame, buffers it into a corpse and reports success, and
     *  nothing here can tell. Closing that hole means an acknowledgement the
     *  call channel does not have, so it stays open on purpose rather than
     *  being papered over with a blind re-send: replaying a `call_end` at a
     *  later moment risks ending the WRONG call between the same two people.
     *  The peer's own dead-call watchdog (45s) is what covers it today. */
    private val callOutbox = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())

    /** Flush the held call signals. A `call_end` goes out whatever happened
     *  since: it is what clears the island's busy registration and stops the
     *  peer ringing. Everything else only means something to a call still on
     *  foot, so frames from a call that ended during the gap are dropped rather
     *  than replayed at somebody who has moved on. A frame the socket refuses
     *  (it died again between the state flip and this running) goes back in the
     *  box with everything after it. */
    private fun flushCallOutbox() {
        val held = synchronized(callOutbox) {
            val copy = callOutbox.toList(); callOutbox.clear(); copy
        }
        if (held.isEmpty()) return
        // A call still ON FOOT, not the one whose Ended state lingers for a
        // couple of seconds so the screen can show a verdict: replaying an
        // offer and its ICE at somebody whose call is already over is worse
        // than dropping them.
        val liveCall = calls.state.value.takeIf { it.active }?.info?.id
        for ((i, frame) in held.withIndex()) {
            val type = frame.get("type")?.takeIf { !it.isJsonNull }?.asString
            val callId = frame.get("call_id")?.takeIf { !it.isJsonNull }?.asString
            if (type != "call_end" && callId != liveCall) continue
            if (!socket.send(frame.toString())) {
                synchronized(callOutbox) { callOutbox.addAll(0, held.subList(i, held.size)) }
                return
            }
        }
    }

    /** §5d: WS for same-island peers, sealed deposit for cross-island ones. */
    private fun routeCallSignal(obj: JsonObject) {
        val toUin = obj.get("to_uin")?.takeIf { !it.isJsonNull }?.asInt
        val ci = toUin?.let { CrossIslandStore.findByUin(it) }
        // ⚠ `call_missed` is the one call signal that must NOT ride the socket
        // on our own island. Every other one is live: it means nothing to a
        // peer who is not there. This one exists precisely because they were
        // not there ([CallController.depositMissedIfUnreachable]), so it has to
        // wait for them in the offline queue the way a message does. Deposited
        // sealed, same as the cross-island path below does with everything.
        if (ci == null && toUin != null && obj.get("type")?.takeIf { !it.isJsonNull }?.asString == "call_missed") {
            val callId = obj.get("call_id")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val data = mutableMapOf<String, String>()
            for ((k, v) in obj.entrySet()) {
                if (k == "type" || k == "to_uin" || k == "call_id") continue
                if (v.isJsonPrimitive) data[k] = v.asString
            }
            scope.launch { runCatching { sendControl(toUin, Envelope.callSignal("call_missed", callId, data)) } }
            return
        }
        if (ci == null) {
            // ⚠ `call_end` is the one LIVE signal that must survive a dead
            // socket. The shade's hang-up runs in a backgrounded process whose
            // socket is often a silently-dead corpse: `ws.send` buffers into it
            // and reports success, the frame never leaves the phone, and the
            // peer's call screen counts on (#724/#730/#733). Two belts: probe
            // the socket first, so a corpse is torn down and the refused frame
            // lands in the outbox for the redial to flush - and deposit a
            // sealed copy the way `call_missed` above already travels, which
            // reaches the peer through their queue even if this process is
            // frozen before any reconnect. A double delivery is idempotent:
            // [CallController.handleRemoteEnd] checks the call id and drops
            // an end for a call that is already gone.
            if (toUin != null && obj.get("type")?.takeIf { !it.isJsonNull }?.asString == "call_end") {
                socket.ensureAlive()
                val callId = obj.get("call_id")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val data = mutableMapOf<String, String>()
                for ((k, v) in obj.entrySet()) {
                    if (k == "type" || k == "to_uin" || k == "call_id") continue
                    if (v.isJsonPrimitive) data[k] = v.asString
                }
                scope.launch { runCatching { sendControl(toUin, Envelope.callSignal("call_end", callId, data)) } }
            }
            // same-island: unchanged plaintext WS relay, but no longer fire and
            // forget — a refused frame waits for the socket instead of vanishing.
            if (!socket.send(obj.toString())) {
                synchronized(callOutbox) {
                    // Bounded: a call is a handful of signals, and a box that
                    // grew without limit on a long outage would replay a crowd.
                    // ⚠ What gets dropped is never a `call_end` while anything
                    // else is in there: the end is the whole reason the box
                    // exists, and evicting the oldest frame would have thrown
                    // it out first, under a pile of ICE from a later redial.
                    if (callOutbox.size >= CALL_OUTBOX_MAX) {
                        val victim = callOutbox.indexOfFirst {
                            it.get("type")?.takeIf { t -> !t.isJsonNull }?.asString != "call_end"
                        }
                        callOutbox.removeAt(if (victim >= 0) victim else 0)
                    }
                    callOutbox.add(obj)
                }
            }
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

    /** Tail of the deposit chain per call id (see [depositCallSignal]). */
    private val ciCallDepositTails = HashMap<String, kotlinx.coroutines.Job>()

    /** Deposit one cross-island call signal, IN EMIT ORDER per call.
     *
     *  Each signal used to be its own free-running coroutine, and the order they
     *  landed in the peer island's queue was whatever the network made of it.
     *  That was a narrow race while every deposit cost the same one POST, but
     *  the waking offer now first asks the peer island whether it honours
     *  `ring` (CrossIslandSender.peerHonoursRing, up to 5 s on a slow or old
     *  island, longer when the tunnel engages), while the ICE batch that
     *  follows it 350 ms later asks nothing. Unordered, the candidates reached
     *  the callee first, and a callee drops `call_ice` for a call it has not
     *  been offered yet, so the call sat in "connecting". So: each deposit
     *  waits for the previous one of the same call before it goes out. Only
     *  the first signal of a call ever pays the probe; the rest hit the memo. */
    private fun depositCallSignal(ci: CrossIslandStore.Contact, me: Int, env: Envelope) {
        val callId = (env as? Envelope.CallSignal)?.cid ?: ""
        synchronized(ciCallDepositTails) {
            val prev = ciCallDepositTails[callId]
            val job = scope.launch(Dispatchers.IO) {
                prev?.join()
                runCatching {
                    CrossIslandSender.deliverCall(ci, env, me, signingPriv(), signingPub(), serverHost())
                }.onFailure { android.util.Log.e("RCQcall", "cross-island signal failed: ${it.message}") }
            }
            ciCallDepositTails[callId] = job
            job.invokeOnCompletion {
                synchronized(ciCallDepositTails) {
                    if (ciCallDepositTails[callId] === job) ciCallDepositTails.remove(callId)
                }
            }
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
        selfUin = { uin ?: 0 },
    )

    /** People Nearby (geohash check-in). REST-polled; no WS routing. */
    val nearby = app.rcq.android.nearby.NearbyController(appCtx, scope) { api }

    /** Radio — offline BLE + Wi-Fi-Direct local mesh (text/rooms/PTT voice).
     *  Fully peer-to-peer (no server); reuses the anonymous Nearby label. */
    val radio = app.rcq.android.nearby.RadioController(
        appContext = appCtx,
        scope = scope,
        // ⚠ `nickname`/`uin`, NOT `store.nickname`/`store.uin`. In a migrated
        // decoy session `store` is still the REAL account, so with the
        // anonymous toggle off this lambda put the REAL nickname (or the real
        // number) into every radio advertisement, roster frame and voice frame
        // — broadcast over Bluetooth and Wi-Fi Direct to everyone in range,
        // from a phone that is supposed to belong to nobody. The Session-level
        // accessors already prefer the decoy identity.
        displayName = { if (nearby.anonymous.value) nearby.displayName.value else (nickname.takeIf { it != "—" } ?: uin?.toString() ?: "Stranger") },
    )

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    /// The message database would not open under the key we hold, so this
    /// session runs WITHOUT local history while everything else works. Kept as
    /// state rather than swallowed, because an empty thread list and a locked
    /// one look identical and mean opposite things: one is "nothing here", the
    /// other is "your history is on this device and we cannot read it".
    private val _dbLocked = MutableStateFlow(false)
    val dbLocked: StateFlow<Boolean> = _dbLocked.asStateFlow()

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

    /** True once the app is going through the RCQ relays, so the home header
     *  can show the relay shield (iOS StealthHeaderBadge
     *  parity). The tunnel persists for the process once up, so this only
     *  flips on. */
    private val _stealthActive = MutableStateFlow(false)
    val stealthActive: StateFlow<Boolean> = _stealthActive.asStateFlow()

    /** True when the relays are on because the USER switched them on, false
     *  when the app engaged them itself after a direct connection failed.
     *
     *  The two are indistinguishable on screen today, so the explainer told
     *  everybody "the network looked blocked, it turns itself on" even when
     *  they had just turned it on by hand from the menu (vss). The persisted
     *  preference already carries the answer: the auto-engage path only fires
     *  when `isEnabled` is false. */
    private val _bypassManual = MutableStateFlow(false)
    val bypassManual: StateFlow<Boolean> = _bypassManual.asStateFlow()

    /** True while the PRIMARY island is unreachable but a backup mailbox is
     *  still handing us mail. Without this the failover is completely silent:
     *  the user keeps receiving and has no way to tell their island is down
     *  ("I would not even know api stopped working" — tester). Set by the
     *  backup drain, cleared as soon as the primary answers again. */
    private val _receivingViaBackup = MutableStateFlow(false)
    val receivingViaBackup: StateFlow<Boolean> = _receivingViaBackup.asStateFlow()

    /** Whether the engaged tunnel VERIFIABLY reaches the backend through the
     *  current route (the same /health-through-route probe the watchdog +
     *  diagnostics use). The home shield reflects this so it can't claim a working
     *  relay route when the chain (esp. onion) carries no traffic, the "лук: щит
     *  есть, связи нет" report. Meaningless (false) when no tunnel is engaged. */
    private val _routeVerified = MutableStateFlow(false)
    val routeVerified: StateFlow<Boolean> = _routeVerified.asStateFlow()

    private val _typingFrom = MutableStateFlow<Int?>(null)
    val typingFrom: StateFlow<Int?> = _typingFrom.asStateFlow()
    private var typingSeq = 0

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
    /** What this island last said, read ONCE at construction. In `start()` it
     *  was already too late: the first frames are drawn before the session
     *  starts, and they drew the retired surface from the permissive default. */
    private val cachedCaps: RcqApi.ServerCapabilities? = capsCache(serverHost())

    private val _uinShopEnabled = MutableStateFlow(cachedCaps?.uin_shop ?: true)
    val uinShopEnabled: StateFlow<Boolean> = _uinShopEnabled.asStateFlow()

    /** Hall of Fame opt-in surface. Flagship advertises hall_of_fame=true;
     *  self-host rcq-server-ref returns false → the Privacy settings opt-in
     *  hides. Permissive default (true) until the first /server/info lands. */
    private val _hallOfFameEnabled = MutableStateFlow(cachedCaps?.hall_of_fame ?: true)
    val hallOfFameEnabled: StateFlow<Boolean> = _hallOfFameEnabled.asStateFlow()

    /** Operator-toggleable optional features (admin console → Features). Each
     *  defaults true so a legacy server keeps the tab; turned off → the UI hides
     *  the entry and the route is also 404-gated server-side. */
    /** The last answer this island gave about its optional surfaces, per host.
     *  Read at start so a switched-off surface does not flash back for the
     *  second the request takes; the live answer overwrites it either way. */
    private fun capsCache(host: String): RcqApi.ServerCapabilities? {
        val raw = appCtx.getSharedPreferences("rcq_caps", android.content.Context.MODE_PRIVATE)
            .getString(host, null) ?: return null
        return runCatching {
            com.google.gson.Gson().fromJson(raw, RcqApi.ServerCapabilities::class.java)
        }.getOrNull()
    }

    private fun rememberCaps(host: String, caps: RcqApi.ServerCapabilities) {
        runCatching {
            appCtx.getSharedPreferences("rcq_caps", android.content.Context.MODE_PRIVATE)
                .edit().putString(host, com.google.gson.Gson().toJson(caps)).apply()
        }
    }

    private val _nearbyEnabled = MutableStateFlow(cachedCaps?.nearby ?: true)
    val nearbyEnabled: StateFlow<Boolean> = _nearbyEnabled.asStateFlow()

    /** Apply an island's answer to the five surface flags and to the wire
     *  switches: anonymous key lookups and the room log.
     *
     *  @param live this is the island's OWN answer, not the cached one. Only a
     *  live answer may say "no vault" out loud: see [vaultAvailable]. */
    private fun applyCaps(c: RcqApi.ServerCapabilities, live: Boolean = false) {
        _uinShopEnabled.value = c.uin_shop
        _hallOfFameEnabled.value = c.hall_of_fame
        _nearbyEnabled.value = c.nearby
        _randomEnabled.value = c.random_chat
        _reportsEnabled.value = c.reports
        // Both or neither: an island that understands the open lookups but
        // issues no tokens would hand out bundles without a one-time prekey
        // to an anonymous caller, so it keeps getting the session token.
        anonKeyLookup = c.anon_keys && c.deposit_auth
        groupLogReader = c.group_log
        vaultEnabled = c.vault
        if (live || c.vault) _vaultAvailable.value = c.vault
        // Zero means the island did not say, and the shipped default stands.
        // An island that DID say wins even when it says something smaller than
        // ours: refusing a video it would refuse anyway is the honest answer.
        if (c.media_max_blob_bytes > 0) mediaMaxBlobBytes = c.media_max_blob_bytes
    }

    /** After an account switch the flags still say what the PREVIOUS island
     *  said. Re-seed from the new island's cached answer, or fall back to
     *  the permissive default for an island never asked; the live answer in
     *  start() overwrites either way. */
    private fun reseedCaps() {
        // ⚠ Not ServerCapabilities(): its false defaults mean "the JSON
        // omitted the field", while the never-asked default here is the
        // permissive one the flags are born with.
        val cached = capsCache(serverHost())
        // ⚠ Back to "not answered yet" FIRST: the flag still says what the
        // previous island said, and carrying a `true` across a switch would let
        // the new account's chat list offer a menu that writes to a slot the
        // new island has no room for. applyCaps below raises it again when the
        // cached answer for THIS island says yes.
        _vaultAvailable.value = null
        applyCaps(cached ?: RcqApi.ServerCapabilities(
            uin_shop = true, hall_of_fame = true, nearby = true, random_chat = true, reports = true,
        ))
        cached?.let { app.rcq.android.data.AccountManager.serverMaxAccounts = it.max_accounts_per_device }
    }
    private val _randomEnabled = MutableStateFlow(cachedCaps?.random_chat ?: true)
    val randomEnabled: StateFlow<Boolean> = _randomEnabled.asStateFlow()

    /** Does this island run a report desk at all? A self-hoster who does not
     *  want to answer anybody switches it off, and then the two entries that
     *  lead there have no business being in Settings. Permissive default so an
     *  island that predates the flag behaves as it always did. */
    private val _reportsEnabled = MutableStateFlow(cachedCaps?.reports ?: true)
    val reportsEnabled: StateFlow<Boolean> = _reportsEnabled.asStateFlow()

    /** Load the server push-preference toggles (Notifications settings). */
    suspend fun loadPushPrefs(): RcqApi.PushPrefs? = runCatching { api.getPushPreferences() }.getOrNull()

    /** Last push-delivery verdict per registered device, or null if the island
     *  is too old to answer (pre-push-health servers 404 here). Drives the
     *  "notifications are not arriving, and here is why" line in Settings —
     *  a failing UnifiedPush distributor is otherwise completely silent. */
    suspend fun loadPushHealth(): RcqApi.PushHealth? = runCatching { api.pushHealth() }.getOrNull()

    /** Flip the "push for new contact requests" preference (optimistic; caller
     *  reverts the UI on failure). */
    suspend fun setContactRequestsPush(on: Boolean): Boolean =
        runCatching { api.setPushPreferences(RcqApi.PushPrefsBody(contact_requests = on)) }.isSuccess

    /** Push the local per-account mute set up to the server so the push fan-out
     *  (is_group_muted / should_push_for) SKIPS the APNs/UnifiedPush wake for a
     *  muted thread. Without this a muted group still woke the device — the mute
     *  was client-only and the server (which gates on muted_group_ids) never
     *  knew. Observed off [LocalStores.muted] in [start], so it both reconciles
     *  existing mutes once on launch and re-syncs on every mute/unmute. */
    private suspend fun syncPushMutes() {
        // In a migrated decoy session LocalStores is bound to the DECOY
        // namespace while `api` still carries the real account's token, so this
        // would push the decoy's (empty) mute set over the real account's
        // server-side one — silently un-muting everything the user muted. The
        // collector that drives this is started by start() and outlives the lock.
        if (duressViewUp) return
        if (!LocalStores.isAccountBound()) return
        runCatching {
            api.setPushPreferences(
                RcqApi.PushPrefsBody(
                    muted_group_ids = LocalStores.mutedGroupIds(),
                    muted_uins = LocalStores.mutedPeerUins(),
                ),
            )
        }
    }

    // ── decoy (duress) session identity ──────────────────────────────
    // Set only while unlocked into a MIGRATED decoy, which is not a roster
    // account at all: it has its own SQLCipher store, its own key and this
    // synthetic identity, and it never touches the network. While these are
    // set, `store` still points at the real active account — every accessor
    // the UI reads for "who am I" must therefore prefer these, or the duress
    // view would show the real number under the decoy's history.
    @Volatile
    private var decoySessionUin: Int? = null
    @Volatile
    private var decoySessionNickname: String? = null

    /** True while this session is showing the decoy's own store. */
    val inDecoySession: Boolean get() = decoySessionUin != null

    /**
     * ⚠⚠ The guard every path that touches the REAL account must ask.
     *
     * In a MIGRATED decoy session `store` still points at the real active
     * account while `db` is the DECOY file — that mismatch is the whole shape
     * of the feature, and it is also a loaded gun. Anything that fetches with
     * the real token and writes through `db` will pull real messages off the
     * island, ACK them there, and file them in the duress store: gone from the
     * real history for good, and sitting in front of whoever was handed the
     * duress PIN.
     *
     * It is not enough to disconnect once on the way in. The periodic
     * coroutines `start()` launches are never cancelled ([tearDownForLock]
     * clears `started`, not the scope), so the route watchdog wakes 60 seconds
     * later, sees a socket that is down, and redials with the real uin+token.
     * That is the path that loses messages, and it is reached without the user
     * touching anything.
     *
     * True from the moment the decoy slot opens (before [startDecoySession]
     * has run) until the vault state is cleared, so no window is left open
     * between [PanicPinService.submit] and the session flipping over.
     */
    private val duressViewUp: Boolean
        get() = decoySessionUin != null ||
            (PanicPinService.inDecoySession && !PanicPinService.decoyIsLegacy)

    val nickname: String get() = decoySessionNickname ?: store.nickname ?: "—"

    // uin -> recipient X25519 identity public (raw), from contacts or lookup.
    private val peerIdentityCache = HashMap<Int, ByteArray>()

    // Peers known to have no v=2 bundle this session — send them v=1 without
    // re-probing /keys/{uin}/bundle on every message. Cleared on account
    // switch (a peer may publish a bundle later; we re-probe next session).
    private val noV2Peers = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    // uin -> (good until, that account's libsignal device ids). A v=2 send
    // asks for the list on every message and it only changes when somebody
    // links or drops an install, so it is held for a while rather than per
    // send. The expiry is stamped per entry with its own jitter (see
    // [peerDevices]), so a row of live conversations does not refetch in
    // lockstep. Dropped early by the three signals that say the list is
    // stale: a send or a bundle fetch for one of its devices answering 404,
    // and an inbound v=2 message naming a device the list does not have.
    private val peerDeviceCache =
        java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, List<Int>>>()

    // ── Silence probe: notice a peer whose install was replaced under us ──
    // A replaced install (re-claimed slot, reinstall, phrase restore onto a
    // new machine) is INVISIBLE from the sending side: the island takes every
    // copy sealed to the session the peer no longer holds, the receipt simply
    // never comes, and an established session means the bundle is not read
    // again for hours. Sustained silence IS the signal: if this side keeps
    // sending and that DEVICE has answered nothing — no receipt, no message,
    // nothing naming it — the next send rebuilds its session outright (fresh
    // bundle + X3DH). Tracked PER DEVICE, exactly like the web (a peer's
    // phone answering promptly says nothing about their dead browser); the
    // web's live test 2026-08-20 is the reference implementation. Keys are
    // "uin:deviceId"; in-memory on purpose — restart amnesia just means the
    // first send after a relaunch arms the timers afresh.
    private val awaitingReplySince = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastSilenceProbeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // "uin:deviceId" -> the libsignal identity that device published the last
    // time we read the (free) device list. The silence probe compares against
    // THIS instead of re-reading a bundle: a bundle read consumes one of the
    // peer's one-time prekeys, and a probe that spends one every half hour to
    // hear "nothing changed" drains a pool that only refills while its owner
    // is online — leaving every later X3DH with that account without its
    // one-time secret. The probe would erode what it exists to protect.
    private val peerDeviceIdentity = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val peerSilenceMs = 2 * 60_000L
    private val silenceProbeMinIntervalMs = 30 * 60_000L

    // True once a LIVE contact refresh has established real presence for this
    // profile. Until then any apparent online/offline transition is an artefact
    // of the disk-cached roster (which forces everyone offline), not something
    // that happened, and must stay silent. See refreshContacts() and #422.
    private var presenceBaselineLive = false

    // v=2 (libsignal Double Ratchet) OUTBOUND, i.e. forward secrecy on the 1:1
    // messages this phone SENDS. Off since May behind one unanswered question:
    // Android's libsignal and iOS's are different versions, and nobody had put
    // a v=2 message from one in front of the other. Answered on 2026-08-21 —
    // a message each way between a real Android build and a real iOS build,
    // against production, decrypted and rendered on both sides — so it is on.
    //
    // What it changes: a 1:1 message is sealed per recipient DEVICE over the
    // Double Ratchet instead of once to the account's long-term key, which is
    // what makes yesterday's messages unreadable if today's keys are taken.
    // Groups (sender keys) and INBOUND were already v=2 and are untouched. A
    // peer with no libsignal bundle still gets the v=1 envelope, so nobody
    // falls off the wire.
    //
    // It also wakes the silence probe (see awaitingReplySince): only a v=2
    // send can arm it, so until now it was dormant code.
    private val v2OutboundEnabled = true
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

    // Own "who may open my profile card" policy, memoised over the on-disk
    // privacy cache so a picker or a screen can ask without a round trip and
    // without a Gson parse. null = not read yet this session; cleared on every
    // account switch, because the cache it memoises is per account and a stale
    // answer here would show one identity's privacy under another's name.
    @Volatile
    private var profileVisibilityMemo: String? = null

    /** The three answers the island accepts for any of the visibility policies. */
    private val visibilityValues = setOf("everyone", "contacts", "nobody")
    // 1:1 inbound message ids we've already acked with a read receipt
    // (in-memory; re-acking after restart is harmless/idempotent).
    private val ackedReads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var started = false

    // True once the WebSocket has connected at least once; gates the
    // reconnect-driven graph resync so the initial launch doesn't double up.
    @Volatile
    /** elapsedRealtime when the socket last went down, 0 while connected. */
    private var offlineSince = 0L
    /** elapsedRealtime of the last route-ladder run, so a network that is down
     *  for everyone cannot turn into a probe storm. */
    private var lastLadderAt = 0L
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
        // Sender-keys chain state (group encrypt-once).
        SenderKeyStore.init(appCtx)
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
                    AccountManager.exitDecoySession()
                    if (started) tearDownForLock()
                } else if (!PanicPinService.inDecoySession) {
                    // Put the push socket back. Entering a duress session stops
                    // it and the gate refuses to let anything restart it, and
                    // the only other caller is `RcqApp.onCreate` — so without
                    // this, one duress session inside a process left the real
                    // user with push quietly dead until they restarted the app,
                    // which is the kind of silence nobody reports as a bug and
                    // everybody feels. Idempotent, and a no-op on ntfy or with
                    // push switched off.
                    runCatching { app.rcq.android.push.Push.resumeEmbedded(appCtx) }
                }
            }
        }
    }

    val isRegistered: Boolean get() = store.isRegistered
    val uin: Int? get() = decoySessionUin ?: store.uin
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
    /** Engage the RCQ relays (if needed) BEFORE the first network
     *  call of registration/recovery — mirrors [start]'s engage logic. No-op
     *  when a direct /health probe to [host] succeeds (healthy network, no
     *  block) or the transport is already up. Blocking work (probe + sing-box
     *  start) runs off the main thread. Best-effort: a failure to start the
     *  transport just leaves the subsequent request to go direct (and fail as
     *  before), so this never makes registration worse.
     *
     *  Bound by the user's "don't turn relays on automatically" opt-out exactly like
     *  the boot ladder is (#588): unreachable + opted out means the request is
     *  left to fail, with the reason said out loud rather than swallowed. */
    private suspend fun ensureTransportForHost(host: String) = withContext(Dispatchers.IO) {
        val transport = app.rcq.android.net.SingBoxTransport
        if (!transport.isActive) {
            // ⚠ Report #588 ("включает обход, хотя стоит настройка не включать
            // автоматом") was this line: a failed probe engaged the tunnel here
            // without ever reading "don't turn relays on automatically", so signing
            // up or restoring on a network that merely answered slowly turned the
            // relays on for a user who had switched that off, and they then
            // stayed on for the session. The forced toggle still engages (that
            // is the user asking); only the probe-driven half is gated, the
            // same way [runRouteLadder] gates it.
            val forced = transport.isEnabled(appCtx)
            val blocked = !forced && !transport.probeDirect(host)
            if (forced || (blocked && transport.mayAutoEngage(appCtx))) {
                app.rcq.android.net.RelayConfigStore.prime(appCtx)
                transport.start()
            } else if (blocked) {
                // The register/recover call about to run will fail against a
                // host we already know we cannot reach. Name the reason, or the
                // screen shows its generic error and the opt-out looks like a
                // broken app.
                transport.noteAutoEngageDeclined(host)
            }
        }
        _stealthActive.value = transport.isActive
        _bypassManual.value = transport.isEnabled(appCtx)
    }

    suspend fun registerNewAccount(nickname: String, serverInput: String? = null, invite: String? = null): Int {
        if (AccountManager.isAtLimit) throw IllegalStateException("Account limit reached")
        val host = normalizeHost(serverInput)
        // Engage the RCQ relays BEFORE the first network call (registration).
        // Without this a blocked user's very first request goes out direct,
        // times out ("Couldn't connect"), and they have to switch on a VPN
        // just to sign up. The RcqApi built next captures the SOCKS proxy.
        ensureTransportForHost(host ?: RcqApi.DEFAULT_HOST)
        val regApi = RcqApi("https://${host ?: RcqApi.DEFAULT_HOST}")
        // Derive the identity from a fresh 32-byte recovery seed so the account
        // is restorable from a BIP39 phrase (the seed is persisted below).
        val seed = IdentityKeys.newSeed()
        val identity = IdentityKeys.fromSeed(seed)
        // Did this install arrive by someone's invite link? The pending add is
        // set by the VIEW intent before any of this runs and survives
        // onboarding, but until now only a REGISTERED session ever consumed it:
        // a person who tapped a friend's link, installed, and signed up landed
        // with an empty contact list and the invite silently dropped. The
        // server recorded exactly zero referrals in the project's life, and
        // this is why: not a missing mechanism, an unreached one.
        //
        // Naming the inviter here makes the server connect the pair on both
        // sides as part of registration, so the account exists with someone in
        // it rather than nobody. The referrals router and its genealogy table
        // are gone (they kept a permanent record of who recruited whom, for no
        // reader); the pair-connect that mattered lives on as
        // `routers/auth._connect_inviter` and this call still reaches it.
        //
        // Flagship + same-island only: a referral to an account on another
        // island is not something this island can verify or connect.
        val inviterUin = ContactAddLink.pending.value
            ?.takeIf { it.host == null && (host ?: RcqApi.DEFAULT_HOST) == RcqApi.DEFAULT_HOST }
            ?.uin
        // Prove the signing key is ours before claiming it. Best-effort: an
        // island that predates the challenge endpoint 404s, and registration
        // there works exactly as it did.
        val signingPubB64 = Base64.encodeToString(identity.signingPublic, Base64.NO_WRAP)
        val regChallenge = runCatching { regApi.registerChallenge(signingPubB64).challenge }.getOrNull()
        val resp = regApi.register(
            RcqApi.RegisterRequest(
                nickname = nickname,
                identity_key = Base64.encodeToString(identity.identityPublic, Base64.NO_WRAP),
                signing_key = signingPubB64,
                inviter_uin = inviterUin,
                invite = invite?.takeIf { it.isNotBlank() },
                device_id = DeviceId.get(appCtx),
                challenge = regChallenge,
                signature = regChallenge?.let {
                    app.rcq.android.crypto.RecoveryPhrase.signChallenge(identity.signingPrivate, it)
                },
            )
        )
        // Consumed: the server already made the two of them contacts, so the
        // confirm dialog that normally follows a tapped link would be asking
        // for something that has happened.
        if (inviterUin != null) {
            android.util.Log.i("RCQinvite", "registered from an invite by #$inviterUin")
            ContactAddLink.pending.value = null
        }
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
    fun recoveryPhrase(): List<String>? {
        // ⚠ THE WHOLE ACCOUNT, FOREVER, AND `store` IS STILL THE REAL ONE IN A
        // MIGRATED DECOY SESSION — that mismatch is the shape of the feature.
        // Only the UI stood between a coercer and the real seed, and that UI is
        // a PIN gate verifying the REAL pin, so under duress it rejected the
        // coercer's pin as "wrong" — which announces that a second pin exists.
        //
        // The decoy identity genuinely has no seed, so null is both true and
        // plausible: the recovery screen already renders "not available" for
        // legacy accounts, and the backup screen already refuses without a
        // phrase. Both take those paths silently.
        if (duressViewUp) return null
        return store.recoverySeed?.let { app.rcq.android.crypto.RecoveryPhrase.encode(it, appCtx) }
    }

    /** A LEGACY account (no seed) backup: its raw identity keys exported as a
     *  48-word phrase (idPriv||signPriv = 64 bytes). null for a seed-derived
     *  account (use [recoveryPhrase]) or if keys are missing/malformed. Restore
     *  via [recoverAccount], which accepts either a 24- or 48-word phrase. */
    fun legacyExportPhrase(): List<String>? {
        // Same reasoning as [recoveryPhrase] — this is the raw identity
        // keypair, which is if anything worse.
        if (duressViewUp) return null
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
    /** Thrown when the phrase resolves to an identity this device ALREADY has.
     *  Restoring anyway is legal (that is how you rebuild a broken local copy)
     *  but it produces a second, EMPTY slot for the same number, and a user who
     *  expected their history back reads that as data loss (report: "I thought
     *  it deleted my history, turns out it made a copy"). Ask first, let them
     *  force it. */
    class AccountAlreadyHere(val uin: Int) : IllegalStateException("account_already_here")

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
        // a manual VPN, so bring the RCQ relays up before the challenge call.
        ensureTransportForHost(host ?: RcqApi.DEFAULT_HOST)
        val regApi = RcqApi("https://${host ?: RcqApi.DEFAULT_HOST}")
        val signingPubB64 = Base64.encodeToString(identity.signingPublic, Base64.NO_WRAP)
        val challenge = regApi.recoverChallenge(signingPubB64).challenge
        val signature = app.rcq.android.crypto.RecoveryPhrase.signChallenge(identity.signingPrivate, challenge)
        val resp = regApi.recover(
            // Name this install straight away: a token without it makes the
            // island treat the session as the anonymous "primary" device, and
            // it can no longer suppress a push for a message this very device
            // just took over its own socket.
            RcqApi.RecoverRequest(signingPubB64, challenge, signature, DeviceId.get(appCtx)),
        )
        // Fetch the real nickname back (the server kept the profile).
        regApi.setToken(resp.token)
        val nick = runCatching { regApi.userInfo(resp.uin).nickname }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "user-${resp.uin}"
        // Same number, same island, already on this device? This is a hard
        // stop, not a warning. There used to be a `force` parameter that skipped
        // it; it is gone rather than merely unused, because a second local copy
        // of one number permanently loses that number's group messages (one
        // bundle per uin on the server, last writer wins) and leaving the
        // parameter in place meant one argument from any future caller could
        // reintroduce that silently. See RestoreScreen and report #421.
        run {
            val dup = AccountManager.accounts.value.any { a ->
                SecureStore(appCtx, a.id).let { st ->
                    st.uin == resp.uin && (st.serverHost ?: RcqApi.DEFAULT_HOST) == (host ?: RcqApi.DEFAULT_HOST)
                }
            }
            if (dup) throw AccountAlreadyHere(resp.uin)
        }
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
        // ⚠ /auth/reissue mints a token WITHOUT the `dev` claim, so from the
        // island's point of view this session stops being a named install and
        // becomes the generic "primary" — after which it can no longer match
        // the socket to the push endpoint, and every 1:1 message wakes the very
        // device that just received it over that socket (one tone from the app,
        // one from the notification). Claim the install back immediately; the
        // session is NOT restarted here, so nothing else would.
        claimInstallToken(resp.token)
        // Rotate the libsignal identity too (upload-first; throws on failure so
        // the UI can ask the user to retry). This is what changes the safety
        // number that warns contacts.
        SignalBootstrap.rebootstrap(signalStores, api, resp.uin, identityPub())
        // Old sessions/cache referenced the previous identity — drop them.
        peerIdentityCache.clear()
        peerDeviceCache.clear()
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

    /**
     * Point EVERY per-account plaintext store at [accountId] (null = none, all
     * reads empty).
     *
     * ⚠⚠ One list, one call site each, on purpose. These four are the stores
     * that live outside SQLCipher and are keyed by account id, and every one of
     * them holds real people: the roster cache + aliases + blocked list
     * (LocalStores), cross-island contacts (CrossIslandStore), guest
     * registrations and foreign-group aliases on other islands
     * (VisitedIslandsStore) and the profile-view tally (VisitStore).
     *
     * The decoy session binds them to [DecoyStore.STORE_ID], which has no rows —
     * that is what makes the duress view show only what was seeded. Binding
     * them one by one at each call site is how the decoy path came to move
     * LocalStores and leave the other three pointing at the real account, which
     * put real cross-island contacts in front of a coercer. Add a new
     * per-account store HERE or it will be missed the same way.
     */
    private fun bindPerAccountStores(accountId: String?) {
        // ⚠⚠ The epoch belongs HERE, not only in [rebindTo]. Every caller of
        // this function moves the ground under work that is already in flight,
        // and the duress path is a caller: entering the decoy re-points all
        // four stores and the message database at DecoyStore.STORE_ID without
        // being an account switch. With the bump only in rebindTo, every guard
        // written for the switch read "still the same account" straight through
        // the panic PIN, so a drain in progress kept acking - the island
        // DELETED the real account's rows and walked its room cursors past
        // history nobody had read, at the exact moment the phone is in somebody
        // else's hands. One line here is what makes the two dozen existing
        // guards cover duress as well, in both directions, for free.
        //
        // Bumping twice on the rebindTo path is harmless: this is a change
        // token, not a counter anybody reads the value of.
        accountEpoch++
        LocalStores.bindAccount(accountId)
        app.rcq.android.data.VisitStore.bindAccount(accountId)
        CrossIslandStore.bindAccount(accountId)
        VisitedIslandsStore.bindAccount(accountId)
    }

    /** Drop everything the decoy namespace could have written outside its own
     *  SQLCipher file. Companion to [DecoyStore.destroy]: the store file is not
     *  the only thing a duress session leaves on disk. */
    private fun wipeDecoyNamespaceStores() {
        LocalStores.clearAccount(DecoyStore.STORE_ID)
        app.rcq.android.data.VisitStore.wipeAccount(DecoyStore.STORE_ID)
        CrossIslandStore.wipeAccount(DecoyStore.STORE_ID)
        VisitedIslandsStore.wipeAccount(DecoyStore.STORE_ID)
        app.rcq.android.data.AccountCards.forget(appCtx, DecoyStore.STORE_ID)
    }

    /** Tear the in-memory session state down and re-point every per-account
     *  store at [accountId]; [start] then loads its history + connects. */
    private fun rebindTo(accountId: String) {
        // FIRST, before a single field moves: everything already in flight is
        // now working for the previous account. See [accountEpoch].
        accountEpoch++
        calls.teardown()   // drop any in-flight call before the identity swaps
        audioRooms.teardown()
        nearby.teardown()
        radio.teardown()
        store = SecureStore(appCtx, accountId)
        // db is (re)opened by bindDb() in start(), with the current dataKey.
        if (::db.isInitialized) db.close()
        signalStores = SignalStores(SignalStoreDb(appCtx, accountId))
        bindPerAccountStores(accountId)
        api = newApi()
        socket = newSocket()
        peerIdentityCache.clear()
        askedProfileKeyAt.clear(); answeredProfileKeyAt.clear()
        noV2Peers.clear(); peerDeviceCache.clear(); awaitingReplySince.clear(); lastSilenceProbeAt.clear(); presenceBaselineLive = false
        ackedReads.clear()
        // ⚠ Held call signals belong to the account that made them: an island,
        // a socket and a peer number that mean somebody else entirely on the
        // next one. Flushing them after a switch would hand a stranger's
        // hang-up to whoever holds that number here.
        synchronized(callOutbox) { callOutbox.clear() }
        lastVisitAt.clear()
        // Stage 5 bookkeeping is per account: another account's rooms are
        // other logs, and a seq carried over would ack the wrong cursor.
        groupLogSeq.clear()
        pendingLogAcks.clear()
        _contacts.value = emptyList()
        _pending.value = emptyList()
        _outgoing.value = emptyList()
        _messages.value = emptyMap()
        _groups.value = emptyList()
        _groupMessages.value = emptyMap()
        // Back to "never loaded", not to "no devices": another account's
        // registry is a different list, and leaving this one visible would
        // show one account's linked sessions under another.
        _devices.value = null
        activeRandomPeer = null
        activeRandomPairId = null
        _randomMessages.value = emptyList()
        _random.value = RandomState.Idle
        _typingFrom.value = null
        // ⚠⚠ MY OWN PICTURE IS PER ACCOUNT TOO, and this reset was missing.
        // The home screen records the ACTIVE account's face into that account's
        // switcher card, preferring this live flow over the new account's
        // profile on disk. Left set across a rebind it wrote the PREVIOUS
        // account's avatar id + key under the NEW account's id: account A's
        // real face, persisted, on account B's row, healed only if and when B's
        // island answers with a profile. Switch while offline and it survives
        // the cold start. The legacy duress unlock switches accounts the same
        // way, so this one line is also what put the real user's face on the
        // decoy row of the switcher.
        _ownAvatar.value = null
        _status.value = UserStatus.ONLINE
        readReceiptsVisibility = "everyone"
        profileVisibilityMemo = null
        activeThread = null
        started = false
        everConnected = false
        // Both vault slots are per account: the retirement latches and the
        // sections debounce belong to whoever we were, not to whoever we are
        // about to be.
        contactsVaultRetired = false
        lastVaultSweep = 0L
        app.rcq.android.data.SectionsVault.resetState()
        reseedCaps()
    }

    /** Load local history, open the WebSocket, drain the offline queue,
     *  and refresh the contact graph. Idempotent enough to call on every
     *  app launch when already registered. */
    fun start() {
        Session.live = this
        if (started) return
        val uin = store.uin ?: return
        val token = store.token ?: return
        started = true
        CrashReporter.crumb(appCtx, "session_start")
        claimInstallToken(token)
        watchUploadsOffScreen()
        // Seed the chat list from the cached roster FIRST (cheap, DB-free) so it
        // paints immediately. The heavy SQLCipher open + full history read move
        // into the connect coroutine below (off the main thread) instead of
        // blocking the first frame behind ~1s of DB work (the "Connecting…"
        // delay on launch). db is opened there BEFORE connectAndSync, which is
        // the only ingest path that writes to it.
        loadCachedRoster()
        // Drop any stored "backup home" that is really the front or the
        // primary island itself. The front is the flagship by another road, so
        // such a row promises redundancy it cannot deliver, and the 30-second
        // drain loop below would pull the account's REAL queue through it on a
        // recover-minted token (founder's #911 carried exactly this phantom).
        // Every add/pick/publish path refuses fronts now, so this only cleans
        // up what older builds let in. Runs before the drain loop starts, so
        // the phantom's drain ends THIS session and not the next one, and
        // before refreshBackupHomes() so Settings never shows the row. No
        // extra republish needed: start() reaches publishHomeIslandRecord()
        // via connectAndSync -> syncGraph, which assembles the record from
        // this store, so senders stop being told about the phantom too.
        MultihomeStore.list(uin)
            .filter { it.host.equals(serverHost(), true) || RelayConfigStore.isFrontHost(it.host) }
            .forEach {
                android.util.Log.w("RCQfed", "scrubbing phantom backup home ${it.host}")
                MultihomeStore.remove(uin, it.host)
            }
        refreshBackupHomes()
        refreshCiRequests()
        // ⚠ Coming back to the foreground now CHECKS THE PIPE AND DRAINS. The
        // hook existed in RcqApp since the PIN-grace work and had zero
        // subscribers, so returning to the app verified nothing: a socket
        // that died while an OEM freezer (MIUI above all) held the process —
        // ping Timer frozen WITH the watchdog inside it — stayed believed-
        // alive, the server pushed (sound!) and queued, and nothing on this
        // side ever fetched until a full restart. Report #807, right after
        // the server-side ghost heal shipped: the heal can only save clients
        // that still send frames, and a frozen client sends none. iOS has
        // always re-drained on foreground; this is that, ported. The drain is
        // throttled so app-switch flurries cost one fetch, and the socket is
        // only redialed when it has been silent past the app-ping cadence.
        var lastForegroundDrain = 0L
        // ⚠ The SESSION slot. `onForegroundChange` belongs to CallController
        // (the ringing handoff); 0.151 assigned it here and broke that handoff.
        RcqApp.onForegroundChangeSession = { up ->
            if (up && started && !duressViewUp) {
                socket.ensureAlive()
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastForegroundDrain > 30_000) {
                    lastForegroundDrain = now
                    scope.launch {
                        runCatching { drainQueue() }
                        runCatching { drainGroupLog() }
                    }
                }
            }
        }
        // Keep the server's push-suppression list in lock-step with the local
        // mute set: the StateFlow replays its current value on subscribe (one
        // reconcile after login — fixes mutes the server never learned about)
        // and re-fires on every mute/unmute. Single collector per Session.
        scope.launch { LocalStores.muted.collect { syncPushMutes() } }
        // Multihoming v1: poll the backup-island mailboxes. Deliberately
        // independent of the primary socket — when the primary island is down,
        // this loop IS the delivery path.
        scope.launch {
            // ⚠ This loop is per ACCOUNT, not per app run. [start] runs again
            // after every switch and would otherwise leave the previous
            // account's loop ticking beside the new one forever, one more on
            // every switch, each draining somebody else's backup mailboxes.
            val ep = epochNow()
            while (stillOn(ep)) {
                delay(30_000)
                if (!stillOn(ep)) return@launch
                runCatching { drainBackupQueuesOnce(ep) }
                runCatching { drainVisitedQueuesOnce(ep) }
            }
        }
        // Disappearing-message reaper: expire messages whose TTL lapsed while a
        // chat is open. 10s cadence keeps a 1-minute timer visibly honest
        // without busy-waiting; the on-load sweep covers longer closed gaps.
        scope.launch {
            while (true) {
                delay(10_000)
                runCatching { sweepExpiredMessages() }
            }
        }
        // O4b onion ENTRY-guard rotation: when onion is active but the backend
        // is unreachable through the current route for two consecutive checks,
        // the sticky ENTRY relay is likely blocked (the whole 2-hop path dies
        // with its single entry) — rotate to the next entry and rebuild the
        // transport so a blocked guard self-heals. DORMANT unless onion is on
        // (off by default), so it's a no-op for everyone until the O5 cohort
        // flip. Won't fire on plain single-hop (onionEnabled gate).
        scope.launch {
            val transport = app.rcq.android.net.SingBoxTransport
            var deadStreak = 0
            while (true) {
                delay(60_000)
                // ⚠⚠ This coroutine is never cancelled — tearDownForLock()
                // clears `started`, not the scope — so it keeps ticking through
                // a lock and into a duress session, where every branch below
                // would redial or re-route the REAL account. Sit it out.
                if (duressViewUp) { deadStreak = 0; continue }
                // Auto-engaged tunnel on a network that has since recovered: drop
                // it. Until now it stayed up for the whole session ("the shield
                // never went away until I killed the service"), which is both
                // slower and confusing, since the user never asked for it. Only
                // the AUTO case is dropped: an explicit toggle, onion and a local
                // proxy all stay exactly where the user put them.
                if (transport.isActive && !transport.isEnabled(appCtx) &&
                    !transport.onionMode() && !transport.localProxyMode()
                ) {
                    val directBack = withContext(Dispatchers.IO) { transport.probeDirect(serverHost()) }
                    if (directBack) {
                        withContext(Dispatchers.IO) { transport.stop() }
                        socket.disconnect()
                        api = newApi()
                        socket = newSocket()
                        _stealthActive.value = false
                        _routeVerified.value = false
                        app.rcq.android.push.embedded.EmbeddedDistributor.reconnectNow(appCtx)
                        store.uin?.let { u -> store.token?.let { t -> connectAndSync(u, t) } }
                        continue
                    }
                }
                // Blocked mid-session: the socket has been down for a while and
                // its own backoff is getting nowhere, so walk the ladder again
                // (direct -> CF front -> relay). Without this the route decided
                // at launch was final, and a network that started filtering
                // while the app was open simply never recovered.
                val down = offlineSince
                val now = android.os.SystemClock.elapsedRealtime()
                if (!_connected.value && down != 0L && now - down >= OFFLINE_RELADDER_MS &&
                    now - lastLadderAt >= LADDER_COOLDOWN_MS
                ) {
                    lastLadderAt = now
                    android.util.Log.i("RCQroute", "offline ${(now - down) / 1000}s — walking the route ladder again")
                    val changed = runCatching { runRouteLadder() }.getOrDefault(false)
                    android.util.Log.i("RCQroute", "ladder done, route changed=$changed front=$frontHost tunnel=${transport.isActive}")
                    if (changed) {
                        socket.disconnect()
                        app.rcq.android.push.embedded.EmbeddedDistributor.reconnectNow(appCtx)
                        store.uin?.let { u -> store.token?.let { t -> connectAndSync(u, t) } }
                    } else {
                        // Same route, but give the socket a nudge rather than
                        // waiting out the rest of its backoff.
                        socket.reconnectNow()
                    }
                    continue
                }
                if (!transport.isActive || !transport.onionMode()) { deadStreak = 0; continue }
                val ok = withContext(Dispatchers.IO) { transport.probeCurrentRoute(serverHost()) }
                _routeVerified.value = ok   // keep the home shield honest for onion (never dropped)
                if (ok) { deadStreak = 0; continue }
                deadStreak++
                if (deadStreak >= 2 && transport.rotateEntry()) {
                    deadStreak = 0
                    withContext(Dispatchers.IO) {
                        transport.stop()
                        app.rcq.android.net.RelayConfigStore.prime(appCtx)
                        transport.start()
                    }
                    api = newApi()
                    socket = newSocket()
                    socket.reconnectNow()
                }
            }
        }
        scope.launch {
            // Open the encrypted DB + load history OFF the main thread (this was
            // the ~1s synchronous block that delayed the first frame). Must run
            // before connectAndSync, the only ingest path that writes to db.
            withContext(Dispatchers.IO) {
                // ⚠ A locked database must not take the socket down with it.
                // Everything below this block — the transport ladder and
                // connectAndSync — is what makes the app usable at all, and it
                // has nothing to do with local history. Losing both at once is
                // what turned a key problem into "the app will not connect".
                runCatching {
                    bindDb(AccountManager.activeId.value ?: "")
                    loadMessagesFromDb()
                }.onFailure {
                    _dbLocked.value = true
                    android.util.Log.e("RCQ", "history unavailable this session", it)
                }
            }
            CrashReporter.crumb(appCtx, "load_db")
            // The RCQ relays (obfuscated sing-box transport), engaged BEFORE
            // the socket/API connect so they ride the sing-box tunnel. Engage
            // when the user forced it on OR — the chicken-and-egg fix — when a
            // direct /health probe fails, since a blocked user can't reach
            // Settings to flip the toggle. Healthy network + toggle off = the
            // probe succeeds and we connect directly as before (no transport,
            // no overhead). The blocking sing-box start runs here off the main
            // thread; api/socket are rebuilt so they capture the SOCKS proxy.
            runRouteLadder()
            connectAndSync(uin, token)
            // (Crash reports are NOT auto-sent. A captured crash is offered to
            // the user on next launch via a consent dialog in MainActivity —
            // sending technical data silently would clash with the privacy
            // posture. See CrashReporter + RcqApp's crash-consent dialog.)
            // Pull a fresh signed relay list (direct mirrors) for NEXT launch —
            // best-effort, never blocks the connect. So a rotated relay is
            // picked up without an app update.
            launch { runCatching { app.rcq.android.net.RelayConfigStore.refresh(appCtx) } }
            // Pull a few broker bridges (anti-enumeration channel) alongside —
            // best-effort, merged into the transport pool as off-config fallback.
            // Then report which known relays are reachable from this network, so
            // the broker serves them region-by-region (throttled hourly inside).
            launch(Dispatchers.IO) {
                runCatching { app.rcq.android.net.BrokerRelayStore.refresh() }
                runCatching { app.rcq.android.net.BrokerRelayStore.reportReachability() }
            }
        }
    }

    /** Toggle for the RCQ relays: both the home "Route the app through RCQ
     *  relays" control and the Settings switch land here. Engages/drops
     *  sing-box LIVE (the pref alone left a running tunnel running): rebuild
     *  the API + socket so they capture (or release) the SOCKS proxy, then
     *  reconnect. Lets a user who's being blocked turn the tunnel on and have
     *  messages start flowing without restarting the app.
     *
     *  ⚠ Nothing here waits for a restart, so no copy may say it does. The
     *  Settings description used to promise "takes effect on next launch" long
     *  after the switch had been rewired to this (#722); that sentence is still
     *  true of the onion row below it, which only writes a mode. */
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
            _bypassManual.value = transport.isEnabled(appCtx)
            _routeVerified.value = transport.isActive && transport.probeCurrentRoute(serverHost())
            connectAndSync(uin, token)
        }
    }

    /** Switch to / from local-proxy transport (route everything through the
     *  user's OWN local Tor/i2p SOCKS5/HTTP proxy). Like [setObfuscation] but
     *  selects LOCAL_PROXY mode (exclusive of relays/onion) and rebuilds the API
     *  + socket so they capture the proxy, then reconnects. There is NO automatic
     *  fallback to relays if the proxy is down — that would leak traffic around
     *  the user's Tor; instead the connect fails and the user switches back. */
    fun setLocalProxy(on: Boolean, host: String, port: Int, type: String) {
        val transport = app.rcq.android.net.SingBoxTransport
        if (on) {
            transport.setLocalProxy(appCtx, host, port, type)
            transport.setMode(appCtx, app.rcq.android.net.SingBoxTransport.Mode.LOCAL_PROXY)
            transport.setEnabled(appCtx, true)
        } else {
            transport.setMode(appCtx, app.rcq.android.net.SingBoxTransport.Mode.RELAYS)
            transport.setEnabled(appCtx, false)
        }
        val uin = store.uin ?: return
        val token = store.token ?: return
        scope.launch {
            // Force a config rebuild (start() is a no-op if already active, so
            // stop first when switching mode while engaged).
            if (transport.isActive) transport.stop()
            if (on) transport.start()
            app.rcq.android.push.embedded.EmbeddedDistributor.reconnectNow(appCtx)
            socket.disconnect()
            api = newApi()
            socket = newSocket()
            _stealthActive.value = transport.isActive
            _bypassManual.value = transport.isEnabled(appCtx)
            _routeVerified.value = transport.isActive && transport.probeCurrentRoute(serverHost())
            connectAndSync(uin, token)
        }
    }

    /** Open the WebSocket + pull the contact graph. Split out of [start] so the
     *  transport engage can run first on a background coroutine. */
    /** Walk the route ladder: direct probe -> CF front -> relay tunnel, then a
     *  post-engage health check that can drop back to direct.
     *
     *  Extracted from [start] so it can be run AGAIN while the app is running.
     *  It used to happen exactly once, at launch, which meant a network that
     *  started blocking mid-session was never re-evaluated: the socket just
     *  retried the dead route with backoff until the user killed the app. From
     *  the outside that is "RCQ broke", while the relays sat there unused.
     *
     *  Returns true when the route CHANGED (front engaged, tunnel started or
     *  dropped), so the caller knows the socket has to be rebuilt.
     */
    private suspend fun runRouteLadder(): Boolean {
        val before = frontHost to app.rcq.android.net.SingBoxTransport.isActive
        val transport = app.rcq.android.net.SingBoxTransport
        val directOk = transport.probeDirect(serverHost())
        val flagship = store.serverHost.isNullOrBlank() || store.serverHost == RcqApi.DEFAULT_HOST
        // CF FRONT FALLBACK (tried BEFORE the relay): the flagship is blocked
        // directly, the user isn't forcing the relay/local-proxy, and the
        // Cloudflare front reaches the island -> route the API + WS through
        // cdn.rcq.app (CF's collateral-resistant IPs proxy to api.rcq.app) with
        // NO relay. Simpler + harder to IP-block than a relay; relays remain the
        // fallback (and the privacy path) if the front is also blocked. Skipped
        // under a forced relay/local-proxy and for custom islands (the front only
        // proxies the flagship).
        if (!directOk && flagship && !transport.isEnabled(appCtx) && !transport.localProxyMode() &&
            transport.probeDirect(FRONT_HOST)
        ) {
            frontHost = FRONT_HOST
            api = newApi()
            socket = newSocket()
            android.util.Log.i("RCQfront", "direct api blocked, CF front reachable — routing via $FRONT_HOST")
            // The push socket is the one connection this branch does NOT fix by
            // itself: it dials its own host, and this path deliberately runs
            // with no relay, so it would keep retrying a blocked address while
            // the API and the message socket both sail through the front. Kick
            // it so it re-reads the subscribe host (a signed config naming
            // `transport.push` moves it onto the front too).
            app.rcq.android.push.embedded.EmbeddedDistributor.reconnectNow(appCtx)
        }
        // Engage the relay when the user forced it on, OR (auto-fallback) when
        // direct is unreachable AND the front didn't take over — UNLESS the user
        // opted out of auto-engage. The explicit toggle always wins; the opt-out
        // gates only the probe-driven auto-engage. The opt-out is read through
        // [mayAutoEngage] rather than inline so that this path and every other
        // automatic one ask the identical question (#588).
        val engage = frontHost == null && (transport.isEnabled(appCtx) ||
            (!directOk && transport.mayAutoEngage(appCtx)))
        if (engage && !transport.isActive) {
            // Use the freshest known relay list (last verified payload off
            // disk) before building the transport; bundled if none yet.
            app.rcq.android.net.RelayConfigStore.prime(appCtx)
            if (transport.start()) {
                api = newApi()
                socket = newSocket()
                // The push socket dials at app start, a beat before this
                // engages; leaving it pinned to the direct route means the
                // one connection that still has to work on a censored
                // network is the one not using the tunnel.
                app.rcq.android.push.embedded.EmbeddedDistributor.reconnectNow(appCtx)
            }
        }
        // Island unreachable, the front did not take over, and the tunnel stayed
        // down because the user asked us not to raise it by ourselves. That
        // combination ends as a permanent "Connecting…", which is exactly what a
        // broken app looks like — so it is stated instead of endured (#588).
        // Silent when the opt-out is off, so the ordinary auto-engage is
        // unchanged.
        if (!directOk && frontHost == null && !transport.isActive && !transport.isEnabled(appCtx)) {
            transport.noteAutoEngageDeclined(serverHost())
        }
        // Post-engage health check + DIRECT fallback (iOS parity, AppState
        // re-probe). The trap behind "Резерв включён, но работает только с
        // VPN": the tunnel engaged (toggle on, or a transient probe miss) but
        // can't actually carry traffic to the backend, while a DIRECT
        // connection CAN — e.g. an UNcensored user whose relay/onion path is
        // broken. Without this they're stuck on a dead tunnel forever (the
        // watchdog only self-heals onion). So: if the tunnel is up but the
        // live route is dead AND direct works, drop to direct.
        // STRICT GATING so a genuinely-blocked user is NEVER silently
        // de-tunnelled: only fall back when direct actually succeeds (a
        // censored user's direct probe fails → stays on the tunnel), NEVER
        // under the user's own local proxy (Tor/i2p — that hard no-fallback
        // rule is the Tor-leak invariant), and NEVER under an explicit
        // per-device onion opt-in (preserve the metadata-resistance the user
        // deliberately chose). Cohort-flipped onion (signed config) on an
        // open network does fall back — it's a censorship aid, moot when
        // direct works.
        var routeOk = false
        // Set by whichever fallback below actually fires. ⚠ It cannot be
        // derived at the end instead: both fallbacks call transport.stop(), so
        // by then "the tunnel was up and carried nothing" is indistinguishable
        // from "never needed a tunnel" — which is the one distinction this
        // whole measurement exists to make.
        var fallbackTaken: String? = null
        if (transport.isActive) {
            // Probe the live route once: it tells the shield whether the tunnel
            // actually carries traffic (read-only /health through the proxy; safe
            // for onion too — it does NOT tear the chain down).
            routeOk = transport.probeCurrentRoute(serverHost())
            // DIRECT fallback only when droppable: tunnel up but dead AND direct
            // works -> drop. NEVER under a local proxy (Tor-leak rule) nor an
            // explicit onion opt-in (preserve chosen metadata-resistance).
            val droppable = !routeOk && !transport.localProxyMode() && !transport.isOnionOptIn(appCtx)
            if (droppable && transport.probeDirect(serverHost())) {
                android.util.Log.i("RCQsingbox", "tunnel unreachable, direct works — falling back to direct")
                fallbackTaken = "fell_to_direct"
                transport.stop()
                api = newApi()
                socket = newSocket()
            } else if (droppable && flagship && transport.probeDirect(FRONT_HOST)) {
                // Tunnel dead AND direct dead — the state where this install has
                // nothing left. The front was skipped on the way in because the
                // relays were engaged, and that is right while they work:
                // a relay hides the user's address from the island, the front
                // does not. But an engaged tunnel that carries nothing is not
                // privacy, it is an app that does not open, and the front is
                // the one path still standing when both the island's address
                // and the relays are blocked while Cloudflare is not.
                //
                // Same gating as the direct fallback above: never under the
                // user's own local proxy, never under an explicit onion opt-in.
                android.util.Log.i("RCQfront", "tunnel and direct both dead — routing via $FRONT_HOST")
                fallbackTaken = "fell_to_front"
                transport.stop()
                frontHost = FRONT_HOST
                api = newApi()
                socket = newSocket()
                app.rcq.android.push.embedded.EmbeddedDistributor.reconnectNow(appCtx)
            }
        }
        // What this network let us do, for the island's per-region counters.
        // The two fallbacks above have already spoken for themselves; this
        // names the outcomes that end here. A tunnel that is up and carrying
        // is the only good one — the rest are the shapes of a blocked network,
        // and telling them apart is the whole point (a reachability probe
        // cannot, it only ever sees whether a port answered).
        app.rcq.android.net.BrokerRelayStore.noteTransportOutcome(
            fallbackTaken ?: when {
                transport.isActive && routeOk -> "tunnel_ok"
                transport.isActive -> "tunnel_dead"
                frontHost != null -> "fell_to_front"
                else -> "direct_ok"
            },
        )
        _stealthActive.value = transport.isActive
        _bypassManual.value = transport.isEnabled(appCtx)
        _routeVerified.value = transport.isActive && routeOk
        return (frontHost to app.rcq.android.net.SingBoxTransport.isActive) != before
    }

    private fun connectAndSync(uin: Int, token: String) {
        // ⚠⚠ Never from a migrated decoy session. [uin]/[token] here are always
        // the REAL account's (they come out of `store`), and `db` is the decoy
        // file: every frame this socket delivered would be filed in the duress
        // store and acked on the island, i.e. removed from the real history.
        // The reachable caller is not the user — it is the route watchdog in
        // start(), whose coroutine outlives the lock.
        if (duressViewUp) return
        socket.connect(
            uin = uin,
            token = token,
            onEvent = ::handleEvent,
            onState = { up ->
                // `|| duressViewUp`: entering a migrated decoy disconnects the
                // real socket, and OkHttp delivers that onClosed a few
                // milliseconds later — after startDecoySession has already set
                // the dot green. Without this the duress view sits on a
                // permanent "Connecting…", which is itself something to explain.
                _connected.value = up || duressViewUp
                // When the link went down, so the watchdog below can tell a
                // blip (the socket's own backoff handles those) from a network
                // that has started blocking us and needs the whole route
                // ladder walked again.
                // How long this reconnect was actually offline, measured before
                // the marker is cleared — the silence probe shifts its clocks by
                // exactly this much (see below).
                val offlineGapMs =
                    if (up && offlineSince != 0L) android.os.SystemClock.elapsedRealtime() - offlineSince else 0L
                offlineSince = if (up) 0L else
                    (offlineSince.takeIf { it != 0L } ?: android.os.SystemClock.elapsedRealtime())
                // The primary answered: whatever the backup drain thought, we
                // are not in failover any more.
                if (up) _receivingViaBackup.value = false
                // The socket is back: hand over the call signals it refused
                // while it was down, `call_end` first among them (#699).
                if (up) flushCallOutbox()
                // And tell the island we are still in the room we think we
                // are in; it may have evicted us while the socket was gone.
                if (up) audioRooms.onSocketUp(offlineGapMs)
                if (up) {
                    // ⚠⚠ There used to be a blanket five-second mute here, armed
                    // on EVERY connect including the first one of a session, to
                    // cover a burst of missed messages the server replayed over
                    // the socket right after connect.
                    //
                    // The server stopped doing that: the offline queue is drained
                    // exclusively over HTTP `/messages/queue` now (see the comment
                    // where the WS post-connect drain used to be, in the backend's
                    // ws.py), and that path is already marked as backlog by
                    // `asBacklog`. So the window guarded against a burst that no
                    // longer arrives, and all it still did was silence real
                    // messages: open the app, have somebody write to you in the
                    // next five seconds, hear nothing (#480). It muted the tone,
                    // the in-app banner and the shade notification alike, because
                    // the check sits above all three.
                    //
                    // The drain marker stays. It is the accurate answer to "was
                    // this message already announced" and it does not depend on a
                    // clock.
                    // A reconnect (after an offline gap) re-pulls the graph so
                    // a roster that failed to load earlier recovers without a
                    // cold start. The first connect is skipped — start() below
                    // already kicked the initial load.
                    if (everConnected) syncGraph()
                    // The first connect is covered by start()'s sync, except
                    // when that sync ran with no network: the island's
                    // capabilities then still say what the cache said (or
                    // nothing, on a fresh install), and a switch the island
                    // turned on since, the room log above all, would wait for
                    // the NEXT reconnect. Ask once more now.
                    else if (capsLiveHost != serverHost()) refreshCaps()
                    // The vault nudge is pub/sub with NO REPLAY: a slot another
                    // device wrote while this socket was down is never
                    // announced again. A reconnect is exactly the moment that
                    // gap closes, and it is also where a write of OURS that
                    // never landed gets its retry (see [sweepVaultSlots]).
                    sweepVaultSlots(force = !everConnected)
                    everConnected = true
                    // The silence probe measures how long a PEER has been quiet,
                    // and a stretch when THIS side had no socket measures
                    // nothing: their replies may be sitting in the queue this
                    // reconnect is about to drain.
                    //
                    // ⚠ So the clocks are PUSHED FORWARD by the gap, not reset.
                    // Resetting looked equivalent and is not: a link that
                    // redials more than once every two minutes — a phone in a
                    // tunnel, a flapping VPN, two installs evicting each other
                    // — would rearm every clock before any of them could reach
                    // the threshold, and the probe would never fire again for
                    // exactly the users whose sessions are most likely dead.
                    if (offlineGapMs > 0) {
                        for ((k, armedAt) in awaitingReplySince) {
                            awaitingReplySince[k] = armedAt + offlineGapMs
                        }
                    }
                    // Connection is back — auto-resend anything stuck in FAILED
                    // (transient network/relay death) so the user doesn't have to
                    // tap each red error by hand.
                    retryFailedSends()
                    // If the previous launch died natively during startup, the
                    // content-free breadcrumb report is now submittable — do it
                    // here (the consent prompt can't show during a crash loop).
                    maybeAutoReportNativeCrash()
                    // Measure TURN reachability on THIS network now, while nobody
                    // is waiting. The measurement used to happen inside the call
                    // itself, which spent its timeout before the offer (or, worse,
                    // before the answer) went out. Runs once per network per URL
                    // set; a reconnect on a new link re-measures.
                    calls.prewarmRelayPath()
                }
            },
            onAuthRejected = ::onSocketAuthRejected,
        )
        syncGraph()
    }

    /** Ask the island what it runs (GET /server/info) and apply the answer to
     *  the surface flags and the wire switches. One in flight at a time; the
     *  socket's first connect after a boot with no network asks again. */
    private fun refreshCaps() {
        if (!capsRefreshInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                // Pin the island this answer is FOR. An account switch while
                // the request is in flight rebinds the session to another
                // island, and the old island's answer would then overwrite the
                // new one's flags and be cached under the wrong host.
                // Pinned by HOST, not by the api object: the route watchdog
                // rebuilds `api` on an onion entry-guard rotation, and that
                // answer is still this island's.
                val askedHost = serverHost()
                val info = api.serverInfo()
                val caps = info.capabilities
                if (serverHost() != askedHost) return@launch
                // The island's own description, kept per HOST so the switcher
                // and the account manager can draw an island nobody is signed
                // into at the moment. Not in a decoy session: nothing about
                // one is written to disk, here as everywhere.
                if (!app.rcq.android.data.AccountManager.isDecoyMode) {
                    app.rcq.android.data.IslandCards.record(
                        appCtx, askedHost, info.name, info.logo_version,
                    )
                }
                val wasLogReader = groupLogReader
                val wasVault = vaultEnabled
                applyCaps(caps, live = true)
                capsLiveHost = askedHost
                app.rcq.android.data.AccountManager.serverMaxAccounts = caps.max_accounts_per_device
                rememberCaps(askedHost, caps)
                // The drain on start ran on the CACHED flag, and on an island
                // we had never asked (a fresh install, the first start after
                // the island upgraded, a boot with the radios off) that flag
                // was false: the log was skipped. Run it now that the island
                // has said yes, once; from the next start on the cached
                // answer covers it. Serialised behind the legacy drain by the
                // drain lock, never beside it.
                if (groupLogReader && !wasLogReader) {
                    runCatching { withRetry { drainGroupLog() } }
                }
                // Same story for the vault: the sweep on the first connect ran
                // on the CACHED flag, and on an island we had never asked (a
                // fresh install, the first start after the island upgraded, a
                // boot with the radios off) that flag was false, so the
                // sections slot was never read. Sweep now that the island has
                // said yes; from the next start on the cached answer covers it.
                if (vaultEnabled && !wasVault) sweepVaultSlots(force = true)
                // The island takes anonymous key lookups: mint the first
                // batch of deposit tokens now, in the background, so the
                // first v=2 session start does not pay the PoW on the send
                // path. The answer came over this api object's route, so
                // the mint goes the same way.
                if (store.isRegistered) api.warmDepositTokens()
            } catch (e: Exception) {
                android.util.Log.w("RCQnet", "server/info: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                capsRefreshInFlight.set(false)
            }
        }
    }

    /** Pull the contact graph + offline queue, each retried and
     *  soft-failing independently. A transient failure at launch used to
     *  strand the UI with an empty roster until the next cold start; the
     *  retry (and the reconnect-driven re-call) make it recover on its own. */
    private fun syncGraph() {
        if (duressViewUp) return
        // Stage 5: the room log is drained right after the legacy queue, in the
        // same coroutine, and both take the drain lock: they share the
        // poison-row marker (#616), and two of them writing it at once would
        // pin a crash on the wrong row. Each is retried and soft-fails on its
        // own; a dead log fetch does not cost the legacy rows.
        scope.launch {
            runCatching { withRetry { drainQueue() } }
            runCatching { withRetry { drainGroupLog() } }
        }
        // Multihoming v1: also drain the backup-island mailboxes (dedup by
        // envelope uuid collapses anything the primary already delivered).
        scope.launch { runCatching { drainBackupQueuesOnce() } }
        // §5c guest mailboxes on visited islands. This used to run ONLY in the
        // 30-second loop, so a cold start — which is exactly what tapping a
        // push does — showed the chat empty for up to half a minute while the
        // message sat on the other island. From the outside that is "пуш есть,
        // а текст не доходит" (vss). The backup drain above was already here;
        // this is the same call for the cross-island half.
        scope.launch { runCatching { drainVisitedQueuesOnce() } }
        scope.launch { runCatching { withRetry { refreshContacts() } } }
        scope.launch { runCatching { withRetry { refreshPending() } } }
        scope.launch { runCatching { withRetry { refreshOutgoing() } } }
        scope.launch { runCatching { withRetry { refreshGroups() } } }
        scope.launch { runCatching { withRetry { loadOwnReadReceiptSetting() } } }
        // A distributor the server cannot reach AT ALL leaves the user with no
        // wakes and nothing to explain it — and no way to be told, since being
        // told would take the push that is broken. Move off it here, once.
        scope.launch {
            loadPushHealth()?.let {
                runCatching { app.rcq.android.push.Push.healUnreachableDistributor(appCtx, it) }
            }
        }
        // Only once the Linked Devices screen has been opened: registry events
        // are fire-and-forget pub/sub with no offline queue, so anything that
        // changed while the socket was down would otherwise stay invisible.
        if (_devices.value != null) scope.launch { runCatching { refreshDevices() } }
        // Optional-surface flags for this server (UIN shop). Best-effort:
        // failure keeps the permissive default so the shop stays reachable.
        // ⚠ Start from what this island said LAST time, not from the permissive
        // default. The default exists for an island we have never asked, and it
        // has to stay permissive; but applying it again on every launch means a
        // surface the island has switched OFF comes back for the second or two
        // the request takes. Seen with my own eyes on a cold start: the retired
        // "Nearby" button in the bottom bar, there and then gone, on an island
        // that answers `nearby: false`. A tester asked what it was (#690).
        // (the flags were already seeded from `cachedCaps` at construction)
        refreshCaps()
        // Advertise sender-keys support so others broadcast to us (encrypt-once)
        // instead of the legacy per-member fan-out. Fire-and-forget.
        scope.launch { runCatching { api.advertiseCapabilities(senderKeys = true) } }
        // Re-register our UnifiedPush endpoint with this account's island so it
        // can wake the device for offline messages/calls. Idempotent upsert;
        // covers an endpoint obtained before login + account/island switches.
        scope.launch {
            runCatching {
                app.rcq.android.push.Push.savedEndpoint(appCtx)?.let {
                    api.setPushToken(it, app.rcq.android.net.DeviceId.get(appCtx))
                }
            }
        }
        // Ensure our libsignal prekey bundle is published so peers can start
        // v=2 sessions with us. Best-effort: failure leaves us on v=1.
        scope.launch {
            runCatching { store.uin?.let { SignalBootstrap.ensureBootstrapped(signalStores, api, it, identityPub()) } }
                .onFailure { android.util.Log.w("RCQsignal", "bootstrap failed: ${it.javaClass.simpleName}: ${it.message}") }
            // Federation F1: publish our signed home-island record now that the
            // libsignal identity exists. Best-effort; never throws upward.
            publishHomeIslandRecord()
        }
    }

    /** Every home this account lives on as far as THIS install knows: the
     *  primary island first, then the stored backups. Fronts and duplicates of
     *  the primary are dropped even if the store still carries one (see the
     *  scrub in [start]): the island now REJECTS any record naming its own
     *  front, so one phantom row would cost the whole publish, legitimate
     *  homes included. */
    private fun ownRecordHomes(uin: Int): List<RcqFederation.Home> =
        listOf(RcqFederation.Home(serverHost(), uin)) +
            MultihomeStore.list(uin)
                .filterNot { it.host.equals(serverHost(), true) || RelayConfigStore.isFrontHost(it.host) }
                .map { RcqFederation.Home(it.host, it.uin) }

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
            val mine = ownRecordHomes(uin)
            // ⚠⚠ Read before publishing. The homes list belongs to the ACCOUNT,
            // not to this install: a backup island switched on in the web, or on
            // a second phone, is not in `MultihomeStore` here. Publishing `mine`
            // alone put a record without it under a fresh `ts`, and since the
            // island rejects only an OLDER ts, this device silently unpublished
            // the other one's backup — senders stop being told the mailbox
            // exists. Anything already in the published record and not known
            // here is carried over untouched (we hold no credentials for it, and
            // the record is an address list, not an authorisation).
            //
            // Untouched with one exception: a front in the published record is
            // the phantom old builds left behind (they stamped the road,
            // cdn.rcq.app, instead of the island). Carrying it forward would
            // re-publish the very row the scrub in [start] removes, forever.
            val published = withContext(Dispatchers.IO) { Multihome.ownPublishedHomes(serverHost(), uin, sk) }
            val homes = mine + published.filter { p ->
                mine.none { it.host.equals(p.host, true) } &&
                    !p.host.equals(serverHost(), true) && !RelayConfigStore.isFrontHost(p.host)
            }
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
            val homes = ownRecordHomes(uin)
            val ts = (System.currentTimeMillis() / 1000).toInt()
            val doc = RcqFederation.buildRecord(ik, sk, signingPriv, homes, ts)
            val env = Envelope.HomeRecord(doc)
            val ownHost = serverHost()
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
        // Keyed on `store.uin` — the REAL number even under duress — and each
        // row is a real `uin@host` of the person being coerced. Same rule as
        // the cross-island roster: the duress view lists nothing it was not
        // seeded with. [startDecoySession] empties the flow; this stops any
        // later call refilling it.
        if (duressViewUp) { backupHomes.value = emptyList(); return }
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
                ownHost = serverHost(),
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
        val oldHost = serverHost()
        if (host == oldHost) throw IllegalArgumentException("primary_island")
        if (MultihomeStore.list(oldUin).none { it.host == host }) throw IllegalArgumentException("not_backup")
        // ⚠ EVERY failure used to collapse into one word, "unreachable", and the
        // screen said "the island is unreachable, nothing changed" whether the
        // island had answered 401, answered 404, or never been reached at all
        // (#687: three islands, all of them answering from the outside, all of
        // them reported unreachable, and the report could not say more because
        // the app did not know either). The cause now rides in the message:
        // `no_account_here` when the island does not know this key, `island_said`
        // plus the status when it answered with one, `no_route` when the address
        // did not resolve, `unreachable` only when nothing answered at all.
        val cred = withContext(Dispatchers.IO) {
            try {
                Multihome.recoverOn(host, signingPriv(), signingPub())
                    ?: throw IllegalArgumentException("no_account_here")
            } catch (e: java.net.UnknownHostException) {
                throw IllegalArgumentException("no_route")
            } catch (e: java.io.IOException) {
                // Just the status code. A fixed-width prefix of the message
                // dragged a fragment of the island's JSON body onto the screen,
                // and that body is the island's to phrase, not ours to show
                // half of.
                val code = Regex("^HTTP (\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)
                throw IllegalArgumentException(
                    if (code != null) "island_said:HTTP $code" else "unreachable",
                )
            }
        }

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
            Multihome.autoPickHost(serverHost(), MultihomeStore.list(uin).map { it.host }.toSet())
        } ?: throw IllegalArgumentException("no_island")
        withContext(Dispatchers.IO) {
            Multihome.addBackupIsland(
                ownUin = uin,
                ownHost = serverHost(),
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
    private suspend fun drainBackupQueuesOnce(epoch: Int = epochNow()) {
        // The island DELETES each row once it is handed over (Multihome.ack),
        // so draining into the decoy store does not copy a message — it moves
        // it there and destroys the only other copy. This loop runs on a timer
        // that survives the lock, so the guard belongs here and not only at the
        // call site.
        if (duressViewUp) return
        // ⚠⚠ And the same "acked and gone" hazard is what makes an account
        // switch mid-drain worse than a leak here: the ack destroys account
        // A's mail on the backup island while the rows are being filed under
        // account B, where they will never decrypt. See [accountEpoch].
        if (!stillOn(epoch)) return
        val uin = store.uin ?: return
        if (MultihomeStore.list(uin).isEmpty()) return
        val sp = runCatching { signingPriv() }.getOrNull() ?: return
        val pp = runCatching { signingPub() }.getOrNull() ?: return
        // Is the primary actually down right now? Cheap /health through the
        // live route; a backup drain on a healthy primary is just the normal
        // belt-and-braces copy and must NOT raise the failover flag.
        //
        // ⚠ A phone with the radios off fails that probe exactly the way a
        // blocked island does, and the banner then told the user two untrue
        // things at once: that their island was not answering, and that their
        // mail was arriving through the backup one (#674). Nothing arrives
        // through anything without a network, so say nothing and skip the
        // probe; the flag is cleared so a banner raised while online goes away.
        if (!app.rcq.android.net.SingBoxTransport.hasNetwork(appCtx)) {
            _receivingViaBackup.value = false
            return
        }
        withContext(Dispatchers.IO) {
            _receivingViaBackup.value =
                !app.rcq.android.net.SingBoxTransport.probeCurrentRoute(serverHost())
        }
        if (!stillOn(epoch)) {
            // ⚠ The probe above LATCHES the banner. Bailing out here without
            // clearing it leaves "your mail is arriving through the backup
            // island" on screen for the account we just switched TO, about a
            // probe run for the account we left.
            _receivingViaBackup.value = false
            return
        }
        // Read ONCE, here, while the epoch still holds: myDeviceId() reads the
        // live store, and after a switch it answers for the other account.
        val dev = myDeviceId()
        withContext(Dispatchers.IO) {
            // Marked per row, not around the whole sweep — see drainQueue.
            // Stage 5: a backup island that keeps one log per room is drained
            // from it too, right after its queue; a log row is filed exactly
            // like a legacy group row of the same mailbox, under the alias.
            Multihome.drainBackupQueues(uin, sp, pp, dev, stillOurs = { stillOn(epoch) && !duressViewUp }, onLogRow = { payload, groupId, host ->
                // ⚠⚠ NO account guard here, deliberately. This lambda's answer
                // means "is the row done with": null closes it and lets the
                // room's ack move past it. Refusing a row by answering null
                // would therefore DESTROY it - the cursor only goes forward and
                // the log cannot be re-read from a given seq. The switch is
                // handled where it can actually stop the work, in
                // drainGroupLog's page loop, before the ack.
                asBacklog { ingestGroup(payload, VisitedIslandsStore.aliasFor(host, groupId)) }
            }) { payload, groupId, host ->
                // ⚠ VisitedIslandsStore is a SINGLETON re-pointed by rebindTo,
                // so aliasFor here would mint account A's room aliases in
                // account B's namespace, and no decryption is needed for that
                // to be a leak: the alias carries the foreign host and room id.
                if (stillOn(epoch)) {
                    asBacklog {
                        // A group row in a BACKUP mailbox = that island also hosts a
                        // group we joined (§5c, same identity = same mailbox) — file it
                        // under the local alias like the visited-island drain does.
                        if (groupId != null) ingestGroup(payload, VisitedIslandsStore.aliasFor(host, groupId))
                        else ingest(payload)
                    }
                }
            }
        }
    }

    /** §5c: drain the guest mailbox on every visited island — the receive path
     *  for cross-island groups. Group rows file under the local alias; a stray
     *  1:1 row (someone on that island messaged our guest uin) goes through the
     *  normal ingest, whose cross-island consent gate quarantines unknown
     *  senders. Never throws. */
    private suspend fun drainVisitedQueuesOnce(epoch: Int = epochNow()) {
        if (duressViewUp) return   // same acked-and-gone hazard as the backup drain
        if (!stillOn(epoch)) return
        // The list is a snapshot ON PURPOSE. VisitedIslandsStore is a singleton
        // that rebindTo re-points, so reading it again after a suspension gives
        // the NEXT account's islands, and the drain would then present the
        // previous account's guest credentials to them.
        val visited = VisitedIslandsStore.list()
        if (visited.isEmpty()) return
        val sp = runCatching { signingPriv() }.getOrNull() ?: return
        val pp = runCatching { signingPub() }.getOrNull() ?: return
        val dev = myDeviceId()
        if (!stillOn(epoch)) return
        withContext(Dispatchers.IO) {
            // Stage 5 on the guest mailbox too: the rooms we visit live on
            // their island, and one that keeps a log is read from it (the
            // same filing under the alias as the legacy rows below).
            Multihome.drainVisitedQueues(sp, pp, dev, visited = visited, stillOurs = { stillOn(epoch) && !duressViewUp }, onLogRow = { payload, groupId, host ->
                // ⚠⚠ NO account guard here, deliberately. This lambda's answer
                // means "is the row done with": null closes it and lets the
                // room's ack move past it. Refusing a row by answering null
                // would therefore DESTROY it - the cursor only goes forward and
                // the log cannot be re-read from a given seq. The switch is
                // handled where it can actually stop the work, in
                // drainGroupLog's page loop, before the ack.
                asBacklog { ingestGroup(payload, VisitedIslandsStore.aliasFor(host, groupId)) }
            }) { payload, groupId, host ->
                if (stillOn(epoch)) {
                    asBacklog {
                        if (groupId != null) ingestGroup(payload, VisitedIslandsStore.aliasFor(host, groupId))
                        else ingest(payload)
                    }
                }
            }
        }
    }

    /** Cache the owner's read-receipt visibility so we honour a "nobody"
     *  setting before emitting any receipts. */
    private suspend fun loadOwnReadReceiptSetting() {
        val me = store.uin ?: return
        val profile = api.getMe(me)
        profile.read_receipts_visibility?.let { readReceiptsVisibility = it }
        // Seed my own picture here too. It used to arrive only when the profile
        // EDITOR was opened, so a fresh launch showed the status flower in the
        // header until you went looking for your own profile.
        _ownAvatar.value = ownAvatarPair(profile.avatar_media_id, profile.avatar_media_key)
        // ⚠⚠ An install that never SET the picture has nothing in LocalStores,
        // so the resolver above finds no key and the face stays blank - on a
        // second phone, a browser-linked device, or any reinstall. The key is
        // the ACCOUNT's, not the install's, and the vault is where it lives.
        // Read-only (publishedKey never mints), once, and only when the island
        // says there IS a picture whose key we do not hold.
        if (!profile.avatar_media_id.isNullOrEmpty() && LocalStores.myProfileKey() == null) {
            val ep = epochNow()
            scope.launch {
                val k = runCatching {
                    ProfileKeyVault.publishedKey(api, store.identityPrivate ?: ByteArray(0))
                }.getOrNull()
                if (k != null && stillOn(ep)) {
                    _ownAvatar.value = ownAvatarPair(profile.avatar_media_id, profile.avatar_media_key)
                }
            }
        }
    }

    fun stop() = socket.disconnect()

    // ── panic-PIN at-rest lock ───────────────────────────────────────

    /** (Re)open the active account's message DB under the current dataKey:
     *  the unlocked PIN-vault key if a PIN is set, else the device key. On the
     *  device-key path, migrate any legacy plaintext DB to encrypted once. */
    private fun bindDb(accountId: String) {
        if (::db.isInitialized) db.close()
        // Third branch, and the whole point of the decoy rework: a MIGRATED
        // decoy session opens the decoy's OWN file under the decoy slot's OWN
        // key. It never sees an account's database and never sees the real
        // dataKey, so handing over the duress PIN no longer hands over the
        // SQLCipher passphrase for every account on the device.
        // A LEGACY decoy (pre-migration) still rides the account path below,
        // exactly as before, until the user passes the migration screen.
        if (PanicPinService.inDecoySession && !PanicPinService.decoyIsLegacy) {
            val decoyKey = PanicPinService.dataKey
                ?: throw DbLocked(DecoyStore.STORE_ID, IllegalStateException("decoy session without a key"))
            db = runCatching { MessageDb(appCtx, DecoyStore.STORE_ID, decoyKey) }.getOrElse { e ->
                CrashReporter.crumb(appCtx, "bindDb_failed")
                android.util.Log.e("RCQ", "decoy store would not open", e)
                throw DbLocked(DecoyStore.STORE_ID, e)
            }
            return
        }
        val pinKey = PanicPinService.dataKey
        val key = if (pinKey != null) pinKey else {
            val deviceKey = SecureStore.deviceKey(appCtx)
            runCatching { MessageDb.migrateToEncrypted(appCtx, accountId, deviceKey) }
            deviceKey
        }
        // ⚠⚠ Opening under a wrong key throws, and this used to be bare. The
        // caller runs inside `scope.launch { withContext(IO) { … } }` on a
        // SupervisorJob, so the throw killed exactly this coroutine and, with
        // it, everything queued behind it: `loadMessagesFromDb()`,
        // `runRouteLadder()` and `connectAndSync()`. The person then reports
        // "the app will not connect", and we go looking in the network — the
        // one place the fault is not. Whatever happens to the database, the
        // socket must still come up.
        //
        // No database is not the same as an empty one, so nothing is recreated
        // here: a wrong key is recoverable (enter the right PIN) while a
        // recreated file is not.
        db = runCatching { MessageDb(appCtx, accountId, key) }.getOrElse { e ->
            CrashReporter.crumb(appCtx, "bindDb_failed")
            android.util.Log.e("RCQ", "message db would not open for $accountId", e)
            throw DbLocked(accountId, e)
        }
    }

    /** The message database refused the key we have. Distinct from a generic
     *  failure so the UI can say "this history is locked" instead of showing an
     *  empty thread list, which reads as "everything is gone". */
    class DbLocked(val accountId: String, cause: Throwable) : Exception(cause)

    /** Tear the live session down when the app locks (background with a PIN
     *  set, flipped by [PanicPinService.lock]): drop the socket + in-memory
     *  history and close the DB so the unlocked dataKey + plaintext history
     *  leave this process's memory. [start] reopens everything after unlock.
     *  Driven by the [PanicPinService.locked] flow (observed in init). */
    private fun tearDownForLock() {
        calls.teardown()
        audioRooms.teardown()
        nearby.teardown()
        radio.teardown()
        socket.disconnect()
        _connected.value = false
        _messages.value = emptyMap()
        _groupMessages.value = emptyMap()
        // Same reason as in [rebindTo]: a lock can be followed by an unlock
        // into a DIFFERENT identity, and a live avatar left over from the
        // locked one is what the switcher-card recorder would write onto that
        // identity's row.
        _ownAvatar.value = null
        // ⚠⚠ BEFORE the database closes. A lock is not an account switch, so
        // this used to leave the epoch alone and every guard written for a
        // switch read "nothing changed" straight through it. The thirty second
        // multihome loop kept draining: ingest then hit a CLOSED SQLCipher
        // handle, the failure looked to it like an undecryptable row, and the
        // ack that followed told the island the rows were taken - so the island
        // DELETED mail that was never written anywhere. With the default grace
        // of zero that happens on every trip to the home screen.
        //
        // The bump also stops the loops accumulating: `started = false` below
        // lets the next unlock start a second copy of every one of them, and
        // only a changed epoch retires the first.
        accountEpoch++
        if (::db.isInitialized) { db.close() }
        started = false
        everConnected = false
        _decoyMigrationDue.value = false
        // Leaving a decoy session: drop the synthetic identity and put the
        // per-account prefs back on the real active account, so the next real
        // unlock does not read the decoy's unread counts and roster cache.
        if (decoySessionUin != null) {
            decoySessionUin = null
            decoySessionNickname = null
            _contacts.value = emptyList()
            ciRequests.value = emptyList()
            AccountManager.exitDecoySession()
            // All four, not just LocalStores: the decoy session re-pointed every
            // per-account store at its own namespace, and leaving three of them
            // there would give the next REAL unlock an empty cross-island roster
            // and no visited islands — the duress leak's mirror image.
            bindPerAccountStores(AccountManager.activeId.value)
        }
        // The roster is deliberately KEPT here (the chat list must survive a
        // lock), so its presence values are frozen at lock time. That makes
        // them a stale baseline: unlock hours later and the first live refresh
        // would knock once for everyone who came online in the meantime, which
        // is #422 again on a path the roster-clearing resets do not cover.
        presenceBaselineLive = false
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

    /** Change the PIN (re-seal the vault slot; the dataKey + DBs are untouched).
     *  From a DECOY session this re-seals the DECOY slot only (report #237: a
     *  coercer with the decoy PIN changes "their" PIN without ever touching or
     *  revealing the hidden real accounts). */
    suspend fun changePin(newPin: String): Boolean = withContext(Dispatchers.IO) {
        if (PanicPinService.inDecoySession) PanicPinService.changeDecoyPin(appCtx, newPin)
        else PanicPinService.changeRealPin(appCtx, newPin)
    }

    /** Remove the PIN: rekey every DB from the vault key back to the device key,
     *  then destroy the vault.
     *
     *  Report #237 (reset-PIN bypass): from a DECOY session, plainly removing
     *  the PIN would revert every DB — including the HIDDEN real accounts — to
     *  the device key, so a coercer could reset the decoy PIN and then see the
     *  real accounts on the next launch. Instead, a reset from duress first
     *  background-wipes the hidden accounts and drops decoy mode, leaving ONLY
     *  the decoy as a normal no-PIN install. To the coercer it looks like an
     *  ordinary PIN removal; the real accounts are gone from the device
     *  (recoverable later from a seed phrase on another device). */
    suspend fun removePin(): Boolean = withContext(Dispatchers.IO) {
        // A MIGRATED decoy session has its own path: the decoy is not a roster
        // account any more, and this session holds only the decoy slot's key —
        // it could not rekey the real accounts' databases back to the device
        // key even if it wanted to, and destroying the vault from here would
        // take their history with it.
        if (PanicPinService.inDecoySession && !PanicPinService.decoyIsLegacy) {
            return@withContext removePinFromDuress()
        }
        val decoyId = AccountManager.decoyMode.value
        if (decoyId != null) {
            AccountManager.accounts.value.filter { it.id != decoyId }.forEach { acc ->
                runCatching {
                    SecureStore.wipeAccount(appCtx, acc.id)
                    MessageDb.wipeAccount(appCtx, acc.id)
                    SignalStoreDb.wipeAccount(appCtx, acc.id)
                    app.rcq.android.data.VisitStore.wipeAccount(acc.id)
                    CrossIslandStore.wipeAccount(acc.id)
                    VisitedIslandsStore.wipeAccount(acc.id)
                    LocalStores.clearAccount(acc.id)
                    // The switcher's cached face for that account: a nickname,
                    // a uin, an island and an avatar id. Written outside
                    // LocalStores, so clearAccount does not reach it, and a
                    // wipe that leaves the name and picture of the account it
                    // just destroyed sitting in preferences is not a wipe.
                    app.rcq.android.data.AccountCards.forget(appCtx, acc.id)
                }
                AccountManager.remove(acc.id)
            }
        }
        val vaultKey = PanicPinService.dataKey ?: return@withContext false
        val deviceKey = SecureStore.deviceKey(appCtx)
        rekeyAllAccountDbs(from = vaultKey, to = deviceKey)
        // Destroying the vault throws away the decoy slot and with it the only
        // key to the decoy store, so the file could never be opened again by
        // anyone — but it would still be there, a database nothing can read
        // under a filename that is identical on every install. Take it with the
        // PIN, the way iOS's removeAllPINs does.
        runCatching {
            DecoyStore.destroy(appCtx)
            wipeDecoyNamespaceStores()
        }
        PanicPinService.removePin(appCtx)
        if (decoyId != null) AccountManager.exitDecoyMode()
        true
    }

    /**
     * "Remove PIN" tapped from inside a MIGRATED duress session.
     *
     * Report #237 rewritten. The old rule was "wipe every account except the
     * decoy account", which is meaningless now that the decoy is not a roster
     * account at all. What has to stay true is the outcome the coercer sees:
     * the PIN is gone and the app opens straight into the conversations they
     * were just shown. What has to stay true for the user is that the hidden
     * accounts are never left readable on the device.
     *
     * So: copy the decoy's history into a fresh account under the device key,
     * erase every real account's local storage, then destroy the vault and the
     * decoy store. The real accounts are gone from this phone (recoverable from
     * their recovery phrase elsewhere); what remains is an ordinary PIN-less
     * install holding the decoy's chats.
     *
     * ⚠ The copy is a COPY. Nothing here runs `PRAGMA rekey` and nothing here
     * re-encrypts a store in place — the decoy file is read, a new file is
     * written under the device key, and only then is the original deleted.
     */
    private fun removePinFromDuress(): Boolean {
        val decoyKey = PanicPinService.dataKey ?: return false
        val decoyUin = decoySessionUin ?: return false
        val decoyNick = decoySessionNickname ?: DecoyStore.randomNickname()
        val deviceKey = SecureStore.deviceKey(appCtx)
        runCatching { calls.teardown() }
        runCatching { audioRooms.teardown() }
        runCatching { nearby.teardown() }
        runCatching { radio.teardown() }
        runCatching { socket.disconnect() }
        if (::db.isInitialized) runCatching { db.close() }
        val shellId = java.util.UUID.randomUUID().toString()
        val roster = DecoyStore.exportTo(appCtx, decoyKey, shellId, deviceKey)
        // The copied file is already SQLCipher-encrypted under the device key;
        // mark it so the plaintext-migration probe never opens it keyless.
        SecureStore.setMsgDbMigrated(appCtx, shellId)
        AccountManager.accounts.value.forEach { acc ->
            runCatching {
                SecureStore.wipeAccount(appCtx, acc.id)
                MessageDb.wipeAccount(appCtx, acc.id)
                SignalStoreDb.wipeAccount(appCtx, acc.id)
                app.rcq.android.data.VisitStore.wipeAccount(acc.id)
                CrossIslandStore.wipeAccount(acc.id)
                VisitedIslandsStore.wipeAccount(acc.id)
                LocalStores.clearAccount(acc.id)
                // See the note in removePin: the switcher card lives outside
                // LocalStores and has to be forgotten by name.
                app.rcq.android.data.AccountCards.forget(appCtx, acc.id)
            }
            AccountManager.remove(acc.id)
        }
        DecoyStore.destroy(appCtx)
        wipeDecoyNamespaceStores()
        PanicPinService.removePin(appCtx)   // destroys the vault + pepper
        AccountManager.exitDecoyMode()
        AccountManager.exitDecoySession()
        // Stand the leftovers up as the one remaining, PIN-less account. No
        // token and no keys: it is not registered anywhere, so the session
        // never connects and the app simply looks offline.
        SecureStore.saveShellIdentity(appCtx, shellId, decoyUin, decoyNick)
        AccountManager.addWithId(shellId, null, null)
        decoySessionUin = null
        decoySessionNickname = null
        rebindTo(shellId)
        LocalStores.bindAccount(shellId)
        runCatching { LocalStores.setCachedContactsJson(gson.toJson(roster)) }
        runCatching {
            bindDb(shellId)
            loadMessagesFromDb()
        }
        _contacts.value = roster
        started = true      // nothing to connect to; keep start() from trying
        _connected.value = false
        return true
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

    /** Does the configured wipe PIN also erase the account server-side?
     *  Display only — the wipe reads the flag out of the wipe slot itself. */
    val wipeErasesServer: Boolean get() = PanicPinService.wipeErasesServer()

    /** Add a wipe PIN: entering it at the lock screen erases everything on this
     *  device, and — only when [eraseServer] is on — the account on the island
     *  too. Default off: the flag has never existed before and every locale's
     *  copy promises "on this device". */
    suspend fun setWipePin(pin: String, eraseServer: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        PanicPinService.setWipePin(appCtx, pin, eraseServer)
    }

    /** Remove the wipe PIN. */
    suspend fun removeWipePin(): Boolean = withContext(Dispatchers.IO) { PanicPinService.removeWipePin(appCtx) }

    /** DURESS WIPE: erase ALL local data for every account + the PIN vault, so
     *  the app drops to a fresh-install / onboarding state. Local-only (no
     *  server call) — it must work offline and instantly under coercion. The
     *  server-side accounts survive (recoverable later from another device if
     *  the keys were backed up); this is about the seized device. */
    suspend fun wipeEverything() = withContext(Dispatchers.IO) {
        // Read the flag out of the WIPE SLOT that was just entered, before the
        // vault goes. It is not in prefs on purpose: anyone holding an unlocked
        // phone can edit prefs, and switching this off is exactly what someone
        // who found the feature would do.
        val alsoServer = PanicPinService.consumeWipeServer()
        // Server-side erasure runs FIRST — it needs the tokens the local wipe
        // is about to destroy — and is strictly best-effort with a short
        // deadline. A duress wipe has to be instant and has to work offline, so
        // an unreachable island must never hold the local wipe up.
        if (alsoServer) deleteAccountsServerSide()
        runCatching { calls.teardown() }
        runCatching { audioRooms.teardown() }
        runCatching { nearby.teardown() }
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
                // See the note in removePin: the switcher card lives outside
                // LocalStores and has to be forgotten by name.
                app.rcq.android.data.AccountCards.forget(appCtx, acc.id)
            }
            AccountManager.remove(acc.id)
        }
        // ⚠ The decoy store is NOT a roster account, so the loop above never
        // reaches it. Without this a duress wipe left the seeded conversations
        // on disk under a fixed, recognisable filename that nothing can open —
        // a fresh-install-looking app with one unexplainable encrypted database
        // beside it, which is the opposite of what a wipe PIN is for. (iOS does
        // this in performPanicWipe; Android had no equivalent.)
        runCatching {
            DecoyStore.destroy(appCtx)
            wipeDecoyNamespaceStores()
        }
        // ⚠⚠ Two stores are keyed by NUMBER, not by account id, so the loop
        // above cannot reach them from `acc.id` - the same omission that had
        // them surviving a single-account burn. Here it is worse: a wipe PIN
        // promises there is nothing left, and what stayed behind was the §5f
        // request list, the numbers this identity blocked, and a LIVE JWT to
        // every backup island. Wiped wholesale, because a duress wipe has no
        // account left to be selective about.
        runCatching {
            MultihomeStore.wipeAll()
            CrossIslandRequestsStore.wipeAll()
        }
        // The install id lived in its own prefs file that nothing above touches,
        // and the island keeps it next to the uin. Left alone, the number this
        // phone registers next arrives wearing the erased account's name tag
        // and one SELECT joins the two — the wipe undone at the only layer that
        // still had a thread to pull.
        runCatching { app.rcq.android.net.DeviceId.rotate(appCtx) }
        // The push socket and its topic are the same kind of thread. The
        // service outlived the wipe entirely (nothing above touches it), and
        // the topic is minted once and deliberately REUSED across
        // re-registrations, so the account registered after a wipe would have
        // subscribed to the erased account's push address.
        runCatching {
            app.rcq.android.push.embedded.EmbeddedDistributor.stop(appCtx)
            app.rcq.android.push.embedded.EmbeddedDistributor.clear(appCtx)
        }
        PanicPinService.removePin(appCtx)   // destroys the vault, clears the lock + dataKey
        peerIdentityCache.clear(); noV2Peers.clear(); peerDeviceCache.clear(); awaitingReplySince.clear(); lastSilenceProbeAt.clear(); presenceBaselineLive = false; ackedReads.clear()
        // ⚠ Held call signals belong to the account that made them: an island,
        // a socket and a peer number that mean somebody else entirely on the
        // next one. Flushing them after a switch would hand a stranger's
        // hang-up to whoever holds that number here.
        synchronized(callOutbox) { callOutbox.clear() }
        _contacts.value = emptyList(); _pending.value = emptyList(); _outgoing.value = emptyList(); _messages.value = emptyMap()
        _groups.value = emptyList(); _groupMessages.value = emptyMap(); _devices.value = null
        activeRandomPeer = null; activeRandomPairId = null; _randomMessages.value = emptyList(); _random.value = RandomState.Idle
    }

    /** DELETE /auth/account for every account on the roster, each against its
     *  own island with its own token. Best-effort and time-boxed: this rides a
     *  duress wipe, where waiting is not an option. */
    private suspend fun deleteAccountsServerSide() {
        // ⚠ ONE deadline for the whole thing, and the calls run in parallel.
        // Per-account timeouts in a loop multiply: ten accounts on an island
        // that is merely unreachable (which is exactly what a hostile network
        // looks like) meant eighty seconds of a duress wipe not happening while
        // someone watched the screen. The local wipe is the part that must be
        // instant; this is the best-effort extra.
        kotlinx.coroutines.withTimeoutOrNull(8_000) {
            coroutineScope {
                AccountManager.accounts.value.map { acc ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val s = SecureStore(appCtx, acc.id)
                            val token = s.token ?: return@runCatching
                            val host = s.serverHost ?: RcqApi.DEFAULT_HOST
                            RcqApi("https://$host", isPrimary = false).apply { setToken(token) }.deleteAccount()
                        }.onFailure {
                            android.util.Log.w("RCQpin", "duress server delete failed for ${acc.id}: ${it.message}")
                        }
                    }
                }.forEach { it.await() }
            }
        }
    }

    val hasDecoyPin: Boolean get() = PanicPinService.hasDecoyPin()
    fun decoyAccountId(): String? = PanicPinService.decoyAccountId()

    /** A decoy configured under the OLD model (a roster account + the real
     *  dataKey) is still in the vault and has to be rebuilt once. */
    val decoyNeedsMigration: Boolean get() = PanicPinService.decoyNeedsMigration()

    // The one-time decoy migration screen (E). Raised after an unlock with the
    // REAL PIN and only then: rebuilding the decoy writes the real slot, and
    // the real slot key exists only when the real PIN was actually typed —
    // which is exactly why a biometric unlock can never reach this screen.
    private val _decoyMigrationDue = MutableStateFlow(false)
    val decoyMigrationDue: StateFlow<Boolean> = _decoyMigrationDue.asStateFlow()

    /** Called right after a REAL unlock. Deferrable on purpose: the old decoy
     *  PIN keeps working until the user finishes, so nagging is the worst this
     *  can do, and being trapped behind a modal during duress is not. */
    fun refreshDecoyMigration() {
        _decoyMigrationDue.value = PanicPinService.decoyNeedsMigration() && PanicPinService.canWriteRealSlot
    }

    fun dismissDecoyMigration() { _decoyMigrationDue.value = false }

    /** Can the decoy be (re)built right now? Needs the REAL slot key, which
     *  only a real PIN entry produces — a biometric unlock deliberately never
     *  holds it, so the migration screen is unreachable from one. */
    val canRebuildDecoy: Boolean get() = PanicPinService.canWriteRealSlot

    /** The 1:1 conversations that can be copied into the decoy, newest first:
     *  peer uin -> display name. Read from what is already loaded in memory,
     *  so this costs nothing and only ever offers threads that actually have
     *  messages (an empty one would seed an empty decoy). */
    fun decoySeedCandidates(): List<Pair<Int, String>> =
        _messages.value.entries
            .filter { it.value.isNotEmpty() }
            .sortedByDescending { e -> e.value.maxOf { it.sentAt } }
            .map { it.key to contactName(it.key) }

    /**
     * Build (or rebuild) the decoy: mint its own key + synthetic identity, seed
     * its own store with copies of [peerUins]' conversations, and only then
     * seal the duress PIN.
     *
     * The seeding is what makes the decoy survive being looked at, and the
     * rewriting inside [DecoyStore.seed] is what stops it giving itself away:
     * the copied rows carry synthetic uins, so nothing in that file points at
     * a real person. Nothing here touches the real slot's dataKey or any
     * existing account database.
     */
    suspend fun setDecoyPin(pin: String, peerUins: List<Int>): Boolean = withContext(Dispatchers.IO) {
        val id = PanicPinService.prepareDecoyIdentity(appCtx) ?: return@withContext false
        // Is there already a decoy slot? It decides what a refusal below may
        // destroy: a store the existing duress PIN still opens must survive.
        val hadDecoy = PanicPinService.hasDecoyPin()
        val threads = peerUins.distinct().mapNotNull { uin ->
            val msgs = _messages.value[uin].orEmpty().filter { it.groupId == null }
            if (msgs.isEmpty()) null
            else DecoyStore.SeedThread(realUin = uin, displayName = contactName(uin), messages = msgs)
        }
        // Refuse rather than seal a PIN over an empty store: every picked thread
        // turning out to hold nothing copyable is the one case the UI's
        // "pick at least one" check cannot see, and an empty decoy is the tell.
        if (threads.isEmpty()) return@withContext false
        if (!DecoyStore.seed(appCtx, id.dataKey, id.uin, threads)) return@withContext false
        if (!PanicPinService.commitDecoyPin(appCtx, pin, id)) {
            // The PIN was refused (too short, collides with another slot, or no
            // free slot) and the vault is exactly as commitDecoyPin found it.
            // If there was no decoy before, leave nothing an unlock could open.
            // If there WAS one, its slot and key are untouched, so the store we
            // just re-seeded is still the store that PIN opens — deleting it
            // would take a working duress view away and leave an empty one.
            if (!hadDecoy) DecoyStore.destroy(appCtx)
            return@withContext false
        }
        true
    }

    suspend fun removeDecoyPin(): Boolean = withContext(Dispatchers.IO) {
        val ok = PanicPinService.removeDecoyPin(appCtx)
        // The store is only reachable with the slot we just cleared, but a file
        // full of someone's copied conversations should not outlive the feature
        // — nor should the plaintext prefs (unread counts, mutes) it wrote
        // under its own namespace.
        if (ok) {
            DecoyStore.destroy(appCtx)
            wipeDecoyNamespaceStores()
        }
        ok
    }

    /** File a bug report — rides the /reports queue with context=bug_bounty
     *  (iOS parity; target is self, which the backend allows for bug_bounty).
     *  The platform + app version are tagged into the text so the admin queue
     *  shows which client a report came from. */
    suspend fun submitBugReport(text: String, attachments: List<RcqApi.ReportAttachment> = emptyList()): Boolean =
        submitBugReportResult(text, attachments) == BugReportResult.SENT

    /** Why a bug report did not go through, so the screen can say something
     *  instead of quietly resetting the button.
     *
     *  Reported by user-9547, who hit "Отправить", watched it return to
     *  "Отправить", and retried for a quarter of an hour: the server was
     *  answering 429 the whole time and nothing on screen said so. */
    enum class BugReportResult { SENT, RATE_LIMITED, CLOSED, TOO_LONG, FAILED }

    /** The island caps a report's text at 1000 characters, and we spend some of
     *  that on the client tag before the text is ever sent. The composer has to
     *  budget for the tag too, or the last stretch of what it lets you type is
     *  rejected — a report typed to one character under the on-screen limit came
     *  back as "проблемы со связью", and splitting it in two "fixed" it. Exposed
     *  from here so the cap and the thing being counted cannot drift apart. */
    val bugReportTextLimit: Int get() = 1000 - bugReportTag.length - 1

    private val bugReportTag: String get() = "[Android ${app.rcq.android.BuildConfig.VERSION_NAME}]"

    suspend fun submitBugReportResult(
        text: String,
        attachments: List<RcqApi.ReportAttachment> = emptyList(),
    ): BugReportResult {
        val me = store.uin ?: return BugReportResult.FAILED
        val tag = bugReportTag
        val body = "$tag $text"
        return runCatching { api.report(me, body, "bug_bounty", attachments) }.fold(
            onSuccess = { BugReportResult.SENT },
            onFailure = { e ->
                when {
                    e.message?.contains("429") == true -> BugReportResult.RATE_LIMITED
                    // The operator switched reports off on this island.
                    e.message?.contains("403") == true -> BugReportResult.CLOSED
                    // The island refused the CONTENT. Saying "connection problem"
                    // for this sends people to check their wifi over a report the
                    // network delivered perfectly well.
                    e.message?.contains("422") == true || e.message?.contains("400") == true ->
                        BugReportResult.TOO_LONG
                    // No HTTP status means the ANSWER never came back, which is
                    // not the same as the request never arriving: reported as
                    // "не удалось отправить" while the report sat in the list on
                    // the server. Ask the island whether it has the report before
                    // telling the user it failed.
                    else -> if (landedOnIsland(body)) BugReportResult.SENT else BugReportResult.FAILED
                }
            },
        )
    }

    /** Did [body] actually reach the island despite a failed round trip? Reads
     *  back my own reports and looks for the exact text. Any failure here means
     *  we genuinely cannot tell, so it answers false and the caller says the
     *  send failed, which is the safe direction (a duplicate is cheaper than a
     *  lost report, but a false "sent" is worse than both). */
    private suspend fun landedOnIsland(body: String): Boolean = runCatching {
        api.myReports().any { it.reason?.trim() == body.trim() }
    }.getOrDefault(false)

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

    /** Toggle per-conversation screen-secure mode for a 1:1 chat: set MY wish
     *  locally and propagate it to the peer so BOTH sides enforce it (iOS
     *  parity). A screenshot by the peer arrives as a system notice.
     *
     *  Only my own bit moves here. The peer's wish lives in its own slot
     *  ([LocalStores.setThreadSecureByPeer]) and the alerts are armed while
     *  either bit is set, so switching this off does NOT put out an alert the
     *  peer turned on: the chat keeps notifying, and the menu keeps its tick,
     *  until they drop it too.
     *
     *  ⚠⚠ NOTHING GOES OUT UNLESS MY OWN BIT ACTUALLY MOVES, and that guard is
     *  load-bearing rather than tidy. The menu row toggles the OR, so a tap in
     *  a chat the PEER armed and I never did arrives here as `on = false` over
     *  a bit that is already false. Sending that "off" would be retracting a
     *  request I never made, and every client that has not yet split the two
     *  slots (all of iOS, Android before this build) still reads an inbound
     *  `secureScreen(false)` as "clear my flag", so the tap would silently
     *  DISARM them while this side kept the tick and told the user the alerts
     *  had survived. No bit of mine moved, so there is nothing to say. */
    fun setChatSecure(peerUin: Int, on: Boolean) {
        val thread = LocalStores.peerThread(peerUin)
        if (LocalStores.isThreadSecureByMe(thread) == on) return
        LocalStores.setThreadSecure(thread, on)
        scope.launch { sendControl(peerUin, Envelope.secureScreen(on)) }
    }

    /** A screenshot was just taken on THIS device (detected by MainActivity on
     *  Android 14+). If a per-conversation secure 1:1 chat is open (notify-only
     *  mode, iOS parity), tell the peer so a "took a screenshot" notice appears
     *  on their side. No-op otherwise.
     *
     *  ★★★ #722. [LocalStores.isThreadSecure] is the OR of my wish and the
     *  peer's, so this stays armed for a thread the PEER asked to protect even
     *  after I switch my own copy off. Turning the alerts off on my phone
     *  cannot silence the notice I owe them; only they can drop it. */
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

    /** After a DECOY submit at the lock screen.
     *
     *  Two shapes, decided by the slot that just opened:
     *   - LEGACY (a vault written before the decoy got its own store): the slot
     *     names a roster account and carries the REAL dataKey, so we do exactly
     *     what we always did. These keep working untouched until the user
     *     passes the one-time migration screen — a duress PIN that stops
     *     working under coercion is worse than an out-of-date one.
     *   - MIGRATED: there is no roster account. We raise the decoy's own store
     *     under its own key, present its synthetic identity, and never connect. */
    suspend fun applyDecoyUnlock() {
        val legacyId = PanicPinService.consumeDecoyAccountId()
        if (legacyId != null) {
            AccountManager.enterDecoyMode(legacyId)                  // hide the real accounts FIRST
            if (AccountManager.activeId.value == legacyId) start()   // already active; just bring it up
            else switchToAccount(legacyId)                           // disconnect + rebind + start
            PanicPinService.completeUnlock()                         // only now reveal the (decoy) UI
            return
        }
        if (!PanicPinService.inDecoySession) return
        startDecoySession()
    }

    /** Raise the duress view: the decoy store, the decoy identity, and nothing
     *  else. Deliberately offline — the decoy has no server account to speak
     *  for it, and a socket opened here would be the real account's. Presence
     *  reads as connected (iOS parity) so the view doesn't advertise itself
     *  with a permanent "Connecting…". */
    private suspend fun startDecoySession() {
        calls.teardown()
        audioRooms.teardown()
        nearby.teardown()
        radio.teardown()
        socket.disconnect()
        decoySessionUin = PanicPinService.decoySessionUin() ?: DecoyStore.randomUin()
        decoySessionNickname = PanicPinService.decoySessionNickname() ?: DecoyStore.randomNickname()
        // Everything the real session had in memory goes, before a single
        // frame of the duress view is drawn.
        _contacts.value = emptyList()
        _pending.value = emptyList()
        _outgoing.value = emptyList()
        _messages.value = emptyMap()
        _groups.value = emptyList()
        _groupMessages.value = emptyMap()
        _devices.value = null
        _ownAvatar.value = null
        backupHomes.value = emptyList()
        activeRandomPeer = null
        activeRandomPairId = null
        _randomMessages.value = emptyList()
        _random.value = RandomState.Idle
        _typingFrom.value = null
        activeThread = null
        // An in-app banner is a real contact's name and a line of their message
        // sitting in a flow the lock does not clear; it would be drawn over the
        // duress view the moment it appears.
        _banner.value = null
        _dbLocked.value = false
        // ⚠ AND THE NOTIFICATIONS ALREADY IN THE SHADE. `PushEnvelope.open`
        // refusing under duress only covers wakes that arrive from now on; the
        // ones delivered BEFORE the phone changed hands are still sitting there
        // with real names and real previews, one swipe down away, without the
        // app being touched at all.
        app.rcq.android.push.Push.clearDeliveredMessages(appCtx)
        // Server capabilities are whatever the REAL session's /server/info
        // answered — a decoy never asks anyone. Left true, the UIN shop and
        // "my numbers" rows advertise a storefront to an identity that has no
        // island, and both screens then hang on a request the DuressGate refuses.
        _uinShopEnabled.value = false
        // The real session's chosen status (Away, Busy, an "Invisible") carried
        // straight into the duress view, where it is a statement about the real
        // user and not about the account on screen. iOS resets it the same way.
        _status.value = UserStatus.ONLINE
        // Pending cross-island requests are the one roster-shaped list that is
        // NOT rebuilt on entry: it is a flow filled by the real session and
        // [tearDownForLock] deliberately keeps the chat list across a lock, so
        // locking a live session and unlocking with the duress PIN handed the
        // coercer a "cross-island requests" section full of real people, name
        // and uin@host and all. Emptied here, and [refreshCiRequests] refuses
        // to refill it while the duress view is up.
        ciRequests.value = emptyList()
        // Unread counts, mutes, the roster cache, cross-island contacts, the
        // islands we hold guest registrations on and the profile-view tally are
        // all per-account prefs OUTSIDE SQLCipher. Point every one of them at
        // the decoy's own namespace (empty) so the duress session never writes a
        // synthetic uin into the real account's slots — and, the part that
        // matters under coercion, never reads the real account's out.
        bindPerAccountStores(DecoyStore.STORE_ID)
        // Nothing from the roster may be shown: every entry in it is a real
        // account, and the decoy is not one of them.
        AccountManager.enterDecoySession()
        withContext(Dispatchers.IO) {
            runCatching {
                bindDb(DecoyStore.STORE_ID)
                loadMessagesFromDb()
                _contacts.value = DecoyStore.contacts(db)
            }.onFailure {
                _dbLocked.value = true
                android.util.Log.e("RCQ", "decoy history unavailable", it)
            }
        }
        // ⚠⚠ The push socket is the one connection the DuressGate never saw.
        // The gate stands in front of HTTP requests; this is a foreground
        // SERVICE holding its own long-lived WebSocket to push.rcq.app, started
        // by the distributor and living outside every code path above. So a
        // duress session that presents itself as an ordinary, quiet phone kept a
        // live connection to our infrastructure the whole time — and kept
        // receiving wakes for the REAL account, whose notifications the decoy
        // then had to swallow. Stopped on entry; `ensureRunning` refuses to
        // bring it back while the gate is up.
        runCatching { app.rcq.android.push.embedded.EmbeddedDistributor.stop(appCtx) }
        // start() must never run for this session: it would read the REAL
        // account's uin + token out of `store` and connect with them.
        started = true
        everConnected = false
        _connected.value = true
        PanicPinService.completeUnlock()
    }

    /** The duress-session half of [burnAccount]. Empties the seeded history and
     *  roster and leaves the decoy sitting on a blank account.
     *
     *  Deliberately does NOT remove the decoy PIN from the vault: rewriting a
     *  slot needs the real slot key, which a decoy session does not hold (by
     *  design — see [PanicPinService.changeDecoyPin]). The PIN keeps working and
     *  keeps opening an empty account, which is what a burnt account looks like.
     *
     *  Also clears the decoy namespace's own plaintext stores, so the next
     *  duress unlock does not inherit unread counts or mutes from this one. */
    private suspend fun burnDecoySession() = withContext(Dispatchers.IO) {
        runCatching { if (::db.isInitialized) db.close() }
        DecoyStore.destroy(appCtx)
        wipeDecoyNamespaceStores()
        // Re-open the (now absent) store so the session keeps a live, empty
        // handle rather than one pointing at a deleted file.
        runCatching { bindDb(DecoyStore.STORE_ID) }
        _messages.value = emptyMap()
        _groupMessages.value = emptyMap()
        _contacts.value = emptyList()
        _groups.value = emptyList()
    }

    /** Wipe local message history (both 1:1 and group threads) without
     *  touching the account. Mirrors iOS "Clear history". */
    fun clearHistory() {
        db.wipe()
        _messages.value = emptyMap()
        _groupMessages.value = emptyMap()
    }

    /** Erase ONE conversation from this device: the stored rows, the in-memory
     *  copy the chat renders from, and its unread badge.
     *
     *  Local only, and deliberately so. "Delete for everyone" already exists
     *  per message and needs the other side to be reachable; this is the "get
     *  it off my phone" action, which must work with no network and cannot
     *  promise anything about the other device.
     *
     *  Reported by vss: removing a chat from the main screen dropped the
     *  roster entry and kept every message, so re-adding the person brought
     *  the whole conversation back and "delete" had quietly meant "hide". */
    fun clearPeerThread(uin: Int) {
        db.deletePeerThread(uin)
        _messages.value = _messages.value - uin
        LocalStores.clearUnread(LocalStores.peerThread(uin))
    }

    fun clearGroupThread(groupId: Int) {
        db.deleteGroupThread(groupId)
        _groupMessages.value = _groupMessages.value - groupId
        LocalStores.clearUnread(LocalStores.groupThread(groupId))
    }

    /** Publish own presence status. Optimistic local update, soft-fail
     *  on the network call. */
    suspend fun setStatus(status: UserStatus) {
        _status.value = status
        runCatching { api.setStatus(status.wire) }
    }

    // ── contact moderation ───────────────────────────────────────────

    /** Toggle block. The LOCAL blocked set is the source of truth (it works for
     *  non-contacts / strangers and drives ingest filtering + the Blocked list,
     *  since sealed sender means the server can't filter for us). The server
     *  call is best-effort: /contacts/{uin}/block toggles the contact `blocked`
     *  flag for real contacts and 404s (harmlessly swallowed) for strangers. */
    suspend fun toggleBlock(uin: Int) {
        LocalStores.setBlocked(uin, !LocalStores.isBlocked(uin))
        runCatching { api.blockContact(uin) }
        runCatching { refreshContacts() }
    }

    /** Mutual remove + local silent-drop of future sealed messages. A
     *  cross-island contact lives only in CrossIslandStore (the own island has
     *  no roster row to DELETE) — drop it there, or refreshContacts would
     *  merge it right back (beta report #207). */
    suspend fun removeContact(uin: Int, alsoDeleteMessages: Boolean = false) {
        LocalStores.addRemoved(uin)
        val ci = CrossIslandStore.findByUin(uin)
        if (ci != null) CrossIslandStore.remove(ci.uin, ci.host)
        else runCatching { api.removeContact(uin) }
        // Take them out of whatever section they were filed in. ⚠ This is the
        // ONLY pruning there is: nothing prunes because a chat failed to
        // resolve while rendering. ⚠⚠ And it runs whether or not they WERE
        // filed: see [SectionsVault.forgetMember] on why the timing of this
        // write is itself the leak.
        forgetSectionMember(app.rcq.android.data.Sections.peerKey(uin, ci?.host))
        // Opt-in, because removing a contact and erasing what they wrote you
        // are different intentions and only the user knows which one this is.
        // The caller asks; nothing here decides on their behalf.
        if (alsoDeleteMessages) clearPeerThread(uin)
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

    /** [loadPeerProfile] with "nobody holds this number" told apart from "the
     *  island did not answer", for a screen that must not invent a person.
     *  See [UinLookup] — same three answers, the fuller card. */
    suspend fun loadPeerProfileDetailed(uin: Int): Pair<RcqApi.MeProfile?, Boolean> =
        runCatching { api.getMe(uin) }.fold(
            onSuccess = { it to false },
            onFailure = { null to (it.message?.startsWith("HTTP 404") == true) },
        )

    /** Fetch the admin-posted news feed (GET /news); null on failure. */
    suspend fun loadNews(): RcqApi.NewsFeed? = runCatching { api.news() }.getOrNull()

    // ── news badge ────────────────────────────────────────────────────────
    // The server's `latest_id` is the authoritative "newest post"; what the
    // user has actually looked at is ours to remember. iOS has shown a red dot
    // on the 3-dot menu off exactly this pair since the feed shipped, and
    // Android just did not, so an Android user only found a post by opening
    // the menu and the screen behind it on the off chance.
    //
    // Not per-account: the feed is the island's, the same for whoever is
    // signed in, and a per-account pointer would re-announce a read post on
    // every account switch.
    private val newsPrefs by lazy { appCtx.getSharedPreferences("rcq_news", Context.MODE_PRIVATE) }

    private val _newsUnread = MutableStateFlow(0)
    /** How many posts are newer than the last one this device has seen. Zero
     *  means no dot. */
    val newsUnread: StateFlow<Int> = _newsUnread.asStateFlow()

    /** Refresh the badge. Cheap and silent: a failed fetch leaves the previous
     *  count alone rather than clearing a dot the user has not acted on. */
    suspend fun refreshNewsBadge() {
        val feed = loadNews() ?: return
        // First run after this feature lands: everything already published is
        // treated as read. These people have been using the app for months
        // without a badge, so they did not MISS 46 posts — announcing the whole
        // archive would be both alarming and wrong. From here on, only what is
        // posted next counts.
        if (!newsPrefs.contains(K_NEWS_SEEN)) {
            newsPrefs.edit().putInt(K_NEWS_SEEN, feed.latest_id).apply()
            _newsUnread.value = 0
            return
        }
        val seen = newsPrefs.getInt(K_NEWS_SEEN, 0)
        _newsUnread.value = feed.items.count { it.id > seen }
            .coerceAtLeast(if (feed.latest_id > seen) 1 else 0)
    }

    /** Called when the news screen opens, so the dot clears on first view. */
    fun markNewsSeen(latestId: Int) {
        if (latestId <= newsPrefs.getInt(K_NEWS_SEEN, 0)) return
        newsPrefs.edit().putInt(K_NEWS_SEEN, latestId).apply()
        _newsUnread.value = 0
    }

    /** My own reports plus any answer written to them. Soft-fails to null so a
     *  dead network shows the empty state rather than a crash. */
    suspend fun loadMyReports(): List<RcqApi.MyReport>? =
        runCatching { api.myReports() }.getOrNull()

    /** The outcome of taking one of my own reports off my own list. Three
     *  cases, because they are three different sentences: [Refused] is the
     *  server holding an OPEN report about ANOTHER user until there is a
     *  verdict, [Removed] is the row leaving the list (including the case where
     *  it had already left, from another device or from the operator's side),
     *  and only [Failed] is worth retrying. A flat boolean turned every dead
     *  network into "still under review", which is a statement about the
     *  report's status and was simply untrue. */
    sealed class ReportRemove {
        object Removed : ReportRemove()
        object Refused : ReportRemove()
        object Failed : ReportRemove()
    }

    /** Take one of my own reports off my own list. [ReportRemove.Refused] when
     *  the server said no: an open report about ANOTHER user waits for a
     *  verdict, because the reporter is a party to that case and the thread is
     *  the operator's only way to ask them anything.
     *
     *  ⚠ Server-side this is a HIDE, not a delete. The row stays and keeps
     *  counting: `hof_stats` reads the reports table live, so erasing the
     *  reports that came back dismissed used to raise the Hall of Fame ratio.
     *  Nothing in the UI may promise the report is destroyed. */
    suspend fun deleteMyReport(id: Int): ReportRemove =
        runCatching { api.deleteMyReport(id) }.fold(
            onSuccess = { ReportRemove.Removed },
            onFailure = {
                val msg = it.message.orEmpty()
                when {
                    msg.startsWith("HTTP 409") -> ReportRemove.Refused
                    // Already gone: another device removed it, or the operator
                    // did. Dropping the row is the honest answer, not an error
                    // and certainly not "still under review".
                    msg.startsWith("HTTP 404") -> ReportRemove.Removed
                    else -> ReportRemove.Failed
                }
            },
        )

    /** The outcome of rewriting one's own report. Three refusals, because they
     *  need three different sentences: [Locked] is "somebody already answered
     *  this, the text they answered is frozen", [NotEditable] is a crash dump
     *  (never editable by hand), and only [Failed] is worth retrying. */
    sealed class ReportEdit {
        data class Saved(val report: RcqApi.MyReport) : ReportEdit()
        object Locked : ReportEdit()
        object NotEditable : ReportEdit()
        object Failed : ReportEdit()
    }

    /** Fix the typo, the missing version number, or the wrong screen name in a
     *  report nobody has answered yet. Without it the only correction was a
     *  second report saying "sorry, I meant", which is how the queue collected
     *  the same issue three times from one person. */
    suspend fun editMyReport(id: Int, reason: String): ReportEdit =
        runCatching { api.editMyReport(id, reason) }.fold(
            onSuccess = { ReportEdit.Saved(it) },
            onFailure = {
                val msg = it.message.orEmpty()
                when {
                    msg.startsWith("HTTP 409") -> ReportEdit.Locked
                    msg.startsWith("HTTP 400") -> ReportEdit.NotEditable
                    else -> ReportEdit.Failed
                }
            },
        )

    /** The outcome of writing back on one's own report. [Closed] is not a
     *  failure, it is an answer: the operator finished the ticket, so the
     *  screen says that instead of "try again" forever. */
    sealed class ReportReply {
        data class Sent(val turn: RcqApi.ReportTurn) : ReportReply()
        object Closed : ReportReply()
        object Failed : ReportReply()
    }

    /** Add a turn to one of my own reports. This is the half that was missing:
     *  an operator could answer, but the reporter could not hand over the log
     *  line they were asked for, so they filed a second report instead. */
    suspend fun addToMyReport(id: Int, body: String): ReportReply =
        runCatching { api.addToMyReport(id, body) }.fold(
            onSuccess = { ReportReply.Sent(it) },
            onFailure = {
                if (it.message?.startsWith("HTTP 409") == true) ReportReply.Closed
                else ReportReply.Failed
            },
        )

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
            val resp = sendSealedCopies(peer, encryptFor(peer, env)) {
                updateRandomState(env.id, DeliveryState.FAILED)
            }
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
            // Named, not left to the legacy route: /keys/{uin}/bundle is
            // deliberately 404 for an account with a linked install, and the
            // number would then be unavailable for exactly the people most
            // likely to want it. The pinned identity is the PRIMARY's either
            // way (that is the address it is stored under).
            runCatching { SignalSession.ensureSession(signalStores, api, uin, SealedSender.PRIMARY_DEVICE_ID) }
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
            sendSealedCopies(uin, encryptFor(uin, Envelope.visit(now)), envelopeType = "visit")
        }.onFailure { lastVisitAt.remove(uin) }
    }

    // ── groups ───────────────────────────────────────────────────────

    private fun mapGroup(g: RcqApi.GroupOut): RcqGroup =
        // Stage 6 phase 2: overlay the sealed identity when we hold the key.
        app.rcq.android.crypto.GroupState.overlay(
            mapGroupRaw(g),
            LocalStores.roomKey(g.id)?.second,
        )

    private fun mapGroupRaw(g: RcqApi.GroupOut): RcqGroup = RcqGroup(
        id = g.id,
        name = g.name ?: "Group ${g.id}",
        description = g.description,
        ownerUin = g.owner_uin,
        postPolicy = g.post_policy ?: "all",
        isClosed = g.is_closed,
        membersHidden = g.members_hidden,
        pinnedText = g.pinned_text,
        linksAllowed = g.links_allowed,
        inCatalog = g.in_catalog,
        stateBlob = g.state_blob,
        stateVer = g.state_ver,
        filesAllowed = g.files_allowed,
        slowmodeSec = g.slowmode_sec,
        minAccountAgeHours = g.min_account_age_hours,
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
                senderKeys = it.sender_keys,
                avatarMediaId = it.avatar_media_id,
                avatarMediaKey = it.avatar_media_key,
            )
        },
        createdAt = parseIso(g.created_at),
        // Older islands do not send it; the roster's own size is right there.
        memberCount = if (g.member_count > 0) g.member_count else g.members.size,
    )

    /** One `pkeyask` per contact per six hours, when they HAVE a picture and we
     *  hold no key for it.
     *
     *  ⚠⚠ The second half of the profile-key design, and it was missing here
     *  entirely: the type was declared, serialised, parsed and answered, and
     *  nothing on this client ever SENT one. The key is handed out once, to the
     *  roster as it stood when the picture was set, so without this a contact
     *  added afterwards - or any reinstall - saw a lettered tile forever, with
     *  no way back. The web has had both halves since the feature shipped
     *  (profile-key.ts askForProfileKey).
     *
     *  ★ Asked exactly where the fallback is resolved, not on the eight screens
     *  that draw a face, for the same reason the fallback lives there.
     *
     *  ⚠ No key and no picture must stay INDISTINGUISHABLE on screen; this asks
     *  only when a picture is named, so it never becomes an oracle for "am I
     *  allowed to see this". A peer who is not entitled simply never answers,
     *  and the throttle keeps a roster of faces we cannot open from turning
     *  into a poll. */
    private fun maybeAskProfileKey(uin: Int, identityKey: String, host: String?) {
        if (identityKey.isBlank()) return
        // Never at ourselves, and never at a CROSS-ISLAND row: that number
        // belongs to their island, so asking it here reaches whoever holds it
        // on ours. Their key rides the §5e profile envelope instead.
        if (host != null || uin == store.uin) return
        // Never at somebody we blocked: the ask is an outbound message, and a
        // blocked number must not receive one because a picture failed to open.
        if (LocalStores.isBlocked(uin)) return
        val now = System.currentTimeMillis()
        // ⚠⚠ IN MEMORY, deliberately. The first version of this kept the
        // throttle in SharedPreferences under "u<uin>" keys, which is a
        // plaintext list of contact numbers, device-wide, outside any account
        // namespace, and swept by nothing - not a burn, not the duress
        // teardown. In this project that is precisely the shape of thing being
        // taken off disk everywhere else, and it was bought for nothing: the
        // throttle exists to stop a roster of unopenable faces turning into a
        // poll WITHIN a session, and a restart is already rate-limited by
        // being a restart. Cleared on rebind with the rest of the per-account
        // state.
        if (now - (askedProfileKeyAt[uin] ?: 0L) < 6L * 3600_000L) return
        askedProfileKeyAt[uin] = now
        val ep = epochNow()
        scope.launch {
            if (!stillOn(ep)) return@launch
            runCatching {
                sendSealedCopies(uin, encryptFor(uin, Envelope.PKeyAsk), envelopeType = "sknack")
            }
        }
    }

    /** One `gsknack` per room per six hours when its sealed blob exists and
     *  our key (if any) does not open it. The reply lands as a gskey. */
    private fun maybeAskRoomKey(g: RcqGroup) {
        val blob = g.stateBlob ?: return
        val held = LocalStores.roomKey(g.id)
        if (held != null && app.rcq.android.crypto.GroupState.open(blob, held.second) != null) return
        val prefs = appCtx.getSharedPreferences("rcq_gsknack", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last: Long = prefs.getLong("g${g.id}", 0L)
        if (now - last < 6L * 3600_000L) return
        prefs.edit().putLong("g${g.id}", now).apply()
        scope.launch {
            runCatching {
                ensureRoster(g.id)
                val owner = groups.value.firstOrNull { it.id == g.id }
                    ?.members?.firstOrNull { it.uin == g.ownerUin } ?: return@runCatching
                if (owner.identityKey.isBlank()) return@runCatching
                sendSealedCopies(
                    owner.uin,
                    encryptFor(owner.uin, Envelope.GsKnack(g.id)),
                    envelopeType = "sknack",
                )
            }
        }
    }

    private suspend fun refreshGroups() {
        // Without the roster: the chat list wants a name, a picture and a count,
        // and the roster is the expensive half — every member with two base64
        // keys. It is fetched per group, on demand, by `ensureRoster`.
        val known = _groups.value.associateBy { it.id }
        // Held across the whole function: room names end up ENCRYPTED IN
        // SecureStore for the headless push wake, and that write is put-only
        // (SecureStore.cacheGroupNames merges), so one poisoned refresh leaves
        // account A's room names in account B's slot for good, surfacing in the
        // text of B's notifications. See [accountEpoch].
        val ep = epochNow()
        val own = api.groups(withMembers = false).map(::mapGroup).map { g ->
            // Keep a roster we already paid for rather than dropping it.
            if (g.members.isEmpty()) g.copy(members = known[g.id]?.members ?: emptyList()) else g
        }
        if (!stillOn(ep)) return
        // Stage 6 phase 2: a blob we cannot open earns one throttled ask
        // toward the owner (six hours per room, mirrored from the web).
        for (g in own) maybeAskRoomKey(g)
        // §5c: groups hosted on OTHER islands, fetched with that island's creds;
        // ids rewritten to the local alias at this boundary. The host can be a
        // visited/guest island OR one of our BACKUP islands (multihome) — a group
        // on a backup island MUST be listed here too or it shows up message-only
        // with no name/roster. Dedup hosts (one can be both) + never let a
        // failing island block the own list (the drain refreshes creds; next
        // refresh recovers).
        val me = store.uin
        val foreignHosts = (
            VisitedIslandsStore.list().map { it.host to it.jwt } +
                (me?.let { MultihomeStore.list(it).map { h -> h.host to h.jwt } } ?: emptyList())
            ).filter { !it.first.equals(serverHost(), ignoreCase = true) }
            .distinctBy { it.first.lowercase() }
        val foreign = foreignHosts.flatMap { (host, jwt) ->
            runCatching {
                val guest = RcqApi("https://$host").apply { setToken(jwt) }
                guest.groups().map { mapGroup(it).copy(id = VisitedIslandsStore.aliasFor(host, it.id), host = host) }
            }.getOrElse { emptyList() }
        }
        if (!stillOn(ep)) return
        _groups.value = (own + foreign).distinctBy { it.id }.sortedByDescending { it.createdAt ?: 0L }
        // Persist the roster so groups are reachable offline (report #7).
        runCatching { LocalStores.setCachedGroupsJson(profileGson.toJson(_groups.value)) }
        // And the names alone, encrypted, where a headless push wake can reach
        // them: the island stopped putting the room name in the payload on
        // 22.08 because it was reaching Apple and Cloudflare in the clear.
        runCatching { store.cacheGroupNames(_groups.value.associate { it.id to it.name }) }
    }

    /** Upsert a group from a WS event. If the embedded roster no longer
     *  contains us (we left / were removed), drop it locally instead —
     *  mirrors the iOS GroupService.upsert rule. */
    private fun upsertGroup(g: RcqGroup) {
        // Foreign group (§5c): in ITS roster we are our per-island uin (a guest
        // entry OR our backup-island uin if the group is hosted on a backup).
        val me = if (g.host != null) foreignCreds(g.host)?.first else store.uin
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

    /** Move the crown on a locally-held group, for the COMPACT
     *  `group_membership_changed` a big room gets (id + `owner_uin`, no
     *  roster). The number is the half that re-gates every screen, and it is
     *  right there in the frame.
     *
     *  The two role rows are normalised with it rather than left for the next
     *  `/groups`: they are already in memory, the list is a copy either way,
     *  and a roster carrying two `role == "owner"` rows badges two owners.
     *  Nothing else is refetched here — the event exists precisely because a
     *  room this size is too expensive to move, and re-fetching the roster
     *  would spend exactly what the compact form saved. */
    private fun applyOwnerLocally(groupId: Int, ownerUin: Int) {
        _groups.value = _groups.value.map { g ->
            if (g.id != groupId || g.ownerUin == ownerUin) return@map g
            g.copy(
                ownerUin = ownerUin,
                members = g.members.map { m ->
                    when (m.uin) {
                        // A capability is a grant FROM the owner, and the owner
                        // is somebody else now: the island cleared both lists.
                        ownerUin -> m.copy(role = "owner", permissions = emptyList())
                        g.ownerUin -> m.copy(role = "member", permissions = emptyList())
                        else -> m
                    }
                },
            )
        }
    }

    /** Optimistically swap a group's pinned text in the local state so the chat
     *  banner updates INSTANTLY when pinning from a message, before patchGroup
     *  round-trips. The PATCH response reconciles it. Blank clears the pin. */
    fun applyPinnedTextLocally(groupId: Int, text: String) {
        val trimmed = text.trim().ifBlank { null }
        _groups.value = _groups.value.map { if (it.id == groupId) it.copy(pinnedText = trimmed) else it }
    }

    // ── Cross-island groups (§5c): guest context ─────────────────────

    /** Resolution of (api client, server-side id, island, my uin THERE) for a
     *  group op. Own-island groups pass through; a NEGATIVE id is the local
     *  alias of a foreign group → the guest client + remote id. */
    private data class GroupCtx(val api: RcqApi, val gid: Int, val host: String?, val myUin: Int)

    /** Creds for a foreign host: a visited/guest island OR one of our BACKUP
     *  islands (multihome). A cross-island group can be hosted on EITHER — both
     *  stores hold this identity's (uin, jwt) for that host. Without the backup
     *  fallback, a group on your backup island has no roster/name and its sends
     *  misroute to your own island (the "Группа / 0 участников / не дошло" bug). */
    private fun foreignCreds(host: String): Pair<Int, String>? {
        VisitedIslandsStore.get(host)?.let { return it.uin to it.jwt }
        val me = store.uin ?: return null
        return MultihomeStore.list(me).firstOrNull { it.host.equals(host, ignoreCase = true) }
            ?.let { it.uin to it.jwt }
    }

    private fun groupCtx(groupId: Int): GroupCtx {
        if (groupId >= 0) return GroupCtx(api, groupId, null, store.uin ?: 0)
        val ref = VisitedIslandsStore.refByAlias(groupId)
        val creds = ref?.let { foreignCreds(it.host) }
        if (ref == null || creds == null) return GroupCtx(api, groupId, null, store.uin ?: 0)
        val guest = RcqApi("https://${ref.host}").apply { setToken(creds.second) }
        return GroupCtx(guest, ref.remoteId, ref.host, creds.first)
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
        // Stage 6 phase 2: a room we hold the key for renders its SEALED
        // identity over the open columns; everyone else sees the columns.
        app.rcq.android.crypto.GroupState.overlay(
            mapGroupCtxRaw(ctx, g),
            LocalStores.roomKey(g.id)?.second,
        )

    private fun mapGroupCtxRaw(ctx: GroupCtx, g: RcqApi.GroupOut): RcqGroup =
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
        val creds = Multihome.recoverOn(h, signingPriv(), signingPub()) ?: run {
            val api = RcqApi("https://$h")
            val skB64 = Base64.encodeToString(signingPub(), Base64.NO_WRAP)
            val challenge = runCatching { api.registerChallenge(skB64).challenge }.getOrNull()
            api.register(
                RcqApi.RegisterRequest(
                    nickname = store.nickname ?: "user-$uin",
                    identity_key = Base64.encodeToString(identityPub(), Base64.NO_WRAP),
                    signing_key = skB64,
                    challenge = challenge,
                    signature = challenge?.let {
                        app.rcq.android.crypto.RecoveryPhrase.signChallenge(signingPriv(), it)
                    },
                ),
            )
        }
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

    /** §5c invite-card preview for a group on another island. The invite LINK is
     *  the capability, so we read the foreign island's PUBLIC card (name/avatar/
     *  member count) even when the island isn't visited yet — the server's
     *  /groups/{id}/preview is optional-auth. Sends the guest token if we have
     *  one (visited), otherwise unauthenticated; rides the proxy when onion is on.
     *  So a received cross-island invite shows the real group, not a blank card. */
    suspend fun previewForeignGroup(host: String, remoteId: Int): RcqApi.GroupPreviewOut? {
        val v = VisitedIslandsStore.get(host)
        return runCatching {
            RcqApi("https://$host").apply { v?.let { setToken(it.jwt) } }.previewGroup(remoteId)
        }.getOrNull()
    }

    /** §5c: media in a group lives on the GROUP's island — upload there (the
     *  guest client for foreign groups; own api otherwise). */
    // Same rule as the 1:1 upload below: a duress session puts no blob on any
    // island. Unreachable while a seeded decoy has no groups, and here for the
    // same reason its sibling in `fanOutGroup` is.
    private suspend fun uploadBlobForGroup(groupId: Int, blob: ByteArray): RcqApi.UploadResponse =
        if (app.rcq.android.security.DuressGate.isActive)
            RcqApi.UploadResponse(java.util.UUID.randomUUID().toString().replace("-", ""), blob.size)
        else groupCtx(groupId).api.uploadBlob(blob, ::reportUpload)

    fun group(id: Int): RcqGroup? = _groups.value.firstOrNull { it.id == id }

    /** The group WITH its roster, fetching it if the list did not carry one.
     *
     *  ⚠ Anything that encrypts per recipient must go through this and not
     *  through [group]. The list is fetched without rosters now, so a cached
     *  group can legitimately have an empty member list, and sending against
     *  that would deliver to nobody while looking like it worked. A foreign
     *  group is left alone: its roster comes from its own island.
     *
     *  Fetched once per group per app run even when a roster is already here,
     *  because the one that is here came off the DISK, from whatever version
     *  of this app wrote it. A cached roster written before member pictures
     *  existed has no pictures in it, and "only refetch when empty" would
     *  never have gone back for them — the list no longer carries a roster to
     *  overwrite it with. Once per run also picks up who joined and who
     *  renamed themselves, which the old every-poll roster used to do. */
    private val rosterFetched = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** [refresh] fetches even when a roster is already held.
     *
     *  ⚠ A roster carries PRESENCE, and that field is as old as the fetch, so
     *  one held for an hour reports a room full of offline people. Anything
     *  that puts a status in front of somebody asks for it fresh; anything
     *  that only needs keys or names does not, because the roster of a big
     *  room is expensive (the beta group is 2200 people). */
    suspend fun ensureRoster(id: Int, refresh: Boolean = false): RcqGroup? {
        val cached = group(id) ?: return null
        if (cached.host != null) return cached
        if (!refresh && cached.members.isNotEmpty() && id in rosterFetched) return cached
        val full = runCatching { mapGroup(api.groupInfo(id)) }.getOrNull() ?: return cached
        rosterFetched.add(id)
        // ⚠ `ownerUin` comes from the ANSWER, not from the cached row. The
        // roster and the crown are one fact: a handover we missed (socket down,
        // an island too old to put the owner in the compact frame) is on this
        // fetch, and keeping the cached number here would have this very screen
        // draw the roles it just fetched under the owner it already had.
        val merged = cached.copy(
            members = full.members, memberCount = full.memberCount, ownerUin = full.ownerUin,
        )
        _groups.value = _groups.value.map { if (it.id == id) merged else it }
        // #650: the fetch/parse above already runs on IO inside RcqApi, but this
        // disk snapshot serializes EVERY group, and a roster can carry 2000+
        // members with two base64 keys each. Callers sit on the main thread
        // (the info and chat screens' LaunchedEffect), so keep the megabytes of
        // Gson work off it; the StateFlow above is published either way.
        withContext(Dispatchers.Default) {
            runCatching { LocalStores.setCachedGroupsJson(profileGson.toJson(_groups.value)) }
        }
        return merged
    }
    fun groupName(id: Int): String = group(id)?.name ?: "Group $id"

    /// ⚠⚠ Takes CONTACTS, not uins, and splits them by island.
    ///
    /// A cross-island contact's uin is their number on THEIR island. Sending it
    /// to ours as a member meant one of two things, and both were wrong: our
    /// island answered "no such user" and the screen reported the invitee had
    /// switched invitations off (a setting we cannot see by construction), or —
    /// worse — a DIFFERENT person happened to hold that number here and got
    /// added to the group instead. Same shape as the cross-island call that
    /// rang a stranger with the same number.
    ///
    /// Adding them properly already existed for an EXISTING group
    /// ([addCrossIslandGroupMember]: mint a shadow uin for their KEY on the
    /// group's island, add that, then send them the invite link). It was simply
    /// never wired into creation. Now it is: the group is created with the
    /// same-island members, and each cross-island one is added afterwards
    /// through the path that knows what it is doing.
    suspend fun createGroup(name: String, members: List<Contact>): RcqGroup {
        val g = mapGroup(api.createGroup(name, members.filter { it.host == null }.map { it.uin }))
        upsertGroup(g)
        roomJoined()
        for (m in members.filter { it.host != null }) {
            val card = CrossIslandStore.findByUin(m.uin) ?: continue
            runCatching { addCrossIslandGroupMember(g.id, card) }
        }
        return g
    }

    /** Returns null on success, else a localized reason. The server returns 403
     *  for THREE distinct reasons (the owner blocked the user / the invitee only
     *  accepts invites from contacts / the invitee accepts no invites); inspect
     *  the 403 body instead of swallowing it or collapsing the reasons. */
    suspend fun addGroupMember(id: Int, uin: Int): String? {
        val ctx = groupCtx(id)
        return runCatching {
            val g = ctx.api.addGroupMember(ctx.gid, uin)
            upsertGroup(mapGroupCtx(ctx, g))
            // Stage 6 phase 2: the inviter hands the new member the room state
            // key at the moment of adding (design doc, road 3). Only for a
            // LOCAL room whose key this device holds; best-effort - a missed
            // hand-off is one gsknack away.
            if (ctx.host == null) {
                LocalStores.roomKey(ctx.gid)?.let { k ->
                    scope.launch {
                        runCatching {
                            sendSealedCopies(
                                uin,
                                encryptFor(uin, Envelope.GsKey(ctx.gid, k.first, k.second)),
                                envelopeType = "skdm",
                            )
                        }
                    }
                }
            }
            null
        }.getOrElse { addMemberReason(it.message) }
    }

    /** Map the IOException message ("HTTP <code>: <body>" from RcqApi.execute) to
     *  a localized string by matching the distinct 403 detail substrings the
     *  groups router emits. Non-403 -> generic add-failed. */
    fun addMemberReason(message: String?): String {
        val m = message ?: ""
        val res = when {
            m.contains("the group owner has blocked this user") -> R.string.gi_add_blocked
            m.contains("only accepts group invites from their contacts") -> R.string.gi_add_contacts_only
            m.contains("does not accept group invites") -> R.string.gi_add_nobody
            else -> R.string.gi_add_failed
        }
        return appCtx.getString(res)
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

    /** Owner: hand the whole group to [toUin] (founder item 23). Owner only,
     *  and one way from here. Returns null when the island took it, else the
     *  sentence to show.
     *
     *  ⚠ The answer replaces the WHOLE local group, not just [RcqGroup.ownerUin].
     *  Both roles moved with it and both `permissions` lists were cleared, so
     *  patching the single field would leave our own row still drawn with
     *  rights it no longer has, and the client-enforced `delete` cap still
     *  honouring them.
     *
     *  ⚠ Own-island only: see [RcqApi.transferGroupOwner]. A cross-island
     *  group's id here is a local alias, and the island the group lives on has
     *  no account for a member of ours to become its owner. */
    suspend fun transferGroupOwner(id: Int, toUin: Int): String? {
        val ctx = groupCtx(id)
        if (ctx.host != null) return appCtx.getString(R.string.gi_transfer_err_failed)
        return runCatching {
            upsertGroup(mapGroupCtx(ctx, ctx.api.transferGroupOwner(ctx.gid, toUin)))
            null
        }.getOrElse { transferOwnerReason(it) }
    }

    /** One sentence for each way the island can refuse a handover. Every code
     *  is a fact about the TARGET or about us rather than a network hiccup, so
     *  each gets its own line instead of a shared "could not". The status is
     *  consulted only for a 429 that reached us through something that ate the
     *  body (a proxy, an older limiter): the ceiling is still what happened. */
    fun transferOwnerReason(e: Throwable): String {
        val refusal = RcqApi.refusalOf(e.message)
        val res = when (refusal.code) {
            "owner_only" -> R.string.gi_transfer_err_owner_only
            "already_owner" -> R.string.gi_transfer_err_already_owner
            "not_a_member" -> R.string.gi_transfer_err_not_a_member
            "no_such_user" -> R.string.gi_transfer_err_no_such_user
            "target_suspended" -> R.string.gi_transfer_err_target_suspended
            "rate_limited" -> return rateLimitedSentence(refusal.retryAfter)
            else -> if (refusal.status == 429) return rateLimitedSentence(refusal.retryAfter)
                else R.string.gi_transfer_err_failed
        }
        return appCtx.getString(res)
    }

    private fun rateLimitedSentence(retryAfter: Int?): String =
        if (retryAfter != null && retryAfter > 0)
            appCtx.getString(R.string.gi_transfer_err_rate_limited_in, retryAfter)
        else appCtx.getString(R.string.gi_transfer_err_rate_limited)

    /** Fetch a group invite-card snapshot (no membership needed). */
    suspend fun previewGroup(id: Int): RcqApi.GroupPreviewOut? =
        runCatching { api.previewGroup(id) }.getOrNull()

    /** Join a group from a shared invite. Already-member is a no-op that just
     *  returns the existing group (the caller jumps into the chat). Returns
     *  null on failure (e.g. a closed group). */
    suspend fun joinGroup(id: Int): RcqGroup? {
        group(id)?.let { return it }
        return runCatching { mapGroup(api.joinGroup(id)).also { upsertGroup(it); roomJoined() } }.getOrNull()
    }

    suspend fun leaveGroup(id: Int) {
        val ctx = groupCtx(id)
        if (ctx.myUin == 0) return
        val key = sectionKeyForGroupId(id)
        runCatching { ctx.api.leaveGroup(ctx.gid, ctx.myUin) }
        _groups.value = _groups.value.filterNot { it.id == id }
        forgetSectionMember(key)
    }

    suspend fun deleteGroup(id: Int) {
        val ctx = groupCtx(id)
        val key = sectionKeyForGroupId(id)
        runCatching { ctx.api.deleteGroup(ctx.gid) }
        _groups.value = _groups.value.filterNot { it.id == id }
        forgetSectionMember(key)
    }

    /** The section member key for a group by its LOCAL id, read while the row
     *  is still in the roster. ⚠ A negative id is an alias this device made up;
     *  [app.rcq.android.data.SectionsVault.keyForGroup] is the edge that turns
     *  it back into (remoteId, host). */
    private fun sectionKeyForGroupId(id: Int): String? =
        _groups.value.firstOrNull { it.id == id }?.let { app.rcq.android.data.SectionsVault.keyForGroup(it) }

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

    /** Set or clear MY profile picture. Same shape as a group's: encrypt with
     *  a fresh per-blob key, upload, then hand the island the id and the key
     *  so it can pass them on to the people allowed to see it.
     *
     *  Passing null clears the picture: the island takes two blank strings as
     *  "remove", while leaving both fields out entirely means "do not touch",
     *  which is what every other profile patch does. */
    suspend fun setOwnAvatar(bytes: ByteArray?) {
        // FIRST, before a single network call. Reading it later is the exact
        // mistake [accountEpoch] warns about: taken after the upload, it is
        // already the NEW account's number and every check passes. Setting a
        // picture is three round trips (vault, blob, profile) and an upload
        // takes seconds on a phone, so this is a wide window, not a race.
        val ep = epochNow()
        if (bytes == null) {
            if (!stillOn(ep)) return
            api.updateMe(RcqApi.UpdateMeBody(avatar_media_id = "", avatar_media_key = ""))
            if (!stillOn(ep)) return
            _ownAvatar.value = null
            // §5e: a cleared picture is a profile change like any other — the
            // envelope names no picture and the far side drops the old one.
            broadcastProfileCrossIsland(pictureCleared = true)
            return
        }
        // My picture is sealed under MY profile key, not a fresh one per upload
        // (docs/profile-key-design.md). Two consequences, both deliberate:
        // the island is never told the key, so it cannot open the face it
        // stores; and changing the picture does NOT change the key, so every
        // contact who already holds it keeps seeing me without a second
        // fan-out. Vault-first, so a second install adopts what this one
        // published rather than minting a rival key.
        val keyB64 = ProfileKeyVault.ensureMyKey(api, store.identityPrivate ?: ByteArray(0))
            ?: Base64.encodeToString(MediaCrypto.newKey(), Base64.NO_WRAP)
        val key = Base64.decode(keyB64, Base64.NO_WRAP)
        val blob = MediaCrypto.seal(bytes, key)
        val upload = api.uploadBlob(blob)
        imageCache.put(upload.media_id, bytes)
        // Keep the SEALED bytes on disk too: §5e re-deposits this exact blob to
        // each cross-island contact's island, and pulling our own picture back
        // from our island every time to do it would be silly.
        runCatching { mediaDiskFile(upload.media_id).writeBytes(blob); trimMediaDiskCache() }
        // ⚠ The id ALONE. An id without a key also CLEARS whatever key the
        // island still holds for us, which is what retires the plaintext one
        // for an account that had set a picture before this shipped.
        // ⚠⚠ These two are the WORST of the unguarded lines, not the fan-out
        // below: `updateMe` PATCHes this picture onto whatever account the api
        // now serves, so account A's face becomes account B's on B's island,
        // sealed under a key B's contacts do not have. And `_ownAvatar` then
        // shows it to B as their own.
        if (!stillOn(ep)) return
        api.updateMe(RcqApi.UpdateMeBody(avatar_media_id = upload.media_id))
        if (!stillOn(ep)) return
        _ownAvatar.value = upload.media_id to keyB64
        // Hand the key to everyone entitled to it. Own scope, not the caller's:
        // backing out of Settings must not cost the roster their copy. A peer
        // we cannot reach today asks with `pkeyask` tomorrow.
        scope.launch {
            // ⚠⚠ One round trip PER CONTACT, with retries: this loop runs for
            // tens of seconds, and `scope` is never cancelled. Without the
            // epoch it keeps going after an account switch, and then
            // `encryptFor`/`sendSealedCopies` read the LIVE store and api - so
            // the island of account B receives a send-sealed for every number
            // in account A's ROSTER. On one island (which is allowed) that
            // hands the island the two accounts as one graph, and the peers
            // who hold those numbers there get account A's profile key filed
            // against account B, which breaks B's face for them. See
            // [accountEpoch].
            val roster = _contacts.value
            roster.forEachIndexed { i, c ->
                if (!stillOn(ep)) return@launch
                // The same filter pushHomeRecordToContacts uses, for the same
                // reasons.
                //
                // ⚠⚠ `c.host != null` is the one that matters most. The roster
                // has cross-island rows merged into it, and their number belongs
                // to THEIR island: sending to it here addresses whoever holds
                // that number on OURS. The outer seal for device 1 is unreadable
                // to them, but a second device of that stranger is addressed
                // with a bundle fetched from our island, so it opens the
                // envelope and reads our profile key. The real cross-island
                // contact gets id and key from depositProfileTo anyway, so this
                // send is wrong AND redundant.
                //
                // A blocked number is not entitled to the key to our face, and
                // handing it over is also an outbound message to somebody we
                // said we wanted nothing from.
                val skip = c.host != null ||
                    c.uin == store.uin ||
                    c.blocked ||
                    LocalStores.isBlocked(c.uin) ||
                    c.identityKey.isBlank()
                if (!skip) {
                    runCatching {
                        sendSealedCopies(
                            c.uin,
                            encryptFor(c.uin, Envelope.PKey(keyB64)),
                            envelopeType = "skdm",
                        )
                    }
                }
                if (i % 16 == 15) kotlinx.coroutines.yield()
            }
        }
        // §5e: push the new picture (blob + key) to the cross-island contacts.
        if (!stillOn(ep)) return
        broadcastProfileCrossIsland()
    }

    /** My own picture (id to key), so Settings can draw it without a round
     *  trip. Seeded from the profile load, updated by [setOwnAvatar]. */
    /// Who we already asked for a profile key, and when. In memory only, see
    /// [maybeAskProfileKey]. Cleared on rebind like every other per-account map.
    /// What the island said when it refused the last send, or null when the
    /// last send was fine. See [ingest] for why the send cannot simply throw.
    ///
    /// ★ A FLOW, not a return value, because there are eight send paths in the
    /// chat screen (text, photo, album, video, voice, file, share, retry) and
    /// wiring an explanation into each of them means the next one added will
    /// not have it - which is how photo, voice and file ended up saying
    /// "check your connection" to somebody the room had refused. One collector
    /// covers them all, including the ones that do not exist yet.
    private val _sendRefusal = MutableStateFlow<String?>(null)
    val sendRefusal: StateFlow<String?> = _sendRefusal.asStateFlow()

    /// Consume the last refusal, so one refusal is spoken once.
    fun takeSendRefusal(): String? = _sendRefusal.getAndUpdate { null }

    var lastSendRefusal: String?
        get() = _sendRefusal.value
        private set(v) { _sendRefusal.value = v }

    private val askedProfileKeyAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /// Who we already answered a `pkeyask` for, and when. Answering costs a
    /// vault round trip plus a sealed send, so an unthrottled asker could make
    /// this device work on demand; and the answer is the same key every time,
    /// so repeating it inside the window buys the asker nothing.
    private val answeredProfileKeyAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private val _ownAvatar = MutableStateFlow<Pair<String, String>?>(null)
    val ownAvatar: StateFlow<Pair<String, String>?> = _ownAvatar.asStateFlow()

    /** My own picture as (mediaId, key), from whatever the island just said.
     *
     *  ⚠⚠ The key has to be resolved, not read. Under the profile-key model we
     *  PUT the media id ALONE, and the island answers by clearing the column it
     *  used to keep (users.py) - which is the entire point, it must not hold
     *  the key to our face. So `avatar_media_key` comes back null forever after
     *  the first change, and every place that insisted on a non-empty key found
     *  none: the picture vanished from Settings and from the account switcher
     *  on the next launch, for everyone, with no switching and no race.
     *
     *  Worse than the blank face: `depositProfileTo` sends the profile as a
     *  SNAPSHOT, and an envelope naming no picture reads on the far side as "I
     *  removed mine". A pair that failed to resolve here therefore DELETED our
     *  face for every cross-island contact on the next nickname edit.
     *
     *  The key we published under lives in [LocalStores.myProfileKey]; the
     *  mirror of this for OTHER people is `avatarKeyResolved` in the contact
     *  mapper. Both are single points on purpose - the resolution must not be
     *  copied into the eight screens that draw a face. */
    private fun ownAvatarPair(mediaId: String?, mediaKey: String?): Pair<String, String>? {
        val id = mediaId?.takeIf { it.isNotEmpty() } ?: return null
        val key = mediaKey?.takeIf { it.isNotEmpty() } ?: LocalStores.myProfileKey() ?: return null
        return id to key
    }

    /** [ownAvatarPair] over a profile as the island serves it. Public because
     *  the account-switcher card resolves the same pair off disk. */
    fun ownAvatarOf(profile: RcqApi.MeProfile?): Pair<String, String>? =
        profile?.let { ownAvatarPair(it.avatar_media_id, it.avatar_media_key) }

    /** Owner/admin: rename / re-describe / re-pin a group. */
    suspend fun patchGroup(
        id: Int,
        name: String? = null,
        description: String? = null,
        pinnedText: String? = null,
        postPolicy: String? = null,
        isClosed: Boolean? = null,
        membersHidden: Boolean? = null,
        linksAllowed: Boolean? = null,
        filesAllowed: Boolean? = null,
        slowmodeSec: Int? = null,
        minAccountAgeHours: Int? = null,
        inCatalog: Boolean? = null,
    ) {
        val ctx = groupCtx(id)
        upsertGroup(mapGroupCtx(ctx, ctx.api.patchGroup(ctx.gid, RcqApi.GroupPatchBody(
            name = name, description = description, pinned_text = pinnedText,
            post_policy = postPolicy, is_closed = isClosed, members_hidden = membersHidden,
            links_allowed = linksAllowed, files_allowed = filesAllowed, slowmode_sec = slowmodeSec,
            min_account_age_hours = minAccountAgeHours,
            in_catalog = inCatalog,
        ))))
    }

    // ── own profile + privacy ────────────────────────────────────────

    private val profileGson = com.google.gson.Gson()

    /** Last-known-good privacy profile off disk, or null. */
    /** Last-known-good profile from the local cache (non-suspend) — the Privacy
     *  screen seeds its pickers from this so they render instantly with the user's
     *  real choices instead of snapping from the permissive defaults when the
     *  server load lands (the "ползунки едут на глазах" report). */
    fun cachedProfile(): RcqApi.MeProfile? =
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
        // Keep the settings avatar in step with the island without a second
        // round trip: the profile load already carries it.
        _ownAvatar.value = ownAvatarPair(net.avatar_media_id, net.avatar_media_key)
        // Re-hydrate the CHOSEN status. The picker was writing to the island and
        // then reading nothing back, so every relaunch quietly answered "Online"
        // for someone who had chosen Invisible (#533). Offline is what the
        // island reports for a viewer, never a choice, so it is not restored.
        net.status?.let { wire ->
            val chosen = UserStatus.from(wire)
            if (chosen != UserStatus.OFFLINE) _status.value = chosen
        }
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
        merged.profile_visibility?.let { profileVisibilityMemo = it }
        return merged
    }

    /** Who may open MY profile card: "everyone" | "contacts" | "nobody"
     *  (founder item 22). Non-suspend and free to call from a composition: it
     *  memoises over the on-disk privacy cache, which [loadProfile] and
     *  [updateProfile] both keep current.
     *
     *  ⚠ Answers "everyone" when nothing is known yet, because that is what the
     *  island does with an unset field. A gate that guessed "nobody" instead
     *  would hide a card the user never asked to hide, and the user would have
     *  no way to tell it apart from a broken fetch. */
    fun profileVisibility(): String =
        profileVisibilityMemo
            ?: (cachedProfile()?.profile_visibility?.takeIf { it in visibilityValues } ?: "everyone")
                .also { profileVisibilityMemo = it }

    /** Set it, island first, cache after. Returns false when the island refused
     *  or never answered.
     *
     *  ⚠ The caller must NOT paint the new value as applied on a false: this is
     *  the switch that decides whether strangers can read someone's city and
     *  age, and a privacy control that shows a setting the island never received
     *  is worse than one that visibly fails. */
    suspend fun setProfileVisibility(value: String): Boolean {
        val v = value.trim().lowercase()
        if (v !in visibilityValues) return false
        return updateProfile(RcqApi.UpdateMeBody(profile_visibility = v)) != null
    }

    suspend fun updateProfile(body: RcqApi.UpdateMeBody): RcqApi.MeProfile? {
        val updated = runCatching { api.updateMe(body) }.getOrNull()
        // Reflect a nickname change locally (the header reads store.nickname).
        if (updated != null && !body.nickname.isNullOrBlank()) {
            store.updateNickname(body.nickname)
            // §5e: the island tells same-island contacts over `contact_renamed`,
            // which cannot reach a holder on ANOTHER island (no host column in
            // the contacts table, so they are not in the audience). Deposit the
            // new name to them ourselves.
            broadcastProfileCrossIsland()
        }
        // Keep the read-receipt gate in sync when the user changes it.
        if (updated != null) body.read_receipts_visibility?.let { readReceiptsVisibility = it }
        // Same for the profile-card gate. Keyed off what the ISLAND echoed back
        // where it echoed one, so the memo can never be more permissive than
        // what was actually stored.
        if (updated != null) {
            (updated.profile_visibility ?: body.profile_visibility)?.let { profileVisibilityMemo = it }
        }
        // The PUT response is the full owner-self profile — refresh the cache
        // so the next Privacy-screen open re-seeds from the new choice even
        // offline.
        if (updated != null) LocalStores.setCachedProfileJson(profileGson.toJson(updated))
        return updated
    }

    /** Contacts the user has blocked (for the Blocked Users settings screen).
     *  Union of the server `blocked` contact flag and the LOCAL blocked set, so
     *  blocked strangers (no contact row) show up too — rendered as a #uin stub
     *  when we have no roster entry for them. */
    fun blockedContacts(): List<Contact> {
        val byUin = _contacts.value.associateBy { it.uin }
        val uins = LocalStores.blocked.value + _contacts.value.filter { it.blocked }.map { it.uin }
        return uins.toSet().map { uin ->
            byUin[uin]?.copy(blocked = true)
                ?: Contact(uin = uin, nickname = "#$uin", identityKey = "", signingKey = null, blocked = true)
        }.sortedBy { it.nickname.lowercase() }
    }

    suspend fun sendGroupText(groupId: Int, text: String, replyTo: app.rcq.android.crypto.Reply? = null) {
        val env = Envelope.text(text, replyTo, groupTtl(groupId))
        sendGroupEnvelope(groupId, env, env.id, text, kind = "text", replyTo = replyTo)
    }

    suspend fun sendGroupPhoto(groupId: Int, jpeg: ByteArray, caption: String?, spoiler: Boolean = false, albumId: String? = null) {
        val ttl = groupTtl(groupId)
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(jpeg, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobForGroup(groupId, blob)
        imageCache.put(upload.media_id, jpeg)
        val env = Envelope.photo(upload.media_id, keyB64, caption, spoiler, albumId, ttl)
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
        // The sender's own copy dies with the recipients' — read off the very
        // envelope going out, so the room's timer cannot be honoured on every
        // device except the one it was set on. See the note on [sendText].
        val (ttl, ts) = dyingOf(env)
        val now = System.currentTimeMillis()
        storeGroup(
            ChatMessage(
                id = id, peerUin = 0, fromMe = true, body = body,
                sentAt = now, state = DeliveryState.SENDING,
                kind = kind, mediaId = mediaId, mediaKey = mediaKey,
                replyToSnippet = replyTo?.snippet, replyToAuthor = replyTo?.authorName, replyToId = replyTo?.id,
                groupId = groupId, senderUin = me,
                fileName = fileName, fileMime = fileMime, fileSize = fileSize,
                durationSec = durationSec, thumbB64 = thumbB64, lat = lat, lng = lng,
                spoiler = spoiler, albumId = albumId,
                expiresAt = expiryFor(ttl, ts, now),
            )
        )
        fanOutGroup(groupId, env, id)
    }

    /** Encrypt [env] once per member (skipping self) and POST the fan-out;
     *  flips the local bubble's delivery state. Shared by send + resend. */
    private suspend fun fanOutGroup(groupId: Int, env: Envelope, id: String) = withContext(Dispatchers.IO) {
        // Same rule as the 1:1 path: a duress session puts nothing on the wire
        // and shows no failure for it. A seeded decoy has no groups today, so
        // this is unreachable — and it is here anyway, because "the decoy has
        // no groups" is a property of the seed, not of this function, and the
        // day that changes is not the day to rediscover the red cross.
        if (app.rcq.android.security.DuressGate.isActive) {
            updateGroupMsgState(groupId, id, DeliveryState.SENT)
            return@withContext
        }
        ensureRoster(groupId)
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
        // Sender keys (encrypt-once) for a LOCAL group with any capable member;
        // foreign groups (ctx.host != null) keep the legacy per-member path in
        // v1 (their capability lookup + broadcast endpoint live on the foreign
        // island we hold only guest creds for). seal once, distribute the chain
        // to capable members who need it, and fan the legacy copy to the rest.
        val sendable = group.members.filter { it.uin != me && it.identityKey.isNotEmpty() }
        val capable = if (ctx.host == null) sendable.filter { it.senderKeys } else emptyList()
        var skipped = 0
        // Stamped BEFORE the POST: the wake for a sibling account on this same
        // device can land while the request is still in flight.
        app.rcq.android.push.Push.noteOwnGroupPost(groupId)
        try {
            val resp: RcqApi.SendResponse
            if (capable.isNotEmpty()) {
                val step = SenderKeyStore.prepareOwnSend(me, ctx.gid, capable.map { it.uin })
                val gmsg = SenderKeys.sealGmsg(env, ctx.gid, step.kid, step.epoch, step.index, step.mk, signingPriv())
                // Distribute the chain key to capable members who don't hold it
                // yet FIRST, so a recipient never gets a gmsg for an unknown kid.
                val skdmTargets = capable.filter { it.uin in step.needDistribution }
                if (skdmTargets.isNotEmpty()) {
                    val skdmEnv = Envelope.Skdm(ctx.gid, step.kid, step.epoch, step.index, step.ckAtI)
                    val skdmPayloads = skdmTargets.mapNotNull { m ->
                        runCatching {
                            RcqApi.GroupPayload(m.uin, SealedSender.encryptV1(skdmEnv, Base64.decode(m.identityKey, Base64.NO_WRAP), me, signingPriv(), signingPub(), ctx.host ?: serverHost()))
                        }.getOrElse { skipped++; null }
                    }
                    if (skdmPayloads.isNotEmpty()) runCatching { ctx.api.sendGroupSealed(ctx.gid, skdmPayloads, envelopeType = "skdm") }
                }
                resp = withRetry { ctx.api.sendGroupBroadcast(ctx.gid, gmsg) }
                // Ratchet + mark distributed only after the broadcast lands.
                SenderKeyStore.markDistributed(me, ctx.gid, skdmTargets.map { it.uin })
                SenderKeyStore.advanceOwn(me, ctx.gid)
                // Legacy members (not yet updated) still get their per-member
                // copy — but NOT on the sender's clock (#465).
                //
                // In RCQ Beta that tail is 1184 separate seals and a ~1.3 MB
                // upload, ten to fifteen seconds of it, and the bubble's state
                // never depended on it: `resp` above is the broadcast's, and
                // this POST's answer is discarded. So the sender sat watching a
                // clock icon spin for work that had already reached everyone
                // whose client can read a broadcast.
                //
                // Detached from the send, not merely moved below the state
                // flip: leaving the caller waiting inside fanOutGroup would
                // still hold up the resend queue and the carbon behind it.
                val legacy = sendable.filter { !it.senderKeys }
                if (legacy.isNotEmpty()) {
                    scope.launch {
                        val legacyPayloads = legacy.mapNotNull { m ->
                            runCatching {
                                RcqApi.GroupPayload(m.uin, SealedSender.encryptV1(env, Base64.decode(m.identityKey, Base64.NO_WRAP), me, signingPriv(), signingPub(), ctx.host ?: serverHost()))
                            }.getOrNull()
                        }
                        if (legacyPayloads.isNotEmpty()) {
                            runCatching { withRetry { ctx.api.sendGroupSealed(ctx.gid, legacyPayloads, authed = group.postPolicy == "owner_only") } }
                                .onFailure { android.util.Log.w("RCQgroup", "group $groupId: legacy fan-out failed for ${legacyPayloads.size} member(s)", it) }
                        }
                    }
                }
            } else {
                // No capable member (or a foreign group): original per-member fan-out.
                val payloads = sendable.mapNotNull { m ->
                    runCatching {
                        RcqApi.GroupPayload(m.uin, SealedSender.encryptV1(env, Base64.decode(m.identityKey, Base64.NO_WRAP), me, signingPriv(), signingPub(), ctx.host ?: serverHost()))
                    }.getOrElse { skipped++; null }
                }
                resp = if (payloads.isEmpty()) RcqApi.SendResponse(delivered = false)
                    else withRetry { ctx.api.sendGroupSealed(ctx.gid, payloads, authed = group.postPolicy == "owner_only") }
            }
            if (skipped > 0) android.util.Log.w("RCQgroup", "group $groupId: skipped $skipped member(s) with an unusable identity key")
            updateGroupMsgState(groupId, id, if (resp.delivered) DeliveryState.DELIVERED else DeliveryState.SENT)
            // Mirror the message to the user's other devices (best-effort).
            // NOT for foreign groups: the carbon would carry the server-side
            // group id, which another of our devices would misread as a LOCAL
            // group (alias maps are per-device) — §5c v1 limit.
            if (ctx.host == null) sendMessageCarbon(env, toPeer = null, toGroup = groupId)
            lastSendRefusal = null
        } catch (e: Exception) {
            // ⚠⚠ Keep WHAT the island said. This catch swallowed the whole
            // answer and painted a red bubble, so a room rule ("you are still
            // in the newcomer waiting period", "slow mode") reached the person
            // as a generic delivery failure. The sentences for those were added
            // in 0.157 and were unreachable, because nothing ever threw out of
            // here for the UI to read (#836).
            lastSendRefusal = e.message
            updateGroupMsgState(groupId, id, DeliveryState.FAILED)
        }
    }

    /** Decrypt + store an inbound group message under its group thread.
     *
     *  Returns null when the row is DONE with: stored, held, a duplicate, or
     *  dropped for a reason no later delivery will change. Otherwise a short
     *  tag naming how it failed, for the room-log drain (Stage 5): a row whose
     *  failure may be transient (a store that would not open, a session that
     *  is still being set up) must not be acked past yet, see [drainGroupLog].
     *  Every other caller ignores the value, as before. */
    /** [depositAtMs] is the island's own stamp on the queue row this payload
     *  came off, epoch ms, and is the FALLBACK anchor for a disappearing
     *  message whose sender was too old to put a `ts` in the envelope. Null on
     *  a live socket delivery (where now IS arrival) and on a replay. */
    private fun ingestGroup(payloadB64: String, groupId: Int, depositAtMs: Long? = null): String? {
        // ⚠ A non-null tag, not null: null means the row is DONE WITH and lets
        // the ack move past it. See [ingest].
        if (duressViewUp) return "duress"
        if (!::db.isInitialized) return "db_closed"
        return runCatching {
            val me = store.uin ?: return null
            val dec = decryptInbound(payloadB64)
            when (val env = dec.envelope) {
                // Sender-keys distribution / recovery (never rendered). SKDM binds
                // the chain to its authenticated sender; SKNACK asks the kid owner
                // to re-distribute. Both ride the per-member sealed path.
                is Envelope.Skdm -> {
                    val ok = SenderKeyStore.acceptSkdm(me, env.kid, env.gid, dec.senderUin, SenderKeys.b64(dec.senderSigningPub), env.epoch, env.index, env.ck)
                    // The chain landed — anything we held for this kid can be
                    // read now. Without this the broadcasts that arrived first
                    // stay lost even though the key is finally here.
                    if (ok) {
                        sknackAnswered(env.kid)
                        replayHeldGmsg(env.kid)
                    }
                }
                is Envelope.Sknack -> answerSknack(groupId, dec.senderUin, env)
                else -> routeGroupEnvelope(env, groupId, dec.senderUin, depositAtMs)
            }
            null
        }.getOrElse { e ->
            logDecryptFailure(payloadB64, e)
            // A v=2 row served twice (the queue and the log both re-serve what
            // a live frame already delivered) is the ratchet saying it has
            // opened this one: done, not failed.
            if (e is DuplicateMessageException) null else e.javaClass.simpleName
        }
    }

    /** Decode a sender-keys `gmsg` broadcast via the stored chain and route the
     *  inner envelope. Drops my own echoed broadcast (carbon handles own
     *  multi-device sync), NACKs an unknown kid, and ignores an unverifiable or
     *  replayed message. Same return as [ingestGroup]: null when done with the
     *  row (a held broadcast counts as done, its SKDM rides its own row), a
     *  tag when the row may still pass on a later delivery. */
    private fun ingestGmsg(payloadB64: String, groupId: Int, depositAtMs: Long? = null): String? {
        // ⚠⚠ The third of the three ingests, and the only one that was missing
        // this. `db` is the DECOY database once the panic PIN is up, so without
        // it a real room broadcast is decrypted and written where the person
        // holding the phone can read it. A NON-null tag, not null: null is this
        // function's way of saying the row is done with, which would move the
        // room's cursor past it.
        if (duressViewUp) return "duress"
        return runCatching {
            val me = store.uin ?: return null
            val hdr = SenderKeys.parseGmsgHeader(payloadB64) ?: return null
            if (SenderKeyStore.ownsKid(me, hdr.kid)) return null // my own broadcast echoed back
            val key = SenderKeyStore.deriveInbound(me, hdr.kid, hdr.epoch, hdr.index)
            if (key == null) {
                // A chain we HAVE, already moved past this index: the broadcast
                // was opened before and is being served again (the legacy queue
                // keeps rows for every queueable member, the room log re-serves
                // whatever a reconnect did not ack), or it is a replay. Nothing
                // that can still arrive opens it, so it is done with: no hold,
                // no NACK. Holding it was not free: the hold table is sixteen
                // kids deep, a re-served room filled it with chains that were
                // never going to be redistributed, and from then on a broadcast
                // that genuinely could not be opened YET (below) was refused a
                // slot and lost for good once the drain acked it.
                if (SenderKeyStore.isBehind(me, hdr.kid, hdr.epoch, hdr.index)) return null
                // ⚠ DO NOT just drop it. This is a broadcast we cannot open
                // YET, not one we can never open: the chain key rides a
                // separate envelope on a separate endpoint, and it can be late
                // (a member added mid-conversation, an SKDM that missed the
                // offline queue). Dropping meant the message was gone for good
                // even after recovery, because SKNACK is answered with the
                // chain AT THE SENDER'S CURRENT INDEX and [deriveInbound]
                // refuses anything behind it. That is the second half of "мне
                // приходит уведомление, что есть сообщение в группе, захожу —
                // а его там нет" (#547), and the reason a newly added member
                // hears nothing for messages everyone else can read (#544).
                val held = holdGmsg(hdr.kid, groupId, payloadB64)
                if (!SenderKeyStore.knowsKid(me, hdr.kid)) sendSknack(groupId, hdr.kid)
                // Refused a slot (the table is full of other kids still waiting
                // for their chains): not held, so not done with; the log drain
                // leaves the room's cursor below it for a few drains.
                return if (held) null else "hold_full"
            }
            val opened = SenderKeys.openGmsg(payloadB64, groupId, key.mk, key.spub)
            if (!opened.verified) {
                android.util.Log.w("RCQgroup", "gmsg sig did not verify; dropping gid=$groupId kid=${hdr.kid}")
                return null
            }
            routeGroupEnvelope(opened.envelope, groupId, key.senderUin, depositAtMs)
            null
        }.getOrElse { e ->
            logDecryptFailure(payloadB64, e)
            e.javaClass.simpleName
        }
    }

    /** Store a decoded group envelope under its thread. Shared by the legacy
     *  per-member path (ingestGroup) and the sender-keys broadcast (ingestGmsg);
     *  [senderUin] is the authenticated sender from either path. */
    private fun routeGroupEnvelope(envelope: Envelope, groupId: Int, senderUin: Int, depositAtMs: Long? = null) {
        // Blocked sender → drop their group content entirely (messages,
        // reactions, edits, deletes). Sealed sender means the server can't
        // filter, so we gate on the decrypted sender here. Sender-key control
        // (SKDM/SKNACK) is handled before this call, so chain recovery is safe.
        if (LocalStores.isBlocked(senderUin)) return
        val dec = SenderUin(senderUin)
        val now = System.currentTimeMillis()
        when (val env = envelope) {
                is Envelope.Text -> storeGroup(
                    ChatMessage(env.id, 0, false, env.text, now, kind = "text", groupId = groupId, senderUin = dec.senderUin, replyToSnippet = env.replyTo?.snippet, replyToAuthor = env.replyTo?.authorName, replyToId = env.replyTo?.id, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs))
                )
                is Envelope.Photo -> storeGroup(
                    ChatMessage(env.id, 0, false, env.caption ?: "", now, kind = "photo", mediaId = env.mediaId, mediaKey = env.mediaKey, groupId = groupId, senderUin = dec.senderUin, spoiler = env.spoiler, albumId = env.albumId, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs))
                )
                is Envelope.File -> storeGroup(
                    ChatMessage(env.id, 0, false, env.caption ?: "", now, kind = "file", mediaId = env.mediaId, mediaKey = env.mediaKey, fileName = env.fileName, fileMime = env.mime, fileSize = env.sizeBytes, groupId = groupId, senderUin = dec.senderUin, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs))
                )
                is Envelope.Voice -> storeGroup(
                    ChatMessage(env.id, 0, false, "", now, kind = "voice", mediaId = env.mediaId, mediaKey = env.mediaKey, durationSec = env.durationSec.toInt(), groupId = groupId, senderUin = dec.senderUin, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs))
                )
                is Envelope.Video -> storeGroup(
                    ChatMessage(env.id, 0, false, env.caption ?: "", now, kind = "video", mediaId = env.mediaId, mediaKey = env.mediaKey, durationSec = env.durationSec.toInt(), thumbB64 = env.thumbnailB64, groupId = groupId, senderUin = dec.senderUin, spoiler = env.spoiler, albumId = env.albumId, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs))
                )
                is Envelope.Location -> storeGroup(
                    ChatMessage(env.id, 0, false, env.caption ?: "", now, kind = "location", lat = env.lat, lng = env.lng, groupId = groupId, senderUin = dec.senderUin, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs))
                )
                is Envelope.Poll -> storeGroup(
                    ChatMessage(env.id, 0, false, app.rcq.android.model.PollContent(env.pollId, env.question, env.options, env.singleChoice, env.anonymous).toJson(), now, kind = "poll", groupId = groupId, senderUin = dec.senderUin)
                )
                is Envelope.Reaction -> addGroupReaction(groupId, env.targetId, dec.senderUin, env.asset)
                is Envelope.Delete -> {
                    // Honor the delete if the deleter is the message author OR a
                    // group moderator: the owner, an ADMIN, or a member the owner
                    // granted the `delete` cap (founder batch 21.08, item 3; web
                    // precedent: incoming-store.ts groupModerator). The wire is
                    // unchanged — the same delete envelope the author's own
                    // retract fans out — the RECEIVER decides whether this sender
                    // may. We can check because sealed sender still reveals the
                    // decrypted deleter (dec.senderUin); the owner is read off
                    // the group row itself (the roster can be absent, #650), an
                    // admin needs the cached roster. An OLDER client ignores a
                    // foreign delete and keeps the message — nothing breaks, it
                    // just stays there. 1:1 deletes remain author-only.
                    val t = _groupMessages.value[groupId]?.firstOrNull { it.id == env.targetId }
                    if (t != null) {
                        val byAuthor = t.senderUin == dec.senderUin
                        val byModerator = group(groupId)?.moderator(dec.senderUin) == true
                        if (byAuthor || byModerator) deleteInFlow(_groupMessages, groupId, env.targetId)
                    }
                }
                is Envelope.Edit -> {
                    val t = _groupMessages.value[groupId]?.firstOrNull { it.id == env.targetId }
                    if (t != null && t.senderUin == dec.senderUin) editInFlow(_groupMessages, groupId, env.targetId, env.text)
                }
                is Envelope.ReadReceipt -> Unit  // group read receipts not surfaced per-message
                // Same for delivery: a group message has as many recipients as
                // it has members, and one tick cannot stand for all of them.
                is Envelope.DeliveredReceipt -> Unit
                is Envelope.Visit -> Unit        // visits are 1:1 only
                is Envelope.SecureScreen -> Unit // secure mode is 1:1 only
                is Envelope.ScreenshotTaken -> Unit
                is Envelope.CallSignal -> Unit   // 1:1 only (§5d); group calls don't cross islands
                is Envelope.Carbon -> Unit       // carbons arrive 1:1 (to self), never group-sealed
                is Envelope.ReadMark -> Unit     // A2 marker rides INSIDE a carbon, never group-sealed
                is Envelope.PKey -> Unit         // profile keys ride 1:1 sealed only
                is Envelope.PKeyAsk -> Unit      // ditto
                is Envelope.GsKey -> Unit        // room keys ride 1:1 sealed only
                is Envelope.GsKnack -> Unit      // asks ride 1:1 sealed only
                is Envelope.HomeRecord -> Unit   // self-push is 1:1 only, intercepted in ingest()
                is Envelope.ContactRequest -> Unit // §5f is 1:1 consent, never group-sealed
                is Envelope.ProfileUpdate -> Unit  // §5e is deposited per-contact, never group-sealed
                is Envelope.Skdm -> Unit         // intercepted in ingestGroup before routing
                is Envelope.Sknack -> Unit       // intercepted in ingestGroup before routing
                is Envelope.RelayShare ->
                    // A member shared a relay into the group to augment everyone's
                    // transport pool. Render as a kind="relay" card; never auto-apply
                    // (each member taps Add). Drop malformed. Group-shared relays stay
                    // exit/fallback + onion-entry-INELIGIBLE (excluded from
                    // trustedVlessEntries), so a poisoned share can't become an entry.
                    if (ContactRelayStore.relayFromJson(env.relay) != null)
                        storeGroup(ChatMessage(env.id, 0, false, env.relay.toString(), now, kind = "relay", groupId = groupId, senderUin = dec.senderUin))
                is Envelope.Unknown -> Unit
            }
    }

    /** Minimal holder so the routing block keeps its `dec.senderUin` reads
     *  whether the sender came from a sealed decrypt or a sender-keys chain. */
    private data class SenderUin(val senderUin: Int)

    /** Broadcasts held back waiting for their chain key, by kid.
     *
     *  In-memory and bounded: the queue drain re-delivers anything a process
     *  restart loses, and holding an unbounded number of un-openable blobs is
     *  its own bug. [HELD_GMSG_CAP] payloads per kid, [HELD_GMSG_KIDS] kids —
     *  enough for the real case (a handful of posts between "you were added"
     *  and "your SKDM arrived"), nowhere near enough to matter for memory. */
    private val heldGmsg = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<Int, String>>>()

    /** False when the table has no room for a new kid: the caller then knows
     *  the row was neither opened nor kept. */
    private fun holdGmsg(kid: String, groupId: Int, payloadB64: String): Boolean {
        if (heldGmsg.size >= HELD_GMSG_KIDS && !heldGmsg.containsKey(kid)) return false
        val list = heldGmsg.getOrPut(kid) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            if (list.any { it.second == payloadB64 }) return true
            if (list.size >= HELD_GMSG_CAP) list.removeAt(0)
            list.add(groupId to payloadB64)
        }
        return true
    }

    /** The chain for [kid] just arrived — re-run everything we held for it.
     *  Taken out of the map FIRST so a payload that still fails is re-held at
     *  most once and can never spin. [storeGroup] dedupes by envelope UUID, so
     *  a payload the drain also delivers costs nothing. */
    private fun replayHeldGmsg(kid: String) {
        val held = heldGmsg.remove(kid) ?: return
        val copy = synchronized(held) { held.toList() }
        copy.forEach { (gid, payload) -> ingestGmsg(payload, gid) }
    }

    /** Per-kid ask ledger: attempts + last-ask stamp. The flat ten-minute
     *  window turned a DEAD kid (owner deleted their account, nobody alive
     *  can answer) into a forever machine: one 24/7 install re-asked a
     *  971-member room every window, 366 whole-room fan-outs in 12 hours,
     *  measured on prod 30.08. The window now doubles per unanswered ask
     *  (10m, 30m, 2h, 6h, 24h) and past the ladder the kid is written off
     *  for a week; an SKDM that finally lands clears its record (see the
     *  Skdm branch in [ingestGroup]). The island additionally budgets
     *  sknack at 10/hour per account, so an old build in this loop now
     *  degrades to silence instead of a storm. In-memory is enough here:
     *  the process lives for days, and a restart costs one extra ask. */
    private val sknackSent = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    private fun sknackAllowed(kid: String): Boolean {
        val ladder = longArrayOf(10 * 60_000L, 30 * 60_000L, 2 * 3600_000L, 6 * 3600_000L, 24 * 3600_000L)
        val now = System.currentTimeMillis()
        val rec = sknackSent[kid]
        if (rec == null) {
            sknackSent[kid] = 1 to now
            return true
        }
        val (n, at) = rec
        val wait = if (n >= ladder.size) 7 * 24 * 3600_000L else ladder[n - 1]
        if (now - at < wait) return false
        sknackSent[kid] = minOf(n + 1, ladder.size + 1) to now
        return true
    }

    /** The asks worked: forget the ledger for this kid. */
    private fun sknackAnswered(kid: String) {
        sknackSent.remove(kid)
    }

    /** Fire one recovery request for an unknown kid to the group's capable
     *  members (we don't know whose kid it is). Ladder-debounced per kid. */
    private fun sendSknack(groupId: Int, kid: String) {
        if (!sknackAllowed(kid)) return
        val ctx = groupCtx(groupId)
        val me = ctx.myUin.takeIf { it != 0 } ?: return
        val g = group(groupId) ?: return
        scope.launch(Dispatchers.IO) {
            val payloads = g.members
                .filter { it.senderKeys && it.uin != me && it.identityKey.isNotEmpty() }
                .mapNotNull { m ->
                    runCatching {
                        RcqApi.GroupPayload(m.uin, SealedSender.encryptV1(Envelope.Sknack(ctx.gid, kid), Base64.decode(m.identityKey, Base64.NO_WRAP), me, signingPriv(), signingPub(), ctx.host ?: serverHost()))
                    }.getOrNull()
                }
            if (payloads.isNotEmpty()) runCatching { ctx.api.sendGroupSealed(ctx.gid, payloads, envelopeType = "sknack") }
        }
    }

    /** Answer a recovery request: if I own this group's chain, re-seal a current
     *  SKDM to the requester so they can read going forward. */
    private fun answerSknack(groupId: Int, requesterUin: Int, env: Envelope.Sknack) {
        val ctx = groupCtx(groupId)
        val me = ctx.myUin.takeIf { it != 0 } ?: return
        if (SenderKeyStore.ownKidForGroup(me, ctx.gid) != env.kid) return
        val snap = SenderKeyStore.ownChainSnapshot(me, ctx.gid) ?: return
        val m = group(groupId)?.members?.firstOrNull { it.uin == requesterUin } ?: return
        if (m.identityKey.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val skdmEnv = Envelope.Skdm(ctx.gid, snap.kid, snap.epoch, snap.index, snap.ck)
            runCatching {
                val p = RcqApi.GroupPayload(m.uin, SealedSender.encryptV1(skdmEnv, Base64.decode(m.identityKey, Base64.NO_WRAP), me, signingPriv(), signingPub(), ctx.host ?: serverHost()))
                ctx.api.sendGroupSealed(ctx.gid, listOf(p), envelopeType = "skdm")
            }
        }
    }

    /** Irreversible burn of the ACTIVE account: delete it server-side, wipe
     *  all of its local storage, drop it from the roster. If another account
     *  remains the session hot-swaps onto it and its UIN is returned;
     *  otherwise returns null (back to a fresh-install / onboarding state). */
    suspend fun burnAccount(): Int? {
        // ⚠ IN A DURESS SESSION THIS BURNS THE DECOY, NEVER THE REAL ACCOUNT.
        //
        // "Burn account" is a plain destructive row in Settings and a coercer
        // is exactly the person who taps it. Run unchanged it would delete the
        // REAL account server-side and then wipe its SecureStore — recovery
        // seed and identity keys included — from inside the duress view. The
        // decoy is the sacrificial session; it must be able to destroy itself
        // and nothing else.
        //
        // What the coercer sees is what they asked for: the account empties.
        if (duressViewUp) {
            burnDecoySession()
            return decoySessionUin
        }
        val burnedId = AccountManager.activeId.value
        // The server call decides whether anything is actually erased, so a
        // failure must not be swallowed: wiping local storage regardless left
        // the app telling someone their data was deleted while the account row
        // was still on the island. Retry once (a burn is usually attempted on a
        // bad network, and the endpoint is idempotent), and only then give up
        // and keep the account so the user can try again instead of being told
        // a comforting lie.
        val serverDeleted = runCatching { api.deleteAccount() }
            .recoverCatching { api.deleteAccount() }
            .isSuccess
        if (!serverDeleted) {
            // Returning the CURRENT uin keeps the caller on this account, which
            // is the truth: nothing was erased. Say so out loud, otherwise the
            // screen just closes and the user assumes it worked.
            android.util.Log.w("RCQburn", "server delete failed; keeping the account rather than faking erasure")
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    appCtx, appCtx.getString(R.string.burn_failed), android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            return store.uin
        }
        return eraseActiveAccountLocally(burnedId)
    }

    /** The local half of a burn: wipe the ACTIVE account's storage, drop it
     *  from the roster, and hot-swap onto the next account (returning its
     *  UIN) or fall back to the onboarding state (null). Shared between the
     *  user-initiated [burnAccount] and the #655 path, where the ISLAND says
     *  the account no longer exists and there is no server call left to make. */
    private suspend fun eraseActiveAccountLocally(burnedId: String?): Int? {
        socket.disconnect()
        // Read BEFORE the wipes below take the identity away: these two stores
        // are keyed by NUMBER, not by account id, so they cannot be swept from
        // `burnedId` afterwards.
        val burnedUin = store.uin
        if (burnedId != null) {
            SecureStore.wipeAccount(appCtx, burnedId)
            MessageDb.wipeAccount(appCtx, burnedId)
            SignalStoreDb.wipeAccount(appCtx, burnedId)
            app.rcq.android.data.VisitStore.wipeAccount(burnedId)
            CrossIslandStore.wipeAccount(burnedId)
            VisitedIslandsStore.wipeAccount(burnedId)
            LocalStores.clearAccount(burnedId)
            app.rcq.android.data.AccountCards.forget(appCtx, burnedId)
            AccountManager.remove(burnedId)   // active falls back to first remaining (or null)
        }
        // Keyed by number rather than by account id, so they sit outside the
        // block above and were simply never swept: §5f requests addressed to
        // the burned identity, the numbers it blocked, and LIVE JWTs to its
        // backup islands. Same shape as the fourth hole of the iOS
        // cross-island leak (f50a7b0).
        burnedUin?.let {
            CrossIslandRequestsStore.wipeOwn(it)
            MultihomeStore.wipeOwn(it)
        }
        if (burnedId == null) {
            store.wipe()
            db.wipe()
            app.rcq.android.data.VisitStore.wipe()
        }
        peerIdentityCache.clear()
        askedProfileKeyAt.clear(); answeredProfileKeyAt.clear()
        noV2Peers.clear(); peerDeviceCache.clear(); awaitingReplySince.clear(); lastSilenceProbeAt.clear(); presenceBaselineLive = false
        ackedReads.clear()
        // ⚠ Held call signals belong to the account that made them: an island,
        // a socket and a peer number that mean somebody else entirely on the
        // next one. Flushing them after a switch would hand a stranger's
        // hang-up to whoever holds that number here.
        synchronized(callOutbox) { callOutbox.clear() }
        _contacts.value = emptyList()
        _pending.value = emptyList()
        _outgoing.value = emptyList()
        _messages.value = emptyMap()
        _groups.value = emptyList()
        _groupMessages.value = emptyMap()
        // Back to "never loaded", not to "no devices": another account's
        // registry is a different list, and leaving this one visible would
        // show one account's linked sessions under another.
        _devices.value = null
        activeRandomPeer = null
        activeRandomPairId = null
        _randomMessages.value = emptyList()
        _random.value = RandomState.Idle
        _typingFrom.value = null
        // See [rebindTo]. Cleared here as well because the branch below only
        // reaches that reset when there IS another account to switch to; the
        // last account being deleted must not leave its face in the flow.
        _ownAvatar.value = null
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
            // Nothing is signed in any more, so the two device-wide island
            // caches are now a plaintext list of every island this device ever
            // talked to and one file per host on disk, outliving the
            // per-account record of the same islands that was just deleted.
            // Not touched while another account remains: it still needs its own
            // island drawn on the first frame.
            app.rcq.android.data.IslandCards.wipe(appCtx)
            app.rcq.android.data.IslandLogos.clear(appCtx)
            null
        }
    }

    /** #655: the island said this account no longer exists — the ACTIVE
     *  account was wiped locally. `nextUin` is the account the session
     *  hot-swapped onto, or null for onboarding. MainActivity collects this
     *  to move the UI; the wipe itself already happened down here. */
    data class AccountLost(val uin: Int, val nextUin: Int?)
    private val _accountLost = MutableSharedFlow<AccountLost>(extraBufferCapacity = 1)
    val accountLost: SharedFlow<AccountLost> = _accountLost

    @Volatile private var lastBurnProbeAt = 0L

    /** The socket was refused with 4401. Could be three different things —
     *  expired token, revoked device, burned account — and only a probe can
     *  tell them apart. Throttled: the socket keeps redialing on its backoff
     *  and every redial would land here. */
    private fun onSocketAuthRejected() {
        // Never from a duress view: `store` is the real account's and a probe
        // outcome must not tear anything down while a coercer is watching.
        if (duressViewUp) return
        val now = System.currentTimeMillis()
        if (now - lastBurnProbeAt < BURN_PROBE_THROTTLE_MS) return
        lastBurnProbeAt = now
        scope.launch { runCatching { probeBurnedAccount() } }
    }

    /** #655 — the burned account that kept talking. Burning bumps the uin
     *  epoch, so every token dies (WS 4401, drains 401) — but /messages/sealed
     *  is anonymous by design, so a client that shrugs 4401 off and reconnects
     *  forever KEEPS SENDING from an account the server already erased. The
     *  probe is /auth/refresh: prove our signing key for our own uin. A fresh
     *  token back means the token was merely stale — adopt it and redial. A
     *  clean `identity_not_found` means the account row is GONE (burned from
     *  another device): wipe locally, exactly like a self-burn minus the
     *  server call, and tell the UI. Any other failure (offline, 5xx) means
     *  nothing and changes nothing. */
    private suspend fun probeBurnedAccount() {
        val me = store.uin ?: return
        val spubB64 = Base64.encodeToString(signingPub(), Base64.NO_WRAP)
        val fresh = try {
            val challenge = api.recoverChallenge(spubB64).challenge
            val signature = app.rcq.android.crypto.RecoveryPhrase.signChallenge(signingPriv(), challenge)
            api.refreshSession(RcqApi.RefreshRequest(me, spubB64, challenge, signature, DeviceId.get(appCtx)))
        } catch (e: Exception) {
            if (e.message?.contains("identity_not_found") == true) {
                android.util.Log.w("RCQburn", "island no longer knows #$me — wiping the local copy (#655)")
                val next = eraseActiveAccountLocally(AccountManager.activeId.value)
                _accountLost.tryEmit(AccountLost(me, next))
            }
            return
        }
        // Alive after all — the token had merely rotted. Adopt + redial.
        store.updateToken(fresh.token)
        api.setToken(fresh.token)
        socket.disconnect()
        connectAndSync(me, fresh.token)
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
        app.rcq.android.data.AccountCards.forget(appCtx, accountId)
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

    /** Take [uin] from the UIN shop, moving this account onto it.
     *
     *  ⚠⚠ [switch] now defaults to TRUE, and false is refused by the island.
     *  Collections closed on 2026-09-01: taking a number without moving onto it
     *  is how 161 numbers ended up parked in 54 private hoards while the short
     *  ones everyone else picks from ran out. Taking one means becoming it, and
     *  the server performs the SAME migration as [migrateToNewUin], so local
     *  handling is identical: history survives (peer-keyed), contacts and
     *  groups re-sync.
     *
     *  A 409 (someone grabbed it first between quote and purchase) maps to
     *  [PurchaseResult.Taken] so the shop can prompt for a different number; a
     *  403 on a short or patterned number is [PurchaseResult.Reserved]; other
     *  failures bubble up as [PurchaseResult.Other]. */
    suspend fun purchaseUin(uin: Int, switch: Boolean = true): PurchaseResult {
        val resp = try {
            api.purchaseUin(uin, switch)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // Both arrive as 409: "someone took it first" and "your collection
            // is full" are different answers to the user, so tell them apart by
            // the code the island sends rather than by the status alone.
            return when {
                msg.contains("reserved") -> PurchaseResult.Reserved
                msg.contains("collections_closed") -> PurchaseResult.Reserved
                msg.contains("too_many_uins") -> PurchaseResult.TooMany
                msg.contains("HTTP 409") -> PurchaseResult.Taken
                else -> PurchaseResult.Other(msg)
            }
        }
        return applyTake(resp)
    }

    // ── backup ───────────────────────────────────────────────────────

    /** Every message on this device, oldest first, for an export. */
    /** Every message on this device that is allowed to outlive it.
     *
     *  ⚠ Disappearing messages are excluded, and that is the point of them.
     *  Someone who sets a one-day timer is saying this should not exist
     *  tomorrow; writing it into a file that survives on a drive for years,
     *  in the clear, would quietly undo the one guarantee they asked for.
     *  Decided by the founder on 2026-08-07 and written into the format doc. */
    fun allMessagesForBackup(): List<ChatMessage> =
        if (::db.isInitialized) db.all().filter { it.expiresAt == null } else emptyList()

    /** What became of one restored message. A restore only ever ADDS, so an old
     *  archive can never eat newer history — but "not added" has two different
     *  meanings and the screen used to report both as "already here", which is
     *  a lie in the second case: the message is not here and will not be. */
    enum class RestoreInsert { ADDED, ALREADY_HERE, DELETED_HERE }

    fun insertRestoredMessage(msg: ChatMessage): RestoreInsert {
        if (!::db.isInitialized) return RestoreInsert.ALREADY_HERE
        // ⚠ honourTombstones is TRUE since 0.105. It was false, on the reading
        // that a restore is the user asking for their history back — but the
        // effect people actually hit was the opposite of what they asked for:
        // delete a message, restore any archive, and it returns, either from
        // the archive or from the island's queue once the tombstone is gone.
        // Reported as "удаление сообщений работает фиктивно ... возможность
        // получения доступа к удалённой переписке". A delete is the clearer
        // instruction of the two, so it wins.
        if (runCatching { db.isDeleted(msg.id) }.getOrDefault(false)) return RestoreInsert.DELETED_HERE
        val added = runCatching { db.insert(msg, honourTombstones = true) }.getOrDefault(false)
        return if (added) RestoreInsert.ADDED else RestoreInsert.ALREADY_HERE
    }

    /** Re-seed the in-memory threads from the database.
     *
     *  ⚠ The chat screens do not read the database, they read the flows below,
     *  and those were filled exactly once — at launch, before anything else
     *  could write. A restore therefore landed every message in SQLite and left
     *  every screen showing what it showed a moment earlier, so the person read
     *  "411 added" over an empty chat list and only saw their history after
     *  restarting the app. Reported by #100200300 as "пишет, что что-то
     *  добавлено, но история не импортируется, чаты пусты" — followed later by
     *  "появилось через некоторое время", which is the restart. */
    suspend fun reloadHistoryFromDb() = withContext(Dispatchers.IO) {
        if (::db.isInitialized) runCatching { loadMessagesFromDb() }
        Unit
    }

    /** Tombstones for the archive, so a restore cannot resurrect deletions. */
    fun deletedIdsForBackup(): List<Pair<String, Long>> =
        if (::db.isInitialized) runCatching { db.allDeletedIds() }.getOrDefault(emptyList()) else emptyList()

    /** Re-arm a tombstone that travelled in an archive. */
    fun restoreDeletedId(id: String, at: Long) {
        if (::db.isInitialized) runCatching { db.markDeleted(id, at) }
    }

    /** Drop a message the archive says was deleted. Needed because the archive
     *  lists messages before tombstones, so one restored a moment ago has to be
     *  taken back out. */
    fun deleteRestoredMessage(id: String) {
        if (::db.isInitialized) runCatching { db.delete(id) }
    }

    /** Put a restored attachment back where [fetchImage] will find it, so the
     *  picture shows even when the blob has long aged off the island.
     *
     *  ⚠ The memory cache alone is not enough and used to be all this did: it
     *  is an LRU of a few tens of megabytes, so a large restore evicted its own
     *  pictures while it was still running and the rest were gone at the next
     *  app start — while the screen promised they would open years from now.
     *  The archive holds the DECRYPTED bytes, the disk cache holds the sealed
     *  blob, so it is re-sealed with the key that travelled in the record. */
    fun cacheRestoredMedia(mediaId: String, bytes: ByteArray, mediaKey: String?) {
        if (mediaId.isEmpty() || bytes.isEmpty()) return
        imageCache.put(mediaId, bytes)
        if (mediaKey.isNullOrEmpty()) return
        runCatching {
            val key = Base64.decode(mediaKey, Base64.NO_WRAP)
            mediaDiskFile(mediaId).writeBytes(MediaCrypto.seal(bytes, key))
            trimMediaDiskCache()
        }
    }

    /** Everything this account holds, plus the number it answers as now. */
    suspend fun myUins(): RcqApi.MyUinsResponse = api.myUins()

    /** Give a held number back to the pool. Throws on failure so the screen can
     *  say what went wrong; there is nothing local to roll back, the collection
     *  is re-read from the server afterwards either way. */
    suspend fun releaseUin(uin: Int) = api.releaseUin(uin)

    /** Answer as [uin], a number already in this account's collection. The
     *  number in use goes into the collection in its place, so this is
     *  reversible. Same migration handling as a purchase-with-switch. */
    suspend fun activateUin(uin: Int): PurchaseResult {
        val resp = try {
            api.activateUin(uin)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            return if (msg.contains("HTTP 404")) PurchaseResult.NotOwned else PurchaseResult.Other(msg)
        }
        return applyTake(resp)
    }

    /** Shared tail of /uin/purchase + /uin/activate: migrate when the server
     *  says it switched us, otherwise just report the new collection. */
    private fun applyTake(resp: RcqApi.PurchaseResponse): PurchaseResult {
        val newUin = resp.new_uin
        val token = resp.token
        if (resp.switched && newUin != null && !token.isNullOrEmpty()) {
            applyMigration(newUin, token)
            return PurchaseResult.Success(newUin)
        }
        return PurchaseResult.Held(resp.owned)
    }

    sealed class PurchaseResult {
        /** The account now answers as [newUin]. */
        data class Success(val newUin: Int) : PurchaseResult()
        /** The number is held; the account still answers as it did. [owned] is
         *  the collection afterwards. */
        data class Held(val owned: List<Int>) : PurchaseResult()
        object Taken : PurchaseResult()
        /** The island keeps this one as stock: short (six digits or fewer) or a
         *  recognisable shape. Not "try again later" — it is not on offer, and
         *  the answer is a different number. */
        object Reserved : PurchaseResult()
        /** The collection is full. The island caps how many numbers one
         *  account may hold, so the answer is "let one go first", not
         *  "try another number". */
        object TooMany : PurchaseResult()
        /** /uin/activate on a number this account does not hold. */
        object NotOwned : PurchaseResult()
        data class Other(val message: String?) : PurchaseResult()
    }

    /** Tell the server which INSTALL this session is, once.
     *
     *  A token minted before the client sent a device id has no `dev` claim,
     *  so the server keys it as "primary" — the same name every other install
     *  of the account uses. Two of them then supersede each other's websocket
     *  in a loop (the reconnect storm), and they share one offline-queue
     *  cursor, so whichever drains first leaves the other with nothing to
     *  read. One call swaps the token for one that names this install; the
     *  server copies the drain cursor across so nothing is re-downloaded.
     *
     *  Best effort and silent: an island too old to know the route answers
     *  404 and we simply keep the token we have. */
    private fun claimInstallToken(current: String) {
        if (JwtPeek.hasDeviceClaim(current)) return
        scope.launch {
            // ⚠⚠ Everything below writes CREDENTIALS, so hold the account we
            // started for and the objects that belonged to it. Written through
            // the live fields instead, a switch during the round trip put the
            // token ISLAND A MINTED into account B's SecureStore, where it
            // survives a restart — and when both accounts sit on the same
            // island (which is allowed), account B then talks to it as A.
            val ep = epochNow()
            val forStore = store
            val forApi = api
            val forSocket = socket
            val fresh = runCatching { forApi.claimDevice(DeviceId.get(appCtx)) }.getOrNull() ?: return@launch
            if (fresh.token.isBlank()) return@launch
            if (!stillOn(ep)) return@launch
            forStore.updateToken(fresh.token)
            forApi.setToken(fresh.token)
            // The socket authenticates with the token it was handed at dial
            // time; reconnect so it comes back under the install's own name
            // instead of "primary". The captured one, so a switch that raced
            // us cannot make this redial the NEW account's socket with a token
            // the OLD account's island issued.
            forSocket.reconnectNow()
        }
    }

    /** Live availability + price preview for a candidate UIN (POST /uin/quote). */
    suspend fun quoteUin(uin: Int): RcqApi.QuoteResponse = api.uinQuote(uin)

    /** Swap uin+token locally (keys/nickname/server stay) and reboot the
     *  session under the new UIN. **Local chat history is PRESERVED** — it's
     *  keyed by the peer's UIN (which doesn't change; only ours does), so it
     *  stays valid; start() reloads it from the intact db. Contacts/groups
     *  re-sync from the server. Shared by the free migrate + the shop purchase. */
    private fun applyMigration(resp: RcqApi.MigrateResponse) =
        applyMigration(resp.new_uin, resp.token)

    private fun applyMigration(newUin: Int, token: String) {
        socket.disconnect()
        store.updateAccount(newUin, token)
        api.setToken(token)
        peerIdentityCache.clear()
        askedProfileKeyAt.clear(); answeredProfileKeyAt.clear()
        noV2Peers.clear(); peerDeviceCache.clear(); awaitingReplySince.clear(); lastSilenceProbeAt.clear(); presenceBaselineLive = false
        ackedReads.clear()
        // ⚠ Held call signals belong to the account that made them: an island,
        // a socket and a peer number that mean somebody else entirely on the
        // next one. Flushing them after a switch would hand a stranger's
        // hang-up to whoever holds that number here.
        synchronized(callOutbox) { callOutbox.clear() }
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

    /** ⚠ THE SENDER'S OWN ROW CARRIES THE TIMER TOO, here and in every send
     *  path below. Wiring only the envelope gives a message that vanishes off
     *  the recipient's device and lives on mine for ever — and worse,
     *  [allMessagesForBackup] keeps exactly the rows whose `expiresAt` is null,
     *  so my copy of a message the other side was told had disappeared would be
     *  written into the export in the clear, which is the one guarantee that
     *  function exists to make. The deadline comes off `env.ts`, so both copies
     *  die at the same instant. */
    suspend fun sendText(toUin: Int, text: String, replyTo: Reply? = null) {
        val env = Envelope.text(text, replyTo, peerTtl(toUin))
        val now = System.currentTimeMillis()
        store(ChatMessage(env.id, toUin, fromMe = true, body = text, sentAt = now, state = DeliveryState.SENDING, replyToSnippet = replyTo?.snippet, replyToAuthor = replyTo?.authorName, replyToId = replyTo?.id, expiresAt = expiryFor(env.ttl, env.ts, now)))
        sendEnvelope(env, env.id, toUin)
    }

    /** In-chat bridge sharing: hand [toUin] a relay descriptor from your known
     *  pool so they can route through it when their own relays are blocked
     *  (censorship-resistance — distribute off-config relays peer-to-peer).
     *  Renders as a kind="relay" card on both sides; the recipient taps Add.
     *  See RCQ/docs/bridge-sharing-design.md. */
    suspend fun shareRelay(toUin: Int, relay: SingBoxTransport.Relay) {
        val relayJson = ContactRelayStore.relayToJson(relay)
        val env = Envelope.relayShare(relayJson)
        store(ChatMessage(env.id, toUin, fromMe = true, body = relayJson.toString(), sentAt = System.currentTimeMillis(), state = DeliveryState.SENDING, kind = "relay"))
        sendEnvelope(env, env.id, toUin)
    }

    /** Share a relay into a GROUP — the highest-reach censorship-resistant
     *  distribution: one drop in a community group hands the relay to every member
     *  over the E2E group fan-out (sender keys), invisible to a censor. Renders as
     *  a kind="relay" card; each member taps Add (never auto-applied). A
     *  group-shared relay stays exit/fallback only and onion-entry-INELIGIBLE
     *  (ContactRelayStore is excluded from trustedVlessEntries), so a poisoned
     *  share can't become anyone's entry guard. See RCQ/docs/relay-distribution-v2.md. */
    suspend fun shareRelayToGroup(groupId: Int, relay: SingBoxTransport.Relay) {
        val relayJson = ContactRelayStore.relayToJson(relay)
        val env = Envelope.relayShare(relayJson)
        sendGroupEnvelope(groupId, env, env.id, relayJson.toString(), kind = "relay")
    }

    /** Relays the user can hand to a contact: the signed-config pool + already
     *  imported/shared relays, deduped by proto:server:port. */
    // Only OFF-CONFIG relays are worth sharing peer-to-peer: the signed-config
    // pool (RelayConfigStore) already reaches every user, and surfacing its IPs
    // in a shareable list just helps a censor enumerate them. Share community
    // relays - handed to us by a contact or pulled from the broker - the point
    // of the hydra (гидра) P2P channel: distribute bridges with no central list
    // to seize. NB: official relays are ALSO registered with the broker, so we
    // subtract the signed-config set explicitly (else they leak back in via
    // BrokerRelayStore).
    fun shareableRelays(): List<SingBoxTransport.Relay> {
        val official = RelayConfigStore.currentRelays()
            .mapTo(HashSet()) { "${it.proto}:${it.server}:${it.port}" }
        return (ContactRelayStore.relays() + app.rcq.android.net.BrokerRelayStore.relays())
            .distinctBy { "${it.proto}:${it.server}:${it.port}" }
            .filterNot { "${it.proto}:${it.server}:${it.port}" in official }
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
        // ⚠⚠ A duress session uploads nothing, to any island. Both branches
        // below walk somewhere it must not go: the own-island one is an OkHttp
        // call, so the gate throws and the row lands FAILED — the red cross we
        // just took out of the text path, back again for a photo — and the
        // cross-island one is `CrossIslandSender.depositBlob`, which is not an
        // `api` call and the gate never saw.
        //
        // A client-minted id and no network. The bubble is built from the id we
        // return and the blob is already on this device, so the picture renders
        // in the decoy exactly like a sent one; the send path marks it SENT.
        if (app.rcq.android.security.DuressGate.isActive) {
            return RcqApi.UploadResponse(java.util.UUID.randomUUID().toString().replace("-", ""), blob.size)
        }
        val ci = CrossIslandStore.findByUin(toUin) ?: return api.uploadBlob(blob, ::reportUpload)
        return withContext(Dispatchers.IO) {
            val mediaId = java.util.UUID.randomUUID().toString().replace("-", "")
            // The peer-island copy is REQUIRED — that's the one they read,
            // and it is the one worth showing a percentage for (#831).
            if (!CrossIslandSender.depositBlob(ci.host, mediaId, blob, ::reportUpload)) {
                throw java.io.IOException("cross-island media deposit failed (${ci.host})")
            }
            runCatching { api.putBlob(mediaId, blob) }
            RcqApi.UploadResponse(mediaId, blob.size)
        }
    }

    /** Encrypt+upload an already-compressed JPEG, then send a photo
     *  envelope carrying the media id + per-blob key (rcq-spec 9). The
     *  local bubble appears once the blob is uploaded. */
    /** Media uploads in flight, so the chat can say something is happening.
     *
     *  ⚠ A photo produces NO row until its upload finishes — the row is built
     *  from the media id the server hands back — so for the whole upload the
     *  screen shows nothing at all. Reported as "не очевиден процесс отправки,
     *  не хватает индикатора" (#473), which is exactly right, and the same
     *  silence is what made the two failures underneath it unreadable. */
    private val _mediaSending = MutableStateFlow(0)
    val mediaSending: StateFlow<Int> = _mediaSending.asStateFlow()
    /// How far the current upload has got, 0f..1f, or null while we do not
    /// know (cross-island deposits and the encrypt step report nothing).
    private val _mediaProgress = MutableStateFlow<Float?>(null)
    val mediaProgress: StateFlow<Float?> = _mediaProgress.asStateFlow()

    /// Files finished since the current wave of sending began; reset to zero
    /// the moment nothing is in flight. Together with [_mediaSending] (what is
    /// LEFT) it gives the size of the wave without anybody having to remember
    /// it: total = done + left, which stays right even when a second batch
    /// starts while the first is still going.
    private val _mediaDone = MutableStateFlow(0)

    /// The size of the wave, never shrinking while it runs (see [reportUpload]).
    private val _mediaWhole = MutableStateFlow(0)

    /// Called from the upload thread for every chunk that reaches the socket.
    /// Kept deliberately dumb about WHICH upload it is: the last write wins,
    /// because with two uploads in flight a shared bar is honest enough and
    /// the count next to it already says how many there are.
    ///
    /// #831: what it is NOT dumb about any more is the batch. The fraction
    /// used to be "this one file", so a batch of ten reset the ring to
    /// indeterminate ten times and spent most of its life spinning rather than
    /// filling. Now the file's own fraction is folded into the wave, so the
    /// ring only ever moves forward.
    private fun reportUpload(sent: Long, total: Long) {
        val here = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
        val done = _mediaDone.value
        // High-water mark: a second batch joining mid-wave GROWS the
        // denominator, and without this the fraction would jump backwards the
        // moment it did. Reset with _mediaDone when the wave drains.
        val whole = _mediaWhole.updateAndGet { maxOf(it, done + _mediaSending.value.coerceAtLeast(1)) }
        _mediaProgress.value = if (whole > 1) ((done + here) / whole).coerceIn(0f, 1f)
                               else if (total > 0) here else null
    }

    /** Mirror an in-flight upload into the shade while the app is NOT on
     *  screen (#831).
     *
     *  The strip above the composer is the in-app answer, and it is enough
     *  right up to the moment the person leaves — which is exactly what
     *  sharing from the Gallery does: pick photos, hand them over, go straight
     *  back to the Gallery, and from there nothing said the upload was still
     *  running ("загрузка происходит незаметно, кажется как будто ничто не
     *  отправилось").
     *
     *  Reads [RcqApp.foreground] rather than taking a third foreground hook:
     *  the two named slots there each have an owner, and stealing one is the
     *  0.151 regression that killed the ringing handoff. Returning to the app
     *  cancels the notice from MainActivity.onStart, so a finished upload
     *  cannot leave a stale bar in the shade.
     */
    @Volatile private var uploadWatcher: kotlinx.coroutines.Job? = null

    private fun watchUploadsOffScreen() {
        // One watcher per session. `start()` no-ops on a second call, but
        // rebindTo (account switch) reaches here too, and a second collector on
        // the same flows would post and cancel the same notification twice.
        if (uploadWatcher?.isActive == true) return
        uploadWatcher = scope.launch {
            combine(_mediaSending, _mediaProgress) { left, p ->
                // Map to what the shade should SHOW before deduping, so a
                // foreground upload does not run cancel() once per 64 KB chunk:
                // in the foreground every tick maps to the same null.
                if (left > 0 && !RcqApp.foreground) left to ((p ?: 0f) * 100).toInt() else null
            }
                .distinctUntilChanged()
                .collect { shown ->
                    if (shown == null) app.rcq.android.push.Push.hideUploadProgress(appCtx)
                    else app.rcq.android.push.Push.showUploadProgress(appCtx, shown.first, shown.second / 100f)
                }
        }
    }

    /** Last media send that failed, for the chat to surface once. */
    private val _mediaSendFailed = MutableStateFlow(0)
    val mediaSendFailed: StateFlow<Int> = _mediaSendFailed.asStateFlow()

    fun clearMediaSendFailed() { _mediaSendFailed.value = 0 }

    /** Send media without tying it to the screen that started it.
     *
     *  ⚠⚠ Two bugs in one line, both silent. The caller used to launch this on
     *  the composable's `rememberCoroutineScope()`, so backing out of the chat
     *  CANCELLED the upload mid-flight and the picture was simply gone — which
     *  is why sharing into RCQ "usually" failed while the paperclip was "50/50":
     *  after a share you are far more likely to leave the screen. And the
     *  failure was wrapped in a bare `runCatching`, so a genuinely failed upload
     *  looked identical to a successful one: dialog closes, nothing appears,
     *  nothing said. This runs on the session's own scope and reports what
     *  happened. */
    fun sendMediaDetached(what: String, count: Int = 1, block: suspend (oneDone: () -> Unit) -> Unit) {
        // An album is `count` files behind one call: the strip above the
        // composer counts them down as each one lands, instead of saying
        // "1 file" for a batch of ten. Before 0.142 a batch did not go through
        // here at all: it ran on the chat screen's own scope with a bare
        // runCatching, so there was no strip, leaving the chat cancelled the
        // upload, and a failure said nothing (#691, the same hole #473 closed
        // for a single picture).
        // update {} rather than `value +=`: the add runs on the caller's
        // thread and the subtract on the session scope, and a lost update
        // would leave the strip stuck at a count that never reaches zero.
        _mediaSending.update { it + count }
        var left = count
        val oneDone = {
            if (left > 1) {
                left -= 1
                _mediaSending.update { (it - 1).coerceAtLeast(0) }
                // #831: the bar spans the WAVE, so a finished file advances it
                // instead of blanking it. The next file is read and sealed
                // before its first byte goes out, and the old `= null` here
                // dropped the ring back to indeterminate for that whole gap.
                _mediaDone.update { it + 1 }
            }
        }
        scope.launch {
            try {
                block(oneDone)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("RCQmedia", "$what failed to send", e)
                _mediaSendFailed.update { it + 1 }
            } finally {
                val now = _mediaSending.updateAndGet { (it - left).coerceAtLeast(0) }
                if (now == 0) {
                    _mediaProgress.value = null
                    // The wave is over; the next one starts counting from zero.
                    _mediaDone.value = 0
                    _mediaWhole.value = 0
                }
            }
        }
    }

    suspend fun sendPhoto(toUin: Int, jpeg: ByteArray, caption: String?, spoiler: Boolean = false, albumId: String? = null) {
        // ⚠ Read BEFORE the upload, which can take a while on a bad line. The
        // timer that counts is the one the thread had when the user pressed
        // send; turning it off while their picture is still going up must not
        // quietly strip the promise off a message already on its way.
        val ttl = peerTtl(toUin)
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(jpeg, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobFor(toUin, blob)      // throws on failure (caller catches)
        imageCache.put(upload.media_id, jpeg)            // own bubble renders without re-download
        val env = Envelope.photo(upload.media_id, keyB64, caption, spoiler, albumId, ttl)
        val now = System.currentTimeMillis()
        store(ChatMessage(env.id, toUin, true, caption ?: "", now, DeliveryState.SENDING, kind = "photo", mediaId = upload.media_id, mediaKey = keyB64, spoiler = spoiler, albumId = albumId, expiresAt = expiryFor(env.ttl, env.ts, now)))
        sendEnvelope(env, env.id, toUin)
    }

    /** Encrypt+upload arbitrary file bytes, then send a file envelope (same
     *  blob path as photos; rcq-spec 9). [fileName]/[mime]/size describe it. */
    suspend fun sendFile(toUin: Int, bytes: ByteArray, fileName: String, mime: String) {
        val ttl = peerTtl(toUin)
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobFor(toUin, blob)
        imageCache.put(upload.media_id, bytes)
        val size = bytes.size.toLong()
        val env = Envelope.file(upload.media_id, keyB64, fileName, mime, size, null, ttl)
        val now = System.currentTimeMillis()
        store(ChatMessage(env.id, toUin, true, "", now, DeliveryState.SENDING, kind = "file", mediaId = upload.media_id, mediaKey = keyB64, fileName = fileName, fileMime = mime, fileSize = size, expiresAt = expiryFor(env.ttl, env.ts, now)))
        sendEnvelope(env, env.id, toUin)
    }

    /** Group file: encrypt once, fan out per member (same as group photo). */
    suspend fun sendGroupFile(groupId: Int, bytes: ByteArray, fileName: String, mime: String) {
        val ttl = groupTtl(groupId)
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobForGroup(groupId, blob)
        imageCache.put(upload.media_id, bytes)
        val size = bytes.size.toLong()
        val env = Envelope.file(upload.media_id, keyB64, fileName, mime, size, null, ttl)
        sendGroupEnvelope(groupId, env, env.id, "", kind = "file", mediaId = upload.media_id, mediaKey = keyB64, fileName = fileName, fileMime = mime, fileSize = size)
    }

    /** Encrypt+upload a recorded voice clip, then send a voice envelope. */
    suspend fun sendVoice(toUin: Int, bytes: ByteArray, durationSec: Int) {
        val ttl = peerTtl(toUin)
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobFor(toUin, blob)
        imageCache.put(upload.media_id, bytes)
        val env = Envelope.voice(upload.media_id, keyB64, durationSec.toDouble(), ttl)
        val now = System.currentTimeMillis()
        store(ChatMessage(env.id, toUin, true, "", now, DeliveryState.SENDING, kind = "voice", mediaId = upload.media_id, mediaKey = keyB64, durationSec = durationSec, expiresAt = expiryFor(env.ttl, env.ts, now)))
        sendEnvelope(env, env.id, toUin)
    }

    /** Group voice note: encrypt once, fan out per member. */
    suspend fun sendGroupVoice(groupId: Int, bytes: ByteArray, durationSec: Int) {
        val ttl = groupTtl(groupId)
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobForGroup(groupId, blob)
        imageCache.put(upload.media_id, bytes)
        val env = Envelope.voice(upload.media_id, keyB64, durationSec.toDouble(), ttl)
        sendGroupEnvelope(groupId, env, env.id, "", kind = "voice", mediaId = upload.media_id, mediaKey = keyB64, durationSec = durationSec)
    }

    /** Encrypt+upload a picked video, then send a video envelope carrying a
     *  base64 poster thumbnail so the bubble renders before download. */
    suspend fun sendVideo(toUin: Int, bytes: ByteArray, thumbB64: String, durationSec: Int, caption: String?, spoiler: Boolean = false, albumId: String? = null) {
        val ttl = peerTtl(toUin)
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobFor(toUin, blob)
        imageCache.put(upload.media_id, bytes)
        val env = Envelope.video(upload.media_id, keyB64, thumbB64, durationSec.toDouble(), caption, spoiler, albumId, ttl)
        val now = System.currentTimeMillis()
        store(ChatMessage(env.id, toUin, true, caption ?: "", now, DeliveryState.SENDING, kind = "video", mediaId = upload.media_id, mediaKey = keyB64, durationSec = durationSec, thumbB64 = thumbB64, spoiler = spoiler, albumId = albumId, expiresAt = expiryFor(env.ttl, env.ts, now)))
        sendEnvelope(env, env.id, toUin)
    }

    /** Group video: encrypt once, fan out per member. */
    suspend fun sendGroupVideo(groupId: Int, bytes: ByteArray, thumbB64: String, durationSec: Int, caption: String?, spoiler: Boolean = false, albumId: String? = null) {
        val ttl = groupTtl(groupId)
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.seal(bytes, key)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadBlobForGroup(groupId, blob)
        imageCache.put(upload.media_id, bytes)
        val env = Envelope.video(upload.media_id, keyB64, thumbB64, durationSec.toDouble(), caption, spoiler, albumId, ttl)
        sendGroupEnvelope(groupId, env, env.id, caption ?: "", kind = "video", mediaId = upload.media_id, mediaKey = keyB64, durationSec = durationSec, thumbB64 = thumbB64, spoiler = spoiler, albumId = albumId)
    }

    /** Share a geographic point (no blob, just coordinates in the envelope). */
    suspend fun sendLocation(toUin: Int, lat: Double, lng: Double, caption: String?) {
        val env = Envelope.location(lat, lng, caption, peerTtl(toUin))
        val now = System.currentTimeMillis()
        store(ChatMessage(env.id, toUin, true, caption ?: "", now, DeliveryState.SENDING, kind = "location", lat = lat, lng = lng, expiresAt = expiryFor(env.ttl, env.ts, now)))
        sendEnvelope(env, env.id, toUin)
    }

    suspend fun sendGroupLocation(groupId: Int, lat: Double, lng: Double, caption: String?) {
        val env = Envelope.location(lat, lng, caption, groupTtl(groupId))
        sendGroupEnvelope(groupId, env, env.id, caption ?: "", kind = "location", lat = lat, lng = lng)
    }

    /** The ttl (seconds) a stored outgoing row was SENT with, recovered from
     *  its absolute deadline so a resend puts the same instruction back on the
     *  wire. Without this a message that failed and retried would land on the
     *  recipient as a permanent one, in a thread the sender had set to
     *  five minutes, and only the retry would give it away.
     *
     *  `expiresAt` was `(sentAt / 1000) * 1000 + ttl * 1000`, so the difference
     *  is the ttl less a sub-second remainder: ceil recovers it exactly.
     *  A row already past its deadline resends with the floor of one second
     *  rather than with nothing — a retry is not the moment to turn somebody's
     *  disappearing message into a permanent one, and the sweeper takes both
     *  copies within the second anyway. */
    private fun resendTtl(msg: ChatMessage): Int? {
        val exp = msg.expiresAt ?: return null
        return kotlin.math.ceil((exp - msg.sentAt) / 1000.0).toInt().coerceAtLeast(1)
    }

    /** Rebuild the wire envelope for a stored outgoing message (resend). */
    private fun resendEnvelope(msg: ChatMessage): Envelope {
        val ttl = resendTtl(msg)
        // The ORIGINAL anchor, not this instant: a resend is the same message
        // making a second attempt, not a new one, and re-dating it would hand
        // the recipient a fresh lifetime for words the sender already started
        // the clock on.
        val ts = ttl?.let { msg.sentAt / 1000 }
        return when {
        msg.kind == "photo" && msg.mediaId != null && msg.mediaKey != null ->
            Envelope.Photo(msg.id, msg.mediaId, msg.mediaKey, msg.body.ifEmpty { null }, msg.spoiler, msg.albumId, ttl, ts)
        msg.kind == "file" && msg.mediaId != null && msg.mediaKey != null ->
            Envelope.File(msg.id, msg.mediaId, msg.mediaKey, msg.fileName ?: "file", msg.fileMime ?: "application/octet-stream", msg.fileSize ?: 0L, msg.body.ifEmpty { null }, ttl, ts)
        msg.kind == "voice" && msg.mediaId != null && msg.mediaKey != null ->
            Envelope.Voice(msg.id, msg.mediaId, msg.mediaKey, (msg.durationSec ?: 0).toDouble(), ttl, ts)
        msg.kind == "video" && msg.mediaId != null && msg.mediaKey != null ->
            Envelope.Video(msg.id, msg.mediaId, msg.mediaKey, msg.thumbB64 ?: "", (msg.durationSec ?: 0).toDouble(), msg.body.ifEmpty { null }, msg.spoiler, msg.albumId, ttl, ts)
        msg.kind == "location" && msg.lat != null && msg.lng != null ->
            Envelope.Location(msg.id, msg.lat, msg.lng, msg.body.ifEmpty { null }, ttl, ts)
        msg.kind == "poll" -> app.rcq.android.model.PollContent.fromJson(msg.body)?.let {
            Envelope.Poll(msg.id, it.pollId, it.question, it.options, it.singleChoice, it.anonymous)
        } ?: Envelope.Text(msg.id, msg.body, null, ttl, ts)
        else -> Envelope.Text(msg.id, msg.body, null, ttl, ts)
        }
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
            // Whatever is STILL failed after this pass (or failed while we
            // were resending) keeps a timer on it - see armFailedRetryTimer.
            armFailedRetryTimer()
        }
    }

    /** Timer companion to [retryFailedSends] (#814). The reconnect hook above
     *  only fires when the SOCKET died and came back - but on a flickering
     *  link the socket often survives while an HTTP send dies, so the message
     *  sat red until the user tapped it. While any FAILED row exists, retry
     *  every 30s (only when the channel claims to be up; a retry into a dead
     *  network would just re-fail and burn battery). The envelope UUID is
     *  stable, so a retry that half-landed before is deduped by the receiver.
     *  One job, self-terminating: when nothing is FAILED any more it ends,
     *  and the next failure arms a fresh one. */
    private var failedRetryJob: Job? = null

    private fun armFailedRetryTimer() {
        if (failedRetryJob?.isActive == true) return
        val anyFailed = _messages.value.values.flatten().any { it.fromMe && it.state == DeliveryState.FAILED } ||
            _groupMessages.value.values.flatten().any { it.fromMe && it.state == DeliveryState.FAILED }
        if (!anyFailed) return
        failedRetryJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val failed = (_messages.value.values.flatten() + _groupMessages.value.values.flatten())
                    .filter { it.fromMe && it.state == DeliveryState.FAILED }
                if (failed.isEmpty()) return@launch
                if (!connected.value) continue
                for (m in failed) runCatching { resend(m) }
            }
        }
    }

    /** Connect-to-web: seal THIS account into the one-time relay slot [token]
     *  for the web client whose ephemeral X25519 pubkey is [webPubB64] (both
     *  read from the scanned `rcq://link` QR). The web opens the sealed blob
     *  with its ephemeral private key and logs in as this same identity. The
     *  blob carries the recovery material (keys + session token), so the caller
     *  MUST confirm with the user first — it hands web access to the account. */
    suspend fun linkWeb(token: String, webPubB64: String, clientLabel: String = "Web") {
        val uin = store.uin ?: error("not registered")
        // Mint a SEPARATE session token for the web device (revocable on its
        // own, and it flips the account to multi-device → server serves v=1).
        // The web carries this, not the phone's token. [clientLabel] ("Desktop"
        // /"Web") comes from the scanned QR's `c` param so the Linked-devices
        // list names it correctly.
        val jwt = api.linkDevice(clientLabel).token.ifEmpty { error("device link failed") }
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
        // A new install of OUR account is about to register its key slot:
        // the cached carbon list is stale from here on.
        ownDeviceListChanged(linked = true)
        // The scan starts on the Linked Devices screen but the confirm dialog
        // (and this call) live in MainActivity, so the screen never heard that
        // the link went through and kept showing the old list.
        if (_devices.value != null) runCatching { refreshDevices() }
    }

    /** Linked web sessions, for the Linked Devices screen. null = never loaded
     *  (the screen shows its spinner); the list is kept HERE rather than in the
     *  screen so a `device_linked`/`device_revoked` socket event can refresh it
     *  while the user is looking at it — signing out on the desktop used to
     *  leave the phone listing it as connected until the screen was closed and
     *  reopened. */
    private val _devices = MutableStateFlow<List<RcqApi.DeviceInfo>?>(null)
    val devices: StateFlow<List<RcqApi.DeviceInfo>?> = _devices.asStateFlow()

    /** Pull the registry. Throws on failure so the screen can show its retry
     *  state rather than an empty list that looks like "no devices". */
    suspend fun refreshDevices() {
        _devices.value = api.listDevices()
    }

    /** Disconnect (revoke) a linked web session, then re-read the registry so
     *  the row disappears even if our own announcement never arrives. */
    suspend fun revokeDevice(deviceId: String) {
        api.revokeDevice(deviceId)
        runCatching { refreshDevices() }
    }

    /** The account's own KEY SLOTS: every install holding encryption keys of
     *  its own — the phone, browsers, the console client. Distinct from the
     *  QR-link registry above, and the one list a recovery-phrase login
     *  cannot stay out of (#643). Second half is OUR slot id, for the "this
     *  device" marker. Throws on failure (screen shows its error state). */
    suspend fun keySlots(): Pair<List<RcqApi.PeerDeviceRow>, Int?> = withContext(Dispatchers.IO) {
        val me = store.uin ?: return@withContext emptyList<RcqApi.PeerDeviceRow>() to null
        // `own`: the island (2026.08.23.5) serves the slot labels only to the
        // owner authenticating about their own account; the anonymous Stage 3
        // form of this lookup would name every slot "unnamed" with the wrong
        // glyph (founder batch 21.08, item 12, all over again).
        api.fetchPeerDevices(me, own = true).devices.sortedBy { it.device_id } to myDeviceIdOrNull()
    }

    /** Retire one of the account's key slots (пункт 13). Throws on failure;
     *  a cooldown refusal surfaces as an IOException whose message carries
     *  the island's `revoke_cooldown` body for the screen to name. */
    suspend fun revokeKeySlot(deviceId: Int) = api.revokeKeySlot(deviceId)

    /** Bumped when the island announces `device_slot_revoked` (a key slot of
     *  this account was retired, here or on another device), so the Linked
     *  Devices screen re-reads the slot list while it is on screen. */
    private val _keySlotsChanged = MutableStateFlow(0)
    val keySlotsChanged: StateFlow<Int> = _keySlotsChanged.asStateFlow()

    /** Bumped when the island announces `news_posted` (A4): an open news
     *  screen re-fetches on the tick, and the home dot goes live through
     *  [refreshNewsBadge] instead of waiting for the next appear. */
    private val _newsFeedChanged = MutableStateFlow(0)
    val newsFeedChanged: StateFlow<Int> = _newsFeedChanged.asStateFlow()

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

    /** One sealed ciphertext and the recipient install it was encrypted for.
     *  A null [deviceId] is the unaddressed copy every island has always
     *  routed to all of an account's devices. */
    private data class SealedCopy(val deviceId: Int?, val payload: String)

    /** The sealed copies of one envelope plus whether they cover EVERY install
     *  the recipient runs. A device we could not seal to is a device that will
     *  never see this message, and a send that reports "delivered" over it is
     *  the same silent loss as not sending at all — only harder to notice. */
    private data class SealedFanout(val copies: List<SealedCopy>, val complete: Boolean)

    /**
     * Encrypt [env] to [toUin], negotiating v=2 forward secrecy: when we've
     * bootstrapped a libsignal identity AND a session with the peer exists or
     * can be established, send v=2 (Double Ratchet); otherwise fall back to
     * v=1, which every account supports. Any v=2 failure degrades to v=1
     * rather than breaking the send — v=2 is strictly additive.
     *
     * A v=2 ratchet belongs to ONE PAIR of devices, so the peer gets one copy
     * per install they run: each is a separate session and a separate
     * ciphertext, addressed with [SealedCopy.deviceId]. v=1 seals to the
     * account's messaging key, which every install of the account holds, so it
     * stays a single unaddressed copy — and so does a peer whose island has no
     * device registry to ask.
     *
     * Called ONCE per logical send (not inside [withRetry]) so a retry resends
     * the identical ciphertext bytes. That is required for ratchet
     * correctness: libsignal emits a self-contained PreKeySignalMessage on
     * every send until the peer first replies, so re-POSTing the same bytes is
     * always safe (the recipient dedups), whereas re-encrypting would advance
     * the ratchet on each attempt.
     */
    private suspend fun encryptFor(toUin: Int, env: Envelope): SealedFanout {
        val me = store.uin ?: error("not registered")
        val recipientPub = recipientKey(toUin)
        // Our own id has to be KNOWN before a ratchet may carry it: the
        // recipient files the session under the id the message names, so
        // sending as the primary and turning out to be a secondary a moment
        // later strands every session we seeded meanwhile. v=1 seals to the
        // account key that every install of the account holds, so it is the
        // right thing to send while the answer is still outstanding.
        val mine = myDeviceIdOrNull()
        if (v2OutboundEnabled && mine != null && signalStores.hasLocalIdentity() && toUin !in noV2Peers) {
            val devices = peerDevices(toUin)
            if (devices == null) {
                // The island never said which installs this peer runs. Guessing
                // "one" is what loses the message: a v=2 copy is opened by the
                // one device it was sealed to and by nobody else, so a peer
                // with a second install would hear nothing on it. v=1 seals to
                // the account key every install holds, so the send falls
                // through to it rather than claiming a fan-out it never made.
                android.util.Log.w("RCQsignal", "device list for $toUin unavailable; sending v1")
            } else if (devices.isNotEmpty()) {
                val now = System.currentTimeMillis()
                // Who may arm the silence probe at all.
                //
                // ⚠ PEERS only, never our own carbon: probing one of our OWN
                // linked sessions would re-read a bundle for an install we are
                // not waiting on.
                //
                // ⚠ And only for an envelope that EARNS an answer — a stored
                // message, which the recipient receipts back. A read receipt,
                // a reaction, an edit or a visit owes nothing in return, and
                // since every message we RECEIVE makes us send a receipt of
                // our own, arming on those made "armed and never cleared" the
                // steady state of every conversation.
                val probePeer = toUin != me && isCarbonable(env)
                val copies = devices.mapNotNull { dev ->
                    // The silence probe (see awaitingReplySince): a device that
                    // has answered NOTHING for two minutes of active sending
                    // gets its bundle re-read, and its session rebuilt only if
                    // the identity behind it CHANGED (SignalSession.probeSession
                    // explains why a blind rebuild loses messages). Throttled
                    // per device, so a quiet peer costs one bundle read every
                    // half hour and nothing else.
                    val probeKey = "$toUin:$dev"
                    val waitingSince = if (probePeer) awaitingReplySince[probeKey] else null
                    val probeDue = waitingSince != null && now - waitingSince > peerSilenceMs &&
                        now - (lastSilenceProbeAt[probeKey] ?: 0L) > silenceProbeMinIntervalMs
                    if (probeDue) {
                        val result = SignalSession.probeSession(
                            signalStores, api, toUin, dev, peerDeviceIdentity["$toUin:$dev"],
                        )
                        // The throttle is spent on a probe that actually READ
                        // something. An unreachable island must not buy the
                        // peer half an hour of not being checked.
                        if (result != SignalSession.ProbeResult.UNREACHABLE) {
                            lastSilenceProbeAt[probeKey] = now
                        }
                        // A rebuilt session starts a fresh conversation with
                        // that install: the old clock is meaningless.
                        if (result == SignalSession.ProbeResult.REBUILT) awaitingReplySince.remove(probeKey)
                    }
                    val usable = SignalSession.ensureSession(signalStores, api, toUin, dev) {
                        // The island has no such install: the list this id
                        // came from is stale, so the next send re-reads it.
                        peerDeviceCache.remove(toUin)
                    }
                    if (!usable) return@mapNotNull null
                    runCatching {
                        SealedCopy(dev, SignalSession.encrypt(signalStores, env, recipientPub, toUin, me, dev, mine))
                    }.onSuccess {
                        // Armed AFTER a successful seal: from here this device
                        // owes us a receipt, and hearing nothing for long
                        // enough is what makes the probe worth running.
                        if (probePeer) awaitingReplySince.putIfAbsent(probeKey, now)
                    }.onFailure {
                        android.util.Log.w("RCQsignal", "v2 encrypt failed for $toUin/$dev: ${it.message}")
                    }.getOrNull()
                }
                if (copies.isNotEmpty()) {
                    if (copies.size != devices.size) {
                        android.util.Log.w(
                            "RCQsignal",
                            "fan-out to $toUin covers ${copies.size} of ${devices.size} devices",
                        )
                    }
                    return SealedFanout(copies, copies.size == devices.size)
                }
                // Not one of their devices could be sealed to: same situation
                // as a peer with no bundle at all.
                noV2Peers.add(toUin)
            } else if (SignalSession.ensureSession(signalStores, api, toUin)) {
                runCatching {
                    // The island ANSWERED that it keeps no device registry, so
                    // this peer has the one install every island has always
                    // had: the single unaddressed copy reaches all of it.
                    return SealedFanout(
                        listOf(SealedCopy(null, SignalSession.encrypt(signalStores, env, recipientPub, toUin, me, ownDeviceId = mine))),
                        complete = true,
                    )
                }.onFailure {
                    android.util.Log.w("RCQsignal", "v2 encrypt failed for $toUin, falling back to v1: ${it.message}")
                }
            } else {
                // Peer has no bundle (or it's unreachable): stop re-probing it
                // this session, just use v=1.
                noV2Peers.add(toUin)
            }
        }
        // v=1 seals to the account's messaging key, which every install of the
        // account holds: one copy, and it reaches all of them.
        return SealedFanout(
            listOf(SealedCopy(null, SealedSender.encryptV1(env, recipientPub, me, signingPriv(), signingPub(), serverHost()))),
            complete = true,
        )
    }

    /**
     * POST one sealed copy per recipient device. Aggregate result: `queued`
     * once any copy was stored, `delivered` only when the fan-out was WHOLE —
     * every install sealed to and every copy accepted — and one of them landed
     * in a live socket. A partial fan-out reports the weaker state on purpose:
     * the message did reach somebody, so failing the whole send would be a lie
     * too, but a device that got no copy is one this send never delivered to
     * and the tick above the bubble must not say otherwise.
     *
     * Throws only when EVERY copy failed — a peer with a phone online and a
     * desktop that is gone has still been written to. A copy that failed while
     * others got through is NOT dropped: it goes to [retryMissedCopies], and if
     * even that gives up, [onCopiesLost] runs so the row can go red. An install
     * that never receives the message and never will is an ordinary failed
     * send, and the user is the only one who can still fix it (by resending).
     */
    private suspend fun sendSealedCopies(
        toUin: Int,
        fanout: SealedFanout,
        envelopeType: String = "message",
        onCopiesLost: (() -> Unit)? = null,
    ): RcqApi.SendResponse {
        var delivered = false
        var queued = false
        var sent = 0
        var last: Exception? = null
        val missed = ArrayList<SealedCopy>()
        for (c in fanout.copies) {
            try {
                val resp = withRetry { api.sendSealed(toUin, c.payload, envelopeType, c.deviceId) }
                delivered = delivered || resp.delivered
                queued = queued || resp.queued
                sent++
            } catch (e: Exception) {
                last = e
                // A device that has been revoked answers 404. Drop the cached
                // list so the next send addresses only the ones still there —
                // and do not chase a copy for an install that no longer exists.
                if (c.deviceId != null && e.message?.startsWith("HTTP 404") == true) {
                    peerDeviceCache.remove(toUin)
                } else {
                    missed.add(c)
                }
            }
        }
        if (sent == 0) throw last ?: IllegalStateException("nothing sent to $toUin")
        val whole = fanout.complete && sent == fanout.copies.size
        if (!whole) {
            android.util.Log.w("RCQsignal", "partial fan-out to $toUin: $sent of ${fanout.copies.size} copies posted")
        }
        if (missed.isNotEmpty()) retryMissedCopies(toUin, missed, envelopeType, onCopiesLost)
        return RcqApi.SendResponse(delivered && whole, queued)
    }

    /**
     * Post the copies a send could not place, in the background, with widening
     * gaps. The ciphertext is reused byte for byte: re-encrypting would advance
     * the ratchet, while a repeat of the same blob is free (the recipient dedups
     * by envelope id) — so the only cost of trying again is the request itself.
     *
     * [onLost] runs when a copy has outlived every round. It is the last word
     * on that message for the install it was addressed to, so the caller uses
     * it to fail the row rather than leave a message showing SENT that one of
     * the recipient's devices will never see.
     */
    private fun retryMissedCopies(
        toUin: Int,
        copies: List<SealedCopy>,
        envelopeType: String,
        onLost: (() -> Unit)?,
    ) {
        scope.launch(Dispatchers.IO) {
            var pending = copies
            repeat(COPY_RETRY_ROUNDS) { round ->
                delay(COPY_RETRY_BASE_MS * (round + 1))
                val still = ArrayList<SealedCopy>(pending.size)
                for (c in pending) {
                    try {
                        api.sendSealed(toUin, c.payload, envelopeType, c.deviceId)
                    } catch (e: Exception) {
                        // 404 = that install has been revoked meanwhile. There
                        // is nobody left to miss this copy, so it is dropped
                        // instead of counted as lost.
                        if (c.deviceId != null && e.message?.startsWith("HTTP 404") == true) {
                            peerDeviceCache.remove(toUin)
                        } else {
                            still.add(c)
                        }
                    }
                }
                pending = still
                if (pending.isEmpty()) return@launch
            }
            android.util.Log.w("RCQsignal", "gave up on ${pending.size} copies to $toUin")
            onLost?.invoke()
        }
    }

    /** Which install of this account we are, or null while [SignalBootstrap]
     *  has not yet got the answer out of the server. Null is not the same as
     *  "the primary": the two are told apart wherever guessing wrong would
     *  destroy the other install's mail. */
    private fun myDeviceIdOrNull(): Int? = runCatching { signalStores.deviceId() }.getOrNull()

    /** This install's own libsignal device id, reading an unresolved one as
     *  the primary — the id every build in the field asserts and the one the
     *  island assumes when nothing says otherwise. */
    private fun myDeviceId(): Int = myDeviceIdOrNull() ?: SealedSender.PRIMARY_DEVICE_ID

    /** The libsignal devices of [uin], briefly cached. EMPTY when the island
     *  answered that it has no device registry, which is the caller's signal to
     *  send exactly one unaddressed copy the way it always has; NULL when it
     *  did not answer at all, which is not the same thing and must not be read
     *  as "one install".
     *  Our OWN device is left out of our own list: a carbon is for the other
     *  installs, not for the one composing it. */
    private suspend fun peerDevices(uin: Int): List<Int>? {
        val now = System.currentTimeMillis()
        peerDeviceCache[uin]?.let { (goodUntil, devices) -> if (now < goodUntil) return devices }
        val self = if (uin == store.uin) myDeviceId() else null
        val answered = try {
            val rows = api.fetchPeerDevices(uin).devices
            for (r in rows) r.signal_identity_key?.let { ik -> peerDeviceIdentity["$uin:${r.device_id}"] = ik }
            rows.map { it.device_id }
        } catch (e: Exception) {
            // 404 IS an answer: the island has no device registry and never
            // will within the TTL. Anything else — a timeout, a dead relay —
            // is not, and remembering it would hold this peer on the
            // single-copy fallback while one of their installs hears nothing.
            if (e.message?.startsWith("HTTP 404") != true) return null
            emptyList()
        }
        val devices = answered.filter { it > 0 && it != self }.sorted()
        // Each entry gets its own expiry: the base TTL plus or minus a random
        // share of the jitter, so lists fetched together are not re-read
        // together (a burst of lookups at a fixed period is a signature).
        // Our OWN list right after a link is the exception: it is re-read
        // often for a while, see [ownDeviceListChanged].
        val jitter = (Math.random() * 2 - 1) * PEER_DEVICES_JITTER_MS
        val ttl = if (self != null && now < ownListShortUntil) OWN_DEVICES_SHORT_TTL_MS
                  else PEER_DEVICES_TTL_MS + jitter.toLong()
        peerDeviceCache[uin] = (now + ttl) to devices
        return devices
    }

    /** Until when our OWN device list is re-read on the short TTL. */
    @Volatile private var ownListShortUntil = 0L

    /** Our own key-slot list changed, or is about to: a link went through
     *  (here, or on another session of ours: `device_linked`), or a slot was
     *  retired. Drop the cached list so the next carbon fan-out re-reads it.
     *  After a LINK the next readings are also held briefly: `device_linked`
     *  marks the QR link, and the new install registers its key slot only
     *  once it has opened the blob and booted, seconds to a minute later. A
     *  list re-read in between would miss the slot for the full TTL, and
     *  everything typed here meanwhile would never reach the new device
     *  unless it sent first. */
    private fun ownDeviceListChanged(linked: Boolean) {
        store.uin?.let { peerDeviceCache.remove(it) }
        if (linked) ownListShortUntil = System.currentTimeMillis() + OWN_DEVICES_SHORT_WINDOW_MS
    }

    private suspend fun sendEnvelope(env: Envelope, id: String, toUin: Int) {
        // ⚠⚠ A duress session sends NOTHING, and must not look like it tried.
        //
        // It already sent nothing — the decoy's contacts carry an empty
        // identityKey, so `encryptFor` below threw and the row went to FAILED —
        // but that is a RED CROSS in the thread, on the first message anyone
        // types. "Send something" is the cheapest test a coercer can run, and
        // the decoy failed it every time. (Our own article says the send is
        // imitated locally; it was not, and that is how a reader found it.)
        //
        // Stored as SENT, which is what a message to somebody who is offline
        // looks like forever: one tick, no error, no second tick promised. The
        // row lives in the decoy's own encrypted database like every other
        // message it shows, so it survives leaving the chat and coming back.
        if (app.rcq.android.security.DuressGate.isActive) {
            updateMessageState(id, toUin, DeliveryState.SENT)
            return
        }
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
            if (!ok) notePeerLivenessAfterFailure(toUin)
            return
        }
        try {
            val fanout = encryptFor(toUin, env)
            // ⚠ A NOTE goes out as "carbon", not "message" (#599). It is
            // addressed to our own number, which is what puts it on our other
            // devices, and the island cannot tell it from a stranger's letter —
            // sealed sender means it never learns who sent what — so it pushed
            // it and the phone rang for something its owner had just typed.
            // "carbon" is already outside the island's pushable set and already
            // routed live by every client, so this needs no new wire type and
            // nothing in the field has to update to understand it.
            val etype = if (toUin == store.uin) "carbon" else "message"
            val resp = sendSealedCopies(toUin, fanout, envelopeType = etype) {
                // One of the peer's installs was never written to, and no round
                // of retries changed that: that install will never see this
                // message. Shown as an ordinary failed send — red cross, and a
                // resend available — because the user is now the only one who
                // can get it there.
                updateMessageState(id, toUin, DeliveryState.FAILED)
            }
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
            // Only when nothing rescued it: a copy that landed on another home
            // is a delivered message, not a refusal to explain.
            lastSendRefusal = if (rescued > 0) null else e.message
            updateMessageState(id, toUin, if (rescued > 0) DeliveryState.SENT else DeliveryState.FAILED)
            if (rescued == 0) notePeerLivenessAfterFailure(toUin)
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
            ownHost = serverHost(),
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
        val gid = target.groupId
        if (gid != null) addGroupReaction(gid, target.id, me, newAsset)
        else addPeerReaction(target.peerUin, target.id, me, newAsset)
        // On OUR scope, not the caller's — the caller is rememberCoroutineScope()
        // in ChatScreen, and the reaction is already drawn. Backing out of the
        // chat right after tapping used to cancel the send, leaving a reaction
        // that existed on this device and nowhere else (#521 is the same bug
        // one menu item down, where it cost a delete instead of a heart).
        scope.launch {
            if (gid != null) fanOutControl(gid, env) else sendControl(target.peerUin, env)
            // Echo to your OWN other devices (linked web / second phone) so a
            // reaction made here also shows there. Sealed to your own identity;
            // the receiver resolves the target message by id across all
            // threads. The origin device re-receives it but applies
            // idempotently (no-op).
            sendControl(me, env)
        }
    }

    /** Retract [target] for everyone (iOS delete-for-everyone). Allowed for the
     *  author, OR (in a group) a moderator: the owner, an admin, or a member
     *  the owner granted the `delete` cap (founder batch 21.08, item 3; web
     *  precedent: Chat.tsx deleteAsModerator). Recipients re-check the same
     *  rule on receipt, so this button grants nothing the group did not
     *  already grant. */
    suspend fun sendDeleteForEveryone(target: ChatMessage) {
        val gid = target.groupId
        // Saved Messages: the thread IS my own number, so every row in it is
        // mine whichever side it was filed on. Notes that arrived before the
        // #599 fix are still `fromMe = false` on disk, and without this they
        // would fall into the moderator branch below, find no group, and return
        // — leaving the note undeleted even locally.
        val mine = target.fromMe || (gid == null && target.peerUin == store.uin)
        if (!mine) {
            // Someone else's message: only a moderator may retract it, and my
            // own cap lives in the roster, so ask for it before deciding — a
            // moderator whose roster has not arrived would silently fail.
            // Author deletes skip this entirely; there is nothing to look up
            // about my own message, and in a 1981-member group this fetch is
            // seconds of it.
            if (gid == null) return
            ensureRoster(gid)
            val g = group(gid)
            val me = store.uin
            val canModerate = g != null && me != null && g.moderator(me)
            if (!canModerate) return
        }
        val env = Envelope.delete(target.id)
        // Locally FIRST, and the fan-out on OUR scope, not the caller's (#521,
        // "в группе rcq beta пытаюсь удалить своё сообщение у всех — не
        // удаляет"). Both halves of that report come from the same ordering:
        //
        //  * The message stayed on screen until the fan-out returned. In RCQ
        //    Beta that is 1184 X25519 seals and a ~1.3 MB upload — the ten to
        //    fifteen seconds measured in #465 — so "delete" looked like it had
        //    done nothing, and the only thing that visibly worked was the
        //    delete-for-me below it.
        //  * The caller is `rememberCoroutineScope()` in ChatScreen. Leaving
        //    the chat inside that window cancelled the coroutine, so nothing
        //    was ever sent AND the message came back. Nothing in the retraction
        //    belongs to a screen that is already gone.
        deleteLocal(target)
        scope.launch { pushRetraction(target, env, gid) }
    }

    /** The wire half of a retraction: fan out, mirror to my own devices, and
     *  put the message back if nothing left the device. Split out of
     *  [sendDeleteForEveryone] so a whole album can be retracted ONE AT A TIME
     *  (#831). Ten parallel fan-outs in a room the size of the beta one is ten
     *  times 1184 seals and ~13 MB racing each other up one socket; sequential
     *  is slower to finish and the only version that does not melt the link.
     *  Callers own the scope and the local delete, exactly as before. */
    private suspend fun pushRetraction(target: ChatMessage, env: Envelope, gid: Int?) {
        run {
            val sent = runCatching {
                if (gid != null) fanOutControl(gid, env) else sendControl(target.peerUin, env)
            }.getOrDefault(false)
            // Mirror the retraction to our OWN other devices (the fan-out
            // skips self); foreign groups excluded, same guard as sendEdit.
            if (sent && (gid == null || gid >= 0)) {
                sendMessageCarbon(env, toPeer = if (gid == null) target.peerUin else null, toGroup = gid)
            }
            if (!sent) {
                // Nothing left the device after the retries, so the message is
                // still on everyone else's screen and only gone from mine. Put
                // it back rather than leave the two out of step: seeing it
                // return says "that did not work, try again", which is the
                // truth, where a silent success would have been a lie.
                android.util.Log.w("RCQgroup", "delete-for-everyone did not reach the island for ${target.id}; restoring")
                restoreAfterFailedDelete(target)
            }
        }
    }

    /** Retract a whole album (#831): "приходится удалять их по одной".
     *
     *  Same permission rule as one message, asked ONCE — every row of an album
     *  has the same author and the same room, so re-deriving it per item would
     *  only re-fetch the roster. Everything vanishes locally at once, which is
     *  what the user asked for, and the wire half then runs one item at a time
     *  through [pushRetraction] (see the note there about ten parallel
     *  fan-outs). A failure restores only the item that failed, so a partial
     *  send is visible instead of pretending the whole batch went.
     */
    fun deleteAlbumForEveryone(items: List<ChatMessage>) {
        if (items.isEmpty()) return
        val first = items.first()
        val gid = first.groupId
        val mine = first.fromMe || (gid == null && first.peerUin == store.uin)
        scope.launch {
            if (!mine) {
                if (gid == null) return@launch
                ensureRoster(gid)
                val g = group(gid)
                val me = store.uin
                if (g == null || me == null || !g.moderator(me)) return@launch
            }
            // Local first, all of it: the point of the item is that the batch
            // goes in one action.
            items.forEach { deleteLocal(it) }
            items.forEach { m -> pushRetraction(m, Envelope.delete(m.id), m.groupId) }
        }
    }

    /** Undo the optimistic half of a retraction that never made it out.
     *  `honourTombstones = false` because [deleteLocal] wrote one moments ago
     *  and this is the same action being reversed — not the offline queue
     *  resurrecting something behind the user's back, which is what the guard
     *  exists to stop. */
    private fun restoreAfterFailedDelete(msg: ChatMessage) {
        if (!db.insert(msg, honourTombstones = false)) return
        val gid = msg.groupId
        if (gid != null) {
            val cur = _groupMessages.value.toMutableMap()
            cur[gid] = ((cur[gid] ?: emptyList()) + msg).sortedBy { it.sentAt }
            _groupMessages.value = cur
        } else {
            val cur = _messages.value.toMutableMap()
            cur[msg.peerUin] = ((cur[msg.peerUin] ?: emptyList()) + msg).sortedBy { it.sentAt }
            _messages.value = cur
        }
    }

    /** Replace the body of [target] (text only, author only) and tell the
     *  other side(s) via an `edit` envelope. */
    suspend fun sendEdit(target: ChatMessage, newText: String) {
        // text OR a captioned media row (#739): for photo/video/file the body
        // IS the caption, and Envelope.edit replaces a body by id everywhere.
        val editable = target.kind == "text" || target.kind == "photo" || target.kind == "video" || target.kind == "file"
        if (!target.fromMe || !editable || newText.isBlank()) return
        val env = Envelope.edit(target.id, newText)
        val gid = target.groupId
        if (gid != null) editInFlow(_groupMessages, gid, target.id, newText)
        else editInFlow(_messages, target.peerUin, target.id, newText)
        // Same reasoning as sendReaction above: the new text is already in the
        // bubble and in the database, so the send must not die with the screen
        // that started it.
        scope.launch {
            if (gid != null) fanOutControl(gid, env) else sendControl(target.peerUin, env)
            // Mirror to our OWN other devices — the fan-out above skips self.
            // Same foreign-group guard as fanOutGroup: an alias id (< 0) is
            // meaningless on another of our devices.
            if (gid == null || gid >= 0) sendMessageCarbon(env, toPeer = if (gid == null) target.peerUin else null, toGroup = gid)
        }
    }

    /** Acknowledge inbound 1:1 messages from [peer] with a read receipt,
     *  unless the user set read receipts to "nobody".
     *
     *  "Read" means SEEN: the chat screen calls this with the ids of the
     *  rows whose whole height has been on screen (the same mark that moves
     *  the unread badge), debounced, as the reader scrolls. It used to fire
     *  for the whole thread the moment the chat opened and again for every
     *  message that arrived while it was open, whatever was on screen, so a
     *  reader parked three screens up in the history "read" everything
     *  below (#707) and every open re-sent receipts for the entire history
     *  (the in-memory ledger forgot them on restart). An acked inbound row is
     *  now marked READ in the store, which is the ledger that survives a
     *  restart; the state of an inbound row is rendered nowhere, ticks are
     *  drawn on own messages only. */
    fun sendReadReceipts(peer: Int, only: Collection<String>) {
        if (readReceiptsVisibility == "nobody") return
        val want = only.toHashSet()
        val ids = (_messages.value[peer] ?: return)
            .filter { !it.fromMe && it.state != DeliveryState.READ && it.id in want }
            .map { it.id }
            .filterNot { ackedReads.contains(it) }
        if (ids.isEmpty()) return
        // Claimed in memory first so a second pass during the send does not
        // send them again; released if the send fails, so a later pass does.
        ackedReads.addAll(ids)
        scope.launch {
            if (!sendControl(peer, Envelope.readReceipt(ids))) {
                ackedReads.removeAll(ids.toSet())
                return@launch
            }
            // Only a receipt that left is recorded: one transaction on this
            // thread, never a write per row on the caller's.
            runCatching { db.updateStates(ids, DeliveryState.READ) }
            val idSet = ids.toHashSet()
            _messages.update { cur ->
                val list = cur[peer] ?: return@update cur
                val next = cur.toMutableMap()
                next[peer] = list.map { m -> if (!m.fromMe && m.id in idSet) m.copy(state = DeliveryState.READ) else m }
                next
            }
        }
    }

    /** Inbound DELIVERY receipt: flip our own sent messages from SENT to
     *  DELIVERED once [peer]'s device reports holding them.
     *
     *  ⚠ Never downgrades. A READ receipt can arrive first (they had the chat
     *  open when it landed), and a delivery receipt for the same id must not
     *  walk the bubble back a state. */
    private fun applyDeliveredReceipt(peer: Int, ids: List<String>) {
        val idSet = ids.toHashSet()
        val cur = _messages.value.toMutableMap()
        val list = cur[peer] ?: return
        var changed = false
        val updated = list.map { m ->
            if (m.fromMe && m.state == DeliveryState.SENT && idSet.contains(m.id)) {
                changed = true
                db.updateState(m.id, DeliveryState.DELIVERED)
                m.copy(state = DeliveryState.DELIVERED)
            } else m
        }
        if (changed) { cur[peer] = updated; _messages.value = cur }
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

    /** Declared OUTER envelope_type for a control envelope — mirrors iOS
     *  MessageService.envelopeType(for:). The server routes the opaque payload
     *  regardless, but gates push on this label (_PUSHABLE_TYPES), so sending
     *  reactions/reads/edits/deletes as the default "message" raises false
     *  "New message" banners on every offline receiver.
     *
     *  ⚠ STAGED, and [TYPED_CONTROL_SENDS] is the switch. The server echoes this
     *  label as the LIVE WS event name, and Android only started accepting the
     *  full set in this version: a v0.75-or-older peer drops anything outside
     *  message/system/gmsg and picks it up on its next queue drain, which runs
     *  on connect. Flipping the senders before receivers have spread would make
     *  a delete-for-everyone sit visible on an un-updated phone until it
     *  reconnects, which is a worse bug than the banner it fixes. Turn this on
     *  once v0.76+ is the norm; iOS already accepts every type it receives. */
    private fun envelopeTypeFor(env: Envelope): String = when (env) {
        is Envelope.Delete -> "delete"
        is Envelope.ReadReceipt -> "read"
        // ⚠ Deliberately labelled "read" on the OUTER envelope, not a new type.
        // The outer label decides two things: whether the island pushes (it does
        // not for "read") and whether a client routes the packet live. A brand
        // new label would be routed by nobody until every client in the field
        // updated, which for a receipt means the tick stays broken for exactly
        // the people whose clients are oldest. The INNER kind is "delivered" and
        // that is what carries the meaning.
        is Envelope.DeliveredReceipt -> "read"
        is Envelope.Reaction -> "reaction"
        is Envelope.Edit -> "edit"
        is Envelope.Visit -> "visit"
        is Envelope.SecureScreen, is Envelope.ScreenshotTaken -> "secscreen"
        // A missed-call marker (#678) is labelled like a receipt on the OUTER
        // envelope, which is the label the island does not push for. That is
        // deliberate: the call is already over, so a wake would ring nothing
        // and a "new message" banner would name it wrong. It waits in the
        // queue, and the drain that runs when the app is next opened files the
        // row and raises the missed-call notification there, which is the
        // moment the person actually wanted to be told.
        //
        // ⚠ ONLY this one signal. Every other CallSignal is a live cross-island
        // signal that goes out through CrossIslandSender.deliverCall with its
        // own type, and never reaches this function. But labelling the whole
        // class "read" here would be one refactor away from silencing a
        // cross-island call offer, so the test names the signal.
        is Envelope.CallSignal -> if (env.sig == "call_missed") "read" else "message"
        else -> "message"
    }.takeIf { TYPED_CONTROL_SENDS } ?: "message"

    /** Encrypt + send a control envelope (e.g. a reaction) to one peer.
     *  Reuses the send-retry but tracks no delivery state. */
    /** @return true when the island took it. Callers that only fire and forget
     *  can ignore it; a retraction cannot — it has already removed the message
     *  from this device and needs to know whether anyone else heard. */
    private suspend fun sendControl(toUin: Int, env: Envelope): Boolean =
        runCatching {
            sendSealedCopies(toUin, encryptFor(toUin, env), envelopeType = envelopeTypeFor(env))
        }.isSuccess

    /** Message kinds we mirror to the user's other devices via a carbon.
     *  Reactions sync through their own self-echo; poll/receipts don't sync.
     *  Edit and Delete joined 2026-08-21: the group fan-out skips self by
     *  design, so without a carbon an edit made here never reached the
     *  account's other devices — the founder edited on the desktop and this
     *  phone kept the old text forever (and vice versa). */
    private fun isCarbonable(env: Envelope): Boolean = when (env) {
        is Envelope.Text, is Envelope.Photo, is Envelope.Video,
        is Envelope.Voice, is Envelope.File, is Envelope.Location,
        is Envelope.Edit, is Envelope.Delete -> true
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
            sendSealedCopies(me, encryptFor(me, carbon), envelopeType = "carbon")
        }
    }

    /** Tell my OTHER devices that I read a thread (megalist A2). The marker
     *  rides inside the same self-carbon a sent message uses, so the island
     *  sees the sealed self-addressed blob it has always seen. The outer type
     *  is "read", which the island already files as EPHEMERAL: it reaches my
     *  other devices live and through their queues, but never pushes a "new
     *  message" banner to my own sleeping phone, and "read" is a token the
     *  island reads on every peer receipt anyway. Nothing new is learned,
     *  which was the condition this feature had to meet.
     *
     *  Never for Saved Messages (I am the peer there) and never for a foreign
     *  group (its id is per-device, the §5c limit carbons already document).
     *  Best effort: a lost marker only means the other device clears its badge
     *  when it is next opened, exactly as it did before. */
    private suspend fun sendReadMarker(toPeer: Int?, toGroup: Int?) {
        val me = store.uin ?: return
        if (toPeer != null && toPeer == me) return
        if (toGroup != null && groups.value.firstOrNull { it.id == toGroup }?.host != null) return
        runCatching {
            val carbon = Envelope.Carbon(to = toPeer, gid = toGroup, env = Envelope.ReadMark(System.currentTimeMillis()))
            sendSealedCopies(me, encryptFor(me, carbon), envelopeType = "read")
        }
    }

    /** Fan a control envelope (delete / edit / reaction) out to the group.
     *  Mirrors fanOutGroup's transport split: capable members get ONE
     *  sender-keys gmsg broadcast, legacy members a per-member sealed copy.
     *  The old always-per-member path needed every member's identity key, so
     *  in a members-hidden group or a huge roster the payload list came up
     *  empty and the delete/edit silently reached NOBODY while the author's
     *  copy vanished locally (GitHub issue #6, "Delete for everyone makes
     *  nothing") — and a 1000+ member group cost one X25519 seal per member
     *  for every reaction. The gmsg receive path has routed control envelopes
     *  since sender keys landed, on all three clients. Also routes through
     *  groupCtx so a FOREIGN group's control lands on ITS island with guest
     *  creds instead of misrouting to ours. */
    private suspend fun fanOutControl(groupId: Int, env: Envelope): Boolean = withContext(Dispatchers.IO) {
        ensureRoster(groupId)
        val ctx = groupCtx(groupId)
        val me = ctx.myUin.takeIf { it != 0 } ?: return@withContext false
        val group = group(groupId) ?: return@withContext false
        runCatching {
            val sendable = group.members.filter { it.uin != me && it.identityKey.isNotEmpty() }
            val capable = if (ctx.host == null) sendable.filter { it.senderKeys } else emptyList()
            if (capable.isNotEmpty()) {
                val step = SenderKeyStore.prepareOwnSend(me, ctx.gid, capable.map { it.uin })
                val gmsg = SenderKeys.sealGmsg(env, ctx.gid, step.kid, step.epoch, step.index, step.mk, signingPriv())
                // Chain distribution first, so no recipient sees an unknown kid.
                val skdmTargets = capable.filter { it.uin in step.needDistribution }
                if (skdmTargets.isNotEmpty()) {
                    val skdmEnv = Envelope.Skdm(ctx.gid, step.kid, step.epoch, step.index, step.ckAtI)
                    val skdmPayloads = skdmTargets.mapNotNull { m ->
                        runCatching {
                            RcqApi.GroupPayload(m.uin, SealedSender.encryptV1(skdmEnv, Base64.decode(m.identityKey, Base64.NO_WRAP), me, signingPriv(), signingPub(), ctx.host ?: serverHost()))
                        }.getOrNull()
                    }
                    if (skdmPayloads.isNotEmpty()) runCatching { ctx.api.sendGroupSealed(ctx.gid, skdmPayloads, envelopeType = "skdm") }
                }
                withRetry { ctx.api.sendGroupBroadcast(ctx.gid, gmsg, envelopeType = envelopeTypeFor(env)) }
                SenderKeyStore.markDistributed(me, ctx.gid, skdmTargets.map { it.uin })
                SenderKeyStore.advanceOwn(me, ctx.gid)
            }
            // Legacy members (or a foreign group / no capable cohort) keep the
            // per-member sealed copy. Same one-bad-key isolation as fanOutGroup.
            val broadcast = capable.isNotEmpty()
            val rest = if (broadcast) sendable.filter { !it.senderKeys } else sendable
            val sealPayloads = {
                rest.mapNotNull { m ->
                    runCatching {
                        RcqApi.GroupPayload(m.uin, SealedSender.encryptV1(env, Base64.decode(m.identityKey, Base64.NO_WRAP), me, signingPriv(), signingPub(), ctx.host ?: serverHost()))
                    }.getOrNull()
                }
            }
            if (rest.isNotEmpty()) {
                if (broadcast) {
                    // The broadcast above has already reached every client that
                    // can read one; this tail is the old clients, and as of
                    // #465 it does NOT ride the caller's clock. Reactions,
                    // edits and deletes pay the same price an ordinary message
                    // did — 1184 seals and ~1.3 MB in RCQ Beta, of which the
                    // island keeps 51 — and nothing looks at the answer. What
                    // the wait bought was a window in which leaving the chat
                    // cancelled a change already on screen.
                    scope.launch {
                        val payloads = sealPayloads()
                        if (payloads.isNotEmpty()) {
                            runCatching { withRetry { ctx.api.sendGroupSealed(ctx.gid, payloads, envelopeType = envelopeTypeFor(env)) } }
                                .onFailure { android.util.Log.w("RCQgroup", "group $groupId: legacy control fan-out failed for ${payloads.size} member(s)", it) }
                        }
                    }
                } else {
                    // No broadcast happened (a small group where nobody has
                    // advertised sender keys, or a foreign group): this IS the
                    // send, so it stays on the caller's clock and its outcome
                    // is the outcome. Detaching it here would have told a
                    // retraction "sent" when nothing had left the device.
                    val payloads = sealPayloads()
                    if (payloads.isNotEmpty()) withRetry { ctx.api.sendGroupSealed(ctx.gid, payloads, envelopeType = envelopeTypeFor(env)) }
                }
            }
        }.isSuccess
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

    /** The decrypted bytes if they are already in memory, without suspending.
     *
     *  For the avatars: their composable used to start every appearance at
     *  `null` and load asynchronously, so leaving a screen and coming back drew
     *  the status glyph first and popped the picture in a moment later, even
     *  though the bytes had been sitting in this cache the whole time. It read
     *  as "the avatars reload every time", which is exactly what it looked
     *  like. A synchronous peek gives the first frame the picture it already
     *  has, and the suspending path still covers a genuine miss. */
    fun cachedImage(mediaId: String?): ByteArray? =
        mediaId?.takeIf { it.isNotEmpty() }?.let { imageCache.get(it) }

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

    // ── large media: streamed in, streamed out ──────────────────────────
    //
    // #691 item 3, "long videos do not download". [fetchImage] above is the
    // only download path there has ever been, and it holds the WHOLE file
    // four times over: the blob, the copy `MediaCrypto.open` makes of the
    // ciphertext, the provider's own buffer (AES-GCM cannot release a byte
    // before it has checked the tag at the end), and the plaintext. Past
    // roughly a 60-100 MB clip that is an OutOfMemoryError, and because the
    // body sits inside `runCatching {}.getOrNull()`, and Kotlin's
    // `runCatching` catches Throwable, Errors included, so the user got a
    // silent null. Not a failed download: nothing at all.
    //
    // Everything below is the way out: an RCQM1 container
    // (crypto/MediaStream.kt) sealed and opened one megabyte at a time. It is
    // chosen by SIZE on the way out and by MAGIC on the way in, so photos,
    // voice notes and ordinary clips keep the byte-identical single-seal
    // layout every shipped client already reads, and only files past the
    // point where the old path was going to die anyway take the new one.

    /** Encrypted containers too big for the 200 MB blob cache above, which is
     *  sized for pictures and would evict a film the moment it landed. Its own
     *  directory, its own ceiling, its own eviction. */
    private val mediaBigDir: java.io.File by lazy {
        java.io.File(appCtx.cacheDir, "media-big").apply { mkdirs() }
    }
    private val mediaBigCapBytes = 1536L * 1024 * 1024
    private fun mediaBigFile(mediaId: String): java.io.File =
        java.io.File(mediaBigDir, mediaId.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    /** The most this client will pull down for one media blob.
     *
     *  ⚠⚠ Two ceilings, and the smaller wins. The island's own cap is what it
     *  will ACCEPT, and a self-hosted island sets that to whatever it likes;
     *  the cache cap is what this phone will HOLD. Taking the island's number
     *  alone means a hostile or simply generous island decides how many
     *  gigabytes land in a cache directory on somebody's phone, and
     *  [trimMediaBigCache] cannot undo it, because the file it must not evict
     *  is the oversized one. Bounding the download is the only place this can
     *  be settled. */
    private val mediaDownloadCeilingBytes: Long
        get() = minOf(mediaMaxBlobBytes, mediaBigCapBytes)

    /** Evict oldest containers until under the cap. [keep] is never evicted:
     *  it is the one that was just downloaded or is being played right now,
     *  and oldest-first would otherwise delete a 1.4 GB film to make room for
     *  itself.
     *
     *  ⚠ That exemption is only safe because the download is bounded by
     *  [mediaDownloadCeilingBytes], which is at most the cap: a kept file can
     *  never be so big that the loop below empties the whole directory and
     *  still cannot get under it. */
    private fun trimMediaBigCache(keep: java.io.File? = null) {
        runCatching {
            val files = mediaBigDir.listFiles()?.toList() ?: return
            var total = files.sumOf { it.length() }
            if (total <= mediaBigCapBytes) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= mediaBigCapBytes) break
                if (keep != null && f.absolutePath == keep.absolutePath) continue
                total -= f.length()
                f.delete()
            }
        }
    }

    /** Plaintext bytes past which an outgoing file is sealed chunk by chunk
     *  instead of all at once.
     *
     *  ⚠ Deliberately ABOVE the point where the old path works, not below it.
     *  A new container is a blob an older client cannot open, so the switch
     *  has to land where the alternative is not "an older client opens it"
     *  but "nobody opens it, on any client, including the sender's own": at
     *  96 MB the single-seal path asks for something like 380 MB of transient
     *  heap on top of a running Compose app, on a heap that is 256-512 MB
     *  total. Below this line nothing changes for anyone; above it, this is
     *  the difference between a video and a silence. Around 45 s of 1080p
     *  phone footage, or six minutes at 720p. */
    private val streamThresholdBytes = 96L * 1024 * 1024

    /** Legacy single-seal blobs bigger than this are not opened, they are
     *  reported. Same arithmetic in the other direction: attempting it is an
     *  OOM, and an OOM here reads as "nothing happened". */
    private val legacyInMemoryCeilingBytes = 96L * 1024 * 1024

    /** The island's `/media` blob cap, as it advertises it. Used to refuse an
     *  oversize video BEFORE uploading it rather than after. The whole point
     *  of a pre-flight is that the person is not asked to spend twenty minutes
     *  of uplink to be told no. 512 MB is what every RCQ island has shipped
     *  with; an island that does not advertise the field keeps that guess. */
    @Volatile
    var mediaMaxBlobBytes: Long = 512L * 1024 * 1024
        private set

    /** Nothing on this device could hold it, and nothing will try. Carries the
     *  numbers so the message can name them. */
    class MediaTooLargeException(val bytes: Long, val maxBytes: Long) :
        java.io.IOException("media is $bytes bytes, island takes at most $maxBytes")

    /** A media blob ready to be used, without the caller having had to know in
     *  advance how heavy it was. */
    sealed class MediaSource {
        /** Small enough to hold, and held: the existing behaviour, byte for
         *  byte, for everything that already worked. */
        class InMemory(val bytes: ByteArray) : MediaSource()

        /** An RCQM1 container on disk plus the key that opens it. The bytes
         *  stay encrypted at rest and are decrypted a chunk at a time as they
         *  are played, so, as before, no plaintext copy of a private video is
         *  ever written anywhere. */
        class Streamed(val file: java.io.File, val key: ByteArray, val plainLen: Long) : MediaSource()

        /** A single-seal blob from before this existed, too heavy to open on
         *  this device. There is no way to read one incrementally: the tag is
         *  at the end and the provider will not release plaintext before it. */
        class TooLargeLegacy(val bytes: Long) : MediaSource()
    }

    /** How far the media currently being downloaded has got, `mediaId to
     *  0f..1f`, or null when nothing is downloading. A 300 MB clip used to
     *  fetch behind a bubble that showed absolutely nothing, which is half of
     *  what "does not download" meant. */
    private val _mediaDownload = MutableStateFlow<Pair<String, Float>?>(null)
    val mediaDownload: StateFlow<Pair<String, Float>?> = _mediaDownload.asStateFlow()

    /**
     * Fetch a media blob in whatever shape it can actually be used in.
     *
     * Unlike [fetchImage] this never assumes the file fits: the download goes
     * to disk as it arrives, and only then is the container inspected. Callers
     * that can handle both shapes (the video path) use this; photo, voice and
     * document bubbles are unchanged and still go through [fetchImage].
     */
    suspend fun fetchMediaSource(
        mediaId: String,
        mediaKey: String,
        host: String? = null,
    ): MediaSource? = withContext(Dispatchers.IO) {
        imageCache.get(mediaId)?.let { return@withContext MediaSource.InMemory(it) }
        val key = runCatching { Base64.decode(mediaKey, Base64.NO_WRAP) }.getOrNull() ?: return@withContext null

        // Either cache may already hold the encrypted bytes: the small one from
        // a fetchImage that ran before this path existed, the large one from a
        // previous open of this very clip.
        val cached = listOf(mediaBigFile(mediaId), mediaDiskFile(mediaId))
            .firstOrNull { it.isFile && it.length() > 0L }
        val blobFile = cached ?: run {
            val dest = mediaBigFile(mediaId)
            val client = if (host != null) RcqApi("https://$host") else api
            // Throttled to whole percents. The reader calls back every 64 KB,
            // which for a 300 MB clip is five thousand writes to a flow the UI
            // recomposes from; a bar cannot show more than a hundred positions
            // anyway.
            var lastPct = -1
            val ok = runCatching {
                client.getBlobToFile(mediaId, dest, mediaDownloadCeilingBytes) { got, total ->
                    if (total > 0) {
                        val pct = ((got * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            _mediaDownload.value = mediaId to pct / 100f
                        }
                    }
                }
            }
            _mediaDownload.value = null
            if (ok.isFailure) {
                android.util.Log.w("RCQmedia", "streamed fetch of $mediaId failed", ok.exceptionOrNull())
                return@withContext null
            }
            trimMediaBigCache(keep = dest)
            dest
        }

        if (MediaStream.looksChunked(blobFile)) {
            val len = runCatching { MediaStream.Reader(blobFile, key).use { it.plainLen } }.getOrElse {
                android.util.Log.w("RCQmedia", "unreadable container for $mediaId", it)
                return@withContext null
            }
            return@withContext MediaSource.Streamed(blobFile, key, len)
        }

        if (blobFile.length() > legacyInMemoryCeilingBytes) {
            return@withContext MediaSource.TooLargeLegacy(blobFile.length())
        }
        val plain = runCatching { MediaCrypto.open(blobFile.readBytes(), key) }.getOrNull()
            ?: return@withContext null
        // ⚠ Only if it FITS. The cache is at most 96 MB and evicts by total
        // size, so putting something near that in it throws every picture in
        // the thread away to hold one clip that is about to be played once.
        if (plain.size <= 16 * 1024 * 1024) imageCache.put(mediaId, plain)
        MediaSource.InMemory(plain)
    }

    /** [uploadBlobFor] for a source too big to hold: same routing decision
     *  (own island, or deposit-the-blob to a cross-island peer's island plus a
     *  best-effort copy on ours), with the sealing done on the socket.
     *
     *  [openSource] is called once per attempt, so it must open a FRESH stream
     *  every time: the route ladder may run the call again through the tunnel,
     *  and the two cross-island deposits are two separate uploads. */
    private suspend fun uploadStreamedFor(
        toUin: Int,
        openSource: () -> java.io.InputStream,
        plainLen: Long,
        key: ByteArray,
    ): RcqApi.UploadResponse {
        preflightBlobSize(plainLen)
        // ⚠⚠ Same rule as every other upload: a duress session puts nothing on
        // any island, and returns an id so the decoy's own bubble still looks
        // like a sent one.
        if (app.rcq.android.security.DuressGate.isActive) {
            // ⚠ Unlike the in-memory path this seeds no local copy, because
            // there is no copy: the decoy's own bubble draws its poster from
            // the row and a tap on play reports a fetch that did not come
            // back, which is what a real session on a bad line looks like
            // too, so it tells an observer nothing they could act on.
            return RcqApi.UploadResponse(java.util.UUID.randomUUID().toString().replace("-", ""), 0)
        }
        val ci = CrossIslandStore.findByUin(toUin)
            ?: return api.uploadBlobStreaming(openSource, plainLen, key, ::reportUpload)
        return withContext(Dispatchers.IO) {
            val mediaId = java.util.UUID.randomUUID().toString().replace("-", "")
            // The peer-island copy is REQUIRED: that is the one they read.
            if (!CrossIslandSender.depositBlobStreaming(ci.host, mediaId, openSource, plainLen, key, ::reportUpload)) {
                throw java.io.IOException("cross-island media deposit failed (${ci.host})")
            }
            // Our own island's copy, for carbons and for our own bubble to play
            // from. Best-effort, exactly as the in-memory path has it.
            runCatching { api.putBlobStreaming(mediaId, openSource, plainLen, key) }
            RcqApi.UploadResponse(mediaId, 0)
        }
    }

    /** §5c: media in a group lives on the GROUP's island. */
    private suspend fun uploadStreamedForGroup(
        groupId: Int,
        openSource: () -> java.io.InputStream,
        plainLen: Long,
        key: ByteArray,
    ): RcqApi.UploadResponse {
        preflightBlobSize(plainLen)
        if (app.rcq.android.security.DuressGate.isActive) {
            return RcqApi.UploadResponse(java.util.UUID.randomUUID().toString().replace("-", ""), 0)
        }
        return groupCtx(groupId).api.uploadBlobStreaming(openSource, plainLen, key, ::reportUpload)
    }

    /** Refuse an oversize blob here, where nothing has been sent yet, instead
     *  of at byte 536,870,913 of an upload the person has been watching for
     *  twenty minutes. */
    private fun preflightBlobSize(plainLen: Long) {
        val encoded = MediaStream.blobLength(plainLen)
        if (encoded > mediaMaxBlobBytes) throw MediaTooLargeException(plainLen, mediaMaxBlobBytes)
    }

    /** True when a video of this size must take the streamed path. */
    fun needsStreamedSend(plainLen: Long): Boolean = plainLen > streamThresholdBytes

    /** [sendVideo] for a clip too big to hold in memory: read, sealed and
     *  uploaded a megabyte at a time straight from the picked content URI.
     *
     *  ⚠ No `imageCache.put` here, because there is no plaintext to put: the
     *  sender's own bubble plays by fetching the container back from the
     *  island, the same way the recipient does. That is the price of never
     *  holding the film, and it is the right way round: a copy of it is
     *  already sitting in the gallery this was picked from. */
    suspend fun sendVideoStreamed(
        toUin: Int,
        openSource: () -> java.io.InputStream,
        plainLen: Long,
        thumbB64: String,
        durationSec: Int,
        caption: String?,
        spoiler: Boolean = false,
        albumId: String? = null,
    ) {
        val ttl = peerTtl(toUin)
        val key = MediaCrypto.newKey()
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadStreamedFor(toUin, openSource, plainLen, key)
        val env = Envelope.video(upload.media_id, keyB64, thumbB64, durationSec.toDouble(), caption, spoiler, albumId, ttl)
        val now = System.currentTimeMillis()
        store(ChatMessage(env.id, toUin, true, caption ?: "", now, DeliveryState.SENDING, kind = "video", mediaId = upload.media_id, mediaKey = keyB64, durationSec = durationSec, thumbB64 = thumbB64, spoiler = spoiler, albumId = albumId, expiresAt = expiryFor(env.ttl, env.ts, now)))
        sendEnvelope(env, env.id, toUin)
    }

    /** Group twin of [sendVideoStreamed]. */
    suspend fun sendGroupVideoStreamed(
        groupId: Int,
        openSource: () -> java.io.InputStream,
        plainLen: Long,
        thumbB64: String,
        durationSec: Int,
        caption: String?,
        spoiler: Boolean = false,
        albumId: String? = null,
    ) {
        val ttl = groupTtl(groupId)
        val key = MediaCrypto.newKey()
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val upload = uploadStreamedForGroup(groupId, openSource, plainLen, key)
        val env = Envelope.video(upload.media_id, keyB64, thumbB64, durationSec.toDouble(), caption, spoiler, albumId, ttl)
        sendGroupEnvelope(groupId, env, env.id, caption ?: "", kind = "video", mediaId = upload.media_id, mediaKey = keyB64, durationSec = durationSec, thumbB64 = thumbB64, spoiler = spoiler, albumId = albumId)
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

    /** [depositAtMs]: see [ingestGroup]. */
    /** File one sealed 1:1 row. Returns null when the row is DONE WITH, or a
     *  short tag naming how it failed in a way a later delivery may survive.
     *
     *  ⚠⚠ The answer used to be thrown away, and so did the caller's. The
     *  legacy queue drain acked every row it had been handed, whether or not
     *  anything was written, and the island DELETES what is acked. So a row
     *  that failed here was gone for good, most easily when the PIN lock had
     *  closed the database under a drain that kept running. That is #835: a
     *  person's OWN messages reach their phone only as a carbon on this queue,
     *  while everybody else's arrive through the room log, where the ack has
     *  always been tied to the result. Own messages vanished, everyone else's
     *  did not, which is exactly what the two screenshots showed.
     *
     *  Same contract as [ingestGmsg] and the room log's onRow. */
    private fun ingest(payloadB64: String, depositAtMs: Long? = null): String? {
        // Last line of defence: in a migrated decoy session `db` is the duress
        // store, and a real message written there is a real message lost.
        if (duressViewUp) return "duress"
        // A closed database is not a decryption problem and must not look like
        // one: bail before touching it so the row stays on the island.
        if (!::db.isInitialized) return "db_closed"
        var why: String? = null
        runCatching {
            val dec = decryptInbound(payloadB64)
            // Removed contacts are silently dropped — sealed sender means
            // the server can't filter by sender, so we gate on receipt.
            // ⚠ Never ourselves: a carbon always arrives from our own uin, and
            // the code that CLEARS the removed flag skips our own uin, so one
            // stray entry would silently kill every self-carbon forever.
            if (dec.senderUin != store.uin &&
                (LocalStores.isRemoved(dec.senderUin) || LocalStores.isBlocked(dec.senderUin))
            ) return@runCatching
            // §5d cross-island call signaling rides sealed envelopes (kind
            // "call") — route to the call state machine, never the message
            // store, and never the request quarantine (signals are ephemeral).
            // Only an ACCEPTED cross-island contact may ring us; a stale offer
            // (old ts — offline-queue drains deliver hours-old rows) is filed
            // as a missed call instead of ringing.
            (dec.envelope as? Envelope.CallSignal)?.let { cs ->
                // ⚠ Before the same-island early return below. A missed call is
                // the one call signal that arrives as an envelope from our OWN
                // island: the caller leaves it when the island told them we
                // were not reachable at all, so that a phone whose app was
                // force-stopped still learns it was called (#678/#686). It
                // never rings, because the call is long over: it files the row
                // and raises the same missed-call notification the live path
                // would have raised.
                if (cs.sig == "call_missed") {
                    // ⚠⚠ NONE OF THE CONSENT GATES BELOW COVER THIS BRANCH.
                    // It sits above the same-island early return on purpose,
                    // and above it there is nothing but the removed/blocked
                    // test: the cross-island accepted-contact gate, the
                    // Variant A quarantine and the same-island stranger gate
                    // are all further down and not one of them is reached. A
                    // marker is an ORDINARY SEALED DEPOSIT, so any number on
                    // any island can compose one, and believing it costs a row
                    // in a thread, an unread badge and a missed-call banner.
                    // So the question those gates ask is asked here, in the
                    // one form that fits a call.
                    if (!mayLeaveCallMarker(dec.senderUin, dec.senderHost)) return@runCatching
                    // ⚠ A marker with no call id has no dedupe key at all, and
                    // acks are best-effort: the same envelope redelivered would
                    // file the row again, every time, for ever.
                    if (cs.cid.isEmpty()) return@runCatching
                    // The dedupe the whole design rests on. This marker is a
                    // guess by the CALLER about what we do not know, and the
                    // island can be wrong about us: a registration that had
                    // gone stale, or our socket coming back inside the same
                    // second, means we may have rung and filed this very call
                    // ourselves. The call id is the only thing our row and this
                    // envelope share, so it is what decides. The probe here
                    // only saves the work; what makes it SAFE against the two
                    // ingest threads is that the row id is derived from the
                    // call id, so the insert itself collapses the repeat (see
                    // [logCallHistory]).
                    // ⚠ `!::db.isInitialized` too, and before the
                    // notification. The store is closed while the account is
                    // PIN-locked, and [logCallHistory] silently writes nothing
                    // in that state, so without this the banner announced a
                    // missed call that left no row behind it.
                    if (!::db.isInitialized || haveCallRow(cs.cid)) return@runCatching
                    val filed = logCallHistory(
                        dec.senderUin,
                        fromMe = false,
                        text = appCtx.getString(
                            if (cs.data["media"] == "video") app.rcq.android.R.string.call_missed_video_push
                            else app.rcq.android.R.string.call_missed_push,
                        ),
                        missed = true,
                        // ⚠ RAILED, like every other stamp that came off a
                        // wire somebody else composed. `Envelope.parse`
                        // defaults a missing or non-numeric `ts` to 0, and 0
                        // filed a row at the epoch: [store] sorts the thread by
                        // sentAt, so it landed below the entire history where
                        // nobody will ever scroll, with a permanent unread
                        // badge above it and nothing visible to clear it. Same
                        // three tiers, in the same order, as the disappearing
                        // anchor in [disappearAnchorMs].
                        startedAt = callStartedAtMs(cs.ts, depositAtMs),
                        callId = cs.cid,
                    )
                    // Only when a row actually landed. The insert is what
                    // decides, so a losing thread in the race above gets false
                    // here, and a banner for a call with no row behind it is
                    // the bug the PIN-lock guard already exists to stop.
                    if (!filed) return@runCatching
                    app.rcq.android.push.Push.showMissedCall(
                        appCtx,
                        peerUin = dec.senderUin,
                        nickname = contactName(dec.senderUin),
                        video = cs.data["media"] == "video",
                    )
                    return@runCatching
                }
                // ⚠ The Cloudflare FRONT is OUR island by another road, and a
                // build that stamps `cdn.rcq.app` instead of the island is not
                // a cross-island caller. This gate used to name serverHost()
                // alone while the message gate below (and §5e/§5f) named both,
                // so such a sender's TEXTS arrived and every one of their call
                // signals was dropped one line later at the CrossIslandStore
                // lookup: they could write but never ring. Same host set
                // everywhere now.
                val host = dec.senderHost
                if (host == null || host in setOf(serverHost(), FRONT_HOST).filter { it.isNotBlank() }) {
                    // Same island (by name, or through the front). Live
                    // signaling rides the WS; a sealed copy exists for exactly
                    // one signal: the `call_end` sent while the sender's
                    // socket was a silently-dead corpse (the notification
                    // hang-up, #724/#730/#733 - see [routeCallSignal]).
                    // Only that one is believed. An offer or ICE from our own
                    // island in an envelope is a replayed antique, and
                    // [handleRemoteEnd]'s call-id check makes the duplicate
                    // from the WS-plus-envelope double send a no-op.
                    if (cs.sig == "call_end") {
                        val sigObj = com.google.gson.JsonObject().apply {
                            addProperty("from_uin", dec.senderUin)
                            addProperty("call_id", cs.cid)
                            cs.data.forEach { (k, v) -> addProperty(k, v) }
                        }
                        calls.onSignal(cs.sig, sigObj)
                    }
                    return@runCatching
                }
                if (CrossIslandStore.get(dec.senderUin, host) == null) return@runCatching
                if (cs.sig == "call_offer" && System.currentTimeMillis() / 1000 - cs.ts > callOfferTtlSec) {
                    // Same dedupe as the marker above: a cross-island offer can
                    // reach us twice (the live drain and a later one), and the
                    // caller may also have deposited a marker for it. Same
                    // requirement of a call id, too, and for the same reason:
                    // without one there is no dedupe key and every redelivery
                    // files the row again.
                    if (cs.cid.isEmpty() || haveCallRow(cs.cid)) return@runCatching
                    // A cross-island offer we only learned about after it went
                    // stale: genuinely missed, so it does count as unread. Its
                    // start is the offer's own timestamp, not now. One label,
                    // not "<media> · missed call" — see CallController.logHistory.
                    logCallHistory(
                        dec.senderUin,
                        fromMe = false,
                        text = appCtx.getString(
                            if (cs.data["media"] == "video") app.rcq.android.R.string.call_missed_video_push
                            else app.rcq.android.R.string.call_missed_push,
                        ),
                        missed = true,
                        // Railed, for the reason spelled out on the marker
                        // above: `ts` is a number a peer put on a wire.
                        startedAt = callStartedAtMs(cs.ts, depositAtMs),
                        callId = cs.cid,
                    )
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
            // §5f cross-island contact requests. Routed here, BEFORE the
            // quarantine below, because a `contactreq` is not a message: it
            // opens a PENDING request in the requests list (where a same-island
            // request appears) and never enters the message store. Same-island
            // adds still go through POST /contacts/request, so an envelope that
            // did not cross an island boundary is ignored.
            (dec.envelope as? Envelope.ContactRequest)?.let { cr ->
                val host = dec.senderHost ?: return@runCatching
                if (host in setOf(serverHost(), FRONT_HOST).filter { it.isNotBlank() }) return@runCatching
                handleContactRequest(dec.senderUin, host, cr)
                return@runCatching
            }
            // §5e cross-island profile refresh. Routed here, BEFORE the
            // quarantine below, for the same reason a `contactreq` is: it is
            // not a message and must never reach the message store or open a
            // request row. It is cosmetic data — from a stranger it is simply
            // DROPPED. Same-island renames still ride the `contact_renamed` WS
            // broadcast, so an envelope that did not cross an island boundary
            // is ignored.
            (dec.envelope as? Envelope.ProfileUpdate)?.let { pu ->
                val host = dec.senderHost ?: return@runCatching
                if (host in setOf(serverHost(), FRONT_HOST).filter { it.isNotBlank() }) return@runCatching
                handleProfileUpdate(dec.senderUin, host, pu)
                return@runCatching
            }
            // Variant A consent: a 1:1 message from an un-accepted CROSS-ISLAND
            // sender (its from_host isn't ours and we haven't added them) is
            // QUARANTINED as a request instead of landing in the chat list.
            // Blocked → hold() drops it. We hold the raw payload so Accept can
            // re-ingest it (now an accepted contact → passes this gate).
            //
            // ⚠ A sender whose client is on the Cloudflare FRONT is on OUR
            // island and only reaches it by a different road. Their build
            // stamped the road (`cdn.rcq.app`) instead of the island, so their
            // messages arrived here looking like they had emigrated, and every
            // recipient who had not already added them quarantined the lot —
            // "why did a friend request come from another island when that
            // account is on this one?". Senders stamp the island now, but the
            // ones still on the old build cannot be fixed from their end, so
            // the front is accepted as local here too.
            val ciHost = dec.senderHost
            val meUin = store.uin ?: 0
            val ownHosts = setOf(serverHost(), FRONT_HOST).filter { it.isNotBlank() }
            if (ciHost != null && ciHost !in ownHosts && dec.senderUin != meUin &&
                CrossIslandStore.get(dec.senderUin, ciHost) == null
            ) {
                CrossIslandRequestsStore.hold(meUin, dec.senderUin, ciHost, payloadB64, ciPreview(dec.envelope))
                refreshCiRequests()
                return@runCatching
            }
            // The same consent gate for OUR OWN island, opt-in (Privacy:
            // strangers go to requests). host "" marks a same-island row in
            // the shared store. Returning here also skips store() below on
            // purpose: a held message must not send the delivery receipt that
            // would confirm to a stranger that it landed in front of a human.
            // The active random-chat peer is exempt; matching with them IS
            // the invitation.
            if ((ciHost == null || ciHost in ownHosts) && dec.senderUin != meUin &&
                dec.senderUin != activeRandomPeer && shouldQuarantineStranger(dec.senderUin, dec.envelope)
            ) {
                CrossIslandRequestsStore.hold(meUin, dec.senderUin, "", payloadB64, ciPreview(dec.envelope))
                refreshCiRequests()
                return@runCatching
            }
            // A thread the user deleted comes back when its peer writes again.
            if (dec.senderUin != meUin) LocalStores.clearRemoved(dec.senderUin)
            val now = System.currentTimeMillis()
            // Random-chat peer: keep the conversation ephemeral (in-memory,
            // never persisted, never on Home). Text only for v=1.
            if (dec.senderUin == activeRandomPeer) {
                (dec.envelope as? Envelope.Text)?.let { appendRandom(ChatMessage(it.id, dec.senderUin, fromMe = false, body = it.text, sentAt = now)) }
                return@runCatching
            }
            when (val env = dec.envelope) {
                is Envelope.Text ->
                    store(ChatMessage(env.id, dec.senderUin, false, env.text, now, replyToSnippet = env.replyTo?.snippet, replyToAuthor = env.replyTo?.authorName, replyToId = env.replyTo?.id, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs)))
                is Envelope.Photo ->
                    store(ChatMessage(env.id, dec.senderUin, false, env.caption ?: "", now, kind = "photo", mediaId = env.mediaId, mediaKey = env.mediaKey, spoiler = env.spoiler, albumId = env.albumId, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs)))
                is Envelope.File ->
                    store(ChatMessage(env.id, dec.senderUin, false, env.caption ?: "", now, kind = "file", mediaId = env.mediaId, mediaKey = env.mediaKey, fileName = env.fileName, fileMime = env.mime, fileSize = env.sizeBytes, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs)))
                is Envelope.Voice ->
                    store(ChatMessage(env.id, dec.senderUin, false, "", now, kind = "voice", mediaId = env.mediaId, mediaKey = env.mediaKey, durationSec = env.durationSec.toInt(), expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs)))
                is Envelope.Video ->
                    store(ChatMessage(env.id, dec.senderUin, false, env.caption ?: "", now, kind = "video", mediaId = env.mediaId, mediaKey = env.mediaKey, durationSec = env.durationSec.toInt(), thumbB64 = env.thumbnailB64, spoiler = env.spoiler, albumId = env.albumId, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs)))
                is Envelope.Location ->
                    store(ChatMessage(env.id, dec.senderUin, false, env.caption ?: "", now, kind = "location", lat = env.lat, lng = env.lng, expiresAt = expiryFor(env.ttl, env.ts, now, depositAtMs)))
                is Envelope.Reaction -> applyReactionByTargetId(env.targetId, dec.senderUin, env.asset)
                is Envelope.Delete -> {
                    // Author-only: a peer can only retract their own message.
                    val t = _messages.value[dec.senderUin]?.firstOrNull { it.id == env.targetId }
                    if (t != null && (fromOwnDevice(dec.senderUin) || !t.fromMe))
                        deleteInFlow(_messages, dec.senderUin, env.targetId)
                }
                is Envelope.Edit -> {
                    val t = _messages.value[dec.senderUin]?.firstOrNull { it.id == env.targetId }
                    if (t != null && (fromOwnDevice(dec.senderUin) || !t.fromMe))
                        editInFlow(_messages, dec.senderUin, env.targetId, env.text)
                }
                is Envelope.ReadReceipt -> applyReadReceipt(dec.senderUin, env.targetIds)
        is Envelope.DeliveredReceipt -> applyDeliveredReceipt(dec.senderUin, env.targetIds)
                is Envelope.Visit -> app.rcq.android.data.VisitStore.record(dec.senderUin, env.atEpochMillis())
                is Envelope.SecureScreen -> {
                    // ★★★ #722. The peer toggled per-conversation screenshot
                    // alerts. This used to write STRAIGHT INTO MY OWN flag and
                    // say nothing, which handed the other side a way to disarm
                    // me: turn the alerts off from their phone, screenshot with
                    // the notice dead on both ends, turn them back on. Nothing
                    // was ever shown, so there was nothing to notice.
                    //
                    // Now their wish lands in a slot of its own. Mine is
                    // untouched, so a remote "off" can no longer lower what I
                    // raised (the alerts are armed while EITHER side wants
                    // them), and every change they make is written into the
                    // thread like any other state change, so the off/shot/on
                    // dance is visible even when it costs them nothing.
                    val thread = LocalStores.peerThread(dec.senderUin)
                    val was = LocalStores.isThreadSecureByPeer(thread)
                    LocalStores.setThreadSecureByPeer(thread, env.on)
                    // ⚠⚠ THE FLAG ALWAYS MOVES; THE ROW DOES NOT. SecureScreen
                    // is control traffic, so it falls straight through the
                    // stranger quarantine by design (there is no message of
                    // ours for it to belong to). The visible row it writes is
                    // NOT control traffic: without this gate anyone who knows
                    // the number could put "#12345 turned on screenshot
                    // alerts" into the chat list of a user who asked for
                    // strangers to wait in the requests list, one row per
                    // flip, having walked round the very list that exists to
                    // hold them. Their bit is still recorded, so the alerts
                    // still arm if we ever do talk to them.
                    if (was != env.on && !isQuarantinedStranger(dec.senderUin)) {
                        val who = _contacts.value.firstOrNull { it.uin == dec.senderUin }?.nickname
                            ?: "#${dec.senderUin}"
                        val text = appCtx.getString(
                            if (env.on) app.rcq.android.R.string.secscreen_peer_on
                            else app.rcq.android.R.string.secscreen_peer_off,
                            who,
                        )
                        // A fresh id: the control envelope carries none, and the
                        // was/is guard already swallows a repeated delivery of
                        // the same state.
                        //
                        // ⚠ `countsUnread = false`: a state change is worth
                        // recording, not worth ringing. It costs the peer one
                        // menu tap, and routed through the badge it would be a
                        // chime, an in-app banner and a full shade notification
                        // PER FLIP, with nothing but their patience limiting
                        // how many they send. The row is there when the chat is
                        // opened, which is what "visible even when it costs
                        // them nothing" needed to mean.
                        store(ChatMessage(
                            java.util.UUID.randomUUID().toString().uppercase(),
                            dec.senderUin, fromMe = false, body = text, sentAt = now, kind = "system",
                        ), countsUnread = false)
                    }
                }
                is Envelope.ScreenshotTaken -> {
                    // Peer took a screenshot in a secure chat — post a notice
                    // with their name (resolved + localized on our side).
                    val name = _contacts.value.firstOrNull { it.uin == dec.senderUin }?.nickname ?: "#${dec.senderUin}"
                    store(ChatMessage(env.id, dec.senderUin, fromMe = false, body = appCtx.getString(app.rcq.android.R.string.secscreen_peer_screenshot, name), sentAt = now, kind = "system"))
                }
                is Envelope.Poll -> Unit       // polls are group-only; ignore in 1:1
                is Envelope.CallSignal -> Unit // intercepted above, never reaches here
                is Envelope.HomeRecord -> Unit // intercepted above, never reaches here
                is Envelope.ContactRequest -> Unit // §5f, intercepted above
                is Envelope.ProfileUpdate -> Unit  // §5e, intercepted above
                is Envelope.Carbon ->
                    // A message I sent from ANOTHER device, echoed to my own uin.
                    // Only honour my own carbon; file the inner message as fromMe
                    // in its destination thread (dedup by id; no badge/sound).
                    if (dec.senderUin == store.uin) storeCarbon(env.to, env.gid, env.env, now, depositAtMs)
                is Envelope.ReadMark -> Unit   // A2 marker only ever arrives WRAPPED in a carbon
                is Envelope.PKey ->
                    // A contact handing us the key to their picture. Filed
                    // against the SEALED sender, never against anything the
                    // wire claimed, or one account could publish a face as
                    // another. Refreshing the roster repaints the avatars.
                    if (LocalStores.putProfileKey(dec.senderUin, env.key)) {
                        scope.launch { runCatching { refreshContacts() } }
                    } else Unit
                is Envelope.PKeyAsk ->
                    // Only the owner can answer this one. Nothing to send if
                    // we never set a picture. Epoch-held like the fan-out in
                    // setOwnAvatar: this reads LocalStores and sends through
                    // the live api, so after a switch it would answer one
                    // account's question with the other account's key, over
                    // the other account's island. See [accountEpoch].
                    // ⚠ Taken HERE, in the synchronous ingest, not inside the
                    // launch: by the time the coroutine is dispatched the
                    // account may already have changed, and the epoch read
                    // there would be the new one.
                    epochNow().let { ep ->
                    scope.launch {
                        runCatching {
                            if (LocalStores.isBlocked(dec.senderUin)) return@runCatching
                            val nowAsk = System.currentTimeMillis()
                            if (nowAsk - (answeredProfileKeyAt[dec.senderUin] ?: 0L) < 6L * 3600_000L) {
                                return@runCatching
                            }
                            answeredProfileKeyAt[dec.senderUin] = nowAsk
                            // Vault-backed, not just the local copy: an
                            // install that never set the picture itself still
                            // has to answer for the account. Read-only, so a
                            // stranger's question can never make us publish a
                            // rival key.
                            val k = ProfileKeyVault.publishedKey(
                                api, store.identityPrivate ?: ByteArray(0),
                            ) ?: return@runCatching
                            if (!stillOn(ep)) return@runCatching
                            sendSealedCopies(
                                dec.senderUin,
                                encryptFor(dec.senderUin, Envelope.PKey(k)),
                                envelopeType = "skdm",
                            )
                        }
                    }
                    }
                is Envelope.GsKey ->
                    // Room state key (stage 6 phase 2). Roster gate: only a
                    // fellow member's key is worth holding; equal-version
                    // replace is the wedge-repair rule from the design doc.
                    if (groups.value.firstOrNull { it.id == env.gid }?.members?.any { it.uin == dec.senderUin } == true) {
                        if (LocalStores.putRoomKey(env.gid, env.ver, env.key, replaceEqual = true)) {
                            scope.launch { runCatching { refreshGroups() } }
                        }
                    } else Unit
                is Envelope.GsKnack ->
                    // Any holder answers. The asker must be in the roster we
                    // can see; the reply is a plain sealed gskey.
                    scope.launch {
                        runCatching {
                            val g = groups.value.firstOrNull { it.id == env.gid } ?: return@runCatching
                            val member = g.members.firstOrNull { it.uin == dec.senderUin } ?: return@runCatching
                            val k = LocalStores.roomKey(env.gid) ?: return@runCatching
                            if (member.identityKey.isNotBlank()) {
                                sendSealedCopies(
                                    dec.senderUin,
                                    encryptFor(dec.senderUin, Envelope.GsKey(env.gid, k.first, k.second)),
                                    envelopeType = "skdm",
                                )
                            }
                        }
                    }
                is Envelope.Skdm -> Unit       // sender-keys distribution is group-only
                is Envelope.Sknack -> Unit     // sender-keys recovery is group-only
                is Envelope.RelayShare ->
                    // In-chat bridge sharing: a contact handed us a relay to
                    // augment our transport pool. Render as a kind="relay" card
                    // the user can Add; never auto-apply. Drop malformed shares.
                    if (ContactRelayStore.relayFromJson(env.relay) != null)
                        store(ChatMessage(env.id, dec.senderUin, false, env.relay.toString(), now, kind = "relay"))
                is Envelope.Unknown -> Unit
            }
        }.onFailure {
            logDecryptFailure(payloadB64, it)
            why = it.javaClass.simpleName
        }
        return why
    }

    /** File a carbon's inner message as a fromMe row in its destination thread
     *  (group [gid] or peer [to]). Mirrors the incoming construction but marks
     *  it ours. store()/storeGroup() dedup by id (INSERT-OR-IGNORE), so the
     *  origin device's own carbon and any queue redelivery are no-ops. */
    private fun storeCarbon(to: Int?, gid: Int?, inner: Envelope, now: Long, depositAtMs: Long? = null) {
        val me = store.uin ?: return
        if (gid != null) {
            when (inner) {
                is Envelope.Text -> storeGroup(ChatMessage(inner.id, 0, true, inner.text, now, kind = "text", groupId = gid, senderUin = me, replyToSnippet = inner.replyTo?.snippet, replyToAuthor = inner.replyTo?.authorName, replyToId = inner.replyTo?.id, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.Photo -> storeGroup(ChatMessage(inner.id, 0, true, inner.caption ?: "", now, kind = "photo", mediaId = inner.mediaId, mediaKey = inner.mediaKey, groupId = gid, senderUin = me, spoiler = inner.spoiler, albumId = inner.albumId, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.File -> storeGroup(ChatMessage(inner.id, 0, true, inner.caption ?: "", now, kind = "file", mediaId = inner.mediaId, mediaKey = inner.mediaKey, fileName = inner.fileName, fileMime = inner.mime, fileSize = inner.sizeBytes, groupId = gid, senderUin = me, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.Voice -> storeGroup(ChatMessage(inner.id, 0, true, "", now, kind = "voice", mediaId = inner.mediaId, mediaKey = inner.mediaKey, durationSec = inner.durationSec.toInt(), groupId = gid, senderUin = me, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.Video -> storeGroup(ChatMessage(inner.id, 0, true, inner.caption ?: "", now, kind = "video", mediaId = inner.mediaId, mediaKey = inner.mediaKey, durationSec = inner.durationSec.toInt(), thumbB64 = inner.thumbnailB64, groupId = gid, senderUin = me, spoiler = inner.spoiler, albumId = inner.albumId, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.Location -> storeGroup(ChatMessage(inner.id, 0, true, inner.caption ?: "", now, kind = "location", lat = inner.lat, lng = inner.lng, groupId = gid, senderUin = me, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                // Control carbons: an edit/retraction made on ANOTHER of our
                // devices targets a row this device already has — apply it,
                // never file it as a new message. The carbon is authenticated
                // as our own account at the call site, so no authority check.
                is Envelope.Edit -> editInFlow(_groupMessages, gid, inner.targetId, inner.text)
                is Envelope.Delete -> deleteInFlow(_groupMessages, gid, inner.targetId)
                // I read this room on another device (A2): drop the badge
                // here too, minus anything that landed after that moment.
                is Envelope.ReadMark -> applyRemoteRead(LocalStores.groupThread(gid), inner.at, gid = gid)
                else -> Unit
            }
        } else if (to != null) {
            when (inner) {
                is Envelope.Text -> store(ChatMessage(inner.id, to, true, inner.text, now, replyToSnippet = inner.replyTo?.snippet, replyToAuthor = inner.replyTo?.authorName, replyToId = inner.replyTo?.id, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.Photo -> store(ChatMessage(inner.id, to, true, inner.caption ?: "", now, kind = "photo", mediaId = inner.mediaId, mediaKey = inner.mediaKey, spoiler = inner.spoiler, albumId = inner.albumId, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.File -> store(ChatMessage(inner.id, to, true, inner.caption ?: "", now, kind = "file", mediaId = inner.mediaId, mediaKey = inner.mediaKey, fileName = inner.fileName, fileMime = inner.mime, fileSize = inner.sizeBytes, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.Voice -> store(ChatMessage(inner.id, to, true, "", now, kind = "voice", mediaId = inner.mediaId, mediaKey = inner.mediaKey, durationSec = inner.durationSec.toInt(), expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.Video -> store(ChatMessage(inner.id, to, true, inner.caption ?: "", now, kind = "video", mediaId = inner.mediaId, mediaKey = inner.mediaKey, durationSec = inner.durationSec.toInt(), thumbB64 = inner.thumbnailB64, spoiler = inner.spoiler, albumId = inner.albumId, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.Location -> store(ChatMessage(inner.id, to, true, inner.caption ?: "", now, kind = "location", lat = inner.lat, lng = inner.lng, expiresAt = expiryFor(inner.ttl, inner.ts, now, depositAtMs)))
                is Envelope.Edit -> editInFlow(_messages, to, inner.targetId, inner.text)
                is Envelope.Delete -> deleteInFlow(_messages, to, inner.targetId)
                // I read this thread on another device (A2).
                is Envelope.ReadMark -> applyRemoteRead(LocalStores.peerThread(to), inner.at, peer = to)
                else -> Unit
            }
        }
    }

    private suspend fun drainQueue() {
        if (duressViewUp) return   // acking the real account's queue from the decoy view loses it
        drainLock.withLock { drainQueueLocked() }
    }

    private suspend fun drainQueueLocked() {
        CrashReporter.crumb(appCtx, "drain_queue")
        // Who this drain is FOR, taken before the first network call. The ack
        // at the end is a second request, and sent through the live `api` it
        // rides whatever token and baseURL the session holds by then: after a
        // switch that is the OTHER account's island, and the ack moves the
        // OTHER account's cursor past rows it never received. See
        // [accountEpoch]; [drainGroupLog] below has carried this shape for
        // longer and is the pattern being copied.
        val ep = epochNow()
        val drainApi = api
        // ── poison-row guard (#616) ──
        // A native crash inside ingest (libsignal decrypt dies with a signal,
        // there is no JVM exception to catch) kills the process BEFORE the ack,
        // so the exact same row comes back on the next launch and the app
        // crash-loops until the queue's 30-day TTL. Mark the row about to be
        // ingested with a SYNCHRONOUS write (commit, not apply — an async write
        // is lost in the very crash it exists to record); a marker still present
        // at the next drain means we died inside that row. Two deaths and the
        // row is acked away unread: losing one envelope beats losing the app.
        val guard = appCtx.getSharedPreferences("rcq_drain_guard", android.content.Context.MODE_PRIVATE)
        guard.getString("attempt", null)?.let { dead ->
            val strikes = guard.getInt("strikes:$dead", 0) + 1
            guard.edit().putInt("strikes:$dead", strikes).remove("attempt").commit()
            android.util.Log.w("RCQdrain", "previous drain died inside queue row $dead (strike $strikes)")
        }
        // ack=1 protocol: the server holds each row until we confirm we ingested
        // it. Collect the ids that made it through ingest, then ack them; a lost
        // response (this fetch's or the ack's) leaves the rows on the server for
        // redelivery instead of silently dropping them. Split direct vs group —
        // the two server tables have independent auto-increment ids that can
        // collide. Ingest is best-effort + dedups by envelope UUID, so acking a
        // row that turned out to be a duplicate is harmless.
        val directIds = ArrayList<Int>()
        val groupIds = ArrayList<Int>()
        // ⚠ The fetch is deliberately OUTSIDE the backlog mark. The mark is a
        // process-wide counter, and it used to be held across this HTTP call —
        // up to a thirty second timeout, three times over — while live socket
        // frames were being ingested concurrently on the reader thread. Every
        // message that merely overlapped a drain was filed as backlog and lost
        // its sound (#480). Marking only the ingest keeps the counter honest:
        // it is raised exactly while backlog is being written.
        val mine = myDeviceIdOrNull()
        // The id the island served this drain under. The ack has to name the
        // SAME one: it advances the cursor over the contiguous prefix of what
        // this device was handed, and a mismatched `dev` puts a sibling's copy
        // in that prefix as a row we never acked — the cursor then stops there
        // for good and the queue never moves again.
        val drainDev = mine ?: SealedSender.PRIMARY_DEVICE_ID
        val rows = drainApi.drainQueue(drainDev)
        // The rows in hand belong to the account we asked for. If that is no
        // longer the account this session serves, they must not be ingested:
        // the message database, the roster and the stores under `ingest` all
        // point at somebody else now.
        if (!stillOn(ep)) return
        asBacklog {
            rows.forEach { q ->
                val payload = q.payload ?: return@forEach
                val toDev = q.to_device_id
                if (toDev != null && toDev != mine) {
                    // Somebody else's copy: it was sealed to another install's
                    // ratchet, so it can never open here. Ack it away instead
                    // of retrying it forever — the addressee drains its own.
                    // While our own id is unresolved there is no telling those
                    // two apart, and acking away the primary's copy is the
                    // very loss this exists to stop: leave the row alone, the
                    // next drain knows which install it is asking for.
                    if (mine != null) {
                        if (q.group_id != null) groupIds.add(q.id) else directIds.add(q.id)
                    }
                    return@forEach
                }
                val rowKey = (if (q.group_id != null) "g" else "d") + ":" + q.id
                if (guard.getInt("strikes:$rowKey", 0) >= 2) {
                    // The marker (not this code) proved the row fatal twice.
                    // Ack it so the server stops redelivering the poison.
                    android.util.Log.w("RCQdrain", "queue row $rowKey skipped after 2 fatal attempts")
                    CrashReporter.crumb(appCtx, "drain_skip_poison")
                    if (q.group_id != null) groupIds.add(q.id) else directIds.add(q.id)
                    return@forEach
                }
                guard.edit().putString("attempt", rowKey).commit()
                // The island's stamp on this row: for a disappearing message
                // from a peer too old to send its own `ts`, the moment it
                // reached the SERVER is much nearer the send than the moment
                // this phone came back online and drained is.
                val depositAt = parseIso(q.received_at)
                // ⚠⚠ READ the answer. All three of these say whether the row is
                // done with (null) or failed in a way a later delivery may
                // survive, and this drain used to throw that away and ack
                // regardless. The island DELETES what is acked, so every such
                // failure was a message destroyed - #835, and the #547/#544
                // class before it. The room log twenty lines below has always
                // done it properly; this is the same rule, same helper.
                val why = when {
                    q.envelope_type == "gmsg" && q.group_id != null -> ingestGmsg(payload, q.group_id, depositAt)
                    q.group_id != null -> ingestGroup(payload, q.group_id, depositAt)
                    else -> ingest(payload, depositAt)
                }
                // Survived — a stale strike from an interrupted PREVIOUS run
                // (the between-rows window marks the row already ingested)
                // must not accumulate toward the skip threshold.
                if (why == null && guard.contains("strikes:$rowKey")) guard.edit().remove("strikes:$rowKey").apply()
                // A row that failed stays on the island and comes back next
                // drain. After a few attempts it is written off, so one poison
                // row cannot freeze the queue behind it forever.
                if (why == null || logRowWrittenOff(guard, rowKey, why)) {
                    if (q.group_id != null) groupIds.add(q.id) else directIds.add(q.id)
                }
            }
            guard.edit().remove("attempt").commit()
        }
        // Best-effort ack. If it fails the server redelivers next drain and the
        // UUID dedupe collapses the repeat, so we never lose and never double.
        // Through the CAPTURED client, and only while the account still holds:
        // an ack is a promise that these rows were filed, and after a switch
        // neither half of that promise is true any more.
        if (stillOn(ep) && !duressViewUp) runCatching { drainApi.ackQueue(directIds, groupIds, drainDev) }
        CrashReporter.crumb(appCtx, "drain_done")
    }

    // ── room log drain (Stage 5 of the core-metadata plan) ──────────

    /** One drain at a time, legacy queue and room log alike. Both write the
     *  poison-row marker of #616, and the marker is only worth anything while
     *  ONE drain owns it: with two running side by side the page end of one
     *  wiped the other's marker (a native death in that row then left no
     *  strike) or the marker named a legacy row while the death happened in a
     *  log row. The start coroutine, a reconnect, the first-time capability
     *  flip in syncGraph() and the fetch after a join all ask for a drain;
     *  they queue up here, and each one finds only what the previous left. */
    private val drainLock = Mutex()

    /** The last log seq this device is known to be level with, per room:
     *  the island's cursor on every fetch, the room's head after a drain that
     *  ran to its end, then advanced by contiguous live frames. The island's
     *  cursor is the authority; this copy exists ONLY so a live `gmsg` can be
     *  acked safely (see [ackLiveGmsg]). Cleared on an account switch:
     *  another account's rooms are other logs. */
    private val groupLogSeq = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** Live-frame acks waiting to go out, max seq per room, coalesced so a
     *  burst (an album, a fast thread) is one POST, not one per frame. */
    private val pendingLogAcks = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val logAckFlushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Drain the room logs this device is behind on, all rooms in one call,
     *  then move the island's cursors past what was persisted.
     *
     *  A post into a room used to be one queue row per member (872 copies of
     *  one blob in RCQ Beta, 79% of the database); on an island that runs
     *  Stage 5 it is ONE row in the room's log, and each device reads it
     *  through its own cursor. The rows are the very same envelopes the legacy
     *  group rows of /messages/queue carried, so they take the same road:
     *  `gmsg` through the sender-keys chain, everything else through the
     *  sealed group ingest, dedupe by message UUID in the store. Nothing the
     *  user sees changes.
     *
     *  ⚠ Dual read. The legacy drain keeps running exactly as before: the 1:1
     *  rows live there, and so does every group row written for this account
     *  before this device read the log (the fetch marks the DEVICE a reader
     *  on the island; the island stops writing legacy rows for the account
     *  once every device that drains its queue is one). Either order is fine;
     *  the UUID dedupe collapses any overlap.
     *
     *  The ack names the max seq per room that went through ingest. Every row
     *  of the page is ingested whatever happened to the ones before it (the
     *  dedupe makes that idempotent); only the ACK stops short of a row that
     *  failed in a way that may pass next time, and stays there for at most
     *  [GroupLogPage.FAIL_DRAINS] drains, after which the row is written off
     *  as unreadable on this device and acked past: one bad row must never
     *  silence a room. A `gmsg` whose chain key has not arrived is HELD by
     *  [ingestGmsg] and counts as done, like in the legacy drain: the SKDM
     *  rides its own row. Skipped entirely on an island that does not
     *  advertise `group_log`: asking it would 404, and its queue already
     *  carries the rooms. */
    private suspend fun drainGroupLog() {
        if (duressViewUp) return   // same hazard as drainQueue: an ack from the decoy view loses the row
        // ⚠⚠ And the entry check alone is not enough: the panic PIN can be
        // entered WHILE a page is being ingested, and from that moment ingest
        // files nothing while the ack below would still tell the island the
        // rows were taken. The host/uin pin used here does not see that (a
        // migrated decoy leaves `store` pointing at the real account); the
        // epoch does, because bindPerAccountStores bumps it.
        val logEpoch = epochNow()
        if (!groupLogReader) return
        drainLock.withLock {
            CrashReporter.crumb(appCtx, "drain_group_log")
            // Poison-row guard (#616): same prefs, same marker, same rule as
            // drainQueue, under an "l:" key. The marker a death leaves behind
            // is read by the legacy drain at the next start, which runs first
            // and books the strike under whichever key it finds; the lock
            // above is what makes that reading sound.
            val guard = appCtx.getSharedPreferences("rcq_drain_guard", android.content.Context.MODE_PRIVATE)
            // Pinned to the account AND island this drain is for: a switch
            // while a page is in flight rebinds `api` and its token, and an
            // ack sent under the new ones would set cursors in the new
            // account's rooms (the ack needs no membership) at seqs that mean
            // nothing to it, possibly past rows it has not read. The api
            // object is captured for the same reason: the ack has to go out
            // under the token the fetch went out under, and the old object
            // keeps it after a switch.
            val drainApi = api
            val drainFor = serverHost() to store.uin
            var pages = 0
            // Page until the island says there is no more. Each page is acked
            // before the next is asked for, so a page that dies mid-way costs
            // only itself on redelivery.
            while (true) {
                if ((serverHost() to store.uin) != drainFor) break
                val page = drainApi.fetchGroupLog()
                if ((serverHost() to store.uin) != drainFor) break
                val acks = GroupLogPage.Acks()
                // Every room the island answered for: remember where the
                // cursor stands even when nothing came back, that is the
                // baseline a live frame is checked against.
                page.cursors.forEach { (gid, seq) -> gid.toIntOrNull()?.let { groupLogSeq[it] = seq } }
                asBacklog {
                    page.rows.forEach { r ->
                        // Between rows: a page is up to five hundred of them.
                        if (!stillOn(logEpoch) || duressViewUp) return@forEach
                        val rowKey = "l:${r.gid}:${r.seq}"
                        // Whatever happens to the row, the cursor moves past
                        // it: an empty row, a poison row, a held row and a
                        // written-off row all count as done, or the room
                        // would stop for good. Only a row whose failure may
                        // pass next time pins the room's ack below itself,
                        // for at most a few drains (see [logRowWrittenOff]).
                        val payload = r.payload
                        if (payload == null) { acks.row(r.gid, r.seq, done = true); return@forEach }
                        if (guard.getInt("strikes:$rowKey", 0) >= 2) {
                            android.util.Log.w("RCQdrain", "log row $rowKey skipped after 2 fatal attempts")
                            CrashReporter.crumb(appCtx, "drain_skip_poison")
                            acks.row(r.gid, r.seq, done = true)
                            return@forEach
                        }
                        guard.edit().putString("attempt", rowKey).commit()
                        // The same dispatch the legacy drain applies to its
                        // group rows: a broadcast opens through the chain,
                        // everything else is a sealed per-member envelope.
                        val why = when (r.envelope_type) {
                            "gmsg" -> ingestGmsg(payload, r.gid, parseIso(r.received_at))
                            else -> ingestGroup(payload, r.gid, parseIso(r.received_at))
                        }
                        if (guard.contains("strikes:$rowKey")) guard.edit().remove("strikes:$rowKey").apply()
                        if (why == null && guard.contains("fail:$rowKey")) guard.edit().remove("fail:$rowKey").apply()
                        acks.row(r.gid, r.seq, done = why == null || logRowWrittenOff(guard, rowKey, why))
                    }
                    guard.edit().remove("attempt").commit()
                }
                if ((serverHost() to store.uin) != drainFor) break
                // The ack is a deletion of the room's past. It must not ride a
                // duress flip or an account rebind that the pin above cannot
                // see.
                if (!stillOn(logEpoch) || duressViewUp) break
                val upto = acks.upto
                val blocked = acks.blocked
                if (upto.isNotEmpty()) {
                    // Persisted (or held, or written off): move the cursors.
                    // An ack that does not land ENDS this drain. The island's
                    // cursor did not move, so the next page would be this
                    // very page again, and re-ingesting it buys nothing; the
                    // next drain (a reconnect, a push wake) starts from the
                    // cursor the island still holds, and the dedupe makes the
                    // repeat free.
                    if (runCatching { drainApi.ackGroupLog(upto) }.isFailure) break
                    upto.forEach { (gid, seq) -> groupLogSeq.merge(gid, seq) { a, b -> maxOf(a, b) } }
                }
                pages++
                if (!page.more) {
                    // The drain ran to its end: every row this member can see
                    // up to the head went through ingest, so the head is what
                    // this device is level with, not the last seq it was
                    // SERVED. The two differ as soon as the room holds one row
                    // sealed to another member (an SKDM to a newcomer, a
                    // legacy copy for an old client): those share the room's
                    // seq axis and are invisible here. A room whose ack is
                    // pinned below a failed row stays where the ack left it.
                    page.heads.forEach { (g, head) ->
                        g.toIntOrNull()?.let { gid ->
                            GroupLogPage.levelAfterDrain(groupLogSeq[gid], head, gid in blocked)?.let { groupLogSeq[gid] = it }
                        }
                    }
                    break
                }
                // A pinned room would be served the same rows on the next
                // page, and `more` with no rows would be an island that cannot
                // hand out its own backlog; neither is worth paging on.
                if (blocked.isNotEmpty() || page.rows.isEmpty() || pages >= 40) break
            }
            CrashReporter.crumb(appCtx, "drain_group_log_done")
        }
    }

    /** Book one failed ingest of a log row, in the drain-guard prefs under
     *  "fail:". True when the row has now failed the same way on
     *  [GroupLogPage.FAIL_DRAINS] drains in a row: it is unreadable on this
     *  device and the ack may move past it. A row that fails in a new way
     *  starts over; one that passes has its count cleared by the caller. */
    private fun logRowWrittenOff(guard: android.content.SharedPreferences, rowKey: String, why: String): Boolean {
        val strike = GroupLogPage.strike(guard.getString("fail:$rowKey", null), why)
        if (strike.writtenOff) {
            guard.edit().remove("fail:$rowKey").apply()
            android.util.Log.w("RCQdrain", "log row $rowKey written off after ${strike.count} drains ($why)")
            CrashReporter.crumb(appCtx, "drain_log_row_written_off")
            return true
        }
        guard.edit().putString("fail:$rowKey", GroupLogPage.encode(strike)).apply()
        return false
    }

    /** The room this account was just put in, or just made: fetch once, now.
     *  The island seeds this device's cursor at the room's head the moment a
     *  reader joins, so nothing depends on this; it is the belt and braces
     *  that puts the cursor there even for a device the island does not know
     *  as a reader yet, before the first post lands instead of at the next
     *  reconnect. Serialised behind whatever drain is running. */
    private fun roomJoined() {
        scope.launch { runCatching { drainGroupLog() } }
    }

    /** A live `gmsg` carried the room's `seq` and went through ingest: tell
     *  the island so the next fetch does not serve it again.
     *
     *  ⚠ Only when it is the NEXT seq after what this device is level with
     *  ([GroupLogPage.liveAckable], which says why a gap is left to the next
     *  drain and why nothing cleverer is possible here). The drain that
     *  follows sets the baseline to the head again, and the dedupe makes the
     *  re-serve free. */
    private fun ackLiveGmsg(gid: Int, seq: Long) {
        if (!groupLogReader) return
        val last = groupLogSeq[gid]
        if (!GroupLogPage.liveAckable(last, seq)) return
        if (!groupLogSeq.replace(gid, last!!, seq)) return
        pendingLogAcks.merge(gid, seq) { a, b -> maxOf(a, b) }
        if (logAckFlushScheduled.compareAndSet(false, true)) {
            // Same pin as the drain: the account and island this ack is for,
            // and the api object whose token the frame came in under.
            val ackFor = serverHost() to store.uin
            val ackApi = api
            scope.launch {
                delay(1_500)
                logAckFlushScheduled.set(false)
                val batch = HashMap<Int, Long>()
                pendingLogAcks.keys.toList().forEach { g -> pendingLogAcks.remove(g)?.let { batch[g] = it } }
                // A switch empties the map; this is for a frame that slipped
                // in between the clear and the rebind.
                if (batch.isNotEmpty() && (serverHost() to store.uin) == ackFor) runCatching { ackApi.ackGroupLog(batch) }
            }
        }
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

    /** True when this account already knows a DIFFERENT person carrying the
     *  same number: our own island's #134 and `134@api.rcq.app` are unrelated
     *  accounts, and the local store still keys a conversation by the bare
     *  number (`peer:134`). Until that key carries the island too, holding both
     *  at once would merge two people's histories into one thread, so the
     *  second one is refused rather than quietly welded to the first. */
    fun clashesWithKnownNumber(uin: Int, host: String?): Boolean {
        val here = host == null || host.equals(serverHost(), true)
        return _contacts.value.any { it.uin == uin && (it.host == null) != here }
    }

    /** Outcome of a §5f cross-island add. [SENT] = the local row is written AND
     *  the peer's island took our `contactreq`; [ADDED_ONLY] = the row is here
     *  but nothing reached them (say so — do NOT claim a request was sent);
     *  [FAILED] = no card, no row, nothing happened. */
    enum class CiAdd { FAILED, ADDED_ONLY, SENT }

    // ⚠ There is deliberately no boolean `addCrossIslandContact` any more. A
    // single yes/no is what let every caller print "Request sent" for what was
    // only a local row (§5f); callers must see SENT and ADDED_ONLY apart.

    /**
     * Add `uin@host` locally AND deposit a §5f `contactreq` to their island.
     *
     * [act] is what the peer is told: `request` (default — the ordinary add),
     * `accept` (we are taking a request they sent us, which is what makes the
     * relationship MUTUAL and unblocks §5d calls / §5e refresh), or null for
     * the receive side, where announcing back would loop.
     *
     * ⚠ The local row and the key pinning are written exactly as before: the
     * pinned identity/signing keys come from the open card and nothing in a
     * contactreq envelope may ever write to them. The deposit is best-effort and
     * strictly additive — a failed deposit still leaves the contact added, and
     * the caller is told the difference instead of guessing.
     */
    suspend fun addCrossIslandContactDetailed(
        uin: Int,
        host: String,
        act: String? = Envelope.ACT_REQUEST,
    ): CiAdd = withContext(Dispatchers.IO) {
        if (clashesWithKnownNumber(uin, host)) return@withContext CiAdd.FAILED
        val card = runCatching { CrossIslandSender.fetchCard(host, uin) }.getOrNull() ?: return@withContext CiAdd.FAILED
        val contact = CrossIslandStore.Contact(
            uin = uin, host = host,
            nickname = card.nickname?.takeIf { it.isNotBlank() } ?: "$uin@$host",
            identityKey = card.identityKey, signingKey = card.signingKey,
            signalIdentityKey = card.signalIdentityKey, addedAt = System.currentTimeMillis(),
            gender = card.gender, statusMessage = card.statusMessage,
        )
        CrossIslandStore.save(contact)
        if (act == null) return@withContext CiAdd.ADDED_ONLY
        if (depositContactRequest(host, uin, contact.identityKey, act)) CiAdd.SENT else CiAdd.ADDED_ONLY
    }

    /** Seal a §5f `contactreq` to [identityKeyB64] (the key from the peer's open
     *  card) and deposit it to their PRIMARY island — the §5d path, no server
     *  changes. Returns true only when the island actually took it. */
    private fun depositContactRequest(
        host: String,
        uin: Int,
        identityKeyB64: String,
        act: String,
        note: String? = null,
    ): Boolean {
        val me = store.uin ?: return false
        val nick = store.nickname?.takeIf { it.isNotBlank() } ?: "#$me"
        return runCatching {
            CrossIslandSender.deliverContactRequest(
                host, uin, identityKeyB64,
                Envelope.contactRequest(act, nick, note),
                me, signingPriv(), signingPub(), serverHost(),
            )
        }.onFailure {
            android.util.Log.e("RCQci", "contactreq $act to $uin@$host failed: ${it.message}")
        }.getOrDefault(false)
    }

    // §5f anti-abuse: one contactreq per sender identity per minute is enough
    // for consent; the rest are dropped on the floor, so a flood cannot even
    // rewrite the pending row it already owns.
    private val ciReqSeenAt = java.util.Collections.synchronizedMap(HashMap<String, Long>())
    private val CI_REQ_MIN_INTERVAL_MS = 60_000L

    /**
     * §5f receive side. `request` from a stranger opens a pending row; from
     * someone already accepted it is a no-op (never a second row). `accept`
     * makes them an accepted cross-island contact here, so both sides hold each
     * other — the mutual state §5d checks. `decline` drops our pending row for
     * them, silently. A blocked sender is dropped silently, same as same-island.
     */
    private fun handleContactRequest(uin: Int, host: String, cr: Envelope.ContactRequest) {
        val me = store.uin ?: return
        if (uin == me) return
        if (CrossIslandRequestsStore.isBlocked(me, uin, host)) return
        when (cr.act) {
            Envelope.ACT_REQUEST -> {
                // Rate-limit REQUESTS only: those are what a stranger can flood.
                // An accept/decline answers something we started, and throttling
                // it would strand a glare (both sides requested at once) with a
                // pending row nobody can clear.
                val key = "$uin@${host.lowercase()}"
                val now = System.currentTimeMillis()
                val last = ciReqSeenAt[key]
                if (last != null && now - last < CI_REQ_MIN_INTERVAL_MS) return
                ciReqSeenAt[key] = now
                // Already ours: nothing to consent to, and no second row.
                if (CrossIslandStore.get(uin, host) != null) return
                if (CrossIslandRequestsStore.holdContactRequest(me, uin, host, cr.nickname, cr.note)) {
                    refreshCiRequests()
                }
            }
            Envelope.ACT_ACCEPT -> {
                // They took the request we sent. We already hold their row (the
                // add wrote it — that is what "we asked them" means on every
                // client), so leave it alone: re-adding would re-pin keys from a
                // card fetch an envelope just triggered. Both sides now hold
                // each other, which is the mutual state §5d checks.
                if (CrossIslandStore.get(uin, host) != null) {
                    CrossIslandRequestsStore.clear(me, uin, host)
                    refreshCiRequests()
                    // §5e: the relationship just became mutual, so they are now
                    // an audience for our profile. Send it once here, so they
                    // start on our CURRENT name and picture instead of whatever
                    // the open card said when they fetched it.
                    depositProfileToNewContact(uin, host)
                    return
                }
                // An `accept` from someone we never asked is NOT a licence to
                // add them: adding here let any stranger self-add with one
                // envelope, skipping the consent step §5f exists to create (and
                // once in the roster their messages skip the Variant A
                // quarantine and §5d lets them call). File it as a pending row
                // the user decides on — same as iOS and web, so one envelope
                // means one thing on all three clients.
                if (CrossIslandRequestsStore.holdContactRequest(me, uin, host, cr.nickname, cr.note)) {
                    refreshCiRequests()
                }
            }
            Envelope.ACT_DECLINE -> {
                // Silent: drop the pending row we hold for them and say nothing.
                // Their contact row (and its pinned keys) is the user's own add
                // and is not touched from the wire.
                CrossIslandRequestsStore.clear(me, uin, host)
                refreshCiRequests()
            }
            else -> Unit // unknown act from a newer client
        }
    }

    // ── §5e cross-island profile refresh (name + picture) ──────────────
    //
    // A cross-island contact's name and picture used to be read exactly ONCE,
    // off the open key card at add time, and never again: the same-island
    // `contact_renamed` WS broadcast cannot reach a holder on another island,
    // because the island's contacts table has no host column and so that holder
    // is not in the audience and cannot be. Someone added as "nick1" read
    // "nick1" forever. The fix is a PUSH — we deposit our new profile to the
    // islands of the people allowed to see it. Same transport as §5f.

    /** Deposit my current profile to EVERY accepted cross-island contact.
     *  Called when the nickname or the picture changes. Fire-and-forget on our
     *  own scope: a profile edit must not block on N foreign islands, and a
     *  contact whose island is down simply learns the new name next time. */
    private fun broadcastProfileCrossIsland(pictureCleared: Boolean = false) {
        scope.launch {
            runCatching {
                // Someone we blocked does not get handed our current name and
                // face. Same filter web applies to this broadcast.
                val me = store.uin ?: return@runCatching
                val targets = CrossIslandStore.list()
                    .filter { !CrossIslandRequestsStore.isBlocked(me, it.uin, it.host) }
                depositProfileTo(targets, pictureCleared)
            }
        }
    }

    /** Deposit my current profile to ONE contact that just became accepted, so
     *  they start out with a current name instead of the card snapshot. */
    private fun depositProfileToNewContact(uin: Int, host: String) {
        scope.launch {
            runCatching {
                val me = store.uin ?: return@runCatching
                if (CrossIslandRequestsStore.isBlocked(me, uin, host)) return@runCatching
                CrossIslandStore.get(uin, host)?.let { depositProfileTo(listOf(it)) }
            }
        }
    }

    /**
     * Seal a §5e `profile` to each of [targets] and deposit it to their PRIMARY
     * island.
     *
     * ⚠ The picture is DEPOSITED, not pulled: the already-encrypted blob is put
     * on the RECIPIENT's island first (§5b `PUT /media/{id}`, client-chosen id,
     * idempotent) and only then is the envelope — which carries the id AND the
     * key — sealed to that one recipient. The key is never published on the open
     * card or in the signed home record: both are unauthenticated, and
     * `GET /media/{id}` has no auth at all, so the key IS the access decision,
     * and the same-island rule for a picture is relationship-based.
     *
     * Per-contact `runCatching`: one unreachable island must not stop the rest.
     */
    private suspend fun depositProfileTo(
        targets: List<CrossIslandStore.Contact>,
        /** The user just REMOVED their picture. Distinguishes a deliberate
         *  clear from "the avatar pair is not loaded yet", which the fallback
         *  below otherwise papers over — and papering over a clear would keep
         *  re-sending the deleted face. */
        pictureCleared: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (targets.isEmpty()) return@withContext
        val me = store.uin ?: return@withContext
        val nick = store.nickname?.takeIf { it.isNotBlank() } ?: "#$me"
        // Only claim a picture we can actually hand over. If the sealed blob
        // can't be recovered, the envelope goes out naming no picture rather
        // than naming one the recipient's island will 404.
        // ⚠ Fall back to the cached profile when the in-memory pair is not
        // seeded yet (a nickname edit can land before loadProfile finishes).
        // Without this the envelope would name NO picture and every recipient
        // would dutifully drop the one they have — a profile refresh that
        // deletes the face is worse than no refresh.
        val own = if (pictureCleared) null else _ownAvatar.value ?: ownAvatarOf(cachedProfile())
        val blob = own?.let { (id, _) -> ownAvatarBlob(id) }
        // ⚠ HAVE a picture but cannot hand over its bytes → send NOTHING. The
        // envelope is a SNAPSHOT of the whole display state, so one that names
        // no picture reads on the far side as "I removed mine" and deletes our
        // face (web, Android and iOS all clear on absence). A transient failure
        // to read our own blob must not delete our face everywhere; the name
        // catches up on the next change.
        if (own != null && blob == null) {
            android.util.Log.w("RCQci", "profile push skipped: own avatar blob unavailable")
            return@withContext
        }
        val env = Envelope.profileUpdate(nick, own?.first, own?.second)
        val priv = signingPriv()
        val pub = signingPub()
        val ownHost = serverHost()
        for (c in targets) {
            runCatching {
                // ⚠ The blob must LAND on the recipient's island before the
                // envelope naming it goes out: islands never talk to each other,
                // so a recipient resolves the id against their OWN island. A
                // failed PUT means the id would 404 there — a permanent broken
                // face — and the same snapshot rule forbids downgrading to a
                // name-only envelope, so this recipient is skipped entirely.
                // depositBlob RETURNS false rather than throwing, which is why
                // the result is checked here and not left to runCatching.
                if (own != null && blob != null) {
                    if (!CrossIslandSender.depositBlob(c.host, own.first, blob)) {
                        android.util.Log.w("RCQci", "profile to ${c.uin}@${c.host} skipped: avatar not deposited")
                        return@runCatching
                    }
                }
                CrossIslandSender.deliverProfile(c, env, me, priv, pub, ownHost)
            }.onFailure {
                android.util.Log.e("RCQci", "profile to ${c.uin}@${c.host} failed: ${it.message}")
            }
        }
    }

    /** My own avatar's ENCRYPTED blob, for re-deposit to a foreign island. Disk
     *  cache first ([setOwnAvatar] writes it there on upload); otherwise pull
     *  the sealed bytes back from our own island and keep them. Never the
     *  decrypted image — the recipient opens it with the key in the envelope. */
    private suspend fun ownAvatarBlob(mediaId: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = mediaDiskFile(mediaId)
        if (f.exists()) return@withContext runCatching { f.readBytes() }.getOrNull()
        runCatching {
            api.getBlob(mediaId).also { b -> runCatching { f.writeBytes(b); trimMediaDiskCache() } }
        }.getOrNull()
    }

    /**
     * §5e receive side. Update ONLY the display fields of an EXISTING
     * cross-island row.
     *
     *  - The pinned identity/signing keys are never touched: they are the
     *    anti-impersonation anchor, and an envelope that could write them would
     *    be an impersonation path. [CrossIslandStore.applyProfile] copies the
     *    row and replaces name + picture only.
     *  - A `profile` from someone we do not hold as an accepted cross-island
     *    contact is DROPPED — not quarantined, not a pending row. Cosmetic data
     *    from a stranger is nothing.
     *  - A device-local ALIAS outranks the received name: the store keeps what
     *    THEY call themselves, and every display path already reads
     *    `alias ?: nickname` ([contactName], the Home row, Contact info).
     *  - The store write is committed to DISK, because the push path reads that
     *    snapshot with no live Session.
     */
    private fun handleProfileUpdate(uin: Int, host: String, pu: Envelope.ProfileUpdate) {
        val me = store.uin ?: return
        if (uin == me) return
        if (!CrossIslandStore.applyProfile(uin, host, pu.nickname, pu.avatarMediaId, pu.avatarMediaKey, pu.ts)) return
        refreshCrossIslandDisplay()
    }

    /** Re-read the display fields of the cross-island rows already merged into
     *  the roster, so a refreshed name/picture lands on every screen at once.
     *  Only the display fields move — a roster row keeps everything else it
     *  has, and same-island contacts are untouched. */
    private fun refreshCrossIslandDisplay() {
        val fresh = CrossIslandStore.list().associateBy { it.uin to it.host.lowercase() }
        _contacts.value = _contacts.value.map { c ->
            val host = c.host ?: return@map c
            val f = fresh[c.uin to host.lowercase()] ?: return@map c
            c.copy(nickname = f.nickname, avatarMediaId = f.avatarMediaId, avatarMediaKey = f.avatarMediaKey)
        }
    }

    // ── cross-island message requests (Variant A consent) ──

    val ciRequests = MutableStateFlow<List<CrossIslandRequestsStore.Request>>(emptyList())

    fun refreshCiRequests() {
        // ⚠⚠ `store.uin` is the REAL account's number even in a duress session
        // (the decoy is not a roster account, so `store` never moves), and this
        // store is keyed by ownUin rather than by account id — so without the
        // guard a single refresh from the decoy view would list every real
        // person waiting for an answer on another island. The quarantine has
        // nothing to do with the decoy: it stays hidden until a real unlock.
        if (duressViewUp) { ciRequests.value = emptyList(); return }
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

    /** Should this decrypted same-island 1:1 envelope go to the requests list
     *  instead of the chat? Opt-in per account (Privacy) and mirrors web-chat's
     *  stranger-requests.ts policy exactly. Only CONTENT kinds are held:
     *  control traffic from an unknown sender (reactions, receipts, edits,
     *  typing, visits) has no message of ours to belong to, so it falls
     *  through and no-ops instead of opening a request row. */
    private fun shouldQuarantineStranger(senderUin: Int, env: Envelope): Boolean {
        if (!LocalStores.strangerQuarantineEnabled()) return false
        val content = env is Envelope.Text || env is Envelope.Photo || env is Envelope.Video ||
            env is Envelope.File || env is Envelope.Voice || env is Envelope.Location
        if (!content) return false
        return isQuarantinedStranger(senderUin)
    }

    /** Is this sender someone the quarantine would hold, sender aside from what
     *  they sent? Split out of [shouldQuarantineStranger] so a branch that is
     *  NOT content can ask the same question. Control traffic falls through the
     *  quarantine on purpose, but a control envelope that now writes a visible
     *  row into the chat has stopped being purely control: the row belongs
     *  behind the same gate, or the requests list is a door with a window next
     *  to it. */
    private fun isQuarantinedStranger(senderUin: Int): Boolean {
        if (!LocalStores.strangerQuarantineEnabled()) return false
        if (LocalStores.isAllowedStranger(senderUin)) return false
        if (isSameIslandContact(senderUin)) return false
        // I wrote to them first, so their reply is invited, whatever the list says.
        if (_messages.value[senderUin].orEmpty().any { it.fromMe }) return false
        return true
    }

    /** Roster membership for the quarantine gate. When there is no roster to
     *  consult AT ALL (live list empty and the offline cache never written)
     *  FAIL OPEN: treat everyone as known rather than eat messages blind.
     *  Same-island only: a cross-island contact can share the bare number
     *  with a local stranger (see [LocalStores.aliasKey]). */
    private fun isSameIslandContact(uin: Int): Boolean {
        val live = _contacts.value
        if (live.isNotEmpty()) return live.any { it.uin == uin && it.host.isNullOrBlank() }
        val json = LocalStores.cachedContactsJson() ?: return true
        val cached = runCatching { profileGson.fromJson(json, Array<Contact>::class.java) }.getOrNull() ?: return true
        return cached.any { it.uin == uin && it.host.isNullOrBlank() }
    }

    /** Cross-island contacts rendered as ordinary [Contact]s so they show in the
     *  chat list (the send path still routes them by [CrossIslandStore] host). */
    private fun crossIslandContacts(): List<Contact> = CrossIslandStore.list().map { c ->
        // callable: §5d made cross-island 1:1 calls work (signaling crosses as
        // sealed deposits; media is P2P either way).
        // §5e: their picture, deposited by them into OUR island — so it is
        // fetched with no host, exactly like a same-island one.
        Contact(uin = c.uin, nickname = c.nickname, identityKey = c.identityKey, signingKey = c.signingKey, status = "offline", callable = true, host = c.host, gender = c.gender, statusMessage = c.statusMessage, avatarMediaId = c.avatarMediaId, avatarMediaKey = c.avatarMediaKey)
    }

    /** Append cross-island contacts to the displayed roster (skip uin already
     *  held by a same-island contact). Called after every contacts refresh
     *  (which overwrites the list) + on accept. */
    fun mergeCrossIslandContacts() {
        // The decoy roster is exactly what was seeded and nothing else. The
        // store is bound to the decoy namespace so this would return nothing
        // anyway; the guard is here because this is the one function whose job
        // is to ADD real people to the visible roster, and a future call site
        // that runs after an unlock must not be able to reach it.
        if (duressViewUp) return
        val extra = crossIslandContacts().filter { c -> _contacts.value.none { it.uin == c.uin } }
        if (extra.isNotEmpty()) _contacts.value = _contacts.value + extra
    }

    /** Accept a cross-island request: save the sender as a contact FIRST (so the
     *  held payloads pass the consent gate), then re-ingest them so the messages
     *  surface in the now-visible thread, and surface the contact in the roster. */
    /** §5f: accepting also DEPOSITS an `accept` back to the requester's island,
     *  so both sides end up holding the other as accepted — the mutual state
     *  §5d already checks and §5e assumes. If the card can't be fetched nothing
     *  was accepted, so the pending row stays instead of vanishing. */
    suspend fun acceptCrossIslandRequest(uin: Int, host: String): Boolean {
        val me = store.uin ?: return false
        // A SAME-ISLAND stranger (host "", the opt-in Privacy quarantine): no
        // key card to fetch, no §5f accept to deposit. Accepting means "let
        // this person talk": remember the allowance so their future messages
        // flow, then re-ingest what they already wrote. The re-ingested
        // payloads pass the gate now, land in a normal thread and only then
        // send their delivery receipts.
        if (host.isEmpty()) {
            LocalStores.allowStranger(uin)
            CrossIslandRequestsStore.clear(me, uin, "")?.msgs?.forEach { ingest(it.payload) }
            refreshCiRequests()
            return true
        }
        if (addCrossIslandContactDetailed(uin, host, Envelope.ACT_ACCEPT) == CiAdd.FAILED) return false
        CrossIslandRequestsStore.clear(me, uin, host)?.msgs?.forEach { ingest(it.payload) }
        mergeCrossIslandContacts()
        refreshCiRequests()
        // §5e: newly accepted → send our current name + picture once, so they
        // do not sit on the snapshot their card fetch took.
        depositProfileToNewContact(uin, host)
        return true
    }

    /** Turn a cross-island request down without blocking the person.
     *
     *  The row offered accept or block and nothing in between, so somebody who
     *  simply did not want to talk right now had to either keep the row or
     *  silence a stranger for good (#586). The quarantined messages go with the
     *  request; the sender can write again, and it will arrive as a fresh one.
     */
    fun dismissCrossIslandRequest(uin: Int, host: String) {
        val me = store.uin ?: return
        CrossIslandRequestsStore.clear(me, uin, host)
        refreshCiRequests()
    }

    fun blockCrossIslandRequest(uin: Int, host: String) {
        val me = store.uin ?: return
        CrossIslandRequestsStore.block(me, uin, host)
        // ...and in the list the user can actually SEE. The quarantine store
        // keeps its own per-(uin,host) denylist, so blocking a cross-island
        // stranger silenced them but left Settings → Blocked users empty, which
        // reads as "the block did not take" (user report). Same set, one
        // surface: this is also what makes Unblock possible at all.
        LocalStores.setBlocked(uin, true)
        refreshCiRequests()
    }

    /** Server-side search for the Add window (users + joinable groups). */
    /** After a send FAILED, ask once whether the peer still exists. A clean
     *  "no such number" from their island means they burned the account, and
     *  the chat can say so instead of leaving a red retry arrow the user will
     *  keep tapping. Only ever called on failure: polling every contact for
     *  liveness would be exactly the metadata traffic we avoid. Any other
     *  outcome (network error, blocked island, anything but a definite 404)
     *  changes nothing — silence is not evidence of death. */
    private fun notePeerLivenessAfterFailure(uin: Int) {
        scope.launch(Dispatchers.IO) {
            val ci = CrossIslandStore.findByUin(uin)
            val gone = if (ci != null) {
                CrossIslandSender.peerMissing(ci.host, uin)
            } else {
                runCatching { api.userInfo(uin) }.exceptionOrNull()
                    ?.message?.startsWith("HTTP 404") == true
            }
            if (gone) LocalStores.setGone(uin, true)
        }
    }

    /** Resolve ONE exact UIN (the `#911` search form). Null when nobody holds
     *  that number, which is a different answer from "no name matched". */
    suspend fun lookupUin(uin: Int): RcqApi.UserInfo? =
        runCatching { api.userInfo(uin) }.getOrNull()

    /** What the island said about a number — three answers, not two.
     *
     *  [lookupUin] folds "nobody holds this number" and "the island did not
     *  answer" into the same null, which is fine for a search box that shows
     *  nothing either way. It is not fine for deciding whether to offer to add
     *  somebody: hiding the offer on a null would take add-by-number away from
     *  exactly the people on the worst networks, who are the ones typing a
     *  number a friend read out to them.
     *
     *  ⚠ A private, unlisted account answers 200 here with its optional fields
     *  stripped, not 404, so [Absent] really does mean the number is free. */
    sealed class UinLookup {
        data class Found(val info: RcqApi.UserInfo) : UinLookup()
        /** The island said 404: nobody holds this number. */
        object Absent : UinLookup()
        /** No answer. Says nothing about the number. */
        object Unknown : UinLookup()
    }

    suspend fun lookupUinDetailed(uin: Int): UinLookup =
        runCatching { api.userInfo(uin) }.fold(
            onSuccess = { UinLookup.Found(it) },
            onFailure = {
                if (it.message?.startsWith("HTTP 404") == true) UinLookup.Absent else UinLookup.Unknown
            },
        )

    suspend fun searchUsers(q: String): List<RcqApi.UserInfo> =
        runCatching { api.searchUsers(q) }.getOrNull() ?: emptyList()

    suspend fun searchGroups(q: String): List<RcqApi.GroupPreviewOut> =
        runCatching { api.searchGroups(q) }.getOrNull() ?: emptyList()

    suspend fun respond(requestId: Int, accept: Boolean) {
        api.respondContact(requestId, accept)
        runCatching { refreshContacts() }
        runCatching { refreshPending() }
    }

    /** Serialises [refreshContacts]. Every `presence` WS frame launches one,
     *  and a burst (a contact reconnecting, or a whole island coming back)
     *  launches several at once. Concurrent refreshes each snapshot
     *  `_contacts.value` BEFORE any of them writes it, so they all see the same
     *  offline→online edge and each one chimes for it: one person coming online,
     *  two or three "о-оу". Whoever holds the lock does the compare-and-swap
     *  alone, and the ones behind it compare against the already-updated list
     *  and find nothing to announce. */
    private val contactsRefreshLock = Mutex()

    // Explicit Unit: the body bails out early on an account switch, and a bare
    // `return@withLock` needs the lambda's type to be Unit rather than whatever
    // its last expression happens to be.
    private suspend fun refreshContacts(): Unit = contactsRefreshLock.withLock {
        // Snapshot presence before the refresh so we can play a sound on
        // online/offline transitions (iOS SoundService parity).
        //
        // "Snapshot is empty" is NOT a good enough guard, and report #422 is
        // what that costs: loadCachedRoster() seeds the roster from disk with
        // everyone forced to `offline` (we cannot vouch for presence while
        // disconnected), and rebindTo() empties it on an account switch. So the
        // first live refresh after a cold start or a profile switch compares
        // "everyone offline" against the truth and knocks once per contact who
        // was already online — the user hears the whole roster arrive because
        // THEY arrived.
        //
        // A transition is only real if the baseline came from a live refresh in
        // this session, so that is what we track. Reset in rebindTo().
        val armed = presenceBaselineLive
        val prevPresence = _contacts.value.associate { it.uin to it.presence }
        // Whose roster this is: an account switch while the fetch is in the
        // air rebinds `store`, and the list must not be sealed into the new
        // account's vault (the mirror checks the pin before it starts).
        val fetchedFor = store.uin
        val ep = epochNow()
        val fetched = api.contacts()
        // ⚠⚠ The pin above guarded ONLY the vault mirror. Everything between
        // here and the end of this function writes the roster somewhere else:
        // the live flow the screens render, and the plaintext cache on disk
        // that a cold start reads back. Under a switch mid-fetch those two put
        // account A's contacts — numbers, nicknames, statuses, keys — into
        // account B's screen and account B's prefs slot, where they survive a
        // restart. Stop before any of it.
        if (!stillOn(ep)) return@withLock
        _contacts.value = fetched.map {
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
                avatarMediaId = it.avatar_media_id,
                // The island no longer holds the key to a picture set under the
                // profile-key model, so it serves null and the real key is the
                // one its owner sealed to us. Done HERE, in the one mapper, so
                // every screen that draws a face gets it without eight copies
                // of the same fallback. docs/profile-key-design.md.
                avatarMediaKey = (it.avatar_media_key ?: LocalStores.profileKey(it.uin))
                    // Named a picture but we hold no key for it: ask its owner
                    // once, so the face appears on a later refresh instead of
                    // never. See [maybeAskProfileKey].
                    ?: run {
                        if (!it.avatar_media_id.isNullOrEmpty()) {
                            maybeAskProfileKey(it.uin, it.identity_key ?: "", null)
                        }
                        null
                    },
            )
        }
        presenceBaselineLive = true
        if (armed) {
            _contacts.value.forEach { ct ->
                val before = prevPresence[ct.uin] ?: return@forEach
                val wasOnline = before != UserStatus.OFFLINE
                val isOnline = ct.presence != UserStatus.OFFLINE
                if (wasOnline == isOnline) return@forEach
                val fav = LocalStores.isFavorite(LocalStores.peerThread(ct.uin))
                if (isOnline) app.rcq.android.media.SoundService.contactOnline(fav)
                else app.rcq.android.media.SoundService.contactOffline(fav)
            }
        }
        // Seed the identity cache so sends to contacts skip a lookup.
        _contacts.value.forEach { c ->
            if (c.identityKey.isNotEmpty()) {
                peerIdentityCache[c.uin] = Base64.decode(c.identityKey, Base64.NO_WRAP)
            }
        }
        // Persist the roster so the chat list is reachable offline (report #7).
        // LocalStores is a singleton bound to whoever is active NOW, so this
        // write is only safe while the epoch still holds.
        if (stillOn(ep)) {
            runCatching { LocalStores.setCachedContactsJson(profileGson.toJson(_contacts.value)) }
        }
        mirrorContactsToVault(_contacts.value, fetchedFor)
        // Cross-island contacts aren't in the server roster — surface them too.
        mergeCrossIslandContacts()
    }

    /** Stage 4, mirror phase: the list the island just served is sealed into
     *  the account's vault slot so a reinstall has a roster once the island
     *  stops serving one. Behind the refresh on our own scope, never blocking
     *  it, never throwing; a write only happens when the slot disagrees with
     *  the list, and one mirror runs at a time per process (a burst of
     *  presence-driven refreshes would otherwise race each other to the same
     *  version and take turns losing). Pinned to the island that served the
     *  list: an account switch mid-flight rebinds `api`, and the old island's
     *  roster must not land in the new island's slot. */
    private val vaultMirrorInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    /** The edges the mirror last folded this process, as a fingerprint, so
     *  a roster refresh that changed nothing (every presence frame launches
     *  one) costs no vault read. Keyed by island and account. */
    @Volatile private var vaultMirrored: String? = null
    /** Set for the rest of the session when the island served a contacts-slot
     *  version below the floor, or when `vault_reset` said this derivation was
     *  retired. Nothing is written under a retired derivation. */
    @Volatile private var contactsVaultRetired: Boolean = false
    private fun mirrorContactsToVault(list: List<Contact>, fetchedFor: Int?) {
        if (!vaultEnabled || contactsVaultRetired) return
        if (fetchedFor == null || store.uin != fetchedFor) return
        val ik = store.identityPrivate ?: return
        val servedBy = serverHost()
        val own = list.filter { it.host == null }
        val key = servedBy + "|" + fetchedFor + "|" + own.map { "${it.uin}:${if (it.blocked) 1 else 0}:${it.nickname}" }.sorted().joinToString("\n")
        if (key == vaultMirrored) return
        val apiNow = api
        if (!vaultMirrorInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (serverHost() != servedBy || store.uin != fetchedFor) return@launch
                val out = app.rcq.android.data.ContactsVault.mirror(apiNow, ik, own) { store.uin == fetchedFor }
                when (out) {
                    is app.rcq.android.data.ContactsVault.Outcome.RolledBack -> {
                        contactsVaultRetired = true
                        android.util.Log.w("RCQvault", "contacts slot rolled back; mirror stopped for this session")
                    }
                    is app.rcq.android.data.ContactsVault.Outcome.Failed ->
                        android.util.Log.w("RCQvault", "contacts mirror: ${out.why}")
                    else -> vaultMirrored = key
                }
            } finally {
                vaultMirrorInFlight.set(false)
            }
        }
    }

    // ── chat-list sections (founder item 1 of 23.08) ──────────────────────
    //
    // A SECOND vault slot, so the same sections, in the same order, with the
    // same chats filed into them, are on the phone, the desktop and the web.
    // The tree itself lives in [LocalStores.sections]; everything here is the
    // plumbing between that cache and the island. See data/Sections.kt for the
    // format and the merge, and RCQ/docs/sections-design-2026-08-23.md.

    /** Everything [app.rcq.android.data.SectionsVault] needs, or null when this
     *  session has nothing to seal with. Rebuilt per call: the route watchdog
     *  swaps [api] under us on an entry-guard rotation, and an account switch
     *  mid-flight must not seal this account's sections into the next one's
     *  slot ([SectionsVault.Ctx.stillOurs] is what asks). */
    private fun sectionsCtx(): app.rcq.android.data.SectionsVault.Ctx? {
        // ⚠ Never from a duress view. A migrated decoy keeps `store` (and its
        // bearer token) pointed at the REAL account while the local stores move,
        // so a write from here would seal the decoy's empty tree over the real
        // account's sections.
        if (duressViewUp) return null
        val ik = store.identityPrivate ?: return null
        val who = store.uin ?: return null
        // ⚠⚠ The per-account STORE id, which is the thing the duress PIN moves
        // and `store.uin` is not. Every cache read and write in SectionsVault
        // is scoped to it; see the note on SectionsVault.Ctx.
        val acct = AccountManager.activeId.value ?: return null
        val host = serverHost()
        return app.rcq.android.data.SectionsVault.Ctx(api, ik, who, acct, scope) {
            // `!duressViewUp` is not redundant with the guard above: the guard
            // only refuses to BUILD a ctx, and a sync that was already in
            // flight when the decoy opened resumes with this one still true.
            !duressViewUp && store.uin == who && serverHost() == host &&
                AccountManager.activeId.value == acct
        }
    }

    /** Apply one local edit to the sections tree and get it to the island.
     *  Throws [app.rcq.android.data.Sections.SectionsException] from the caps
     *  before anything is saved, so the caller can say "this section is full".
     *  [defer] coalesces a burst (a drag reorder, a picker sheet) into one put. */
    fun editSections(defer: Boolean = false, edit: (JsonObject) -> JsonObject) {
        app.rcq.android.data.SectionsVault.mutate(sectionsCtx(), defer, edit)
    }

    /** Read the island's copy and fold it into the cache. Boot, the
     *  `vault_changed` nudge and every socket reconnect. */
    fun syncSections() {
        if (!vaultEnabled) return
        val ctx = sectionsCtx() ?: return
        scope.launch { app.rcq.android.data.SectionsVault.sync(ctx) }
    }

    /** A chat is going away on THIS device on purpose. See the write-timing
     *  note on [app.rcq.android.data.SectionsVault.forgetMember]: the write
     *  happens whether or not the chat was filed. */
    private fun forgetSectionMember(key: String?) {
        if (!vaultEnabled) return
        app.rcq.android.data.SectionsVault.forgetMember(sectionsCtx(), key)
    }

    /** A socket that keeps dying redials on a curve that starts at one second,
     *  and each redial that succeeds would otherwise be a sweep. One every
     *  fifteen seconds is plenty for a change another device just made. */
    @Volatile private var lastVaultSweep = 0L
    private val vaultSweepInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Boot and every reconnect: ask the island which slots moved and re-read
     * the ones that did.
     *
     * The `vault_changed` nudge covers the device that is connected right now,
     * but it is pub/sub with NO REPLAY: a device whose socket was down when the
     * other one wrote never hears it, and a reconnect is exactly the moment
     * that gap closes. One `GET /vault` (slots and versions, no blobs) says
     * what moved.
     *
     * ⚠ The version is not the only reason to sync. A device that owes the
     * island a write (offline when the section was made, a 429, a 5xx) has to
     * be let in even though the island's copy has not moved: [SectionsVault.sync]
     * merges both ways and sends what is outstanding. Without this the sweep
     * looks at an unchanged version, decides there is nothing to do, and the
     * section stays on one device forever.
     */
    fun sweepVaultSlots(force: Boolean = false) {
        if (!vaultEnabled) return
        val now = System.currentTimeMillis()
        if (!force && now - lastVaultSweep < VAULT_SWEEP_FLOOR_MS) return
        val ctx = sectionsCtx() ?: return
        if (!vaultSweepInFlight.compareAndSet(false, true)) return
        lastVaultSweep = now
        scope.launch {
            try {
                val slots = runCatching { ctx.api.vaultList() }.getOrNull() ?: return@launch
                if (!ctx.stillOurs()) return@launch
                val byName = slots.associate { it.slot to it.version }
                val sectionsSlot = app.rcq.android.data.SectionsVault.slotOf(ctx.identityPriv)
                val seen = LocalStores.vaultSlotVersion(sectionsSlot)
                if ((byName[sectionsSlot] ?: 0L) > seen || LocalStores.sectionsPushPending()) {
                    app.rcq.android.data.SectionsVault.sync(ctx)
                }
                if (!ctx.stillOurs()) return@launch
                val contactsSlot = app.rcq.android.crypto.Vault.slotId(ctx.identityPriv, app.rcq.android.crypto.Vault.CONTACTS)
                if ((byName[contactsSlot] ?: 0L) > LocalStores.vaultContactsVersion()) {
                    // Still a MIRROR of the island's own list in this phase, so
                    // re-reading it does not change what the chat list draws.
                    // What it does do is move the floor up to what another
                    // device just wrote, which is what keeps this install's
                    // next mirror write from opening with a 409, and drop the
                    // "already folded this list" fingerprint so the next
                    // /contacts refresh folds against the fresh copy.
                    runCatching { app.rcq.android.data.ContactsVault.read(ctx.api, ctx.identityPriv) }
                    vaultMirrored = null
                }
            } finally {
                vaultSweepInFlight.set(false)
            }
        }
    }

    /**
     * `vault_changed {slot, version}` from the socket (SPEC §4.9). Until 23.08
     * NO client listened for it, so a section made on the desktop reached this
     * phone on its next cold start and not before.
     *
     * ⚠ Slot names are hashes. `slot` on the wire is 32 hex characters that
     * mean nothing without the account's identity key, so the frame is matched
     * by deriving both names locally rather than by comparing strings to
     * "contacts". The writer hears its own nudge too and drops it by version.
     */
    private fun onVaultChanged(obj: JsonObject) {
        if (!vaultEnabled) return
        val slot = obj.get("slot")?.takeIf { !it.isJsonNull }?.asString ?: return
        val version = obj.get("version")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        val ctx = sectionsCtx() ?: return
        if (slot == app.rcq.android.data.SectionsVault.slotOf(ctx.identityPriv)) {
            if (version > 0L && version <= LocalStores.vaultSlotVersion(slot)) return
            scope.launch { app.rcq.android.data.SectionsVault.sync(ctx) }
            return
        }
        if (slot == app.rcq.android.crypto.Vault.slotId(ctx.identityPriv, app.rcq.android.crypto.Vault.CONTACTS)) {
            if (version > 0L && version <= LocalStores.vaultContactsVersion()) return
            scope.launch {
                runCatching { app.rcq.android.data.ContactsVault.read(ctx.api, ctx.identityPriv) }
                vaultMirrored = null
            }
        }
    }

    /**
     * `vault_reset {reason: "identity_reissued"}`: `POST /auth/reissue` on
     * another device rotated the account's identity, and the island emptied the
     * vault in the same transaction. One account-level frame, not a
     * `vault_changed` per slot, because the NAMES changed rather than the
     * versions.
     *
     * ⚠ NOT a wipe, and not a republish either. Slot names and seal keys are
     * derived from `identity_priv`, and this install is holding the retired
     * one: it cannot write anything the new derivation will ever read, and what
     * it CAN still write is the whole sections tree and contact list, sealed
     * with the key the user has just declared compromised, under the old name.
     * So both slots stop for this session and the local caches are left exactly
     * as they are, because until a device with the new identity publishes the
     * sections tree exists nowhere else.
     *
     * The stored floors go, because they belong to names that will never be
     * read again and a stale floor is what locks a fresh derivation out of its
     * own slot for good.
     */
    private fun onVaultReset() {
        val ik = store.identityPrivate ?: return
        val who = store.uin ?: return
        LocalStores.forgetVaultSlotVersion(app.rcq.android.data.SectionsVault.slotOf(ik))
        LocalStores.setVaultContactsVersion(0)
        app.rcq.android.data.SectionsVault.retire(who)
        contactsVaultRetired = true
        android.util.Log.w("RCQvault", "the account rotated its identity elsewhere; this derivation is retired")
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

    /** What to CALL this person on screen: my own name for them when I set one,
     *  otherwise the nickname they chose, otherwise their number. Every surface
     *  that shows a person's name goes through here, so a rename lands
     *  everywhere at once. */
    fun contactName(uin: Int, host: String? = null): String {
        // The host matters: a uin is per-island, so my name for `1234@is2` must
        // not be handed to the local #1234. When the caller does not say, fall
        // back to the host on the contact row itself.
        val c = _contacts.value.firstOrNull { it.uin == uin && (host == null || it.host == host) }
            ?: _contacts.value.firstOrNull { it.uin == uin }
        return app.rcq.android.data.LocalStores.aliasFor(uin, host ?: c?.host)
            ?: c?.nickname
            ?: "#$uin"
    }

    /** Append a call-summary line to the 1:1 thread (kind="call"), so a
     *  finished/missed call shows in the chat history. Called by
     *  [CallController] on every call end. */
    private fun logCallHistory(
        peerUin: Int,
        fromMe: Boolean,
        text: String,
        /** Only a call that never connected is something the user still has to
         *  deal with. A call that HAPPENED cannot be unread — both people were
         *  on it — and counting it left a green "unread" divider above a
         *  finished conversation (tester, 0.95). */
        missed: Boolean,
        /** When the call STARTED, not when this line was written. The row shows
         *  the time beside the duration, and "10:10 · 1:53" only reads right if
         *  the timestamp is the beginning of that 1:53. */
        startedAt: Long,
        /** Which call this row records, so a later marker for the same call can
         *  be recognised as already known (#678/#686). */
        callId: String? = null,
    ): Boolean {
        if (!::db.isInitialized) return false
        val cid = callId?.takeIf { it.isNotEmpty() }
        return store(
            ChatMessage(
                // ⚠⚠ DERIVED FROM THE CALL ID, never a fresh UUID when there
                // is one. `INSERT OR IGNORE` on the primary key is the
                // mechanism this codebase leans on everywhere else to collapse
                // the live-socket-versus-queue-drain overlap (see [store]), and
                // a random id opted call rows out of it: the only dedupe left
                // was a `haveCallRow` probe followed by an unsynchronised
                // insert, and the two ingest paths run on DIFFERENT THREADS:
                // `handleEvent` on OkHttp's websocket reader, `drainQueueLocked`
                // in a coroutine, with `drainLock` guarding drain against drain
                // and nothing else. Both read "no row" and both wrote one,
                // which is two "Missed call" lines for one call. One row per
                // call now, by construction rather than by timing.
                //
                // The call id is unique per call and each side files its own
                // copy on its own device, so there is nothing for it to
                // collide with. A call that has no id (a local end that never
                // got one) keeps a fresh UUID.
                id = cid?.let { "call:$it" } ?: java.util.UUID.randomUUID().toString(),
                peerUin = peerUin,
                fromMe = fromMe,
                body = text,
                sentAt = startedAt,
                state = DeliveryState.DELIVERED,
                kind = "call",
                callId = cid,
            ),
            countsUnread = missed,
        )
    }

    /** Do we already hold a history row for this call? The test the callee runs
     *  before filing a caller-written missed-call marker (#678/#686). */
    private fun haveCallRow(callId: String): Boolean =
        callId.isNotEmpty() && ::db.isInitialized && db.hasCallId(callId)

    /** When a call announced by a sealed envelope STARTED, in epoch ms.
     *
     *  Three tiers, in the same order and for the same reasons as
     *  [disappearAnchorMs]: the sender's own `ts` when it survives
     *  [Envelope.anchorFromTs]; failing that the island's deposit stamp, which
     *  is not the send time but is far closer to it than the moment this phone
     *  came back online; failing that now. */
    private fun callStartedAtMs(tsSec: Long, depositAtMs: Long?): Long {
        val now = System.currentTimeMillis()
        return Envelope.anchorFromTs(tsSec, now)
            ?: Envelope.saneAnchorMs(depositAtMs, now)
            ?: now
    }

    /** May [senderUin] on [host] leave a MISSED-CALL MARKER in our history?
     *
     *  The marker (#678/#686) is a claim by the caller about a call this
     *  device never saw. Nothing ties it to a call that happened: it is an
     *  ordinary sealed deposit, which any number on any island can compose,
     *  and there is no live signalling behind it for the island to have
     *  policed. So the gate is the same question the island asks before it
     *  lets a `call_offer` through (`_caller_allowed` in `routers/ws.py`),
     *  asked here because the island enforces `call_policy` on the WS path
     *  ONLY and a deposit never goes near it. Without this, a `nobody` policy
     *  meant nothing at all against a marker, and a stranger on another island
     *  could file rows and raise banners without limit.
     *
     *  ⚠ Under `everyone` a stranger passes, and that is the honest answer
     *  rather than a hole: the very same stranger may ring this phone for
     *  real, and a ring nobody picks up leaves the very same row. What the
     *  policy stops is a number the user has already told the island may not
     *  call them.
     *
     *  ⚠ The policy is read from the cached profile, which is a mirror of a
     *  server value and can be stale on a phone that has not fetched since the
     *  setting changed. Stale in the permissive direction costs one row the
     *  island would have refused; the enforcement that matters is still the
     *  island's, on the path a real call takes. */
    private fun mayLeaveCallMarker(senderUin: Int, host: String?): Boolean {
        val ownHosts = setOf(serverHost(), FRONT_HOST).filter { it.isNotBlank() }
        // Cross-island: exactly the gate every other cross-island call signal
        // passes a few lines below, since nothing else over there may ring us.
        if (host != null && host !in ownHosts) return CrossIslandStore.get(senderUin, host) != null
        return when (cachedProfile()?.call_policy ?: "everyone") {
            "nobody" -> false
            "contacts" -> isSameIslandContact(senderUin)
            else -> true
        }
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
            in SEALED_WS_TYPES -> {
                val payload = obj.get("payload")?.asString
                val gid = obj.get("group_id")?.takeIf { !it.isJsonNull }?.asInt
                // Live delivery is NOT filtered per device: every socket of the
                // account sees every copy of a fanned-out message, and only the
                // install it was sealed to holds the session that opens it.
                // An unresolved id tries anyway rather than dropping what may
                // be our own copy — a live packet has no queue row to come
                // back from, and a copy that is not ours simply fails to open.
                val mine = myDeviceIdOrNull()
                val toDev = obj.get("to_device_id")?.takeIf { !it.isJsonNull }?.asInt
                if (payload != null && (toDev == null || mine == null || toDev == mine)) {
                    if (gid != null) ingestGroup(payload, gid) else ingest(payload)
                }
            }
            "gmsg" -> {
                // Sender-keys broadcast: not a sealed envelope — decode via the chain.
                val payload = obj.get("payload")?.asString
                val gid = obj.get("group_id")?.takeIf { !it.isJsonNull }?.asInt
                if (payload != null && gid != null) {
                    val why = ingestGmsg(payload, gid)
                    // Stage 5: the frame names its row in the room's log when
                    // the post was logged. Ingested (or held) above, so the
                    // island may move this device's cursor past it. Not when
                    // ingest says the row may still pass on a later delivery:
                    // the next drain serves it again, and stops short of it
                    // there too (the same rule for both roads).
                    if (why == null) obj.get("seq")?.takeIf { !it.isJsonNull }?.asLong?.let { ackLiveGmsg(gid, it) }
                }
            }
            // Two shapes, and the second one is NOT a no-op (see below).
            "group_created", "group_membership_changed" -> {
                val gj = obj.get("group")?.takeIf { it.isJsonObject }?.asJsonObject
                if (gj != null) {
                    scope.launch {
                        val g = runCatching { mapGroup(gson.fromJson(gj, RcqApi.GroupOut::class.java)) }.getOrNull() ?: return@launch
                        // New to this account (we were added, or this is the
                        // echo of a room we made), not a roster change in a
                        // room we already hold: the beta room turns one of
                        // those over per registration, and every member
                        // fetching on each of them would be a fan-out of its own.
                        val joined = _groups.value.none { it.id == g.id }
                        // The whole snapshot, so `ownerUin` and every member's
                        // role come with it: `upsertGroup` REPLACES the row
                        // rather than diffing the roster, which is what makes a
                        // handover land here live.
                        runCatching { upsertGroup(g) }
                        if (joined) roomJoined()
                    }
                } else {
                    // The COMPACT form: above SNAPSHOT_BROADCAST_LIMIT members
                    // the island cannot afford to push a whole roster to
                    // everyone, so it sends the group id and `owner_uin` alone.
                    //
                    // ⚠ Ignoring it is not harmless, and ignoring it is exactly
                    // what this branch did until founder item 23 shipped. Every
                    // other owner-only lever is enforced by the island, which
                    // 403s a stale client the moment it pulls one; the
                    // moderator `delete` cap is honoured by the RECEIVING
                    // client against its cached roster, because sealed sender
                    // leaves the island no sender to check. So a stale owner is
                    // a revoked owner whose deletes still land on every big
                    // group, with the crown drawn on the wrong row and the
                    // composer of an owner-only room shut for the person who
                    // now runs it, until the next boot.
                    val gid = obj.get("group_id")?.takeIf { !it.isJsonNull }?.asInt
                    val owner = obj.get("owner_uin")?.takeIf { !it.isJsonNull }?.asInt
                    // Both or nothing: an older island sends the bare id (the
                    // beta-group broadcast), and there is nothing to apply.
                    if (gid != null && owner != null) applyOwnerLocally(gid, owner)
                }
            }
            "group_deleted" -> {
                obj.get("group_id")?.asInt?.let { gid -> _groups.value = _groups.value.filterNot { it.id == gid } }
            }
            // The vault moved on some device of this account (SPEC §4.9).
            // ⚠ The frame names the slot by its HASH, which means nothing
            // without the identity key, so it is matched by deriving both names
            // locally. Nobody on Android listened for this at all until 23.08.
            "vault_changed" -> onVaultChanged(obj)
            // /auth/reissue elsewhere: the slot NAMES changed and the island
            // emptied the vault. Not a wipe: see [onVaultReset].
            "vault_reset" -> onVaultReset()
            "contact_request", "contact_response", "contact_removed" -> {
                scope.launch { runCatching { refreshContacts() }; runCatching { refreshPending() }; runCatching { refreshOutgoing() } }
            }
            // A request we received was revoked by its sender — drop it from
            // our incoming list (the row is already gone server-side).
            "contact_request_cancelled" -> {
                scope.launch { runCatching { refreshPending() } }
            }
            // The device registry changed on some device of ours. Only worth a
            // round-trip once the Linked Devices screen has actually been
            // opened — before that there is nothing to keep fresh.
            "device_linked", "device_revoked" -> {
                // Our own carbon list is stale either way: a linked install
                // is about to register a key slot, a revoked one just lost it.
                ownDeviceListChanged(linked = type == "device_linked")
                if (_devices.value != null) scope.launch { runCatching { refreshDevices() } }
            }
            // A KEY SLOT was retired (пункт 13), possibly from another session
            // of this account. Nudge the screen that shows the list, and drop
            // the carbon list so no copy is sealed to the retired slot.
            "device_slot_revoked" -> {
                ownDeviceListChanged(linked = false)
                _keySlotsChanged.value++
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
            "call_offer", "call_answer", "call_ice", "call_end", "call_unreachable", "call_offline",
            "call_renegotiate", "call_renegotiate_answer", "call_renegotiate_decline",
            "call_ice_restart", "call_ice_restart_answer" ->
                calls.onSignal(type, obj)
            // ⚠ `room_deleted` never existed: the island calls it
            // `audio_room_deleted`, so deleting a room left everyone else
            // sitting in a room that was gone. Same class of miss as the four
            // owner events below, none of which were ever routed here.
            "room_roster", "room_member_entered", "room_member_left", "room_offer",
            "room_answer", "room_ice", "room_speaking", "room_enter_rejected",
            "audio_room_deleted", "audio_room_kicked", "audio_room_membership_revoked",
            "audio_room_key_rotated", "audio_room_member_muted",
            "audio_room_owner_only_changed", "audio_room_renamed" ->
                audioRooms.onSignal(type, obj)
            "presence" -> scope.launch { runCatching { refreshContacts() } }
            // A contact changed their name. Nothing announced this before, so
            // the new name only appeared whenever the roster happened to be
            // re-read next — "изменение произошло не сразу, в какой момент оно
            // должно актуализироваться?" had no answer.
            "contact_renamed" -> scope.launch { runCatching { refreshContacts() } }
            // A fresh news post, announced live (A4). The frame is only a
            // doorbell: refreshNewsBadge re-reads /news itself, which keeps
            // the first-run seeding (a fresh install must not be told it
            // missed the whole archive) in the one place that handles it.
            "news_posted" -> scope.launch {
                runCatching { refreshNewsBadge() }
                _newsFeedChanged.value++
            }
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
        // Reap any disappearing messages whose TTL lapsed while the app was
        // closed BEFORE seeding the flows, so an expired message never flashes
        // on launch.
        //
        // ⚠ READ FIRST, DELETE SECOND, AND TELL THE SHADE. This used to be a
        // bare `deleteExpired` whose returned ids went nowhere, and that left
        // the exact banner the sweeper exists to take down: offline for an
        // hour, a message with a one-minute timer arrives and posts a wake
        // carrying its FULL body, the app is opened later, the row is reaped
        // here — and the banner stays on the lock screen and on any paired
        // watch until somebody swipes it away. The 10-second sweeper cannot
        // clean up after this one either: by the time it first runs the row is
        // already gone from the table, so it finds nothing expired and returns.
        // Same `now` for the partition and the delete, so the two agree exactly.
        val now = System.currentTimeMillis()
        val all = db.all()
        val (doomed, live) = all.partition { it.expiresAt != null && it.expiresAt!! <= now }
        if (doomed.isNotEmpty()) db.deleteExpired(now)
        // The badge goes with them, exactly as in [sweepExpiredMessages] and
        // for the same reason. This is the path that handles the case the
        // whole thing is about, a message that lapsed while the app was shut,
        // so a badge left standing here survives every restart.
        val doomedIds = doomed.map { it.id }.toSet()
        if (doomedIds.isNotEmpty()) {
            val hitPeers = doomed.filter { it.groupId == null }.map { it.peerUin }.toSet()
            val hitGroups = doomed.mapNotNull { it.groupId }.toSet()
            for ((uin, rows) in all.filter { it.groupId == null && it.peerUin in hitPeers }.groupBy { it.peerUin })
                shedLapsedUnread(LocalStores.peerThread(uin), rows, doomedIds)
            for ((gid, rows) in all.filter { it.groupId in hitGroups }.groupBy { it.groupId!! })
                shedLapsedUnread(LocalStores.groupThread(gid), rows, doomedIds)
        }
        _messages.value = live.filter { it.groupId == null }.groupBy { it.peerUin }
        _groupMessages.value = live.filter { it.groupId != null }.groupBy { it.groupId!! }
        cancelShadeFor(
            peers = doomed.filter { it.groupId == null }.map { it.peerUin }.distinct(),
            groups = doomed.mapNotNull { it.groupId }.distinct(),
        )
    }

    /** The disappearing-message timer this 1:1 thread is set to, in seconds,
     *  or null when it is off.
     *
     *  ⚠ READ HERE, at the moment of sending, and NOT taken as an argument
     *  from whoever is sending. Every screen that can put a message into a
     *  thread would otherwise have to remember to look it up, and the web
     *  already learned what that costs: forwarding one line into a room set to
     *  five minutes left a permanent message in it, on every participant's
     *  device, in a conversation whose header says everything disappears. A
     *  send path that reads the destination's own setting cannot be forgotten
     *  by a caller, and the destination is the thread that gets to decide. */
    private fun peerTtl(uin: Int): Int? = LocalStores.threadTtl(LocalStores.peerThread(uin))

    /** The same, for a room. [groupId] is this device's local group id, which
     *  is what the chat screen keys its picker on too (a foreign room's alias
     *  id on both sides). */
    private fun groupTtl(groupId: Int): Int? = LocalStores.threadTtl(LocalStores.groupThread(groupId))

    /** The (ttl, sender timestamp) a content envelope is carrying, so the
     *  sender's OWN row can be dated from the very same numbers that went on
     *  the wire instead of a second clock reading. (null, null) for every kind
     *  that has no timer on it (polls, relay cards, control envelopes). */
    private fun dyingOf(env: Envelope): Pair<Int?, Long?> = when (env) {
        is Envelope.Text -> env.ttl to env.ts
        is Envelope.Photo -> env.ttl to env.ts
        is Envelope.File -> env.ttl to env.ts
        is Envelope.Voice -> env.ttl to env.ts
        is Envelope.Video -> env.ttl to env.ts
        is Envelope.Location -> env.ttl to env.ts
        else -> null to null
    }

    /** Disappearing messages: the TTL (seconds) the sender packed into the
     *  envelope, turned into an absolute epoch-ms deadline. A null or
     *  non-positive [ttl] is permanent.
     *
     *  ⚠⚠ THE COUNTDOWN RUNS FROM WHEN THE MESSAGE WAS SENT, NOT FROM WHEN
     *  THIS DEVICE HAPPENED TO GET IT. This used to be plain `nowMs + ttl`, and
     *  that is a real bug and not a shortcut: a phone offline for a week drains
     *  the queue and then keeps a "vanishes in 5 minutes" message for five
     *  minutes MORE, a week after its author was told it was gone. Three
     *  anchors, best first — see [disappearAnchorMs].
     *
     *  ⚠ NOTHING ALREADY ON DISK IS RE-DATED. Rows written before this change
     *  keep the absolute `expires_at` the receipt anchor gave them, and there
     *  is no migration in [MessageDb]. Not because their ttl is unrecoverable
     *  (for an inbound row it is: `expiresAt - sentAt`, since `sentAt` WAS the
     *  anchor) but because the number a correction would need is the SEND time,
     *  and that was never on the wire for those rows and is not on this disk.
     *  A rewrite could therefore only guess, and both guesses are worse than
     *  leaving it: guess early and a message vanishes out from under someone
     *  mid-sentence, guess late and one the sender was promised was gone comes
     *  back. Every row written from here on is anchored properly and the old
     *  ones age out on their own. */
    private fun expiryFor(ttl: Int?, sentAtSec: Long?, nowMs: Long, depositAtMs: Long? = null): Long? =
        ttl?.takeIf { it > 0 }?.let { disappearAnchorMs(sentAtSec, depositAtMs, nowMs) + it * 1000L }

    /** The instant a disappearing message's countdown starts, in epoch ms.
     *
     *  (a) [sentAtSec], the sender's own `ts` from inside the ciphertext. The
     *      truth, when it is there. Absent from every client build older than
     *      this one, so it cannot be relied on.
     *  (b) [depositAtMs], the island's `received_at` on the queue row. Not the
     *      send time, but the moment the message reached the SERVER, which for
     *      an old peer is far closer to it than the moment this phone finally
     *      came back online and drained.
     *  (c) [nowMs]. The honest "we do not know", and what every build before
     *      this one used for everything.
     *
     *  ⚠ NEITHER (a) NOR (b) IS OUR OWN CLOCK, so both go through
     *  `Envelope.saneAnchorMs` before they are believed — see the rails and
     *  what they are for over there. (b) is our own island's stamp rather than
     *  a peer's, but "our own island" is 165.232.69.229 to some people and a
     *  box in somebody's flat to others, and a wrong stamp does the same damage
     *  whoever set it. */
    private fun disappearAnchorMs(sentAtSec: Long?, depositAtMs: Long?, nowMs: Long): Long =
        Envelope.anchorFromTs(sentAtSec, nowMs)
            ?: Envelope.saneAnchorMs(depositAtMs, nowMs)
            ?: nowMs

    /** Delete every message whose TTL elapsed, from the DB and both in-memory
     *  message flows, so disappearing messages actually disappear from open
     *  chats. Runs on a timer and on chat activity. No-op when nothing expired. */
    private fun sweepExpiredMessages() {
        val expired = db.deleteExpired(System.currentTimeMillis()).toSet()
        if (expired.isEmpty()) return
        // Which threads just lost a row. Read BEFORE the flows are filtered,
        // because afterwards there is nothing left to read it from — and the
        // badge arithmetic below needs the rows in their pre-sweep order, for
        // the same reason.
        val peers = _messages.value.filterValues { l -> l.any { it.id in expired } }.keys.toList()
        val groups = _groupMessages.value.filterValues { l -> l.any { it.id in expired } }.keys.toList()
        for (p in peers) shedLapsedUnread(LocalStores.peerThread(p), _messages.value[p].orEmpty(), expired)
        for (g in groups) shedLapsedUnread(LocalStores.groupThread(g), _groupMessages.value[g].orEmpty(), expired)
        _messages.value = _messages.value.mapValues { (_, list) -> list.filterNot { it.id in expired } }
        _groupMessages.value = _groupMessages.value.mapValues { (_, list) -> list.filterNot { it.id in expired } }
        cancelShadeFor(peers, groups)
    }

    /** Take off [thread]'s badge the rows [expired] just removed from [rows]
     *  that had never been read. [rows] is the thread as it stood BEFORE the
     *  sweep, oldest first.
     *
     *  ⚠⚠ Only the rows INSIDE the unread run may be subtracted, and expiry
     *  systematically takes the OLDEST ones, which are exactly the rows
     *  already read. Subtracting all of them zeroes a live badge: a thread
     *  where six read messages lapse and two unread ones remain would go to
     *  0, and a zero on open means no divider at all, so the two waiting
     *  messages get scrolled past with no marker.
     *
     *  The run starts where counting back [n] of the OTHER side's rows lands,
     *  which is the same walk `ChatScreen`'s `unreadAnchorId` performs: my own
     *  rows (and, in a room, the carbons of them) were never waiting to be
     *  read, so they do not count. web-chat does this arithmetic in
     *  `incoming-store.ts` `sweepExpiredIncoming`. */
    private fun shedLapsedUnread(thread: String, rows: List<ChatMessage>, expired: Set<String>) {
        val n = LocalStores.unreadOf(thread)
        if (n <= 0) return
        var unreadFrom = rows.size
        var counted = 0
        for (i in rows.indices.reversed()) {
            if (rows[i].fromMe) continue
            counted++
            unreadFrom = i
            if (counted == n) break
        }
        var gone = 0
        for (i in unreadFrom until rows.size) if (rows[i].id in expired) gone++
        LocalStores.decUnread(thread, gone)
    }

    /** ⚠ THE CHAT IS NOT THE ONLY PLACE THE TEXT IS. A message that arrived
     *  while the app was in the background is sitting in the shade with its
     *  FULL body, and deleting the row does nothing to that copy: it stays on
     *  the lock screen, and on any paired watch, until somebody happens to
     *  swipe it away, hours after the sender was told it had disappeared. So
     *  the thread's notification goes down with the message.
     *
     *  Shared by BOTH reapers on purpose. It was written into the timer sweep
     *  alone, and the cold-start reap — the path that handles exactly the case
     *  this is about, a message that expired while the app was closed — walked
     *  straight past it. */
    private fun cancelShadeFor(peers: Collection<Int>, groups: Collection<Int>) {
        for (p in peers) app.rcq.android.push.Push.cancelMessageThread(appCtx, null, p)
        for (g in groups) app.rcq.android.push.Push.cancelMessageThread(appCtx, g, null)
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

    /** True when a 1:1 envelope arrived from MY OWN number — i.e. another
     *  device of this account, writing into Saved Messages («Заметки»).
     *
     *  This is what lets the author rule on `delete` / `edit` stand aside for
     *  the notes thread. The rule compares the target's `fromMe` against the
     *  deleter, and in Saved Messages EVERY row is `fromMe`: a note written
     *  here is mine, and a note written on the web arrives as an ordinary
     *  sealed envelope from my own number which [store] deliberately files as
     *  mine (see below). So "only the author may retract this" read as "nobody
     *  may retract this", and a note deleted on the web stayed on the phone for
     *  ever — no reconnect or restart could heal it, because the delete
     *  envelope was ingested, dropped and acked (report #601).
     *
     *  Safe for real conversations because it is not a relaxation at all: the
     *  deleter still has to BE the author. An envelope claiming to come from my
     *  number is sealed to my identity key and signed by my signing key or it
     *  never got as far as this function, so "sender == me" is exactly as
     *  trustworthy as "sender == that peer" is in every other thread. Nothing
     *  changes for a peer thread: there `dec.senderUin` is the peer, this is
     *  false, and the `!fromMe` rule decides on its own as before. */
    private fun fromOwnDevice(senderUin: Int): Boolean {
        val me = store.uin ?: return false
        return senderUin == me
    }

    /** File one received (or locally-minted) row. Answers true when the row was
     *  NEW, false when the UUID was already on this device or is tombstoned,
     *  which is what lets a caller tell a first delivery from a repeat. */
    private fun store(msg: ChatMessage, countsUnread: Boolean = true): Boolean {
        // ★★ A note I wrote on my OTHER device (#599: "заметка, написанная из
        // веба, приходит в приложение как новое сообщение со звуком и пушем, а
        // должно быть просто синхронизировано"). It reaches this device as an
        // ordinary sealed envelope addressed to my own number — which is what
        // makes Saved Messages sync at all — and was then filed as if a
        // stranger had sent it: on the left, with a chime, a banner and an
        // unread badge, for something I typed myself a second ago. It is mine:
        // file it as mine, silently.
        //
        // Only the 1:1 self thread qualifies. A group message cannot arrive
        // from my own number, and an envelope claiming to be from me is signed
        // by my own key or it never got this far.
        val ownNote = !msg.fromMe && msg.groupId == null && msg.peerUin == store.uin
        val row = if (ownNote) msg.copy(fromMe = true) else msg
        // INSERT OR IGNORE dedups by envelope UUID (WS vs queue overlap).
        if (!db.insert(row)) return false
        val cur = _messages.value.toMutableMap()
        cur[row.peerUin] = ((cur[row.peerUin] ?: emptyList()) + row).sortedBy { it.sentAt }
        _messages.value = cur
        // Not everything that lands in a thread is something to catch up on.
        // A finished call is a record of something both people were present
        // for; only a missed one is still owed attention.
        if (countsUnread) bumpUnreadIfInbound(row, LocalStores.peerThread(row.peerUin))
        // (A message that arrives into the open thread is NOT acked here: the
        // chat screen sends the receipt once the row has actually been on
        // screen, which for a reader at the bottom is the next frame and for
        // a reader up in the history is when they get there, #707.)
        // And tell the sender it ARRIVED, whether or not anybody opened it.
        //
        // ⚠ This is the only way the second tick can ever catch up. The island
        // decides "delivered" once, at send time, from whether a socket of ours
        // was live at that instant — so everything written while we were offline
        // kept one tick forever, even after we came back and read it. Sealed
        // sender means the island cannot correct itself later: it does not know
        // who sent the row it just handed us. Only this device knows, so only
        // this device can say.
        //
        // 1:1 only. A group message has as many recipients as members and one
        // tick cannot mean all of them; the phones have never claimed otherwise.
        //
        // ⚠⚠ NEVER FOR A CALL SUMMARY. That row is minted HERE, on this device,
        // out of a call id, and the peer has never seen its message id: the
        // receipt names nothing they can match and moves no tick anywhere. All
        // it does is answer, on the sealed channel, "a human's phone has this
        // open", which is precisely the confirmation the stranger quarantine a
        // few hundred lines up refuses to give and for exactly the same
        // reason. A missed-call marker (#678/#686) turned that into a liveness
        // oracle anyone could poll: deposit a marker, watch for the receipt.
        //
        // ⚠⚠ AND NEVER FOR A NOTICE, for exactly the same reason. `kind =
        // "system"` rows are minted here out of a CONTROL envelope (the peer
        // toggling screenshot alerts, the peer taking a screenshot) and the
        // sender holds no message row with that id either way: the screenshot
        // envelope's id is theirs but was never filed as a message, and the
        // alerts one carries no id at all, so this device makes one up. Either
        // way the receipt moves no tick and says only "somebody's phone is
        // awake over here". The alerts toggle is worse than the call marker
        // was: it costs the sender one menu tap, it is not held by the
        // stranger quarantine, and flipping it back and forth is a liveness
        // oracle anyone with the number can poll for free.
        if (!row.fromMe && row.groupId == null && row.peerUin != store.uin &&
            row.kind != "call" && row.kind != "system") {
            scope.launch {
                runCatching { sendControl(row.peerUin, Envelope.deliveredReceipt(listOf(row.id))) }
            }
        }
        return true
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
    fun bodyMentionsMe(body: String): Boolean {
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
        // Home-row @ indicator (iOS parity, GROUP-ONLY): an inbound group message
        // that @mentions me in a thread I'm not looking at raises the mention inbox.
        if (msg.groupId != null && !msg.fromMe && thread != activeThread && bodyMentionsMe(msg.body)) {
            LocalStores.markMention(LocalStores.groupThread(msg.groupId))
        }
        // Skip the receive sound for BACKLOG: a message pulled out of an
        // offline queue was already announced by the push that woke us, and
        // announcing it a second time is what "о-оу несколько раз" is. The
        // short post-connect window stays as a second net for the burst the
        // server replays over the socket itself.
        val live = drainDepth.get() == 0
        // Notify gate (#11): NONE = silent, MENTIONS = only when @mentioned,
        // ALL = always. One authoritative read of the mute state, so mute is
        // deterministic on both the socket and the push path.
        val ring = when (LocalStores.notifyMode(thread)) {
            LocalStores.NotifyMode.NONE -> false
            LocalStores.NotifyMode.MENTIONS -> msg.groupId != null && bodyMentionsMe(msg.body)
            LocalStores.NotifyMode.ALL -> true
        }
        // A call-summary row is written locally when a call ends — it is not an
        // arriving message, and playing the MESSAGE tone for it made a missed
        // call chime like one.
        if (!live || !ring || msg.kind == "call") return
        if (app.rcq.android.RcqApp.foreground) {
            // On screen: the in-app tone (media stream, the app's own volume
            // slider) plus the in-app banner ARE the notification.
            app.rcq.android.media.SoundService.message()
            // In-app banner for a thread you're NOT looking at.
            if (thread != activeThread) emitBanner(msg, thread)
        } else {
            // Backgrounded: the alert belongs in the shade at the system's
            // notification volume, exactly like a wake. Two things were wrong
            // before. A message that arrived over the live socket only chirped
            // — audible with the screen off, invisible in the shade, and
            // unaffected by the notification settings. And when the wake ALSO
            // arrived (a device whose live socket the server failed to place),
            // the same message sounded twice: quietly here, loudly there. Both
            // paths now post the same notification id inside the same burst
            // window, so whichever gets there first is the one that alerts.
            // If notifications are switched off at the OS level there is no
            // shade to post into, so fall back to the tone rather than to
            // silence.
            if (!notifyInBackground(msg)) app.rcq.android.media.SoundService.message()
        }
    }

    /** Raise the system notification for a message that arrived over the live
     *  socket while the app was in the background. False when the OS has our
     *  notifications switched off and nothing was shown. */
    private fun notifyInBackground(msg: ChatMessage): Boolean {
        val gid = msg.groupId
        // While the app is locked its own history is unreadable, so a preview in
        // the shade would walk straight around the panic PIN. Same rule the wake
        // path follows (PushEnvelope refuses to open anything while locked).
        val locked = app.rcq.android.security.PanicPinService.isLocked
        val preview = notificationPreview(msg)
        return app.rcq.android.push.Push.showLocalMessage(
            ctx = appCtx,
            title = when {
                locked -> appCtx.getString(app.rcq.android.R.string.app_name)
                gid != null -> groupName(gid)
                else -> contactName(msg.peerUin)
            },
            body = when {
                locked -> appCtx.getString(
                    if (gid != null) app.rcq.android.R.string.push_new_group_message
                    else app.rcq.android.R.string.push_new_message,
                )
                // In a group the title is the group, so the sender goes in front
                // of the text — same shape as the wake and as the iOS NSE.
                gid != null && msg.senderUin != null -> "${contactName(msg.senderUin)}: $preview"
                else -> preview
            },
            groupId = gid,
            peerUin = if (gid == null) msg.peerUin else null,
            toUin = store.uin,
        )
    }

    /** One line of the message for a notification — the same shapes
     *  [app.rcq.android.push.PushEnvelope] gives a wake, so a message announced
     *  by the socket and one announced by a push read identically. */
    private fun notificationPreview(msg: ChatMessage): String {
        val text = msg.body.takeIf { it.isNotBlank() }
        fun r(id: Int) = appCtx.getString(id)
        return when (msg.kind) {
            "photo" -> text ?: ("📷 " + r(app.rcq.android.R.string.kind_photo))
            "video" -> text ?: ("🎬 " + r(app.rcq.android.R.string.kind_video))
            "voice" -> "🎤 " + r(app.rcq.android.R.string.kind_voice)
            "file" -> "📎 " + (text ?: r(app.rcq.android.R.string.kind_file))
            "location" -> "📍 " + r(app.rcq.android.R.string.kind_location)
            // ⚠ NEVER `text` here: for a poll the body is the SERIALIZED
            // BALLOT (PollContent.toJson), so this printed a line of raw JSON
            // into the shade. This build can no longer create a poll, which
            // means every poll it will ever announce comes from an old peer and
            // this is the only poll preview left. Same shape the wake gives
            // (PushEnvelope reads env.question).
            "poll" -> "📊 " + (
                app.rcq.android.model.PollContent.fromJson(msg.body)?.question?.takeIf { it.isNotBlank() }
                    ?: r(app.rcq.android.R.string.kind_message)
                )
            "relay" -> "🛡️ " + r(app.rcq.android.R.string.push_kind_relay_share)
            else -> text ?: r(app.rcq.android.R.string.kind_message)
        }
    }

    /** Non-zero while a mailbox drain is feeding [store] — see [live] above.
     *  A counter rather than a flag because the primary, the backup islands and
     *  the visited islands drain concurrently. */
    private val drainDepth = java.util.concurrent.atomic.AtomicInteger(0)

    /** Run [block] with everything it ingests treated as backlog (no sound, no
     *  banner, no notification): the push already announced it. */
    private inline fun <T> asBacklog(block: () -> T): T {
        drainDepth.incrementAndGet()
        try {
            return block()
        } finally {
            drainDepth.decrementAndGet()
        }
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

    /** The thread the user currently has open (or null). Set by the UI so
     *  inbound messages to it don't raise a badge, and so a message that
     *  arrives while it's open is immediately marked read. */
    @Volatile
    var activeThread: String? = null
        private set

    fun openThread(thread: String) {
        activeThread = thread
        val had = LocalStores.unreadOf(thread)
        LocalStores.clearUnread(thread)
        LocalStores.clearReaction(thread)
        LocalStores.clearMention(thread)
        // A2: my other devices drop this thread's badge too. Only worth a
        // packet when there WAS something unread; opening an already-read
        // chat says nothing, so it sends nothing.
        if (had > 0) {
            val peer = thread.removePrefix("peer:").toIntOrNull().takeIf { thread.startsWith("peer:") }
            val gid = thread.removePrefix("group:").toIntOrNull().takeIf { thread.startsWith("group:") }
            if (peer != null || gid != null) scope.launch { sendReadMarker(peer, gid) }
        }
    }

    /** Another device of this account read a thread up to [at] (A2). Recount
     *  rather than clear: a message that landed AFTER that moment is still
     *  unread here, so a marker crossing paths with a fresh message cannot
     *  swallow it. The badge only ever shrinks, so a stale or out-of-order
     *  marker can never un-read a thread. */
    private fun applyRemoteRead(thread: String, at: Long, peer: Int? = null, gid: Int? = null) {
        val current = LocalStores.unreadOf(thread)
        if (current <= 0) return
        val rows = if (gid != null) _groupMessages.value[gid] ?: emptyList()
        else if (peer != null) _messages.value[peer] ?: emptyList()
        else return
        // Only rows somebody else sent count towards a badge.
        val after = rows.count { !it.fromMe && it.sentAt > at }
        val next = minOf(current, after)
        if (next == current) return
        // decUnread drops the entry when it hits zero, exactly like clearUnread.
        LocalStores.decUnread(thread, current - next)
    }

    fun closeThread() {
        activeThread = null
    }

    private fun updateMessageState(id: String, peer: Int, state: DeliveryState) {
        if (state == DeliveryState.FAILED) armFailedRetryTimer()
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
        if (state == DeliveryState.FAILED) armFailedRetryTimer()
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
                    // Home-row reaction-heart (iOS parity): someone else reacting
                    // (asset != null = set, not a clear) to MY message in a thread
                    // I'm not looking at raises the inbox indicator.
                    val thread = LocalStores.peerThread(peer)
                    if (asset != null && reactorUin != store.uin && m.fromMe && thread != activeThread) {
                        LocalStores.markReaction(thread)
                        // Also record WHICH of my messages got reacted, for the
                        // reaction-jump on chat open (scroll to + flash it).
                        LocalStores.markReactedMsg(thread, targetId)
                    }
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
                    // Home-row reaction-heart (iOS parity): someone else reacting
                    // (asset != null = set, not a clear) to MY message in a thread
                    // I'm not looking at raises the inbox indicator.
                    val thread = LocalStores.groupThread(groupId)
                    if (asset != null && reactorUin != store.uin && m.fromMe && thread != activeThread) {
                        LocalStores.markReaction(thread)
                        // Also record WHICH of my messages got reacted, for the
                        // reaction-jump on chat open (scroll to + flash it).
                        LocalStores.markReactedMsg(thread, targetId)
                    }
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
        (if (SealedSender.wireVersion(payloadB64) == 2) {
            SignalSession.decrypt(signalStores, payloadB64, identityPriv(), identityPub())
        } else {
            SealedSender.decryptV1(payloadB64, identityPriv(), identityPub())
        }).also { d ->
            // Any decrypted envelope NAMING its device — a message, a receipt,
            // anything — proves that install can talk to us: its silence probe
            // stands down. v=1 names no device and clears nothing (crediting
            // the primary for a copy that may have come from a sibling is
            // exactly the confusion that kept a dead device unhealed on the
            // web). Both delivery paths (live socket, queue drain) come
            // through here, so this is the one place to listen.
            val dev = d.senderDeviceId
            if (dev != null && d.senderUin != store.uin) {
                awaitingReplySince.remove("${d.senderUin}:$dev")
            }
            // A device the cached list does not have is a device linked after
            // the list was read: drop the list, the next send re-reads it and
            // fans out to the new install too. Our own list leaves our own
            // id out by design, so that id is not a stale-list signal. Nor is
            // an EMPTY list: that is the island answering that it keeps no
            // device registry (every v=2 copy from there names device 1),
            // and dropping it would re-ask on every inbound message.
            if (dev != null) {
                val from = d.senderUin
                peerDeviceCache[from]?.let { (_, known) ->
                    val ours = from == store.uin && dev == myDeviceIdOrNull()
                    if (known.isNotEmpty() && dev !in known && !ours) peerDeviceCache.remove(from)
                }
            }
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

    /** A message push landed while this process is ALIVE: the island has a
     *  row for us that no live frame delivered. Probe the socket (a corpse
     *  is torn down and redialled) and drain both mailboxes, so the message
     *  is on screen by the time the finger reaches the icon - instead of
     *  after the next background/foreground flip (#732, #830). Throttled:
     *  a burst of pushes for one conversation costs one drain. */
    private var lastPushDrain = 0L

    fun pushSaysDrain() {
        if (!started || duressViewUp) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPushDrain < 10_000) return
        lastPushDrain = now
        socket.ensureAlive()
        scope.launch {
            runCatching { drainQueue() }
            runCatching { drainGroupLog() }
        }
    }

    companion object {
        /** The session of this process, for the one caller that lives outside
         *  it: the push service. A message push while the process is alive is
         *  PROOF the island holds something this session has not shown, and
         *  the class of reports where the notification sounds but the chat
         *  stays empty until an app re-enter (#732, #830) is exactly a
         *  silently dead socket that nobody probed because the app never left
         *  the foreground. Set in [start], best-effort by design. */
        @Volatile
        var live: Session? = null
            private set

        /** How long the socket must stay down before the route ladder is walked
         *  again. Longer than the socket's own max backoff (30s) so ordinary
         *  blips are handled where they belong. */
        const val OFFLINE_RELADDER_MS = 90_000L
        /** Floor between two burned-account probes (#655): the socket redials
         *  on its backoff and every 4401 close would otherwise probe again. */
        const val BURN_PROBE_THROTTLE_MS = 60_000L
        /** Held call signals kept while the socket is down (#699). A call is a
         *  handful of frames; a box with no ceiling would replay a crowd after
         *  a long outage. */
        const val CALL_OUTBOX_MAX = 32
        /** Floor between two ladder runs. A ladder walk costs up to three
         *  probes of several seconds each, and on a network that is down for
         *  everyone it would otherwise repeat every minute forever. */
        const val LADDER_COOLDOWN_MS = 5 * 60_000L
        /** Newest news post this device has been shown. */
        const val K_NEWS_SEEN = "news_seen_id"
        /** Un-openable broadcasts held per kid / distinct kids held. */
        const val HELD_GMSG_CAP = 64
        const val HELD_GMSG_KIDS = 16
        /** How long a peer's libsignal device list is reused before it is
         *  asked for again, and the spread around it each entry draws its
         *  own share of. Was five minutes flat: with Stage 3 the lookup
         *  names nobody, but a fixed period per live conversation is a
         *  signature of its own, and a longer hold is fewer lookups. Not a
         *  day: a device linked on the other side has to start receiving
         *  without the sender restarting, and an hour of no carbons was a
         *  real report (2026-08-19). The three stale-list signals on
         *  [peerDeviceCache] cover the rest. */
        const val PEER_DEVICES_TTL_MS = 15 * 60_000L
        const val PEER_DEVICES_JITTER_MS = 3 * 60_000L
        /** How often our OWN list is re-read in the window after a link,
         *  and how long that window lasts (see [ownDeviceListChanged]). */
        const val OWN_DEVICES_SHORT_TTL_MS = 60_000L
        const val OWN_DEVICES_SHORT_WINDOW_MS = 15 * 60_000L
        /** Background rounds a sealed copy that no attempt could place gets
         *  before the message it belongs to is failed, and the gap before the
         *  first of them (it widens by that much each round). Long enough to
         *  outlive a tunnel switch or a relay dropping out; short enough that
         *  the red cross still arrives while the user is looking at the chat. */
        const val COPY_RETRY_ROUNDS = 3
        const val COPY_RETRY_BASE_MS = 5_000L
        /** A socket that keeps dying redials on a curve that starts at one
         *  second, and every redial that succeeds would otherwise be a vault
         *  sweep. One every fifteen seconds is plenty for a change another
         *  device just made. */
        const val VAULT_SWEEP_FLOOR_MS = 15_000L
    }
}
