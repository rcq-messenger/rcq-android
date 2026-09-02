package app.rcq.android.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Turning plain server text into tappable links.
 *
 * The news feed is written by a human in a text box: an operator types a
 * paragraph in Russian with a link in the middle of it, and until now that link
 * rendered as dead grey text nobody could reach without retyping it by hand.
 *
 * This is deliberately NOT the chat linkifier ([Emoticons.URL_RE], a plain
 * `https?://\S+`). That one is fine inside a chat bubble, where people paste a
 * URL on a line of its own, and wrong inside a written sentence, where it eats
 * the full stop that ends it:
 *
 *   "Подробности тут: https://rcq.app/hygiene."   -> ".../hygiene." is a 404
 *   "(см. https://rcq.app/free)"                  -> ".../free)" is a 404
 *   "Открой https://rcq.app/free, там всё есть"   -> ".../free," is a 404
 *
 * So the tail is trimmed of sentence punctuation, and brackets are trimmed only
 * when they are UNBALANCED, because a balanced pair belongs to the URL
 * (`.../wiki/Кот_(животное)` is a real address and must survive).
 *
 * The other half is Cyrillic, and it cuts both ways. A URL may legitimately
 * CONTAIN Cyrillic (`https://rcq.app/новости`, `https://почта.рф`), so no
 * ASCII-only character class may be used to find the end of one. But a Cyrillic
 * word glued to the FRONT of a scheme is a typo, not a link, so a candidate
 * whose preceding character is a letter or a digit in ANY alphabet is dropped.
 *
 * Where they open: nowhere in particular, on purpose. A [LinkAnnotation.Url]
 * resolves through the app-wide `LocalUriHandler`, which MainActivity points at
 * [InAppBrowser]: a Custom Tab over the app for the web, and ACTION_VIEW for
 * rcq:// and the rcq.app deep-link paths so an invite in a news post still
 * lands in the app's own join flow instead of a web view. A URL whose host is
 * a `.rcq` name (`https://e2ee.rcq/en.html`) is not a web address at all and
 * [InAppBrowser] turns it back into the app's own site reader
 * ([app.rcq.android.sites.SiteAddress.linkOf]); the hit here stays a URL, the
 * decision is made where it is opened.
 */
object TextLinks {

    /** A link as found in the source text: where it sits, what to draw, where
     *  it goes. [display] is what the author typed (so a `www.` link is not
     *  silently rewritten under them); [href] is what gets opened. */
    data class Hit(val start: Int, val end: Int, val display: String, val href: String)

    /** Widest possible candidate: a scheme (or a bare `www.`) plus everything
     *  up to whitespace. Everything narrowing happens afterwards, in code,
     *  because "where does this URL end" is not a question a regex answers in
     *  a sentence written by a person.
     *
     *  `rcq://` is in here because the manifest claims it (rcq://group,
     *  rcq://add, rcq://link and friends): a post that hands out an invite in
     *  the app's own scheme should open the join flow on tap, and it does,
     *  because [InAppBrowser] sends anything non-http to ACTION_VIEW. */
    private val CANDIDATE = Regex("(?i)(?:https?://|rcq://|www\\.)\\S+")

    /** Punctuation that ends a SENTENCE, never a URL. Note the Russian and
     *  typographic quotes: the feed is written in Russian and «ссылка» is how a
     *  link gets quoted there. */
    private const val TRAILING = ".,;:!?\"'’”»…>|"

    /** Closing bracket -> its opener. Trimmed only when unbalanced. */
    private val BRACKETS = mapOf(')' to '(', ']' to '[', '}' to '{')

    /** Cheap gate so the common link-free post never touches the regex. */
    fun mayContain(text: String): Boolean =
        text.contains("://") || text.contains("www.", ignoreCase = true)

    /** Every link in [text], in source order, non-overlapping. */
    fun find(text: String): List<Hit> {
        if (text.isEmpty() || !mayContain(text)) return emptyList()
        val out = ArrayList<Hit>()
        var from = 0
        for (m in CANDIDATE.findAll(text)) {
            val start = m.range.first
            if (start < from) continue
            // Glued to a word: "тутhttps://rcq.app" is a missing space, and
            // "me@www.host.tld" is an address. isLetterOrDigit is Unicode-aware,
            // which is the entire point here.
            val prev = if (start > 0) text[start - 1] else ' '
            if (Character.isLetterOrDigit(prev) || prev == '@') continue
            val display = trimTail(m.value)
            val href = hrefOf(display) ?: continue
            out.add(Hit(start, start + display.length, display, href))
            from = start + display.length
        }
        return out
    }

