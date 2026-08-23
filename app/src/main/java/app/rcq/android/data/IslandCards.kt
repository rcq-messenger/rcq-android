package app.rcq.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What an island calls itself and which logo it is on, kept across restarts.
 *
 * Both come from `/server/info`, which is a network call, and both are drawn in
 * places that have to be complete on the FIRST frame: the account switcher, the
 * account manager, the island card in Settings. Without this every one of them
 * showed a lettered tile and a bare hostname for as long as the request took,
 * on every cold start.
 *
 * Same division [AccountCards] makes for a face, and the same one iOS makes
 * (`AccountCardCache`) and the desktop makes (`web-chat/src/lib/island-card.ts`,
 * whose comment is the shortest version: "there is nothing to fetch").
 *
 * ⚠ KEYED BY ISLAND, NOT BY ACCOUNT, which is the one thing that differs from
 * [AccountCards]. An island's public name and its logo are the same for
 * everybody on it: two accounts on one host share one entry, and an account we
 * are NOT signed into still has its island drawn properly, because some other
 * account on the same island filled the entry in. Nothing here is a secret
 * (it is what `/server/info` hands to any anonymous caller), which is also why
 * it may live in plain preferences rather than in an account's sealed stores,
 * and why the switcher can read it while no account is unlocked.
 *
 * ⚠ Nothing is written in a decoy session. The callers guard, exactly as they
 * do for [AccountCards]: a decoy session must leave nothing on disk at all.
 */
object IslandCards {

    /** One island's public description, as of the last time anything on this
     *  device talked to it. */
    data class Card(
        /** What the island calls itself. Empty when it has never answered or
         *  its operator left the field blank; callers fall back to the host. */
        val name: String = "",
        /** Digest of its logo; empty means it has none and the caller draws the
         *  lettered tile. Rides on the picture's URL as `?v=`, so a changed
         *  logo is a changed URL and a changed cache key. */
        val logoVersion: String = "",
    )

    private const val FILE = "rcq_island_cards"
    private const val K_HOSTS = "hosts"
    private const val K_NAME = "name"
    private const val K_LOGO = "logo"

    private val _cards = MutableStateFlow<Map<String, Card>>(emptyMap())

    /** Every known island, keyed by host. A flow rather than a plain map so a
     *  screen redraws the moment an island answers with a new name or a new
     *  logo, without anyone having to remember to poke it. */
    val cards: StateFlow<Map<String, Card>> = _cards.asStateFlow()

    private var prefs: SharedPreferences? = null

    /** Load from disk into memory. Idempotent and cheap (a handful of islands),
     *  and safe from a composition: it is the same kind of preferences read
     *  [AccountCards.warm] already does on the way to drawing the switcher. */
    fun warm(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val out = HashMap<String, Card>()
        for (host in p.getStringSet(K_HOSTS, emptySet()).orEmpty()) {
            out[host] = Card(
                name = p.getString("$host.$K_NAME", "").orEmpty(),
                logoVersion = p.getString("$host.$K_LOGO", "").orEmpty(),
            )
        }
        _cards.value = out
    }

    /** One island's card, or null when nothing on this device has talked to it.
     *  Memory only: no disk, no network, no island. */
    fun cardFor(host: String?): Card? = host?.takeIf { it.isNotBlank() }?.let { _cards.value[it.lowercase()] }

    /**
     * Remember what an island answered on `/server/info`.
     *
     * Write-through and idempotent: an unchanged card touches neither the map
     * nor the disk, so this can be driven straight off every boot's server-info
     * read without turning it into a preferences write.
     *
     * ⚠ A BLANK NAME DOES NOT OVERWRITE A KNOWN ONE, the same guard
     * [AccountCards.record] puts on a picture and iOS puts on
     * `AccountCardCache.record`. A half-known answer (an island momentarily
     * serving an empty name) would otherwise wipe the name the switcher has
     * been drawing and the screen would lose it for a beat. A blank
     * [logoVersion] is a different matter and IS believed: that is the operator
     * REMOVING the logo, and a removal has to take effect.
     */
    fun record(context: Context, host: String?, name: String?, logoVersion: String?) {
        val h = host?.takeIf { it.isNotBlank() }?.lowercase() ?: return
        warm(context)
        val p = prefs ?: return
        val held = _cards.value[h]
        val next = Card(
            name = name?.takeIf { it.isNotBlank() } ?: held?.name.orEmpty(),
            logoVersion = logoVersion.orEmpty(),
        )
        if (held == next) return
        _cards.value = _cards.value + (h to next)
        val hosts = p.getStringSet(K_HOSTS, emptySet()).orEmpty() + h
        p.edit()
            // A fresh set instance: SharedPreferences hands back the live one
            // and silently ignores a mutation of it (the same trap LocalStores
            // documents on its own string-set writes).
            .putStringSet(K_HOSTS, hosts.toSet())
            .putString("$h.$K_NAME", next.name)
            .putString("$h.$K_LOGO", next.logoVersion)
            .apply()
    }

    /**
     * Forget every island.
     *
     * ⚠ Keyed by host, so this is not an account's data and it is NOT cleared
     * on an ordinary burn: the accounts that remain on the device still need
     * their islands drawn. It is cleared when the LAST account goes, because at
     * that point the file is a plaintext list of every island this device ever
     * talked to, sitting next to the per-account record of the same islands
     * ([VisitedIslandsStore]) that the burn just deleted. [IslandLogos.clear]
     * is the same list in pictures and goes with it.
     */
    fun wipe(context: Context) {
        warm(context)
        _cards.value = emptyMap()
        prefs?.edit()?.clear()?.apply()
    }
}
