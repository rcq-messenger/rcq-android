package app.rcq.android.call

import android.content.Context
import android.media.AudioManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps libwebrtc for 1:1 calls — Android port of the iOS `WebRTCManager`.
 * Owns one [PeerConnection] at a time; signalling rides the existing WS pipe
 * via [onLocalIceCandidate]. Media (DTLS-SRTP) is peer-to-peer; the server only
 * relays SDP/ICE. STUN is Google's public set; TURN comes from
 * `/users/me/turn-credentials` (empty → STUN-only, cross-NAT calls may fail).
 */
class WebRtcClient(private val appContext: Context) {
    enum class Media { AUDIO, VIDEO }

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()
    private val _cameraOff = MutableStateFlow(false)
    val cameraOff: StateFlow<Boolean> = _cameraOff.asStateFlow()
    private val _speakerOn = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    /** Local ICE candidate ready to ship (JSON {sdp,sdpMLineIndex,sdpMid}). */
    var onLocalIceCandidate: ((String) -> Unit)? = null
    /** ICE reached a connected/completed state (media is flowing). */
    var onConnected: (() -> Unit)? = null
    /** ICE dropped to DISCONNECTED — transient; may self-heal or escalate. */
    var onDisconnected: (() -> Unit)? = null
    /** ICE FAILED — connectivity checks exhausted; needs a restart/teardown. */
    var onFailed: (() -> Unit)? = null

    private var pc: PeerConnection? = null
    private var localAudio: AudioTrack? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var usingFront = true

    private var turnServers: List<PeerConnection.IceServer> = emptyList()

    // ICE candidate-type tally for the current peer connection (call-failure
    // diagnostics). On a real censored device a failed/timed-out call with
    // relay=0 means NO reachable TURN relay candidate was gathered → STUN-only →
    // the "UI connects, no media" symptom on symmetric NAT/CGNAT. Reset per pc.
    @Volatile private var candHost = 0
    @Volatile private var candSrflx = 0
    @Volatile private var candRelay = 0

    /** Human-readable local ICE candidate tally, e.g. "host=4 srflx=2 relay=0". */
    fun iceDiag(): String = "host=$candHost srflx=$candSrflx relay=$candRelay"

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── TURN ───────────────────────────────────────────────────────────
    fun setTurn(urls: List<String>, username: String, credential: String) {
        CallDiagnostics.turnHost = urls.firstNotNullOfOrNull { url ->
            Regex("^turns?:([^:/?]+)").find(url)?.groupValues?.get(1)
        }
        // With the obfuscated connection up, reach the relay THROUGH it. The
        // credentials are unchanged: TURN authenticates the username, not the
        // address the connection came from, so the island still recognises this
        // as the same user arriving by a different road.
        TurnTunnel.ensureRunning(CallDiagnostics.turnHost)
        // ⚠ ADDED to the island's URLs, never substituted for them. ICE tries
        // every server it is given and uses whichever answers, so offering both
        // roads can only help: on a censored network the direct one is dead and
        // the tunnel carries the call, on an open network the direct one wins on
        // latency and the tunnel costs nothing. Replacing them would mean that
        // any fault in the tunnel took calls away from people whose direct path
        // was fine — trading one outage for another.
        val tunnelled = TurnTunnel.activeUrl()
        val effective = if (tunnelled != null && urls.isNotEmpty()) listOf(tunnelled) + urls else urls
        turnServers = if (effective.isEmpty()) emptyList() else listOf(
            PeerConnection.IceServer.builder(effective)
                .setUsername(username)
                .setPassword(credential)
                .createIceServer(),
        )
        // Our own STUN, taken from whatever host the island handed out for TURN.
        // coturn answers STUN on the same port, and the bundled set is Google's,
        // which is exactly what is unreachable in the networks where calls are
        // reported dead ("ни с обходом, ни без"). Without a reachable STUN there
        // are no srflx candidates at all, so a call between two NATs has nothing
        // to try until TURN allocates. Ours first, Google kept as a fallback for
        // everyone whose network is fine.
        // ⚠ Only forget the probe result when the SERVER SET changed. The probe
        // measures whether this network can reach that TURN host — a property of
        // the network, not of the credentials, which are re-minted before every
        // single call. Resetting it on every refresh meant re-probing (and so
        // waiting out its timeout) on every call, including while answering one.
        // Keyed on the EFFECTIVE set: switching the tunnel on or off changes
        // the road to the relay, and a verdict measured on the other road is
        // worth nothing.
        if (effective != probedUrls) {
            relayUsable = null
            probedUrls = effective
        }
        // ⚠ STUN is derived from the ISLAND's url, never the tunnel's: the
        // forwarder speaks TCP, STUN is UDP, and `stun:127.0.0.1:<tcp port>`
        // is an address nothing answers on.
        ownStun = urls.firstNotNullOfOrNull { url ->
            Regex("^turns?:([^:/?]+)(?::(\\d+))?").find(url)?.let { m ->
                val host = m.groupValues[1]
                val port = m.groupValues[2].ifBlank { "3478" }
                PeerConnection.IceServer.builder("stun:$host:$port").createIceServer()
            }
        }
    }

