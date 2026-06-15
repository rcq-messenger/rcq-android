package app.rcq.android.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import app.rcq.android.R
import app.rcq.android.data.LocalStores

/**
 * The classic KOLOBOK emoticon set, bundled in `assets/emoticons/<name>.gif`
 * (copied byte-for-byte from the iOS app's Resources/Emoticons). Reaction
 * asset names match iOS exactly, so a reaction renders identically on both
 * clients — iOS sends e.g. "smile", and we render the same GIF (and vice
 * versa) instead of the old mismatch where Android sent a system emoji that
 * iOS couldn't render and iOS sent an emoticon name Android showed as text.
 */
internal object Emoticons {
    /** Default reactions when the user hasn't customised their set (mirrors the
     *  historical fixed list). The user can now pick up to 6 of their own in the
     *  emoji-customise sheet; see LocalStores.reactionEmojis. */
    val defaultReactions = listOf("good", "give_heart", "biggrin", "shok", "cray", "mad")

    /** Extra koloboks (from the full Kolobok library) the user can pick into
     *  their panel / reactions, on top of the original set below. Asset names
     *  are the `:code:` wire form and MUST be bundled identically on iOS+Android. */
    val extraKoloboks = listOf(
        "Cherna_01", "FinouCat_02", "Koshechka_06", "Laie_74", "Mauridia_02",
        "Rulezzz_03", "WhiteVoid_1", "d_clock", "kirtsun_05", "l_girl_kiss",
        "l_lovers", "l_teddy", "snoozer_likelinux_man", "viannen_03", "viannen_06",
        "viannen_09", "viannen_35", "viannen_48", "viannen_76", "viannen_88",
    )

    /** The original composer palette: (asset, display name), Kolobok ICQ "set 14".
     *  Codes are the `:asset:` form; must match the iOS `Emoticons.entries`. */
    val palette: List<Pair<String, String>> = listOf(
        "smile" to "Happy", "biggrin" to "Laughing", "lol" to "LOL", "rofl" to "ROFL",
        "good" to "Thumbs Up", "give_heart" to "Heart", "man_in_love" to "In Love", "give_rose" to "Rose",
        "kiss" to "Kiss", "kiss3" to "Smooch", "air_kiss" to "Air Kiss", "blush" to "Embarrassed",
        "i_am_so_happy" to "So Happy", "dance" to "Dancing", "music" to "Music", "cool" to "Cool",
        "gamer" to "Gamer", "drinks" to "Cheers", "hi" to "Hi", "bye2" to "Bye",
        "blum1" to "Tongue", "mocking" to "Teasing", "crazy" to "Crazy", "wacko1" to "Wacko",
        "nea" to "Pensive", "scratch_one-s_head" to "Thinking", "unknown" to "Dunno", "shok" to "Shocked",
        "sad" to "Sad", "cray" to "Crying", "pardon" to "Pardon", "sorry" to "Sorry",
        "mad" to "Angry", "ireful" to "Furious", "shout" to "Shouting", "bad" to "Sick",
        "diablo" to "Devil", "bomb" to "Bomb", "girl_angel" to "Angel", "hang1" to "Hang",
    )

    /** The full pickable set the customise sheet offers: the original palette
     *  plus the extra koloboks. Order = palette first, then extras. */
    val fullSet: List<String> = palette.map { it.first } + extraKoloboks

    /** Asset names that have a `:code:` (for tokenizing message bodies) — the
     *  WHOLE bundled set, so a `:viannen_03:` from a peer renders too. */
    private val codes: Set<String> = fullSet.toSet()

    private val cache = HashMap<String, ByteArray?>()

    /** Raw GIF bytes for an emoticon [name] from assets (cached). Null when
     *  there's no such asset (e.g. a plain-emoji reaction from an old client). */
    fun bytes(context: Context, name: String): ByteArray? {
        synchronized(cache) { if (cache.containsKey(name)) return cache[name] }
        val b = runCatching {
            context.applicationContext.assets.open("emoticons/$name.gif").use { it.readBytes() }
        }.getOrNull()
        synchronized(cache) { cache[name] = b }
        return b
    }

    fun isEmoticon(context: Context, name: String): Boolean = bytes(context, name) != null

