package app.rcq.android.data

import android.util.Base64
import android.util.Log
import app.rcq.android.crypto.Vault
import app.rcq.android.model.RcqGroup
import app.rcq.android.net.RcqApi
import app.rcq.android.net.VisitedIslandsStore
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The `sections` slot: read it, merge it, write it back.
 *
 * The transport is [Vault] + [RcqApi.vaultGet]/[RcqApi.vaultPut] and the
 * read-merge-write loop is the one [ContactsVault.mirror] already runs (a write
 * names the version it was based on; the island answers 409 with the current
 * one and the loop goes around). What this file adds is the two halves that
 * loop takes as arguments: the merge ([Sections], pure) and the rollback floor.
 *
 * Both directions go through the SAME merge:
 *
 *     read   cache = merge(cache, remote)
 *     write  next  = merge(remote, cache),  nothing to send when they agree
 *
 * ⚠ A rollback must NOT clear the floor and rewrite the slot from local state:
 * there is no server-side truth to rebuild from here. The island cannot tell
 * "restored from a backup" apart from "your derivation was retired by
 * /auth/reissue", and rewriting under a retired derivation republishes the very
 * ciphertext the reissue existed to destroy. It stops the sync for this
 * session, keeps rendering the cache, and says so in the log.
 *
 * ⚠⚠ The floor is keyed by SLOT NAME ([LocalStores.vaultSlotVersion]), never by
 * the account alone. See the note there.
 */
object SectionsVault {

    /**
     * Everything one call needs from the session. Rebuilt per call by
     * [app.rcq.android.Session]: the route watchdog swaps `api` under us, and
     * an account switch mid-flight must not seal this account's sections into
     * the next one's slot ([stillOurs] is asked after every await).
     *
     * ⚠⚠ [account] is not decoration and it is not [uin]. Every read and every
     * write of the local cache in this file goes through it
     * ([LocalStores.sectionsTreeFor], [LocalStores.updateSectionsTree]),
     * because [stillOurs] structurally CANNOT catch the case that matters: in a
     * migrated decoy session the duress PIN rebinds the per-account stores to
     * [DecoyStore.STORE_ID] while `store` stays on the REAL account, so uin,
     * host and token all still say "us" and the tree the caller is holding
     * would be published on the duress screen under the real user's section
     * names. The store id is the only thing that moves in that case, so the
     * store id is what the write has to be scoped to.
     */
    class Ctx(
        val api: RcqApi,
        val identityPriv: ByteArray,
        val uin: Int,
        val account: String,
        val scope: CoroutineScope,
        val stillOurs: () -> Boolean = { true },
    )

    /** A drag ends in one write, not one per frame. 240 puts an account per
     *  hour is the island's budget; a reorder is the only gesture that can
     *  produce a burst. */
    private const val PUSH_DEBOUNCE_MS = 800L

    /** Set for the rest of the session when the island serves a version below
     *  the floor, or when the account's derivation was retired under us
     *  (`vault_reset`). Keyed by uin so an account switch clears it. */
    @Volatile private var rolledBackFor: Int? = null

    private val pushMutex = Mutex()
    @Volatile private var pushJob: Job? = null

    fun slotOf(identityPriv: ByteArray): String = Vault.slotId(identityPriv, Vault.SECTIONS)

    /**
     * Stop the slot for this session, from outside: `/auth/reissue` on another
     * device retired the derivation this install's slot name and key come from,
     * so anything written from here would be sealed with a key the user has
     * just declared dead, under a name nothing will ever read again.
     */
    fun retire(uin: Int) {
        rolledBackFor = uin
        pushJob?.cancel()
        pushJob = null
    }

    /** Account switch. */
    fun resetState() {
        rolledBackFor = null
        pushJob?.cancel()
        pushJob = null
    }

    // ── read path ────────────────────────────────────────────────────────

