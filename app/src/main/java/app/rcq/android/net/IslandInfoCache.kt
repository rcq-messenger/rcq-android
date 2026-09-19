package app.rcq.android.net

import app.rcq.android.data.LocalStores
import com.google.gson.Gson

/**
 * The island's own description, remembered across launches.
 *
 * `/server/info` is public, tiny and nearly static (a name, a welcome text, a
 * logo version, the entry rules). It used to live in a `mutableMapOf` inside
 * the settings screen, which means it lived and died with the process: the
 * first visit to Settings after every launch painted the island block empty
 * and filled it in a round trip later, so the screen visibly jumped (report
 * #1026, with a screenshot).
 *
 * Two layers, on purpose. The map answers instantly and holds the freshest
 * copy this process has seen; the preference survives a restart so a COLD
 * first visit paints from the last known description instead of from nothing.
 * Both are caches of a public document: a stale name is a cosmetic wrong for
 * one frame, never a trust decision, and nothing here is ever used to decide
 * where to register, what certificate to accept or whom to trust.
 */
object IslandInfoCache {

    private val gson = Gson()
    private val memory = mutableMapOf<String, RcqApi.ServerInfoResponse>()

    private fun key(host: String) = host.trim().lowercase()

    /** The best answer available WITHOUT a network call. Null only when this
     *  install has never heard from [host]. */
    fun peek(host: String): RcqApi.ServerInfoResponse? {
        val k = key(host)
        memory[k]?.let { return it }
        val stored = LocalStores.islandInfoJson(k) ?: return null
        val parsed = runCatching { gson.fromJson(stored, RcqApi.ServerInfoResponse::class.java) }.getOrNull()
        // A parse failure means the shape changed under a stored copy. Treat it
        // as "never heard from" rather than throwing at a screen: the refresh
        // below overwrites it with something this build understands.
        if (parsed != null) memory[k] = parsed
        return parsed
    }

    /** Remember an answer somebody else already paid for. The boot path asks
     *  `/server/info` for the capability flags anyway (Session.refreshCaps), so
     *  handing that answer here is what makes the FIRST settings visit of a
     *  launch instant instead of merely the second one. */
    fun put(host: String, info: RcqApi.ServerInfoResponse) {
        val k = key(host)
        memory[k] = info
        runCatching { LocalStores.setIslandInfoJson(k, gson.toJson(info)) }
    }

    /** The island's headcount, last known. A separate endpoint from
     *  `/server/info` (`/public/stats`), and the FIRST row of the island
     *  block, so it is the one whose late arrival pushes every row below it
     *  down — the jitter in #1026. Absent, never zero: a headcount of 0 on an
     *  island you are logged into is a lie, and a missing row is not. */
    fun peekPeople(host: String): Int? =
        LocalStores.islandPeople(key(host))?.takeIf { it > 0 }

    suspend fun refreshPeople(host: String): Int? {
        val k = key(host)
        val n = RcqApi.islandPeople(k) ?: return null
        runCatching { LocalStores.setIslandPeople(k, n) }
        return n
    }

    /** Ask the island, and remember the answer in both layers. Returns null
     *  when the island does not answer, leaving any cached copy in place. */
    suspend fun refresh(host: String): RcqApi.ServerInfoResponse? {
        val k = key(host)
        val fresh = RcqApi.serverInfoOf(k) ?: return null
        put(k, fresh)
        return fresh
    }
}
