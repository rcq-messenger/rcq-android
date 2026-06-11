package app.rcq.android.call

import android.content.Context
import app.rcq.android.R
import app.rcq.android.net.RcqApi
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val appendHistory: (peerUin: Int, fromMe: Boolean, text: String) -> Unit,
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

    private var pendingRemoteOffer: String? = null
    private val pendingRemoteIce = mutableListOf<String>()
    private var pendingRenegotiationOffer: String? = null
    private var outgoingVideoUpgradePending = false
    private var connectedSince = 0L

    init {
        rtc.onLocalIceCandidate = { json -> shipIce(json) }
        rtc.onFailed = { scope.launch { onRtcFailed() } }
        // Media actually started flowing (ICE connected). Until this fires the
        // UI shows "Connecting…" instead of a running timer — the old code
        // marked the call connected the instant the SDP answer landed, so a
        // call that never completed ICE (dead TURN path, symmetric NAT) showed
        // a ticking timer with silence ("соединяется и тишина").
        rtc.onConnected = { scope.launch { onRtcConnected() } }
    }

    // ── public API ──────────────────────────────────────────────────────
    fun start(peerUin: Int, media: Media) {
        if (_state.value.active) return
        WebRtcClient.ensureInitialised(appContext) // EGL ready before the screen composes
        val call = CallInfo(UUID.randomUUID().toString(), peerUin, nameFor(peerUin), media, outgoing = true)
        _state.value = State.Outgoing(call)
        scope.launch {
            try {
                refreshTurn()
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
        val call = s.info
        WebRtcClient.ensureInitialised(appContext)
        ringer.stop()
        scope.launch {
            try {
                refreshTurn()
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
    fun toggleSpeaker() = rtc.toggleSpeaker()

    // ── WS signalling in ──────────────────────────────────────────────────
    /** Routed from Session.handleEvent for the call_* event types. */
    fun onSignal(type: String, obj: JsonObject) {
        val from = obj.get("from_uin")?.takeIf { !it.isJsonNull }?.asInt ?: return
        val callId = obj.get("call_id")?.takeIf { !it.isJsonNull }?.asString ?: ""
        when (type) {
            "call_offer" -> handleIncomingOffer(from, callId, obj)
            "call_answer" -> handleAnswer(callId, obj.get("sdp")?.asString ?: "")
            "call_ice" -> handleIce(callId, obj.get("candidate")?.asString ?: "")
            "call_end" -> handleRemoteEnd(callId, obj.get("reason")?.takeIf { !it.isJsonNull }?.asString ?: "ended")
            "call_renegotiate" -> handleRenegotiate(callId, obj.get("sdp")?.asString ?: "")
            "call_renegotiate_answer" -> handleRenegotiateAnswer(callId, obj.get("sdp")?.asString ?: "")
            "call_renegotiate_decline" -> handleRenegotiateDecline(callId)
        }
    }

    private fun handleIncomingOffer(from: Int, callId: String, obj: JsonObject) {
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
        ringer.startIncoming()
        armRingTimeout(call)
    }

    private fun handleAnswer(callId: String, sdp: String) {
        val s = _state.value as? State.Outgoing ?: return
        if (s.info.id != callId) return
        val call = s.info
        scope.launch {
            try {
                rtc.handleAnswer(sdp)
                _state.value = State.Connected(call)
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
        _state.value = State.Ended(call, reason)
        rtc.close()
        ringer.stop()
        pendingRemoteOffer = null
        pendingRemoteIce.clear()
        pendingRenegotiationOffer = null
        _incomingVideoUpgrade.value = false
        outgoingVideoUpgradePending = false
        connectedSince = 0L
        _connectedAtMs.value = 0L
        scope.launch {
            delay(2500)
            if (_state.value is State.Ended) _state.value = State.Idle
        }
    }

    private suspend fun onRtcFailed() {
        val call = _state.value.info ?: return
        if (_state.value is State.Connected) finishEnded(call, "peer_disconnected")
    }

    /** ICE connected → media is flowing. Start the call timer for real (the
     *  "Connecting…" status flips to the running duration). */
    private fun onRtcConnected() {
        if (_state.value is State.Connected && connectedSince == 0L) markConnected()
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
                endLocally(call, "setup_failed")
            }
        }
    }

    /** Burn/rebind/wipe hook: drop any in-flight call without signalling. */
    fun teardown() {
        rtc.close()
        ringer.stop()
        pendingRemoteOffer = null
        pendingRemoteIce.clear()
        pendingRenegotiationOffer = null
        _incomingVideoUpgrade.value = false
        outgoingVideoUpgradePending = false
        connectedSince = 0L
        _connectedAtMs.value = 0L
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

    private suspend fun refreshTurn() {
        runCatching {
            val c = withContext(Dispatchers.IO) { turn() }
            rtc.setTurn(c.urls, c.username, c.credential)
        }.onFailure { rtc.setTurn(emptyList(), "", "") }
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
        val tail = if (durationMs >= 1000) {
            formatDuration(durationMs / 1000)
        } else {
            appContext.getString(
                when (reason) {
                    "declined", "declinedElsewhere" -> R.string.call_out_declined
                    "cancelled" -> if (call.outgoing) R.string.call_out_cancelled else R.string.call_out_missed
                    "busy" -> R.string.call_out_busy
                    "expired", "unanswered" -> if (call.outgoing) R.string.call_out_no_answer else R.string.call_out_missed
                    "setup_failed" -> R.string.call_out_failed
                    "peer_disconnected" -> R.string.call_out_disconnected
                    else -> R.string.call_out_ended
                },
            )
        }
        appendHistory(call.peerUin, call.outgoing, "$mediaLabel · $tail")
    }

    private fun formatDuration(secs: Long): String = "%d:%02d".format(secs / 60, secs % 60)

    private fun Media.toRtc() = if (this == Media.VIDEO) WebRtcClient.Media.VIDEO else WebRtcClient.Media.AUDIO
}
