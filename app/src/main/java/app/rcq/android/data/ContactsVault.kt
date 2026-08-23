package app.rcq.android.data

import android.util.Base64
import app.rcq.android.crypto.Vault
import app.rcq.android.model.Contact
import app.rcq.android.net.RcqApi
import com.google.gson.Gson

/**
 * The contact list in the vault (stage 4 of the metadata plan, client half;
 * web `src/lib/contacts-vault.ts`, same slot, same JSON).
 *
 * Today's phase, "mirror": the island's `contacts` table is still what every
 * client (including the ones that have not updated) adds to and removes
 * from, so the server list is the truth and the vault holds a sealed copy of
 * it. Every successful `/contacts` fetch on an island that advertises `vault`
 * is folded into the account's `contacts` slot: entries the server has and
 * the slot does not are added, entries the server no longer has become
 * tombstones, and nothing is written when the slot already says the same.
 *
 * Why bother before the table goes: the moment the island stops answering
 * `/contacts` (the read-only and drop steps of the plan), a reinstall or a new
 * device recovers its roster from this slot and from nowhere else, and a
 * client that shipped today already keeps the copy current.
 *
 * ⚠ Server-wins is the rule of THIS phase only. A merge that let the slot
 * re-add what an old client removed on the island would resurrect deleted
 * contacts on every device; a merge that let the slot remove what the island
 * still has would drop contacts the other phone can see.
 *
 * ⚠⚠ The #605 rule: never write the slot from local state alone. [mirror]
 * reads, folds, writes with the version it read, and goes around on a stale
 * answer. The island's nudge (`vault_changed`) has no replay, so a re-read
 * happens on every roster refresh anyway, which in this phase is enough.
 */
object ContactsVault {
    /** One edge. Field names are the wire's (one byte each, the slot is
     *  padded to 512-byte classes and a short key keeps more entries in a
     *  class): a = added ms, u = updated ms, b = 1 when blocked, n = last
     *  nickname seen, h = home island host for a cross-island peer. */
    data class Entry(val a: Long, val u: Long, val b: Int? = null, val n: String? = null, val h: String? = null)
    data class Blob(val v: Int = 1, val c: Map<String, Entry> = emptyMap(), val g: Map<String, Long> = emptyMap())

    private const val TOMBSTONE_TTL_MS = 90L * 24 * 3600 * 1000
    private val gson = Gson()

