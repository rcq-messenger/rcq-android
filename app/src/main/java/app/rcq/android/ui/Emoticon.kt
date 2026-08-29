package app.rcq.android.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput

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
     *  historical fixed list). The user can pick up to 40 of their own in the
     *  emoji-customise sheet; see LocalStores.reactionEmojis, which reads this
     *  list rather than keeping a second copy of it.
     *
     *  ⚠ Every name must be in [standardPack], i.e. must have a bundled GIF.
     *  The copy that used to live in LocalStores did not, and three of its six
     *  slots drew nothing. */
    val defaultReactions = listOf("good", "give_heart", "laugh1", "scare", "cray", "ireful1")

    /** The "standart" Kolobok set (258 glyphs), bundled on every client.
     *
     *  A plain list, not 258 hand-written pairs: the display name is mechanical
     *  and the three clients MUST agree on this set exactly — a `:code:` missing
     *  here renders as raw text on Android and nowhere else.
     *
     *  Additive on purpose: the older set stays bundled even where this one has
     *  no replacement, because those codes are already in people's history. */
    val standardPack: List<String> = listOf(
        "acute", "aggressive", "agree", "aikido", "air_kiss", "alcoholic", "angel",
        "assassin", "bad", "banned", "beach", "beee", "beta", "big_boss", "black_eye",
        "blind", "blum2", "blum3", "blush2", "boast", "boredom", "brunette", "buba",
        "buba_phone", "butcher", "censored", "clapping", "comando", "cray", "cray2",
        "crazy", "crazy_pilot", "curtsey", "dance", "dance2", "dance3", "dance4",
        "dash1", "dash2", "dash3", "declare", "ded_moroz", "ded_snegurochka",
        "ded_snegurochka2", "dinamo", "dirol", "dntknw", "don-t_mention", "download",
        "drinks", "dwarf", "elf", "facepalm", "fan_1", "fans", "feminist",
        "feminist_en", "first_move", "flirt", "focus", "fool", "friends", "gamer1",
        "gamer2", "gamer3", "gamer4", "girl_blum", "girl_blum2", "girl_cray",
        "girl_cray2", "girl_cray3", "girl_crazy", "girl_dance", "girl_drink1",
        "girl_drink2", "girl_drink3", "girl_drink4", "girl_haha", "girl_hide",
        "girl_hospital", "girl_impossible", "girl_in_love", "girl_mad",
        "girl_prepare_fish", "girl_sad", "girl_sigh", "girl_smile",
        "girl_to_take_umbrage", "girl_to_take_umbrage2", "girl_wacko", "girl_werewolf",
        "girl_wink", "girl_witch", "give_heart", "give_rose", "good", "good2", "good3",
        "heat", "help", "hi", "hunter", "hysteric", "i-m_so_happy", "ireful1",
        "ireful2", "ireful3", "jester", "king", "king2", "kiss", "kiss2", "kiss3",
        "laugh1", "laugh2", "laugh3", "lazy", "lazy2", "lazy3", "locomotive", "mail1",
        "mamba", "man_in_love", "mda", "meeting", "moil", "morpheus", "mosking",
        "music", "music2", "nea", "negative", "neo", "new_russian", "nhl", "nhl2",
        "nhl3", "nhl_checking", "nhl_crach", "nhl_fight", "no2", "offtopic", "ok",
        "on_the_quiet", "on_the_quiet2", "orc", "padonak", "paint", "paint2", "paint3",
        "paladin", "pardon", "parting", "parting2", "party", "patsak", "phi", "pilot",
        "pioneer", "pioneer_smoke", "pleasantry", "pogranichnik", "polling", "popcorm1",
        "popcorm2", "prankster", "prankster2", "preved", "protest", "punish", "punish2",
        "queen", "rabbi", "rap", "read", "resent", "rofl", "russian", "sad", "santa",
        "santa2", "santa3", "sarcasm", "sarcastic", "sarcastic_blum", "sarcastic_hand",
        "scare", "scare2", "scenic", "sclerosis", "scout", "scout_en", "scratch_one-s_head", "search", "secret", "shablon_01", "shablon_02", "shablon_03",
        "shablon_04", "shout", "slow", "slow_en", "smile3", "smoke", "snegurochka",
        "snooks", "sorry", "sorry2", "spartak", "spruce_up", "stinker", "stop",
        "sun_bespectacled", "superman", "superman2", "superstition", "swoon", "swoon2",
        "take_example", "taunt", "tease", "telephone", "tender", "thank_you",
        "thank_you2", "this", "to_babruysk", "to_become_senile", "to_clue",
        "to_keep_order", "to_pick_ones_nose", "to_pick_ones_nose2",
        "to_pick_ones_nose3", "to_pick_ones_nose_eat", "to_take_umbrage", "tommy",
        "training1", "triniti", "umnik", "umnik2", "vampire", "victory", "vinsent",
        "wacko", "wacko2", "warning", "warning2", "whistle", "whistle2", "whistle3",
        "wild", "wink3", "wizard", "yahoo", "yes2", "yes3", "yes4", "yu"
    )

    /** "to_pick_ones_nose" -> "To pick ones nose". Standard pack only; the
     *  curated palette above keeps its hand-written names. */
    private fun displayName(asset: String): String =
        asset.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }

    /** The full pickable set the customise sheet offers: the original palette,
     *  then the extra koloboks, then the standard pack. */
    /** What the customise sheet OFFERS: the current pack, and only it. The older
     *  set stays bundled and stays tokenizable (see [codes]) so a `:smile:` sent
     *  last week still draws a smiley instead of turning into raw text — but it
     *  is not offered any more, or the grid would mix two drawing styles. */
    val fullSet: List<String> = standardPack

    /** Every asset that has a `:code:`, INCLUDING the retired set: a body is
     *  tokenized against this, never against what the picker offers. */
    private val tokenizable: List<String> = standardPack

    /** Display name for any bundled asset. */
    fun nameOf(asset: String): String = displayName(asset)

    /** Asset names that have a `:code:` (for tokenizing message bodies) — the
     *  WHOLE bundled set, so a `:viannen_03:` from a peer renders too. */
    private val codes: Set<String> = tokenizable.toSet()

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

    private val aspects = HashMap<String, Float>()

    /** Width / height of [name] from the GIF header, 1f when unknown.
     *
     *  The set is not square — glyphs run from 20x20 to 38x27 — so a square
     *  inline box shrinks the wide ones to fit and a line of smileys comes out
     *  uneven. Callers size by HEIGHT and take the width from here, which is
     *  what iOS does. The logical screen size lives in bytes 6..9 of every GIF,
     *  little-endian, so this costs a lookup in the already-cached blob. */
    fun aspect(context: Context, name: String): Float {
        synchronized(aspects) { aspects[name]?.let { return it } }
        val b = bytes(context, name)
        val a = if (b != null && b.size >= 10) {
            val w = (b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8)
            val h = (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8)
            if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 1f
        } else 1f
        synchronized(aspects) { aspects[name] = a }
        return a
    }

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
@Composable
internal fun ReactionChip(
    asset: String,
    count: Int? = null,   // null = no number (Radio chips); a count of 1 is also hidden (a lone reaction needs no "1")
    mine: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val isEmoticon = remember(asset) { Emoticons.isEmoticon(context, asset) }
    // The pointer node outlives a recomposition, so it must not capture the
    // first callback it ever saw (the chip's message changes as rows are reused).
    val longClick by rememberUpdatedState(onLongClick)
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (mine) c.accent.copy(alpha = 0.22f) else c.bgSecondary)
            .let {
                // Tap only: the long press is a separate detector because
                // `combinedClickable` fires it under a moving finger — see
                // [longPressUnlessScrolled].
                if (onClick != null || onLongClick != null) {
                    it.clickable { onClick?.invoke() }
                } else it
            }
            .longPressUnlessScrolled(enabled = onLongClick != null) { longClick?.invoke() }
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
        // past one (founder feedback). Radio passes null (never numbered).
        if (count != null && count > 1) {
            Text("$count", fontSize = 11.sp, color = c.textPrimary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    }
}