    /**
     * Read the island's copy and fold it into the cache. Boot, the
     * `vault_changed` nudge, and every socket reconnect (the nudge is pub/sub
     * with NO REPLAY, so a device whose socket was down never hears it).
     *
     * Never throws. Returns the tree now in the cache, or null when the slot
     * was unreadable, the sync is retired, or the island did not answer.
     */
    suspend fun sync(ctx: Ctx): JsonObject? {
        if (rolledBackFor == ctx.uin) return null
        val slot = slotOf(ctx.identityPriv)
        return try {
            val cur = ctx.api.vaultGet(slot)
            if (!ctx.stillOurs()) return null
            val floor = LocalStores.vaultSlotVersion(slot)
            if (cur.version < floor) {
                rolledBackFor = ctx.uin
                Log.w("RCQsections", "island served ${cur.version} below the floor $floor; sync stopped for this session")
                return null
            }
            val remote = openTree(ctx, slot, cur) ?: return null
            // ⚠ Scoped to ctx.account, not merely to stillOurs(): the fold and
            // the store are one operation, and the stores can be rebound (an
            // account switch, or the duress PIN) while the GET above is in the
            // air. A null answer means they were, and nothing was written.
            val next = LocalStores.updateSectionsTree(ctx.account) { Sections.merge(it, remote) } ?: return null
            LocalStores.setVaultSlotVersion(slot, cur.version, ctx.account)
            // ⚠ AND THIS IS THE RETRY. A write that failed (offline, a 429
            // against the 240-an-hour budget, a 5xx, a conflict loop) leaves an
            // edit sitting in the cache and nothing else was ever going to send
            // it: [push] has no caller but [mutate]. The read path is the one
            // thing that runs on boot, on the nudge and on every reconnect, so
            // it is where the outstanding write belongs.
            //
            // ⚠ The condition is the merge's own answer, not a byte compare: a
            // blob the web or iOS serialised with its keys in another order is
            // not a difference, and a client that writes on one gets into a
            // rewrite war with the client that wrote it.
            if (!Sections.sameContent(next, remote)) ctx.scope.launch { push(ctx) }
            next
        } catch (e: Sections.SectionsException) {
            Log.w("RCQsections", "sync refused: ${e.code}")
            null
        } catch (e: Exception) {
            Log.i("RCQsections", "sync failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ── write path ───────────────────────────────────────────────────────

    /**
     * Apply a local edit and get it to the island.
     *
     * The cache is updated first and synchronously, because the list has to
     * repaint at the speed of the tap; the write goes behind it. [defer]
     * coalesces a burst (the drag reorder, the picker sheet) into one put.
     *
     * Throws [Sections.SectionsException] from the caps BEFORE anything is
     * saved, so the UI can say "this section is full" instead of writing a blob
     * the island will refuse.
     */
    fun mutate(ctx: Ctx?, defer: Boolean = false, edit: (JsonObject) -> JsonObject): JsonObject {
        val next = edit(LocalStores.sections.value)
        Sections.assertWritable(next)
        LocalStores.setSectionsTree(next)
        LocalStores.setSectionsPushPending(true)
        if (ctx == null) return next
        if (defer) schedulePush(ctx) else ctx.scope.launch { push(ctx) }
        return next
    }

    private fun schedulePush(ctx: Ctx) {
        pushJob?.cancel()
        pushJob = ctx.scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            push(ctx)
        }
    }

    /** One write in flight at a time. Two overlapping read-merge-write loops on
     *  the same slot are legal (the island's 409 sorts them out) but they burn
     *  the hourly budget for nothing. */
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
                    Log.w("RCQsections", "island served ${cur.version} below the floor $floor; sync stopped for this session")
                    return
                }
                // Unreadable (a newer `v`, or a seal this identity cannot open):
                // writing would erase whatever wrote it. Leave the slot alone.
                val remote = openTree(ctx, slot, cur) ?: return
                // ⚠⚠ The tree that is about to be SEALED for this account's
                // slot comes from this account's cache or from nowhere. Read
                // plainly, this line folds whatever the stores hold right now,
                // and the duress PIN can have made that the decoy's tree while
                // the GET was in the air: the coercer's own sections would then
                // be published into the real user's slot and syndicated to
                // their desktop and web.
                val local = LocalStores.sectionsTreeFor(ctx.account) ?: return
                val next = Sections.dropExpired(Sections.merge(remote, local), now)
                if (Sections.sameContent(next, remote)) {
                    if (LocalStores.updateSectionsTree(ctx.account) { Sections.merge(it, remote) } == null) return
                    LocalStores.setVaultSlotVersion(slot, cur.version, ctx.account)
                    LocalStores.setSectionsPushPending(false, ctx.account)
                    return
                }
                Sections.assertWritable(next)
                val sealed = Vault.seal(ctx.identityPriv, slot, cur.version + 1, Sections.encode(next))
                val w = ctx.api.vaultPut(slot, Base64.encodeToString(sealed, Base64.NO_WRAP), cur.version)
                if (!ctx.stillOurs()) return
                if (w.version != null) {
                    LocalStores.setVaultSlotVersion(slot, w.version, ctx.account)
                    // The island's copy now includes ours, so the cache becomes
                    // the merged tree rather than the local one. Fold rather
                    // than replace: a tap that landed while the put was in the
                    // air must not be thrown away.
                    LocalStores.updateSectionsTree(ctx.account) { Sections.merge(it, next) }
                    // ...and only here. A write that threw leaves the flag
                    // standing, which is what makes the next sync look.
                    LocalStores.setSectionsPushPending(false, ctx.account)
                    return
                }
                // Stale: somebody else's write landed between our read and ours.
                floor = maxOf(floor, w.current)
            }
            Log.w("RCQsections", "write gave up after five conflicts; the next sync will send it")
        } catch (e: Sections.SectionsException) {
            Log.w("RCQsections", "refused to write: ${e.code}")
        } catch (e: Exception) {
            // Offline, or the island is unhappy (a 5xx, or a 429 against the
            // 240-an-hour put budget). The cache keeps the edit and [sync]
            // sends it: it runs on boot, on the `vault_changed` nudge and on
            // every socket reconnect, and it pushes whenever folding the cache
            // into the island's copy would change the island's copy.
            Log.i("RCQsections", "write failed (${e.javaClass.simpleName}); the next sync will send it")
        }
    }

    /** The island's copy as a tree, or null when this build must not touch the
     *  slot: a `v` above 1, bytes that are not the tree, or a seal this
     *  identity cannot open. */
    private fun openTree(ctx: Ctx, slot: String, cur: RcqApi.VaultSlotRead): JsonObject? {
        val plain = try {
            cur.blob?.let { Vault.open(ctx.identityPriv, slot, cur.version, Base64.decode(it, Base64.NO_WRAP)) }
        } catch (e: Vault.BadSeal) {
            Log.w("RCQsections", "slot will not open for this identity; not syncing")
            return null
        }
        val tree = Sections.decode(plain)
        if (tree == null) Log.i("RCQsections", "slot is newer than this build; not syncing")
        return tree
    }

    // ── edges ────────────────────────────────────────────────────────────

    /**
     * The member key for a group row, or null when this device cannot name the
     * group in a way another device would recognise.
     *
     * ⚠⚠ A foreign group's [RcqGroup.id] on this device is the LOCAL ALIAS: a
     * negative number handed out in first-sight order by [VisitedIslandsStore],
     * different on every phone and in every browser. Putting one in the slot
     * would file this chat here and a different chat, or none, over there. The
     * slot only ever holds (remoteId, host), and this is the edge where that
     * translation happens.
     */
    fun keyForGroup(group: RcqGroup): String? {
        if (group.id >= 0) return Sections.groupKey(group.id)
        val ref = VisitedIslandsStore.refByAlias(group.id) ?: return null
        return Sections.groupKey(ref.remoteId, ref.host)
    }

    /**
     * Take a chat out of whatever section holds it, because it is going away on
     * THIS device on purpose: a contact removed, a group left, a cross-island
     * peer deleted. Writes the member tombstone in the same operation.
     *
     * ⚠ This is the only pruning there is. Nothing prunes because a chat failed
     * to render: one failed roster fetch would then empty the account's
     * sections everywhere.
     *
     * ⚠⚠ WRITE TIMING IS THE SIDE CHANNEL HERE, not the blob. This runs
     * directly after `DELETE /contacts/{uin}`, `POST /groups/{id}/leave` or the
     * cross-island equivalent. The island cannot read the slot, but it can read
     * its own request log: a delete followed within a moment by a put on this
     * account's second, rarely-written slot says "that uin was in one of their
     * sections", and a delete followed by nothing says the opposite. For the
     * common account whose only user section is the PIN-gated one, that
     * reconstructs the hidden membership one removal at a time, which is
     * exactly what sealing it was for.
     *
     * So the write is unconditional whenever the account has any user section
     * at all: a removal that changed nothing still stamps `w` (which merges by
     * `max` and costs the receiver nothing) and still puts. It is deferred as
     * well, so the put does not sit against the API call in the log. An account
     * with no user sections writes nothing and has nothing to hide.
     */
    fun forgetMember(ctx: Ctx?, key: String?) {
        if (key == null) return
        if (Sections.userSections(LocalStores.sections.value).isEmpty()) return
        runCatching {
            mutate(ctx, defer = true) { tree -> Sections.forgetMember(tree, key) ?: Sections.touchTree(tree) }
        }
    }
}