    sealed class Outcome {
        object Written : Outcome()
        object Unchanged : Outcome()
        object Skipped : Outcome()
        /** The island served a version BELOW the floor this install has seen.
         *  The caller stops mirroring for the rest of the session; see the
         *  note in [mirror]. */
        object RolledBack : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    /** Fold the server's list into the slot. Never throws: the roster is on
     *  screen already and a vault that is down is not the user's problem at
     *  that moment. */
    /** [stillOurs] is asked after every await: an account switch mid-flight
     *  rebinds the stores, and neither the floor nor the slot may then be
     *  touched on behalf of the account the list belonged to. */
    suspend fun mirror(api: RcqApi, identityPriv: ByteArray, list: List<Contact>, now: Long = System.currentTimeMillis(), stillOurs: () -> Boolean = { true }): Outcome {
        val slot = Vault.slotId(identityPriv, Vault.CONTACTS)
        var floor = LocalStores.vaultContactsVersion()
        return try {
            repeat(5) {
                val cur = api.vaultGet(slot)
                if (!stillOurs()) return Outcome.Skipped
                if (cur.version < floor) {
                    // ⚠⚠ The island served an older version than this install
                    // has seen, and the FLOOR IS NOT CLEARED. This line used to
                    // clear it and rewrite the whole list, on the reasoning
                    // that in the mirror phase the server list is the truth
                    // anyway. It is not safe: the island cannot tell "restored
                    // from a backup" apart from "your derivation was retired by
                    // POST /auth/reissue", and in the second case rewriting
                    // republishes the entire contact list, sealed with the key
                    // the user has just declared compromised, under a slot name
                    // that will never be read again. Stop the mirror for this
                    // session, keep what is on screen, say so. Same rule as the
                    // sections slot (design 23.08 §2.2).
                    return Outcome.RolledBack
                }
                val remote = cur.blob?.let { decode(Vault.open(identityPriv, slot, cur.version, Base64.decode(it, Base64.NO_WRAP))) } ?: Blob()
                val next = fold(remote, list, now)
                if (next == null) {
                    LocalStores.setVaultContactsVersion(cur.version)
                    return Outcome.Unchanged
                }
                val sealed = Vault.seal(identityPriv, slot, cur.version + 1, gson.toJson(next).toByteArray(Charsets.UTF_8))
                val w = api.vaultPut(slot, Base64.encodeToString(sealed, Base64.NO_WRAP), cur.version)
                if (!stillOurs()) return Outcome.Skipped
                if (w.version != null) {
                    LocalStores.setVaultContactsVersion(w.version)
                    return Outcome.Written
                }
                // Stale: somebody else's write landed between our read and ours.
                floor = maxOf(floor, w.current)
            }
            Outcome.Failed("conflict loop")
        } catch (e: Vault.BadSeal) {
            // A blob this identity cannot open: another account's, a newer
            // format, or damage. Not ours to overwrite blindly in this phase;
            // the next phase decides. Leave it and say so.
            Outcome.Failed("bad seal")
        } catch (e: Exception) {
            Outcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The roster as the vault has it, for a device that has nothing else
     *  once the island stops serving `/contacts`. Null when empty or unreadable. */
    suspend fun read(api: RcqApi, identityPriv: ByteArray): Blob? {
        val slot = Vault.slotId(identityPriv, Vault.CONTACTS)
        val cur = api.vaultGet(slot)
        if (cur.version < LocalStores.vaultContactsVersion()) return null
        val blob = cur.blob ?: return null
        val out = decode(Vault.open(identityPriv, slot, cur.version, Base64.decode(blob, Base64.NO_WRAP)))
        LocalStores.setVaultContactsVersion(cur.version)
        return out
    }

    private fun decode(p: ByteArray): Blob =
        runCatching { gson.fromJson(String(p, Charsets.UTF_8), Blob::class.java) }
            .getOrNull()?.takeIf { it.v == 1 } ?: Blob()

    /** Pure: the slot after folding the server list in, or null when nothing
     *  would change. Same rules as the web's foldServerList. */
    fun fold(cur: Blob, list: List<Contact>, now: Long): Blob? {
        val c = cur.c.toMutableMap()
        val g = cur.g.toMutableMap()
        var changed = false
        val onServer = HashSet<String>()
        for (ct in list) {
            val k = ct.uin.toString()
            onServer.add(k)
            val prev = c[k]
            var entry = Entry(
                a = prev?.a ?: now,
                u = prev?.u ?: now,
                b = if (ct.blocked) 1 else null,
                n = ct.nickname.takeIf { it.isNotEmpty() },
                h = ct.host?.takeIf { it.isNotEmpty() },
            )
            if (prev == null || !same(prev, entry)) {
                entry = entry.copy(u = now)
                c[k] = entry
                changed = true
            }
            if (g.remove(k) != null) changed = true
        }
        for (k in c.keys.toList()) {
            if (k !in onServer) {
                c.remove(k)
                g[k] = now
                changed = true
            }
        }
        for ((k, t) in g.entries.toList()) {
            if (now - t > TOMBSTONE_TTL_MS) {
                g.remove(k)
                changed = true
            }
        }
        return if (changed) Blob(1, c, g) else null
    }

    private fun same(x: Entry, y: Entry) =
        (x.b ?: 0) == (y.b ?: 0) && (x.n ?: "") == (y.n ?: "") && (x.h ?: "") == (y.h ?: "")
}
