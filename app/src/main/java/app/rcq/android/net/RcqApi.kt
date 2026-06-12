package app.rcq.android.net

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal REST client for the RCQ backend, talking the same wire protocol
 * as the iOS client (rcq-spec) against prod by default. Registration,
 * peer lookup, and 1:1 sealed send are wired; contacts roster + media come
 * later.
 */
class RcqApi(private val baseUrl: String = DEFAULT_BASE_URL) {

    private val client = OkHttpClient.Builder()
        // Detect a dead/stale connection fast (cellular CGNAT + radio sleep
        // silently kill idle keep-alives). callTimeout stays generous so
        // large media uploads on slow links still complete.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        // Don't let pooled connections sit idle long enough to die unnoticed;
        // a fresh one is cheap next to a 10s+ dead-socket hang.
        .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
        // Route through the embedded sing-box SOCKS proxy when the
        // circumvention transport is engaged (null = direct, the default).
        // Captured at build time; Session rebuilds this RcqApi after engaging
        // the transport so the new instance picks the proxy up.
        .proxy(SingBoxTransport.proxy())
        .build()
    private val gson = Gson()

    /** Drop all pooled connections so the next request opens a fresh
     *  TCP+TLS one. Called between send retries: on mobile data a pooled
     *  keep-alive often dies silently, and reusing it is exactly why a
     *  message sometimes "needs to be sent 3 times". Forcing a fresh
     *  connection on retry automates that manual resend. */
    suspend fun evictConnections() = withContext(Dispatchers.IO) {
        client.connectionPool.evictAll()
    }

    @Volatile
    private var token: String? = null
    fun setToken(t: String?) { token = t }

    // ── register (rcq-spec 2.2) ──────────────────────────────────────

    data class RegisterRequest(
        val nickname: String,
        val identity_key: String,
        val signing_key: String,
        val inviter_uin: Int? = null,
        // Server-join invite token, required only when the target server runs
        // REGISTRATION_POLICY=invite. Gson omits it when null (open servers).
        val invite: String? = null,
        // Best-effort preferred UIN (multihoming "same number everywhere"):
        // honoured only if free on the target island, else a fresh uin is
        // minted. Gson omits when null (the normal primary-register path).
        val desired_uin: Int? = null,
    )

    data class RegisterResponse(val uin: Int, val token: String)

    // Account recovery (seed-phrase): prove possession of the signing key to
    // rebind a fresh device to the same UIN. Reuses RegisterResponse {uin,token}.
    data class RecoverChallengeRequest(val signing_key: String)
    data class RecoverChallengeResponse(val challenge: String)
    data class RecoverRequest(val signing_key: String, val challenge: String, val signature: String)
    // In-place identity key rotation for the current (authed) account: replaces
    // the long-term X25519 identity + Ed25519 signing keys; the UIN is unchanged.
    data class ReissueRequest(val identity_key: String, val signing_key: String)

    // ── libsignal prekey bundle (v=2 forward secrecy) ─────────────────
    // JSON key is "public" (a Kotlin keyword) → @SerializedName.
    data class SignedPreKeyDto(val id: Int, @SerializedName("public") val publicKey: String, val signature: String)
    data class KyberPreKeyDto(val id: Int, @SerializedName("public") val publicKey: String, val signature: String)
    data class OneTimePreKeyDto(val id: Int, @SerializedName("public") val publicKey: String)
    data class KeysBundleBody(
        val signal_identity_key: String,
        val registration_id: Int,
        val signed_prekey: SignedPreKeyDto,
        val kyber_prekey: KyberPreKeyDto,
        val one_time_prekeys: List<OneTimePreKeyDto>,
    )
    data class PrekeysBody(val one_time_prekeys: List<OneTimePreKeyDto>)
    data class KeysStatus(
        val has_bundle: Boolean,
        val one_time_prekey_count: Int,
        val target_count: Int,
        val signed_prekey_age_seconds: Int?,
    )
    /** Peer's published bundle for session establishment (PQXDH). */
    data class PeerBundle(
        val uin: Int,
        val registration_id: Int,
        val signal_identity_key: String,
        val signed_prekey: SignedPreKeyDto,
        val kyber_prekey: KyberPreKeyDto,
        val one_time_prekey: OneTimePreKeyDto?,
    )

    /** Upload the full prekey bundle (POST /keys/bundle → 204). */
    suspend fun uploadKeysBundle(body: KeysBundleBody) = withContext(Dispatchers.IO) {
        postNoContent("/keys/bundle", gson.toJson(body), authed = true)
    }
    /** Replenish the one-time prekey pool (POST /keys/prekeys → 204). */
    suspend fun replenishPrekeys(body: PrekeysBody) = withContext(Dispatchers.IO) {
        postNoContent("/keys/prekeys", gson.toJson(body), authed = true)
    }
    /** Own prekey-pool status (for top-up decisions). */
    suspend fun keysStatus(): KeysStatus = withContext(Dispatchers.IO) {
        get("/keys/me/status", authed = true, KeysStatus::class.java)
    }
    /** Fetch a peer's bundle to establish a v=2 session. */
    suspend fun fetchPeerBundle(uin: Int): PeerBundle = withContext(Dispatchers.IO) {
        get("/keys/$uin/bundle", authed = true, PeerBundle::class.java)
    }

