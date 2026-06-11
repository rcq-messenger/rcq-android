package app.rcq.android.net

import android.util.Base64
import app.rcq.android.crypto.Envelope
import app.rcq.android.crypto.RecoveryPhrase
import app.rcq.android.crypto.SealedSender
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Multihoming (federation v1) — the mechanism. Mirrors web-chat's
 * multihome.ts, which is verified end-to-end against a real second island.
 *
 *   • [addBackupIsland]: register this identity (same X25519+Ed25519 keys) on
 *     another island — recover-first so a re-add finds the existing per-island
 *     uin instead of minting a second one.
 *   • [publishToBackups]: PUT the signed home-island record to each backup
 *     (the primary PUT rides the session's authed api).
 *   • [drainBackupQueues]: poll each backup mailbox; rows feed the same ingest
 *     path as the primary queue — the INSERT-OR-IGNORE envelope-uuid dedup
 *     collapses copies the primary already delivered.
 *   • [depositToExtraHomes]: v=1-seal once, deposit to each of a peer's homes
 *     OTHER than our own island. No-op for single-homed peers (today's
 *     universal case — the flagship send path stays byte-identical).
 *
 * v=1 only on backups (a v=2 session needs the auth-gated prekey bundle, which
 * stays on the primary). Blocking I/O — call from Dispatchers.IO.
 */
object Multihome {

    /** How long a resolved peer-homes entry stays fresh. Stale entries are
     *  still used when the record can't be re-fetched — that staleness IS the
     *  failover path when the primary island is down. */
    private const val PEER_CACHE_TTL_MS = 10 * 60 * 1000L

    private val JSON = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Re-authenticate on [host] by proving possession of the signing key
     *  (same challenge-response as seed-phrase recovery). Returns null when
     *  this identity never registered there; throws on network errors. */
    suspend fun recoverOn(host: String, signingPriv: ByteArray, signingPub: ByteArray): RcqApi.RegisterResponse? {
        val api = RcqApi("https://$host")
        val skB64 = Base64.encodeToString(signingPub, Base64.NO_WRAP)
        val challenge = api.recoverChallenge(skB64).challenge
        val signature = RecoveryPhrase.signChallenge(signingPriv, challenge)
        return try {
            api.recover(RcqApi.RecoverRequest(skB64, challenge, signature))
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP 404") == true) null else throw e
        }
    }

    /** Register (or recover) this identity on [hostInput] as a backup home and
     *  persist it. Throws with a short reason on failure; the caller should
     *  republish the home-island record afterwards. */
    suspend fun addBackupIsland(
        ownUin: Int,
        ownHost: String,
        hostInput: String,
        identityPub: ByteArray,
        signingPriv: ByteArray,
        signingPub: ByteArray,
        nickname: String,
        auto: Boolean = false,
    ): MultihomeStore.Home {
        val host = normalizeHost(hostInput) ?: throw IllegalArgumentException("invalid_host")
        if (host == ownHost) throw IllegalArgumentException("primary_island")
        if (MultihomeStore.list(ownUin).any { it.host == host }) throw IllegalArgumentException("already_added")

        val creds = recoverOn(host, signingPriv, signingPub)
            ?: RcqApi("https://$host").register(
                RcqApi.RegisterRequest(
                    nickname = nickname,
                    identity_key = Base64.encodeToString(identityPub, Base64.NO_WRAP),
                    signing_key = Base64.encodeToString(signingPub, Base64.NO_WRAP),
                ),
            )
        val home = MultihomeStore.Home(ownUin, host, creds.uin, creds.token, System.currentTimeMillis(), auto)
        MultihomeStore.save(home)
        return home
    }

    // The auto-pick list is a SEPARATE, Ed25519-signed file (not servers.json):
    // the toggle silently registers a backup mailbox on whatever it picks, so a
    // tampered catalogue must not steer that. We verify the signature over the
    // EXACT bytes GitHub served, against the maintainer key already pinned for
    // relay-config (RelayConfigStore.PUBKEY_B64). servers.json stays display-only.
    private const val AUTO_ISLANDS_URL =
        "https://raw.githubusercontent.com/rcq-messenger/rcq-servers/main/auto-islands.json"
    private const val PUBKEY_B64 = "TY834OFcBvtUqHcnVw/QrPBOaEAZo7a1GAmABMhjkT8="

    /** Pick a backup island from the SIGNED island list, minus our own island +
     *  already-added hosts; the FIRST healthy one in list order wins (the order
     *  is the project's preference). Returns the bare host, or null when the
     *  list is unreachable, the signature fails, or no island responds
     *  (fail-safe: never auto-register on an unverified island). Plain OkHttp,
     *  same accepted simplification as the deposit path. Blocking — call from IO. */
    fun autoPickHost(ownHost: String, exclude: Set<String>): String? = runCatching {
        val jsonBytes = httpBytes(AUTO_ISLANDS_URL) ?: return@runCatching null
        val sigB64 = httpBytes("$AUTO_ISLANDS_URL.sig")?.toString(Charsets.UTF_8)?.trim()
            ?: return@runCatching null
        val pub = Ed25519PublicKeyParameters(Base64.decode(PUBKEY_B64, Base64.DEFAULT), 0)
        val signer = Ed25519Signer().apply { init(false, pub); update(jsonBytes, 0, jsonBytes.size) }
        if (!signer.verifySignature(Base64.decode(sigB64, Base64.DEFAULT))) return@runCatching null
        val doc = JsonParser.parseString(jsonBytes.toString(Charsets.UTF_8)).asJsonObject
        val islands = doc.getAsJsonArray("islands")?.mapNotNull { normalizeHost(it.asString) } ?: emptyList()
        islands.firstOrNull { it != ownHost && it !in exclude && healthy(it) }
    }.getOrNull()

    /** Raw response bytes (the exact bytes the signature covers), or null. */
    private fun httpBytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url).header("Cache-Control", "no-cache").get().build()
        client.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    }.getOrNull()

    private fun healthy(host: String): Boolean = runCatching {
        val req = Request.Builder().url("https://$host/health").get().build()
        client.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** `is2.rcq.app`, `https://is2.rcq.app/x` → `is2.rcq.app`. */
    fun normalizeHost(input: String): String? = runCatching {
        val t = input.trim().lowercase()
        if (t.isEmpty()) return null
        java.net.URI(if (t.contains("://")) t else "https://$t").host
    }.getOrNull()

    /** PUT the signed record to every backup home. Best-effort per island; a
     *  401 refreshes the token via recover once. 409 (stale ts) is fine — an
     *  equal-or-newer record is already there. */
    suspend fun publishToBackups(ownUin: Int, signingPriv: ByteArray, signingPub: ByteArray, docJson: String) {
        for (home in MultihomeStore.list(ownUin)) {
            runCatching {
                val api = RcqApi("https://${home.host}")
                api.setToken(home.jwt)
                try {
                    api.publishIslandRecord(docJson)
                } catch (e: IOException) {
                    if (e.message?.startsWith("HTTP 401") == true) {
                        val fresh = recoverOn(home.host, signingPriv, signingPub) ?: return@runCatching
                        MultihomeStore.updateCreds(ownUin, home.host, fresh.uin, fresh.token)
                        api.setToken(fresh.token)
                        api.publishIslandRecord(docJson)
                    } else if (e.message?.startsWith("HTTP 409") != true) throw e
                }
            }.onFailure {
                android.util.Log.w("RCQfed", "multihome publish ${home.host}: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /** Drain every backup mailbox, feeding each payload to [onPayload] (the
     *  session's ingest — dedup happens there). A 401 refreshes the token via
     *  recover and retries once. Never throws. */
    suspend fun drainBackupQueues(
        ownUin: Int,
        signingPriv: ByteArray,
        signingPub: ByteArray,
        onPayload: (String) -> Unit,
    ) {
        for (home in MultihomeStore.list(ownUin)) {
            runCatching {
                val api = RcqApi("https://${home.host}")
                api.setToken(home.jwt)
                val rows = try {
                    api.drainQueue()
                } catch (e: IOException) {
                    if (e.message?.startsWith("HTTP 401") == true) {
                        val fresh = recoverOn(home.host, signingPriv, signingPub) ?: return@runCatching
                        MultihomeStore.updateCreds(ownUin, home.host, fresh.uin, fresh.token)
                        api.setToken(fresh.token)
                        api.drainQueue()
                    } else throw e
                }
                rows.forEach { q -> q.payload?.let(onPayload) }
            }.onFailure {
                android.util.Log.w("RCQfed", "multihome drain ${home.host}: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /** Resolve a flagship peer's home list from OUR island's open record
     *  endpoint, verified against the peer's locally-pinned Ed25519 signing
     *  key. Cached with a TTL; a fetch failure serves the stale cache. */
    private fun resolvePeerHomesCached(ownHost: String, peerUin: Int, peerSigningKeyB64: String): List<RcqFederation.Home> {
        val cached = MultihomeStore.cachedPeerHomes(peerUin)
        if (cached != null && System.currentTimeMillis() - cached.ts < PEER_CACHE_TTL_MS) return cached.homes
        return try {
            val req = Request.Builder().url("https://$ownHost/federation/island-record/$peerUin").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    MultihomeStore.cachePeerHomes(peerUin, emptyList())
                    return emptyList()
                }
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val doc = JsonParser.parseString(resp.body?.string() ?: "").asJsonObject
                val v = RcqFederation.verifyRecord(doc, expectedIk = null, expectedSk = peerSigningKeyB64)
                val homes = if (v is RcqFederation.VerifyResult.Ok) {
                    v.doc.getAsJsonArray("homes").map {
                        val h = it.asJsonObject
                        RcqFederation.Home(h.get("host").asString, h.get("uin").asInt)
                    }
                } else emptyList()
                MultihomeStore.cachePeerHomes(peerUin, homes)
                homes
            }
        } catch (e: Exception) {
            cached?.homes ?: emptyList()
        }
    }

    /** Deposit a v=1 sealed copy of [env] into each of the peer's homes OTHER
     *  than our own island. Seal once — v=1 binds only the identity key, which
     *  is identical on every island. Returns how many homes accepted the copy;
     *  0 for single-homed peers. Never throws. */
    fun depositToExtraHomes(
        ownHost: String,
        ownUin: Int,
        peerUin: Int,
        peerIdentityKeyB64: String?,
        peerSigningKeyB64: String?,
        env: Envelope,
        signingPriv: ByteArray,
        signingPub: ByteArray,
    ): Int {
        return try {
            if (peerIdentityKeyB64.isNullOrEmpty() || peerSigningKeyB64.isNullOrEmpty()) return 0
            val extra = resolvePeerHomesCached(ownHost, peerUin, peerSigningKeyB64).filter { it.host != ownHost }
            if (extra.isEmpty()) return 0

            val recipientPub = Base64.decode(peerIdentityKeyB64, Base64.NO_WRAP)
            val payload = SealedSender.encryptV1(env, recipientPub, ownUin, signingPriv, signingPub, ownHost)
            var delivered = 0
            for (h in extra) {
                val body = JsonObject().apply {
                    addProperty("to_uin", h.uin)
                    addProperty("envelope_type", "message")
                    addProperty("payload", payload)
                }.toString().toRequestBody(JSON)
                val req = Request.Builder().url("https://${h.host}/messages/sealed").post(body).build()
                runCatching { client.newCall(req).execute().use { if (it.isSuccessful) delivered++ } }
            }
            delivered
        } catch (e: Exception) {
            0
        }
    }
}
