package app.rcq.android.nearby

import android.content.Context
import app.rcq.android.crypto.RadioCrypto
import app.rcq.android.model.RadioMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the hybrid Radio mesh — Android port of the iOS `RadioService`.
 * Ties [RadioBleDiscovery] (ambient presence), [RadioWifiDirect] (the data
 * session), [RadioVoiceEngine] (PTT) and [RadioCrypto] (per-session keys)
 * together behind a Compose-friendly StateFlow surface.
 *
 * Radio is fully offline / peer-to-peer: no server, no WS, no REST. It reuses
 * the anonymous Nearby display name as the on-air label (the real UIN never
 * goes on the Radio wire) and, like the other live controllers, tears down on
 * lock / account-switch / burn / wipe.
 *
 * Sessions come in two shapes (mirroring iOS):
 *   - **1:1** — tap a person → Wi-Fi Direct connect → a 2-message X25519 ECDH
 *     handshake derives the session key → encrypted chat.
 *   - **Room** — host creates a room (open or password); the key is derived
 *     from the room id (open) or PBKDF2(password) (closed). The first sealed
 *     frame from the host that decrypts proves the password and admits the
 *     joiner; a decrypt failure ejects them.
 *
 * ⚠ Everything below the crypto/wire layer is COMPILE-VERIFIED ONLY — the
 * emulator has no BLE/Wi-Fi-Direct radio. Two physical devices are the real
 * check.
 */
