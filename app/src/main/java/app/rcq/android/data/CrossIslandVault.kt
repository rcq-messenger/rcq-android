package app.rcq.android.data

import android.util.Base64
import android.util.Log
import app.rcq.android.crypto.Vault
import app.rcq.android.net.CrossIslandStore
import app.rcq.android.net.RcqApi
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cross-island contacts in the vault. The Android half of web-chat's
 * `src/lib/crossisland-vault.ts`; same slot, same merge, same bytes.
 *
 * [CrossIslandStore] is the ONLY record that a peer on another island exists:
 * there is no server-side row for them, because /contacts holds users of this
 * island and a peer on another one is not. That store is SharedPreferences. So
 * reinstall the app, or simply pick up the second device, and every
 * cross-island contact was gone along with the peer's pinned keys, with no way
 * back but asking them for their number again. Same-island contacts have
 * survived that since stage 4.
 *
 * Deliberately NOT what [ContactsVault] does. That slot is a MIRROR of a list
 * the island can always be asked for again, so its merge is server-wins. This
 * slot is the only copy in existence, so the merge is a two-way union and a
 * failed write is retried by the next read.
 *
 * ⚠⚠ The pinned keys are the point, and the one thing a merge must not get
 * wrong. `identityKey` / `signingKey` / `signalIdentityKey` come from the
 * peer's island key card at the moment they were added, and everything ever
 * verified about that peer is checked against them. So the merge is
 * TRUST-ON-FIRST-USE across devices: when both sides hold the same handle the
 * keys come from the row with the EARLIER addedAt, never the newer one. A
 * second device cannot re-pin a peer to a different key card by adding them
 * again, which is exactly what an island handing out a swapped card needs it
 * to do. Display fields go the other way: newest profileTs wins.
 *
 * ⚠⚠ The wire shape is a CONTRACT between three clients with three
 * serialisers, not a private encoding. [canon] is that contract: required
 * fields always, optionals only when they hold a value, `profileTs` always a
 * number, keys sorted. Anything else and this client and the web read each
 * other's writes as a disagreement, rewrite, and burn the account's
 * 240-puts-an-hour budget rewriting the same contacts at each other forever.
 */
object CrossIslandVault {

    class Ctx(
        val api: RcqApi,
        val identityPriv: ByteArray,
        val uin: Int,
        val account: String,
        val scope: CoroutineScope,
        val stillOurs: () -> Boolean = { true },
    )

    private const val PUSH_DEBOUNCE_MS = 900L
    private const val TOMBSTONE_TTL_MS = 90L * 24 * 3600 * 1000

    /** The island's cap is 256 KiB decoded and a write over it is a permanent
     *  413: a sync that never works again and says nothing. A row is ~250
     *  bytes. Whoever gets here keeps every row locally and loses the backup,
     *  which is the mild half of the failure. */
    private const val MAX_ROWS = 600

    @Volatile private var rolledBackFor: Int? = null
    private val pushMutex = Mutex()
    @Volatile private var pushJob: Job? = null

    fun slotOf(identityPriv: ByteArray): String = Vault.slotId(identityPriv, Vault.CROSSISLAND)

    /** `vault_reset`: another device rotated the identity this slot name and
     *  key derive from. Stop, and keep the local rows — they are the only copy. */
    fun retire(uin: Int) {
        rolledBackFor = uin
        pushJob?.cancel(); pushJob = null
    }

    fun resetState() {
        rolledBackFor = null
        pushJob?.cancel(); pushJob = null
        armedFor = null
    }

    @Volatile private var armedFor: Int? = null

    /**
     * Point the store's change listener at this account's slot. Called from the
     * vault sweep, which is the one thing that runs at boot and on every
     * reconnect with a live context in hand; arming anywhere a screen can leave
     * would stop mirroring local adds for the rest of the session.
     */
    fun arm(ctx: Ctx) {
        if (armedFor == ctx.uin) return
        armedFor = ctx.uin
        CrossIslandStore.setListener { schedulePush(ctx) }
    }

    // ── the merge, pure and shared with the web ──────────────────────────

    private fun str(o: JsonObject, k: String): String? =
        o.get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private fun num(o: JsonObject, k: String): Long =
        o.get(k)?.takeIf { !it.isJsonNull }?.runCatching { asLong }?.getOrNull() ?: 0L

    private fun valid(e: JsonElement?): Boolean {
        val o = e as? JsonObject ?: return false
        return o.get("uin")?.isJsonPrimitive == true &&
            str(o, "host") != null &&
            o.has("identityKey") && !o.get("identityKey").isJsonNull &&
            o.has("signingKey") && !o.get("signingKey").isJsonNull
    }

