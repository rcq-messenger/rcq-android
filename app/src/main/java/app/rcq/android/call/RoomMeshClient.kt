package app.rcq.android.call

import android.content.Context
import android.media.AudioManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Mesh WebRTC for an audio room — one [PeerConnection] per other member
 * (≤7 at full capacity). Android port of the iOS `AudioRoomMeshManager`,
 * audio-only for v1 (video-in-rooms deferred). Server convention: the
 * EXISTING member is the offerer when a newcomer enters, so the newcomer just
 * waits for offers — no glare without an ordering protocol. Reuses
 * [WebRtcClient]'s shared factory/EGL; signalling is shipped via the
 * send-callbacks (the controller wraps them in room_offer/answer/ice).
 */
class RoomMeshClient(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val sendOffer: (toUin: Int, sdp: String) -> Unit,
    private val sendAnswer: (toUin: Int, sdp: String) -> Unit,
    private val sendIce: (toUin: Int, candidateJson: String) -> Unit,
) {
    private val lock = Any()
    private val peers = HashMap<Int, PeerConnection>()
    private val pendingIce = HashMap<Int, MutableList<String>>()
    private var localAudio: AudioTrack? = null
    private var turn: List<PeerConnection.IceServer> = emptyList()

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Loudspeaker vs earpiece for the room. A room is a conference, so the
     *  loudspeaker is still what you get on entry — but it was the ONLY thing
     *  you could get, with no button and no way back to the earpiece, which is
     *  what a tester asked for ("аудиокомнаты можно использовать не только по
     *  громкой связи"). Kept here rather than in the controller because this is
     *  the object that owns the AudioManager. */
    private val _speakerOn = MutableStateFlow(true)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    fun start(turnServers: List<PeerConnection.IceServer>) {
        WebRtcClient.ensureInitialised(appContext)
        turn = turnServers
        // Every entry starts on the loudspeaker: the choice belongs to this
        // room, not to whatever the previous one was left on.
        _speakerOn.value = true
        configureAudio()
        val factory = WebRtcClient.peerConnectionFactory()
        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudio = factory.createAudioTrack("rcq_room_audio0", audioSource)
    }

    fun stop() {
        synchronized(lock) {
            peers.values.forEach { runCatching { it.close() } }
            peers.clear()
            pendingIce.clear()
        }
        localAudio = null
        runCatching {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
    }

    fun setMicMuted(muted: Boolean) {
        localAudio?.setEnabled(!muted)
    }

    /** Move the room's audio between the loudspeaker and the earpiece. Unlike a
     *  1:1 call there is no tone playing here, so flipping the AudioManager is
     *  the whole job. */
    fun setSpeaker(on: Boolean) {
        _speakerOn.value = on
        runCatching { audioManager.isSpeakerphoneOn = on }
    }

    /** Existing-member side: a newcomer entered, dial them. */
    fun dialNewPeer(uin: Int) {
        val pc: PeerConnection
        synchronized(lock) {
            if (peers[uin] != null) return
            pc = makePeerConnection(uin)
            peers[uin] = pc
        }
        attachLocalMedia(pc)
        scope.launch {
            runCatching {
                val offer = pc.createSdp(offer = true)
                pc.setLocal(offer)
                sendOffer(uin, offer.description)
            }.onFailure { android.util.Log.e("RCQroom", "dial offer failed uin=$uin: ${it.message}") }
        }
    }

    fun handleOffer(fromUin: Int, sdp: String) {
        val pc: PeerConnection
        val fresh: Boolean
        synchronized(lock) {
            val existing = peers[fromUin]
            if (existing == null) {
                pc = makePeerConnection(fromUin)
                peers[fromUin] = pc
                fresh = true
            } else {
                pc = existing
                fresh = false
            }
        }
        if (fresh) attachLocalMedia(pc)
        scope.launch {
            runCatching {
                pc.setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
                val answer = pc.createSdp(offer = false)
                pc.setLocal(answer)
                sendAnswer(fromUin, answer.description)
                drainPendingIce(fromUin, pc)
            }.onFailure { android.util.Log.e("RCQroom", "handleOffer failed uin=$fromUin: ${it.message}") }
        }
    }

    fun handleAnswer(fromUin: Int, sdp: String) {
        val pc = synchronized(lock) { peers[fromUin] } ?: return
        scope.launch {
            runCatching {
                pc.setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                drainPendingIce(fromUin, pc)
            }.onFailure { android.util.Log.e("RCQroom", "handleAnswer failed uin=$fromUin: ${it.message}") }
        }
    }

    fun handleIce(fromUin: Int, candidateJson: String) {
        synchronized(lock) {
            val pc = peers[fromUin]
            if (pc != null) applyIce(candidateJson, pc)
            else pendingIce.getOrPut(fromUin) { mutableListOf() }.add(candidateJson)
        }
    }

    fun peerUins(): Set<Int> = synchronized(lock) { peers.keys.toSet() }

    /** Every leg at once, for a re-entry after the island evicted us: the
     *  others dial fresh and their offers must not land on these. */
    fun dropAllPeers() {
        synchronized(lock) {
            peers.values.forEach { runCatching { it.close() } }
            peers.clear()
            pendingIce.clear()
        }
    }

    fun dropPeer(uin: Int) {
        synchronized(lock) {
            peers.remove(uin)?.let { runCatching { it.close() } }
            pendingIce.remove(uin)
        }
    }

    // ── internals ─────────────────────────────────────────────────────
    private fun makePeerConnection(remoteUin: Int): PeerConnection {
        val servers = ArrayList(WebRtcClient.stunServers())
        servers.addAll(turn)
        // Same reasoning as WebRtcClient.makePeerConnection: without RELAY every
        // participant learns every other participant's real IP. In a room that
        // matters MORE than in a 1:1 call, because the people in it are not
        // necessarily each other's contacts. Falls back to the default policy
        // when no TURN creds arrived, otherwise the room silently never
        // connects.
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            if (turn.isNotEmpty()) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        return WebRtcClient.peerConnectionFactory().createPeerConnection(cfg, observerFor(remoteUin))
            ?: error("could not create room peer connection")
    }

    private fun attachLocalMedia(pc: PeerConnection) {
        localAudio?.let { pc.addTrack(it, listOf("rcq_room_stream0")) }
    }

    private fun applyIce(json: String, pc: PeerConnection) {
        runCatching {
            val o = JsonParser.parseString(json).asJsonObject
            val sdp = o.get("sdp")?.asString ?: return
            val idx = o.get("sdpMLineIndex")?.asInt ?: 0
            val mid = o.get("sdpMid")?.takeIf { !it.isJsonNull }?.asString
            pc.addIceCandidate(IceCandidate(mid, idx, sdp))
        }
    }

    private fun drainPendingIce(uin: Int, pc: PeerConnection) {
        val queued = synchronized(lock) { pendingIce.remove(uin) } ?: return
        queued.forEach { applyIce(it, pc) }
    }

    private fun audioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private suspend fun PeerConnection.createSdp(offer: Boolean): SessionDescription =
        suspendCancellableCoroutine { cont ->
            val obs = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
                override fun onCreateFailure(e: String?) { if (cont.isActive) cont.resumeWithException(RuntimeException("createSdp: $e")) }
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String?) {}
            }
            if (offer) createOffer(obs, audioConstraints()) else createAnswer(obs, audioConstraints())
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

    private fun observerFor(remoteUin: Int) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            val payload = JsonObject().apply {
                addProperty("sdp", candidate.sdp)
                addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
                addProperty("sdpMid", candidate.sdpMid ?: "")
            }
            sendIce(remoteUin, payload.toString())
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        override fun onTrack(transceiver: RtpTransceiver?) {}
    }

    private fun configureAudio() {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = _speakerOn.value
        }
    }
}
