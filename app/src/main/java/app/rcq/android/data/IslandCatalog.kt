package app.rcq.android.data

import android.content.Context
import app.rcq.android.net.RcqApi
import app.rcq.android.net.SingBoxTransport
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The published island catalogue, and the artwork the site draws it with.
 *
 * Two separate things behind one object because they are always wanted
 * together: the LIST (who is out there, from `servers.json`) and the PICTURE
 * for each row (`/islands/island-N.png`, the same nine paintings fed.rcq.app
 * floats in its hero). An island's own LOGO is a third thing and already has a
 * home in [IslandLogos]; the picker draws the logo ON the painting.
 *
 * ⚠ DISPLAY ONLY, and this is the whole security story. The list that decides
 * where the app may fall back on a blocked network is a SIGNED file handled in
 * [app.rcq.android.net.Multihome]; nothing here can steer it. This one only
 * ever fills in a host that a person then picks on purpose, which is why an
 * unsigned catalogue is acceptable here and would not be there. Rows that are
 * not `https://` are dropped rather than shown, so a tampered catalogue cannot
 * even offer a plaintext island to tap.
 */
object IslandCatalog {

    /** The island every build points at with no choice made. Always first in
     *  the list, always painted with island-1 (the green "home" one), the same
     *  invariant fed.rcq.app keeps for the flagship in its hero. */
    const val FLAGSHIP_HOST = RcqApi.DEFAULT_HOST

    private const val CATALOGUE_URL = "https://rcq.app/servers.json"
    private const val ART_BASE = "https://rcq.app/islands/island-"
    /** How long a fetched catalogue is good for. Islands are added by hand,
     *  a few times a year: this is about not asking twice in one sitting. */
    private const val TTL_MS = 6L * 60 * 60 * 1000
    private const val ART_CAP_BYTES = 3L * 1024 * 1024

    data class Entry(
        val host: String,
        val name: String,
        val description: String? = null,
        val region: String? = null,
        /// The island's logo, MIRRORED ON THE SITE rather than fetched from the
        /// island itself. An operator's logo lives at `<island>/server/logo`,
        /// and reading it from here would hand this device's address to every
        /// island in the catalogue the moment somebody opened the picker,
        /// including the ones they scroll past and never join. The catalogue and
        /// the paintings already come from rcq.app; one more file from the same
        /// host tells nobody anything new.
        val logoUrl: String? = null,
    )

    private var memory: List<Entry>? = null
    private var fetchedAt = 0L

    private fun http(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
        SingBoxTransport.proxy()?.let { b.proxy(it) }
        return b.build()
    }

    private fun cacheFile(context: Context): File =
        File(context.applicationContext.cacheDir, "island-catalogue.json")

    private fun artDir(context: Context): File =
        File(context.applicationContext.cacheDir, "island-art").apply { mkdirs() }

    /** The list as we last held it, with no disk and no network, so a sheet
     *  opens on content instead of on a spinner. */
    @Synchronized
    fun cached(): List<Entry>? = memory