    /** A run of a tokenized message body: literal text or an emoticon. */
    sealed interface Token {
        data class Text(val text: String) : Token
        data class Emo(val asset: String, val code: String) : Token
    }

    // `:asset:` codes only (iOS parity — short shortcuts like :) are NOT parsed,
    // they collide with URLs/math). Asset names use [A-Za-z0-9_!-].
    private val TOKEN_RE = Regex(":([A-Za-z0-9_!-]+):")

    /** A `#<uin>` mention in a message body (3+ digits to avoid matching `#1`
     *  or `#ff0000`-style tokens). Rendered as the user's clickable nick when a
     *  resolver is supplied to [EmoticonText]. */
    val MENTION_RE = Regex("#(\\d{3,})")

    /** An `@nickname` mention in a message body. Resolved against the group
     *  roster (case-insensitive) by [EmoticonText]; an unmatched `@foo` (or an
     *  email's `@domain`) stays plain text. Mirrors the iOS MentionParser
     *  pattern: letters/digits/underscore/dot/hyphen (so ".Dev" resolves). */
    val MENTION_AT_RE = Regex("@([\\p{L}\\p{N}_.\\-]+)")
    val URL_RE = Regex("https?://\\S+")

    /** Split [text] into text runs + known `:asset:` emoticons. Returns a single
     *  Text token when there are no emoticons (the common case). */
    fun tokenize(text: String): List<Token> {
        if (!text.contains(':')) return listOf(Token.Text(text))
        val out = ArrayList<Token>()
        var last = 0
        for (m in TOKEN_RE.findAll(text)) {
            val asset = m.groupValues[1]
            if (asset !in codes) continue
            if (m.range.first > last) out.add(Token.Text(text.substring(last, m.range.first)))
            out.add(Token.Emo(asset, m.value))
            last = m.range.last + 1
        }
        if (out.isEmpty()) return listOf(Token.Text(text))
        if (last < text.length) out.add(Token.Text(text.substring(last)))
        return out
    }

    fun hasEmoticon(text: String): Boolean =
        text.contains(':') && TOKEN_RE.findAll(text).any { it.groupValues[1] in codes }

    /** (start, endExclusive, asset) for every known `:code:` in [text] — used to
     *  paint inline ImageSpans in the composer's native EditText. */
    fun codeSpans(text: String): List<Triple<Int, Int, String>> {
        if (!text.contains(':')) return emptyList()
        return TOKEN_RE.findAll(text).mapNotNull { m ->
            val a = m.groupValues[1]
            if (a in codes) Triple(m.range.first, m.range.last + 1, a) else null
        }.toList()
    }
}

/** Render a bundled emoticon GIF by [name] — animated on API 28+, a frozen
 *  first frame below that (minSdk is 26). Renders nothing if the asset is
 *  missing. */
@Composable
internal fun EmoticonGif(name: String, modifier: Modifier, animate: Boolean = true) {
    val context = LocalContext.current
    val bytes by produceState<ByteArray?>(initialValue = null, name) {
        value = Emoticons.bytes(context, name)
    }
    val b = bytes ?: return
    // [animate]=false (e.g. the 28-emoticon picker grid, message history) shows a
    // frozen first frame so we don't run dozens of frame loops at once. Both
    // paths use the PURE-JAVA GIF decoder (SafeGif.kt) — the platform Skia GIF
    // decoder SIGSEGVs on some OEM ROMs (realme/ColorOS), which crashed every
    // emoticon render and was a v0.30–0.33 launch-crash path.
    if (animate) {
        SafeAnimatedGif(b, modifier)
    } else {
        // Static first frame, decoded ONCE per asset and shared process-wide. The
        // same `:code:` recurs across many history rows / reaction chips; without
        // the cache each occurrence decoded its own bitmap (bytes were cached, the
        // decode was not), piling up allocations in emoticon-dense groups.
        val img = remember(name) { staticEmoticonBitmap(name, b) }
        if (img != null) Image(bitmap = img, contentDescription = null, modifier = modifier)
    }
}

private val staticEmoticonBitmaps = HashMap<String, ImageBitmap?>()

/** Decoded static first frame for emoticon [name], cached process-wide. GIFs
 *  (every Kolobok asset) decode via the PURE-JAVA decoder; a JPEG/PNG asset
 *  would use the safe native path. Never touches the native GIF decoder, which
 *  SIGSEGVs on some OEM ROMs. */
