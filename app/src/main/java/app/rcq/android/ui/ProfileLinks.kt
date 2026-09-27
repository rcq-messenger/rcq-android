package app.rcq.android.ui

import app.rcq.android.net.RcqFederation
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `uin@island` addresses inside a message body, made tappable (#1053).
 *
 * `833111503@is2.rcq.app` is what the app itself puts in the clipboard when
 * somebody copies a number (#1025: a bare number names a different person on
 * every island), so it is exactly what gets pasted into a chat to hand a
 * contact on. It was drawn as a SITE link instead: [SiteLinks] saw the
 * `is2.rcq` inside the host and offered to open a site called `is2` on the
 * reader's own island, which exists nowhere. The address is a person, and a tap
 * on it opens that person's card on that island, the same card the Add sheet
 * opens for a typed `uin@host`.
 *
 * Digits before the `@` and nothing else: `name@example.com` is an email and
 * stays text. The number cannot be the tail of a longer word or number
 * (`a134@x.org`, a phone number glued to a domain), and the host needs a dot,
 * as every island's host has one. The flagship form `134@api.rcq.app` is the
 * same shape and needs no case of its own; whether an address is on the
 * reader's own island is decided where it opens, against the session.
 *
 * Found before the `.rcq` pass and handed to it as claimed ranges, the same
 * two-pass order the http links already use with [SiteLinks].
 */
object ProfileLinks {

    data class Hit(val range: IntRange, val uin: Int, val host: String)

    /** A number no longer than a UIN can be, `@`, a dotted host and an
     *  optional port. Nothing word-like on either side: the number must start
     *  the token, and the host must end it (a dot before the next word is a
     *  sentence's full stop, not more host). */
    private val CANDIDATE = Regex(
        "(?<![\\p{L}\\p{N}_.+\\-@:/=%#])(\\d{1,10})@" +
            "((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,62}(?::\\d{1,5})?)" +
            "(?![\\p{L}\\p{N}_@-]|\\.[\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    /** Cheap gate so the overwhelmingly common message never sees the regex. */
    fun mayContain(text: String): Boolean {
        var at = text.indexOf('@')
        while (at >= 0) {
            if (at > 0 && text[at - 1] in '0'..'9') return true
            at = text.indexOf('@', at + 1)
        }
        return false
    }

    /** Every profile address in [text], in source order. [skip] are ranges
     *  already claimed (the http links), and a candidate touching one is
     *  dropped: `https://rcq.app/u/134?h=…` is a URL, whatever it contains. */
    fun find(text: String, skip: List<IntRange> = emptyList()): List<Hit> {
        if (!mayContain(text)) return emptyList()
        val out = ArrayList<Hit>()
        for (m in CANDIDATE.findAll(text)) {
            val r = m.range
            if (skip.any { r.first <= it.last && it.first <= r.last }) continue
            // The same parser the Add sheet and the contact links use, so a
            // message never offers an address that the card then refuses.
            val a = runCatching { RcqFederation.parseAddress(m.value) }.getOrNull() ?: continue
            if (a.uin <= 0) continue
            out.add(Hit(r, a.uin, a.host))
        }
        return out
    }
}

/**
 * A tapped profile address, parked for the navigation to pick up: the renderer
 * sits inside a bubble and holds no navigation state (see [SiteOpen]).
 */
object ProfileOpen {
    data class Req(val uin: Int, val host: String)

    val pending = MutableStateFlow<Req?>(null)

    fun request(uin: Int, host: String) {
        pending.value = Req(uin, host)
    }
}
