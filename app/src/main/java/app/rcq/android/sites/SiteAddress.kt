package app.rcq.android.sites

/**
 * `.rcq` addresses: an island and a site name, worked out on THIS device.
 *
 * ⚠⚠ There is no DNS anywhere in here, and that is the design rather than a
 * shortcut: `.rcq` is not a domain, it is a marker that says "this name is
 * resolved inside the network". The name is split on this phone into an island
 * and a site, and the request goes straight to that island. Nothing about what
 * a person reads passes through a resolver, and their own island is not asked
 * either — proxying would hand its operator a journal of what its users read
 * elsewhere.
 *
 * Mirrors `web-chat/src/lib/sites.ts` (`parseRcqAddress`,
 * `islandHostFromLabel`, `originOf`) and is checked against the shared corpus
 * in `RCQ/docs/rcq-sites-conformance.json`. An address that resolves on one
 * client and errors on another is worse than one that errors everywhere: the
 * reader who can open it is the one left holding a pin nobody else can check.
 */
data class SiteAddress(
    /** Site name inside the island's zone. */
    val name: String,
    /** Island host to ask, already resolved from the address. */
    val host: String,
    /** What the address bar shows: the cleaned string, e.g. `blog.is2.rcq`. */
    val display: String,
) {

    /**
     * The identity a pin belongs to: the site and the island that serves it,
     * never the string somebody typed. `blog.rcq` on a flagship client and
     * `blog.flagship.rcq` are one site; keyed by what was typed they would be
     * two pins, and a key change would go unseen on the other one.
     */
    val pinKey: String get() = "$name@$host"

    /**
     * Everything is https except a developer's own machine. An island is a
     * public host and the one exception is spelled out rather than inferred —
     * `blog.localhost.rcq` (no colon) is an ordinary unknown label and becomes
     * the PUBLIC host `localhost.rcq.app`, so a client that special-cased the
     * word would point a public address at the device's own loopback.
     */
    val origin: String get() = originOf(host)

    companion object {
        /** Split out so the island catalogue, which has a host and no site,
         *  cannot end up with a different rule than a page request. */
        fun originOf(host: String): String {
            val local = host == "localhost" || host.startsWith("localhost:") ||
                host == "127.0.0.1" || host.startsWith("127.0.0.1:")
            return "${if (local) "http" else "https"}://$host"
        }

        /** Legal site names, and nothing else: no underscore, no dot, no
         *  percent, no unicode. A name goes into a URL path, into a pin key and
         *  onto a screen, so the legal set has to be identical in every client
         *  or one of them requests a path another cannot even form. A TRAILING
         *  dash is legal — this is not a hostname, and a DNS label validator
         *  reused here would refuse a name the island serves happily. */
        private val NAME = Regex("^[a-z0-9][a-z0-9-]{0,31}\$")

        private const val SUFFIX = ".rcq"

        /**
         * `blog.is2.rcq` → { name: blog, host: is2.rcq.app }, or null when the
         * string is not an address at all. Null is not a soft failure: the
         * reader raises `address` and NEVER touches the network, because an
         * address it cannot parse is an address it must not guess at.
         *
         * A bare `blog.rcq` means "on my own island", which is what makes
         * somebody's first site reachable before they know what an island is.
         * [ownHost] is the island this client already talks to (Session's
         * `serverHost()`, `api.rcq.app` on the flagship) and must be threaded
         * through rather than assumed: `blog@island.example.org` and
         * `blog@api.rcq.app` are different sites with different pins.
         */
        fun parse(raw: String, ownHost: String): SiteAddress? {
            val cleaned = raw.trim(::isJsWhitespace).lowercase()
                .removePrefix("rcq://").trimEnd('/')
            if (!cleaned.endsWith(SUFFIX)) return null
            // Empty labels are FILTERED, not counted: `..blog.rcq` and
            // `blog...is2.rcq` are the padded forms of addresses that resolve,
            // and a client using a plain split would refuse them while the
            // others open them.
            val parts = cleaned.dropLast(SUFFIX.length).split('.').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.size > 2) return null
            val name = parts[0]
            if (!NAME.matches(name)) return null
            // One label is ALWAYS a name, never an island: `flagship.rcq` is a
            // site called `flagship` on the reader's own island, and a client
            // that scanned for alias words anywhere in the address would show a
            // stranger's site under a name the user owns.
            val host = if (parts.size == 2) islandHostFromLabel(parts[1], ownHost) else ownHost
            return SiteAddress(name = name, host = host, display = cleaned)
        }

        /**
         * The island label → host mapping. An unknown label is treated as a
         * hostname so an operator can hand out an address before any client has
         * heard of their island.
         *
         * ⚠ Every entry is an EXACT label match, never a prefix: `mine.rcq` is
         * a site called `mine`, not the `my` alias with a stray `ne`. Prefix
         * matching is the most common way to get this table wrong.
         */
        internal fun islandHostFromLabel(label: String, ownHost: String): String = when {
            label == "flagship" || label == "rcq" -> "api.rcq.app"
            label == "is2" -> "is2.rcq.app"
            label == "here" || label == "my" -> ownHost
            // A colon makes the label a literal host, port and all: that is how
            // a developer reads a bundle off their own machine, and it changes
            // the pin key too, so a local test bundle can never overwrite the
            // pinned key of the same name on a real island. (The dot arm is
            // unreachable from parsing — the address was split on dots first —
            // and is kept because the mapping rule is written that way in all
            // three clients.)
            label.contains('.') || label.contains(':') -> label
            else -> "$label.rcq.app"
        }

        /**
         * The whitespace JavaScript's `trim` removes, spelled out.
         *
         * ⚠⚠ Kotlin's own `trim()` is a different set in both directions and
         * this is the one place it matters. An address copied out of a rendered
         * page carries U+00A0, one copied out of a file carries U+FEFF, and the
         * reference accepts both — a port that inherits the platform's idea of
         * whitespace refuses an address that opens elsewhere. The other
         * direction is sharper: U+200B is NOT whitespace to JavaScript, so
         * a U+200B in front of `blog.rcq` is refused, and a port that helpfully strips every
         * invisible character would ACCEPT a look-alike address that resolves
         * on some clients only and whose pin is a different pin from the real
         * one.
         */
        private fun isJsWhitespace(c: Char): Boolean = when (c) {
            // WhiteSpace: TAB, VT, FF, SP, NBSP, ZWNBSP
            '\u0009', '\u000B', '\u000C', '\u0020', '\u00A0', '\uFEFF' -> true
            // LineTerminator: LF, CR, LS, PS
            '\u000A', '\u000D', '\u2028', '\u2029' -> true
            // The rest of Unicode's Space_Separator class
            '\u1680', '\u202F', '\u205F', '\u3000' -> true
            else -> c in '\u2000'..'\u200A'
        }
    }
}