private fun staticEmoticonBitmap(name: String, bytes: ByteArray): ImageBitmap? =
    synchronized(staticEmoticonBitmaps) {
        staticEmoticonBitmaps.getOrPut(name) {
            runCatching {
                val bmp = if (bytes.isGif()) gifFirstFrame(bytes)
                else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bmp?.asImageBitmap()
            }.getOrNull()
        }
    }

/** A reaction chip under a message bubble: a small emoticon GIF when the
 *  reaction is a known KOLOBOK asset, else the raw string (plain-emoji
 *  reactions from older clients still show). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ReactionChip(
    asset: String,
    count: Int? = null,   // null = no number (Radio/Hood chips); a count of 1 is also hidden (a lone reaction needs no "1")
    mine: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val isEmoticon = remember(asset) { Emoticons.isEmoticon(context, asset) }
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (mine) c.accent.copy(alpha = 0.22f) else c.bgSecondary)
            .let {
                if (onClick != null || onLongClick != null) {
                    it.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
                } else it
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Animated via the shared-frame cache (AnimatedEmoticon) — safe even
        // with many chips on screen because frames decode ONCE process-wide and
        // cells just cycle them (no per-chip decoder, so not the old OOM).
        if (isEmoticon) AnimatedEmoticon(asset, Modifier.size(16.dp))
        else Text(asset, fontSize = 13.sp, color = c.textPrimary)
        // A single reactor needs no "1" — show the number only once it grows
        // past one (founder feedback). Radio/Hood pass null (never numbered).
        if (count != null && count > 1) {
            Text("$count", fontSize = 11.sp, color = c.textPrimary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    }
}

/** A message/caption body with inline `:asset:` emoticons rendered as small
 *  GIFs (iOS EmoticonText parity). Falls back to a plain [Text] when the body
 *  has no emoticon codes (the common path — no inline-content overhead). */
