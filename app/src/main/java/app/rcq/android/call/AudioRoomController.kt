package app.rcq.android.call

import android.content.Context
import app.rcq.android.net.RcqApi
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection

/**
 * Audio-room subscription list + live mesh-voice session — Android port of the
 * iOS `AudioRoomService` (audio-only for v1; video-in-rooms + owner moderation
 * deferred). Mesh WebRTC lives in [RoomMeshClient]; signalling rides the WS
 * `room_*` relay (routed from Session.handleEvent). Single-busy: can't enter a
 * room while in a 1:1 call (also enforced server-side).
 */
class AudioRoomController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val send: (JsonObject) -> Boolean,
    private val turn: suspend () -> RcqApi.TurnCreds,
    private val api: () -> RcqApi,
    private val isInCall: () -> Boolean,
    /// Who we are. Needed to tell an owner-mute aimed at us from one aimed at
    /// somebody else, which is the difference between a badge and a real mute.
    private val selfUin: () -> Int,
) {
    data class Room(
        val id: Int,
        val name: String,
        val ownerUin: Int,
        val joinKey: String,
        val activeCount: Int,
        val capacity: Int = 8,
    )

    data class Member(
        val uin: Int,
        val nickname: String,
        val speaking: Boolean = false,
        /// Owner silenced this person. The island has always sent it; the
        /// roster simply threw it away, so the badge never appeared.
        val mutedByOwner: Boolean = false,
        /// Avatar of a room-mate. The island gates it by room membership, the
        /// same relationship a group roster already treats as enough.
        val avatarMediaId: String? = null,
        val avatarMediaKey: String? = null,
    )

    private fun memberOf(m: com.google.gson.JsonObject, uin: Int): Member = Member(
        uin = uin,
        nickname = m.get("nickname")?.takeIf { !it.isJsonNull }?.asString ?: "#$uin",
        mutedByOwner = m.get("muted_by_owner")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
        avatarMediaId = m.get("avatar_media_id")?.takeIf { !it.isJsonNull }?.asString,
        avatarMediaKey = m.get("avatar_media_key")?.takeIf { !it.isJsonNull }?.asString,
    )

    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()
    private val _activeRoomId = MutableStateFlow<Int?>(null)
    val activeRoomId: StateFlow<Int?> = _activeRoomId.asStateFlow()
    private val _activeRoomName = MutableStateFlow<String?>(null)
    val activeRoomName: StateFlow<String?> = _activeRoomName.asStateFlow()
    private val _roster = MutableStateFlow<Map<Int, Member>>(emptyMap())
    val roster: StateFlow<Map<Int, Member>> = _roster.asStateFlow()
    private val _localMuted = MutableStateFlow(false)
    val localMuted: StateFlow<Boolean> = _localMuted.asStateFlow()
    private val _joining = MutableStateFlow(false)
    val joining: StateFlow<Boolean> = _joining.asStateFlow()
    private val _joinError = MutableStateFlow<String?>(null)
    val joinError: StateFlow<String?> = _joinError.asStateFlow()
    /// Owner has the room on "only I speak". Sent with the roster and changed
    /// by `audio_room_owner_only_changed`.
    private val _ownerOnlySpeaking = MutableStateFlow(false)
    val ownerOnlySpeaking: StateFlow<Boolean> = _ownerOnlySpeaking.asStateFlow()

    /** In-flight entry (TURN fetch + mesh start). Cancelled on teardown so a
     *  late arrival cannot revive a room the user already left. */
    private var enterJob: Job? = null

    private val mesh = RoomMeshClient(
        appContext, scope,
        sendOffer = { to, sdp -> sendRoom("room_offer", to, "sdp", sdp) },
        sendAnswer = { to, sdp -> sendRoom("room_answer", to, "sdp", sdp) },
        sendIce = { to, json -> sendRoom("room_ice", to, "candidate", json) },
    )

    // ── REST ────────────────────────────────────────────────────────────
    suspend fun refresh() {
        runCatching { api().audioRooms() }.onSuccess { list ->
            _rooms.value = list.map { Room(it.id, it.name, it.owner_uin, it.join_key, it.active_count, it.capacity) }
        }
    }

    suspend fun create(name: String): Boolean = runCatching {
        val r = api().createAudioRoom(name)
        _rooms.value = listOf(Room(r.id, r.name, r.owner_uin, r.join_key, r.active_count, r.capacity)) + _rooms.value
        true
    }.getOrDefault(false)

    suspend fun joinByKey(key: String): Boolean = runCatching {
        val r = api().joinAudioRoom(key.trim())
        if (_rooms.value.none { it.id == r.id }) {
            _rooms.value = listOf(Room(r.id, r.name, r.owner_uin, r.join_key, r.active_count, r.capacity)) + _rooms.value
        }
        true
    }.getOrDefault(false)

    suspend fun leaveList(roomId: Int) {
        if (_activeRoomId.value == roomId) exit()
        runCatching { api().leaveAudioRoomList(roomId) }
        _rooms.value = _rooms.value.filterNot { it.id == roomId }
    }

    /** Owner-only rename. The list is patched locally rather than refetched:
     *  the server broadcasts `audio_room_renamed` to everyone else anyway, and
     *  the person who typed the name should not wait a round trip to see it. */
    suspend fun rename(roomId: Int, name: String): Boolean = runCatching {
        val r = api().renameAudioRoom(roomId, name.trim())
        _rooms.value = _rooms.value.map { if (it.id == r.id) it.copy(name = r.name) else it }
        if (_activeRoomId.value == r.id) _activeRoomName.value = r.name
        true
    }.getOrDefault(false)

    suspend fun delete(roomId: Int) {
        runCatching { api().deleteAudioRoom(roomId) }
        _rooms.value = _rooms.value.filterNot { it.id == roomId }
        if (_activeRoomId.value == roomId) tearDownLocal()
    }

    // ── voice session ────────────────────────────────────────────────────
    fun enter(room: Room) {
        if (_activeRoomId.value == room.id) return
        if (isInCall()) { _joinError.value = "in_call"; return }
        if (_activeRoomId.value != null) exit()
        _activeRoomId.value = room.id
        _activeRoomName.value = room.name
        _roster.value = emptyMap()
        _localMuted.value = false
        _joining.value = true
        // Fetching TURN credentials is a network round trip; leaving is one
        // tap. Entering and leaving a few times in a row left one of these in
        // flight, and when it landed it started the mesh and announced
        // `room_enter` for a room already left — after which `_activeRoomId`
        // was null, every room signal was dropped by the guards below, and the
        // next entry never connected (#418, "after several enters and exits it
        // stops connecting"). The server, meanwhile, thought we were inside.
        //
        // Cancel is the first line of defence and the re-check is the load
        // bearing one: `runCatching` swallows CancellationException like any
        // other throwable, so a cancelled fetch would otherwise carry straight
        // on into `mesh.start`.
        enterJob?.cancel()
        enterJob = scope.launch {
            val creds = runCatching { turn() }.getOrNull()
            if (_activeRoomId.value != room.id) return@launch
            mesh.start(iceServers(creds))
            send(JsonObject().apply { addProperty("type", "room_enter"); addProperty("room_id", room.id) })
        }
    }

    /** The socket came back after a drop. The island forgets a member whose
     *  socket has been gone for a minute and tells the OTHERS; we were never
     *  told, so the strip kept saying "you are in the room" over a room we
     *  were no longer in, with nobody able to hear us. Announce ourselves
     *  again: the island's `room_enter` is idempotent (still inside → the
     *  roster; evicted → re-added and the others dial us again; room gone or
     *  full → `room_enter_rejected`, which tears the room down here). Found in
     *  review before 0.142. */
    fun onSocketUp(offlineGapMs: Long) {
        // A leave the dead socket refused goes out first, or the island keeps
        // a member nobody can hear until its own timer notices.
        pendingLeave?.let { left ->
            if (send(JsonObject().apply { addProperty("type", "room_leave"); addProperty("room_id", left) })) pendingLeave = null
        }
        val id = _activeRoomId.value ?: return
        if (enterJob?.isActive == true) return   // the first entry is still on its way
        if (offlineGapMs >= LONG_GAP_MS) {
            // Long enough that the island may have evicted us (its timer is
            // 60s) and the others dropped their legs. Whether it did or not is
            // not knowable from here, so make it deterministic: leave (the
            // others drop whatever they still hold), drop our own legs, enter
            // (we are a fresh entrant, the others dial us). Costs an audio
            // restart after an outage that long, which is no loss.
            mesh.dropAllPeers()
            send(JsonObject().apply { addProperty("type", "room_leave"); addProperty("room_id", id) })
            send(JsonObject().apply { addProperty("type", "room_enter"); addProperty("room_id", id) })
        } else {
            // A blip: still a member, the legs are alive, the announce is a
            // no-op on the island and comes back as the roster. What we may
            // have missed is someone who entered meanwhile: the island told
            // them to wait for OUR offer (the existing member dials), and the
            // `room_member_entered` meant for us died with the socket. The
            // roster handler dials whoever is in it and not in the mesh.
            resyncPending = true
            send(JsonObject().apply { addProperty("type", "room_enter"); addProperty("room_id", id) })
        }
    }

    /** Set by [onSocketUp] for a short gap; the next roster is reconciled
     *  against the mesh instead of only replacing the member list. */
    private var resyncPending = false

    /** A `room_leave` the socket refused (it was down); replayed on the next
     *  socket up. Only the refused one: replaying a delivered leave later could
     *  throw out a re-entry made from another device of the same account. */
    private var pendingLeave: Int? = null

    private companion object {
        /** The island drops a member whose socket has been gone 60s
         *  (`_OFFLINE_DEBOUNCE_SECONDS`). Well under that: near the line the
         *  two sides can disagree on the gap, and a fresh entry is the safe
         *  answer either way. */
        const val LONG_GAP_MS = 40_000L
    }

    fun exit() {
        val id = _activeRoomId.value ?: return
        if (!send(JsonObject().apply { addProperty("type", "room_leave"); addProperty("room_id", id) })) pendingLeave = id
        // Take ourselves off the cached headcount. The server knows we left, but
        // it only tells the people still inside (`room_member_left`), and we are
        // no longer one of them — so the room list kept showing the number from
        // the moment we walked out, including us. Someone who was alone in a
        // room saw "1 в комнате" after leaving it, and it corrected itself only
        // on the next fetch, which is why re-entering the screen fixed it (#418).
        _rooms.value = _rooms.value.map {
            if (it.id == id) it.copy(activeCount = (it.activeCount - 1).coerceAtLeast(0)) else it
        }
        tearDownLocal()
    }

    fun toggleMute() {
        val next = !_localMuted.value
        mesh.setMicMuted(next)
        _localMuted.value = next
    }

    /** Loudspeaker/earpiece for the live room. Owned by the mesh client (it
     *  holds the AudioManager); surfaced here because the screen talks to the
     *  controller and nothing else. */
    val speakerOn: StateFlow<Boolean> get() = mesh.speakerOn

    fun toggleSpeaker() = mesh.setSpeaker(!mesh.speakerOn.value)

    fun acknowledgeJoinError() { _joinError.value = null }

    fun isInside(roomId: Int) = _activeRoomId.value == roomId

    // ── WS routing (from Session.handleEvent) ─────────────────────────────
    fun onSignal(type: String, obj: JsonObject) {
        val roomId = obj.get("room_id")?.takeIf { !it.isJsonNull }?.asInt ?: return
        when (type) {
            "room_enter_rejected" -> {
                if (_activeRoomId.value != roomId) return
                _joinError.value = obj.get("reason")?.takeIf { !it.isJsonNull }?.asString ?: "generic"
                tearDownLocal()
            }
            "room_roster" -> {
                if (_activeRoomId.value != roomId) return
                _joining.value = false
                val fresh = HashMap<Int, Member>()
                obj.getAsJsonArray("members")?.forEach { el ->
                    val m = el.asJsonObject
                    val uin = m.get("uin")?.asInt ?: return@forEach
                    fresh[uin] = memberOf(m, uin)
                }
                _roster.value = fresh
                _ownerOnlySpeaking.value =
                    obj.get("owner_only_speaking")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                updateActiveCount(roomId, fresh.size)
                if (resyncPending) {
                    resyncPending = false
                    val me = selfUin()
                    val inMesh = mesh.peerUins()
                    fresh.keys.filter { it != me && it !in inMesh }.forEach { mesh.dialNewPeer(it) }
                    inMesh.filter { it !in fresh.keys }.forEach { mesh.dropPeer(it) }
                }
            }
            "room_member_entered" -> {
                if (_activeRoomId.value != roomId) return
                val m = obj.getAsJsonObject("member") ?: return
                val uin = m.get("uin")?.asInt ?: return
                _roster.value = _roster.value + (uin to memberOf(m, uin))
                updateActiveCount(roomId, _roster.value.size)
                mesh.dialNewPeer(uin) // existing member is the offerer
            }
            "room_member_left" -> {
                if (_activeRoomId.value != roomId) return
                val uin = obj.get("uin")?.asInt ?: return
                _roster.value = _roster.value - uin
                updateActiveCount(roomId, _roster.value.size)
                mesh.dropPeer(uin)
            }
            "room_offer" -> if (_activeRoomId.value == roomId) obj.from()?.let { mesh.handleOffer(it, obj.get("sdp")?.asString ?: "") }
            "room_answer" -> if (_activeRoomId.value == roomId) obj.from()?.let { mesh.handleAnswer(it, obj.get("sdp")?.asString ?: "") }
            "room_ice" -> if (_activeRoomId.value == roomId) obj.from()?.let { mesh.handleIce(it, obj.get("candidate")?.asString ?: "") }
            "room_speaking" -> {
                if (_activeRoomId.value != roomId) return
                val uin = obj.get("uin")?.asInt ?: return
                val speaking = obj.get("speaking")?.asBoolean ?: false
                _roster.value[uin]?.let { _roster.value = _roster.value + (uin to it.copy(speaking = speaking)) }
            }
            // ⚠ The island calls this `audio_room_deleted`; Android listened
            // for `room_deleted`, which nothing has ever sent, so an owner
            // deleting a room left everyone else inside a room that no longer
            // existed until they closed the screen by hand.
            "audio_room_deleted", "audio_room_kicked", "audio_room_membership_revoked" -> {
                _rooms.value = _rooms.value.filterNot { it.id == roomId }
                if (_activeRoomId.value == roomId) tearDownLocal()
            }
            // The owner minted a new join key. Everyone keeps their membership,
            // but the cached key in the list is now the one that stopped
            // working, and sharing it would send people to a 404.
            "audio_room_key_rotated" -> {
                val fresh = obj.get("new_key")?.takeIf { !it.isJsonNull }?.asString ?: return
                _rooms.value = _rooms.value.map { if (it.id == roomId) it.copy(joinKey = fresh) else it }
            }
            // Owner silenced someone. The badge is drawn from the roster, and
            // the muted client honours it locally.
            "audio_room_member_muted" -> {
                val target = obj.get("uin")?.asInt ?: return
                val muted = obj.get("muted_by_owner")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                if (_activeRoomId.value != roomId) return
                _roster.value[target]?.let {
                    _roster.value = _roster.value + (target to it.copy(mutedByOwner = muted))
                }
                // Being silenced by the owner mutes us for real, not just as a
                // badge on someone else's screen.
                if (muted && target == selfUin() && !_localMuted.value) toggleMute()
            }
            "audio_room_owner_only_changed" -> {
                if (_activeRoomId.value != roomId) return
                _ownerOnlySpeaking.value = obj.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            }
            // The server has broadcast this to every subscriber since audio
            // rooms shipped and nothing on Android listened, so a rename only
            // ever showed up for the person who typed it — everyone else kept
            // the old name until a manual refresh.
            "audio_room_renamed" -> {
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return
                _rooms.value = _rooms.value.map { if (it.id == roomId) it.copy(name = name) else it }
                if (_activeRoomId.value == roomId) _activeRoomName.value = name
            }
        }
    }

    /** Burn/rebind/lock/wipe hook: drop the live session without signalling. */
    fun teardown() {
        mesh.stop()
        _activeRoomId.value = null
        _activeRoomName.value = null
        _roster.value = emptyMap()
        _localMuted.value = false
        _joining.value = false
    }

    // ── helpers ───────────────────────────────────────────────────────────
    private fun tearDownLocal() {
        enterJob?.cancel()
        enterJob = null
        mesh.stop()
        _activeRoomId.value = null
        _activeRoomName.value = null
        _roster.value = emptyMap()
        _localMuted.value = false
        _joining.value = false
    }

    private fun updateActiveCount(roomId: Int, count: Int) {
        _rooms.value = _rooms.value.map { if (it.id == roomId) it.copy(activeCount = count) else it }
    }

    private fun JsonObject.from(): Int? = get("from_uin")?.takeIf { !it.isJsonNull }?.asInt

    private fun sendRoom(type: String, toUin: Int, key: String, value: String) {
        val id = _activeRoomId.value ?: return
        send(JsonObject().apply {
            addProperty("type", type)
            addProperty("room_id", id)
            addProperty("to_uin", toUin)
            addProperty(key, value)
        })
    }

    private fun iceServers(c: RcqApi.TurnCreds?): List<PeerConnection.IceServer> {
        if (c == null || c.urls.isEmpty()) return emptyList()
        return listOf(
            PeerConnection.IceServer.builder(c.urls)
                .setUsername(c.username).setPassword(c.credential).createIceServer(),
        )
    }
}
