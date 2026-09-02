package app.rcq.android.net

import android.util.Base64
import app.rcq.android.crypto.Envelope
import app.rcq.android.crypto.SealedSender
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Federation Layer B (F2) — cross-island send.
 *
 * Deliver a sealed envelope to a peer on ANOTHER island: resolve their current
 * home(s) from their island's open key card + signed record, v=1-seal to them
 * (their public identity key from the card — a v=2 session would need their
 * auth-gated prekey bundle, which a cross-island sender has no token for, and
 * v=1 is the 1:1 default), and deposit a copy to each home's `/messages/sealed`.
 *
 * Mirrors web-chat/src/lib/federation-send.ts, which is verified end-to-end
 * against a real second island. Blocking I/O — call from a Dispatchers.IO context.
 */
object CrossIslandSender {

    private val JSON = "application/json".toMediaType()
    private val OCTET = "application/octet-stream".toMediaType()
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        // Stamp X-RCQ-Auth on every foreign-host call so a closed (masquerade)
        // island is reachable; no token for the host = no header (public
        // islands unaffected). proxiedClient inherits this via newBuilder().
        .addInterceptor(AccessTokenInterceptor)
        .addInterceptor(UserAgentInterceptor)
        // A foreign island trusted by fingerprint (design §6), inherited by
        // proxiedClient the same way.
        .islandTrust()
        .build()
    @Volatile private var proxiedClient: OkHttpClient? = null

    /** Cross-island HTTP must ride the sing-box SOCKS proxy when the
     *  obfuscated/onion transport is engaged: on a censored network the direct
     *  foreign-island deposit is blocked (the recipient never gets the message),
     *  and a direct call would also leak the foreign host + our real IP outside
     *  the tunnel. Mirrors RcqApi's `.proxy(SingBoxTransport.proxy())`. Direct
     *  (unchanged) whenever the transport is off. */
    private fun http(): OkHttpClient {
        val p = SingBoxTransport.proxy() ?: return baseClient
        return proxiedClient ?: baseClient.newBuilder().proxy(p).build().also { proxiedClient = it }
    }

    /** Foreign hosts whose direct route we already found to be blocked, so the
     *  retry below happens once per host and not once per call. */
    private val needsTunnel = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Run a call to [host], falling back to the RCQ relays when the
     * DIRECT route to that specific island is blocked.
     *
     * The app auto-engages the tunnel when its OWN island fails to answer, and
     * the Cloudflare front only ever proxies the flagship — so a user whose
     * carrier blocks the subnet of ANOTHER island (a tester saw exactly this:
     * "the main server started working, is2 did not", while is2 was healthy and
     * answering from other networks) had no fallback at all for that island: it
     * simply stayed unreachable while everything else looked fine.
     *
     * Only connection-level failures trigger it. An HTTP error means we reached
     * the island and it answered, which the tunnel would not change.
     */
    private fun <T> viaBestRoute(host: String, call: (OkHttpClient) -> T): T {
        // Everything here is addressed FROM the real uin and signed with the
        // real key: §5e profile broadcasts, sealed deposits to foreign islands,
        // contact-request cards. See [DuressGate].
        app.rcq.android.security.DuressGate.check()
        if (host in needsTunnel && SingBoxTransport.engageForBlockedDestination(host)) {
            return call(http())
        }
        return try {
            call(http())
        } catch (e: java.io.IOException) {
            // Already tunnelled: a failure here is the island or the relay path,
            // and re-running the same call would only double the wait.
            if (SingBoxTransport.proxy() != null) throw e
            if (!SingBoxTransport.engageForBlockedDestination(host)) throw e
            needsTunnel.add(host)
            call(http())
        }
    }