    /** STUN on the island's own TURN host; null until credentials arrive. */
    private var ownStun: PeerConnection.IceServer? = null

    /** Did a throwaway allocation actually produce a relay candidate?
     *
     *  null = not measured yet. Calls started before the probe finishes do NOT
     *  force RELAY: a call that cannot connect is a worse failure than a call
     *  that leaks an address, and this is exactly the state a user hits when
     *  they place a call two seconds after opening the app.
     */
    @Volatile private var relayUsable: Boolean? = null

    /** The URL set [relayUsable] was measured against, so a re-mint of the same
     *  credentials does not throw the measurement away. */
    private var probedUrls: List<String> = emptyList()

    /** The user chose, for THIS call only, to connect without the relay after
     *  being told it could not carry the call. Never persisted: the next call
     *  starts private again. */
    @Volatile private var relayWaived = false

    fun waiveRelayOnce() { relayWaived = true }

    fun clearRelayWaiver() { relayWaived = false }

    /** Is this connection currently gathering nothing but relay candidates? */
    fun relayOnlyActive(): Boolean = turnServers.isNotEmpty() && relayUsable == true &&
        CallPrivacy.alwaysRelay(appContext) && !relayWaived

    /** Candidates gathered so far on the live connection. */
    fun candidateCount(): Int = candHost + candSrflx + candRelay

    /** Has this network already been measured for TURN reachability? */
    fun relayProbed(): Boolean = relayUsable != null

    /** Throw the measurement away because the network underneath it changed.
     *
     *  ⚠ Caching the probe across credential refreshes is what makes answering
     *  a call fast, and it is also what makes this necessary: a "relay
     *  reachable" measured on Wi-Fi, carried onto a mobile link that blocks it,
     *  forces relay-only on a connection that can gather no relay candidate —
     *  zero candidates, and a call that connects to nothing. Cheaper to
     *  re-measure on every link change than to be confidently wrong once. */
    fun forgetRelayProbe() {
        relayUsable = null
        probedUrls = emptyList()
    }

    /** Last probe verdict; null when never measured. For diagnostics. */
    fun relayReachable(): Boolean? = relayUsable

    /** TURN servers currently configured, and whether the live connection is
     *  forcing every candidate through one. For diagnostics. */
    fun turnServerCount(): Int = turnServers.size
    fun isRelayOnly(): Boolean = turnServers.isNotEmpty() && relayUsable == true

    /** Probe whether this network can reach TURN at all, in the background.
     *
     *  ⚠ Why this exists: 0.103 forced iceTransportsType=RELAY whenever TURN
     *  credentials existed. Credentials existing says nothing about the TURN
     *  server being REACHABLE from the user's network — and where it is not,
     *  RELAY leaves the connection with no candidates whatsoever, so the call
     *  rings and then dies on the connect timeout. Three reports on 0.104 said
     *  exactly that ("ждёт некоторое время и закрывается вызов"), from networks
     *  where the pre-0.103 build called fine.
     *
     *  So: measure, once per credential set, on a throwaway PeerConnection that
     *  carries no media. If a relay candidate turns up, calls are relay-only and
     *  the address stays private. If none does, calls fall back to the old
     *  behaviour and still connect.
     */
    suspend fun probeRelay() {
        val servers = turnServers
        if (servers.isEmpty()) {
            relayUsable = false
            return
        }
        val ok = runCatching { measureRelayReachable(servers) }.getOrDefault(false)
        relayUsable = ok
        android.util.Log.i("RCQcall", "relay probe: reachable=$ok")
    }