/** A long press that belongs to the finger, not to the pixel under it: the
 *  press is only ever completed while the finger stays put, and any travel
 *  hands the gesture back to whoever is scrolling.
 *
 *  ★★ Report #583: scrolling the history with a finger that happened to rest on
 *  a reaction chip threw the "who reacted" sheet up mid-scroll. `combinedClickable`
 *  gives its long press up only once someone CONSUMES the gesture, and the list
 *  consumes nothing until the drag crosses touch slop — a slow drag crosses it
 *  well after the 500ms timeout, so the timeout won and the sheet flew out.
 *  Here the same touch slop the list scrolls with also ends the press (as does a
 *  parent claiming the gesture: SwipeToReply, the list itself), which is the web
 *  client's press timer cancelled on move.
 *
 *  A finger that holds still for the timeout is still a long press. From that
 *  moment the rest of the gesture is ours, so the lift cannot ALSO land as a tap
 *  on the chip and toggle the reaction on the way into the sheet.
 *
 *  [enabled]=false leaves the chip with no detector at all: a chip with nothing
 *  to show (Radio) must not swallow the gesture its row's own long press needs.
 *
 *  The action is published to accessibility services too, which `combinedClickable`
 *  did for free — TalkBack drives the action, it cannot hold a finger still.
 */
