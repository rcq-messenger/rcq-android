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
        /** What this island calls its badges, keyed by kind, as
         *  `label|description|color` (any part may be empty).
         *
         *  ⚠ Here rather than in an account's stores for the same reason the
         *  name is: a badge drawn beside a contact's nickname has to be right
         *  on the FIRST frame, and it is public text `/server/info` hands to
         *  any anonymous caller. Flattened into one string per kind because
         *  SharedPreferences has no nested maps and this is three short fields.
         *  `|` is not in a hex colour and is stripped from the other two on the
         *  way in, so it cannot be smuggled in to forge a field boundary. */
        val badges: Map<String, BadgeText> = emptyMap(),
    )

    /** One badge's words, as its island says them. */
    data class BadgeText(val label: String = "", val description: String = "", val color: String = "")

    private const val FILE = "rcq_island_cards"
    private const val K_HOSTS = "hosts"
    private const val K_NAME = "name"
    private const val K_LOGO = "logo"
    private const val K_BADGES = "badges"

    /** An island's text, bounded before it is believed. It is drawn beside a
     *  contact's name, so an operator (or an island we merely PROBED) must not
     *  be able to push a paragraph into a roster row, and `|` cannot be
     *  smuggled in to forge a field boundary in the flattened form. */
    private const val LABEL_MAX = 32
    private const val DESC_MAX = 200

    private fun clean(s: String?, max: Int): String =
        s.orEmpty().replace("|", " ").trim().take(max)

    private fun flatten(b: Map<String, BadgeText>): Set<String> =
        b.entries.map { (k, v) -> "$k|${v.label}|${v.description}|${v.color}" }.toSet()

    private fun unflatten(raw: Set<String>): Map<String, BadgeText> {
        val out = HashMap<String, BadgeText>()
        for (line in raw) {
            val p = line.split("|")
            if (p.size < 4 || p[0].isBlank()) continue
            out[p[0]] = BadgeText(p[1], p[2], p[3])
        }
        return out
    }

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
                badges = unflatten(p.getStringSet("$host.$K_BADGES", emptySet()).orEmpty()),
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
    /** The island this device is signed into right now, so a badge drawn in a
     *  roster row can find its island's words without every call site having
     *  to be handed a host. Set by [Session] from the same `/server/info` read
     *  that fills the card in; null before the first one answers, and the
     *  badge falls back to the built-in strings.
     *
     *  ⚠ Memory only, deliberately. It is the CURRENT session's island, and a
     *  decoy session must not leave one behind for the real one to pick up. */
    @Volatile private var active: String? = null

    fun markActive(host: String?) { active = host?.takeIf { it.isNotBlank() }?.lowercase() }

    fun activeHost(): String? = active

    /** This island's word for one badge kind, or null to use the built-in.
     *  Memory only: no disk, no network, no island, so it is safe to call from
     *  a list row that redraws on every frame. */
    fun badgeText(host: String?, kind: String?): BadgeText? {
        if (kind.isNullOrBlank()) return null
        return cardFor(host)?.badges?.get(kind)
    }

    fun record(context: Context, host: String?, name: String?, logoVersion: String?) =
        record(context, host, name, logoVersion, null)

    fun record(
        context: Context,
        host: String?,
        name: String?,
        logoVersion: String?,
        badges: Map<String, BadgeText>?,
    ) {
        val h = host?.takeIf { it.isNotBlank() }?.lowercase() ?: return
        warm(context)
        val p = prefs ?: return
        val held = _cards.value[h]
        val next = Card(
            name = name?.takeIf { it.isNotBlank() } ?: held?.name.orEmpty(),
            logoVersion = logoVersion.orEmpty(),
            // ⚠ null is "the caller did not ask about badges" and keeps what we
            // hold; an EMPTY MAP is the island saying it has renamed nothing,
            // and that has to take effect, or an operator could never undo a
            // rename. Same split the name and the logo already make.
            badges = badges?.mapValues {
                BadgeText(
                    clean(it.value.label, LABEL_MAX),
                    clean(it.value.description, DESC_MAX),
                    clean(it.value.color, 16),
                )
            } ?: held?.badges.orEmpty(),
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
            .putStringSet("$h.$K_BADGES", flatten(next.badges))
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