class RadioController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    /** Anonymous on-air label (reuses Nearby's display name). */
    private val displayName: () -> String,
) {
    // ── published surface ─────────────────────────────────────────────
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _discovered = MutableStateFlow<List<RadioPeer>>(emptyList())
    val discovered: StateFlow<List<RadioPeer>> = _discovered.asStateFlow()

    private val _activeOneToOne = MutableStateFlow<RadioPeer?>(null)
    val activeOneToOne: StateFlow<RadioPeer?> = _activeOneToOne.asStateFlow()

    private val _activeRoom = MutableStateFlow<RadioRoomMetadata?>(null)
    val activeRoom: StateFlow<RadioRoomMetadata?> = _activeRoom.asStateFlow()

    private val _messages = MutableStateFlow<List<RadioMessage>>(emptyList())
    val messages: StateFlow<List<RadioMessage>> = _messages.asStateFlow()

    private val _roster = MutableStateFlow<List<String>>(emptyList())
    val roster: StateFlow<List<String>> = _roster.asStateFlow()

    private val _isTalking = MutableStateFlow(false)
    val isTalking: StateFlow<Boolean> = _isTalking.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val inSession: Boolean get() = _activeOneToOne.value != null || _activeRoom.value != null

    // ── internals ─────────────────────────────────────────────────────
    private val voice = RadioVoiceEngine(appContext)
    private var ble: RadioBleDiscovery? = null
    private var wifi: RadioWifiDirect? = null

    private var endpointId: ByteArray = RadioBleDiscovery.newEndpointId()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private var pruneJob: Job? = null

    @Volatile private var myEphemeral: RadioCrypto.EphemeralKeys? = null
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile private var isRoomHost = false
    /** Room key staged on join; promoted to [_activeRoom] on first decrypt. */
    @Volatile private var pendingRoom: RadioRoomMetadata? = null

    // ── discovery lifecycle ───────────────────────────────────────────
    fun startDiscovery() {
        if (_isOnline.value) return
        endpointId = RadioBleDiscovery.newEndpointId()
        clearSessionState()
        _discovered.value = emptyList()
        lastSeen.clear()

        ble = RadioBleDiscovery(appContext, ::onBlePeer) { setError(it) }.also {
            it.start(localAdvertisement(RadioPeer.Kind.OneToOne, null))
        }
        wifi = RadioWifiDirect(
            appContext, scope,
            myEndpointId = RadioBleDiscovery.bytesToHex(endpointId),
            onFrameReceived = ::onFrame,
            onConnected = ::onTransportConnected,
            onDisconnected = ::onTransportDisconnected,
            onError = { setError(it) },
        ).also { it.start() }

        _isOnline.value = true
        startPruning()
    }

    fun stop() {
        pruneJob?.cancel(); pruneJob = null
        voice.stop()
        wifi?.teardown(); wifi = null
        ble?.stop(); ble = null
        clearSessionState()
        _discovered.value = emptyList()
        lastSeen.clear()
        _isOnline.value = false
    }

    /** Burn / rebind / lock / wipe hook. */
    fun teardown() = stop()

    fun clearError() { _lastError.value = null }

    // ── 1:1 ───────────────────────────────────────────────────────────
    fun inviteOneToOne(peer: RadioPeer) {
        if (peer.kind != RadioPeer.Kind.OneToOne) return
        myEphemeral = RadioCrypto.makeEphemeralKeys()
        sessionKey = null
        isRoomHost = false
        pendingRoom = null
        _activeOneToOne.value = peer.copy(state = RadioPeer.ConnectionState.Connecting)
        _connecting.value = true
        markPeer(peer.endpointId, RadioPeer.ConnectionState.Connecting)
        wifi?.connectToPeer(peer.endpointId)
    }

    // ── rooms ─────────────────────────────────────────────────────────
    fun createRoom(name: String, password: String?) {
        val roomUuid = UUID.randomUUID().toString().lowercase()
        val cleanPwd = password?.trim()?.takeIf { it.isNotEmpty() }
        val roomId = RadioBleDiscovery.bytesToHex(RadioBleDiscovery.shortRoomIdBytes(roomUuid))
        val meta = RadioRoomMetadata(roomId = roomId, name = name, needsPassword = cleanPwd != null)
        // The BLE-advertised room id IS the salt both sides hash, so derive the
        // key from the same short id the joiner will see.
        sessionKey = RadioCrypto.roomKey(roomId, cleanPwd)
        isRoomHost = true
        pendingRoom = null
        _activeRoom.value = meta
        _connecting.value = true
        ble?.updateAdvertisement(localAdvertisement(RadioPeer.Kind.Room, meta))
        wifi?.hostGroup()
    }

    fun joinRoom(peer: RadioPeer, password: String?) {
        val meta = peer.room ?: return
        val cleanPwd = password?.trim()?.takeIf { it.isNotEmpty() }
        sessionKey = RadioCrypto.roomKey(meta.roomId, cleanPwd)
        isRoomHost = false
        pendingRoom = meta // promoted to active once a host frame decrypts
        _connecting.value = true
        markPeer(peer.endpointId, RadioPeer.ConnectionState.Connecting)
        wifi?.connectToPeer(peer.endpointId)
    }

    fun leaveActiveSession() {
        if (_isTalking.value) { voice.stopCapture(); _isTalking.value = false }
        voice.stop()
        wifi?.teardown()
        // Recreate the data-plane transport for the next session.
        wifi = RadioWifiDirect(
            appContext, scope,
            myEndpointId = RadioBleDiscovery.bytesToHex(endpointId),
            onFrameReceived = ::onFrame,
            onConnected = ::onTransportConnected,
            onDisconnected = ::onTransportDisconnected,
            onError = { setError(it) },
        ).also { it.start() }
        clearSessionState()
        ble?.updateAdvertisement(localAdvertisement(RadioPeer.Kind.OneToOne, null))
    }

    // ── send / react ──────────────────────────────────────────────────
    fun send(text: String, replyTo: RadioMessage? = null) {
        val key = sessionKey ?: return
        if (text.isBlank()) return
        val msg = RadioMessage(
            id = UUID.randomUUID().toString().lowercase(),
            senderDisplayName = displayName(),
            isFromMe = true,
            text = text,
            timestampMs = System.currentTimeMillis(),
            replyToId = replyTo?.id,
            replyToSender = replyTo?.senderDisplayName,
            replyToBody = replyTo?.text?.take(160),
        )
        _messages.value = _messages.value + msg
        sendSealed(RadioPayload.Message(msg), key)
    }

    fun toggleReaction(asset: String, message: RadioMessage) {
        val key = sessionKey ?: return
        val me = displayName()
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == message.id }
        if (idx < 0) return
        val live = list[idx]
        val reactions = live.reactions.toMutableMap()
        if (reactions[me] == asset) reactions.remove(me) else reactions[me] = asset
        list[idx] = live.copy(reactions = reactions)
        _messages.value = list
        sendSealed(RadioPayload.Reaction(message.id, me, reactions[me]), key)
    }

    // ── push-to-talk ──────────────────────────────────────────────────
    fun startTalking() {
        val key = sessionKey ?: return
        if (_isTalking.value) return
        if (!voice.hasMicPermission()) { setError("mic_denied"); return }
        val started = voice.startCapture { seq, pcm ->
            sessionKey?.let { k -> sendSealed(RadioPayload.VoiceFrame(displayName(), seq, pcm), k) }
        }
        if (!started) { setError("voice_start_failed"); return }
        _isTalking.value = true
        sendSealed(RadioPayload.VoiceTalkStart(displayName()), key)
    }

    fun stopTalking() {
        if (!_isTalking.value) return
        voice.stopCapture()
        _isTalking.value = false
        sessionKey?.let { sendSealed(RadioPayload.VoiceTalkStop(displayName()), it) }
    }

    // ── transport callbacks ───────────────────────────────────────────
    private fun onTransportConnected() {
        _connecting.value = false
        when {
            // Room host / room joiner: introduce ourselves with a sealed roster.
            _activeRoom.value != null || pendingRoom != null ->
                sessionKey?.let { sendSealed(RadioPayload.Roster(displayName()), it) }
            // 1:1 initiator: kick off the ECDH by sending our public key.
            myEphemeral != null && sessionKey == null ->
                myEphemeral?.let { wifi?.broadcastFrame(RadioFrame.Handshake(it.pub)) }
            // else: 1:1 responder — wait for the inbound handshake.
        }
    }

    private fun onTransportDisconnected() {
        if (!inSession && pendingRoom == null) return
        // Peer/host gone — drop back to discovery.
        scope.launch {
            _roster.value = emptyList()
            _activeSpeakers.value = emptySet()
            if (_isTalking.value) { voice.stopCapture(); _isTalking.value = false }
            voice.stop()
            clearSessionState()
            ble?.updateAdvertisement(localAdvertisement(RadioPeer.Kind.OneToOne, null))
        }
    }

    private fun onFrame(frame: RadioFrame) {
        when (frame) {
            is RadioFrame.Handshake -> handleHandshake(frame.pub)
            is RadioFrame.Sealed -> handleSealed(frame.combined)
        }
    }

    private fun handleHandshake(theirPub: ByteArray) {
        // Rooms never handshake (key comes from id/password).
        if (_activeRoom.value != null || pendingRoom != null) return
        if (sessionKey != null) return
        val mine = myEphemeral ?: RadioCrypto.makeEphemeralKeys().also {
            // 1:1 responder: we were connected to without initiating.
            myEphemeral = it
            wifi?.broadcastFrame(RadioFrame.Handshake(it.pub))
        }
        sessionKey = RadioCrypto.deriveSessionKey(mine.priv, theirPub)
        if (_activeOneToOne.value == null) {
            _activeOneToOne.value = RadioPeer(
                endpointId = "",
                displayName = "",
                kind = RadioPeer.Kind.OneToOne,
                state = RadioPeer.ConnectionState.Connected,
            )
        }
        _connecting.value = false
        sessionKey?.let { sendSealed(RadioPayload.Roster(displayName()), it) }
    }

    private fun handleSealed(combined: ByteArray) {
        val key = sessionKey ?: return
        val opened = runCatching { RadioCrypto.open(combined, key) }.getOrNull()
        if (opened == null) {
            // A join awaiting password confirmation: a decrypt failure is the
            // wrong password — eject. An established 1:1/room just drops it.
            if (pendingRoom != null) {
                pendingRoom = null
                setError("wrong_password")
                leaveActiveSession()
            }
            return
        }
        // First successful decrypt while joining = correct password → admit.
        pendingRoom?.let { meta ->
            _activeRoom.value = meta
            pendingRoom = null
            _connecting.value = false
        }
        val payload = RadioWire.decodePayload(opened) ?: return
        handlePayload(payload)
    }

    private fun handlePayload(payload: RadioPayload) {
        when (payload) {
            is RadioPayload.Message ->
                _messages.value = _messages.value + payload.message.copy(isFromMe = false)
            is RadioPayload.Reaction -> {
                val list = _messages.value.toMutableList()
                val idx = list.indexOfFirst { it.id == payload.messageId }
                if (idx >= 0) {
                    val reactions = list[idx].reactions.toMutableMap()
                    if (payload.asset != null) reactions[payload.reactor] = payload.asset
                    else reactions.remove(payload.reactor)
                    list[idx] = list[idx].copy(reactions = reactions)
                    _messages.value = list
                }
            }
            is RadioPayload.Roster ->
                if (payload.displayName !in _roster.value)
                    _roster.value = _roster.value + payload.displayName
            is RadioPayload.VoiceTalkStart -> {
                if (payload.speaker == displayName()) return
                _activeSpeakers.value = _activeSpeakers.value + payload.speaker
            }
            is RadioPayload.VoiceTalkStop -> {
                if (payload.speaker == displayName()) return
                _activeSpeakers.value = _activeSpeakers.value - payload.speaker
                voice.dropSpeaker(payload.speaker)
            }
            is RadioPayload.VoiceFrame -> {
                if (payload.speaker == displayName()) return
                if (payload.speaker !in _activeSpeakers.value)
                    _activeSpeakers.value = _activeSpeakers.value + payload.speaker
                voice.feedFrame(payload.speaker, payload.data)
            }
        }
    }

    // ── BLE discovery ─────────────────────────────────────────────────
    /** Same person / same room seen under a NEW endpoint id.
     *
     *  `endpointId` is re-minted every time a device goes on air, so someone
     *  who steps out of the air and back in arrives as a stranger and their
     *  previous row sits there until it goes stale. The tester's screenshot
     *  (0.95) had the same peer listed six times over. Rooms are identified by
     *  their room id, which IS stable; people by their on-air label, whose
     *  four-digit suffix makes a collision unlikely and harmless (two rows
     *  collapse into one, which is what the user wanted anyway). */
    private fun isSameParty(a: RadioPeer, b: RadioPeer): Boolean {
        if (a.kind != b.kind) return false
        return if (a.kind == RadioPeer.Kind.Room) {
            val ra = a.room?.roomId; ra != null && ra == b.room?.roomId
        } else {
            a.displayName.isNotBlank() && a.displayName == b.displayName
        }
    }

    private fun onBlePeer(peer: RadioPeer) {
        lastSeen[peer.endpointId] = System.currentTimeMillis()
        val list = _discovered.value.toMutableList()
        var idx = list.indexOfFirst { it.endpointId == peer.endpointId }
        if (idx < 0) {
            // No id match: fold onto the same party if they are already listed
            // under a spent endpoint id, and forget that id so pruning does not
            // keep resurrecting it.
            idx = list.indexOfFirst { isSameParty(it, peer) }
            if (idx >= 0) lastSeen.remove(list[idx].endpointId)
        }
        if (idx >= 0) {
            // Preserve an in-flight connection state across re-discovery.
            val existing = list[idx]
            list[idx] = peer.copy(state = existing.state.takeIf {
                it != RadioPeer.ConnectionState.Discovered
            } ?: RadioPeer.ConnectionState.Discovered)
        } else {
            list.add(peer)
        }
        _discovered.value = list
    }

    private fun startPruning() {
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                delay(REFRESH_MS)
                val now = System.currentTimeMillis()
                val active = _activeOneToOne.value?.endpointId
                _discovered.value = _discovered.value.filter { peer ->
                    // The peer we are actually in session with stays regardless
                    // of whether they are still advertising.
                    if (peer.endpointId == active) return@filter true
                    // Anything past Discovered gets a longer grace: a Wi-Fi
                    // Direct connect can outlast one stale window, and dropping
                    // the row mid-handshake would cancel it from under the user.
                    // It is a grace, not immunity — the old code returned true
                    // here unconditionally, so a peer who reached Connecting and
                    // walked away was listed until the screen was left, which is
                    // half of why the same person appeared six times over.
                    val grace = if (peer.state == RadioPeer.ConnectionState.Discovered) {
                        STALE_MS
                    } else STALE_MS * 3
                    (lastSeen[peer.endpointId] ?: 0L) >= now - grace
                }
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────
    private fun sendSealed(payload: RadioPayload, key: ByteArray) {
        val sealed = RadioCrypto.seal(RadioWire.encodePayload(payload), key)
        wifi?.broadcastFrame(RadioFrame.Sealed(sealed))
    }

    private fun localAdvertisement(kind: RadioPeer.Kind, room: RadioRoomMetadata?) =
        RadioBleDiscovery.LocalAdvertisement(
            endpointId = endpointId,
            displayName = displayName(),
            kind = kind,
            room = room,
        )

    private fun markPeer(endpointId: String, state: RadioPeer.ConnectionState) {
        val list = _discovered.value.toMutableList()
        val idx = list.indexOfFirst { it.endpointId == endpointId }
        if (idx >= 0) { list[idx] = list[idx].copy(state = state); _discovered.value = list }
    }

    private fun clearSessionState() {
        myEphemeral = null
        sessionKey = null
        isRoomHost = false
        pendingRoom = null
        _activeOneToOne.value = null
        _activeRoom.value = null
        _messages.value = emptyList()
        _roster.value = emptyList()
        _activeSpeakers.value = emptySet()
        _isTalking.value = false
        _connecting.value = false
    }

    private fun setError(key: String) { _lastError.value = key }

    companion object {
        private const val REFRESH_MS = 12_000L
        private const val STALE_MS = 25_000L
    }
}
