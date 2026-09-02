package app.rcq.android.sites

import android.util.Base64

/**
 * Turning a bundle's HTML into one self-contained document that can reach
 * nothing and run nothing.
 *
 * ⚠⚠ On the web this is the SECOND lock: the page goes into an iframe whose
 * sandbox and CSP are the browser's promise, and this pass is ours. On a phone
 * there is only this one. The document is handed to a WebView, often with a
 * real base URL, so a surviving relative `url()` or a leftover `<img>` is a
 * live request to the island — a read receipt per reader, which is the exact
 * metadata this whole design exists not to produce. Everything below has to
 * hold on its own; the `<meta>` policy we prepend is a courtesy, not the
 * defence.
 *
 * ## Why a tokeniser and not a library
 *
 * There is no DOM on Android and no HTML parser in the platform that gives us
 * a tree to walk (`Html.fromHtml` is a text formatter, not a parser). Adding
 * jsoup would be a 400 KB dependency parsing hostile input in the same process
 * as the message store, so this file carries a small tolerant tokeniser
 * instead — enough of the HTML5 tokeniser's special cases to agree with the
 * frame's parser where agreement matters: raw-text elements (`script`,
 * `style`, `xmp`, `iframe`, `plaintext`), escapable text (`title`), the
 * `image` → `img` rename, comments, and case folding of tag and attribute
 * names.
 *
 * ★ The reason that list is the list: we serialise once and the WebView parses
 * again. Any place the two parsers disagree about where an element ends is a
 * place where the text we thought was prose comes back as markup. That is why
 * `<xmp>` content is escaped on the way out rather than passed through, and
 * why `</style` is neutralised inside CSS, where escaping is not available.
 *
 * Mirrors `web-chat/src/lib/sites.ts` (`inline`, `cleanCss`, `resolve`) and is
 * checked against `RCQ/docs/rcq-sites-conformance.json`.
 */
internal object SiteSanitizer {