    /** Long enough for an allocation on a slow mobile link, short enough that a
     *  call placed right after it is not left waiting. */
    private val RELAY_PROBE_TIMEOUT_MS = 4000L

    private suspend fun measureRelayReachable(
        servers: List<PeerConnection.IceServer>,
    ): Boolean = withContext(Dispatchers.IO) {
        ensureInitialised(appContext)
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        val seen = CompletableDeferred<Boolean>()
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate?.sdp?.contains(" typ relay") == true) seen.complete(true)
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                // Gathering finished without a relay candidate: there is none to be had.
                if (state == PeerConnection.IceGatheringState.COMPLETE) seen.complete(false)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }
        val probe = factory().createPeerConnection(cfg, observer) ?: return@withContext false
        try {
            // A data channel is the cheapest way to give ICE something to gather for.
            probe.createDataChannel("relay-probe", org.webrtc.DataChannel.Init())
            val offer = suspendCancellableCoroutine<SessionDescription?> { cont ->
                probe.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) { cont.resume(sdp) {} }
                    override fun onCreateFailure(err: String?) { cont.resume(null) {} }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(err: String?) {}
                }, MediaConstraints())
            } ?: return@withContext false
            probe.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(err: String?) {}
                override fun onSetSuccess() {}
                override fun onSetFailure(err: String?) {}
            }, offer)
            withTimeoutOrNull(RELAY_PROBE_TIMEOUT_MS) { seen.await() } ?: false
        } finally {
            runCatching { probe.close() }
        }
    }

    // ── call lifecycle ──────────────────────────────────────────────────
    suspend fun createOffer(media: Media): String {
        ensureInitialised(appContext)
        configureAudio(speaker = media == Media.VIDEO)
        val pc = makePeerConnection()
        this.pc = pc
        addLocalTracks(media, pc)
        val offer = pc.createSdp(offer = true, media)
        pc.setLocal(offer)
        return offer.description
    }

    suspend fun handleOffer(remoteSdp: String, media: Media): String {
        ensureInitialised(appContext)
        configureAudio(speaker = media == Media.VIDEO)
        val pc = makePeerConnection()
        this.pc = pc
        pc.setRemote(SessionDescription(SessionDescription.Type.OFFER, remoteSdp))
        addLocalTracks(media, pc)
        val answer = pc.createSdp(offer = false, media)
        pc.setLocal(answer)
        return answer.description
    }

    suspend fun handleAnswer(remoteSdp: String) {
        pc?.setRemote(SessionDescription(SessionDescription.Type.ANSWER, remoteSdp))
    }

    // ── ICE restart (recover a dropped connection without losing tracks) ────
    /** Caller side: re-offer with fresh ICE credentials (new ufrag/pwd) so a
     *  stalled/failed connection re-gathers candidates. Tracks are untouched —
     *  this is purely a transport recovery, not a renegotiation of media. */
    suspend fun restartIce(): String {
        val pc = pc ?: error("no active peer connection")
        val media = if (_localVideo.value != null) Media.VIDEO else Media.AUDIO
        val offer = pc.createSdp(offer = true, media, iceRestart = true)
        pc.setLocal(offer)
        return offer.description
    }

    /** Callee side: answer the caller's ICE-restart offer (no track changes;
     *  the new remote ufrag triggers our side's ICE restart automatically). */
    suspend fun handleIceRestartOffer(remoteSdp: String): String {
        val pc = pc ?: error("no active peer connection")
        val media = if (_localVideo.value != null) Media.VIDEO else Media.AUDIO
        pc.setRemote(SessionDescription(SessionDescription.Type.OFFER, remoteSdp))
        val answer = pc.createSdp(offer = false, media)
        pc.setLocal(answer)
        return answer.description
    }

    suspend fun handleIceRestartAnswer(remoteSdp: String) {
        pc?.setRemote(SessionDescription(SessionDescription.Type.ANSWER, remoteSdp))
    }

    // ── audio → video upgrade (renegotiation) ────────────────────────────
    suspend fun upgradeToVideo(): String {
        val pc = pc ?: error("no active peer connection")
        configureAudio(speaker = true)
        if (_localVideo.value == null) addLocalVideoTrack(pc)
        val offer = pc.createSdp(offer = true, Media.VIDEO)
        pc.setLocal(offer)
        return offer.description
    }

    suspend fun handleRenegotiationOffer(remoteSdp: String): String {
        val pc = pc ?: error("no active peer connection")
        configureAudio(speaker = true)
        pc.setRemote(SessionDescription(SessionDescription.Type.OFFER, remoteSdp))
        if (_localVideo.value == null) addLocalVideoTrack(pc)
        val answer = pc.createSdp(offer = false, Media.VIDEO)
        pc.setLocal(answer)
        return answer.description
    }

    suspend fun handleRenegotiationAnswer(remoteSdp: String) {
        pc?.setRemote(SessionDescription(SessionDescription.Type.ANSWER, remoteSdp))
    }

    /** Strip the just-added local video after a declined/failed upgrade. */
    fun rollbackVideoUpgrade() {
        stopCapture()
        val track = _localVideo.value
        if (track != null) {
            pc?.senders?.filter { it.track()?.id() == track.id() }?.forEach { pc?.removeTrack(it) }
        }
        _localVideo.value = null
        _cameraOff.value = false
    }

    /** Can this connection accept a remote candidate right now? WebRTC throws
     *  a candidate away unless a remote description is already set, so anything
     *  arriving earlier has to be buffered by the caller of [addRemoteIce]. */
    fun canTakeRemoteIce(): Boolean = pc?.remoteDescription != null

    fun addRemoteIce(candidateJSON: String) {
        val pc = pc ?: return
        runCatching {
            val o = JsonParser.parseString(candidateJSON).asJsonObject
            val sdp = o.get("sdp")?.asString ?: return
            val idx = o.get("sdpMLineIndex")?.asInt ?: 0
            val mid = o.get("sdpMid")?.takeIf { !it.isJsonNull }?.asString
            // The return value is the only signal that a candidate was rejected;
            // dropping it on the floor is how the buffering bug above stayed
            // invisible for as long as it did.
            if (!pc.addIceCandidate(IceCandidate(mid, idx, sdp))) {
                android.util.Log.w("RCQcall", "remote ICE candidate rejected: ${sdp.take(60)}")
            }
        }
    }

    /** Idempotent. */
    fun close() {
        stopCapture()
        videoSource?.dispose()
        videoSource = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        runCatching { pc?.close() }
        pc = null
        localAudio = null
        _localVideo.value = null
        _remoteVideo.value = null
        _micMuted.value = false
        _cameraOff.value = false
        _speakerOn.value = false
        usingFront = true
        runCatching {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
    }

    // ── in-call controls ─────────────────────────────────────────────────
    fun toggleMicMute() {
        val muted = !_micMuted.value
        localAudio?.setEnabled(!muted)
        _micMuted.value = muted
    }

    fun toggleCameraOff() {
        val track = _localVideo.value ?: return
        val off = track.enabled()
        track.setEnabled(!off)
        _cameraOff.value = !track.enabled()
    }

    fun flipCamera() {
        (capturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { usingFront = isFront }
            override fun onCameraSwitchError(error: String?) {}
        })
    }

    fun toggleSpeaker() {
        val on = !_speakerOn.value
        audioManager.isSpeakerphoneOn = on
        _speakerOn.value = on
    }

    fun eglContext(): EglBase.Context = eglBase().eglBaseContext

    // ── internals ─────────────────────────────────────────────────────────
    private fun makePeerConnection(): PeerConnection {
        val servers = ArrayList<PeerConnection.IceServer>()
        ownStun?.let { servers.add(it) }
        servers.addAll(STUN)
        servers.addAll(turnServers)
        candHost = 0; candSrflx = 0; candRelay = 0
        // ⚠ Calls used to run with the default policy, which gathers host and
        // srflx candidates and puts them in the SDP. That hands the other side
        // your LAN addresses and your real public IP, before a word is spoken
        // and regardless of whether the messenger itself is riding a relay —
        // WebRTC opens its own UDP sockets and does not go through sing-box.
        // For an app whose pitch is that it does not know where you are, that
        // was the largest hole we had, and no privacy setting could close it.
        //
        // RELAY forces every candidate through our TURN server: the peer sees
        // the TURN address and nothing else. The cost is real (all media now
        // transits our box, so bandwidth and a little latency) and accepted.
        //
        // The fallback matters: with RELAY and no TURN server there are no
        // candidates at all, i.e. the call silently never connects. When creds
        // are missing (fetch failed, offline island) keep the old behaviour
        // rather than shipping a phone that cannot ring.
        // ⚠ Relay-only ONLY when a relay candidate was actually obtainable on this
        // network (see probeRelay). 0.103 keyed this on "credentials exist",
        // which is not the same thing, and on networks that cannot reach TURN it
        // left the connection with no candidates at all — the call rang and then
        // died on the connect timeout. Reported three times on 0.104.
        // ⚠ Three things must all hold, and the third is new: the user has to
        // still want relay-only. Losing that check is how a relay problem turns
        // into "calls do not work" with no way out from the handset.
        val relayOnly = turnServers.isNotEmpty() && relayUsable == true &&
            CallPrivacy.alwaysRelay(appContext) && !relayWaived
        android.util.Log.i(
            "RCQcall",
            "peerConnection: ${turnServers.size} TURN server(s), relayOnly=$relayOnly (probe=$relayUsable)",
        )
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            if (relayOnly) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        return factory().createPeerConnection(cfg, pcObserver)
            ?: error("could not create peer connection")
    }

    private fun addLocalTracks(media: Media, pc: PeerConnection) {
        val audioSource = factory().createAudioSource(MediaConstraints())
        val audio = factory().createAudioTrack("rcq_audio0", audioSource)
        localAudio = audio
        pc.addTrack(audio, listOf(STREAM_ID))
        if (media == Media.VIDEO) addLocalVideoTrack(pc)
    }

    private fun addLocalVideoTrack(pc: PeerConnection) {
        val source = factory().createVideoSource(false)
        videoSource = source
        val helper = SurfaceTextureHelper.create("RCQCapture", eglContext())
        surfaceHelper = helper
        val cap = createCameraCapturer() ?: return
        capturer = cap
        cap.initialize(helper, appContext, source.capturerObserver)
        cap.startCapture(1280, 720, 30)
        val track = factory().createVideoTrack("rcq_video0", source)
        track.setEnabled(true)
        _localVideo.value = track
        pc.addTrack(track, listOf(STREAM_ID))
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val names = enumerator.deviceNames
        val front = names.firstOrNull { enumerator.isFrontFacing(it) }
        val name = front ?: names.firstOrNull() ?: return null
        usingFront = front != null
        return enumerator.createCapturer(name, null)
    }

    private fun stopCapture() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
    }

    /** WebRTC plays remote audio through whichever device the AudioManager
     *  mode selects; MODE_IN_COMMUNICATION routes to earpiece (or speaker
     *  when [speaker]). Mirrors the iOS voiceChat session config. */
    private fun configureAudio(speaker: Boolean) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speaker
            _speakerOn.value = speaker
        }
    }

    private fun mediaConstraints(media: Media) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (media == Media.VIDEO) "true" else "false"))
    }

    private suspend fun PeerConnection.createSdp(
        offer: Boolean,
        media: Media,
        iceRestart: Boolean = false,
    ): SessionDescription =
        suspendCancellableCoroutine { cont ->
            val obs = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
                override fun onCreateFailure(e: String?) { if (cont.isActive) cont.resumeWithException(RuntimeException("createSdp: $e")) }
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String?) {}
            }
            val constraints = mediaConstraints(media).apply {
                if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
            if (offer) createOffer(obs, constraints) else createAnswer(obs, constraints)
        }

    private suspend fun PeerConnection.setLocal(sdp: SessionDescription): Unit =
        suspendCancellableCoroutine { cont ->
            setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onSetFailure(e: String?) { if (cont.isActive) cont.resumeWithException(RuntimeException("setLocal: $e")) }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, sdp)
        }

    private suspend fun PeerConnection.setRemote(sdp: SessionDescription): Unit =
        suspendCancellableCoroutine { cont ->
            setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onSetFailure(e: String?) { if (cont.isActive) cont.resumeWithException(RuntimeException("setRemote: $e")) }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, sdp)
        }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            when {
                candidate.sdp.contains(" typ relay") -> candRelay++
                candidate.sdp.contains(" typ srflx") -> candSrflx++
                candidate.sdp.contains(" typ host") -> candHost++
            }
            val payload = JsonObject().apply {
                addProperty("sdp", candidate.sdp)
                addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
                addProperty("sdpMid", candidate.sdpMid ?: "")
            }
            onLocalIceCandidate?.invoke(payload.toString())
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            android.util.Log.i("RCQcall", "ICE $state (local cand ${iceDiag()})")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> onConnected?.invoke()
                PeerConnection.IceConnectionState.DISCONNECTED -> onDisconnected?.invoke()
                PeerConnection.IceConnectionState.FAILED -> onFailed?.invoke()
                // CLOSED arrives only when we tear the pc down ourselves; the
                // call is already ending, so there is nothing to recover.
                else -> Unit
            }
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            (receiver?.track() as? VideoTrack)?.let { _remoteVideo.value = it }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            (transceiver?.receiver?.track() as? VideoTrack)?.let { _remoteVideo.value = it }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    companion object {
        private const val STREAM_ID = "rcq_stream0"
        private val STUN = listOf(
            PeerConnection.IceServer.builder(
                listOf(
                    "stun:stun.l.google.com:19302",
                    "stun:stun1.l.google.com:19302",
                    "stun:stun2.l.google.com:19302",
                ),
            ).createIceServer(),
        )

        @Volatile private var sharedFactory: PeerConnectionFactory? = null
        @Volatile private var sharedEgl: EglBase? = null

        private fun eglBase(): EglBase = sharedEgl ?: error("WebRtcClient not initialised")
        private fun factory(): PeerConnectionFactory = sharedFactory ?: error("WebRtcClient not initialised")

        /** Process-wide one-time init of libwebrtc + the shared factory/EGL.
         *  Idempotent; call before constructing a client. */
        @Synchronized
        fun ensureInitialised(context: Context) {
            if (sharedFactory != null) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            val egl = EglBase.create()
            sharedEgl = egl
            sharedFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                // ⚠ libwebrtc ignores the loopback adapter when it enumerates
                // networks, which means it cannot reach a TURN server on
                // 127.0.0.1 — and that is exactly where [TurnTunnel] puts one
                // so that call media can ride the obfuscated connection. Left
                // at the default, the tunnel is built, listens, and is never
                // dialled: the probe comes back "no relay" in half a second.
                //
                // Clearing the mask only makes loopback usable, it does not put
                // loopback candidates anywhere they matter: the tunnelled path
                // is relay-only, so the sole candidate gathered is the relay
                // address the island allocated.
                .setOptions(PeerConnectionFactory.Options().apply { networkIgnoreMask = 0 })
                .createPeerConnectionFactory()
        }

        fun sharedEglContext(): EglBase.Context = eglBase().eglBaseContext

        /** Shared factory for reuse by the audio-room mesh (after
         *  [ensureInitialised]). */
        fun peerConnectionFactory(): PeerConnectionFactory = factory()

        /** Google STUN set, shared with the mesh. */
        fun stunServers(): List<PeerConnection.IceServer> = STUN
    }
}