    // ── Federation Layer B (F1): self-signed home-island record ──
    data class IslandRecordPutResp(val ok: Boolean = false, val ts: Long = 0)

    /** Publish this account's signed home-island record (PUT /federation/island-record). */
    suspend fun publishIslandRecord(docJson: String): IslandRecordPutResp = withContext(Dispatchers.IO) {
        request("PUT", "/federation/island-record", docJson, authed = true, IslandRecordPutResp::class.java)
    }

    /** Host authority of this session's island, for the record's home entry. */
    fun islandHost(): String = runCatching { java.net.URI(baseUrl).host ?: DEFAULT_HOST }.getOrDefault(DEFAULT_HOST)

    suspend fun register(req: RegisterRequest): RegisterResponse = withContext(Dispatchers.IO) {
        post("/auth/register", gson.toJson(req), authed = false, RegisterResponse::class.java)
    }

    suspend fun recoverChallenge(signingKey: String): RecoverChallengeResponse = withContext(Dispatchers.IO) {
        post("/auth/recover/challenge", gson.toJson(RecoverChallengeRequest(signingKey)), authed = false, RecoverChallengeResponse::class.java)
    }

    suspend fun recover(req: RecoverRequest): RegisterResponse = withContext(Dispatchers.IO) {
        post("/auth/recover", gson.toJson(req), authed = false, RegisterResponse::class.java)
    }

    /** Rotate the long-term identity keys for the current (authed) account in
     *  place (POST /auth/reissue). UIN unchanged; returns a fresh token. */
    suspend fun reissue(req: ReissueRequest): RegisterResponse = withContext(Dispatchers.IO) {
        post("/auth/reissue", gson.toJson(req), authed = true, RegisterResponse::class.java)
    }

    // ── peer lookup (rcq-spec 3.1) ───────────────────────────────────

    data class UserInfo(
        val uin: Int,
        val nickname: String?,
        val identity_key: String?,        // base64 raw X25519 public
        val signing_key: String?,         // base64 raw Ed25519 public
        val signal_identity_key: String? = null,  // base64 libsignal IdentityKey; null = v=1 only
    )

    suspend fun userInfo(uin: Int): UserInfo = withContext(Dispatchers.IO) {
        get("/users/$uin/info", authed = true, UserInfo::class.java)
    }