    data class Card(
        val identityKey: String,
        val signingKey: String,
        val signalIdentityKey: String?,
        // §5c display: the open card now carries the peer's nickname (+ optional
        // gender/status) so a cross-island contact shows a real name, not uin@host.
        val nickname: String? = null,
        val gender: String? = null,
        val statusMessage: String? = null,
    )

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    /** Fetch a peer's open public-key card from their island (no auth). */
    fun fetchCard(host: String, uin: Int): Card? {
        val req = Request.Builder().url("https://$host/federation/keys/$uin").get().build()
        viaBestRoute(host) { it.newCall(req).execute() }.use { resp ->
            if (!resp.isSuccessful) return null
            val o = JsonParser.parseString(resp.body?.string() ?: return null).asJsonObject
            return Card(
                identityKey = o.get("identity_key").asString,
                signingKey = o.get("signing_key").asString,
                signalIdentityKey = o.str("signal_identity_key"),
                nickname = o.str("nickname"),
                gender = o.str("gender"),
                statusMessage = o.str("status_message"),
            )
        }
    }

    /** Does [host] answer a definite "no such number" for [uin]? Used once,
     *  after a send to that peer has already failed, to tell "they burned the
     *  account" apart from "the island is unreachable right now". Anything but
     *  a clean 404 returns false: we never mark someone gone on a maybe. */
    fun peerMissing(host: String, uin: Int): Boolean = runCatching {
        val req = Request.Builder().url("https://$host/federation/keys/$uin").get().build()
        viaBestRoute(host) { it.newCall(req).execute() }.use { it.code == 404 }
    }.getOrDefault(false)

    /** §5c cross-island group add: resolve the local uin bound to [signingKeyB64]
     *  on [host], or null when no account there has that key yet. Open inverse
     *  map of the key card; lets an owner-initiated add reuse an existing
     *  account instead of minting a duplicate. */
    fun resolveUinForKey(host: String, signingKeyB64: String): Int? {
        val url = "https://$host/federation/uin-for-key?signing_key=" +
            java.net.URLEncoder.encode(signingKeyB64, "UTF-8")
        val req = Request.Builder().url(url).get().build()
        viaBestRoute(host) { it.newCall(req).execute() }.use { resp ->
            if (!resp.isSuccessful) return null
            return runCatching {
                JsonParser.parseString(resp.body?.string() ?: return null).asJsonObject.get("uin").asInt
            }.getOrNull()
        }
    }

    /** §5c: register a cross-island contact's PUBLIC keys on [host] so an
     *  owner-initiated group add has a local uin to put in the roster. The
     *  contact later recovers the SAME uin (recover-first is keyed by the
     *  signing key). Returns the new local uin, or null on failure. */
    fun registerForeignKeys(host: String, identityKeyB64: String, signingKeyB64: String, nickname: String): Int? {
        val body = com.google.gson.JsonObject().apply {
            addProperty("nickname", nickname)
            addProperty("identity_key", identityKeyB64)
            addProperty("signing_key", signingKeyB64)
        }
        val req = Request.Builder().url("https://$host/auth/register")
            .post(body.toString().toRequestBody(JSON)).build()
        viaBestRoute(host) { it.newCall(req).execute() }.use { resp ->
            if (!resp.isSuccessful) return null
            return runCatching {
                JsonParser.parseString(resp.body?.string() ?: return null).asJsonObject.get("uin").asInt
            }.getOrNull()
        }
    }

    /** Resolve the peer's verified home islands (spec §4). Falls back to the
     *  single home [(host, uin)] when no record is published or it doesn't verify. */
    fun resolveHomes(host: String, uin: Int): List<RcqFederation.Home> {
        val fallback = listOf(RcqFederation.Home(host, uin))
        val card = fetchCard(host, uin) ?: return fallback
        val req = Request.Builder().url("https://$host/federation/island-record/$uin").get().build()
        viaBestRoute(host) { it.newCall(req).execute() }.use { resp ->
            if (!resp.isSuccessful) return fallback
            val doc = runCatching {
                JsonParser.parseString(resp.body?.string() ?: "").asJsonObject
            }.getOrNull() ?: return fallback
            val v = RcqFederation.verifyRecord(doc, expectedIk = card.signalIdentityKey, expectedSk = card.signingKey)
            if (v is RcqFederation.VerifyResult.Ok) {
                return v.doc.getAsJsonArray("homes").map {
                    val h = it.asJsonObject
                    RcqFederation.Home(h.get("host").asString, h.get("uin").asInt)
                }
            }
            return fallback
        }
    }

