package app.rcq.android.sites

import android.content.Context
import app.rcq.android.net.SingBoxTransport
import app.rcq.android.net.islandTrust
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Reading a `.rcq` site: fetch, verify, hash-check, sanitise.
 *
 * ## The one thing to keep
 *
 * ⚠⚠ These requests carry NO authentication header, and that is the feature
 * rather than an oversight. A token would let the island build a record of who
 * read what — the reading side of the metadata this project has spent months
 * removing from everything else. The client that asks for a page is a stranger
 * to the island every time, and the island cannot lie about the bytes anyway,
 * because [SiteManifest] carries a signature the island did not make.
 *
 * The address was resolved on this device ([SiteAddress]), so the request goes
 * to the island that hosts the site and NEVER through the reader's own island:
 * proxying would hand its operator a journal of what its users read elsewhere.
 *
 * Mirrors `web-chat/src/lib/sites.ts` (`fetchSitePage`, `fetchFile`,
 * `fetchSiteIcon`, `fetchCatalogue`).
 */
object SitesRepository {

    /**
     * A page, ready for whatever draws it.
     *
     * There is no UI in this file on purpose: the screen decides how a banner
     * looks, this decides what is true.
     */
    data class SitePage(
        /** Assets inlined, everything outward removed, self-contained. */
        val html: String,
        /** Which file of the bundle this is. */
        val path: String,
        /** Every page of the bundle, so the reader can move between them
         *  without a single script running inside the frame. */
        val pages: List<String>,
        val version: Int,
        val key: String,
        /** We had a DIFFERENT key pinned for this name. Trust on first use, the
         *  same rule as safety numbers: the island may serve other bytes, it
         *  may not pass them off as the same site. The page is still rendered —
         *  a reader who cannot see it cannot judge it — with a banner. */
        val keyChanged: Boolean,
        val title: String?,
    )

    /** The site's mark, verified the same way a page is: the manifest signature
     *  covers its hash and the bytes are checked against it. Raster bytes for
     *  the same decoder that draws every avatar — never SVG, see
     *  [SiteManifest.iconPathOf]. */
    class SiteMark(val path: String, val mime: String, val bytes: ByteArray)

