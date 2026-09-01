package app.rcq.android.sites

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Trust on first use for `.rcq` sites: which Ed25519 key each site was signed
 * under the first time this device read it.
 *
 * The same rule as safety numbers. A manifest verifies under the key it
 * carries, so a signature alone only proves that one key signed the whole
 * bundle — it says nothing about WHOSE key. The pin is what turns that into an
 * identity over time: an island may serve other bytes under a name, it may not
 * pass them off as the same site. A different key than the pinned one is not
 * refused (the owner may have rotated, or handed the site on); it is RENDERED
 * with a banner, because a reader who cannot see the page cannot judge it.
 *
 * ⚠ Keyed `name@host` ([SiteAddress.pinKey]), never by what the user typed.
 * `blog.rcq` on a flagship client and `blog.flagship.rcq` are one site; keyed
 * by the typed string they would be two pins and a key change would go unseen
 * on the other one.
 *
 * ⚠ NOT per account, unlike the stores around it. A pin is a statement about a
 * site's key, not about the reader: reading a site carries no token and no
 * identity (see [SitesRepository]), so there is no account for it to belong to,
 * and per-account pins would silently reset every key warning on an account
 * switch — which is the one moment a warning is worth most. The cost is that
 * the pin file records which sites this DEVICE has opened; it is the same trade
 * web-chat makes in localStorage, and the panic wipe clears it with everything
 * else under the app's data directory.
 */
object SitePins {

    private const val PREFS = "rcq_sites"
    private const val K_PINS = "pins.v1"

    private val gson = Gson()
    private var prefs: SharedPreferences? = null

    /** Idempotent; [SitesRepository] calls it before any manifest is verified,
     *  so a pin can never be skipped just because a screen forgot to wire it. */
    fun init(ctx: Context) {
        if (prefs == null) {
            prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /**
     * Anchor [key] for [addr], and report whether a DIFFERENT key was pinned
     * before. False on the first visit (the key is stored) and false when it
     * matches; true is the banner.
     */
    fun pin(addr: SiteAddress, key: String): Boolean {
        val pins = read()
        val known = pins[addr.pinKey]
        if (known != null && known != key) return true
        if (known == null) write(pins + (addr.pinKey to key))
        return false
    }

    /** Forget the old key after the reader decided to trust the new one. The
     *  decision is theirs and it is per site: nothing here trusts a key because
     *  another site on the same island used it. */
    fun repin(addr: SiteAddress, key: String) {
        write(read() + (addr.pinKey to key))
    }

    /** What is anchored for this site, for a screen that wants to show it. */
    fun pinned(addr: SiteAddress): String? = read()[addr.pinKey]

    fun forget(addr: SiteAddress) {
        write(read() - addr.pinKey)
    }

    private fun read(): Map<String, String> {
        val raw = prefs?.getString(K_PINS, "{}") ?: return emptyMap()
        val type = object : TypeToken<Map<String, String>>() {}.type
        return runCatching { gson.fromJson<Map<String, String>>(raw, type) }.getOrNull() ?: emptyMap()
    }

    private fun write(pins: Map<String, String>) {
        prefs?.edit()?.putString(K_PINS, gson.toJson(pins))?.apply()
    }
}
