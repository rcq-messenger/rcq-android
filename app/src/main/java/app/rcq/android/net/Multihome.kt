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
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        // Backup homes and peers' extra homes may be fingerprint islands
        // (design §6); the proxied twin inherits this via newBuilder().
        .islandTrust()
        .build()
    @Volatile private var proxiedClient: OkHttpClient? = null

    /** Like RcqApi, ride the sing-box SOCKS proxy when the obfuscated/onion
     *  transport is engaged so cross-island gossip/record/media calls work on a
     *  censored network (and don't leak the foreign host + our IP outside the
     *  tunnel). Direct (unchanged) when the transport is off. The RcqApi-based
     *  paths here are already proxy-aware; this covers the raw-client calls. */
    private fun http(): OkHttpClient {
        // Called immediately before every raw cross-island call here (backup
        // homes, guest registrations, the signed home-island record). All of it
        // proves possession of the REAL signing key. See [DuressGate].
        app.rcq.android.security.DuressGate.check()
        val p = SingBoxTransport.proxy() ?: return baseClient
        return proxiedClient ?: baseClient.newBuilder().proxy(p).build().also { proxiedClient = it }
    }

    /** Re-authenticate on [host] by proving possession of the signing key
     *  (same challenge-response as seed-phrase recovery). Returns null when
     *  this identity never registered there; throws on network errors. */
    suspend fun recoverOn(host: String, signingPriv: ByteArray, signingPub: ByteArray): RcqApi.RegisterResponse? {
        val api = RcqApi("https://$host")
        val skB64 = Base64.encodeToString(signingPub, Base64.NO_WRAP)
        // ⚠ The challenge call belongs INSIDE the same failure handling as the
        // recover call. It used to sit outside, so a 404 from an island that
        // does not know the endpoint arrived as a raw IOException while the
        // very same 404 from `recover` meant "no account here" — two names for
        // one answer, and the caller could not tell them apart (#687).
        val challenge = try {
            api.recoverChallenge(skB64).challenge
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP 404") == true) return null else throw e
        }
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
        // The front is the flagship by another road: "adding" it registers a
        // second mailbox on the island this account already lives on. Same
        // refusal as the primary, because that is what it is.
        if (host == ownHost || RelayConfigStore.isFrontHost(host)) throw IllegalArgumentException("primary_island")
        if (MultihomeStore.list(ownUin).any { it.host == host }) throw IllegalArgumentException("already_added")

        val creds = recoverOn(host, signingPriv, signingPub) ?: run {
            val api = RcqApi("https://$host")
            val skB64 = Base64.encodeToString(signingPub, Base64.NO_WRAP)
            // ⚠ The number is only handed out under proof now. Without the
            // signature the island still registers us, but on a fresh number,
            // and "one number everywhere" quietly stops being true. An island
            // too old to know the endpoint 404s and we register as before.
            val challenge = runCatching { api.registerChallenge(skB64).challenge }.getOrNull()
            api.register(
                RcqApi.RegisterRequest(
                    nickname = nickname,
                    identity_key = Base64.encodeToString(identityPub, Base64.NO_WRAP),
                    signing_key = skB64,
                    // Ask to keep our primary number on this backup island
                    // (best-effort; the server mints a fresh uin if it's taken).
                    desired_uin = ownUin,
                    challenge = challenge,
                    signature = challenge?.let {
                        app.rcq.android.crypto.RecoveryPhrase.signChallenge(signingPriv, it)
                    },
                ),
            )
        }
        val home = MultihomeStore.Home(ownUin, host, creds.uin, creds.token, System.currentTimeMillis(), auto)
        MultihomeStore.save(home)
        return home
    }

    // The auto-pick list is a SEPARATE, Ed25519-signed file (not servers.json):
    // the toggle silently registers a backup mailbox on whatever it picks, so a
    // tampered catalogue must not steer that. We verify the signature over the
    // EXACT bytes GitHub served, against the keys accepted for the ISLAND_LIST
    // role ([SigningKeys]) — its own role, because steering a backup mailbox and
    // steering a tunnel are different powers and should not stay welded to one
    // key. servers.json stays display-only.
    // ⚠ Two sources, ours first. GitHub raw is blocked in a good share of the
    // networks this project exists for, so the one feature whose purpose is
    // "your island may go away, keep a spare" failed for exactly the people who
    // need a spare (report #579). The mirror on rcq.app is reachable wherever
    // the app is. It grants us nothing: the bytes are verified against the
    // pinned ISLAND_LIST key either way, so a mirror that lies simply fails
    // verification and the next source is tried.
    private val AUTO_ISLANDS_URLS = listOf(
        "https://rcq.app/auto-islands.json",
        "https://raw.githubusercontent.com/rcq-messenger/rcq-servers/main/auto-islands.json",
    )

    /** The verified island list from whichever source answers first, or null
     *  when none of them does. A static host that answers a missing file with
     *  its index page fails the signature check, which is what makes trying the
     *  next source safe. */
    private fun signedIslands(): List<String>? {
        for (url in AUTO_ISLANDS_URLS) {
            val islands = runCatching {
                val jsonBytes = httpBytes(url) ?: return@runCatching null
                val sigB64 = httpBytes("$url.sig")?.toString(Charsets.UTF_8)?.trim()
                    ?: return@runCatching null
                if (!SigningKeys.verify(SigningKeys.Role.ISLAND_LIST, jsonBytes, sigB64)) {
                    return@runCatching null
                }
                val doc = JsonParser.parseString(jsonBytes.toString(Charsets.UTF_8)).asJsonObject
                doc.getAsJsonArray("islands")?.mapNotNull { normalizeHost(it.asString) } ?: emptyList()
            }.getOrNull()
            if (islands != null) return islands
        }
        return null
    }

    /** Pick a backup island from the SIGNED island list, minus our own island +
     *  already-added hosts; the FIRST healthy one in list order wins (the order
     *  is the project's preference). Returns the bare host, or null when the
     *  list is unreachable, the signature fails, or no island responds
     *  (fail-safe: never auto-register on an unverified island). Plain OkHttp,
     *  same accepted simplification as the deposit path. Blocking — call from IO. */
    fun autoPickHost(ownHost: String, exclude: Set<String>): String? = runCatching {
        val direct = pickPass(ownHost, exclude)
        if (direct != null) return@runCatching direct
        // ⚠ ONE WHOLE PASS, NOT ONE PROBE, is what says the network is the
        // problem. [http] alone never brings the tunnel up, so on a censored
        // network the catalogue is unreachable, no island answers and the one
        // feature whose purpose is "your island may go away, keep a spare"
        // fails for exactly the people who need a spare (report #726). But a
        // health probe is a call that is EXPECTED to fail: an island down for
        // maintenance is not censorship, and engaging on a single IOException
        // would move every later request in the process onto the relays
        // because ONE island in the catalogue was offline, on a network that
        // never blocked anything. Nothing at all answering is a different
        // statement, and the only one worth a tunnel for. The user asked for
        // this pass out loud by tapping the toggle.
        if (SingBoxTransport.proxy() != null) return@runCatching null
        if (!SingBoxTransport.engageForBlockedDestination("multihome:auto-pick")) return@runCatching null
        pickPass(ownHost, exclude)
    }.getOrNull()

    /** One catalogue fetch plus one probe of each candidate, over whatever
     *  route is up right now. */
    private fun pickPass(ownHost: String, exclude: Set<String>): String? {
        val islands = signedIslands() ?: return null
        return islands.firstOrNull { it != ownHost && it !in exclude && !RelayConfigStore.isFrontHost(it) && healthy(it) }
    }

    /** Raw response bytes (the exact bytes the signature covers), or null. */
    private fun httpBytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url).header("Cache-Control", "no-cache").get().build()
        http().newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    }.getOrNull()

    private fun healthy(host: String): Boolean = runCatching {
        val req = Request.Builder().url("https://$host/health").get().build()
        http().newCall(req).execute().use { it.isSuccessful }
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
            // A phantom front row PUTs to our own island, which the session's
            // authed api already covered (and the island rejects any record
            // naming its front anyway).
            if (RelayConfigStore.isFrontHost(home.host)) continue
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

    /** Advance a secondary island's drain cursor past the rows we just ingested.
     *
     *  `drainQueue()` asks with `?ack=1`, which means the island KEEPS every row
     *  until the device confirms it — and these two drains never confirmed. The
     *  same envelopes therefore came back on every pass of the 30-second loop
     *  for as long as the mailbox lived. The uuid dedup hid it while the local
     *  copy existed, but once that copy was gone (a disappearing message
     *  expired, the thread was cleared) each pass re-inserted the message and
     *  played the receive tone again — "о-оу" every half minute for one old
     *  message. Best effort: a lost ack just means one more redelivery.
     *
     *  [deviceId] must be the one the drain asked with: the island computes the
     *  acked prefix over the rows it served THAT device, and a mismatch wedges
     *  the cursor at the first row it thinks we skipped. */
    private suspend fun ack(api: RcqApi, rows: List<RcqApi.QueuedEnvelope>, deviceId: Int) {
        val direct = rows.filter { it.group_id == null }.map { it.id }
        val group = rows.filter { it.group_id != null }.map { it.id }
        runCatching { api.ackQueue(direct, group, deviceId) }
    }

    // ── Stage 5 on a foreign mailbox: the room log of a backup or visited island ──

    /** Per host: does it keep one log per room (`capabilities.group_log`),
     *  and when we last asked. In memory: a foreign island is asked on the
     *  first drain of a process and then once per [CAPS_TTL_MS]; an island
     *  that does not answer is treated as one without a log until it does,
     *  which is exactly the legacy path it is drained by anyway. */
    private val groupLogCaps = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private const val CAPS_TTL_MS = 6 * 60 * 60 * 1000L

    /** The strike count of a foreign log row that would not ingest, same
     *  rule as the primary drain ([GroupLogPage.strike]). In memory here; the
     *  30-second loop makes the three drains a matter of two minutes. */
    private val logRowFails = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun advertisesGroupLog(api: RcqApi, host: String): Boolean {
        val now = System.currentTimeMillis()
        groupLogCaps[host]?.let { (yes, at) -> if (now - at < CAPS_TTL_MS) return yes }
        val yes = runCatching { api.serverInfo().capabilities.group_log }.getOrNull() ?: return false
        groupLogCaps[host] = yes to now
        return yes
    }

    /** The same drain [Session.drainGroupLog] runs on the primary, for a
     *  mailbox we hold on another island, right after that island's legacy
     *  queue was drained (never beside it). Feature-detected per island: the
     *  rooms a backup or visited island hosts for us live THERE, and only an
     *  island that advertises the log keeps one. The rows are handed to
     *  [onRow] exactly as the legacy group rows of the same mailbox are, and
     *  filed the same way; its answer says whether the row is done with
     *  (null) or failed in a way that may pass next time, in which case the
     *  room's ack stops short of it for a few drains, then writes it off. An
     *  ack that does not land ends the drain. Never throws. */
    private suspend fun drainGroupLog(
        api: RcqApi,
        host: String,
        /** False once the caller's account has been switched out from under it.
         *
         *  ⚠⚠ This has to STOP THE LOOP, and it cannot be expressed by [onRow]
         *  answering null: null means the row is DONE WITH, which advances the
         *  room's ack. The cursor only ever moves forward and [fetchGroupLog]
         *  cannot be asked to start from a given seq, so an ack over rows that
         *  were never filed loses them for good, in silence. */
        stillOurs: () -> Boolean = { true },
        onRow: (payload: String, groupId: Int, host: String) -> String?,
    ) {
        runCatching {
            var pages = 0
            while (true) {
                if (!stillOurs()) return
                val page = api.fetchGroupLog()
                if (!stillOurs()) return
                val acks = GroupLogPage.Acks()
                page.rows.forEach { r ->
                    // ⚠⚠ BETWEEN rows, not just around the page. A page is up
                    // to fifty rows and each one is a libsignal decrypt, so the
                    // switch usually lands INSIDE this loop. The handler writes
                    // to per-account singletons before it even gets to the
                    // decrypt (the room alias is computed as its argument), so
                    // rows fed to it after the switch put one account's foreign
                    // rooms in the other account's prefs, where they stay.
                    // `forEach` is inline, so this returns out of drainGroupLog
                    // itself - which is the point: it also skips the ack below,
                    // leaving every unread row on the island.
                    if (!stillOurs()) return
                    val payload = r.payload
                    val why = if (payload == null) null else onRow(payload, r.gid, host)
                    val key = "$host:${r.gid}:${r.seq}"
                    val done = if (why == null) {
                        logRowFails.remove(key)
                        true
                    } else {
                        val strike = GroupLogPage.strike(logRowFails[key], why)
                        if (strike.writtenOff) {
                            logRowFails.remove(key)
                            android.util.Log.w("RCQfed", "log row $key written off after ${strike.count} drains ($why)")
                        } else {
                            logRowFails[key] = GroupLogPage.encode(strike)
                        }
                        strike.writtenOff
                    }
                    acks.row(r.gid, r.seq, done)
                }
                // The last gate before the cursor moves. Everything above this
                // line is recoverable; an ack is not.
                if (!stillOurs()) return
                if (acks.upto.isNotEmpty() && runCatching { api.ackGroupLog(acks.upto) }.isFailure) break
                pages++
                if (!page.more || acks.blocked.isNotEmpty() || page.rows.isEmpty() || pages >= 40) break
            }
        }.onFailure {
            android.util.Log.w("RCQfed", "group log drain $host: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** Drain every backup mailbox, feeding each payload to [onPayload] (the
     *  session's ingest — dedup happens there). A 401 refreshes the token via
     *  recover and retries once. Never throws. */
    /** The handler gets each row's payload plus its group_id and the home's
     *  host: if a backup island ALSO hosts a group we joined (§5c — same
     *  identity, same mailbox there), group rows arrive through this drain and
     *  must be filed under the local alias, not the raw remote group id.
     *
     *  [deviceId] is our libsignal device, passed to drain AND ack so the two
     *  agree. It is a HOME-island fact: on a backup mailbox we hold a separate
     *  alias with no published bundle, so everything spooled there is an
     *  unaddressed v=1 copy that any `dev` is served. What matters is that both
     *  calls name the same one.
     *
     *  [onLogRow] is the Stage 5 half: on an island that advertises the room
     *  log, the log is drained right after the queue, through the same filing
     *  (see [drainGroupLog]). Null leaves the island on the legacy rows. */
    suspend fun drainBackupQueues(
        ownUin: Int,
        signingPriv: ByteArray,
        signingPub: ByteArray,
        deviceId: Int = 1,
        /** False once this drain no longer speaks for the account it started
         *  for. That covers an account SWITCH and also the duress view coming
         *  up, which rebinds every per-account store without being a switch.
         *
         *  ⚠⚠ Checked before the ack in particular: [ack] tells the island the
         *  rows were taken and the island DELETES them, so a drain that keeps
         *  going does not merely misfile account A's mail under account B, it
         *  destroys the only copy. */
        stillOurs: () -> Boolean = { true },
        onLogRow: ((payload: String, groupId: Int, host: String) -> String?)? = null,
        onPayload: (payload: String, groupId: Int?, host: String) -> Unit,
    ) {
        for (home in MultihomeStore.list(ownUin)) {
            if (!stillOurs()) return
            // ⚠ A phantom front home is OUR OWN island by another road: this
            // drain would pull the account's REAL queue through the front on a
            // recover-minted token and then ack the rows away from the primary
            // cursor, behind the back of the main drain the session runs on.
            // Never drain the real queue through a front.
            if (RelayConfigStore.isFrontHost(home.host)) continue
            runCatching {
                val api = RcqApi("https://${home.host}")
                api.setToken(home.jwt)
                val rows = try {
                    api.drainQueue(deviceId)
                } catch (e: IOException) {
                    if (e.message?.startsWith("HTTP 401") == true) {
                        if (!stillOurs()) return@runCatching
                        val fresh = recoverOn(home.host, signingPriv, signingPub) ?: return@runCatching
                        if (!stillOurs()) return@runCatching
                        MultihomeStore.updateCreds(ownUin, home.host, fresh.uin, fresh.token)
                        api.setToken(fresh.token)
                        api.drainQueue(deviceId)
                    } else throw e
                }
                if (!stillOurs()) return@runCatching
                rows.forEach { q -> q.payload?.let { onPayload(it, q.group_id, home.host) } }
                // ⚠⚠ Asked AGAIN, after the loop. The account can change while
                // the rows are being filed, and the handler then drops the rest
                // of them - but the ack does not know that and would tell the
                // island they were taken, which DELETES them. Skipping the ack
                // costs one redelivery, which the envelope-uuid dedup collapses.
                if (!stillOurs()) return@runCatching
                ack(api, rows, deviceId)
                // ⚠ Two network calls stand between the last check and this one
                // (the ack above and the capability probe), so it is asked again
                // here rather than inherited.
                if (!stillOurs()) return@runCatching
                if (onLogRow != null && advertisesGroupLog(api, home.host)) drainGroupLog(api, home.host, stillOurs, onLogRow)
            }.onFailure {
                android.util.Log.w("RCQfed", "multihome drain ${home.host}: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /** §5c: drain the GUEST mailbox on every visited island (the receive path
     *  for cross-island groups — the host island spools group fan-out there).
     *  Same shape as the backup drain; 401 → recover-refresh once. */
    suspend fun drainVisitedQueues(
        signingPriv: ByteArray,
        signingPub: ByteArray,
        deviceId: Int = 1,
        /** The islands to drain, taken as a SNAPSHOT by the caller before its
         *  first suspension. ⚠⚠ Reading [VisitedIslandsStore] here instead is
         *  the bug this parameter exists to prevent: it is a singleton that
         *  Session.rebindTo re-points on an account switch, so a drain that
         *  started under account A would come back and walk account B's list
         *  while still holding A's signing keys, and the 401 branch below would
         *  then sign a recovery challenge as A and write the resulting guest
         *  credentials into B's store. */
        visited: List<VisitedIslandsStore.Visited> = VisitedIslandsStore.list(),
        /** False once the caller's account has been switched out from under it.
         *  Checked before every write and before the ack, because the ack is a
         *  second network call and the island DELETES what it acknowledges. */
        stillOurs: () -> Boolean = { true },
        onLogRow: ((payload: String, groupId: Int, host: String) -> String?)? = null,
        onPayload: (payload: String, groupId: Int?, host: String) -> Unit,
    ) {
        for (v in visited) {
            if (!stillOurs()) return
            runCatching {
                val api = RcqApi("https://${v.host}")
                api.setToken(v.jwt)
                val rows = try {
                    api.drainQueue(deviceId)
                } catch (e: IOException) {
                    if (e.message?.startsWith("HTTP 401") == true) {
                        if (!stillOurs()) return@runCatching
                        val fresh = recoverOn(v.host, signingPriv, signingPub) ?: return@runCatching
                        if (!stillOurs()) return@runCatching
                        VisitedIslandsStore.updateCreds(v.host, fresh.uin, fresh.token)
                        api.setToken(fresh.token)
                        api.drainQueue(deviceId)
                    } else throw e
                }
                if (!stillOurs()) return@runCatching
                rows.forEach { q -> q.payload?.let { onPayload(it, q.group_id, v.host) } }
                // Same as the backup drain: the ack is a deletion, so it is
                // asked again after the rows have actually been handed over.
                if (!stillOurs()) return@runCatching
                ack(api, rows, deviceId)
                // ⚠ Two network calls stand between the last check and this one
                // (the ack above and the capability probe), so it is asked again
                // here rather than inherited.
                if (!stillOurs()) return@runCatching
                if (onLogRow != null && advertisesGroupLog(api, v.host)) drainGroupLog(api, v.host, stillOurs, onLogRow)
            }.onFailure {
                android.util.Log.w("RCQfed", "visited drain ${v.host}: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    // ── Gossip: mirror a peer's signed record by global identity (sk) so it can
    // be served from any honest island a contact uses (address-mobility B1).
    // Self-signed, so a mirror adds redundancy with zero added trust — the
    // server re-verifies the signature on write, this client on read.

    /** Best-effort mirror a verified record JSON onto [host]'s gossip store.
     *  Never throws; a busy/unreachable island just retries on the next resolve. */
    fun mirrorRecord(host: String, recordJson: String) {
        runCatching {
            val req = Request.Builder()
                .url("https://$host/federation/gossip-record")
                .put(recordJson.toRequestBody(JSON))
                .build()
            http().newCall(req).execute().use { }
        }
    }

    /** Fetch a peer's mirrored record by its Ed25519 signing key from [host]'s
     *  gossip store. Returns the parsed doc or null (404 / unreachable / error). */
    fun fetchGossipRecord(host: String, signingKeyB64: String): JsonObject? = runCatching {
        val sk = java.net.URLEncoder.encode(signingKeyB64, "UTF-8")
        val req = Request.Builder().url("https://$host/federation/gossip-record?sk=$sk").get().build()
        http().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JsonParser.parseString(resp.body?.string() ?: "").asJsonObject
        }
    }.getOrNull()

    private fun homesOf(doc: JsonObject): List<RcqFederation.Home> =
        doc.getAsJsonArray("homes").map {
            val h = it.asJsonObject
            RcqFederation.Home(h.get("host").asString, h.get("uin").asInt)
        }

    /** The homes OUR OWN published record already lists, verified against our
     *  own signing key. Blocking — call from IO. Empty when there is no record
     *  yet, when it does not verify, or when the island cannot be reached.
     *
     *  ⚠⚠ This exists because the record is an ACCOUNT-wide fact and this
     *  install only ever knew its own local half of it. A backup island
     *  switched on in the web (or on a second phone) is invisible here — and
     *  the boot republish then PUT a record without it, under a fresh `ts`.
     *  The island rejects only an OLDER ts, so the newcomer's own record wins
     *  and the backup island quietly stops being advertised to senders: mail
     *  keeps going to a mailbox nobody is told about any more. Reading before
     *  publishing is what keeps one device from unpublishing another's work.
     */
    fun ownPublishedHomes(ownHost: String, ownUin: Int, ownSigningPubB64: String): List<RcqFederation.Home> = runCatching {
        val req = Request.Builder()
            .url("https://$ownHost/federation/island-record/$ownUin")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        http().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching emptyList()
            val doc = JsonParser.parseString(resp.body?.string() ?: "").asJsonObject
            when (val v = RcqFederation.verifyRecord(doc, expectedIk = null, expectedSk = ownSigningPubB64)) {
                is RcqFederation.VerifyResult.Ok -> homesOf(v.doc)
                else -> emptyList()
            }
        }
    }.getOrDefault(emptyList())

    /** Apply a contact's SELF-PUSHED home-island record (gossip B1): verify it
     *  is signed by [senderSigningPub] (the same Ed25519 key that signed the
     *  envelope it arrived in — binds the record to its real sender), reject a
     *  ts rollback against what we've already cached, and cache the homes for
     *  future sends. Returns true when the cache was updated. Never throws. */
    fun applyPushedRecord(senderUin: Int, senderSigningPub: ByteArray, rec: JsonObject): Boolean = runCatching {
        val expectedSk = Base64.encodeToString(senderSigningPub, Base64.NO_WRAP)
        val prevTs = MultihomeStore.cachedPeerHomes(senderUin)?.recTs ?: 0
        val v = RcqFederation.verifyRecord(rec, expectedIk = null, expectedSk = expectedSk, minTs = prevTs.takeIf { it > 0 })
        if (v !is RcqFederation.VerifyResult.Ok) return false
        val ts = v.doc.get("ts")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        MultihomeStore.cachePeerHomes(senderUin, homesOf(v.doc), ts)
        true
    }.getOrDefault(false)

    /** Resolve a peer's home list. Sources, in order: (1) OUR island's by-uin
     *  owner record (peer is on / multi-homed onto us), seeded into our gossip
     *  store on success; (2) OUR island's GOSSIP mirror by sk (some contact
     *  mirrored it here — survives the peer's own island being blocked/gone).
     *  Verified against the peer's locally-pinned Ed25519 signing key. TTL
     *  cached; a total miss serves the stale cache. */
    private fun resolvePeerHomesCached(ownHost: String, peerUin: Int, peerSigningKeyB64: String): List<RcqFederation.Home> {
        val cached = MultihomeStore.cachedPeerHomes(peerUin)
        if (cached != null && System.currentTimeMillis() - cached.ts < PEER_CACHE_TTL_MS) return cached.homes
        // (1) by-uin owner record on our island.
        runCatching {
            val req = Request.Builder().url("https://$ownHost/federation/island-record/$peerUin").get().build()
            http().newCall(req).execute().use { resp ->
                if (resp.code == 404) return@runCatching null
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body?.string() ?: ""
                val doc = JsonParser.parseString(body).asJsonObject
                val v = RcqFederation.verifyRecord(doc, expectedIk = null, expectedSk = peerSigningKeyB64)
                if (v is RcqFederation.VerifyResult.Ok) {
                    mirrorRecord(ownHost, body)  // seed gossip so other islands can serve it by sk
                    val homes = homesOf(v.doc)
                    MultihomeStore.cachePeerHomes(peerUin, homes, v.doc.get("ts")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
                    return homes
                }
                null
            }
        }
        // (2) gossip mirror by sk on our island.
        val rec = fetchGossipRecord(ownHost, peerSigningKeyB64)
        if (rec != null) {
            val v = RcqFederation.verifyRecord(rec, expectedIk = null, expectedSk = peerSigningKeyB64)
            if (v is RcqFederation.VerifyResult.Ok) {
                val homes = homesOf(v.doc)
                MultihomeStore.cachePeerHomes(peerUin, homes, v.doc.get("ts")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
                return homes
            }
        }
        // by-uin was a clean 404 (peer has no owner record here) and no gossip:
        // cache empty so single-homed peers cost one lookup per TTL.
        if (cached == null) MultihomeStore.cachePeerHomes(peerUin, emptyList())
        return cached?.homes ?: emptyList()
    }

    /** Resolve a peer's homes from THEIR OWN island, mirror the verified record
     *  onto our island's gossip store, and fall back to our gossip mirror if
     *  their island is unreachable. The cross-island entry point (used when we
     *  know the peer's home host). Returns verified homes, or [] if nothing
     *  verifies anywhere. */
    fun resolveAndMirrorHomes(ownHost: String, peerHost: String, peerUin: Int, peerSigningKeyB64: String): List<RcqFederation.Home> {
        runCatching {
            val req = Request.Builder().url("https://$peerHost/federation/island-record/$peerUin").get().build()
            http().newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val doc = JsonParser.parseString(body).asJsonObject
                    val v = RcqFederation.verifyRecord(doc, expectedIk = null, expectedSk = peerSigningKeyB64)
                    if (v is RcqFederation.VerifyResult.Ok) {
                        mirrorRecord(ownHost, body)
                        return homesOf(v.doc)
                    }
                }
            }
        }
        val rec = fetchGossipRecord(ownHost, peerSigningKeyB64)
        if (rec != null) {
            val v = RcqFederation.verifyRecord(rec, expectedIk = null, expectedSk = peerSigningKeyB64)
            if (v is RcqFederation.VerifyResult.Ok) return homesOf(v.doc)
        }
        return emptyList()
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
            // A front in a PEER's record (25 flagship accounts carried one) is
            // our own island by another road: depositing "extra" copies there
            // just doubles the rows in the queue the primary send already
            // reached.
            val extra = resolvePeerHomesCached(ownHost, peerUin, peerSigningKeyB64)
                .filter { it.host != ownHost && !RelayConfigStore.isFrontHost(it.host) }
            if (extra.isEmpty()) return 0

            val recipientPub = Base64.decode(peerIdentityKeyB64, Base64.NO_WRAP)
            val payload = SealedSender.encryptV1(env, recipientPub, ownUin, signingPriv, signingPub, ownHost)
            var delivered = 0
            for (h in extra) {
                val body = JsonObject().apply {
                    addProperty("to_uin", h.uin)
                    addProperty("envelope_type", "message")
                    // Stage 2: retention / push class beside the legacy type.
                    addProperty("cls", SealedSender.messageClass("message"))
                    addProperty("payload", payload)
                }.toString().toRequestBody(JSON)
                val req = Request.Builder().url("https://${h.host}/messages/sealed").post(body).build()
                runCatching { http().newCall(req).execute().use { if (it.isSuccessful) delivered++ } }
            }
            delivered
        } catch (e: Exception) {
            0
        }
    }
}