    /** Deposit an already-encrypted media blob under a CLIENT-chosen id
     *  (`PUT /media/{id}`, idempotent, no auth — same trust model as the
     *  envelope deposit). Cross-island media: the recipient fetches media from
     *  their OWN island, so the sender puts the blob there itself
     *  (deposit-the-blob — islands never talk to each other). */
    fun depositBlob(
        host: String,
        mediaId: String,
        blob: ByteArray,
        /// #831: without this a cross-island picture showed no percentage at
        /// all — the bar sat indeterminate for the whole upload, which on a
        /// slow link is the longest part of sending. Same chunked body the
        /// own-island upload has always used.
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): Boolean {
        val part = if (onProgress == null) blob.toRequestBody(OCTET)
                   else ChunkedBlobBody(blob, OCTET, onProgress)
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("blob", "photo.bin", part)
            .build()
        val req = Request.Builder().url("https://$host/media/$mediaId").put(body).build()
        return runCatching { viaBestRoute(host) { c -> c.newCall(req).execute() }.use { it.isSuccessful } }.getOrDefault(false)
    }

    /** [depositBlob] for a file too big to hold: the source is read a chunk at
     *  a time, sealed chunk by chunk into an RCQM1 container
     *  (`crypto/MediaStream.kt`), and written straight to the socket.
     *
     *  ⚠ The whole-call ceiling is taken off for this one call. The 30 seconds
     *  every other cross-island call runs under is right for a signed JSON card
     *  and absurd for a film: a cross-island video would have been cut off at
     *  thirty seconds no matter how healthy the link. Stalls are still caught,
     *  by the per-socket read/write timeouts. */
    fun depositBlobStreaming(
        host: String,
        mediaId: String,
        openSource: () -> java.io.InputStream,
        plainLen: Long,
        key: ByteArray,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): Boolean {
        val part = object : okhttp3.RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength() = app.rcq.android.crypto.MediaStream.blobLength(plainLen)
            override fun writeTo(sink: okio.BufferedSink) {
                openSource().use { input ->
                    app.rcq.android.crypto.MediaStream.seal(
                        input, sink.outputStream(), key, plainLen, onProgress = onProgress,
                    )
                }
            }
        }
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("blob", "media.bin", part)
            .build()
        val req = Request.Builder().url("https://$host/media/$mediaId").put(body).build()
        return runCatching {
            viaBestRoute(host) { c ->
                c.newBuilder()
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                    .newCall(req).execute()
            }.use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** §5d cross-island call signaling: v=1-seal a call envelope and deposit it
     *  to the contact's PRIMARY island only. No backup-home copies — backup
     *  mailboxes are polled (~30s), useless for real-time signaling, and if the
     *  primary island is down the call cannot work anyway. */
    fun deliverCall(
        contact: CrossIslandStore.Contact,
        env: Envelope,
        ownUin: Int,
        signingPriv: ByteArray,
        signingPub: ByteArray,
        ownHost: String,
    ): Boolean {
        val ring = isWakingCall(env)
        // A call that does not ring is not a call (founder rule): a waking
        // signal bound for an island that predates `ring` goes out under the
        // legacy, more telling type "call", the only thing such an island rings
        // a closed app on. `ring` stays set in both forms; it is harmless to an
        // old island and exact on a new one. Non-waking signals never ask and
        // never change type, but they do WAIT while a probe of this host is in
        // flight: the offer is what the probe delays, and an ICE batch that
        // slipped past it landed in the callee's queue before the call it
        // belongs to, which the callee drops. Nothing goes to a host mid-probe.
        val envelopeType = if (ring) {
            if (peerHonoursRing(contact.host)) "message" else "call"
        } else {
            ringProbeLocks[contact.host]?.let { synchronized(it) {} }
            "message"
        }
        return depositToPrimary(
            contact.host, contact.uin, contact.identityKey, env, ownUin, signingPriv, signingPub, ownHost,
            envelopeType = envelopeType,
            ring = ring,
        )
    }

    /** Per-host memo for [peerHonoursRing]: answer + the elapsedRealtime it
     *  expires at. A true answer is kept long (an island does not un-learn
     *  `ring`); anything else short, so an island that upgrades is picked up
     *  and a transient failure is not remembered as "old" for an hour. */
    private val ringCapable = HashMap<String, Pair<Boolean, Long>>()
    private val ringProbeLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private const val RING_TRUE_TTL_MS = 60 * 60 * 1000L
    private const val RING_OTHER_TTL_MS = 10 * 60 * 1000L
    private const val RING_PROBE_TIMEOUT_S = 5L

    /**
     * Does the island at [host] honour the `ring` flag of a sealed deposit?
     * Read from its open `/server/info`, `capabilities.envelope_class`, which
     * was born together with `ring` (server 2026.08.22.15). Only `true` counts:
     * false, absent, a non-200, a failed or timed-out fetch and an unparseable
     * body all mean "treat as old", because the cost of a wrong "new" is a
     * call that stays silent while the cost of a wrong "old" is one legible
     * "call" row on an island that could have done better.
     *
     * Sits on the press-to-ringback path, so the fetch gives up after
     * [RING_PROBE_TIMEOUT_S] and the answer is memoised per host; the other
     * signals of the same call hit the memo. Goes through [viaBestRoute] like
     * the deposit itself, so an island that is blocked here but reachable
     * through the tunnel is not mistaken for an old one just because a direct
     * fetch failed. ⚠ A TIMEOUT is not a blocked route: this deadline is
     * tighter than the deposit's, so a mere slow answer must not engage the
     * tunnel and pin the host to it for the rest of the process. It is caught
     * inside the route and simply reads as "cannot tell quickly", i.e. false;
     * a hard connection failure still takes the deposit's own tunnel path.
     * One fetch per host at a time: concurrent callers for the same host wait
     * on its lock and then read the memo.
     */
    private fun peerHonoursRing(host: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(ringCapable) {
            ringCapable[host]?.let { (ok, until) -> if (now < until) return ok }
        }
        synchronized(ringProbeLocks.computeIfAbsent(host) { Any() }) {
            synchronized(ringCapable) {
                ringCapable[host]?.let { (ok, until) -> if (now < until) return ok }
            }
            val ok = runCatching {
                val req = Request.Builder().url("https://$host/server/info").get().build()
                val probe = viaBestRoute(host) { c ->
                    try {
                        // newBuilder shares the pool and dispatcher; only the
                        // overall deadline is tightened for this one call.
                        c.newBuilder().callTimeout(RING_PROBE_TIMEOUT_S, TimeUnit.SECONDS).build()
                            .newCall(req).execute()
                    } catch (e: java.io.InterruptedIOException) {
                        // callTimeout and the socket timeouts both surface as
                        // this. Swallowed HERE, inside the route, so
                        // viaBestRoute's IOException path (engage the tunnel,
                        // remember the host as blocked) is not taken for a
                        // slow answer; any other IOException still propagates.
                        null
                    }
                } ?: return@runCatching false
                probe.use { resp ->
                    if (!resp.isSuccessful) return@runCatching false
                    val o = JsonParser.parseString(resp.body?.string() ?: return@runCatching false).asJsonObject
                    o.getAsJsonObject("capabilities")?.get("envelope_class")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                        ?.asBoolean == true
                }
            }.getOrDefault(false)
            val ttl = if (ok) RING_TRUE_TTL_MS else RING_OTHER_TTL_MS
            synchronized(ringCapable) {
                ringCapable[host] = ok to (android.os.SystemClock.elapsedRealtime() + ttl)
            }
            return ok
        }
    }

    /** Warm the [peerHonoursRing] memo for [host] ahead of an outgoing call,
     *  off the offer's path: the caller fires this when the call starts, next
     *  to the TURN refresh, so by the time the offer is built the answer is
     *  already in memory and the deposit pays no extra round trip. A cache hit
     *  returns at once; a failure is memoised as "old" for a short while, the
     *  same answer the deposit itself would have reached. Nothing propagates. */
    fun prewarmRing(host: String) {
        runCatching { peerHonoursRing(host) }
    }

    /**
     * §5d: does this call signal have to RING a device that holds no live
     * socket? Stage 2 (core-metadata plan): a call deposit to an island that
     * knows `ring` rides `envelope_type "message"`, and a waking signal adds
     * `ring:true` so the recipient's island fires the same VoIP + UnifiedPush
     * pair a same-island `call_offer` does, instead of the ordinary message
     * banner. Before Stage 2 this was carried by the more telling type
     * `"call"`; `ring` asks for the exact same wake while the island stores
     * the quieter `"message"`. An island that predates `ring` still gets the
     * legacy `"call"` type for a waking signal, see [peerHonoursRing]; the
     * two signals named below are the only ones that ever do.
     *
     * Only the two signals that must reach a CLOSED app ring: the OFFER, which
     * is the call, and the END, which takes a ring down when the caller gives up
     * before pickup. `call_answer` / `call_ice` / `call_renegotiate*` only ever
     * matter to an app already up and holding this call, so they deposit as a
     * plain `"message"` with no ring.
     *
     * ⚠ WHAT `ring` TELLS THE RECIPIENT'S ISLAND: that a call is arriving for
     * this user, at this instant. Nothing else: who is calling, on which
     * island, the call id, audio vs video and the SDP all stay inside the sealed
     * envelope, which that island cannot open. Founder decision 2026-08-15, made
     * because a censor can already infer a call from packet timing and size.
     */
    private fun isWakingCall(env: Envelope): Boolean {
        val sig = (env as? Envelope.CallSignal)?.sig
        return sig == "call_offer" || sig == "call_end"
    }

    /** §5f cross-island contact requests: same one-hop deposit as [deliverCall]
     *  (primary island, `envelope_type: "message"`, v=1-sealed to the identity
     *  key from the peer's open card), addressed by a RAW card key rather than a
     *  stored contact — a `request` may go out before any local row exists and a
     *  `decline` after the row is gone. Zero server changes. */
    fun deliverContactRequest(
        host: String,
        uin: Int,
        identityKeyB64: String,
        env: Envelope,
        ownUin: Int,
        signingPriv: ByteArray,
        signingPub: ByteArray,
        ownHost: String,
    ): Boolean = depositToPrimary(host, uin, identityKeyB64, env, ownUin, signingPriv, signingPub, ownHost)

    /** §5e cross-island profile refresh: same one-hop deposit as
     *  [deliverContactRequest] (primary island, `envelope_type: "message"`,
     *  v=1-sealed to the identity key the peer's open card pinned at add time),
     *  addressed by a STORED contact — the audience is exactly the accepted
     *  cross-island contacts, by construction. The picture's blob must already
     *  have been put on [contact]'s island with [depositBlob] before this call:
     *  the recipient fetches media from their OWN island and islands never talk
     *  to each other. Zero server changes. */
    fun deliverProfile(
        contact: CrossIslandStore.Contact,
        env: Envelope,
        ownUin: Int,
        signingPriv: ByteArray,
        signingPub: ByteArray,
        ownHost: String,
    ): Boolean = depositToPrimary(contact.host, contact.uin, contact.identityKey, env, ownUin, signingPriv, signingPub, ownHost)

    /** One sealed envelope, one deposit, PRIMARY island only. No backup-home
     *  copies: those mailboxes are polled (~30s), and both callers are
     *  interactive (call signalling, a contact request the sender is waiting on).
     *
     *  [envelopeType] is the OUTER type the island routes on and is
     *  `"message"` in every case but one; a §5d call that must wake a closed app
     *  sets [ring] instead of a louder type (see [isWakingCall]), and only an
     *  island too old to know [ring] still gets the legacy `"call"` type (see
     *  [peerHonoursRing]). The INNER envelope is unchanged in every case;
     *  nothing else about the wire moves. */
    private fun depositToPrimary(
        host: String,
        uin: Int,
        identityKeyB64: String,
        env: Envelope,
        ownUin: Int,
        signingPriv: ByteArray,
        signingPub: ByteArray,
        ownHost: String,
        envelopeType: String = "message",
        ring: Boolean = false,
    ): Boolean {
        val recipientPub = Base64.decode(identityKeyB64, Base64.NO_WRAP)
        val payload = SealedSender.encryptV1(env, recipientPub, ownUin, signingPriv, signingPub, ownHost)
        val body = JsonObject().apply {
            addProperty("to_uin", uin)
            addProperty("envelope_type", envelopeType)
            // Stage 2: the retention / push class beside the legacy type, and
            // the ring flag for a call wake. An old island ignores both.
            addProperty("cls", SealedSender.messageClass(envelopeType))
            if (ring) addProperty("ring", true)
            addProperty("payload", payload)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("https://$host/messages/sealed").post(body).build()
        return runCatching { viaBestRoute(host) { c -> c.newCall(req).execute() }.use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Deliver [env] to a cross-island [contact]: v=1-seal to their identity key
     *  and deposit to each resolved home. Returns true if any home accepted it. */
    fun deliver(
        contact: CrossIslandStore.Contact,
        env: Envelope,
        ownUin: Int,
        signingPriv: ByteArray,
        signingPub: ByteArray,
        ownHost: String,
    ): Boolean {
        val recipientPub = Base64.decode(contact.identityKey, Base64.NO_WRAP)
        val payload = SealedSender.encryptV1(env, recipientPub, ownUin, signingPriv, signingPub, ownHost)
        var delivered = false
        // Gossip-aware home resolution anchored to the LOCALLY-pinned signing
        // key (not a live card fetch), so the send reaches the peer via our
        // gossip mirror even when their own island is blocked or dead. Floor to
        // the single address we have when nothing verifies anywhere.
        val homes = Multihome.resolveAndMirrorHomes(ownHost, contact.host, contact.uin, contact.signingKey)
            .ifEmpty { listOf(RcqFederation.Home(contact.host, contact.uin)) }
        for (h in homes) {
            // Per-home best route: a home whose island is blocked for this
            // network gets a second attempt through the tunnel instead of
            // silently failing while the other homes succeed.
            runCatching {
                viaBestRoute(h.host) { client ->
                    val body = JsonObject().apply {
                        addProperty("to_uin", h.uin)
                        addProperty("envelope_type", "message")
                        // Stage 2: retention / push class beside the legacy type.
                        addProperty("cls", SealedSender.messageClass("message"))
                        addProperty("payload", payload)
                        // F3 deposit-auth: attach an anonymous blinded token when the
                        // recipient island offers it, so our cross-island deposit isn't
                        // throttled by the blunt per-IP cap (and survives a future
                        // require-token flip). Best-effort — null = the legacy path.
                        DepositAuthStore.tokenFor(h.host, client)?.let { add("deposit_token", it) }
                    }.toString().toRequestBody(JSON)
                    val req = Request.Builder().url("https://${h.host}/messages/sealed").post(body).build()
                    client.newCall(req).execute().use { if (it.isSuccessful) delivered = true }
                }
            }
        }
        return delivered
    }
}

/** A blob written to the socket in 64 KB slices so the caller can watch it go.
 *
 *  ⚠ Deliberately a local copy of what `RcqApi` does for own-island uploads
 *  rather than a shared class: this file talks to OTHER islands and has no
 *  business reaching into the api client's internals. The rule it exists for
 *  is the same one (#537): handed a byte array, OkHttp writes it in one or two
 *  goes and a progress callback fires twice, which is a bar that jumps from
 *  nothing to done instead of a line that moves.
 */
private class ChunkedBlobBody(
    private val bytes: ByteArray,
    private val type: okhttp3.MediaType,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : okhttp3.RequestBody() {
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
