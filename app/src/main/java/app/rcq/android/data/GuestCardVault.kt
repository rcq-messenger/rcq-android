package app.rcq.android.data

import android.util.Base64
import android.util.Log
import app.rcq.android.crypto.Vault
import app.rcq.android.net.GuestCardStore
import app.rcq.android.net.RcqApi
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The cards other people gave us, in the vault.
 *
 * ⚠⚠ WITHOUT THIS THE WHOLE FEATURE HAS A TRAPDOOR, and the trapdoor is
 * silent. A guest card is the only way to reach somebody on a closed island,
 * the cards live in SharedPreferences, and a closed island answers a caller
 * with no card by saying "no such number" — which is the refusal working
 * exactly as designed. So a person reinstalls, restores from their recovery
 * phrase, sees every contact intact, and simply cannot write to any of them,
 * with nothing on screen to explain it.
 *
 * ⚠ ONLY THEIRS, never ours. A card we minted is a credential we HAND OUT: if
 * it is lost we mint another and share it again, and the island revokes the
 * old one from its own list. A card somebody gave US is irreplaceable without
 * asking them for it, which on a closed island is exactly the conversation we
 * cannot have.
 *
 * The merge is a union that never removes. Removals are deliberately not
 * synced: a card a stale device dropped must not vanish from a device still
 * using it. The cost is a card kept slightly too long, which nobody can see;
 * the alternative is a contact who goes quietly unreachable. Local wins a
 * collision — somebody can revoke and re-share, and the device holding the
 * newer card is the one that is right.
 *
 * Mirrors web-chat's `src/lib/guestcard-vault.ts`, including sorted keys so
 * two devices that agree on the cards agree on the bytes rather than
 * rewriting the slot at each other.
 */
object GuestCardVault {

    /// A card is ~43 characters and a handle is short, so this is far under the
    /// island's 256 KiB blob cap. The bound exists so a corrupted or hostile
    /// slot cannot make the client build an enormous map.
    private const val MAX_CARDS = 2000

    @Volatile private var rolledBackFor: Int? = null

    fun slotOf(identityPriv: ByteArray): String = Vault.slotId(identityPriv, Vault.GUESTCARDS)

    fun retire(uin: Int) { rolledBackFor = uin }

    fun resetState() { rolledBackFor = null }

    /** Union, local first, sorted. Pure; exported for the test. */
    fun merge(local: Map<String, String>, remote: Map<String, String>): LinkedHashMap<String, String> {
        val out = HashMap<String, String>(remote)
        out.putAll(local)
        val keys = out.keys.sorted().take(MAX_CARDS)
        val sorted = LinkedHashMap<String, String>(keys.size)
        for (k in keys) out[k]?.let { sorted[k] = it }
        return sorted
    }

    private fun encode(m: Map<String, String>): JsonObject {
        val o = JsonObject()
        o.addProperty("v", 1)
        val c = JsonObject()
        for ((k, v) in m) c.addProperty(k, v)
        o.add("c", c)
        return o
    }

    /** null = a newer format this build must not overwrite. */
    private fun decode(json: String?): Map<String, String>? {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JsonParser.parseString(json) as? JsonObject ?: return emptyMap()
            val v = o.get("v")?.asInt ?: 1
            if (v > 1) return null
            val c = o.getAsJsonObject("c") ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for ((k, e) in c.entrySet()) {
                val s = runCatching { e.asString }.getOrNull() ?: continue
                if (s.isNotBlank() && s.length <= 128 && out.size < MAX_CARDS) out[k] = s
            }
            out
        }.getOrNull() ?: emptyMap()
    }

    /**
     * Boot, the nudge, and every reconnect. Never throws.
     *
     * ⚠ Only where cards mean anything. An open island never mints one, so
     * there is nothing to carry and no reason to spend a request.
     */
    suspend fun sync(ctx: SectionsVault.Ctx, closedIsland: Boolean): Int {
        if (rolledBackFor == ctx.uin || !closedIsland) return 0
        val slot = slotOf(ctx.identityPriv)
        return try {
            val cur = ctx.api.vaultGet(slot)
            if (!ctx.stillOurs()) return 0
            val floor = LocalStores.vaultSlotVersion(slot)
            if (cur.version < floor) {
                rolledBackFor = ctx.uin
                Log.w("RCQcards", "island served ${cur.version} below the floor $floor; sync stopped")
                return 0
            }
            val remote = openSlot(ctx, slot, cur) ?: return 0
            val merged = merge(GuestCardStore.allTheirCards(), remote)
            if (!ctx.stillOurs()) return 0
            GuestCardStore.replaceTheirCards(merged)
            LocalStores.setVaultSlotVersion(slot, cur.version, ctx.account)
            // The same retry the other slots use: if folding our copy into the
            // island's copy changes the island's copy, the island is missing
            // something of ours, and a device that received a card five minutes
            // ago is the only thing in the world that has it.
            if (merged != remote.toSortedMap()) push(ctx, slot, merged)
            merged.size
        } catch (e: Exception) {
            Log.i("RCQcards", "sync failed: ${e.javaClass.simpleName}")
            0
        }
    }

    private fun openSlot(ctx: SectionsVault.Ctx, slot: String, cur: RcqApi.VaultSlotRead): Map<String, String>? {
        val blob = cur.blob ?: return emptyMap()
        return runCatching {
            val bytes = Vault.open(ctx.identityPriv, slot, cur.version, Base64.decode(blob, Base64.DEFAULT))
            decode(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    private suspend fun push(ctx: SectionsVault.Ctx, slot: String, merged: Map<String, String>) {
        runCatching {
            var floor = LocalStores.vaultSlotVersion(slot)
            repeat(5) {
                val cur = ctx.api.vaultGet(slot)
                if (!ctx.stillOurs()) return
                if (cur.version < floor) { rolledBackFor = ctx.uin; return }
                val remote = openSlot(ctx, slot, cur) ?: return
                val next = merge(merged, remote)
                if (next == remote.toSortedMap()) return
                val sealed = Vault.seal(
                    ctx.identityPriv, slot, cur.version + 1,
                    encode(next).toString().toByteArray(Charsets.UTF_8),
                )
                val w = ctx.api.vaultPut(slot, Base64.encodeToString(sealed, Base64.NO_WRAP), cur.version)
                if (!ctx.stillOurs()) return
                if (w.version != null) {
                    LocalStores.setVaultSlotVersion(slot, w.version, ctx.account)
                    GuestCardStore.replaceTheirCards(next)
                    return
                }
                floor = maxOf(floor, w.current)
            }
        }
    }
}