    private fun ciKey(o: JsonObject): String = "${num(o, "uin")}@${(str(o, "host") ?: "").lowercase()}"

    /** `profileTs` is epoch SECONDS on the wire; `addedAt` is ms. */
    private fun updatedAt(o: JsonObject): Long = maxOf(num(o, "addedAt"), num(o, "profileTs") * 1000)

    /** Keys from the earlier row, display from the newer one. */
    private fun combine(a: JsonObject, b: JsonObject): JsonObject {
        val base = if (num(a, "addedAt") <= num(b, "addedAt")) a else b
        val fresh = if (updatedAt(a) >= updatedAt(b)) a else b
        val o = JsonObject()
        o.addProperty("uin", num(base, "uin"))
        o.addProperty("host", str(base, "host"))
        o.addProperty("nickname", str(fresh, "nickname") ?: str(base, "nickname"))
        o.addProperty("identityKey", str(base, "identityKey"))
        o.addProperty("signingKey", str(base, "signingKey"))
        str(base, "signalIdentityKey")?.let { o.addProperty("signalIdentityKey", it) }
        o.addProperty("addedAt", num(base, "addedAt"))
        (str(fresh, "gender") ?: str(base, "gender"))?.let { o.addProperty("gender", it) }
        (str(fresh, "statusMessage") ?: str(base, "statusMessage"))?.let { o.addProperty("statusMessage", it) }
        val id = str(fresh, "avatarMediaId")
        val key = str(fresh, "avatarMediaKey")
        if (id != null && key != null) {
            o.addProperty("avatarMediaId", id)
            o.addProperty("avatarMediaKey", key)
        }
        o.addProperty("profileTs", maxOf(num(a, "profileTs"), num(b, "profileTs")))
        return o
    }

    /**
     * Every row leaving the merge goes through here, including the ones only
     * one device has. ⚠ Not only the combined ones: the avatar id and its key
     * are a pair, and a row written before that rule can carry half of one.
     * Half a pair names a blob nobody can open, so the picture stays broken
     * forever.
     */
    private fun canonRow(o: JsonObject): JsonObject {
        val out = JsonObject()
        out.addProperty("addedAt", num(o, "addedAt"))
        val id = str(o, "avatarMediaId")
        val key = str(o, "avatarMediaKey")
        if (id != null && key != null) {
            out.addProperty("avatarMediaId", id)
            out.addProperty("avatarMediaKey", key)
        }
        str(o, "gender")?.let { out.addProperty("gender", it) }
        out.addProperty("host", str(o, "host"))
        out.addProperty("identityKey", str(o, "identityKey"))
        out.addProperty("nickname", str(o, "nickname") ?: "")
        out.addProperty("profileTs", num(o, "profileTs"))
        str(o, "signalIdentityKey")?.let { out.addProperty("signalIdentityKey", it) }
        out.addProperty("signingKey", str(o, "signingKey"))
        str(o, "statusMessage")?.let { out.addProperty("statusMessage", it) }
        out.addProperty("uin", num(o, "uin"))
        return sortKeys(out)
    }

    private fun sortKeys(o: JsonObject): JsonObject {
        val out = JsonObject()
        for (k in o.keySet().sorted()) {
            val v = o.get(k)
            out.add(k, if (v is JsonObject) sortKeys(v) else v)
        }
        return out
    }

    /** The wire form: sorted keys, canonical rows. */
    fun canon(state: JsonObject): JsonObject {
        val out = JsonObject()
        out.addProperty("v", 1)
        val c = JsonObject()
        val cIn = state.getAsJsonObject("c") ?: JsonObject()
        for (k in cIn.keySet().sorted()) (cIn.get(k) as? JsonObject)?.let { c.add(k, canonRow(it)) }
        out.add("c", c)
        val g = JsonObject()
        val gIn = state.getAsJsonObject("g") ?: JsonObject()
        for (k in gIn.keySet().sorted()) g.addProperty(k, gIn.get(k).asLong)
        out.add("g", g)
        return out
    }

    fun sameContent(a: JsonObject, b: JsonObject): Boolean = canon(a).toString() == canon(b).toString()

    fun empty(): JsonObject {
        val o = JsonObject()
        o.addProperty("v", 1)
        o.add("c", JsonObject())
        o.add("g", JsonObject())
        return o
    }

