package app.rcq.android.ui

import app.rcq.android.sites.SiteAddress
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `.rcq` addresses inside a message body, made tappable.
 *
 * An address is how a site is found at all: there is no search and no index,
 * so `blog.rcq` gets passed around in a chat the way a phone number does. Left
 * as dead text it has to be retyped by hand into the address bar, one
 * character wrong away from a site nobody has.
 *
 * Two rules, both borrowed rather than invented:
 *
 * * WHAT LOOKS LIKE ONE is [CANDIDATE], the same shape as the web client's
 *   `RCQ_RE` (`web-chat/src/components/EmoticonText.tsx`) — narrow on purpose,
 *   so an ordinary word before a full stop cannot become a link.
 * * WHETHER IT IS ONE is [SiteAddress.parse], the same function the address
 *   bar and the fetcher use. A second opinion about what an address is would
 *   mean a message could offer a link that the browser then refuses, which is
 *   the one failure a reader cannot do anything about.
 *
 * Addresses inside an http URL are skipped by the caller, not by this regex:
 * `https://blog.rcq.app/x` is somebody's host on the ordinary web, and the
 * `blog.rcq` inside it is not a site here. Same two-pass order as the web.
 */
object SiteLinks {

    /** Mirrors the web `RCQ_RE`: a name, an optional island label, `.rcq`. */
    private val CANDIDATE = Regex(
        "\\b[a-z0-9][a-z0-9-]{0,31}(?:\\.[a-z0-9][a-z0-9.:-]{0,63})?\\.rcq\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The island this file resolves against, and it is deliberately a host
     * that cannot exist.
     *
     * [SiteAddress.parse] is asked one question here — "is this an address at
     * all" — and that answer never depends on the reader's own island: the
     * host only decides WHERE a bare `blog.rcq` points. The real one belongs
     * to the session and is applied in the browser screen when the address opens,
     * so nothing here has to reach for a Session it has no business holding.
     */
    private const val DETECT_HOST = "detect.invalid"

    /** Cheap gate so the overwhelmingly common message never sees the regex. */
    fun mayContain(text: String): Boolean = text.contains(".rcq", ignoreCase = true)

    /**
     * Every `.rcq` address in [text], in source order. [skip] are ranges
     * already claimed by something else (the http links found first), and a
     * candidate touching one is dropped rather than trimmed.
     */
    fun find(text: String, skip: List<IntRange> = emptyList()): List<IntRange> {
        if (!mayContain(text)) return emptyList()
        val out = ArrayList<IntRange>()
        for (m in CANDIDATE.findAll(text)) {
            val r = m.range
            if (skip.any { r.first <= it.last && it.first <= r.last }) continue
            if (SiteAddress.parse(m.value, DETECT_HOST) == null) continue
            out.add(r)
        }
        return out
    }
}

/**
 * A tapped address, parked for the navigation to pick up.
 *
 * The message renderer is several screens deep and holds no navigation state,
 * so it does what a share intent and a tapped notification already do here
 * (`ShareIntake`, `NotificationOpen`): it leaves the address in one place and
 * `RcqApp` moves the UI. The alternative was threading a lambda down through
 * every message composable in ChatScreen, a file already at the ART verifier's
 * method-size limit.
 */
object SiteOpen {
    /** The address, and the page of the bundle a link with a path asked for
     *  (null is the front page). */
    data class Req(val address: String, val page: String? = null)

    val pending = MutableStateFlow<Req?>(null)

    fun request(address: String, page: String? = null) {
        pending.value = Req(address, page)
    }
}