    /**
     * Marks already fetched and checked this process, keyed `name@host`. A
     * catalogue redraws often and a mark is the same bytes every time.
     *
     * ⚠ Bounded, unlike web-chat's map: these are image BYTES, and a reader
     * who walks a few islands' catalogues would otherwise pin every mark they
     * ever saw in memory for the life of the process.
     */
    private val marks: MutableMap<String, SiteMark?> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SiteMark?>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SiteMark?>) = size > 32
        }
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        // A site lives on an island, and an island may be trusted by its
        // fingerprint rather than a CA (design §6). Nothing else is added:
        // see the note in [get] on what this client must NOT carry.
        .islandTrust()
        .build()

    /** Ride the sing-box tunnel when it is up, exactly as the signed relay
     *  config does: a censored reader has to reach islands too, and there is no
     *  identity in one of these requests to leak into the tunnel. */
    private fun http(): OkHttpClient =
        SingBoxTransport.proxy()?.let { client.newBuilder().proxy(it).build() } ?: client

    /**
     * What the address bar typed into it: an address or [SiteError.Address],
     * and in the second case nothing has been sent anywhere.
     */
    fun address(raw: String, ownHost: String): SiteAddress =
        SiteAddress.parse(raw, ownHost) ?: throw SiteError.Address

    /**
     * Open a page of a site.
     *
     * [fresh] is the reload button: a bundle is served with a short cache,
     * which is right for reading and wrong for somebody who just republished
     * and wants to see it.
     */
    suspend fun page(
        ctx: Context,
        addr: SiteAddress,
        path: String = "index.html",
        fresh: Boolean = false,
    ): SitePage = withContext(Dispatchers.IO) {
        // Before anything is verified, so a screen cannot skip the pin store by
        // forgetting to initialise it and silently lose every key warning.
        SitePins.init(ctx)
        val m = manifest(addr, fresh)
        val raw = file(addr, m, path, fresh)
        val html = SiteSanitizer.render(SiteSanitizer.decodeDeclared(raw), path, m.files.keys, ::door) { p ->
            // A stylesheet or image that is absent or unreachable costs its own
            // element and not the page. A hash MISMATCH is different and is
            // rethrown: that is the island serving bytes the owner did not
            // sign, and half a page assembled from those is not a page worth
            // showing.
            try {
                file(addr, m, p, fresh)
            } catch (e: SiteError) {
                if (e is SiteError.Tampered) throw e else null
            }
        }
        SitePage(
            html = html,
            path = path,
            pages = m.pages(),
            version = m.version,
            key = m.key,
            keyChanged = SitePins.pin(addr, m.key),
            title = m.title,
        )
    }

    /**
     * The `href` a link inside a page gets when it stays inside the network,
     * and null — no `href`, dead text — for everything else.
     *
     * The WebView runs no scripts, so a tap on an anchor is the only thing a
     * page can say to our chrome, and an anchor without an `href` says
     * nothing. These two private schemes are that channel: the reader's
     * `shouldOverrideUrlLoading` swallows every navigation and acts on these
     * two alone, so the frame still loads nothing and goes nowhere. A page of
     * the same bundle opens that page; a `.rcq` address, bare or with a
     * scheme, opens that site. An outward link is exactly as dead as before
     * (founder, 02.09: the web outside is not decided, and is not opened).
     */
    private fun door(page: String?, external: String?): String? = when {
        page != null -> DOOR_PAGE + page
        external != null -> SiteAddress.linkOf(external)?.let { l ->
            DOOR_SITE + l.address + (l.page?.let { "/$it" } ?: "")
        }
        else -> null
    }

    /** A page of the bundle being read: `rcq-page:<path>`. */
    const val DOOR_PAGE = "rcq-page:"

    /** Another site: `rcq-site:<address>[/<page>]`, in the form [SiteAddress.linkOf] reads back. */
    const val DOOR_SITE = "rcq-site:"

    /**
     * Fetch the manifest and check the owner's signature over it.
     *
     * Note what this does NOT do: it does not pin. Only opening a page anchors
     * a key, so drawing a catalogue of marks cannot quietly commit a reader to
     * a key for a site they never opened.
     */
    suspend fun manifest(addr: SiteAddress, fresh: Boolean = false): SiteManifest =
        withContext(Dispatchers.IO) {
            val bytes = get(url(addr, "manifest.json"), fresh)
            SiteManifest.parse(bytes, addr.name)
        }

    /**
     * The site's mark, or null when it has none or when anything about it does
     * not check out. A mark we cannot verify is not drawn at all: it is what a
     * site looks like in a list, and an island that could choose it could dress
     * one site up as another.
     */
    suspend fun mark(addr: SiteAddress, fresh: Boolean = false): SiteMark? =
        withContext(Dispatchers.IO) {
            val cacheKey = addr.pinKey
            if (fresh) marks.remove(cacheKey)
            if (marks.containsKey(cacheKey)) return@withContext marks[cacheKey]
            val found = try {
                val m = manifest(addr, fresh)
                val path = m.iconPathOf()
                val mime = path?.let { mimeOf(it) }
                if (path != null && mime != null) SiteMark(path, mime, file(addr, m, path, fresh)) else null
            } catch (e: SiteError) {
                null
            }
            marks[cacheKey] = found
            found
        }

    /** One row of an island's catalogue. [featured] is the island putting a
     *  site up top on the browser's start screen — the network's own page on
     *  the flagship. Islands older than the field do not send it, and an
     *  absent flag is false, not an error. */
    data class Listed(val name: String, val title: String?, val featured: Boolean)

    /** The catalogue of an island: only the sites that asked to be in it. Best
     *  effort — an island that does not publish one is not an error, it is an
     *  island whose sites are found by being told their names. */
    suspend fun catalogue(host: String): List<Listed> = withContext(Dispatchers.IO) {
        val url = (SiteAddress.originOf(host) + "/sites").toHttpUrlOrNull()
            ?: return@withContext emptyList()
        runCatching {
            val bytes = get(url, fresh = false)
            val rows = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonArray
            rows.mapNotNull { row ->
                val o = row.asJsonObject
                val name = o.get("name")?.asString ?: return@mapNotNull null
                Listed(
                    name = name,
                    title = o.get("title")?.takeIf { !it.isJsonNull }?.asString,
                    featured = o.get("featured")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                )
            }
        }.getOrDefault(emptyList())
    }

    // ───────────────────────────── the bytes ─────────────────────────────

    /**
     * Fetch one file of the bundle and check it against the manifest's hash.
     *
     * A path that is not in the manifest is never requested: nothing unhashed
     * is fetched, however ordinary the path looks.
     */
    private fun file(addr: SiteAddress, m: SiteManifest, path: String, fresh: Boolean): ByteArray {
        val want = m.files[path] ?: throw SiteError.Missing
        val bytes = get(url(addr, path), fresh)
        if (hex(sha256(bytes)) != want) throw SiteError.Tampered
        return bytes
    }

    /**
     * ⚠ Built with path SEGMENTS rather than by pasting a string together. The
     * name is `[a-z0-9-]` by the time it gets here and needs no encoding, but a
     * bundle path is whatever the site owner signed, and a path pasted raw into
     * a URL is how a stray character becomes a request somewhere else.
     */
    private fun url(addr: SiteAddress, path: String): HttpUrl {
        val base = addr.origin.toHttpUrlOrNull() ?: throw SiteError.Offline
        return base.newBuilder()
            .addPathSegment("sites")
            .addPathSegment(addr.name)
            .addPathSegments(path)
            .build()
    }

    private fun get(url: HttpUrl, fresh: Boolean): ByteArray {
        val req = Request.Builder().url(url).get()
        // ⚠⚠ No Authorization header, no cookie jar, and none of the app's
        // authenticated clients: a read carries nothing that ties a page to a
        // person. This client is built here for exactly that reason — reusing
        // RcqApi's would inherit its interceptors along with its token.
        if (fresh) req.cacheControl(CacheControl.FORCE_NETWORK)
        val resp = try {
            http().newCall(req.build()).execute()
        } catch (e: Exception) {
            throw SiteError.Offline
        }
        resp.use {
            // 410 is the island saying the site is gone on purpose, which is a
            // different sentence to a reader than "not found".
            if (it.code == 410) throw SiteError.Frozen
            if (!it.isSuccessful) throw SiteError.Missing
            return try {
                it.body?.bytes() ?: ByteArray(0)
            } catch (e: Exception) {
                throw SiteError.Offline
            }
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Lowercase hex, because that is what the manifest is signed with. A
     *  manifest whose hashes are uppercase does not match and reads as
     *  tampered: one spelling per network, decided by the signer. */
    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    private fun mimeOf(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        else -> null
    }
}

/**
 * The six things that can go wrong, by name. The screen turns them into
 * sentences; nothing else in this package invents a seventh.
 *
 * The names are shared with web-chat and iOS on purpose. "This island is
 * unreachable" and "this address is not an address" are different sentences to
 * a person, and a client that collapsed them would be telling a reader a
 * different story about the same site than the phone next to them.
 *
 * No stack trace is captured: these are a screen's six sentences, not a crash
 * report, and one of them is raised for every unparseable address somebody
 * types.
 */
sealed class SiteError(val code: String) : Exception(code, null, false, false) {

    /** Not an address at all. Raised WITHOUT touching the network: an address
     *  the reader cannot parse is one it must not guess at. */
    object Address : SiteError("address")

    /** The island has no such site, or no such file in it. */
    object Missing : SiteError("missing")

    /** HTTP 410: the site is gone, and the island says so on purpose. */
    object Frozen : SiteError("frozen")

    /** No valid signature: a bad one, an unparseable manifest, or a manifest
     *  whose `name` is not the one that was asked for — a manifest signed for
     *  one site, replayed under another name on the same island. */
    object Unsigned : SiteError("unsigned")

    /** A file's bytes do not match the hash the owner signed. The island is
     *  the only party that could have done this, and it is the one thing the
     *  signature exists to catch. */
    object Tampered : SiteError("tampered")

    /** The island could not be reached. Not the same as missing, and not the
     *  reader's fault to imply. */
    object Offline : SiteError("offline")
}
