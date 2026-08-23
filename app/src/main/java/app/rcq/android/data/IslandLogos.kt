package app.rcq.android.data

import android.content.Context
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The islands' own pictures: fetched once per version, then read off the disk.
 *
 * Same shape as an account avatar ([app.rcq.android.Session.fetchImage]): a
 * memory cache in front, a file cache behind it, and a network read only on a
 * miss of both. What is different, and why this is not that function, is that a
 * logo is PUBLIC PLAINTEXT: there is no media id, no AES key and nothing to
 * decrypt, so the pipeline is a GET and a write rather than a GET, a disk
 * write, and an open under a key that lives in the encrypted database.
 *
 * ⚠ KEYED ON HOST **AND** VERSION. An island's logo can be replaced by its
 * operator, and a cache keyed on the host alone would keep the old one until
 * the app was reinstalled. The version comes from `/server/info`
 * (`logo_version`, a digest of the picture), so a new logo is a new key, a new
 * URL and a new file; the stale file is swept by [trim] like any other.
 *
 * Nothing here can fail into a broken image. Every miss, every refusal and
 * every unreadable file resolves to null, and every caller
 * ([app.rcq.android.ui.IslandAvatar]) draws the lettered tile for null.
 */
object IslandLogos {

    /** A logo is at most 64 KB by the island's own cap, and a phone holds a
     *  handful of islands. This is a small map with a hard ceiling rather than
     *  an LRU sized off the heap: the whole store is smaller than one photo. */
    private const val MEM_CAP = 16
    private const val DISK_CAP_BYTES = 4L * 1024 * 1024

    /** How many silent islands are remembered as silent. Loose: an entry is a
     *  string, and the list is thrown away with the process. */
    private const val MISS_CAP = 64

    private val memory = LinkedHashMap<String, ByteArray>(MEM_CAP, 0.75f, true)

    /** Islands whose logo did not arrive this run, so a dead or logo-less
     *  island is not re-asked on every appearance of every row that names it.
     *
     *  ⚠ Without this an island that advertises a `logo_version` it will not
     *  serve gets a fresh unauthenticated GET on every recomposition of every
     *  row (the account switcher opening and closing, the manage-accounts
     *  list, Settings): a continuous heartbeat of this phone's address to a
     *  host that answered 500, paid for in battery and traffic. Memory only,
     *  and keyed like the caches, so a NEW version is a new question. iOS keeps
     *  the same list (`IslandLogoStore.missed`). */
    private val missed = LinkedHashSet<String>()

    private fun key(host: String, version: String) = "$host@$version"

    private fun dir(context: Context): File =
        File(context.applicationContext.cacheDir, "island-logos").apply { mkdirs() }

    private fun file(context: Context, host: String, version: String): File =
        File(dir(context), key(host, version).replace(Regex("[^A-Za-z0-9_@-]"), "_"))

    /**
     * What we already hold for this island, with no disk and no network.
     *
     * Read from a composition to seed the first frame: starting from null and
     * loading afterwards redraws the lettered tile on every appearance and
     * swaps the picture in a frame later, which is the flicker
     * [app.rcq.android.Session.cachedImage] exists to prevent for people.
     */
    @Synchronized
    fun cached(host: String?, version: String?): ByteArray? {
        if (host.isNullOrBlank() || version.isNullOrBlank()) return null
        return memory[key(host, version)]
    }

    /**
     * The island's logo, from memory, then disk, then the island.
     *
     * Null means "draw the tile", and that covers every case worth
     * distinguishing to a caller: the island has no logo, the island is too old
     * to have the field, the island did not answer, or the bytes did not
     * arrive. None of them is an error.
     */
    suspend fun load(context: Context, host: String?, version: String?): ByteArray? {
        if (host.isNullOrBlank() || version.isNullOrBlank()) return null
        cached(host, version)?.let { return it }
        val k = key(host, version)
        if (isMissed(k)) return null
        return withContext(Dispatchers.IO) {
            val got = runCatching {
                val f = file(context, host, version)
                val bytes = if (f.exists()) {
                    f.readBytes().takeIf { it.isNotEmpty() }
                } else {
                    // A one-off client for this host, like every other read
                    // against an island that is not the session's own. Media
                    // reads are unauthenticated, so no token of any account is
                    // involved either way.
                    RcqApi("https://$host", isPrimary = false).serverLogo(version)?.also {
                        runCatching { f.writeBytes(it); trim(context) }
                    }
                }
                bytes?.also { put(host, version, it) }
            }.getOrNull()
            if (got == null) markMissed(k)
            got
        }
    }

    @Synchronized
    private fun isMissed(k: String): Boolean = k in missed

    @Synchronized
    private fun markMissed(k: String) {
        missed.add(k)
        while (missed.size > MISS_CAP) {
            val oldest = missed.firstOrNull() ?: break
            missed.remove(oldest)
        }
    }

    @Synchronized
    private fun put(host: String, version: String, bytes: ByteArray) {
        memory[key(host, version)] = bytes
        while (memory.size > MEM_CAP) {
            val oldest = memory.keys.firstOrNull() ?: break
            memory.remove(oldest)
        }
    }

    /**
     * Forget every island's picture: the memory map, the silent-island list and
     * the files on disk.
     *
     * ⚠ The file names are HOSTS. Burning the last account on the device
     * removes that account's own record of the islands it visited
     * ([VisitedIslandsStore]), and leaving a directory named after the same
     * hosts behind would mean an app that reports everything erased while the
     * cache still lists every island this device ever drew, a private
     * self-hosted one included.
     */
    @Synchronized
    fun clear(context: Context) {
        memory.clear()
        missed.clear()
        runCatching { dir(context).deleteRecursively() }
    }

    /** Evict oldest files until under the cap. A replaced logo leaves its
     *  predecessor behind (different version, different name), so without this
     *  the directory would only ever grow. */
    private fun trim(context: Context) {
        runCatching {
            val files = dir(context).listFiles()?.toList() ?: return
            var total = files.sumOf { it.length() }
            if (total <= DISK_CAP_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= DISK_CAP_BYTES) break
                total -= f.length()
                f.delete()
            }
        }
    }
}
