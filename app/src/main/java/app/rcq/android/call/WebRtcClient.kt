package app.rcq.android.call

import android.content.Context
import android.media.AudioManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── TURN ───────────────────────────────────────────────────────────
    fun setTurn(urls: List<String>, username: String, credential: String) {
        turnServers = if (urls.isEmpty()) emptyList() else listOf(
            PeerConnection.IceServer.builder(urls)
                .setUsername(username)
                .setPassword(credential)
                .createIceServer(),
        )
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

    fun addRemoteIce(candidateJSON: String) {
        val pc = pc ?: return
        runCatching {
            val o = JsonParser.parseString(candidateJSON).asJsonObject
            val sdp = o.get("sdp")?.asString ?: return
            val idx = o.get("sdpMLineIndex")?.asInt ?: 0
            val mid = o.get("sdpMid")?.takeIf { !it.isJsonNull }?.asString
            pc.addIceCandidate(IceCandidate(mid, idx, sdp))
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
        val servers = ArrayList(STUN)
        servers.addAll(turnServers)
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
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
            val payload = JsonObject().apply {
                addProperty("sdp", candidate.sdp)
                addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
                addProperty("sdpMid", candidate.sdpMid ?: "")
            }
            onLocalIceCandidate?.invoke(payload.toString())
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
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