    /** Drop the punctuation a sentence left on the end of a URL. */
    private fun trimTail(raw: String): String {
        var end = raw.length
        while (end > 0) {
            val ch = raw[end - 1]
            val opener = BRACKETS[ch]
            if (opener != null) {
                var opens = 0
                var closes = 0
                for (i in 0 until end) {
                    if (raw[i] == opener) opens++ else if (raw[i] == ch) closes++
                }
                // Balanced (or more openers than closers): the bracket is part
                // of the address, e.g. /wiki/Кот_(животное). Stop here.
                if (opens >= closes) break
                end--
                continue
            }
            if (TRAILING.indexOf(ch) >= 0) {
                end--
                continue
            }
            break
        }
        return raw.substring(0, end)
    }

    /** What to hand the uri handler, or null when what is left is not an
     *  address at all (a bare "https://", a lone "www."). */
    private fun hrefOf(s: String): String? {
        val lower = s.lowercase()
        val hostAt = when {
            lower.startsWith("https://") -> 8
            lower.startsWith("http://") -> 7
            lower.startsWith("rcq://") -> 6
            lower.startsWith("www.") -> 0
            else -> return null
        }
        if (hostAt >= s.length) return null
        // A host starts with a letter or a digit in any alphabet; anything else
        // ("https://," after trimming, "https:///path") is not one.
        if (!Character.isLetterOrDigit(s[hostAt])) return null
        if (hostAt == 0) {
            // Bare `www.` form: insist on a second dot, so the sentence
            // fragment "www." on its own is not a link, and add the scheme the
            // author left out.
            if (s.indexOf('.', 4) < 4) return null
            return "https://$s"
        }
        return s
    }
}

/**
 * [text] with every URL in it drawn as an accent underline and tappable.
 * Returns a plain [AnnotatedString] when there is nothing to link, so the
 * link-free post costs exactly what it did before.
 */
fun linkedText(text: String, accent: Color): AnnotatedString {
    val hits = TextLinks.find(text)
    if (hits.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        for (h in hits) {
            if (h.start > cursor) append(text.substring(cursor, h.start))
            withLink(LinkAnnotation.Url(h.href)) {
                withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) {
                    append(h.display)
                }
            }
            cursor = h.end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

/**
 * Give a long press back to the surface underneath, even on top of a link.
 *
 * Compose's text-link gesture detector claims the whole pointer stream inside a
 * link and resolves it as a tap on release, however long the finger stayed
 * down. Adding links to the news card would therefore have QUIETLY REMOVED the
 * long-press-to-copy the founder asked for, on exactly the posts people most
 * want to copy: the ones with a link in them.
 *
 * So this watches the same stream in the Initial pass, before the link sees it,
 * and does nothing at all until the long-press timeout has passed. A normal tap
 * is untouched and still opens the URL. Travel past touch slop, or a change the
 * list already consumed for its own scroll, ends the press: it belongs to the
 * finger, not to the pixel under it (the lesson of report #583).
 *
 * Same shape as the chat bubble's own version in Emoticon.kt; kept separate
 * rather than shared because that one is private to the message renderer and
 * this file must not reach into it.
 */
fun Modifier.longPressOverLinks(onLongPress: (() -> Unit)?): Modifier =
    if (onLongPress == null) this else this.pointerInput(onLongPress) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val held = try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    var up = false
                    while (!up) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.none { it.pressed }) up = true
                        else if (ev.changes.any { it.isConsumed }) up = true
                        else if (ev.changes.any { (it.position - down.position).getDistance() > slop }) up = true
                    }
                }
                false
            } catch (_: PointerEventTimeoutCancellationException) {
                true
            }
            if (!held) return@awaitEachGesture
            onLongPress()
            // Everything left in this gesture belongs to us now.
            var pressed = true
            while (pressed) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                ev.changes.forEach { it.consume() }
                pressed = ev.changes.any { it.pressed }
            }
        }
    }
