package app.rcq.android.net

import app.rcq.android.crypto.SealedSender
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal REST client for the RCQ backend, talking the same wire protocol
 * as the iOS client (rcq-spec) against prod by default. Registration,
 * peer lookup, and 1:1 sealed send are wired; contacts roster + media come
 * later.
 */
class RcqApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    /** True only for the session's OWN island client (built by Session). The
     *  primary host already has a full boot-time route ladder (direct probe →
     *  CF front → relay → post-engage direct fallback), so it must NOT also
     *  auto-engage the tunnel on a one-off network error: a transient blip on
     *  a healthy network would silently move every user onto a relay. Every
     *  OTHER host (backup island, visited island, guest session, cross-island
     *  media) has no such ladder — see [viaBestRoute]. */
    private val isPrimary: Boolean = false,
    /** Stage 3 of the core-metadata plan: when this answers true, the three
     *  peer key lookups ([fetchPeerDevices], [fetchPeerBundle],
     *  [fetchPeerDeviceBundle]) carry NO session token, and a bundle fetch
     *  spends one anonymous deposit token (`X-Deposit-Token`, minted by
     *  [DepositAuthStore] against this very host) for its one-time prekey.
     *  Every lookup used to tell the island, under our identity, whose keys
     *  we were about to use; that is the pair the sealed queue row was
     *  already being stripped of. A supplier, not a value: Session reads its
     *  own volatile switch (`anon_keys && deposit_auth` from /server/info)
     *  at request time, so an instance built a moment before the answer
     *  landed, or rebuilt by the route watchdog, never holds a stale copy.
     *  False is the old behaviour to the byte, which is what an island
     *  without the flags, a guest session and a foreign island get. The
     *  masquerade header for a closed island is unaffected: the interceptor
     *  stamps it per host, not per account. */
    private val anonKeyLookup: () -> Boolean = { false },
) {

    private val client = OkHttpClient.Builder()
        // Detect a dead/stale connection fast (cellular CGNAT + radio sleep
        // silently kill idle keep-alives). callTimeout stays generous so
        // large media uploads on slow links still complete. BUT a user-chosen
        // local proxy (Tor/i2p/AWG) is inherently slow — i2p/Tor build circuits
        // over many seconds — so give those a far longer leash, or the connect
        // gives up before the tunnel is ready (the "works in Telegram but not
        // RCQ" report: TG is patient, we weren't). Only local-proxy users pay it.
        .connectTimeout(if (SingBoxTransport.localProxyMode()) 30 else 10, TimeUnit.SECONDS)
        .readTimeout(if (SingBoxTransport.localProxyMode()) 30 else 15, TimeUnit.SECONDS)
        .callTimeout(if (SingBoxTransport.localProxyMode()) 90 else 30, TimeUnit.SECONDS)
        // Don't let pooled connections sit idle long enough to die unnoticed;
        // a fresh one is cheap next to a 10s+ dead-socket hang.
        .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
        // Route through the embedded sing-box SOCKS proxy when the
        // RCQ relays are engaged (null = direct, the default).
        // Captured at build time; Session rebuilds this RcqApi after engaging
        // the transport so the new instance picks the proxy up.
        .proxy(SingBoxTransport.proxy())
        // Stamp X-RCQ-Auth from the per-host store so a closed (masquerade)
        // island — own OR foreign — is reachable; no token = no header.
        .addInterceptor(AccessTokenInterceptor)
        .addInterceptor(UserAgentInterceptor)
        .build()
    private val gson = Gson()

    /** Bare host of [baseUrl] (`is2.example.org`), for the blocked-route memo. */
    private val host: String =
        runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: DEFAULT_HOST

    @Volatile private var proxiedClient: OkHttpClient? = null

    /** The client to use RIGHT NOW. [client] captured the proxy at build time,
     *  which is correct for the primary (Session rebuilds it after engaging)
     *  but wrong for the ad-hoc instances created per foreign-island call: one
     *  built before the transport came up would keep going direct, both failing
     *  on a censored network and leaking the foreign host outside the tunnel. */
    private fun http(): OkHttpClient {
        val p = SingBoxTransport.proxy() ?: return client
        if (client.proxy != null) return client
        return proxiedClient ?: client.newBuilder().proxy(p).build().also { proxiedClient = it }
    }

    /** A media-sized twin of whatever client the route ladder picked: same
     *  route, pool and dispatcher, but the whole-call ceiling fits a real file
     *  on a slow uplink. The general 30 s [OkHttpClient.callTimeout] is right
     *  for JSON and was killing every upload that could not finish in half a
     *  minute — "с ПК на смартфон отправляется, а наоборот нет" (#613): the
     *  browser has no such ceiling, the phone did. Progress is still policed
     *  per operation by read/write timeouts, so a genuinely dead transfer
     *  fails in about a minute, not in ten. */
    private fun mediaClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .callTimeout(10, TimeUnit.MINUTES)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * Execute against the best route for THIS host: direct while that works,
     * through the RCQ relays once the direct route to this specific
     * island turns out to be blocked.
     *
     * The gap this closes: the boot ladder probes only the user's OWN island,
     * and the Cloudflare front only ever proxies the flagship. So a user whose
     * network blocks a DIFFERENT island (a tester saw exactly this: "the main
     * server started working, is2 did not", while is2 was healthy from other
     * networks) had no fallback for it at all. [CrossIslandSender] already got
     * this treatment for sealed deposits; every other foreign-island call —
     * backup homes, visited islands, guest sessions, cross-island media, push
     * token registration — went through here and still had none.
     *
     * Only connection-level failures (IOException from the call itself) count.
     * An HTTP error means the island answered, and a tunnel would not change it.
     */
    private fun viaBestRoute(call: (OkHttpClient) -> okhttp3.Response): okhttp3.Response {
        // ⚠ The single chokepoint for every REST call this client makes, and
        // therefore the place a duress session is stopped. A migrated decoy
        // keeps `Session.store` (and this token) on the REAL account by design,
        // so without this any duress-view screen that fetches would answer with
        // — or write to — the real account. See [DuressGate].
        app.rcq.android.security.DuressGate.check()
        if (isPrimary) return call(http())
        if (host in blockedHosts && SingBoxTransport.engageForBlockedDestination("api:$host")) {
            return call(http())
        }
        return try {
            call(http())
        } catch (e: IOException) {
            // Already tunnelled: another attempt would only double the wait.
            if (SingBoxTransport.proxy() != null) throw e
            if (!SingBoxTransport.engageForBlockedDestination("api:$host")) throw e
            blockedHosts.add(host)
            call(http())
        }
    }

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
        // Stable per-install id. Without it the server keys this session as
        // "primary" like every other install of the account, and two of them
        // evict each other's websocket forever.
        val device_id: String? = null,
        // Proof that we hold the private half of `signing_key`: a nonce from
        // /auth/register/challenge and our signature over it. The island needs
        // it before it will let a key that is already claimed be claimed again,
        // or hand out a specific number — a public key alone used to be enough
        // to do both, which is how somebody else's recovery could be captured.
        val challenge: String? = null,
        val signature: String? = null,
    )

    data class RegisterResponse(val uin: Int, val token: String)

    // Account recovery (seed-phrase): prove possession of the signing key to
    // rebind a fresh device to the same UIN. Reuses RegisterResponse {uin,token}.
    data class RecoverChallengeRequest(val signing_key: String)
    data class RecoverChallengeResponse(val challenge: String)
    /** `device_id` names the install to the island, so the token it hands back
     *  carries a `dev` claim like a fresh registration's does. Without it a
     *  recovered session is anonymous ("primary"), and the island can no longer
     *  tell that the device it is about to wake is the one already holding the
     *  socket — the message then arrives twice, once as a tone and once as a
     *  notification. Nullable so an island too old to read it still works. */
    data class RecoverRequest(
        val signing_key: String,
        val challenge: String,
        val signature: String,
        val device_id: String? = null,
    )
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
        // Identity key currently published in the PRIMARY slot — how an
        // install tells "that bundle is mine" from "another install of this
        // account owns it". Null on an account with no bundle, and on an
        // island too old to report it.
        val signal_identity_key: String? = null,
    )
    /** Peer's published bundle for session establishment (PQXDH). */
    data class PeerBundle(
        val uin: Int,
        val registration_id: Int,
        val signal_identity_key: String,
        val signed_prekey: SignedPreKeyDto,
        val kyber_prekey: KyberPreKeyDto,
        val one_time_prekey: OneTimePreKeyDto?,
        // X25519 key the OUTER sealed-sender layer of a copy for THIS device
        // has to be sealed to. The account's messaging key for the primary; a
        // secondary install registers one of its own, and a copy sealed to the
        // account key instead is one it cannot open. Null on an island too old
        // to report it.
        val sealed_sender_pub: String? = null,
    )
    /** A SECONDARY device's bundle: the primary shape plus the two fields only
     *  a non-primary install has to name for itself. */
    data class DeviceBundleBody(
        val signal_identity_key: String,
        val registration_id: Int,
        val signed_prekey: SignedPreKeyDto,
        val kyber_prekey: KyberPreKeyDto,
        val one_time_prekeys: List<OneTimePreKeyDto>,
        val label: String,
        val sealed_sender_pub: String,
    )
    /** The libsignal device id the SERVER assigned; never self-asserted. */
    data class DeviceRegistered(val device_id: Int = 0)
    /** [signal_identity_key] is the libsignal identity that device currently
     *  publishes. Null on an island too old to send it. Reading it here costs
     *  nothing; reading it from a BUNDLE consumes one of the peer's one-time
     *  prekeys, which is why the silence probe compares this instead. */
    data class PeerDeviceRow(
        val device_id: Int = 0,
        val label: String? = null,
        val signal_identity_key: String? = null,
    )
    data class PeerDevices(val uin: Int = 0, val devices: List<PeerDeviceRow> = emptyList())

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
    /** Register this install as a SECONDARY device of the account and take the
     *  id the server hands back (>= 2). 404 on an island whose key store has
     *  the single primary slot and nothing else. */
    suspend fun registerDevice(body: DeviceBundleBody): DeviceRegistered = withContext(Dispatchers.IO) {
        post("/keys/devices", gson.toJson(body), authed = true, DeviceRegistered::class.java)
    }
    /** Replenish a secondary device's one-time prekey pool (→ 204). */
    suspend fun replenishDevicePrekeys(deviceId: Int, body: PrekeysBody) = withContext(Dispatchers.IO) {
        postNoContent("/keys/devices/$deviceId/prekeys", gson.toJson(body), authed = true)
    }
    /** Fetch a peer's bundle to establish a v=2 session. */
    suspend fun fetchPeerBundle(uin: Int): PeerBundle = withContext(Dispatchers.IO) {
        fetchBundle("/keys/$uin/bundle")
    }
    /** Every device of [uin] a sender has to reach, the primary included.
     *  Nothing is consumed by reading it, so the anonymous form needs no
     *  token. `label` is "" for anyone but the owner on a Stage 3 island
     *  (server 2026.08.23.5) and must not be read off a PEER's list; the
     *  owner's own key-slot screen passes [own] and keeps the session token
     *  on that one call about its own account (there is no pair to leak in
     *  naming oneself), which is what the island serves the labels against. */
    suspend fun fetchPeerDevices(uin: Int, own: Boolean = false): PeerDevices = withContext(Dispatchers.IO) {
        get("/keys/$uin/devices", authed = own || !anonKeyLookup(), PeerDevices::class.java)
    }
    /** One device's bundle, for the session that belongs to that device. */
    suspend fun fetchPeerDeviceBundle(uin: Int, deviceId: Int): PeerBundle = withContext(Dispatchers.IO) {
        fetchBundle("/keys/$uin/devices/$deviceId/bundle")
    }

    /** Fill the deposit-token reserve for this host in the background, so the
     *  first bundle fetch after the island said `anon_keys && deposit_auth`
     *  finds a token waiting instead of paying the PoW on the send path. */
    fun warmDepositTokens() {
        if (!anonKeyLookup() || app.rcq.android.security.DuressGate.isActive) return
        DepositAuthStore.warm(host, http())
    }

    /** A bundle lookup the way this island wants it: anonymous with a deposit
     *  token when [anonKeyLookup] says so, the session token otherwise.
     *
     *  One token per fetch, single-use. The island answers 200 (token spent,
     *  `one_time_prekey` filled), 403 (a bad or already-spent token, or an
     *  island that stopped issuing: the epoch most likely rotated under the
     *  cached params), or 404 (no such bundle or device). A token the island
     *  never looked at goes back to the reserve: that is the 404, a 429 from
     *  the per-IP bucket in front of the handler, and a call that got no
     *  answer at all (a dead pooled socket, a tunnel blip). On 403 the cached
     *  params and the tokens of that epoch are dropped, a fresh token is
     *  minted and the fetch is tried once more; a second 403, or no token to
     *  be had at all, falls back to the session-token path for THIS fetch so
     *  a send is never blocked on the mint. The PoW inside the mint runs here
     *  on Dispatchers.IO, never on the main thread.
     *
     *  The duress gate is checked before the mint, not only inside
     *  [viaBestRoute]: the mint talks to the island on its own, and a decoy
     *  session must not spend a PoW and five requests to look offline. */
    private fun fetchBundle(path: String): PeerBundle {
        if (!anonKeyLookup()) return get(path, authed = true, PeerBundle::class.java)
        app.rcq.android.security.DuressGate.check()
        var token = DepositAuthStore.tokenFor(host, http())
        var retried = false
        while (token != null) {
            val presented = token
            val req = Request.Builder().url("$baseUrl$path").get()
                .header("X-Deposit-Token", DepositAuthStore.headerValue(presented))
                .build()
            val resp = try {
                viaBestRoute { it.newCall(req).execute() }
            } catch (e: IOException) {
                DepositAuthStore.giveBack(host, presented)
                throw e
            }
            resp.use {
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful ->
                        return gson.fromJson(text, PeerBundle::class.java) ?: throw IOException("empty/unparseable response")
                    resp.code == 403 -> Unit
                    else -> {
                        if (resp.code == 404 || resp.code == 429) DepositAuthStore.giveBack(host, presented)
                        throw IOException("HTTP ${resp.code}: ${text.take(200)}")
                    }
                }
            }
            DepositAuthStore.forget(host, presented)
            if (retried) break
            retried = true
            token = DepositAuthStore.tokenFor(host, http())
        }
        return get(path, authed = true, PeerBundle::class.java)
    }
    /** Retire one of MY key slots (POST /keys/devices/{id}/revoke, 204):
     *  senders stop fanning out to it and its one-time prekeys are gone.
     *  Slot 1 is refused server-side (that is the primary bundle); a young
     *  linked session gets 403 {code:"revoke_cooldown", wait_seconds} for
     *  anything older than itself. */
    suspend fun revokeKeySlot(deviceId: Int) = withContext(Dispatchers.IO) {
        postNoContent("/keys/devices/$deviceId/revoke", "{}", authed = true)
    }

    // ── Federation Layer B (F1): self-signed home-island record ──
    data class IslandRecordPutResp(val ok: Boolean = false, val ts: Long = 0)

    /** Publish this account's signed home-island record (PUT /federation/island-record). */
    suspend fun publishIslandRecord(docJson: String): IslandRecordPutResp = withContext(Dispatchers.IO) {
        request("PUT", "/federation/island-record", docJson, authed = true, IslandRecordPutResp::class.java)
    }

    /** Host authority of this session's island, for the record's home entry. */
    /** The host this client is TALKING to, which is not the same thing as the
     *  island it belongs to: with the Cloudflare front engaged this is
     *  `cdn.rcq.app`, and under a custom transport it is whatever carries the
     *  traffic. ⚠ Never use it as an identity — that is `Session.serverHost()`.
     *  Stamping this into a sealed envelope's `from_host` made every fronted
     *  user look like they had moved to another island, so their messages were
     *  quarantined as cross-island requests by anyone who had not added them. */
    fun transportHost(): String = runCatching { java.net.URI(baseUrl).host ?: DEFAULT_HOST }.getOrDefault(DEFAULT_HOST)

    suspend fun register(req: RegisterRequest): RegisterResponse = withContext(Dispatchers.IO) {
        post("/auth/register", gson.toJson(req), authed = false, RegisterResponse::class.java)
    }

    /** POST /auth/device — trade this session for one that names the install.
     *  For accounts registered before the client sent a device id: the server
     *  cannot know which install we are, so we tell it once. Returns the new
     *  token (same uin). 404 on an island too old to know the route. */
    suspend fun claimDevice(deviceId: String): SessionResponse = withContext(Dispatchers.IO) {
        post("/auth/device", gson.toJson(mapOf("device_id" to deviceId)), authed = true, SessionResponse::class.java)
    }

    data class SessionResponse(val token: String = "", val ws_url: String = "")

    /** Nonce to sign at registration. Same shape as the recovery challenge, and
     *  deliberately a different `typ` on the island so neither is replayable as
     *  the other. */
    suspend fun registerChallenge(signingKey: String): RecoverChallengeResponse = withContext(Dispatchers.IO) {
        post("/auth/register/challenge", gson.toJson(RecoverChallengeRequest(signingKey)), authed = false, RecoverChallengeResponse::class.java)
    }

    suspend fun recoverChallenge(signingKey: String): RecoverChallengeResponse = withContext(Dispatchers.IO) {
        post("/auth/recover/challenge", gson.toJson(RecoverChallengeRequest(signingKey)), authed = false, RecoverChallengeResponse::class.java)
    }

    suspend fun recover(req: RecoverRequest): RegisterResponse = withContext(Dispatchers.IO) {
        post("/auth/recover", gson.toJson(req), authed = false, RegisterResponse::class.java)
    }

    /** POST /auth/refresh — mint a fresh session token for a NAMED uin by
     *  proving the signing key (same challenge dance as recover, without
     *  recover's oldest-account tie-break). Unauthenticated on purpose: it is
     *  the probe that tells a 4401'd session whether the account still exists
     *  at all — `identity_not_found` here means BURNED, not expired (#655). */
    data class RefreshRequest(
        val uin: Int,
        val signing_key: String,
        val challenge: String,
        val signature: String,
        val device_id: String?,
    )
    suspend fun refreshSession(req: RefreshRequest): RegisterResponse = withContext(Dispatchers.IO) {
        post("/auth/refresh", gson.toJson(req), authed = false, RegisterResponse::class.java)
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
        // Null unless we are a mutual contact of theirs (or it is our own row).
        val avatar_media_id: String? = null,
        val avatar_media_key: String? = null,
        // Real name, ONLY while the account's profile is "everyone" — the
        // island blanks both fields otherwise, on this endpoint and on search.
        // The island has always sent these; we simply threw them away, which is
        // why two accounts that share an auto-generated nickname
        // ("user-5835" — four random digits, so collisions are routine) looked
        // like the same person in the Add sheet.
        val first_name: String? = null,
        val last_name: String? = null,
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

    // ── my reports (the answer to a report I filed) ──────────────────
    // Reports used to be a one-way box: you could file one and never learn
    // whether anyone read it. The reply cannot arrive as a chat message (the
    // server holds no keys and never composes envelopes), so it is fetched
    // here on our own session instead.
    /** One turn in a report's conversation. `from_admin` is the only side the
     *  reader needs: there is exactly one person on each end. */
    data class ReportTurn(
        val id: Int,
        val from_admin: Boolean = false,
        val body: String?,
        val created_at: String?,
    )

    data class MyReport(
        val id: Int,
        val reason: String?,
        val status: String?,
        val created_at: String?,
        /** The LAST operator answer. An island older than the thread sends only
         *  this, which is why the screen still falls back to it. */
        val reply: String?,
        val replied_at: String?,
        /** The whole exchange, oldest first. Empty on an island that predates
         *  tickets, and on a report nobody has answered or added to. */
        val thread: List<ReportTurn> = emptyList(),
    )

    suspend fun myReports(): List<MyReport> = withContext(Dispatchers.IO) {
        get("/reports/mine", authed = true, Array<MyReport>::class.java).toList()
    }

    data class ReportTurnBody(val body: String)

    /** Write back on my own report. Throws "HTTP 409" once the report is
     *  closed, and "HTTP 404" on somebody else's. */
    suspend fun addToMyReport(id: Int, body: String): ReportTurn = withContext(Dispatchers.IO) {
        post("/reports/mine/$id/messages", gson.toJson(ReportTurnBody(body)), authed = true, ReportTurn::class.java)
    }

    /** Drop one of my own reports. 409 = still open against another user, so
     *  moderation keeps it until there is a verdict. */
    suspend fun deleteMyReport(id: Int) = withContext(Dispatchers.IO) {
        deleteNoContent("/reports/mine/$id", authed = true)
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

    data class SendRequest(
        val to_uin: Int,
        val envelope_type: String,
        val payload: String,
        // Which of the recipient's devices this ciphertext was encrypted for.
        // Gson omits it when null, which is the legacy "any device" shape a
        // v=1 seal keeps using and an older island only knows how to route.
        val to_device_id: Int? = null,
        // Stage 2 (core-metadata plan): the retention / push class beside the
        // legacy type. Equals the island's own `_cls_for` derivation for every
        // shipped type, so push + retention behaviour is unchanged; an old
        // island ignores the unknown field.
        val cls: Int? = null,
        // A deposit that must WAKE a closed app (a §5d call wake) rides
        // `envelope_type "message"` with `ring:true`; the island honours it and
        // keeps the quieter type. An island too old to know the flag still
        // gets the legacy "call" type beside it (CrossIslandSender.peerHonoursRing).
        // Gson omits it on an ordinary send.
        val ring: Boolean? = null,
    )
    data class SendResponse(val delivered: Boolean = false, val queued: Boolean = false)

    suspend fun sendSealed(
        toUin: Int,
        payloadB64: String,
        envelopeType: String = "message",
        toDeviceId: Int? = null,
        ring: Boolean = false,
    ): SendResponse =
        withContext(Dispatchers.IO) {
            post(
                "/messages/sealed",
                gson.toJson(
                    SendRequest(
                        toUin,
                        envelopeType,
                        payloadB64,
                        toDeviceId,
                        cls = SealedSender.messageClass(envelopeType),
                        ring = if (ring) true else null,
                    ),
                ),
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
        // The device this copy was encrypted for; null = a legacy sender's
        // copy, addressed to the account rather than to one of its installs.
        val to_device_id: Int? = null,
        // Stage 2 (core-metadata plan): the server's retention / push class
        // (0/1/2) and a durable per-mailbox sequence, served alongside the
        // legacy `id`. Both are read only when present and are null on an older
        // island. The drain still cursors and dedupes on `id` / envelope UUID;
        // `seq` is captured for future ordering / dedup, and a GAP in it is
        // NEVER read as loss (rows served to a sibling device, TTL-expired rows,
        // and other mailboxes all leave holes by design).
        val cls: Int? = null,
        val seq: Long? = null,
    )

    /** Fetch the offline queue with `ack=1`: the server returns rows WITHOUT
     *  deleting them and holds each until the client POSTs /messages/queue/ack
     *  with the ids it actually persisted. The old ack-less call deleted rows
     *  on fetch, so a fetch whose HTTP response was lost in flight (dropped
     *  connection, NAT reset) advanced the server cursor and the messages were
     *  gone — the "изредка теряются сообщения" reports. Now a lost response
     *  means no ack, so the server redelivers; the client dedupes by envelope
     *  UUID. Matches the iOS drain.
     *
     *  [deviceId] is the CALLER's own libsignal device: the island answers with
     *  the rows addressed to it plus every row addressed to no device in
     *  particular, so two installs of one account stop draining each other's
     *  copies. An island that does not know the parameter ignores it and
     *  answers exactly as before. */
    suspend fun drainQueue(deviceId: Int = 1): List<QueuedEnvelope> = withContext(Dispatchers.IO) {
        get("/messages/queue?ack=1&dev=$deviceId", authed = true, Array<QueuedEnvelope>::class.java).toList()
    }

    data class QueueAckIn(val direct_ids: List<Int>, val group_ids: List<Int>)

    /** Advance this device's drain cursor past the rows it has persisted.
     *
     *  ⚠ [deviceId] MUST be the one [drainQueue] asked with. The cursor moves
     *  over the contiguous prefix of the rows this device was SERVED, and the
     *  island works out what it served from `dev`: ask under another id and
     *  the fan-out copies of a sibling install count as rows we failed to ack,
     *  the prefix stops at the first of them, and the cursor never moves
     *  again. */
    suspend fun ackQueue(directIds: List<Int>, groupIds: List<Int>, deviceId: Int = 1) = withContext(Dispatchers.IO) {
        if (directIds.isEmpty() && groupIds.isEmpty()) return@withContext
        sendNoResult("POST", "/messages/queue/ack?dev=$deviceId", gson.toJson(QueueAckIn(directIds, groupIds)), authed = true)
    }

    // ── room log drain (Stage 5 of the core-metadata plan) ───────────

    /** One row of a room's log: the same envelope types and payloads the
     *  legacy group rows of /messages/queue carry (`gmsg` broadcasts plus the
     *  rows sealed to this member: `skdm`, `sknack`, `reaction`, `message`,
     *  `system`, `delete`, `read` ...), so it goes through the same ingest.
     *  `seq` is the room's durable sequence, the thing the cursor moves over. */
    data class GroupLogRow(
        val gid: Int,
        val seq: Long,
        val envelope_type: String?,
        val cls: Int? = null,
        val payload: String?,
        val received_at: String? = null,
    )

    /** A room to read and, optionally, from where. `after` is recovery only
     *  (re-read without moving the stored cursor); the normal drain omits it
     *  and lets the island serve from this device's cursor. */
    data class GroupLogRoomIn(val gid: Int, val after: Long? = null)

    /** `rooms` omitted = every room the account is a member of, in one call. */
    data class GroupLogFetchIn(val rooms: List<GroupLogRoomIn>? = null, val limit: Int = 500)

    /** `heads` and `cursors` are keyed by room id; Gson keeps the JSON object
     *  keys as strings, so they are read back through [GroupLogFetchOut.seqOf].
     *  `more` means `limit` cut the answer short: fetch again. */
    data class GroupLogFetchOut(
        val rows: List<GroupLogRow> = emptyList(),
        val heads: Map<String, Long> = emptyMap(),
        val cursors: Map<String, Long> = emptyMap(),
        val more: Boolean = false,
    ) {
        companion object {
            fun seqOf(map: Map<String, Long>, gid: Int): Long? = map[gid.toString()]
        }
    }

    data class GroupLogAckRoomIn(val gid: Int, val upto: Long)
    data class GroupLogAckIn(val rooms: List<GroupLogAckRoomIn>)

    /** Fetch the rows this device is behind on in every room the account is
     *  in, above the island's stored cursor for this device. The cursor is
     *  created AT THE HEAD on a device's first read (a fresh install gets no
     *  backlog, same as the 1:1 watermark) and moves only on [ackGroupLog]:
     *  a crash between fetch and persist re-serves the rows, and the UUID
     *  dedupe collapses a repeat.
     *
     *  ⚠ The first call flips the ACCOUNT to a log reader on that island:
     *  from then on new room posts for it exist only in the log. Only ever
     *  called on an island that advertises `group_log`, and always next to
     *  the legacy drain, which keeps serving whatever was written before. */
    suspend fun fetchGroupLog(limit: Int = 500): GroupLogFetchOut = withContext(Dispatchers.IO) {
        request("POST", "/messages/group-log/fetch", gson.toJson(GroupLogFetchIn(limit = limit)), authed = true, GroupLogFetchOut::class.java)
    }

    /** Move this device's cursor in each room forward to the max seq it has
     *  PERSISTED (or held). Forward only on the island, so a stale or
     *  out-of-order ack is harmless; one call for all rooms. */
    suspend fun ackGroupLog(upto: Map<Int, Long>) = withContext(Dispatchers.IO) {
        if (upto.isEmpty()) return@withContext
        val rooms = upto.map { (gid, seq) -> GroupLogAckRoomIn(gid, seq) }
        sendNoResult("POST", "/messages/group-log/ack", gson.toJson(GroupLogAckIn(rooms)), authed = true)
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
        // Profile picture, same shape as a group's: encrypted blob + its key.
        // The server only fills these in for people allowed to see it.
        val avatar_media_id: String? = null,
        val avatar_media_key: String? = null,
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

    /** A bug-report attachment: an AES-GCM-sealed blob (nonce||ct||tag) already
     *  uploaded to /media, plus the key the admin needs to decrypt it. */
    data class ReportAttachment(val media_id: String, val key: String, val mime: String, val size: Int)
    data class ReportBody(
        val target_uin: Int,
        val reason: String,
        val context: String = "",
        val attachments: List<ReportAttachment> = emptyList(),
    )

    /** File an abuse / bug report (POST /reports), optionally with attachments. */
    suspend fun report(targetUin: Int, reason: String, context: String = "", attachments: List<ReportAttachment> = emptyList()) = withContext(Dispatchers.IO) {
        sendNoResult("POST", "/reports", gson.toJson(ReportBody(targetUin, reason, context, attachments)), authed = true)
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
        // Sender-keys capability of this member's account (gmsg/skdm support).
        val sender_keys: Boolean = false,
        // Profile picture, gated by MEMBERSHIP rather than by the contact list:
        // sharing a group is the relationship here, the same one that already
        // exposes the nickname on this row.
        val avatar_media_id: String? = null,
        val avatar_media_key: String? = null,
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
        // Present even when the roster is not, so a list can say "1869 members"
        // without paying for the roster. Older islands omit it; fall back to
        // the roster's own size.
        val member_count: Int = 0,
        val members: List<GroupMemberOut> = emptyList(),
    )

    /** The account's groups.
     *
     *  [withMembers]=false asks the island to leave the roster out, which is
     *  the expensive part: every member with two base64 keys each, on a group
     *  with a couple of thousand people. The count still rides along, and the
     *  roster is one `groupInfo` away for the moments that genuinely need it.
     *  Older islands ignore the parameter and answer with the roster anyway,
     *  which is exactly the safe direction. */
    suspend fun groups(withMembers: Boolean = true): List<GroupOut> = withContext(Dispatchers.IO) {
        val path = if (withMembers) "/groups" else "/groups?members=0"
        get(path, authed = true, Array<GroupOut>::class.java).toList()
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
    // Stage 2: `cls` mirrors the island's `_cls_for` for the group type (skdm /
    // sknack are critical, everything else content); an old island ignores it.
    data class GroupSendBody(val group_id: Int, val envelope_type: String, val payloads: List<GroupPayload>, val cls: Int? = null)

    /** Group send: per-recipient fan-out (anonymous, like 1:1 sealed).
     *  [authed] attaches our bearer token — used ONLY for owner_only
     *  (broadcast) groups, where the server must verify the poster IS the
     *  owner. For normal 'all' groups it stays false so sealed sender keeps
     *  hiding which member sent the message. */
    suspend fun sendGroupSealed(groupId: Int, payloads: List<GroupPayload>, envelopeType: String = "message", authed: Boolean = false): SendResponse =
        withContext(Dispatchers.IO) {
            post(
                "/messages/group-sealed",
                gson.toJson(GroupSendBody(groupId, envelopeType, payloads, cls = SealedSender.messageClass(envelopeType))),
                authed = authed,
                SendResponse::class.java,
            )
        }

    data class GroupBroadcastBody(val group_id: Int, val envelope_type: String, val payload: String, val cls: Int? = null)

    /** Sender-keys encrypt-once group send: ONE ciphertext for the whole
     *  group. The server fans the same blob to every capable member. Always
     *  authed — the new endpoint enforces owner_only strictly (and an authed
     *  poster costs nothing for 'all' groups). */
    suspend fun sendGroupBroadcast(groupId: Int, payload: String, envelopeType: String = "message"): SendResponse =
        withContext(Dispatchers.IO) {
            post(
                "/messages/group-broadcast",
                gson.toJson(GroupBroadcastBody(groupId, envelopeType, payload, cls = SealedSender.messageClass(envelopeType))),
                authed = true,
                SendResponse::class.java,
            )
        }

    data class CapabilitiesBody(val sender_keys: Boolean)

    /** Advertise this client's capabilities (fire-and-forget at start). */
    suspend fun advertiseCapabilities(senderKeys: Boolean): Unit =
        withContext(Dispatchers.IO) {
            postNoContent("/users/me/capabilities", gson.toJson(CapabilitiesBody(senderKeys)), authed = true)
        }

    data class PushTokenBody(
        val token: String,
        val platform: String = "android-up",
        /** This install's id — the SAME one the session token carries as its
         *  `dev` claim, so the server can tell which endpoint belongs to which
         *  connected device. Without it the server can only ask "is this
         *  ACCOUNT online", and one open desktop suppressed the wake for every
         *  other device of the account. */
        val device_id: String? = null,
    )

    /** Register this device's UnifiedPush endpoint URL so the server can POST
     *  offline wakes to it. Idempotent upsert server-side (uin, token). */
    suspend fun setPushToken(endpoint: String, deviceId: String? = null): Unit =
        withContext(Dispatchers.IO) {
            postNoContent(
                "/users/me/push-token",
                gson.toJson(PushTokenBody(endpoint, device_id = deviceId)),
                authed = true,
            )
        }

    /** Drop a UnifiedPush endpoint (distributor unregistered / logout). */
    suspend fun deletePushToken(endpoint: String): Unit =
        withContext(Dispatchers.IO) {
            sendNoResult("DELETE", "/users/me/push-token", gson.toJson(PushTokenBody(endpoint)), authed = true)
        }

    // Server push preferences (the per-category toggles the iOS Notifications
    // screen also drives). Defaults mirror the server's hydration defaults.
    data class PushPrefs(
        val contact_requests: Boolean = true,
        val trades_from_contacts: Boolean = true,
        val trades_from_strangers: Boolean = false,
    )

    data class PushPrefsBody(
        val contact_requests: Boolean? = null,
        val trades_from_contacts: Boolean? = null,
        val trades_from_strangers: Boolean? = null,
        // Full lists of fully-muted groups / peers. The server's push fan-out
        // (is_group_muted / should_push_for) reads these to SKIP the APNs /
        // UnifiedPush wake for a muted thread. Null = field omitted by Gson =
        // untouched (partial update), so a contact_requests-only PUT never
        // clears the mute lists, and vice-versa.
        val muted_group_ids: List<Int>? = null,
        val muted_uins: List<Int>? = null,
    )

    suspend fun getPushPreferences(): PushPrefs = withContext(Dispatchers.IO) {
        get("/users/me/push-preferences", authed = true, PushPrefs::class.java)
    }

    suspend fun setPushPreferences(body: PushPrefsBody): Unit = withContext(Dispatchers.IO) {
        sendNoResult("PUT", "/users/me/push-preferences", gson.toJson(body), authed = true)
    }

    /** What the server's last wake attempt to each registered device did.
     *  `last_error` is an HTTP status the distributor answered with ("507" =
     *  no connected subscriber, "429" = its rate bucket is drained) or an
     *  exception name; null means the last attempt got through. */
    data class PushHealthRow(
        val platform: String,
        val host: String? = null,
        val last_error: String? = null,
        val last_ok: String? = null,
        val registered_at: String? = null,
    )

    data class PushHealth(val devices: List<PushHealthRow> = emptyList())

    suspend fun pushHealth(): PushHealth = withContext(Dispatchers.IO) {
        get("/users/me/push-health", authed = true, PushHealth::class.java)
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

    /** Superset of [MigrateResponse]: `new_uin`/`token` are filled exactly when
     *  the caller asked to switch onto the number, `owned` is the collection
     *  afterwards. The server returns this shape from both /uin/purchase and
     *  /uin/activate. */
    data class PurchaseResponse(
        val new_uin: Int? = null,
        val token: String? = null,
        val switched: Boolean = false,
        val owned: List<Int> = emptyList(),
    )

    /** POST /uin/purchase — take the UIN. `switch=false` (the default here)
     *  puts it in the collection and leaves the account answering as it is;
     *  `switch=true` migrates onto it right away. A 409 means someone grabbed
     *  it first (surfaced as "HTTP 409" upstream).
     *
     *  The old `receipt` field is gone: the server never validated it, and a
     *  field that looks like a payment check but is not is worse than none. */
    suspend fun purchaseUin(uin: Int, switch: Boolean = false): PurchaseResponse = withContext(Dispatchers.IO) {
        post("/uin/purchase", gson.toJson(mapOf("uin" to uin, "switch" to switch)), authed = true, PurchaseResponse::class.java)
    }

    /** One number held in the collection. `acquired_at` is an ISO-8601 stamp. */
    data class OwnedUinItem(val uin: Int = 0, val length: Int = 0, val acquired_at: String? = null)

    data class MyUinsResponse(
        val active: Int = 0,
        val owned: List<OwnedUinItem> = emptyList(),
        // How many one account may hold on THIS island. Defaults to 10 so a
        // server that predates the field still gives a sane number to show.
        val max_owned: Int = 10,
    )

    /** GET /uin/mine — the number this account answers as, plus everything it
     *  holds. Answers regardless of the shop toggle: an operator closing the
     *  shop stops new sales, it does not hide from people what they own. */
    suspend fun myUins(): MyUinsResponse = withContext(Dispatchers.IO) {
        get("/uin/mine", authed = true, MyUinsResponse::class.java)
    }

    /** DELETE /uin/mine/{uin} — give a held number back to the pool.
     *
     *  Collecting numbers nobody chose is a side effect of the vault: switching
     *  to a number puts the previous one in the collection whether it was
     *  wanted or not, and the long number handed out at signup is usually the
     *  first one people stop wanting (user request). Irreversible — the number
     *  goes back in the pool and somebody else may take it.
     *
     *  404 = not held by this account, 400 = it is the number you answer as. */
    suspend fun releaseUin(uin: Int) = withContext(Dispatchers.IO) {
        sendNoResult("DELETE", "/uin/mine/$uin", null, authed = true)
    }

    /** POST /uin/activate — answer as a number already in the collection. The
     *  number being used goes into the collection in its place, so this is
     *  reversible and never loses one. Migrates, hence the {new_uin, token}.
     *  404 means the number is not held by this account. */
    suspend fun activateUin(uin: Int): PurchaseResponse = withContext(Dispatchers.IO) {
        post("/uin/activate", "{\"uin\":$uin}", authed = true, PurchaseResponse::class.java)
    }

    // ── server capability discovery (GET /server/info, unauthenticated) ──

    // nearby/random_chat default TRUE so a server that omits them (legacy)
    // keeps the entry visible; the operator hides a feature via the admin
    // console (Features). max_accounts_per_device caps the account switcher.
    data class ServerCapabilities(
        val uin_shop: Boolean = false,
        val hall_of_fame: Boolean = false,
        val registration_policy: String = "open",
        val nearby: Boolean = true,
        val random_chat: Boolean = true,
        // An island may run no report desk at all. Permissive default: an
        // older island that does not advertise the flag still accepts them.
        val reports: Boolean = true,
        val max_accounts_per_device: Int = 5,
        // Defaults false, like `uin_shop` and `hall_of_fame` above and unlike
        // the feature toggles, though for a different reason: those two are
        // surfaces that exist on the flagship and nowhere else, this one is a
        // wire ability. It was born together with
        // the `ring` field of a sealed deposit (Stage 2 of the core-metadata
        // plan, server 2026.08.22.15): an island that does not advertise it is
        // an island that does not know `ring`, and a call deposited there as a
        // plain "message" never wakes a closed app. A sender reads this before
        // a waking call and falls back to the legacy "call" type when it is
        // anything but true. See CrossIslandSender.peerHonoursRing.
        val envelope_class: Boolean = false,
        // Stage 3 of the same plan (server 2026.08.23.4): the three peer key
        // lookups (GET /keys/{uin}/devices and the two bundle routes) take no
        // session token on this island, and a one-time prekey is handed out
        // against an anonymous deposit token instead. `anon_keys` says the
        // island understands that; `deposit_auth` says it also ISSUES the
        // tokens. A client goes anonymous only when BOTH are true (see the
        // `anonKeyLookup` supplier on the constructor); an island missing
        // either gets the old authenticated calls, never a half-anonymous
        // one. Both default false: an older island omits them.
        val anon_keys: Boolean = false,
        val deposit_auth: Boolean = false,
        // Stage 5 of the plan (server 2026.08.23.6): a post into a room is
        // ONE row in the room's log, read through a per-device cursor at
        // POST /messages/group-log/fetch, instead of one per-member queue row
        // for every member. An island that advertises this is drained from
        // the log next to the legacy queue (see Session.drainGroupLog); one
        // that does not is drained exactly as before. Default false: an older
        // island omits it, and asking it for a log it does not keep would
        // only earn a 404.
        val group_log: Boolean = false,
    )
    data class ServerInfoResponse(
        val name: String = "",
        // The operator's welcome / rules text. It has been served since islands
        // existed and no client read it, so the admin panel carried a warning
        // saying that whatever you type here changes nothing.
        val welcome: String = "",
        val capabilities: ServerCapabilities = ServerCapabilities(),
    )


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
        val avatar_media_id: String? = null,
        val avatar_media_key: String? = null,
        val first_name: String? = null,
        val last_name: String? = null,
        val age: Int? = null,
        val gender: String? = null,
        val city: String? = null,
        val country: String? = null,
        val about: String? = null,
        val interests: List<String> = emptyList(),
        val homepage: String? = null,
        /** The status the user CHOSE (online/away/dnd/invisible). Self-view only:
         *  for anyone else the island folds invisible down to offline. Without
         *  reading it back, a relaunch showed "Online" to someone who had picked
         *  Invisible, and their own app was the only place saying otherwise. */
        val status: String? = null,
        val status_message: String? = null,
        val last_seen_visibility: String? = null,
        val gender_visibility: String? = null,
        val profile_visibility: String? = null,
        val group_invite_policy: String? = null,
        val read_receipts_visibility: String? = null,
        /** everyone | contacts | nobody. The one policy the server has always
         *  enforced and this client could never set: a person being called by
         *  strangers had no way to stop it except on an iPhone. */
        val call_policy: String? = null,
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
        // How many the room holds. The server has always enforced a cap and
        // never named it, so the app could only say "full" after a refused
        // entry. Defaulted for islands still running an older build.
        val capacity: Int = 8,
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

    /** Owner-only rename. The server fans `audio_room_renamed` to every
     *  subscriber, so other people's lists follow without a refetch — the
     *  endpoint has been there since audio rooms shipped and nothing on
     *  Android ever called it. */
    suspend fun renameAudioRoom(roomId: Int, name: String): AudioRoomOut = withContext(Dispatchers.IO) {
        request("PATCH", "/audio_rooms/$roomId", gson.toJson(mapOf("name" to name)), authed = true, AudioRoomOut::class.java)
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

    /** Partial profile/privacy update (PUT /me). Gson omits null fields,
     *  so only what the caller sets is changed. */
    data class UpdateMeBody(
        // Both blank clears the picture; both absent leaves it untouched, so a
        // patch that only changes a nickname cannot wipe it.
        val avatar_media_id: String? = null,
        val avatar_media_key: String? = null,
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
        val call_policy: String? = null,
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
            // PUT/PATCH used to fall through to GET (the `else`), so every
            // result-less PUT — push-preferences (mute sync + contact-request
            // toggle) — silently sent a bodyless GET that 200s without writing.
            "PUT" -> b.put((json ?: "{}").toRequestBody(JSON))
            "PATCH" -> b.patch((json ?: "{}").toRequestBody(JSON))
            "DELETE" -> b.delete()
            else -> b.get()
        }
        if (authed) token?.let { b.header("Authorization", "Bearer $it") }
        viaBestRoute { it.newCall(b.build()).execute() }.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        }
    }

    // ── media blobs (rcq-spec 9) ─────────────────────────────────────

    data class UploadResponse(val media_id: String, val size: Int = 0)

    /** Upload an encrypted blob. Free up to the island's size cap. */
/** The blob part of an upload, written in chunks so progress means something.
 *
 *  ⚠ Wrapping the finished multipart body in a counting sink does NOT work:
 *  the source is a byte array in memory, so OkHttp hands it to the socket in
 *  one or two writes and the callback fires twice for a 31 MB file — a bar
 *  that jumps from nothing to done. Writing the array ourselves in 64 KB
 *  slices is what turns it into a line that moves.
 *
 *  Asked for by a tester in Shanghai (report #537): on a slow uplink a
 *  spinner cannot be told apart from an upload that never started.
 */
private class ProgressBody(
    private val bytes: ByteArray,
    private val type: okhttp3.MediaType?,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = bytes.size.toLong()

    override fun writeTo(sink: okio.BufferedSink) {
        val total = bytes.size.toLong()
        var sent = 0L
        val chunk = 64 * 1024
        while (sent < total) {
            val n = minOf(chunk.toLong(), total - sent).toInt()
            sink.write(bytes, sent.toInt(), n)
            sink.flush()
            sent += n
            onProgress(sent, total)
        }
    }
}

    suspend fun uploadBlob(
        bytes: ByteArray,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): UploadResponse = withContext(Dispatchers.IO) {
        val part = if (onProgress == null) bytes.toRequestBody(OCTET)
                   else ProgressBody(bytes, OCTET, onProgress)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("blob", "photo.bin", part)
            .build()
        val b = Request.Builder().url("$baseUrl/media/upload").post(body)
        token?.let { b.header("Authorization", "Bearer $it") }
        viaBestRoute { mediaClient(it).newCall(b.build()).execute() }.use { resp ->
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
        viaBestRoute { mediaClient(it).newCall(b.build()).execute() }.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("deposit HTTP ${resp.code}: ${text.take(200)}")
            gson.fromJson(text, UploadResponse::class.java)
        }
    }

    suspend fun getBlob(mediaId: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/media/$mediaId").get().build()
        viaBestRoute { mediaClient(it).newCall(req).execute() }.use { resp ->
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
        viaBestRoute { it.newCall(builder.build()).execute() }.use { resp ->
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
        viaBestRoute { it.newCall(builder.build()).execute() }.use { resp ->
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
        viaBestRoute { it.newCall(request).execute() }.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${text.take(200)}")
            }
            return gson.fromJson(text, type) ?: throw IOException("empty/unparseable response")
        }
    }

    companion object {
    /** `/server/info` for an island we are NOT on, by host. Used before joining
     *  one: the name and rules belong on the confirm, which is the only moment
     *  anybody reads them. Unauthenticated by design — this is the island's
     *  public description. Null when the island does not answer. */
    suspend fun serverInfoOf(host: String): ServerInfoResponse? = withContext(Dispatchers.IO) {
        runCatching {
            RcqApi("https://$host", isPrimary = false)
                .get("/server/info", authed = false, ServerInfoResponse::class.java)
        }.getOrNull()
    }

        const val DEFAULT_HOST = "api.rcq.app"
        const val DEFAULT_BASE_URL = "https://$DEFAULT_HOST"

        /** Foreign hosts whose DIRECT route was already found to be blocked.
         *  Process-wide on purpose: the instances that talk to those hosts are
         *  created per call, so a per-instance memo would re-pay the failed
         *  direct attempt every time. Mirrors CrossIslandSender.needsTunnel. */
        private val blockedHosts =
            java.util.Collections.synchronizedSet(HashSet<String>())
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