    /**
     * A tombstone kills a row only while it is NEWER than that row was added:
     * remove a peer on the phone, add them again on the desktop, and the fresh
     * row wins, or re-adding somebody you once removed would be impossible
     * from a second device.
     */
    fun merge(local: JsonObject, remote: JsonObject, now: Long): JsonObject {
        val lc = local.getAsJsonObject("c") ?: JsonObject()
        val rc = remote.getAsJsonObject("c") ?: JsonObject()
        val lg = local.getAsJsonObject("g") ?: JsonObject()
        val rg = remote.getAsJsonObject("g") ?: JsonObject()

        val graves = HashMap<String, Long>()
        for (k in lg.keySet() + rg.keySet()) {
            val t = maxOf(
                lg.get(k)?.runCatching { asLong }?.getOrNull() ?: 0L,
                rg.get(k)?.runCatching { asLong }?.getOrNull() ?: 0L,
            )
            if (t > 0 && now - t < TOMBSTONE_TTL_MS) graves[k] = t
        }

        val rows = HashMap<String, JsonObject>()
        for (k in lc.keySet() + rc.keySet()) {
            val a = lc.get(k)
            val b = rc.get(k)
            val picked = when {
                valid(a) && valid(b) -> combine(a as JsonObject, b as JsonObject)
                valid(a) -> a as JsonObject
                valid(b) -> b as JsonObject
                else -> null
            } ?: continue
            val buried = graves[k] ?: 0L
            if (buried > num(picked, "addedAt")) continue
            if (buried > 0) graves.remove(k)
            rows[ciKey(picked)] = picked
        }

        val out = JsonObject()
        out.addProperty("v", 1)
        val c = JsonObject()
        for (k in rows.keys.sorted()) c.add(k, rows[k])
        out.add("c", c)
        val g = JsonObject()
        for (k in graves.keys.sorted()) g.addProperty(k, graves[k])
        out.add("g", g)
        return canon(out)
    }

    // ── this device's side ───────────────────────────────────────────────

    private fun localState(): JsonObject {
        val out = JsonObject()
        out.addProperty("v", 1)
        val c = JsonObject()
        for (r in CrossIslandStore.list()) {
            val o = JsonObject()
            o.addProperty("uin", r.uin)
            o.addProperty("host", r.host)
            o.addProperty("nickname", r.nickname)
            o.addProperty("identityKey", r.identityKey)
            o.addProperty("signingKey", r.signingKey)
            r.signalIdentityKey?.let { o.addProperty("signalIdentityKey", it) }
            o.addProperty("addedAt", r.addedAt)
            r.gender?.let { o.addProperty("gender", it) }
            r.statusMessage?.let { o.addProperty("statusMessage", it) }
            r.avatarMediaId?.let { o.addProperty("avatarMediaId", it) }
            r.avatarMediaKey?.let { o.addProperty("avatarMediaKey", it) }
            o.addProperty("profileTs", r.profileTs)
            c.add("${r.uin}@${r.host.lowercase()}", o)
        }
        out.add("c", c)
        val g = JsonObject()
        for ((k, v) in CrossIslandStore.tombstones()) g.addProperty(k, v)
        out.add("g", g)
        return out
    }

    private fun applyLocally(state: JsonObject, account: String) {
        val c = state.getAsJsonObject("c") ?: return
        val rows = ArrayList<CrossIslandStore.Contact>()
        for (k in c.keySet()) {
            val o = c.getAsJsonObject(k) ?: continue
            rows.add(
                CrossIslandStore.Contact(
                    uin = num(o, "uin").toInt(),
                    host = str(o, "host") ?: continue,
                    nickname = str(o, "nickname") ?: "",
                    identityKey = str(o, "identityKey") ?: continue,
                    signingKey = str(o, "signingKey") ?: continue,
                    signalIdentityKey = str(o, "signalIdentityKey"),
                    addedAt = num(o, "addedAt"),
                    gender = str(o, "gender"),
                    statusMessage = str(o, "statusMessage"),
                    avatarMediaId = str(o, "avatarMediaId"),
                    avatarMediaKey = str(o, "avatarMediaKey"),
                    profileTs = num(o, "profileTs"),
                ),
            )
        }
        val graves = HashMap<String, Long>()
        (state.getAsJsonObject("g") ?: JsonObject()).entrySet().forEach { (k, v) ->
            graves[k] = v.runCatching { asLong }.getOrNull() ?: 0L
        }
        CrossIslandStore.replaceAll(account, rows, graves)
    }

