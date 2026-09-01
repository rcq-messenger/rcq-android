package app.rcq.android.sites

import android.util.Base64
import app.rcq.android.net.CanonicalJson
import app.rcq.android.net.SigningKeys
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The signed index of a `.rcq` bundle, and the only reason an island's bytes
 * are worth reading.
 *
 * ⚠⚠ The island is NOT trusted with the bytes. Every bundle carries a manifest
 * signed by the owner's key with a hash per file; this file checks the
 * signature, and [SitesRepository] checks every file it fetches against these
 * hashes. The island can refuse to serve a site, it cannot alter one.
 *
 * The key that verifies the signature comes out of the manifest ITSELF, which
 * sounds like no check at all and is not: it is trust on first use, the same
 * rule as safety numbers. What the signature proves is that one key signed
 * every byte of the bundle; [SitePins] then holds that key against the next
 * visit, so an island may serve other bytes but may not pass them off as the
 * same site.
 *
 * Mirrors `web-chat/src/lib/sites.ts` (`SiteManifest`, `fetchManifest`,
 * `iconPathOf`).
 */
data class SiteManifest(
    val v: Int,
    val name: String,
    val version: Int,
    /** Ed25519 public key (base64) the bundle is signed under. */
    val key: String,
    /** path → sha256 hex of the file's bytes. */
    val files: Map<String, String>,
    val title: String?,
    /** The site's mark, a path inside the bundle. Inside the SIGNATURE on
     *  purpose: this is what a site looks like in a list of sites, and an
     *  island that could choose it could dress one site up as another. */
    val icon: String?,
) {

    /**
     * Every page of the bundle, `index.html` first and the rest alphabetically.
     * The front page is the front page whatever it sorts as.
     *
     * This list is the ONLY way the reader moves between pages: the rendered
     * document has no scripts and no live links, so navigation happens in our
     * own chrome, from names the owner signed.
     */
    fun pages(): List<String> = files.keys
        .filter { it.lowercase().endsWith(".html") }
        .sortedWith(compareBy({ if (it == "index.html") 0 else 1 }, { it }))

    /**
     * Which file in this bundle is the site's mark, if any.
     *
     * ⚠⚠ RASTER ONLY, and that is a network-wide decision rather than this
     * screen's taste. A mark is drawn by OUR chrome, OUTSIDE the locked frame:
     * on a phone that means an SVG would be handed to a native decoder with no
     * sandbox around it and no sanitiser in front of it, and iOS has no native
     * SVG renderer at all. PNG and WebP are decoded by the same code that draws
     * every avatar in the app already. A manifest whose `icon` names an SVG
     * does not get a mark from it — the default names are tried instead, and if
     * none of them is raster either the site simply has no mark.
     */
    fun iconPathOf(): String? {
        val named = icon?.takeIf { files.containsKey(it) && isRasterMark(it) }
        return named ?: ICON_NAMES.firstOrNull { files.containsKey(it) }
    }

    companion object {
        /** What a bundle may call its mark when the manifest does not say. */
        private val ICON_NAMES = listOf("icon.png", "icon.webp", "favicon.png")

        /** The image types this app already decodes for avatars. Deliberately
         *  no `svg`: see [iconPathOf]. */
        private val RASTER_MARK_TYPES = setOf("png", "webp", "jpg", "jpeg", "gif")

        private fun isRasterMark(path: String): Boolean =
            path.substringAfterLast('.', "").lowercase() in RASTER_MARK_TYPES

        /**
         * Parse a manifest and check the owner's signature over it, or throw
         * [SiteError.Unsigned].
         *
         * The signed bytes are the whole object minus `sig`, canonicalised the
         * same way every other signature in this network is ([CanonicalJson]).
         *
         * ⚠ [expectName] is checked against the manifest's own `name` because
         * the name is inside the signature: without that check a manifest
         * signed for one site could be replayed under another name on the same
         * island, and the reader would pin a stranger's key to a name they
         * trust.
         *
         * Anything malformed reads as `unsigned` rather than throwing something
         * else. A reader has exactly six sentences for a person, and "this is
         * not a signed bundle" is the honest one for a truncated file, a
         * key that is not a key, and a signature that does not check out alike.
         */
        fun parse(bytes: ByteArray, expectName: String): SiteManifest {
            val root: JsonObject = runCatching {
                JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            }.getOrNull() ?: throw SiteError.Unsigned

            val key = root.str("key") ?: throw SiteError.Unsigned
            val sig = root.str("sig") ?: throw SiteError.Unsigned
            val filesObj = root.get("files")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw SiteError.Unsigned

            val ok = runCatching {
                SigningKeys.verifyWith(
                    Base64.decode(key, Base64.DEFAULT),
                    CanonicalJson.bytesWithout(root, "sig"),
                    sig,
                )
            }.getOrDefault(false)
            val name = root.str("name")
            if (!ok || name != expectName) throw SiteError.Unsigned

            // Only string values are hashes. A `files` entry holding anything
            // else names a path with no hash to check it against, and a path we
            // cannot check is a path we never fetch.
            val files = HashMap<String, String>(filesObj.size())
            for ((path, value) in filesObj.entrySet()) {
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) files[path] = value.asString
            }

            return SiteManifest(
                v = root.int("v") ?: 1,
                name = name,
                version = root.int("version") ?: 0,
                key = key,
                files = files,
                title = root.str("title"),
                icon = root.str("icon"),
            )
        }

        private fun JsonObject.str(k: String): String? =
            get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private fun JsonObject.int(k: String): Int? =
            get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.let { runCatching { it.asInt }.getOrNull() }
    }
}
