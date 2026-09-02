package app.rcq.android.sites

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The sites this device opened last, newest first, for the browser's start
 * screen (founder, 02.09): a reader who found a site once should not have to
 * remember its spelling to find it again.
 *
 * Keyed `name@host` like [SitePins], never by the string somebody typed:
 * `blog.rcq` opened on the flagship and `blog.flagship.rcq` are one site and
 * make one row. What is stored is the identity plus the title the manifest
 * carried, and the address shown is rebuilt for the island the reader is on
 * now ([SiteAddress.of]), so a row never points at a different site after an
 * account switch than the one that was opened.
 *
 * ⚠ Per DEVICE, not per account, for the same reason the pins are: reading a
 * site carries no identity, so there is no account for a visit to belong to.
 * It shares the pins' preferences file, and the panic wipe clears that file
 * whole ([SitePins.wipeAll]), so a wiped phone forgets these with the keys.
 */
object SiteRecents {

    data class Entry(val name: String, val host: String, val title: String?, val at: Long) {
        val key: String get() = "$name@$host"
    }

    private const val PREFS = "rcq_sites"
    private const val K_RECENTS = "recents.v1"
    private const val MAX = 10

    private val gson = Gson()
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) {
            prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** Newest first, at most [MAX]. */
    fun list(): List<Entry> = read()

    /** A page of [addr] was opened: put it on top, and let the oldest fall
     *  off the end. The title is refreshed on every visit, since a site may
     *  have been renamed since the last one. */
    fun touch(addr: SiteAddress, title: String?) {
        val now = Entry(addr.name, addr.host, title?.takeIf { it.isNotBlank() }, System.currentTimeMillis())
        write(listOf(now) + read().filter { it.key != now.key }.take(MAX - 1))
    }

    fun remove(entry: Entry) {
        write(read().filter { it.key != entry.key })
    }

    private fun read(): List<Entry> {
        val raw = prefs?.getString(K_RECENTS, "[]") ?: return emptyList()
        val type = object : TypeToken<List<Entry>>() {}.type
        return runCatching { gson.fromJson<List<Entry>>(raw, type) }.getOrNull()
            ?.filter { it.name.isNotEmpty() && it.host.isNotEmpty() }
            ?.sortedByDescending { it.at }
            ?.take(MAX)
            ?: emptyList()
    }

    private fun write(entries: List<Entry>) {
        prefs?.edit()?.putString(K_RECENTS, gson.toJson(entries))?.apply()
    }
}