@Composable
internal fun EmoticonText(
    body: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    // When supplied, a `#<uin>` in the body whose uin resolves to a nick renders
    // as the clickable accent nick (tap → [onMentionClick]); else it stays plain
    // digits. Null = no mention handling (the default for non-message text).
    mentionNick: ((Int) -> String?)? = null,
    onMentionClick: ((Int) -> Unit)? = null,
    // When supplied, an `@nickname` whose nick resolves to a group member's uin
    // renders as the clickable accent nick (tap → [onMentionClick]); else plain.
    mentionUin: ((String) -> Int?)? = null,
    // #2: cap visible lines (collapsed long message); Int.MAX_VALUE = no cap.
    maxLines: Int = Int.MAX_VALUE,
    // #2: reports the layout so the caller can tell if the text was actually
    // truncated (hasVisualOverflow) and only then offer "Show more".
    onTextLayout: ((androidx.compose.ui.text.TextLayoutResult) -> Unit)? = null,
) {
    val tokens = remember(body) { Emoticons.tokenize(body) }
    val accent = RcqTheme.colors.accent
    val overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis
    val layoutCb: (androidx.compose.ui.text.TextLayoutResult) -> Unit = { onTextLayout?.invoke(it) }
    val hasMention =
        (mentionNick != null && body.contains('#') && Emoticons.MENTION_RE.containsMatchIn(body)) ||
        (mentionUin != null && body.contains('@') && Emoticons.MENTION_AT_RE.containsMatchIn(body))
    // http(s) links are made tappable in the body too (report: links in chats
    // weren't clickable). Cheap "://" gate before the regex.
    val hasUrl = body.contains("://") && Emoticons.URL_RE.containsMatchIn(body)
    // Fast path: a pure-text body with no resolvable mentions and no links.
    if (tokens.size == 1 && tokens[0] is Emoticons.Token.Text && !hasMention && !hasUrl) {
        Text(body, color = color, fontSize = fontSize, lineHeight = lineHeight, modifier = modifier, maxLines = maxLines, overflow = overflow, onTextLayout = layoutCb)
        return
    }
    // Solo-emoticon message (#12): the whole body is a single `:code:` — animate
    // it (the common "send a smiley" case). Bounded to ONE frame loop per
    // VISIBLE message (LazyColumn composes only on-screen rows), so it avoids
    // the inline-in-text / picker churn that forced static frames elsewhere.
    (tokens.singleOrNull() as? Emoticons.Token.Emo)?.let { emo ->
        if (!hasMention) {
            AnimatedEmoticon(emo.asset, Modifier.size(28.dp))
            return
        }
    }
    // MEMOIZE the annotated string + inline-emoticon content. Without this,
    // every recomposition rebuilt the inline map with FRESH composable lambdas,
    // so each inline emoticon was recreated → re-read its GIF bytes + re-decoded
    // a bitmap. The IME-show animation recomposes the message list every frame,
    // so in an emoticon-dense large group (e.g. 832-member RCQ Beta) that meant
    // dozens of GIFs re-decoded per frame → ~12MB/frame allocation → GC thrash →
    // the composer froze on focus and OOM-crashed on weaker devices (couldn't
    // even type). The resolvers are remember()'d by the caller, so this only
    // rebuilds when the body or a resolver actually changes. animate=false also
    // renders a static first frame (no AnimatedImageDrawable churn).
    val (annotated, inline) = remember(body, mentionNick, mentionUin, onMentionClick, accent) {
        val inlineMap = HashMap<String, InlineTextContent>()
        val ann = buildAnnotatedString {
            for (t in tokens) when (t) {
                is Emoticons.Token.Text -> appendWithMentions(t.text, mentionNick, mentionUin, onMentionClick, accent)
                is Emoticons.Token.Emo -> {
                    appendInlineContent(t.asset, t.code)
                    if (t.asset !in inlineMap) {
                        val asset = t.asset
                        inlineMap[asset] = InlineTextContent(
                            // 1.45em — slightly smaller than before (founder).
                            // em-relative so captions / smaller-font chats scale.
                            // ANIMATED via the shared-frame cache (AnimatedEmoticon):
                            // frames decode ONCE process-wide and cells just cycle
                            // them, so even the IME-recompose storm never re-decodes
                            // (the old OOM) — no per-cell decoder. Bounded by the
                            // LazyColumn (only visible rows compose).
                            Placeholder(width = 1.45.em, height = 1.45.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
                        ) { AnimatedEmoticon(asset, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        ann to inlineMap
    }
    Text(annotated, color = color, fontSize = fontSize, lineHeight = lineHeight, inlineContent = inline, modifier = modifier, maxLines = maxLines, overflow = overflow, onTextLayout = layoutCb)
}

/** Append [text], turning each resolvable mention into a clickable accent nick
 *  (tap → [onMentionClick]): `#<uin>` via [mentionNick] (renders the nick), and
 *  `@nickname` via [mentionUin] (renders the typed `@nick`). Unresolved or
 *  no-resolver tokens stay plain. Both kinds are merged in source order. */
private fun AnnotatedString.Builder.appendWithMentions(
    text: String,
    mentionNick: ((Int) -> String?)?,
    mentionUin: ((String) -> Int?)?,
    onMentionClick: ((Int) -> Unit)?,
    accent: Color,
) {
    // url=true -> a tappable http(s) link (display = the URL); otherwise a
    // mention (uin + display nick). Both kinds are merged in source order.
    data class Hit(val range: IntRange, val url: Boolean, val uin: Int, val display: String)
    val hits = ArrayList<Hit>()
    if (mentionNick != null) {
        for (m in Emoticons.MENTION_RE.findAll(text)) {
            val uin = m.groupValues[1].toIntOrNull() ?: continue
            val nick = mentionNick(uin) ?: continue
            hits.add(Hit(m.range, false, uin, nick))
        }
    }
    if (mentionUin != null) {
        for (m in Emoticons.MENTION_AT_RE.findAll(text)) {
            val uin = mentionUin(m.groupValues[1]) ?: continue
            hits.add(Hit(m.range, false, uin, m.value)) // keep the typed "@nick"
        }
    }
    for (m in Emoticons.URL_RE.findAll(text)) {
        hits.add(Hit(m.range, true, 0, m.value))
    }
    if (hits.isEmpty()) { append(text); return }
    hits.sortBy { it.range.first }
    var cursor = 0
    for (h in hits) {
        if (h.range.first < cursor) continue // skip overlaps
        if (h.range.first > cursor) append(text.substring(cursor, h.range.first))
        if (h.url) {
            // LinkAnnotation.Url auto-opens via the platform UriHandler (and a
            // rcq.app/g/ link is caught by our deep-link filter -> opens in-app).
            withLink(LinkAnnotation.Url(h.display)) {
                withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(h.display) }
            }
        } else {
            withLink(LinkAnnotation.Clickable(tag = "m${h.uin}", linkInteractionListener = { onMentionClick?.invoke(h.uin) })) {
                withStyle(SpanStyle(color = accent)) { append(h.display) }
            }
        }
        cursor = h.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

/** The composer smiley panel — now showing the user's OWN chosen emoticons
 *  ([LocalStores.panelEmojis]) rather than the whole palette. It starts EMPTY
 *  with a centered "Choose" CTA that opens [EmojiPickerDialog] (where the user
 *  also picks their quick reactions); once a set is chosen the grid shows those
 *  assets plus a small "Edit" affordance to reopen the picker. Tapping an
 *  emoticon calls [onPick] with its `:asset:` code to splice into the draft.
 *
 *  Cells ANIMATE SAFELY via [AnimatedEmoticon]/[decodeGifFrames]: every asset's
 *  frames are decoded ONCE into a shared, process-wide cache and the cells just
 *  cycle the pre-decoded bitmaps — no per-cell decoder, no per-frame
 *  allocation. This is the crash-safe replacement for the old per-cell
 *  [SafeAnimatedGif] approach, which spun up live decoders churning a fresh
 *  bitmap every frame and OOM-crashed low-RAM devices (the "crashes when using
 *  smileys" report on Redmi Note 7 / Android 10). */
@Composable
internal fun EmoticonPanel(onPick: (String) -> Unit) {
    val c = RcqTheme.colors
    val panel by LocalStores.panelEmojis.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) EmojiPickerDialog(onDismiss = { showPicker = false })

    if (panel.isEmpty()) {
        // Empty by default: a centered CTA inviting the user to choose their own
        // panel set (and, in the same window, their quick reactions).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(c.bgSecondary)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.emoji_choose_cta),
                color = c.textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showPicker = true }) {
                Text(stringResource(R.string.emoji_choose_btn))
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth().background(c.bgSecondary)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { showPicker = true }) {
                Text(stringResource(R.string.emoji_edit), color = c.accent, fontSize = 13.sp)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 46.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(panel.size) { i ->
                val asset = panel[i]
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).clickable { onPick(":$asset:") },
                    contentAlignment = Alignment.Center,
                ) { AnimatedEmoticon(asset, Modifier.size(30.dp)) }
            }
        }
    }
}

/** An emoticon that ANIMATES from the shared pre-decoded frame cache
 *  ([decodeGifFrames]). Safe to have MANY on screen at once (the whole picker
 *  grid, or every inline emoticon across the visible chat rows): no cell owns a
 *  decoder and playback allocates nothing — it only cycles cached frames, and
 *  frames are decoded ONCE process-wide, so even a recompose storm never
 *  re-decodes. Shows the static first frame while decoding (or a 1-frame asset). */
@Composable
internal fun AnimatedEmoticon(name: String, modifier: Modifier) {
    val context = LocalContext.current
    val bytes by produceState<ByteArray?>(initialValue = null, name) {
        value = Emoticons.bytes(context, name)
    }
    val b = bytes ?: return
    val frames by produceState<GifFrames?>(initialValue = null, name, b) {
        value = withContext(Dispatchers.Default) { decodeGifFrames(name, b) }
    }
    val f = frames
    if (f == null || f.frames.size <= 1) {
        // Decoding, or a single-frame asset → the shared static first frame.
        val img = remember(name) { staticEmoticonBitmap(name, b) }
        if (img != null) Image(bitmap = img, contentDescription = null, modifier = modifier)
        return
    }
    var idx by remember(name) { mutableStateOf(0) }
    LaunchedEffect(f) {
        idx = 0
        while (isActive) {
            delay(f.delaysMs[idx.coerceIn(0, f.delaysMs.lastIndex)].toLong())
            idx = (idx + 1) % f.frames.size
        }
    }
    Image(bitmap = f.frames[idx.coerceIn(0, f.frames.lastIndex)], contentDescription = null, modifier = modifier)
}
