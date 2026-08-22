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
    fun depositBlob(host: String, mediaId: String, blob: ByteArray): Boolean {
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("blob", "photo.bin", blob.toRequestBody(OCTET))
            .build()
        val req = Request.Builder().url("https://$host/media/$mediaId").put(body).build()
        return runCatching { viaBestRoute(host) { c -> c.newCall(req).execute() }.use { it.isSuccessful } }.getOrDefault(false)
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
    ): Boolean = depositToPrimary(
        contact.host, contact.uin, contact.identityKey, env, ownUin, signingPriv, signingPub, ownHost,
        envelopeType = "message",
        ring = isWakingCall(env),
    )

    /**
     * §5d: does this call signal have to RING a device that holds no live
     * socket? Stage 2 (core-metadata plan): every call deposit rides
     * `envelope_type "message"`, and a waking signal adds `ring:true` so the
     * recipient's island fires the same VoIP + UnifiedPush pair a same-island
     * `call_offer` does, instead of the ordinary message banner. Before Stage 2
     * this was carried by the more telling type `"call"`; `ring` asks for the
     * exact same wake while the island stores the quieter `"message"`.
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
     *  [envelopeType] is the OUTER type the island routes on and stays
     *  `"message"` in every case now; a §5d call that must wake a closed app
     *  sets [ring] instead of a louder type; see [isWakingCall]. The INNER
     *  envelope is unchanged in every case; nothing else about the wire moves. */
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