    /**
     * The catalogue: memory, then the file we wrote last time, then the site.
     *
     * Never empty and never throwing. A phone that has never reached the site,
     * or is on a network where it is blocked, still gets the flagship, because
     * "no islands at all" is a screen that reads as breakage when the truth is
     * that the app works fine and simply has nothing else to offer.
     */
    suspend fun load(context: Context, force: Boolean = false): List<Entry> {
        val held = synchronized(this) { memory }
        if (!force && held != null && System.currentTimeMillis() - fetchedAt < TTL_MS) return held
        return withContext(Dispatchers.IO) {
            val fromNet = runCatching {
                val req = Request.Builder().url(CATALOGUE_URL).header("Cache-Control", "no-cache").get().build()
                http().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.string()
                }
            }.getOrNull()
            val text = fromNet ?: runCatching { cacheFile(context).takeIf { it.exists() }?.readText() }.getOrNull()
            val parsed = text?.let { parse(it) }.orEmpty()
            val list = if (parsed.isEmpty()) listOf(Entry(FLAGSHIP_HOST, "RCQ")) else parsed
            if (fromNet != null && parsed.isNotEmpty()) {
                runCatching { cacheFile(context).writeText(fromNet) }
            }
            synchronized(this@IslandCatalog) {
                memory = list
                if (parsed.isNotEmpty()) fetchedAt = System.currentTimeMillis()
            }
            list
        }
    }

    /** Flagship first, then the file's own order (the site keeps it deliberate). */
    private fun parse(text: String): List<Entry> = runCatching {
        val root = JsonParser.parseString(text)
        val arr = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.has("servers") -> root.asJsonObject.getAsJsonArray("servers")
            else -> return@runCatching emptyList()
        }
        val out = ArrayList<Entry>()
        for (el in arr) {
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val url = o.get("url")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            if (!url.startsWith("https://")) continue
            val host = url.removePrefix("https://").trimEnd('/').lowercase()
            if (host.isEmpty()) continue
            out.add(
                Entry(
                    host = host,
                    name = o.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifEmpty { host },
                    description = o.get("description")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                    region = o.get("region")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                    logoUrl = o.get("logo")?.takeIf { !it.isJsonNull }?.asString?.trim()
                        ?.takeIf { it.startsWith("https://") },
                ),
            )
        }
        val flagship = out.firstOrNull { it.host == FLAGSHIP_HOST }
        val rest = out.filter { it.host != FLAGSHIP_HOST }
        if (flagship != null) listOf(flagship) + rest else listOf(Entry(FLAGSHIP_HOST, "RCQ")) + rest
    }.getOrDefault(emptyList())

    /**
     * Which of the nine paintings belongs to a host.
     *
     * ⚠ Stable, and stable ACROSS CLIENTS: FNV-1a over the host, never
     * [String.hashCode], which is fine on the JVM but is not the number iOS or
     * the web would compute. An island that looks like one picture on the phone
     * and another on the desktop reads as two different islands.
     */
    fun artIndex(host: String): Int {
        if (host.equals(FLAGSHIP_HOST, ignoreCase = true)) return 1
        var h = 2166136261u
        for (b in host.lowercase().toByteArray()) {
            h = (h xor (b.toInt() and 0xFF).toUInt()) * 16777619u
        }
        return 2 + (h % 8u).toInt()   // 2..9; island-1 belongs to the flagship
    }

    /** The painting for a host: disk first, then the site. Null draws nothing,
     *  and the picker falls back to a plain tinted card, which is a card and
     *  not a hole. */
    suspend fun art(context: Context, host: String): ByteArray? = withContext(Dispatchers.IO) {
        val n = artIndex(host)
        val f = File(artDir(context), "island-$n.png")
        runCatching {
            if (f.exists()) return@runCatching f.readBytes().takeIf { it.isNotEmpty() }
            val req = Request.Builder().url("$ART_BASE$n.png").get().build()
            http().newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val bytes = r.body?.bytes() ?: return@runCatching null
                if (bytes.size > ART_CAP_BYTES) return@runCatching null
                f.writeBytes(bytes)
                trimArt(context)
                bytes
            }
        }.getOrNull()
    }

    /** An island's mirrored logo, cached beside the paintings. Null draws the
     *  lettered tile, which is also what an island with no logo at all gets. */
    suspend fun logo(context: Context, entry: Entry): ByteArray? = withContext(Dispatchers.IO) {
        val url = entry.logoUrl ?: return@withContext null
        val f = File(artDir(context), "logo-" + entry.host.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")
        runCatching {
            if (f.exists()) return@runCatching f.readBytes().takeIf { it.isNotEmpty() }
            val req = Request.Builder().url(url).get().build()
            http().newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val bytes = r.body?.bytes() ?: return@runCatching null
                if (bytes.size > 256 * 1024) return@runCatching null
                f.writeBytes(bytes)
                bytes
            }
        }.getOrNull()
    }

    /** The whole set is nine files of about 150 KB; this only ever matters if
     *  the site starts serving something bigger than it says. */
    private fun trimArt(context: Context) = runCatching {
        val files = artDir(context).listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= ART_CAP_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
