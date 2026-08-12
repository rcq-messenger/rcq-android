package app.rcq.android.call

import android.content.Context
import app.rcq.android.R
import app.rcq.android.net.RcqApi
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 1:1 call state machine — Android port of the iOS `CallService` (minus
 * CallKit, which has no Android analogue; we ring in-app). Bridges the WS
 * dumb-relay signalling and [WebRtcClient] media. Single-call invariant: a
 * second inbound offer while busy is auto-declined with reason "busy".
 *
 * No VoIP/FCM push yet, so an incoming call only rings while the callee's app
 * is alive and the WS is connected — wake-from-killed needs FCM (deferred).
 */
class CallController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val ownUin: () -> Int?,
    private val send: (JsonObject) -> Unit,
    private val turn: suspend () -> RcqApi.TurnCreds,
    private val nameFor: (Int) -> String,
    private val appendHistory: (
        peerUin: Int,
        fromMe: Boolean,
        text: String,
        /** The call never connected, so it is still owed attention. */
        missed: Boolean,
        /** Epoch ms the call STARTED, which is what the history row shows. */
        startedAt: Long,
    ) -> Unit,
) {
    enum class Media(val wire: String) {
        AUDIO("audio"), VIDEO("video");
        companion object {
            fun fromWire(w: String) = if (w == "video") VIDEO else AUDIO
        }
    }

    data class CallInfo(
        val id: String,
        val peerUin: Int,
        val peerNickname: String,
        val media: Media,
        val outgoing: Boolean,
    )

    sealed interface State {
        val info: CallInfo?
        val active: Boolean get() = this is Outgoing || this is Incoming || this is Connected

        data object Idle : State { override val info: CallInfo? = null }
        data class Outgoing(override val info: CallInfo) : State
        data class Incoming(override val info: CallInfo) : State
        data class Connected(override val info: CallInfo) : State
        data class Ended(override val info: CallInfo, val reason: String) : State
    }

    val rtc = WebRtcClient(appContext)
    private val ringer = Ringer(appContext)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val micMuted get() = rtc.micMuted
    val cameraOff get() = rtc.cameraOff
    val speakerOn get() = rtc.speakerOn
    val localVideo get() = rtc.localVideo
    val remoteVideo get() = rtc.remoteVideo

    private val _incomingVideoUpgrade = MutableStateFlow(false)
    val incomingVideoUpgrade: StateFlow<Boolean> = _incomingVideoUpgrade.asStateFlow()
    private val _connectedAtMs = MutableStateFlow(0L)
    val connectedAtMs: StateFlow<Long> = _connectedAtMs.asStateFlow()

    /** The island had no live socket for the callee when our offer arrived, so
     *  it went out as a wake-up push instead. Set on the OUTGOING side only,
     *  and only until the call moves on. Drives both the silence and the line
     *  under the name — see [handleCalleeOffline]. */
    private val _peerOffline = MutableStateFlow(false)
    val peerOffline: StateFlow<Boolean> = _peerOffline.asStateFlow()

    /** True while the current Incoming call's accept/decline surface is the
     *  full-screen IncomingCallActivity (raised because the offer arrived over the
     *  WS while the app was backgrounded). The in-app CallScreen suppresses its own
     *  accept/decline buttons in that case so the two surfaces can't both drive the
     *  same call (double-accept / decline-then-resurrect). Cleared when the call
     *  ends or tears down. */
    private val _incomingViaFsi = MutableStateFlow(false)
    val incomingViaFsi: StateFlow<Boolean> = _incomingViaFsi.asStateFlow()

    private var pendingRemoteOffer: String? = null
    private val pendingRemoteIce = mutableListOf<String>()
    private var pendingRenegotiationOffer: String? = null
    private var outgoingVideoUpgradePending = false
    private var connectedSince = 0L
    /** Reentrancy guard: an accept() is mid-flight (TURN fetch + handleOffer).
     *  Blocks a second accept() — a fast double-tap, or the FSI-accept drain
     *  racing an in-app tap — from running handleOffer twice on one connection
     *  (which throws and kills the just-answered call). */
    private var accepting = false

    // ICE-recovery state. Touched from IO-pool timers + the rtc callbacks, so
    // the decision points are guarded by recoveryLock.
    private val recoveryLock = Any()
    private var disconnectGraceJob: Job? = null
    private var calleeFailsafeJob: Job? = null
    private var iceRestarting = false
    private var iceRestartAttempts = 0
    // Bumped on every confirmed (re)connect so a restart-timeout that lost the
    // lock race against onRtcConnected sees a stale epoch and no-ops.
    private var iceRestartEpoch = 0

    init {
        rtc.onLocalIceCandidate = { json -> shipIce(json) }
        // Media actually started flowing (ICE connected). Until this fires the
        // UI shows "Connecting…" instead of a running timer — the old code
        // marked the call connected the instant the SDP answer landed, so a
        // call that never completed ICE (dead TURN path, symmetric NAT) showed
        // a ticking timer with silence ("соединяется и тишина").
        rtc.onConnected = { scope.launch { onRtcConnected() } }
        // ICE recovery: a transient DISCONNECTED gets a grace window to
        // self-heal; a hard FAILED triggers one or two caller-initiated ICE
        // restarts (glare-avoided) before the call is declared dead. Previously
        // any FAILED ended the call instantly — a brief network blip killed a
        // live call that ICE could have recovered.
        rtc.onDisconnected = { scope.launch { onIceDisconnected() } }
        rtc.onFailed = { scope.launch { onIceFailed() } }

        // Give a live call a control surface that does not depend on an Activity.
        // Android has no CallKit, so hanging up meant reaching the in-app
        // CallScreen, and any path that leaves a connected call without
        // MainActivity in front leaves the user talking with no way to stop
        // (reported on 0.100 after accepting from the lock screen). Driven off
        // the state flow rather than from each transition, so a state this file
        // grows later cannot forget to raise it.
        scope.launch {
            _state.collect { s ->
                val call = s.info
                when {
                    call == null || s is State.Ended ->
                        app.rcq.android.push.Push.cancelOngoingCall(appContext)
                    // An unanswered incoming call is the RINGING notification's
                    // job, not this one; showing both would offer End twice for
                    // one call and race the decline path.
                    s is State.Incoming -> Unit
                    else -> app.rcq.android.push.Push.showOngoingCall(
                        appContext, call.id, call.peerNickname, connected = s is State.Connected,
                    )
                }
            }
        }
        registerHangUpReceiver()
        // A ringing call has to survive the person leaving the app.
        //
        // An offer that arrives while the app is in front rings in-app, on the
        // CallScreen. Put the app aside mid-ring and that screen goes away with
        // it: no notification was ever posted for this call, so there was
        // nothing left to answer from, and the call rang out. Reported as
        // "звонят в открытое приложение, но я сворачиваю его — должен появиться
        // пуш с кнопками принять и отклонить".
        //
        // So hand the ring over to the same full-screen-intent notification the
        // backgrounded path uses, and take it back on return. Nothing is posted
        // for a call that is already up: that one has its own ongoing surface.
        app.rcq.android.RcqApp.onForegroundChange = { fg -> onAppForegroundChanged(fg) }
    }

    private fun onAppForegroundChanged(foreground: Boolean) {
        val s = _state.value as? State.Incoming ?: return
        val call = s.info
        val offer = pendingRemoteOffer ?: return
        if (foreground) {
            if (!_incomingViaFsi.value) return
            _incomingViaFsi.value = false
            app.rcq.android.push.Push.cancelCallNotification(appContext)
            ringer.startIncoming()
        } else {
            if (_incomingViaFsi.value) return
            ringer.stop()
            _incomingViaFsi.value = true
            app.rcq.android.push.Push.showIncomingCall(
                appContext,
                JsonObject().apply {
                    addProperty("call_id", call.id)
                    addProperty("from_uin", call.peerUin)
                    addProperty("sdp", offer)
                    addProperty("media", call.media.wire)
                    addProperty("nickname", call.peerNickname)
                },
            )
        }
    }

    /** The End button on the ongoing-call notification, and Decline on the ringing
     *  one. Registered at runtime rather than in the manifest: the only object that
     *  can end a call is this controller, and a manifest receiver would have to find
     *  its way back to a live instance that may not exist. Explicit + NOT_EXPORTED,
     *  and it checks the call id, so a stale PendingIntent cannot end a later call.
     *
     *  Declining is not the same as hanging up: the caller should be told they were
     *  turned down, not that the call was cancelled, so route by state rather than
     *  by which button was pressed. */
    private fun registerHangUpReceiver() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                val id = intent?.getStringExtra(app.rcq.android.push.Push.EXTRA_CALL_ID) ?: return
                if (_state.value.info?.id != id) return
                if (_state.value is State.Incoming) decline() else hangUp()
            }
        }
        val filter = android.content.IntentFilter(app.rcq.android.push.Push.ACTION_HANG_UP).apply {
            addAction(app.rcq.android.push.Push.ACTION_DECLINE_CALL)
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        }
    }

    // ── public API ──────────────────────────────────────────────────────
    fun start(peerUin: Int, media: Media) {
        if (_state.value.active) return
        WebRtcClient.ensureInitialised(appContext) // EGL ready before the screen composes
        val call = CallInfo(UUID.randomUUID().toString(), peerUin, nameFor(peerUin), media, outgoing = true)
        _state.value = State.Outgoing(call)
        scope.launch {
            try {
                val turnOk = refreshTurn()
                android.util.Log.i("RCQcall", "call ${call.id.take(8)} outgoing media=${media.wire} turn=$turnOk")
                val sdp = rtc.createOffer(media.toRtc())
                send(signal("call_offer", peerUin, call.id, mapOf("media" to media.wire, "sdp" to sdp)))
                ringer.startRingback()
                armRingTimeout(call)
            } catch (e: Exception) {
                android.util.Log.e("RCQcall", "createOffer failed: ${e.message}")
                endLocally(call, "setup_failed")
            }
        }
    }

    fun accept() {
        val s = _state.value as? State.Incoming ?: return
        val offer = pendingRemoteOffer ?: return
        if (accepting) return
        accepting = true
        val call = s.info
        WebRtcClient.ensureInitialised(appContext)
        ringer.stop()
        scope.launch {
            try {
                val turnOk = refreshTurn()
                android.util.Log.i("RCQcall", "call ${call.id.take(8)} answering media=${call.media.wire} turn=$turnOk")
                val answerSdp = rtc.handleOffer(offer, call.media.toRtc())
                _state.value = State.Connected(call)
                send(signal("call_answer", call.peerUin, call.id, mapOf("sdp" to answerSdp)))
                pendingRemoteIce.forEach { rtc.addRemoteIce(it) }
                pendingRemoteIce.clear()
                pendingRemoteOffer = null
                armConnectTimeout(call)
            } catch (e: Exception) {
                android.util.Log.e("RCQcall", "handleOffer failed: ${e.message}")
                endLocally(call, "setup_failed")
            }
        }
    }

    fun decline() {
        val s = _state.value as? State.Incoming ?: return
        endLocally(s.info, "declined")
    }

    fun hangUp() {
        val call = _state.value.info ?: return
        val reason = when (_state.value) {
            is State.Outgoing -> "cancelled"
            is State.Connected -> "hangup"
            else -> "cancelled"
        }
        endLocally(call, reason)
    }

    // ── audio → video upgrade ─────────────────────────────────────────────
    fun requestVideoUpgrade() {
        val s = _state.value as? State.Connected ?: return
        if (s.info.media != Media.AUDIO || outgoingVideoUpgradePending) return
        outgoingVideoUpgradePending = true
        val call = s.info
        scope.launch {
            try {
                val sdp = rtc.upgradeToVideo()
                _state.value = State.Connected(call.copy(media = Media.VIDEO))
                send(signal("call_renegotiate", call.peerUin, call.id, mapOf("sdp" to sdp)))
            } catch (e: Exception) {
                outgoingVideoUpgradePending = false
                rtc.rollbackVideoUpgrade()
            }
        }
    }

    fun acceptVideoUpgrade() {
        val s = _state.value as? State.Connected ?: run { clearUpgrade(); return }
        val offer = pendingRenegotiationOffer ?: run { clearUpgrade(); return }
        _incomingVideoUpgrade.value = false
        val call = s.info
        scope.launch {
            try {
                val answerSdp = rtc.handleRenegotiationOffer(offer)
                _state.value = State.Connected(call.copy(media = Media.VIDEO))
                send(signal("call_renegotiate_answer", call.peerUin, call.id, mapOf("sdp" to answerSdp)))
            } catch (e: Exception) {
                send(signal("call_renegotiate_decline", call.peerUin, call.id, emptyMap()))
                rtc.rollbackVideoUpgrade()
            }
            pendingRenegotiationOffer = null
        }
    }

    fun declineVideoUpgrade() {
        val s = _state.value as? State.Connected ?: run { clearUpgrade(); return }
        _incomingVideoUpgrade.value = false
        pendingRenegotiationOffer = null
        send(signal("call_renegotiate_decline", s.info.peerUin, s.info.id, emptyMap()))
    }

    // controls
    fun toggleMic() = rtc.toggleMicMute()
    fun toggleCamera() = rtc.toggleCameraOff()
    fun flipCamera() = rtc.flipCamera()
    /** The speaker button has to work before the call connects too.
     *
     *  The ringback tone is a ToneGenerator, and one picks its output when it is
     *  constructed: flipping the AudioManager under a playing generator leaves
     *  the tone where it started, so pressing Speaker during the ringing kept
     *  the tone in the earpiece and looked like the button did nothing
     *  ("если во время гудков включить кнопку динамик, звук продолжает идти для
     *  уха"). The route change is real, it just cannot reach a tone that is
     *  already playing — so the tone is restarted onto the new one.
     *
     *  ⚠ Not while the callee is offline: there is deliberately no tone then
     *  ([handleCalleeOffline]), and restarting one here would put it back. */
    fun toggleSpeaker() {
        rtc.toggleSpeaker()
        if (_state.value is State.Outgoing && !_peerOffline.value) ringer.startRingback()
    }

    // ── WS signalling in ──────────────────────────────────────────────────
    /** Routed from Session.handleEvent for the call_* event types. */
    fun onSignal(type: String, obj: JsonObject) {
        val from = obj.get("from_uin")?.takeIf { !it.isJsonNull }?.asInt ?: return
        val callId = obj.get("call_id")?.takeIf { !it.isJsonNull }?.asString ?: ""
        when (type) {
            "call_offer" -> handleIncomingOffer(from, callId, obj)
            "call_answer" -> handleAnswer(callId, obj.get("sdp")?.asString ?: "")
            "call_ice" -> {
                // Cross-island calls may batch a burst of trickle candidates
                // into one envelope (`candidates` = JSON array string); a
                // same-island WS signal still carries a single `candidate`.
                val batch = obj.get("candidates")?.takeIf { !it.isJsonNull }?.asString
                if (batch != null) {
                    runCatching { com.google.gson.JsonParser.parseString(batch).asJsonArray }
                        .getOrNull()?.forEach { handleIce(callId, it.asString) }
                } else {
                    handleIce(callId, obj.get("candidate")?.asString ?: "")
                }
            }
            "call_end" -> handleRemoteEnd(callId, obj.get("reason")?.takeIf { !it.isJsonNull }?.asString ?: "ended")
            // The island has no socket and no push endpoint for them, so
            // nothing is going to ring on the other side at all. End it now
            // instead of playing a ringback for thirty seconds and calling it
            // "no answer" — the caller was being told they are being ignored
            // when they were not being reached (user request).
            "call_unreachable" -> handleRemoteEnd(callId, "unreachable")
            // Reachable, but not connected: the offer went out as a push, and
            // until that push wakes their phone nothing is ringing anywhere.
            // The tone was inventing an alerting phone on the other end, so it
            // stops and the screen says what is really going on (#463).
            "call_offline" -> handleCalleeOffline(callId)
            "call_renegotiate" -> handleRenegotiate(callId, obj.get("sdp")?.asString ?: "")
            "call_renegotiate_answer" -> handleRenegotiateAnswer(callId, obj.get("sdp")?.asString ?: "")
            "call_renegotiate_decline" -> handleRenegotiateDecline(callId)
            "call_ice_restart" -> handleIceRestart(callId, obj.get("sdp")?.asString ?: "")
            "call_ice_restart_answer" -> handleIceRestartAnswer(callId, obj.get("sdp")?.asString ?: "")
        }
    }

    /** Peer's ICE-restart offer (its connection dropped and it re-gathered);
     *  answer it in place so media resumes without re-ringing. */
    private fun handleIceRestart(callId: String, sdp: String) {
        val s = _state.value as? State.Connected ?: return
        if (s.info.id != callId || sdp.isEmpty()) return
        // Don't answer a restart while our own renegotiation (video upgrade) is
        // mid-handshake — the pc isn't in a stable signaling state, so applying
        // a remote offer would throw. The caller retries after the upgrade settles.
        if (outgoingVideoUpgradePending || pendingRenegotiationOffer != null || _incomingVideoUpgrade.value) return
        scope.launch {
            runCatching {
                val answer = rtc.handleIceRestartOffer(sdp)
                send(signal("call_ice_restart_answer", s.info.peerUin, s.info.id, mapOf("sdp" to answer)))
            }.onFailure { android.util.Log.e("RCQcall", "handleIceRestart failed: ${it.message}") }
        }
    }

    private fun handleIceRestartAnswer(callId: String, sdp: String) {
        val s = _state.value as? State.Connected ?: return
        if (s.info.id != callId || sdp.isEmpty()) return
        scope.launch {
            runCatching { rtc.handleIceRestartAnswer(sdp) }
                .onFailure { android.util.Log.e("RCQcall", "handleIceRestartAnswer failed: ${it.message}") }
        }
    }

    private fun handleIncomingOffer(from: Int, callId: String, obj: JsonObject, ring: Boolean = true) {
        if (_state.value.active) {
            send(signal("call_end", from, callId, mapOf("reason" to "busy")))
            return
        }
        val mediaWire = obj.get("media")?.takeIf { !it.isJsonNull }?.asString ?: "video"
        val sdp = obj.get("sdp")?.asString ?: return
        val call = CallInfo(callId, from, nameFor(from), Media.fromWire(mediaWire), outgoing = false)
        pendingRemoteOffer = sdp
        pendingRemoteIce.clear()
        _state.value = State.Incoming(call)
        // The push-accept path already rang on the lock screen and accepts
        // immediately, so skip the in-app ringer + ring watchdog there.
        if (ring) {
            if (app.rcq.android.RcqApp.foreground) {
                ringer.startIncoming()
                armRingTimeout(call)
            } else {
                // App backgrounded: a WS-delivered offer can't present the in-app
                // CallScreen (no Activity launches from the background), so the user
                // would get a ring with no UI — or nothing with the screen off.
                // Raise the SAME full-screen-intent surface the push path uses; it
                // shows IncomingCallActivity over the lockscreen and rings there.
                // Accept hands back to MainActivity, which re-enters this controller
                // via onPushOffer (already State.Incoming → accept()). Adds no new
                // wake transport (no FCM, no foreground service) — this is an offer
                // we already hold over the live WS.
                val fsi = JsonObject().apply {
                    addProperty("call_id", callId)
                    addProperty("from_uin", from)
                    addProperty("sdp", sdp)
                    addProperty("media", mediaWire)
                    addProperty("nickname", call.peerNickname)
                }
                _incomingViaFsi.value = true
                app.rcq.android.push.Push.showIncomingCall(appContext, fsi)
                armRingTimeout(call)
            }
        }
    }

    /** Feed a push-delivered offer the user already accepted on the lock-screen
     *  [IncomingCallActivity]: set up Incoming state WITHOUT re-ringing, then
     *  accept. Called from MainActivity once the WS is connected. */
    fun onPushOffer(obj: JsonObject) {
        val from = obj.get("from_uin")?.takeIf { !it.isJsonNull }?.asInt ?: return
        val callId = obj.get("call_id")?.takeIf { !it.isJsonNull }?.asString ?: ""
        // This offer was accepted on the full-screen surface (lock-screen
        // IncomingCallActivity), so the in-app CallScreen must not also show
        // accept/decline during the brief Incoming window before accept() flips to
        // Connected — true for both the killed-app push and backgrounded-WS cases.
        _incomingViaFsi.value = true
        // The offer may already be set up by the live WS (backgrounded-but-alive
        // case: handleIncomingOffer raised the full-screen UI and we're already
        // ringing this exact call). Re-running handleIncomingOffer would trip the
        // `active` busy-guard and send the caller "busy" — so just accept the
        // call we already hold.
        val cur = _state.value
        if (cur is State.Incoming && cur.info.id == callId) { accept(); return }
        handleIncomingOffer(from, callId, obj, ring = false)
        accept()
    }

    private fun handleAnswer(callId: String, sdp: String) {
        val s = _state.value as? State.Outgoing ?: return
        if (s.info.id != callId) return
        val call = s.info
        scope.launch {
            try {
                rtc.handleAnswer(sdp)
                _state.value = State.Connected(call)
                _peerOffline.value = false
                ringer.stop()
                pendingRemoteIce.forEach { rtc.addRemoteIce(it) }
                pendingRemoteIce.clear()
                armConnectTimeout(call)
            } catch (e: Exception) {
                endLocally(call, "setup_failed")
            }
        }
    }

    private fun handleIce(callId: String, candidateJSON: String) {
        val call = _state.value.info ?: return
        if (call.id != callId || candidateJSON.isEmpty()) return
        // Stash ICE while still ringing inbound (no peer connection until accept).
        if (_state.value is State.Incoming) pendingRemoteIce.add(candidateJSON)
        else rtc.addRemoteIce(candidateJSON)
    }

    /** The callee has no live socket: our offer became a push. Stop the ringback
     *  — a tone that means "their phone is ringing" must not play while nothing
     *  is ringing — but do NOT end the call: the push may still wake them inside
     *  the ring window, and answering has to keep working. */
    private fun handleCalleeOffline(callId: String) {
        val s = _state.value as? State.Outgoing ?: return
        if (s.info.id != callId) return
        _peerOffline.value = true
        ringer.stop()
    }

    fun handleRemoteEnd(callId: String, reason: String) {
        val call = _state.value.info ?: return
        if (call.id != callId) return
        finishEnded(call, reason)
    }

    private fun handleRenegotiate(callId: String, sdp: String) {
        val s = _state.value as? State.Connected ?: return
        if (s.info.id != callId) return
        if (s.info.media != Media.AUDIO) {
            // already on video: idempotent auto-accept
            scope.launch {
                runCatching {
                    val answer = rtc.handleRenegotiationOffer(sdp)
                    send(signal("call_renegotiate_answer", s.info.peerUin, s.info.id, mapOf("sdp" to answer)))
                }
            }
            return
        }
        pendingRenegotiationOffer = sdp
        _incomingVideoUpgrade.value = true
    }

    private fun handleRenegotiateAnswer(callId: String, sdp: String) {
        val s = _state.value as? State.Connected ?: return
        if (s.info.id != callId) return
        outgoingVideoUpgradePending = false
        scope.launch { runCatching { rtc.handleRenegotiationAnswer(sdp) } }
    }

    private fun handleRenegotiateDecline(callId: String) {
        val s = _state.value as? State.Connected ?: return
        if (s.info.id != callId) return
        outgoingVideoUpgradePending = false
        rtc.rollbackVideoUpgrade()
        if (s.info.media == Media.VIDEO) _state.value = State.Connected(s.info.copy(media = Media.AUDIO))
    }

    // ── teardown ──────────────────────────────────────────────────────────
    /** Local hangup/decline/cancel: signal the peer, then end. */
    private fun endLocally(call: CallInfo, reason: String) {
        send(signal("call_end", call.peerUin, call.id, mapOf("reason" to reason)))
        finishEnded(call, reason)
    }

    private fun finishEnded(call: CallInfo, reason: String) {
        if (_state.value is State.Ended) return
        val duration = if (connectedSince > 0) System.currentTimeMillis() - connectedSince else 0L
        logHistory(call, reason, duration)
        // An incoming call that rang out leaves a row in the chat and, until
        // this, nothing anywhere else: the ringing notification is cancelled as
        // the ring ends, so someone who was away from the phone learned they
        // had been called only by opening the app.
        //
        // Same test [logHistory] uses to mark the row missed, deliberately: two
        // definitions of "missed" would drift, and the notification saying one
        // thing while the chat says another is worse than neither.
        if (isMissed(call, duration, reason)) {
            app.rcq.android.push.Push.showMissedCall(
                appContext,
                peerUin = call.peerUin,
                nickname = call.peerNickname,
                video = call.media == Media.VIDEO,
            )
        }
        _state.value = State.Ended(call, reason)
        rtc.close()
        ringer.stop()
        // Tear down any full-screen incoming-call UI we raised for a backgrounded
        // offer (idempotent: harmless if none was showing). Covers every end path
        // — remote hangup, local decline, and the unanswered/connect timeouts.
        app.rcq.android.push.Push.dismissIncomingCall(appContext, call.id)
        pendingRemoteOffer = null
        pendingRemoteIce.clear()
        pendingRenegotiationOffer = null
        _incomingVideoUpgrade.value = false
        _incomingViaFsi.value = false
        _peerOffline.value = false
        accepting = false
        outgoingVideoUpgradePending = false
        connectedSince = 0L
        _connectedAtMs.value = 0L
        resetRecoveryState()
        scope.launch {
            delay(2500)
            if (_state.value is State.Ended) _state.value = State.Idle
        }
    }

    /** ICE connected → media is flowing. Start the call timer for real (the
     *  "Connecting…" status flips to the running duration) and clear any
     *  in-flight recovery — we are healthy again. */
    private fun onRtcConnected() {
        // Media is flowing now — a connected call must never still be ringing.
        // accept()/handleAnswer() already stop the ringer, but guarantee it here
        // too so no path or race (duplicate offer, push+WS double-deliver) can
        // leave the ringtone/ringback looping after the call connects.
        ringer.stop()
        cancelRecoveryTimers()
        // Recovery succeeded (or first connect): clear the in-flight flag, restore
        // the per-incident restart budget (it's per-drop, not per-call), and bump
        // the epoch so a racing restart-timeout drops out.
        synchronized(recoveryLock) { iceRestarting = false; iceRestartAttempts = 0; iceRestartEpoch++ }
        if (_state.value is State.Connected && connectedSince == 0L) markConnected()
    }

    // ── ICE recovery ────────────────────────────────────────────────────────
    /** Transient DISCONNECTED on an established call: give ICE a short grace to
     *  re-establish on its own before forcing a restart, so a momentary network
     *  hiccup (handoff, brief loss) doesn't tear down a working call. */
    private fun onIceDisconnected() {
        val call = _state.value.info ?: return
        if (_state.value !is State.Connected || connectedSince == 0L) return
        synchronized(recoveryLock) {
            if (disconnectGraceJob != null) return
            disconnectGraceJob = scope.launch {
                delay(ICE_DISCONNECT_GRACE_MS)
                synchronized(recoveryLock) { disconnectGraceJob = null }
                if (_state.value.info?.id == call.id && _state.value is State.Connected) {
                    attemptIceRestartOrEnd(call)
                }
            }
        }
    }

    /** Hard ICE FAILED. Before media ever flowed this is a setup failure (the
     *  old immediate-end behaviour). On an established call, try to recover. */
    private fun onIceFailed() {
        val call = _state.value.info ?: return
        if (_state.value !is State.Connected) return
        if (connectedSince == 0L) { finishEnded(call, "peer_disconnected"); return }
        cancelDisconnectGrace()
        attemptIceRestartOrEnd(call)
    }

    /** Recovery policy for an established call whose ICE dropped. Only the
     *  original caller re-offers (glare avoidance); the callee waits for that
     *  re-offer and ends the call if it never recovers. The caller retries up
     *  to [MAX_ICE_RESTARTS] times, then gives up with "peer_disconnected". */
    private fun attemptIceRestartOrEnd(call: CallInfo) {
        // An ICE restart reuses the offer/answer machinery; don't collide with
        // a video-upgrade renegotiation mid-handshake — just end if one's live.
        if (outgoingVideoUpgradePending || pendingRenegotiationOffer != null || _incomingVideoUpgrade.value) {
            finishEnded(call, "peer_disconnected"); return
        }
        if (!call.outgoing) { scheduleCalleeFailsafe(call); return }
        var doRestart = false
        var doEnd = false
        synchronized(recoveryLock) {
            when {
                iceRestarting -> Unit // a restart is already in flight; let it run
                iceRestartAttempts >= MAX_ICE_RESTARTS -> doEnd = true
                else -> { iceRestarting = true; iceRestartAttempts++; doRestart = true }
            }
        }
        if (doEnd) { finishEnded(call, "peer_disconnected"); return }
        if (!doRestart) return
        scope.launch {
            try {
                val sdp = rtc.restartIce()
                send(signal("call_ice_restart", call.peerUin, call.id, mapOf("sdp" to sdp)))
                armIceRestartTimeout(call)
            } catch (e: Exception) {
                android.util.Log.e("RCQcall", "ICE restart offer failed: ${e.message}")
                synchronized(recoveryLock) { iceRestarting = false }
                finishEnded(call, "peer_disconnected")
            }
        }
    }

    /** If a caller's ICE restart hasn't reconnected within the window, retry it
     *  (within the attempt budget) or give up. No-op once media reconnects
     *  (onRtcConnected clears `iceRestarting`). */
    private fun armIceRestartTimeout(call: CallInfo) {
        val epoch = synchronized(recoveryLock) { iceRestartEpoch }
        scope.launch {
            delay(ICE_RESTART_TIMEOUT_MS)
            val stuck = synchronized(recoveryLock) {
                if (iceRestarting && iceRestartEpoch == epoch) { iceRestarting = false; true } else false
            }
            if (stuck && _state.value.info?.id == call.id && _state.value is State.Connected) {
                attemptIceRestartOrEnd(call)
            }
        }
    }

    /** Callee side: the caller owns the restart, so just wait. If the call
     *  hasn't recovered after a generous window (covers the caller's full
     *  restart budget), end it rather than sit on dead media forever. */
    private fun scheduleCalleeFailsafe(call: CallInfo) {
        synchronized(recoveryLock) {
            if (calleeFailsafeJob != null) return
            calleeFailsafeJob = scope.launch {
                delay(CALLEE_FAILSAFE_MS)
                synchronized(recoveryLock) { calleeFailsafeJob = null }
                if (_state.value.info?.id == call.id && _state.value is State.Connected) {
                    finishEnded(call, "peer_disconnected")
                }
            }
        }
    }

    private fun cancelDisconnectGrace() {
        synchronized(recoveryLock) { disconnectGraceJob?.cancel(); disconnectGraceJob = null }
    }

    private fun cancelRecoveryTimers() {
        synchronized(recoveryLock) {
            disconnectGraceJob?.cancel(); disconnectGraceJob = null
            calleeFailsafeJob?.cancel(); calleeFailsafeJob = null
        }
    }

    private fun resetRecoveryState() {
        cancelRecoveryTimers()
        synchronized(recoveryLock) { iceRestarting = false; iceRestartAttempts = 0 }
    }

    /** Ringing watchdog, both directions. Outgoing: the offer can be silently
     *  lost (peer offline same-island, old peer client ignoring a §5d
     *  cross-island call envelope) — stop ringback after 60s as "no answer".
     *  Incoming: if the caller's app died mid-ring no call_end ever arrives —
     *  stop ringing after 60s as missed. No-op once the state moved on. */
    private fun armRingTimeout(call: CallInfo) {
        scope.launch {
            delay(60_000)
            if (_state.value.info?.id != call.id) return@launch
            when (_state.value) {
                is State.Outgoing -> endLocally(call, "unanswered")
                is State.Incoming -> finishEnded(call, "expired")
                else -> Unit
            }
        }
    }

    /** If ICE never completes within the window (dead TURN path / symmetric
     *  NAT on both ends), end the call cleanly instead of leaving the user on a
     *  silent "Connecting…" forever. No-op once media has connected. */
    private fun armConnectTimeout(call: CallInfo) {
        scope.launch {
            delay(35_000)
            if (_state.value.info?.id == call.id &&
                _state.value is State.Connected && connectedSince == 0L
            ) {
                // Diagnostics: relay=0 here means ICE never gathered a TURN relay
                // candidate → STUN-only → cross-NAT media never formed (the root
                // cause of "UI connects, no media" on CGNAT/symmetric NAT).
                android.util.Log.w("RCQcall", "connect timeout call=${call.id.take(8)} ${rtc.iceDiag()}")
                endLocally(call, "setup_failed")
            }
        }
    }

    /** Burn/rebind/wipe hook: drop any in-flight call without signalling. */
    fun teardown() {
        rtc.close()
        ringer.stop()
        // Also drop any full-screen incoming UI raised for a backgrounded offer.
        _state.value.info?.let { app.rcq.android.push.Push.dismissIncomingCall(appContext, it.id) }
        pendingRemoteOffer = null
        pendingRemoteIce.clear()
        pendingRenegotiationOffer = null
        _incomingVideoUpgrade.value = false
        _incomingViaFsi.value = false
        _peerOffline.value = false
        accepting = false
        outgoingVideoUpgradePending = false
        connectedSince = 0L
        _connectedAtMs.value = 0L
        resetRecoveryState()
        _state.value = State.Idle
    }

    fun clearEnded() {
        if (_state.value is State.Ended) _state.value = State.Idle
    }

    // ── helpers ─────────────────────────────────────────────────────────
    private fun markConnected() {
        connectedSince = System.currentTimeMillis()
        _connectedAtMs.value = connectedSince
    }

    private fun clearUpgrade() {
        _incomingVideoUpgrade.value = false
        pendingRenegotiationOffer = null
    }

    private fun shipIce(candidateJSON: String) {
        val call = _state.value.info ?: return
        send(signal("call_ice", call.peerUin, call.id, mapOf("candidate" to candidateJSON)))
    }

    /** Fetch fresh TURN credentials, retrying a transient fetch failure a few
     *  times before giving up. A single blip used to silently leave the call
     *  STUN-only, which dooms a cross-NAT (symmetric) call to the connect
     *  timeout; we retry it away. If TURN is genuinely unreachable we proceed
     *  STUN-only (better than refusing the call) but log loudly so it's
     *  diagnosable. @return true if real TURN servers were configured. */
    private suspend fun refreshTurn(): Boolean {
        repeat(TURN_FETCH_ATTEMPTS) { attempt ->
            val creds = withContext(Dispatchers.IO) { runCatching { turn() } }.getOrNull()
            if (creds != null) {
                rtc.setTurn(creds.urls, creds.username, creds.credential)
                // Measure whether this network can actually reach TURN before a
                // call decides to be relay-only. Costs a few seconds once per
                // credential fetch and saves the call that would otherwise ring
                // into a timeout on a network where TURN is blocked.
                rtc.probeRelay()
                if (creds.urls.isEmpty()) {
                    android.util.Log.w("RCQcall", "TURN endpoint returned no servers — STUN-only call")
                }
                return creds.urls.isNotEmpty()
            }
            if (attempt < TURN_FETCH_ATTEMPTS - 1) delay(TURN_RETRY_DELAY_MS)
        }
        rtc.setTurn(emptyList(), "", "")
        android.util.Log.e(
            "RCQcall",
            "TURN fetch failed after $TURN_FETCH_ATTEMPTS attempts — STUN-only (cross-NAT may fail)",
        )
        return false
    }

    private fun signal(type: String, toUin: Int, callId: String, extras: Map<String, String>): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            addProperty("to_uin", toUin)
            addProperty("call_id", callId)
            extras.forEach { (k, v) -> addProperty(k, v) }
        }

    private fun logHistory(call: CallInfo, reason: String, durationMs: Long) {
        val mediaLabel = appContext.getString(
            if (call.media == Media.VIDEO) R.string.call_hist_video else R.string.call_hist_voice,
        )
        val connected = durationMs >= 1000
        val tail = if (connected) {
            formatDuration(durationMs / 1000)
        } else {
            appContext.getString(
                when (reason) {
                    "declined", "declinedElsewhere" -> R.string.call_out_declined
                    "cancelled" -> if (call.outgoing) R.string.call_out_cancelled else R.string.call_out_missed
                    "busy" -> R.string.call_out_busy
                    "expired", "unanswered" -> if (call.outgoing) R.string.call_out_no_answer else R.string.call_out_missed
                    "unreachable" -> R.string.call_out_unreachable
                    "setup_failed" -> R.string.call_out_failed
                    "peer_disconnected" -> R.string.call_out_disconnected
                    else -> R.string.call_out_ended
                },
            )
        }
        val missed = isMissed(call, durationMs, reason)
        appendHistory(
            call.peerUin,
            call.outgoing,
            "$mediaLabel · $tail",
            missed,
            System.currentTimeMillis() - durationMs,
        )
    }

    /** "Missed" is narrower than "did not connect": a call I placed and
     *  cancelled, or one I declined myself, is not something waiting for me.
     *  Only an inbound call that never connected is. */
    private fun isMissed(call: CallInfo, durationMs: Long, reason: String): Boolean =
        durationMs < 1000 && !call.outgoing &&
            reason !in setOf("declined", "declinedElsewhere", "busy")

    private fun formatDuration(secs: Long): String = "%d:%02d".format(secs / 60, secs % 60)

    private fun Media.toRtc() = if (this == Media.VIDEO) WebRtcClient.Media.VIDEO else WebRtcClient.Media.AUDIO

    companion object {
        private const val TURN_FETCH_ATTEMPTS = 3
        private const val TURN_RETRY_DELAY_MS = 800L
        // ICE recovery tuning. A transient drop self-heals within the grace
        // window most of the time; otherwise the caller re-offers (up to MAX),
        // each attempt given the restart timeout to reconnect. The callee's
        // failsafe spans the caller's whole budget so it doesn't end early.
        private const val ICE_DISCONNECT_GRACE_MS = 4_000L
        private const val ICE_RESTART_TIMEOUT_MS = 12_000L
        private const val MAX_ICE_RESTARTS = 2
        private const val CALLEE_FAILSAFE_MS = 32_000L
    }
}