    /**
     * Decode a text file the way its author declared it, not the way we would
     * prefer.
     *
     * The first site anybody published on this network was a 2000s frameset in
     * windows-1251, and that is not an accident: a format with no scripts and
     * no tracking attracts exactly the people whose pages predate UTF-8.
     * Reading their Russian as UTF-8 turns it into mojibake. The label is read
     * out of the first kilobyte, the way a browser sniffs it.
     */
    fun decodeDeclared(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val label = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)").find(head)?.groupValues?.get(1)
        if (label != null && !label.equals("utf-8", true) && !label.equals("utf8", true)) {
            try {
                return String(bytes, java.nio.charset.Charset.forName(label))
            } catch (_: Exception) {
                // A charset this device does not know. UTF-8 at least renders
                // the ASCII half rather than nothing.
            }
        }
        return String(bytes, Charsets.UTF_8)
    }


    /**
     * What a page may contain. An ALLOW-LIST, not a list of things to remove: a
     * deny-list is a promise that we thought of everything, and the web keeps
     * inventing elements. Anything not named here is UNWRAPPED (its text stays,
     * the element goes), so an unknown tag costs a page its styling and never
     * its content.
     */
    private val ALLOWED_TAGS = setOf(
        "html", "head", "body", "title", "style", "meta",
        "div", "span", "p", "br", "hr", "section", "article", "main", "aside", "nav",
        "header", "footer", "figure", "figcaption", "blockquote", "pre", "code", "kbd", "samp",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "dl", "dt", "dd",
        "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "colgroup", "col",
        "a", "img", "strong", "b", "em", "i", "u", "s", "small", "sub", "sup", "mark",
        "time", "abbr", "cite", "q", "ruby", "rt", "rp", "wbr", "details", "summary",
    )

    /**
     * Attributes that may survive on any element. Everything else goes, which
     * covers `on*`, `ping`, `srcset`, `formaction`, `background`, `xlink:href`
     * and whatever is invented next without us having to name it.
     */
    private val ALLOWED_ATTRS = setOf(
        "class", "id", "title", "lang", "dir", "alt", "width", "height",
        "colspan", "rowspan", "headers", "scope", "span", "datetime", "cite", "open",
        "start", "reversed", "value", "charset",
    )

    /**
     * Elements removed WITH their children, unlike the unwrap above: what is
     * inside them is not prose. A script's text is code; a form's text is bait
     * for a submit button that carries its own destination; an SVG's text is
     * drawing instructions in a second parser with its own casing rules and its
     * own URL attributes (`xlink:href`, `href` on `image`, `use`, and
     * `foreignObject`, which re-enters HTML). `template` and `noscript` are
     * here because their content is parsed under different rules than the
     * markup around it, so the same bytes mean one thing to us and another to
     * the frame — and `base` is here because a surviving one re-points every
     * relative reference in the document AFTER we resolved them.
     */
    private val REMOVED = setOf(
        "script", "iframe", "object", "embed", "form", "video", "audio", "source", "track",
        "base", "svg", "math", "canvas", "template", "noscript", "portal",
    )

    /** Elements a real parser reads as text rather than markup. `plaintext` is
     *  handled apart: it has no closing form and swallows the document. */
    private val TEXT_ONLY = setOf(
        "script", "style", "xmp", "iframe", "noembed", "noframes", "title", "textarea",
    )

    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
        "meta", "param", "source", "track", "wbr",
    )

    /** Where the leading metadata of a document belongs, so the head we build
     *  looks like the head a parser would have built. */
    private val HEAD_TAGS = setOf(
        "base", "basefont", "bgsound", "link", "meta", "noscript", "script",
        "style", "template", "title",
    )

    /** Enough implied-end-tag rules to keep ordinary prose nested the way its
     *  author meant. Not a conformance matter — nesting cannot make an element
     *  allowed — but a `<p>a<p>b` that comes out nested renders wrong. */
    private val CLOSES_P = setOf(
        "address", "article", "aside", "blockquote", "details", "div", "dl", "fieldset",
        "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
        "header", "hr", "main", "nav", "ol", "p", "pre", "section", "summary", "table", "ul",
    )

    /** Attribute values that could fetch. Deliberately WIDER than the cleaner
     *  used on `<style>` blocks (which wants `url(` with no gap): an attribute
     *  has no author structure worth preserving, so the whole thing is dropped
     *  on suspicion rather than cleaned. Unifying the two would change which
     *  attributes survive, and that is visible on screen. */
    private val STYLE_FETCH = Regex("url\\s*\\(|@import", RegexOption.IGNORE_CASE)

    private val SCHEME = Regex("^[a-zA-Z]+:")

    /**
     * Our own policy, prepended last so it is not one of the attributes the
     * walk just stripped and is the first thing the frame's parser reads.
     * Normative character for character across the three clients:
     * `default-src 'none'` means no scripts, no fetches, no connections;
     * `img-src data:` allows exactly the images we inlined ourselves from
     * verified bytes; `font-src 'none'` because a webfont is a request like any
     * other.
     */
    private const val CSP =
        "<meta http-equiv=\"Content-Security-Policy\" " +
            "content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src 'none'\">"

    /**
     * ⚠ A bundle is not trusted, and the passes below recurse while the parser
     * does not. Depth is bounded where it is created, so 100k nested `<div>`
     * cannot turn a page into a StackOverflowError inside the app's process.
     */
    private const val MAX_DEPTH = 128

    private val IMAGE_TYPES = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
    )

    /**
     * The whole pipeline, in the order the conformance corpus pins.
     *
     * [read] hands back the VERIFIED bytes of a bundle path (hash-checked
     * against the manifest by [SitesRepository]) or null when there are none.
     * The sanitiser never touches the network itself: everything it inlines has
     * already been checked against a signature.
     */
    suspend fun render(
        html: String,
        pagePath: String,
        files: Set<String>,
        /**
         * The reader's own `href` for an anchor the passes below marked, or
         * null to leave it dead: called with the bundle path of an internal
         * link, or the verbatim target of an outward one, never both. Runs
         * AFTER the allow-list walk, so what it writes is the one `href` in
         * the document and the only thing a tap can say. The default writes
         * nothing, which is the output the conformance corpus pins: no
         * `href=` anywhere.
         */
        door: ((page: String?, external: String?) -> String?)? = null,
        read: suspend (String) -> ByteArray?,
    ): String {
        val doc = Html(html).parse()
        // Stylesheets first: a <link> is resolved into a <style> BEFORE the
        // allow-list walk, which then treats it like any author style block.
        inlineLinks(doc.roots, pagePath, files, read)
        // ⚠ The removal list runs before the allow-list walk. Reversed, an
        // element like `template` — which is not allowed — would be UNWRAPPED,
        // lifting its inert content into the document and handing the frame a
        // live script.
        strip(doc.roots)
        // ⚠ Every `data-rcq-*` the PAGE supplied goes before we write our own.
        // Those attributes are not decoration, they are the channel between
        // this file and our own chrome: a page that could write on it could
        // make the chrome open a page of the bundle the reader never chose.
        scrubMarks(doc.roots)
        // Images before the walk, so a page-supplied `data:` URI never reaches
        // the walk's `src` exception — that exception exists for the URIs WE
        // built out of verified bytes.
        inlineImages(doc.roots, pagePath, files, read)
        markLinks(doc.roots, pagePath, files)
        walk(doc.roots)
        if (door != null) openDoors(doc.roots, door)
        return serialize(doc)
    }

    // ───────────────────────────── the passes ─────────────────────────────

    private suspend fun inlineLinks(
        nodes: MutableList<SiteNode>,
        pagePath: String,
        files: Set<String>,
        read: suspend (String) -> ByteArray?,
    ) {
        var k = 0
        while (k < nodes.size) {
            val el = nodes[k] as? SiteNode.Element
            if (el == null) { k++; continue }
            if (el.tag != "link") { inlineLinks(el.children, pagePath, files, read); k++; continue }
            // `rel` is compared for EQUALITY with `stylesheet`, never searched
            // for it as a token, so `alternate stylesheet` is dropped rather
            // than inlined: the author marked it as not-default.
            val rel = el.attr("rel")?.lowercase() ?: ""
            val href = resolve(pagePath, el.attr("href") ?: "")
            // Only a stylesheet pointing INTO the bundle survives. Everything
            // else — preload, prefetch, dns-prefetch, icon, and whatever `rel`
            // is invented next — is a request with no visible element and no
            // user action, which is the shape of a read receipt. A stylesheet
            // from outside cannot be hashed against the manifest and so is
            // never fetched: CSS decides what the reader sees, and an
            // unverified one could hide or fake the whole page.
            val css = if (rel == "stylesheet" && href != null && href in files) {
                read(href)?.let { decodeDeclared(it) }
            } else null
            if (css == null) { nodes.removeAt(k); continue }
            nodes[k] = SiteNode.Element("style").also { it.children.add(SiteNode.Text(cleanCss(css))) }
            k++
        }
    }

    private fun strip(nodes: MutableList<SiteNode>) {
        var k = 0
        while (k < nodes.size) {
            val el = nodes[k] as? SiteNode.Element
            if (el == null) { k++; continue }
            if (el.tag in REMOVED) { nodes.removeAt(k); continue }
            strip(el.children)
            k++
        }
    }

    private fun scrubMarks(nodes: MutableList<SiteNode>) {
        for (node in nodes) {
            val el = node as? SiteNode.Element ?: continue
            el.attrs.removeAll { it.name.startsWith("data-rcq-") }
            scrubMarks(el.children)
        }
    }

    private suspend fun inlineImages(
        nodes: MutableList<SiteNode>,
        pagePath: String,
        files: Set<String>,
        read: suspend (String) -> ByteArray?,
    ) {
        var k = 0
        while (k < nodes.size) {
            val el = nodes[k] as? SiteNode.Element
            if (el == null) { k++; continue }
            if (el.tag != "img") { inlineImages(el.children, pagePath, files, read); k++; continue }
            // A remote image is a request per reader with an IP and a
            // timestamp; a path that is not in the manifest is bytes nobody
            // signed. Neither is fetched, and the element goes rather than
            // showing a broken frame.
            val src = resolve(pagePath, el.attr("src") ?: "")
            val uri = if (src != null && src in files) read(src)?.let { dataUri(src, it) } else null
            if (uri == null) { nodes.removeAt(k); continue }
            el.setAttr("src", uri)
            k++
        }
    }

    private fun markLinks(nodes: MutableList<SiteNode>, pagePath: String, files: Set<String>) {
        for (node in nodes) {
            val el = node as? SiteNode.Element ?: continue
            if (el.tag == "a") {
                val href = el.attr("href") ?: ""
                val inner = resolve(pagePath, href)
                if (inner != null && inner in files) {
                    // An internal link. Nothing inside the frame navigates; the
                    // page list in our own chrome is the door, and the anchor
                    // only says where it points. Its `title` is OVERWRITTEN
                    // with the path on purpose: the author does not get to
                    // write the tooltip, because the tooltip is how a reader
                    // checks where a link goes before following it.
                    el.setAttr("data-rcq-page", inner)
                    el.setAttr("title", inner)
                } else {
                    // ⚠ An outward link stays as TEXT and does nothing. One
                    // click out of the network is how a reader gets
                    // deanonymised; Tor's exit-node problem is one this design
                    // can simply not have. The target is kept verbatim so the
                    // chrome can show a reader where the page wanted to send
                    // them — as TEXT, never as markup and never handed to an
                    // opener.
                    el.setAttr("data-rcq-external", href)
                    el.setAttr("title", href)
                }
            }
            markLinks(el.children, pagePath, files)
        }
    }

    /**
     * ⚠ After the walk, never before it: the walk drops every `href`, which
     * is what makes the marks the only thing an anchor carries out of the
     * page. Here the reader writes its own from those marks — from OUR
     * `data-rcq-page`, scrubbed of the author's ([scrubMarks]) — so a page
     * cannot choose where a tap goes any more than it could before.
     */
    private fun openDoors(nodes: MutableList<SiteNode>, door: (String?, String?) -> String?) {
        for (node in nodes) {
            val el = node as? SiteNode.Element ?: continue
            if (el.tag == "a") {
                val href = door(el.attr("data-rcq-page"), el.attr("data-rcq-external"))
                if (href != null) el.setAttr("href", href)
            }
            openDoors(el.children, door)
        }
    }

    /**
     * The allow-list walk. Unknown elements are unwrapped rather than deleted,
     * and every attribute not on the list goes — including the `href` the
     * anchor pass just read and the `src` the image pass just wrote, which is
     * why the image `src` has an exception and the anchor's marks do.
     */
    private fun walk(nodes: MutableList<SiteNode>) {
        var k = 0
        while (k < nodes.size) {
            val el = nodes[k] as? SiteNode.Element
            if (el == null) { k++; continue }
            if (el.tag !in ALLOWED_TAGS) {
                // Children are walked BEFORE they move up: an unwrap that
                // forgot this would leave the handler on a `<strong>` that just
                // became a top-level element.
                walk(el.children)
                nodes.removeAt(k)
                nodes.addAll(k, el.children)
                k += el.children.size
                continue
            }
            el.attrs.retainAll { keep(el.tag, it) }
            if (el.tag == "style") {
                val css = el.children.filterIsInstance<SiteNode.Text>().joinToString("") { it.text }
                el.children.clear()
                el.children.add(SiteNode.Text(cleanCss(css)))
            }
            walk(el.children)
            k++
        }
    }

    private fun keep(tag: String, a: SiteAttr): Boolean =
        a.name in ALLOWED_ATTRS ||
            (tag == "img" && a.name == "src" && a.value.startsWith("data:")) ||
            (tag == "a" && (a.name == "data-rcq-page" || a.name == "data-rcq-external")) ||
            // A style attribute may stay only once it can no longer fetch, and
            // then it stays VERBATIM: it is dropped whole rather than cleaned,
            // so `color:red` goes with the `url()` next to it.
            (a.name == "style" && !fetches(a.value))

    /**
     * ⚠ The test is run twice, on the value as written AND on the value with
     * its CSS escapes decoded.
     *
     * The reference tests the raw text only, and `\75 rl(` is `url(` to every
     * CSS parser: `style="x:\75 rl(https://tracker/y)"` walks straight past a
     * text expression and fetches. On the web the frame's `default-src 'none'`
     * catches what got through; on a phone there is nothing behind this. The
     * decoded form is used for the TEST alone and never written out — an
     * attribute either survives exactly as its author wrote it or does not
     * survive at all.
     *
     * ★ This only ever drops MORE than the reference does, never less, so a
     * page that renders here renders there. The one thing it costs is a style
     * attribute using an escape innocently, `content:"\201C"` and the like —
     * and that one decodes to no `url(` and stays.
     */
    private fun fetches(value: String): Boolean =
        STYLE_FETCH.containsMatchIn(value) ||
            STYLE_FETCH.containsMatchIn(decodeEscapes(value))

    // ─────────────────────────── bundle paths ───────────────────────────

    /**
     * Resolve `../a/b.png` against the page's own path, inside the bundle only.
     *
     * ⚠ The result is only ever a KEY into `manifest.files`. It is never a
     * filesystem path and never a URL built by concatenation: `..` pops an
     * already-empty stack so a reference cannot climb out of the bundle, and a
     * leading `/` means the bundle root and not the device root. That
     * distinction is cheap on the web and load-bearing here, where a client
     * that unpacked a bundle into a cache directory and joined these onto it
     * would be reading the user's files.
     */
    internal fun resolve(from: String, ref: String): String? {
        // No scheme, no protocol-relative, no fragment: an in-page anchor is
        // outward too, because scrolling by fragment would need the frame to
        // act on a URL and the rule is that the frame acts on nothing.
        if (SCHEME.containsMatchIn(ref) || ref.startsWith("//") || ref.startsWith("#")) return null
        val out = if (ref.startsWith("/")) mutableListOf<String>() else
            from.split('/').dropLast(1).toMutableList()
        for (seg in ref.removePrefix("/").split('/')) {
            when {
                seg.isEmpty() || seg == "." -> continue
                seg == ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return out.joinToString("/").ifEmpty { null }
    }

    /** Bundle paths are byte-exact keys and are never case-folded, even though
     *  the address above them is: the signature covers `about.html`, so
     *  `ABOUT.HTML` is a path the owner never signed. */
    private fun dataUri(path: String, bytes: ByteArray): String? {
        val type = IMAGE_TYPES[path.substringAfterLast('.', "").lowercase()] ?: return null
        return "data:$type;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ────────────────────────────── the CSS ──────────────────────────────

    /**
     * Author CSS is kept, because it is what makes a bundle look like itself.
     * Anything that FETCHES is not.
     *
     * ★ Written as a scanner rather than as expressions, and the corpus is the
     * argument: `@\69 mport` and `\75 rl(` are `@import` and `url(` to a
     * conforming tokeniser, and no text expression matches them. So idents are
     * read with their escapes decoded — and then the ORIGINAL source slice is
     * what gets written back out, never the decoded form. Emitting the decoded
     * text would be its own hole: `\28` is an escaped `(` inside an ident, and
     * writing it back as a real one would turn a harmless ident into a live
     * function call.
     *
     * The fetching surface of CSS is not the `url()` token, it is every place a
     * URL may be spelled, and that set grows: `@import` in both its forms,
     * `@font-face`, `url()` in any property, and `image-set()`, which takes
     * bare strings and needs no `url(` at all.
     */
    internal fun cleanCss(css: String): String {
        val out = StringBuilder(css.length)
        var i = 0
        val n = css.length
        while (i < n) {
            val c = css[i]
            when {
                c == '/' && i + 1 < n && css[i + 1] == '*' -> i = skipComment(css, i)
                c == '"' || c == '\'' -> { val e = skipString(css, i); out.append(css, i, e); i = e }
                c == '@' -> {
                    val end = identEnd(css, i + 1)
                    when (identOf(css, i + 1, end).lowercase()) {
                        // A request wearing a stylesheet's clothes, and the one
                        // construct that pulls a whole second document. The
                        // rule runs to the `;`, or to the `{` when the author
                        // left the semicolon out — a browser's parser forgives
                        // that, so a cleaner that needs the semicolon leaves a
                        // live import behind.
                        "import" -> i = skipStatement(css, end)
                        // A webfont is the request an author has the best
                        // excuse for. `font-src 'none'` says the same thing in
                        // the frame's policy; the phones have only this half.
                        "font-face" -> i = skipBlock(css, end)
                        else -> { out.append(css, i, end); i = end }
                    }
                }
                isIdentStart(c) -> {
                    val end = identEnd(css, i)
                    val name = identOf(css, i, end).lowercase()
                    var j = end
                    while (j < n && css[j].isWhitespace()) j++
                    val fn = j < n && css[j] == '(' &&
                        (name == "url" || name == "image-set" || name == "-webkit-image-set")
                    if (!fn) { out.append(css, i, end); i = end; continue }
                    val close = matchParen(css, j)
                    val inner = css.substring(j + 1, close)
                    // A data: URI survives untouched, whitespace and quotes or
                    // not: it is the only URL form that cannot leave the
                    // device, it is what `img-src data:` allows, and it is how
                    // an author is meant to ship an image. A gap before the
                    // parenthesis means this was never a function token to
                    // begin with, so it is not rebuilt as one.
                    if (name == "url" && j == end && isDataUri(inner)) {
                        out.append("url(").append(inner).append(')')
                    } else {
                        // Every other URL becomes `none`, whatever property it
                        // sits in — including one pointing at a file that IS in
                        // the bundle. We do not rewrite CSS URLs into data:
                        // URIs: a bundle that wants a background image inlines
                        // it itself, and leaving the relative reference would
                        // be a live request to the island in a WebView with a
                        // real base URL.
                        out.append("none")
                    }
                    i = if (close < n) close + 1 else n
                }
                else -> { out.append(c); i++ }
            }
        }
        // ⚠⚠ A `<style>` element is RAW TEXT: the serialiser writes its content
        // verbatim because there is no escaping available inside one. A
        // stylesheet containing `</style>` would end the element early and turn
        // the rest of the file into live markup in the frame's parser. The
        // author of a stylesheet is the site owner, who is anyone with an
        // island account, so this is reachable by design and not only through a
        // compromised island. `\3c ` is the CSS escape for `<`, which means the
        // same thing to a CSS parser and nothing at all to an HTML one.
        return out.toString().replace("<", "\\3c ")
    }

    private fun isDataUri(inner: String): Boolean {
        val s = inner.trim().removePrefix("'").removePrefix("\"")
        return s.startsWith("data:", ignoreCase = true)
    }

    private fun isIdentStart(c: Char): Boolean =
        c.isLetter() || c == '-' || c == '_' || c == '\\' || c.code > 0x7F

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun identEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> i = escapeEnd(s, i)
                c.isLetterOrDigit() || c == '-' || c == '_' || c.code > 0x7F -> i++
                else -> return i
            }
        }
        return i
    }

    /** One CSS escape: a backslash, then 1-6 hex digits and at most one
     *  whitespace, or any single character. */
    private fun escapeEnd(s: String, at: Int): Int {
        var i = at + 1
        if (i < s.length && isHex(s[i])) {
            var k = 0
            while (i < s.length && k < 6 && isHex(s[i])) { i++; k++ }
            if (i + 1 < s.length && s[i] == '\r' && s[i + 1] == '\n') i += 2
            else if (i < s.length && s[i].isWhitespace()) i++
        } else if (i < s.length) i++
        return i
    }

    /** Every CSS escape in [s] resolved, for COMPARISON only. */
    private fun decodeEscapes(s: String): String =
        if (s.indexOf('\\') < 0) s else identOf(s, 0, s.length)

    /** The decoded ident, for COMPARISON only. See [cleanCss]. */
    private fun identOf(s: String, from: Int, end: Int): String {
        val sb = StringBuilder(end - from)
        var i = from
        while (i < end) {
            if (s[i] != '\\') { sb.append(s[i]); i++; continue }
            var j = i + 1
            if (j < end && isHex(s[j])) {
                val hs = j
                var k = 0
                while (j < end && k < 6 && isHex(s[j])) { j++; k++ }
                val cp = s.substring(hs, j).toIntOrNull(16) ?: 0xFFFD
                if (cp in 1..0x10FFFF) sb.appendCodePoint(cp) else sb.append('\uFFFD')
                if (j + 1 < end && s[j] == '\r' && s[j + 1] == '\n') j += 2
                else if (j < end && s[j].isWhitespace()) j++
            } else if (j < end) { sb.append(s[j]); j++ }
            i = j
        }
        return sb.toString()
    }

    private fun skipComment(s: String, at: Int): Int {
        val e = s.indexOf("*/", at + 2)
        return if (e < 0) s.length else e + 2
    }

    /** Index just past the closing quote, or past the newline that ends an
     *  unterminated string the way a CSS parser ends one. */
    private fun skipString(s: String, at: Int): Int {
        val q = s[at]
        var i = at + 1
        while (i < s.length) {
            when {
                s[i] == '\\' -> i += 2
                s[i] == q -> return i + 1
                s[i] == '\n' -> return i
                else -> i++
            }
        }
        return s.length
    }

    /** Index OF the matching `)`, strings and nesting honoured, or the end. */
    private fun matchParen(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when {
                s[i] == '\\' -> i += 2
                s[i] == '"' || s[i] == '\'' -> i = skipString(s, i)
                s[i] == '(' -> { depth++; i++ }
                s[i] == ')' -> { depth--; if (depth == 0) return i; i++ }
                else -> i++
            }
        }
        return s.length
    }

    /** Past the `;` that ends an at-rule, or up to the `{` that means the
     *  author left it out. The orphaned block a browser then drops is ugly and
     *  inert, which is the trade this makes on purpose. */
    private fun skipStatement(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            when {
                s[i] == '"' || s[i] == '\'' -> i = skipString(s, i)
                s[i] == '(' -> { val c = matchParen(s, i); i = if (c < s.length) c + 1 else s.length }
                s[i] == '/' && i + 1 < s.length && s[i + 1] == '*' -> i = skipComment(s, i)
                s[i] == ';' -> return i + 1
                s[i] == '{' -> return i
                else -> i++
            }
        }
        return s.length
    }

    /** An at-rule with a block: prelude, then balanced braces. Strings are
     *  honoured, so a `}` inside `font-family:"a}b"` does not end it early. */
    private fun skipBlock(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            when {
                s[i] == '"' || s[i] == '\'' -> i = skipString(s, i)
                s[i] == '/' && i + 1 < s.length && s[i + 1] == '*' -> i = skipComment(s, i)
                s[i] == ';' -> return i + 1
                s[i] == '{' -> {
                    var depth = 0
                    while (i < s.length) {
                        when {
                            s[i] == '"' || s[i] == '\'' -> i = skipString(s, i)
                            s[i] == '/' && i + 1 < s.length && s[i + 1] == '*' -> i = skipComment(s, i)
                            s[i] == '{' -> { depth++; i++ }
                            s[i] == '}' -> { depth--; i++; if (depth == 0) return i }
                            else -> i++
                        }
                    }
                    return s.length
                }
                else -> i++
            }
        }
        return s.length
    }

    // ───────────────────────────── serialising ─────────────────────────────

    private fun serialize(doc: Html): String {
        val head = mutableListOf<SiteNode>()
        val body = mutableListOf<SiteNode>()
        var inHead = true
        for (node in doc.roots) {
            if (inHead) {
                if (node is SiteNode.Text && node.text.isBlank()) continue
                if (node is SiteNode.Element && node.tag in HEAD_TAGS) { head.add(node); continue }
                inHead = false
            }
            body.add(node)
        }
        val sb = StringBuilder()
        sb.append("<!doctype html><html")
        // The root and body carry attributes too, and a walk that visited only
        // the content elements would never reach the `onload` on either of
        // them. `lang` survives: language and direction are typography, not
        // capability.
        writeAttrs(sb, doc.htmlAttrs.filter { keep("html", it) })
        sb.append("><head>").append(CSP)
        for (node in head) write(sb, node)
        sb.append("</head><body")
        writeAttrs(sb, doc.bodyAttrs.filter { keep("body", it) })
        sb.append('>')
        for (node in body) write(sb, node)
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun write(sb: StringBuilder, node: SiteNode) {
        when (node) {
            is SiteNode.Text -> sb.append(escapeText(node.text))
            is SiteNode.Element -> {
                sb.append('<').append(node.tag)
                writeAttrs(sb, node.attrs)
                sb.append('>')
                if (node.tag in VOID) return
                if (node.tag == "style") {
                    // Raw text on the way out, which is why [cleanCss] has to
                    // do the neutralising: there is no escaping in here.
                    for (child in node.children) if (child is SiteNode.Text) sb.append(child.text)
                } else {
                    for (child in node.children) write(sb, child)
                }
                sb.append("</").append(node.tag).append('>')
            }
        }
    }

    private fun writeAttrs(sb: StringBuilder, attrs: List<SiteAttr>) {
        for (a in attrs) {
            sb.append(' ').append(a.name).append("=\"").append(escapeAttr(a.value)).append('"')
        }
    }

    /**
     * ⚠ `<` and `>` only, and `&` deliberately left alone.
     *
     * Entities are never decoded on the way in, so `&amp;` arrives here as the
     * five characters the author wrote and leaves as the same five: the frame
     * decodes it once, exactly as the author meant. Decoding and re-encoding
     * would need the whole HTML5 entity table to round-trip, and getting that
     * table half right renders `&hellip;` as literal text. What must never
     * survive is a `<`, because the frame parses this string again and text
     * that comes back as an element is the mutation this pipeline exists to
     * prevent — the raw text inside `<xmp>` being the clearest case.
     */
    private fun escapeText(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }

    /**
     * ⚠ The quote, and nothing else. A value that arrived inside single quotes
     * may hold a double quote, and writing it out unescaped would end the
     * attribute early and let the rest of it become attributes of its own —
     * an `onclick` among them. `<` is left alone: an attribute value cannot be
     * broken by one, which is why a `data-rcq-external` holding
     * `data:text/html,<script>` reads as alarming and is inert.
     */
    private fun escapeAttr(s: String): String = buildString(s.length) {
        for (c in s) if (c == '"') append("&quot;") else append(c)
    }

    // ────────────────────────── the tokeniser ──────────────────────────

    /**
     * A tolerant HTML tokeniser and tree builder. Iterative on purpose: the
     * input is a stranger's document, and the passes above are the recursive
     * half (see [MAX_DEPTH]).
     */
    private class Html(private val src: String) {

        val roots = mutableListOf<SiteNode>()
        val htmlAttrs = mutableListOf<SiteAttr>()
        val bodyAttrs = mutableListOf<SiteAttr>()

        private val n = src.length
        private var i = 0
        private val open = ArrayList<SiteNode.Element>()

        private fun current(): MutableList<SiteNode> =
            if (open.isEmpty()) roots else open[open.size - 1].children

        private fun inForeign(): Boolean = open.any { it.tag == "svg" || it.tag == "math" }

        fun parse(): Html {
            while (i < n) {
                val lt = src.indexOf('<', i)
                if (lt < 0) { text(src.substring(i)); break }
                if (lt > i) text(src.substring(i, lt))
                i = lt
                when {
                    // ⚠ Comments are DROPPED, not passed through. Two reasons.
                    // A comment is where build tooling leaves absolute paths,
                    // usernames and tool versions, and this project spent a
                    // whole audit removing that class of leak from everything
                    // else it publishes. And conditional or bogus comments are
                    // a classic place for two HTML parsers to disagree about
                    // where the comment ends — this pipeline runs two.
                    src.startsWith("<!--", i) -> {
                        val e = src.indexOf("-->", i + 4)
                        i = if (e < 0) n else e + 3
                    }
                    src.startsWith("<!", i) || src.startsWith("<?", i) -> {
                        val e = src.indexOf('>', i)
                        i = if (e < 0) n else e + 1
                    }
                    src.startsWith("</", i) -> endTag()
                    i + 1 < n && src[i + 1].isLetter() -> startTag()
                    else -> { text("<"); i++ }
                }
            }
            return this
        }

        private fun text(s: String) {
            if (s.isNotEmpty()) current().add(SiteNode.Text(s))
        }

        private fun isNameChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '-' || c == ':' || c == '_'

        private fun startTag() {
            i++
            val start = i
            while (i < n && isNameChar(src[i])) i++
            var name = src.substring(start, i).lowercase()
            val attrs = mutableListOf<SiteAttr>()
            var selfClosing = false
            loop@ while (i < n) {
                while (i < n && src[i].isWhitespace()) i++
                if (i >= n) break
                when {
                    src[i] == '>' -> { i++; break@loop }
                    src[i] == '/' -> {
                        if (i + 1 < n && src[i + 1] == '>') { selfClosing = true; i += 2; break@loop }
                        i++
                    }
                    else -> readAttr(attrs)
                }
            }
            // ⚠ `<image>` outside foreign content is renamed to `img` by every
            // HTML parser, so it has to be renamed here too — otherwise the
            // image pass never sees it and the frame's parser gets an element
            // it treats as an img and fetches.
            if (name == "image" && !inForeign()) name = "img"
            when (name) {
                "html" -> { merge(htmlAttrs, attrs); return }
                "body" -> { merge(bodyAttrs, attrs); return }
                "head" -> return
            }
            if (!inForeign()) closeImplied(name)
            val el = SiteNode.Element(name, attrs)
            current().add(el)
            val foreign = inForeign() || name == "svg" || name == "math"
            when {
                // No closing form: it swallows the rest of the document as
                // text. Removed or unwrapped later like anything else, but the
                // text must not be re-tokenised as markup.
                name == "plaintext" -> { el.children.add(SiteNode.Text(src.substring(i))); i = n }
                name in TEXT_ONLY && !selfClosing -> readTextOnly(el, name)
                name in VOID -> Unit
                selfClosing && foreign -> Unit
                open.size >= MAX_DEPTH -> Unit
                else -> open.add(el)
            }
        }

        private fun readAttr(attrs: MutableList<SiteAttr>) {
            val start = i
            while (i < n && !src[i].isWhitespace() && src[i] != '=' && src[i] != '>' && src[i] != '/') i++
            if (i == start) { i++; return }
            val name = src.substring(start, i).lowercase()
            var value = ""
            var j = i
            while (j < n && src[j].isWhitespace()) j++
            if (j < n && src[j] == '=') {
                j++
                while (j < n && src[j].isWhitespace()) j++
                if (j < n && (src[j] == '"' || src[j] == '\'')) {
                    val q = src[j]
                    j++
                    val e = src.indexOf(q, j)
                    if (e < 0) { value = src.substring(j); j = n } else { value = src.substring(j, e); j = e + 1 }
                } else {
                    val vs = j
                    while (j < n && !src[j].isWhitespace() && src[j] != '>') j++
                    value = src.substring(vs, j)
                }
                i = j
            }
            // First wins, as in a real parser: a duplicate cannot overwrite the
            // one the earlier passes already inspected.
            if (attrs.none { it.name == name }) attrs.add(SiteAttr(name, value))
        }

        /** Everything up to the matching end tag is TEXT, whatever it looks
         *  like. Its end tag is matched case-insensitively and must be followed
         *  by whitespace, `/` or `>`, the way the tokeniser matches it. */
        private fun readTextOnly(el: SiteNode.Element, name: String) {
            val start = i
            var from = i
            var end = -1
            while (from < n) {
                val at = indexOfIgnoreCase("</$name", from)
                if (at < 0) break
                val after = at + 2 + name.length
                if (after >= n || src[after].isWhitespace() || src[after] == '>' || src[after] == '/') {
                    end = at
                    break
                }
                from = at + 1
            }
            if (end < 0) {
                if (start < n) el.children.add(SiteNode.Text(src.substring(start)))
                i = n
            } else {
                if (end > start) el.children.add(SiteNode.Text(src.substring(start, end)))
                val gt = src.indexOf('>', end)
                i = if (gt < 0) n else gt + 1
            }
        }

        /** Deliberately not `String.indexOf(ignoreCase = true)`: that folds
         *  case with the platform's rules on both sides, and the needle here is
         *  ASCII. `p`, not `i`, so nothing can walk the tokeniser's cursor. */
        private fun indexOfIgnoreCase(needle: String, from: Int): Int {
            var p = from
            val last = n - needle.length
            outer@ while (p <= last) {
                for (k in needle.indices) {
                    if (src[p + k].lowercaseChar() != needle[k]) { p++; continue@outer }
                }
                return p
            }
            return -1
        }

        private fun endTag() {
            i += 2
            val start = i
            while (i < n && isNameChar(src[i])) i++
            val name = src.substring(start, i).lowercase()
            val gt = src.indexOf('>', i)
            i = if (gt < 0) n else gt + 1
            if (name.isEmpty() || name == "html" || name == "head" || name == "body") return
            val idx = open.indexOfLast { it.tag == name }
            if (idx >= 0) while (open.size > idx) open.removeAt(open.size - 1)
        }

        private fun closeImplied(name: String) {
            val top = open.lastOrNull()?.tag ?: return
            when (name) {
                "li" -> if (top == "li") pop()
                "dt", "dd" -> if (top == "dt" || top == "dd") pop()
                "td", "th" -> if (top == "td" || top == "th") pop()
                "tr" -> {
                    if (top == "td" || top == "th") pop()
                    if (open.lastOrNull()?.tag == "tr") pop()
                }
                "option" -> if (top == "option") pop()
            }
            if (name in CLOSES_P && open.lastOrNull()?.tag == "p") pop()
        }

        private fun pop() {
            if (open.isNotEmpty()) open.removeAt(open.size - 1)
        }

        private fun merge(into: MutableList<SiteAttr>, from: List<SiteAttr>) {
            for (a in from) if (into.none { it.name == a.name }) into.add(a)
        }
    }
}

internal class SiteAttr(val name: String, var value: String)

internal sealed class SiteNode {

    class Text(val text: String) : SiteNode()

    class Element(
        val tag: String,
        val attrs: MutableList<SiteAttr> = mutableListOf(),
        val children: MutableList<SiteNode> = mutableListOf(),
    ) : SiteNode() {

        fun attr(name: String): String? = attrs.firstOrNull { it.name == name }?.value

        /** Overwrites in place when the attribute is already there, so an
         *  author's `title` keeps its position and loses its value. */
        fun setAttr(name: String, value: String) {
            val found = attrs.firstOrNull { it.name == name }
            if (found != null) found.value = value else attrs.add(SiteAttr(name, value))
        }
    }
}
