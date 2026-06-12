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
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

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
        client.newCall(req).execute().use { resp ->
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

    /** §5c cross-island group add: resolve the local uin bound to [signingKeyB64]
     *  on [host], or null when no account there has that key yet. Open inverse
     *  map of the key card; lets an owner-initiated add reuse an existing
     *  account instead of minting a duplicate. */
    fun resolveUinForKey(host: String, signingKeyB64: String): Int? {
        val url = "https://$host/federation/uin-for-key?signing_key=" +
            java.net.URLEncoder.encode(signingKeyB64, "UTF-8")
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
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
        client.newCall(req).execute().use { resp ->
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
        client.newCall(req).execute().use { resp ->
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
        return runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
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
        val recipientPub = Base64.decode(contact.identityKey, Base64.NO_WRAP)
        val payload = SealedSender.encryptV1(env, recipientPub, ownUin, signingPriv, signingPub, ownHost)
        val body = JsonObject().apply {
            addProperty("to_uin", contact.uin)
            addProperty("envelope_type", "message")
            addProperty("payload", payload)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("https://${contact.host}/messages/sealed").post(body).build()
        return runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
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
            val body = JsonObject().apply {
                addProperty("to_uin", h.uin)
                addProperty("envelope_type", "message")
                addProperty("payload", payload)
            }.toString().toRequestBody(JSON)
            val req = Request.Builder().url("https://${h.host}/messages/sealed").post(body).build()
            runCatching { client.newCall(req).execute().use { if (it.isSuccessful) delivered = true } }
        }
        return delivered
    }
}