    private fun openState(ctx: Ctx, slot: String, cur: RcqApi.VaultSlotRead): JsonObject? {
        val blob = cur.blob ?: return empty()
        return try {
            val bytes = Vault.open(ctx.identityPriv, slot, cur.version, Base64.decode(blob, Base64.DEFAULT))
            val o = JsonParser.parseString(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return empty()
            val v = num(o, "v")
            // A newer format: leave it alone rather than overwrite what wrote it.
            if (v > 1L) null else if (o.has("c")) o else empty()
        } catch (e: Exception) {
            Log.i("RCQcisland", "slot unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    // ── read path: boot, the nudge, every reconnect ──────────────────────

    /** Never throws. Returns the number of cross-island contacts now held. */
    suspend fun sync(ctx: Ctx): Int {
        if (rolledBackFor == ctx.uin) return 0
        val slot = slotOf(ctx.identityPriv)
        val now = System.currentTimeMillis()
        return try {
            val cur = ctx.api.vaultGet(slot)
            if (!ctx.stillOurs()) return 0
            val floor = LocalStores.vaultSlotVersion(slot)
            if (cur.version < floor) {
                rolledBackFor = ctx.uin
                Log.w("RCQcisland", "island served ${cur.version} below the floor $floor; sync stopped for this session")
                return 0
            }
            val remote = openState(ctx, slot, cur) ?: return 0
            val next = merge(localState(), remote, now)
            if (!ctx.stillOurs()) return 0
            applyLocally(next, ctx.account)
            LocalStores.setVaultSlotVersion(slot, cur.version, ctx.account)
            // ⚠ AND THIS IS THE RETRY, exactly as in SectionsVault: a write
            // that failed (offline, a 429, a 5xx) leaves rows nothing else in
            // the world holds, and the read path is the one thing that runs on
            // boot, on the nudge and on every reconnect.
            if (!sameContent(next, remote)) ctx.scope.launch { push(ctx) }
            (next.getAsJsonObject("c") ?: JsonObject()).size()
        } catch (e: Exception) {
            Log.i("RCQcisland", "sync failed: ${e.javaClass.simpleName}: ${e.message}")
            0
        }
    }

    // ── write path ───────────────────────────────────────────────────────

    /** A local add or remove just happened. Debounced: accepting a request
     *  writes the row and then the profile that came with it. */
    fun schedulePush(ctx: Ctx?) {
        if (ctx == null) return
        pushJob?.cancel()
        pushJob = ctx.scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            push(ctx)
        }
    }

    suspend fun push(ctx: Ctx) = pushMutex.withLock { pushOnce(ctx) }

    private suspend fun pushOnce(ctx: Ctx) {
        if (rolledBackFor == ctx.uin) return
        val slot = slotOf(ctx.identityPriv)
        val now = System.currentTimeMillis()
        var floor = LocalStores.vaultSlotVersion(slot)
        try {
            repeat(5) {
                val cur = ctx.api.vaultGet(slot)
                if (!ctx.stillOurs()) return
                if (cur.version < floor) {
                    rolledBackFor = ctx.uin
                    Log.w("RCQcisland", "island served ${cur.version} below the floor $floor; sync stopped for this session")
                    return
                }
                val remote = openState(ctx, slot, cur) ?: return
                // ⚠⚠ Same rule as SectionsVault: what is about to be SEALED
                // into this account's slot comes from THIS account's store or
                // from nowhere. The duress PIN can rebind the stores while the
                // GET is in the air, and publishing the decoy's contacts into
                // the real account's slot would syndicate them to every device.
                if (!ctx.stillOurs()) return
                val next = merge(localState(), remote, now)
                if (sameContent(next, remote)) return
                val rows = (next.getAsJsonObject("c") ?: JsonObject()).size()
                if (rows > MAX_ROWS) {
                    Log.w("RCQcisland", "$rows rows is over the slot budget; not backing up")
                    return
                }
                val sealed = Vault.seal(
                    ctx.identityPriv, slot, cur.version + 1,
                    next.toString().toByteArray(Charsets.UTF_8),
                )
                val w = ctx.api.vaultPut(slot, Base64.encodeToString(sealed, Base64.NO_WRAP), cur.version)
                if (!ctx.stillOurs()) return
                if (w.version != null) {
                    LocalStores.setVaultSlotVersion(slot, w.version, ctx.account)
                    applyLocally(next, ctx.account)
                    return
                }
                floor = maxOf(floor, w.current)
            }
        } catch (e: Exception) {
            Log.i("RCQcisland", "push failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