    /** Server-side people search (nickname / name / city / exact UIN). Powers
     *  the Add window's "find users" (iOS parity). */
    suspend fun searchUsers(q: String): List<UserInfo> = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        get("/users/search?q=$enc&limit=20", authed = true, Array<UserInfo>::class.java).toList()
    }

    // ── news (admin-posted feed, rcq-spec) ───────────────────────────
    data class NewsAttachment(val media_id: String?, val mime: String?, val kind: String?)
    data class NewsPost(
        val id: Int,
        val body: String?,
        val attachments: List<NewsAttachment> = emptyList(),
        val author_label: String?,
        val published_at: String?,
    )
    data class NewsFeed(val items: List<NewsPost> = emptyList(), val latest_id: Int = 0)

    suspend fun news(): NewsFeed = withContext(Dispatchers.IO) {
        get("/news", authed = true, NewsFeed::class.java)
    }

    // ── stories (24h ephemeral, rcq-spec) ────────────────────────────
    // Media reuses the sealed-blob path: encrypt → /media/upload → the
    // resulting media_id + key go up with the story. Datetimes arrive as
    // ISO-8601 strings.
    data class StoryOut(
        val id: String,
        val owner_uin: Int?,            // null for an anonymous story (non-owner view)
        val owner_nickname: String?,
        val media_id: String,
        val media_kind: String,         // "photo" | "video"
        val media_key_b64: String,
        val caption: String?,
        val is_anonymous: Boolean = false,
        val duration_sec: Int? = null,
        val posted_at: String?,
        val expires_at: String?,
        val view_count: Int = 0,
        val viewed: Boolean = false,
    )
    data class StoryGroupOut(
        val owner_uin: Int?,
        val owner_nickname: String?,
        val is_anonymous: Boolean = false,
        val stories: List<StoryOut> = emptyList(),
    )
    data class StoriesFeed(val groups: List<StoryGroupOut> = emptyList())
    data class PostStoryBody(
        val media_id: String,
        val media_kind: String,
        val media_key_b64: String,
        val caption: String?,
        val is_anonymous: Boolean,
        val duration_sec: Int?,
    )
    data class PostedStory(val story: StoryOut)
    data class StoryViewer(val viewer_uin: Int, val viewer_nickname: String?, val viewed_at: String?)
    data class StoryViewers(val viewers: List<StoryViewer> = emptyList())

    suspend fun storiesFeed(): StoriesFeed = withContext(Dispatchers.IO) {
        get("/stories/feed", authed = true, StoriesFeed::class.java)
    }

    suspend fun postStory(body: PostStoryBody): PostedStory = withContext(Dispatchers.IO) {
        post("/stories", gson.toJson(body), authed = true, PostedStory::class.java)
    }

    suspend fun markStoryViewed(storyId: String) = withContext(Dispatchers.IO) {
        sendNoResult("POST", "/stories/$storyId/view", "{}", authed = true)
    }

    suspend fun storyViewers(storyId: String): StoryViewers = withContext(Dispatchers.IO) {
        get("/stories/$storyId/viewers", authed = true, StoryViewers::class.java)
    }

    suspend fun deleteStory(storyId: String) = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/stories/$storyId", null, authed = true)
    }

    // ── random chat (anonymous time-boxed 1:1 with a stranger) ───────
    // Matchmaking only — once matched, chat rides the normal /messages/sealed
    // path (we have the peer's identity_key from the match). /queue may 403
    // with detail {"code":"age_required"|"under_18"} (the body string carries
    // the code, surfaced to the user). A match arrives either as this sync
    // response (status="matched") or, if parked, via the WS `random_match`.
    data class RandomPeerInfo(
        val uin: Int,
        val nickname: String?,
        val identity_key: String?,
        val signing_key: String?,
    )
    data class RandomQueueOut(
        val status: String,             // "queued" | "matched"
        val pair_id: String? = null,
        val peer: RandomPeerInfo? = null,
        val expires_at: String? = null,
    )
    data class RandomLeaveOut(val left: Boolean = false)

    suspend fun randomQueue(): RandomQueueOut = withContext(Dispatchers.IO) {
        post("/random/queue", "{}", authed = true, RandomQueueOut::class.java)
    }

    suspend fun randomSkip(): RandomQueueOut = withContext(Dispatchers.IO) {
        post("/random/skip", "{}", authed = true, RandomQueueOut::class.java)
    }

    suspend fun randomLeave(): RandomLeaveOut = withContext(Dispatchers.IO) {
        post("/random/leave", "{}", authed = true, RandomLeaveOut::class.java)
    }

    // ── group polls (rcq-spec) ───────────────────────────────────────
    // The server is blind to the question/option text (those ride the
    // encrypted "poll" group envelope); it only holds the structural shape +
    // per-option tallies. Create returns a poll_id used to vote/close/read.
    data class CreatePollBody(val message_id: String, val num_options: Int, val single_choice: Boolean, val anonymous: Boolean)
    data class CreatePollOut(val poll_id: Int, val created_at: String? = null)
    data class VoteBody(val option_index: Int)
    data class PollTally(val option_index: Int, val count: Int = 0, val voter_uins: List<Int> = emptyList())
    data class PollOut(
        val poll_id: Int,
        val group_id: Int = 0,
        val creator_uin: Int = 0,
        val message_id: String? = null,
        val num_options: Int = 0,
        val single_choice: Boolean = true,
        val anonymous: Boolean = false,
        val closed_at: String? = null,
        val created_at: String? = null,
        val tallies: List<PollTally> = emptyList(),
        val total_votes: Int = 0,
        val my_votes: List<Int> = emptyList(),
    )

    suspend fun createPoll(groupId: Int, body: CreatePollBody): CreatePollOut = withContext(Dispatchers.IO) {
        post("/groups/$groupId/polls", gson.toJson(body), authed = true, CreatePollOut::class.java)
    }

    suspend fun votePoll(pollId: Int, optionIndex: Int): PollOut = withContext(Dispatchers.IO) {
        post("/polls/$pollId/vote", gson.toJson(VoteBody(optionIndex)), authed = true, PollOut::class.java)
    }

    suspend fun getPoll(pollId: Int): PollOut = withContext(Dispatchers.IO) {
        get("/polls/$pollId", authed = true, PollOut::class.java)
    }

    suspend fun closePoll(pollId: Int): PollOut = withContext(Dispatchers.IO) {
        post("/polls/$pollId/close", "{}", authed = true, PollOut::class.java)
    }

    // ── 1:1 send (rcq-spec 6.2.1) ────────────────────────────────────

    data class SendRequest(val to_uin: Int, val envelope_type: String, val payload: String)
    data class SendResponse(val delivered: Boolean = false, val queued: Boolean = false)

    suspend fun sendSealed(toUin: Int, payloadB64: String, envelopeType: String = "message"): SendResponse =
        withContext(Dispatchers.IO) {
            post(
                "/messages/sealed",
                gson.toJson(SendRequest(toUin, envelopeType, payloadB64)),
                authed = false, // sealed-sender is anonymous by design
                SendResponse::class.java,
            )
        }

    data class LinkDepositBody(val blob: String)

    /** Connect-to-web: drop a sealed account [blob] into the one-time relay
     *  slot [token] for a web client to collect. Authenticated (only a
     *  logged-in client links a session). */
    suspend fun depositLink(token: String, blob: String) =
        withContext(Dispatchers.IO) {
            postNoContent("/link/$token", gson.toJson(LinkDepositBody(blob)), authed = true)
        }

    data class LinkDeviceBody(val label: String)
    data class LinkDeviceResponse(val device_id: String = "", val token: String = "")

    /** Register a new linked web session and get its OWN session token (so the
     *  web can be revoked independently of the phone). Registering also flips
     *  the account to multi-device → the server serves v=1 to senders. */
    suspend fun linkDevice(label: String): LinkDeviceResponse =
        withContext(Dispatchers.IO) {
            post("/devices/link", gson.toJson(LinkDeviceBody(label)), authed = true, LinkDeviceResponse::class.java)
        }

    data class DeviceInfo(val device_id: String = "", val label: String = "", val created_at: String = "")

    /** Linked web sessions for this account (the Linked Devices screen). */
    suspend fun listDevices(): List<DeviceInfo> =
        withContext(Dispatchers.IO) {
            get("/devices", authed = true, Array<DeviceInfo>::class.java).toList()
        }

    /** Disconnect (revoke) a linked web session by its id. */
    suspend fun revokeDevice(deviceId: String) =
        withContext(Dispatchers.IO) {
            deleteNoContent("/devices/$deviceId", authed = true)
        }

    // ── offline queue drain (rcq-spec 6.3.1) ─────────────────────────

    data class QueuedEnvelope(
        val id: Int,
        val envelope_type: String?,
        val payload: String?,
        val received_at: String?,
        val group_id: Int? = null,
    )

    suspend fun drainQueue(): List<QueuedEnvelope> = withContext(Dispatchers.IO) {
        get("/messages/queue", authed = true, Array<QueuedEnvelope>::class.java).toList()
    }

    // ── contacts (rcq-spec 4) ────────────────────────────────────────

    data class ContactRow(
        val uin: Int,
        val nickname: String?,
        val status: String?,
        val status_message: String? = null,
        val blocked: Boolean = false,
        val gender: String? = null,
        val last_seen: String? = null,   // ISO-8601, null when hidden/online
        val callable: Boolean = true,    // false = peer's call_policy is "nobody"
        val identity_key: String?,
        val signing_key: String?,
    )

    suspend fun contacts(): List<ContactRow> = withContext(Dispatchers.IO) {
        get("/contacts", authed = true, Array<ContactRow>::class.java).toList()
    }

    data class PendingRow(
        val id: Int,
        val from_uin: Int,
        val nickname: String?,
        val state: String?,
    )

    suspend fun pending(): List<PendingRow> = withContext(Dispatchers.IO) {
        get("/contacts/pending", authed = true, Array<PendingRow>::class.java).toList()
    }

    data class OutgoingRow(
        val id: Int = 0,
        val to_uin: Int = 0,
        val nickname: String? = null,
        val state: String? = null,
    )

    /** Requests WE sent that are still pending or were declined. */
    suspend fun outgoing(): List<OutgoingRow> = withContext(Dispatchers.IO) {
        get("/contacts/outgoing", authed = true, Array<OutgoingRow>::class.java).toList()
    }

    /** Cancel/revoke a sent request, or dismiss a declined one (DELETE, 204). */
    suspend fun cancelOutgoing(toUin: Int) = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/contacts/outgoing/$toUin", null, authed = true)
    }

    data class ContactRequestBody(val to_uin: Int)
    data class ContactRequestResponse(val id: Int = 0, val state: String? = null, val auto: Boolean = false)

    suspend fun requestContact(toUin: Int): ContactRequestResponse = withContext(Dispatchers.IO) {
        post("/contacts/request", gson.toJson(ContactRequestBody(toUin)), authed = true, ContactRequestResponse::class.java)
    }

    data class RespondBody(val request_id: Int, val accept: Boolean)
    data class RespondResponse(val state: String? = null)

    suspend fun respondContact(requestId: Int, accept: Boolean): RespondResponse = withContext(Dispatchers.IO) {
        post("/contacts/respond", gson.toJson(RespondBody(requestId, accept)), authed = true, RespondResponse::class.java)
    }

    data class BlockResponse(val blocked: Boolean = false)

    /** Toggle block on a contact (server flips the flag). */
    suspend fun blockContact(uin: Int): BlockResponse = withContext(Dispatchers.IO) {
        post("/contacts/$uin/block", "{}", authed = true, BlockResponse::class.java)
    }

    /** ICQ-style mutual remove (DELETE /contacts/{uin}, 204). */
    suspend fun removeContact(uin: Int) = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/contacts/$uin", null, authed = true)
    }

    data class ReportBody(val target_uin: Int, val reason: String, val context: String = "")

    /** File an abuse report (POST /reports). */
    suspend fun report(targetUin: Int, reason: String, context: String = "") = withContext(Dispatchers.IO) {
        sendNoResult("POST", "/reports", gson.toJson(ReportBody(targetUin, reason, context)), authed = true)
    }

    // ── groups (rcq-spec 6.4) ────────────────────────────────────────

    data class GroupMemberOut(
        val uin: Int,
        val nickname: String?,
        val role: String?,
        val status: String?,
        val identity_key: String?,
        val signing_key: String?,
        // Granular moderator caps the owner granted (subset of delete|members|info).
        val permissions: List<String> = emptyList(),
    )

    data class GroupOut(
        val id: Int,
        val name: String?,
        val description: String? = null,
        val owner_uin: Int = 0,
        val post_policy: String? = null,
        val is_closed: Boolean = false,
        val members_hidden: Boolean = false,
        val pinned_text: String? = null,
        val avatar_media_id: String? = null,
        val avatar_media_key: String? = null,
        val created_at: String? = null,
        val members: List<GroupMemberOut> = emptyList(),
    )

    suspend fun groups(): List<GroupOut> = withContext(Dispatchers.IO) {
        get("/groups", authed = true, Array<GroupOut>::class.java).toList()
    }

    data class CreateGroupBody(val name: String, val member_uins: List<Int>)

    suspend fun createGroup(name: String, memberUins: List<Int>): GroupOut = withContext(Dispatchers.IO) {
        post("/groups", gson.toJson(CreateGroupBody(name, memberUins)), authed = true, GroupOut::class.java)
    }

    suspend fun groupInfo(id: Int): GroupOut = withContext(Dispatchers.IO) {
        get("/groups/$id", authed = true, GroupOut::class.java)
    }

    suspend fun joinGroup(id: Int): GroupOut = withContext(Dispatchers.IO) {
        post("/groups/$id/join", "{}", authed = true, GroupOut::class.java)
    }

    /** Public-ish group snapshot for the invite card (no membership needed),
     *  mirrors backend GroupPreviewOut. */
    data class GroupPreviewOut(
        val id: Int,
        val name: String? = null,
        val description: String? = null,
        val member_count: Int = 0,
        val is_closed: Boolean = false,
        val owner_uin: Int = 0,
        val owner_nickname: String? = null,
        val avatar_media_id: String? = null,
        val avatar_media_key: String? = null,
    )

    suspend fun previewGroup(id: Int): GroupPreviewOut = withContext(Dispatchers.IO) {
        get("/groups/$id/preview", authed = true, GroupPreviewOut::class.java)
    }

    /** Server-side group search (name substring / exact id), joinable groups
     *  the caller isn't already in. Powers the Add window's "find groups". */
    suspend fun searchGroups(q: String): List<GroupPreviewOut> = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        get("/groups/search?q=$enc&limit=20", authed = true, Array<GroupPreviewOut>::class.java).toList()
    }

    data class AddMemberBody(val uin: Int)

    suspend fun addGroupMember(id: Int, uin: Int): GroupOut = withContext(Dispatchers.IO) {
        post("/groups/$id/members", gson.toJson(AddMemberBody(uin)), authed = true, GroupOut::class.java)
    }

    /** Self-leave or owner-kick (DELETE /groups/{id}/members/{uin}). */
    suspend fun leaveGroup(id: Int, memberUin: Int) = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/groups/$id/members/$memberUin", null, authed = true)
    }

    /** Owner-only group delete. */
    suspend fun deleteGroup(id: Int) = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/groups/$id", null, authed = true)
    }

    data class SetPermissionsBody(val permissions: List<String>)

    /** Owner-only: grant/revoke a member's moderator caps (subset of
     *  delete|members|info). Returns the updated group. */
    suspend fun setMemberPermissions(id: Int, memberUin: Int, permissions: List<String>): GroupOut =
        withContext(Dispatchers.IO) {
            post("/groups/$id/members/$memberUin/permissions", gson.toJson(SetPermissionsBody(permissions)), authed = true, GroupOut::class.java)
        }

    /** Partial group update (owner/admin). Only non-null fields are sent
     *  (Gson omits nulls by default), so a PATCH that only swaps the
     *  avatar leaves name/policy/pin untouched. */
    data class GroupPatchBody(
        val name: String? = null,
        val description: String? = null,
        val post_policy: String? = null,
        val pinned_text: String? = null,
        val is_closed: Boolean? = null,
        val members_hidden: Boolean? = null,
        val avatar_media_id: String? = null,
        val avatar_media_key: String? = null,
    )

    suspend fun patchGroup(id: Int, body: GroupPatchBody): GroupOut = withContext(Dispatchers.IO) {
        request("PATCH", "/groups/$id", gson.toJson(body), authed = true, GroupOut::class.java)
    }

    data class GroupPayload(val to_uin: Int, val payload: String)
    data class GroupSendBody(val group_id: Int, val envelope_type: String, val payloads: List<GroupPayload>)

    /** Group send: per-recipient fan-out (anonymous, like 1:1 sealed). */
    suspend fun sendGroupSealed(groupId: Int, payloads: List<GroupPayload>, envelopeType: String = "message"): SendResponse =
        withContext(Dispatchers.IO) {
            post(
                "/messages/group-sealed",
                gson.toJson(GroupSendBody(groupId, envelopeType, payloads)),
                authed = false,
                SendResponse::class.java,
            )
        }

    // ── presence + account (rcq-spec 3.3 / 2.4) ──────────────────────

    data class StatusBody(val status: String, val status_message: String? = null)

    suspend fun setStatus(status: String) = withContext(Dispatchers.IO) {
        sendNoResult("POST", "/presence/status", gson.toJson(StatusBody(status)), authed = true)
    }

    /** DELETE /auth/account — irreversible burn (rcq-spec 2.4). */
    suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/auth/account", null, authed = true)
    }

    data class MigrateResponse(val new_uin: Int = 0, val token: String = "")

    /** POST /account/migrate — move to a freshly-allocated UIN. Server
     *  keeps profile/contacts/groups + reuses the identity keys under the
     *  new UIN; returns the new UIN + a token for it. */
    suspend fun migrateAccount(): MigrateResponse = withContext(Dispatchers.IO) {
        post("/account/migrate", "{}", authed = true, MigrateResponse::class.java)
    }

    // ── UIN shop (buy any free 3-9 digit UIN, then migrate) ──────────
    // Backend app/routers/uin_shop.py. /quote previews availability+price;
    // /purchase reuses the same migration as /account/migrate (returns
    // {new_uin, token}). Mock IAP for now: any non-empty receipt is accepted.

    /** Availability + tier price for a candidate UIN. `available=false` comes
     *  with a `reason` ("taken"|"too_short"|"too_long"|"self"); price fields
     *  are null when unavailable / out of bounds. */
    data class QuoteResponse(
        val uin: Int = 0,
        val length: Int = 0,
        val available: Boolean = false,
        val price_cents: Int? = null,
        val price_display: String? = null,
        val reason: String? = null,
    )

    /** POST /uin/quote — does this UIN exist + what would it cost. */
    suspend fun uinQuote(uin: Int): QuoteResponse = withContext(Dispatchers.IO) {
        post("/uin/quote", "{\"uin\":$uin}", authed = true, QuoteResponse::class.java)
    }

    /** POST /uin/purchase — buy the UIN (mock receipt) and migrate the
     *  account onto it. Same {new_uin, token} shape as /account/migrate; a
     *  409 means someone grabbed it first (surfaced as "HTTP 409" upstream). */
    suspend fun purchaseUin(uin: Int, receipt: String): MigrateResponse = withContext(Dispatchers.IO) {
        post("/uin/purchase", gson.toJson(mapOf("uin" to uin, "receipt" to receipt)), authed = true, MigrateResponse::class.java)
    }

    // ── server capability discovery (GET /server/info, unauthenticated) ──

    data class ServerCapabilities(val uin_shop: Boolean = false, val registration_policy: String = "open")
    data class ServerInfoResponse(val name: String = "", val capabilities: ServerCapabilities = ServerCapabilities())

    /** Server metadata + optional-surface flags. api.rcq.app advertises
     *  uin_shop=true; self-host rcq-server-ref defaults to false so the shop
     *  surface hides. Unauthenticated + stable across versions. */
    suspend fun serverInfo(): ServerInfoResponse = withContext(Dispatchers.IO) {
        get("/server/info", authed = false, ServerInfoResponse::class.java)
    }

    // ── own profile + privacy (GET /users/{uin}/info, PUT /me) ───────

    /** Own profile + privacy mirror. Visibility/policy fields are only
     *  populated by the server when viewing your own account. */
    data class MeProfile(
        val uin: Int = 0,
        val nickname: String? = null,
        val first_name: String? = null,
        val last_name: String? = null,
        val age: Int? = null,
        val gender: String? = null,
        val city: String? = null,
        val country: String? = null,
        val about: String? = null,
        val interests: List<String> = emptyList(),
        val homepage: String? = null,
        val status_message: String? = null,
        val last_seen_visibility: String? = null,
        val gender_visibility: String? = null,
        val profile_visibility: String? = null,
        val group_invite_policy: String? = null,
        val read_receipts_visibility: String? = null,
        val presence_persistent: Boolean? = null,
        val presence_ttl_minutes: Int? = null,
        val hof_opt_in: Boolean? = null,
        val hof_avatar: String? = null,
    )

    suspend fun getMe(uin: Int): MeProfile = withContext(Dispatchers.IO) {
        get("/users/$uin/info", authed = true, MeProfile::class.java)
    }

    /** Short-lived TURN credentials for WebRTC calls (TURN REST API pattern).
     *  Empty `urls` means TURN isn't configured server-side → STUN-only. */
    data class TurnCreds(
        val urls: List<String> = emptyList(),
        val username: String = "",
        val credential: String = "",
        val ttl: Int = 0,
    )

    suspend fun turnCredentials(): TurnCreds = withContext(Dispatchers.IO) {
        get("/users/me/turn-credentials", authed = true, TurnCreds::class.java)
    }

    // ── audio rooms ──────────────────────────────────────────────────
    data class AudioRoomOut(
        val id: Int,
        val name: String,
        val owner_uin: Int,
        val join_key: String,
        val owner_only_speaking: Boolean = false,
        val active_count: Int = 0,
    )

    suspend fun audioRooms(): List<AudioRoomOut> = withContext(Dispatchers.IO) {
        val arr = get("/audio_rooms", authed = true, Array<AudioRoomOut>::class.java)
        arr.toList()
    }

    suspend fun createAudioRoom(name: String): AudioRoomOut = withContext(Dispatchers.IO) {
        request("POST", "/audio_rooms", gson.toJson(mapOf("name" to name)), authed = true, AudioRoomOut::class.java)
    }

    suspend fun joinAudioRoom(joinKey: String): AudioRoomOut = withContext(Dispatchers.IO) {
        request("POST", "/audio_rooms/join", gson.toJson(mapOf("join_key" to joinKey)), authed = true, AudioRoomOut::class.java)
    }

    suspend fun leaveAudioRoomList(roomId: Int) = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/audio_rooms/$roomId/membership", null, authed = true)
    }

    suspend fun deleteAudioRoom(roomId: Int) = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/audio_rooms/$roomId", null, authed = true)
    }

    // ── People Nearby (geohash check-in) ──────────────────────────────
    data class NearbyUser(
        val uin: Int,
        val nickname: String,
        val anonymous: Boolean = false,
        val status: String = "online",
        val gender: String? = null,
        val bucket_id: String = "",
        val expires_at: String? = null,
    )

    data class CheckinResult(val expires_at: String? = null)

    /** Register the caller in [bucketId] for [ttlSeconds]. [displayName] non-null
     *  = anonymous mode (server surfaces it instead of the real nickname). */
    suspend fun nearbyCheckin(bucketId: String, ttlSeconds: Int, displayName: String?): CheckinResult =
        withContext(Dispatchers.IO) {
            val body = HashMap<String, Any?>()
            body["bucket_id"] = bucketId
            body["ttl_seconds"] = ttlSeconds
            if (displayName != null) body["display_name"] = displayName
            request("POST", "/nearby/checkin", gson.toJson(body), authed = true, CheckinResult::class.java)
        }

    /** Others checked in to any of [buckets] (self + 8 neighbours). Requires the
     *  caller to be checked in (403 otherwise). */
    suspend fun nearbyList(buckets: List<String>): List<NearbyUser> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(buckets.joinToString(","), "UTF-8")
        get("/nearby/list?bucket=$q", authed = true, Array<NearbyUser>::class.java).toList()
    }

    suspend fun nearbyEndCheckin() = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/nearby/checkin", null, authed = true)
    }

    // ── Hood Chat (district chat, per geohash bucket) ─────────────────
    data class HoodMessage(
        val id: Int,
        val bucket_id: String = "",
        val nickname: String = "",
        val owner_uin: Int? = null,
        val body: String = "",
        val anonymous: Boolean = true,
        val reply_to_id: Int? = null,
        val reply_to_nickname: String? = null,
        val reply_to_body: String? = null,
        val deleted: Boolean = false,
        val reactions: Map<String, String> = emptyMap(),
        val created_at: String? = null,
    )
    data class HoodList(val messages: List<HoodMessage> = emptyList(), val bucket_count: Int = 0)
    data class HoodSendBody(
        val body: String,
        val nickname: String,
        val anonymous: Boolean,
        val reply_to_id: Int? = null,
        val reply_to_nickname: String? = null,
        val reply_to_body: String? = null,
    )

    suspend fun hoodMessages(bucket: String): HoodList = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(bucket, "UTF-8")
        get("/hood/messages?bucket=$q", authed = true, HoodList::class.java)
    }
    suspend fun hoodSend(body: HoodSendBody): HoodMessage = withContext(Dispatchers.IO) {
        request("POST", "/hood/send", gson.toJson(body), authed = true, HoodMessage::class.java)
    }
    suspend fun hoodDelete(id: Int) = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/hood/messages/$id", null, authed = true)
    }
    suspend fun hoodReact(id: Int, emoji: String) = withContext(Dispatchers.IO) {
        sendNoResult("POST", "/hood/messages/$id/react", gson.toJson(mapOf("emoji" to emoji)), authed = true)
    }

    // ── Hood Banners (district announcements, mock-IAP paid) ──────────
    data class Banner(
        val id: Int,
        val bucket_id: String = "",
        val text: String = "",
        val image_url: String? = null,
        val image_thumb_url: String? = null,
        val is_anonymous: Boolean = false,
        val is_mine: Boolean = false,
        val owner_nickname: String? = null,
        val owner_uin: Int? = null,
        val duration: String = "",
        val created_at: String? = null,
        val expires_at: String? = null,
    )
    data class BannerList(val items: List<Banner> = emptyList(), val total_active: Int = 0, val can_post: Boolean = true)
    data class BannerPricing(val duration: String, val label: String = "", val price_cents: Int = 0, val price_display: String = "")
    data class CreateBannerBody(
        val bucket_id: String,
        val text: String,
        val is_anonymous: Boolean,
        val duration: String,
        val receipt: String,
        val image_url: String? = null,
        val image_thumb_url: String? = null,
    )
    private data class CreateBannerResult(val banner: Banner)

    suspend fun banners(bucket: String): BannerList = withContext(Dispatchers.IO) {
        val b = java.net.URLEncoder.encode(bucket, "UTF-8")
        get("/hood/banners/$b", authed = true, BannerList::class.java)
    }
    suspend fun bannerPricing(): List<BannerPricing> = withContext(Dispatchers.IO) {
        get("/hood/banners/pricing", authed = true, Array<BannerPricing>::class.java).toList()
    }
    suspend fun createBanner(body: CreateBannerBody): Banner = withContext(Dispatchers.IO) {
        request("POST", "/hood/banners", gson.toJson(body), authed = true, CreateBannerResult::class.java).banner
    }
    suspend fun deleteBanner(id: Int) = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/hood/banners/$id", null, authed = true)
    }

    /** Partial profile/privacy update (PUT /me). Gson omits null fields,
     *  so only what the caller sets is changed. */
    data class UpdateMeBody(
        val nickname: String? = null,
        val status_message: String? = null,
        val gender: String? = null,
        val age: Int? = null,
        val city: String? = null,
        val country: String? = null,
        val about: String? = null,
        val first_name: String? = null,
        val last_name: String? = null,
        val interests: List<String>? = null,
        val homepage: String? = null,
        val last_seen_visibility: String? = null,
        val gender_visibility: String? = null,
        val profile_visibility: String? = null,
        val group_invite_policy: String? = null,
        val read_receipts_visibility: String? = null,
        val presence_persistent: Boolean? = null,
        val presence_ttl_minutes: Int? = null,
        val hof_opt_in: Boolean? = null,
        val hof_avatar: String? = null,
    )

    suspend fun updateMe(body: UpdateMeBody): MeProfile = withContext(Dispatchers.IO) {
        request("PUT", "/users/me", gson.toJson(body), authed = true, MeProfile::class.java)
    }

    private fun sendNoResult(method: String, path: String, json: String?, authed: Boolean) {
        val b = Request.Builder().url("$baseUrl$path")
        when (method) {
            "POST" -> b.post((json ?: "{}").toRequestBody(JSON))
            "DELETE" -> b.delete()
            else -> b.get()
        }
        if (authed) token?.let { b.header("Authorization", "Bearer $it") }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        }
    }

    // ── media blobs (rcq-spec 9) ─────────────────────────────────────

    data class UploadResponse(val media_id: String, val size: Int = 0)

    /** Upload an encrypted blob. pay_jetons=0 (free tier ≤ 50 MB). */
    suspend fun uploadBlob(bytes: ByteArray): UploadResponse = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("blob", "photo.bin", bytes.toRequestBody(OCTET))
            .addFormDataPart("pay_jetons", "0")
            .build()
        val b = Request.Builder().url("$baseUrl/media/upload").post(body)
        token?.let { b.header("Authorization", "Bearer $it") }
        client.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("upload HTTP ${resp.code}: ${text.take(200)}")
            gson.fromJson(text, UploadResponse::class.java)
        }
    }

    /** Cross-island media (federation): PUT an encrypted blob under a
     *  CLIENT-chosen id, so the same envelope reference resolves on every
     *  island the blob is deposited to. Idempotent server-side. */
    suspend fun putBlob(mediaId: String, bytes: ByteArray): UploadResponse = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("blob", "photo.bin", bytes.toRequestBody(OCTET))
            .build()
        val b = Request.Builder().url("$baseUrl/media/$mediaId").put(body)
        token?.let { b.header("Authorization", "Bearer $it") }
        client.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("deposit HTTP ${resp.code}: ${text.take(200)}")
            gson.fromJson(text, UploadResponse::class.java)
        }
    }

    suspend fun getBlob(mediaId: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/media/$mediaId").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("download HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IOException("empty blob")
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────

    private fun <T> post(path: String, json: String, authed: Boolean, type: Class<T>): T {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .post(json.toRequestBody(JSON))
        if (authed) token?.let { builder.header("Authorization", "Bearer $it") }
        return execute(builder.build(), type)
    }

    /** POST with no response body to parse (204 endpoints). Throws on !2xx. */
    private fun postNoContent(path: String, json: String, authed: Boolean) {
        val builder = Request.Builder().url("$baseUrl$path").post(json.toRequestBody(JSON))
        if (authed) token?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
        }
    }

    private fun <T> get(path: String, authed: Boolean, type: Class<T>): T {
        val builder = Request.Builder().url("$baseUrl$path").get()
        if (authed) token?.let { builder.header("Authorization", "Bearer $it") }
        return execute(builder.build(), type)
    }

    /** DELETE with no response body to parse (204 endpoints). Throws on !2xx. */
    private fun deleteNoContent(path: String, authed: Boolean) {
        val builder = Request.Builder().url("$baseUrl$path").delete()
        if (authed) token?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
        }
    }

    /** Generic verb + JSON body → typed response. Used for PATCH/PUT. */
    private fun <T> request(method: String, path: String, json: String, authed: Boolean, type: Class<T>): T {
        val builder = Request.Builder().url("$baseUrl$path").method(method, json.toRequestBody(JSON))
        if (authed) token?.let { builder.header("Authorization", "Bearer $it") }
        return execute(builder.build(), type)
    }

    private fun <T> execute(request: Request, type: Class<T>): T {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${text.take(200)}")
            }
            return gson.fromJson(text, type) ?: throw IOException("empty/unparseable response")
        }
    }

    companion object {
        const val DEFAULT_HOST = "api.rcq.app"
        const val DEFAULT_BASE_URL = "https://$DEFAULT_HOST"
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