private fun Modifier.longPressUnlessScrolled(enabled: Boolean, onLongPress: () -> Unit): Modifier =
    if (!enabled) this else this
        .semantics { onLongClick { onLongPress(); true } }
        .pointerInput(onLongPress) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                val held = try {
                    withTimeout(viewConfiguration.longPressTimeoutMillis) {
                        var settled = false
                        while (!settled) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            // Lifted (an ordinary tap, which the chip's `clickable`
                            // owns), taken by a parent, or travelling: not ours.
                            settled = change == null || !change.pressed ||
                                event.changes.any { it.isConsumed } ||
                                (change.position - down.position).getDistance() > slop
                        }
                    }
                    false
                } catch (_: PointerEventTimeoutCancellationException) {
                    true
                }
                if (!held) return@awaitEachGesture
                onLongPress()
                // The rest of the gesture is ours: the lift must not reach the
                // chip's `clickable` and toggle the reaction behind the sheet.
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                    pressed = event.changes.any { it.pressed }
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
    // When supplied, an `@mention` is resolved by ROSTER longest-match, not a
    // fixed char-class regex: given the body + the index of an `@`, it returns
    // (uin, matched-nick-length) for the longest member nick that follows, or
    // null. This makes nicks with spaces/colons (e.g. "JO f3 JO", ".:example")
    // clickable, which the old `@([\p{L}\p{N}_.-]+)` regex couldn't match.
    mentionMatch: ((String, Int) -> Pair<Int, Int>?)? = null,
    // #2: cap visible lines (collapsed long message); Int.MAX_VALUE = no cap.
    maxLines: Int = Int.MAX_VALUE,
    // #2: reports the layout so the caller can tell if the text was actually
    // truncated (hasVisualOverflow) and only then offer "Show more".
    onTextLayout: ((androidx.compose.ui.text.TextLayoutResult) -> Unit)? = null,
    /// The bubble's own long-press (the message menu), handed down so it still
    /// works ON a mention or a link.
    ///
    /// ★★ Without this, a message that IS a mention or IS a link had no
    /// reachable menu at all — no reply, no copy, no delete, no reaction.
    /// Compose's text-link handling owns every gesture inside the link's
    /// bounds, and it only knows about taps: press and hold on `@Anna` and the
    /// release still fires as a tap, so the profile opens and the parent's
    /// `combinedClickable` never sees a long click. In a group "@Anna" on its
    /// own is one of the commonest messages there is.
    onLongPress: (() -> Unit)? = null,
    // False when the group's owner turned links off (#755) and neither the
    // reader nor the sender is exempt: the URL then stays literal text, no
    // annotation, exactly as if this renderer never learned about links (web
    // parity, EmoticonText.tsx LinkContext). Defaults to true so every
    // non-group call site keeps its behavior untouched.
    linksEnabled: Boolean = true,
) {
    val tokens = remember(body) { Emoticons.tokenize(body) }
    val accent = RcqTheme.colors.accent
    val overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis
    val layoutCb: (androidx.compose.ui.text.TextLayoutResult) -> Unit = { onTextLayout?.invoke(it) }
    val hasMention =
        (mentionNick != null && body.contains('#') && Emoticons.MENTION_RE.containsMatchIn(body)) ||
        (mentionMatch != null && body.contains('@'))
    // http(s) links are made tappable in the body too (report: links in chats
    // weren't clickable). Cheap "://" gate before the regex. A links-off room
    // (linksEnabled=false) skips this whole branch: the body takes the same
    // plain-text path a link-free message always took.
    val hasUrl = linksEnabled && body.contains("://") && Emoticons.URL_RE.containsMatchIn(body)
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
    val context = LocalContext.current
    val (annotated, inline) = remember(body, mentionNick, mentionMatch, onMentionClick, accent, linksEnabled) {
        val inlineMap = HashMap<String, InlineTextContent>()
        val ann = buildAnnotatedString {
            for (t in tokens) when (t) {
                is Emoticons.Token.Text -> appendWithMentions(t.text, mentionNick, mentionMatch, onMentionClick, accent, linksEnabled)
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
                            // Height is fixed, width follows the glyph's own
                            // aspect: the set is not square (20x20 to 38x27), and
                            // a square box shrank the wide ones to fit, so a line
                            // of smileys came out visibly uneven. iOS sizes the
                            // same way.
                            Placeholder(
                                width = (1.45f * Emoticons.aspect(context, asset)).em,
                                height = 1.45.em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) { AnimatedEmoticon(asset, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        ann to inlineMap
    }
    Text(annotated, color = color, fontSize = fontSize, lineHeight = lineHeight, inlineContent = inline, modifier = modifier.longPressBeatsLinks(onLongPress), maxLines = maxLines, overflow = overflow, onTextLayout = layoutCb)
}

/** Give a long press back to the message bubble, even on top of a link.
 *
 *  Compose's text-link gesture detector claims the whole pointer stream inside
 *  a link and resolves it as a tap on release, however long the finger stayed
 *  down. So this watches the SAME stream in the Initial pass — before the link
 *  sees it — and does nothing at all until the long-press timeout has passed.
 *  A normal tap is therefore untouched and still opens the profile or the URL.
 *  Only a press that outlasts the timeout is taken: we consume it, fire the
 *  menu, and swallow the rest of the gesture so the release cannot land as a
 *  tap on the link the finger happens to be resting on.
 */
private fun Modifier.longPressBeatsLinks(onLongPress: (() -> Unit)?): Modifier =
    if (onLongPress == null) this else this.pointerInput(onLongPress) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val held = try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    // Returns as soon as the finger lifts or the gesture is
                    // cancelled — i.e. an ordinary tap, which is not ours.
                    //
                    // ★★ Report #583: "если медленно прокручивать диалог, зажав
                    // палец на сообщение, где есть смайл, то вылетает шторка".
                    // A press only had to OUTLAST the timeout, and a finger
                    // dragging the history slowly does exactly that — so the
                    // action sheet jumped out mid-scroll, from under the thumb,
                    // whenever an emoticon happened to be where the finger
                    // started. Travel past touch slop, or a change somebody
                    // else already consumed (the list taking the scroll), ends
                    // the press: it belongs to the finger, not to the pixel
                    // under it. The same rule the reaction chip beside it uses.
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

/** Append [text], turning each resolvable mention into a clickable accent nick
 *  (tap → [onMentionClick]): `#<uin>` via [mentionNick] (renders the nick), and
 *  `@mention` via [mentionMatch] (roster longest-match; renders the typed `@nick`).
 *  Unresolved or no-resolver tokens stay plain. Both kinds merged in source order. */
private fun AnnotatedString.Builder.appendWithMentions(
    text: String,
    mentionNick: ((Int) -> String?)?,
    mentionMatch: ((String, Int) -> Pair<Int, Int>?)?,
    onMentionClick: ((Int) -> Unit)?,
    accent: Color,
    // False = leave URLs as literal text (links-off room, #755); mentions
    // stay clickable either way, they are not links out of the app.
    linkify: Boolean = true,
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
    if (mentionMatch != null) {
        // Scan each '@' and ask the roster for the longest member nick that
        // follows (spaces/colons included), instead of a fixed char-class regex.
        var i = 0
        while (i < text.length) {
            val at = text.indexOf('@', i)
            if (at < 0) break
            val m = mentionMatch(text, at)
            if (m != null) {
                val (uin, len) = m            // len = matched nick length (no '@')
                hits.add(Hit(at..(at + len), false, uin, text.substring(at, at + len + 1)))
                i = at + len + 1
            } else {
                i = at + 1
            }
        }
    }
    if (linkify) {
        for (m in Emoticons.URL_RE.findAll(text)) {
            hits.add(Hit(m.range, true, 0, m.value))
        }
    }
    if (hits.isEmpty()) { append(text); return }
    hits.sortBy { it.range.first }
    var cursor = 0
    for (h in hits) {
        if (h.range.first < cursor) continue // skip overlaps
        if (h.range.first > cursor) append(text.substring(cursor, h.range.first))
        if (h.url) {
            // LinkAnnotation.Url auto-opens via LocalUriHandler = InAppBrowser
            // (Custom Tab for the web; rcq deep links keep routing in-app).
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

    // iOS parity: the panel is a floating card docked under the input bar, not
    // a full-bleed slab. Rounded 18, hairline white border, and over a chat
    // wallpaper a translucent fill so the frosted slice the bottom chrome
    // draws (L2.9, wallpaperSlice in ChatScreen) reads through it, same as the
    // composer pill's 0.55. Without a wallpaper the opaque bgSecondary it has
    // always been. The 0.66 is a veil, not a blur: per-frame RenderEffect over
    // the chrome is rejected for target phones (ChatBackground.kt), and the
    // pre-blurred slice under the chrome already supplies the frosted part.
    val cardShape = RoundedCornerShape(18.dp)
    val fill =
        if (LocalStores.chatBackground.collectAsState().value.isBlank()) c.bgSecondary
        else c.bgSecondary.copy(alpha = 0.66f)
    val card = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp)
        .padding(bottom = 6.dp)
        .clip(cardShape)
        .background(fill)
        .border(0.5.dp, Color.White.copy(alpha = 0.08f), cardShape)

    if (panel.isEmpty()) {
        // Empty by default: a centered CTA inviting the user to choose their own
        // panel set (and, in the same window, their quick reactions).
        Column(
            modifier = card
                .height(200.dp)
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

    Column(card) {
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
