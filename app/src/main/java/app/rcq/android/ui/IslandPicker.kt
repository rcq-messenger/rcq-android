package app.rcq.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.BuildConfig
import app.rcq.android.R
import app.rcq.android.data.IslandCards
import app.rcq.android.data.IslandCatalog
import app.rcq.android.net.RcqApi
import app.rcq.android.net.SingBoxTransport

/**
 * Picking an island, as a thing you swipe through rather than a host you type.
 *
 * One view, two places: the onboarding flow (before there is an account) and
 * the in-app "add a server" sheet. It draws the published catalogue as cards —
 * the island's painting from the site, its own logo on top of it when we have
 * ever spoken to it, its name, host, region and the sentence its operator
 * wrote — and hands back a bare host.
 *
 * Typing an address by hand is still here, one tap away, because a self-hoster
 * and anybody handed a private island by an organisation is never in this
 * catalogue and must not be made to feel like an edge case.
 *
 * ⚠ The catalogue is DISPLAY ONLY (see [IslandCatalog]). Nothing picked here
 * bypasses anything: the person chooses a host and the app then talks to it the
 * same way it talks to any host they could have typed.
 */
@Composable
internal fun IslandPickerSheet(
    /** Host currently in force, so its card opens first. */
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    val islands by produceState(initialValue = IslandCatalog.cached().orEmpty()) {
        value = IslandCatalog.load(ctx)
    }
    var manual by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(current.ifBlank { RcqApi.DEFAULT_HOST }) }
    // An address error (design §3): a `#fp` fragment that is not a
    // fingerprint, or one on a host that is never pinned, or one the store
    // disagrees with. Said under the field; Use does nothing until it is fixed.
    var addressError by remember { mutableStateOf<String?>(null) }

    RcqSheet(onDismiss = onDismiss, title = stringResource(R.string.island_pick_title)) {
        if (manual || islands.isEmpty()) {
            // The typed path, and also what an unreachable catalogue falls back
            // to: a blocked network must never leave this sheet empty.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(c.bgPrimary).padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (draft.isEmpty()) Text(stringResource(R.string.island_host_hint), color = c.textSecondary, fontSize = 14.sp)
                    BasicTextField(
                        value = draft, onValueChange = { draft = it; addressError = null }, singleLine = true,
                        textStyle = TextStyle(color = c.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth(),
                    )
                }
                addressError?.let {
                    Text(it, color = androidx.compose.ui.graphics.Color(0xFFE5484D), fontSize = 12.sp)
                }
                Text(stringResource(R.string.island_manual_help), color = c.textSecondary, fontSize = 11.sp)
                Text(
                    stringResource(R.string.island_reset_default), color = c.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { draft = RcqApi.DEFAULT_HOST },
                )
                if (islands.isNotEmpty()) Text(
                    stringResource(R.string.island_back_to_list), color = c.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { manual = false },
                )
            }
            SheetGap(16)
            CapsuleButton(stringResource(R.string.island_use), modifier = Modifier.fillMaxWidth()) {
                // The fragment is taken on file here, before the first
                // connection, and what is handed on is the bare host:port.
                when (val e = app.rcq.android.net.IslandTrust.adopt(draft)) {
                    is app.rcq.android.net.IslandTrust.Entry.Ok -> onPick(e.hostPort)
                    is app.rcq.android.net.IslandTrust.Entry.Empty -> onPick("")
                    else -> addressError = islandAddressError(ctx, e)
                }
            }
        } else {
            IslandCarousel(current = current, islands = islands, onPick = onPick)
            Text(
                stringResource(R.string.island_manual_entry), color = c.accent, fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp)).clickable { manual = true }.padding(vertical = 6.dp),
            )
        }
        Text(
            stringResource(R.string.common_cancel), color = c.textSecondary, fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss).padding(vertical = 14.dp),
        )
    }
}

/// The deck itself, without a sheet around it: cards, dots and the Use button.
///
/// Split out because the add-account flow shows the same deck INSIDE its own
/// sheet, and a sheet within a sheet is not a thing. Both callers therefore
/// draw one list of islands rather than two that drift apart.
@Composable
internal fun IslandCarousel(
    current: String,
    islands: List<IslandCatalog.Entry>,
    onPick: (String) -> Unit,
) {
    val c = RcqTheme.colors
    if (islands.isEmpty()) return
    // ⚠ REMEMBERED ABOVE THE BRANCH BELOW, not inside the deck. Reading an
    // island's rules takes the pager out of composition, and a pager state
    // created inside it would be a NEW one on the way back: you would open the
    // rules of the third island and return to the first.
    val startAt = islands.indexOfFirst { it.host.equals(current.trim(), ignoreCase = true) }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startAt) { islands.size }
    // The island whose rules are being read, and the name to put over them.
    //
    // ⚠ A PAGE OF THIS SAME SHEET, not a sheet of its own. Both callers draw
    // this deck INSIDE a sheet already (see [IslandPickerSheet] and the
    // add-account flow in HomeScreen), and a sheet within a sheet is not a
    // thing here — so reading the rules replaces the deck the way «enter an
    // address» does, with one way back. The height changes, which is fine: it
    // changes on a deliberate tap, not on every swipe, and #736 is about the
    // latter.
    var reading by remember { mutableStateOf<IslandRulesPage?>(null) }
    val page = reading
    if (page != null) {
        Text(page.name, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        SheetGap(8)
        Text(
            page.rules, color = c.textPrimary, fontSize = 14.sp,
            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        )
        SheetGap(12)
        Text(
            stringResource(R.string.island_back_to_list), color = c.accent, fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable { reading = null }.padding(vertical = 6.dp),
        )
        return
    }
    run {
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 28.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth(),
            ) { index ->
                IslandCard(islands[index]) { name, rules ->
                    reading = IslandRulesPage(name, rules)
                }
            }
            Spacer(Modifier.height(12.dp))
            // Where you are in the deck. Dots rather than "3 / 5": the count is
            // not the point, the fact that there is more to the left and right is.
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                islands.indices.forEach { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (i == pager.currentPage) c.accent else c.textSecondary.copy(alpha = 0.35f)),
                    )
                }
            }
            SheetGap(16)
            CapsuleButton(stringResource(R.string.island_use), modifier = Modifier.fillMaxWidth()) {
                onPick(islands[pager.currentPage].host)
            }
        }
}

/// One island's house rules, being read in place of the deck.
private class IslandRulesPage(val name: String, val rules: String)

/** One island: its painting, its logo, and what it says about itself.
 *
 *  [onRules] is handed the island's name and the rules text, and only ever
 *  from a card that HAS rules — the caller draws them, because this card is a
 *  page of a pager inside a sheet and has nowhere of its own to put them. */
@Composable
private fun IslandCard(island: IslandCatalog.Entry, onRules: (String, String) -> Unit) {
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    val cards by IslandCards.cards.collectAsState()
    // #929: an island that did not answer, while the user's opt-out forbade
    // raising the relays for it, says so ON ITS OWN CARD. The Toast is kept for
    // the connection here and now (your own island, a call being placed) and
    // nothing else — a popup about somebody else's server covers the screen you
    // are reading to tell you a fact about the card already in front of you.
    val declined by SingBoxTransport.declinedIslands.collectAsState()
    val art by produceState<ByteArray?>(initialValue = null, island.host) {
        value = IslandCatalog.art(ctx, island.host)
    }
    val image = rememberSampledBitmap(art, maxPx = 512)
    // ⚠ Read HERE, not inside the box below, because two things need the same
    // answer now: the line in the reserved box, and the rules button beside
    // the name. Still one question per card — [islandDoor] remembers it for
    // the life of the sheet.
    //
    // The age is read at composition and is not itself state, so an entry that
    // goes stale while the sheet sits open keeps its line until the flow next
    // changes or the card is reopened. Both are seconds away in practice, and
    // the alternative is a ticker recomposing every card in the deck.
    val declinedAt = declined[SingBoxTransport.declineKey(island.host)]
    val unreachable = declinedAt != null &&
        System.currentTimeMillis() - declinedAt < SingBoxTransport.DECLINED_LINE_TTL_MS
    // ⚠ NOT asked of an island this device has just declined to reach: that
    // decline is the person's own opt-out from raising the relays for it, and
    // a request we know will fail is not made to fill in a label.
    val door = if (unreachable) null else islandDoor(island.host)
    // No card under it. The island is a cut-out and the sheet already has a
    // ground of its own; a second panel behind the painting turned a floating
    // island into a sticker on a tile (founder, 24.08). Everything here stands
    // on the sheet, and the island drifts.
    val float = rememberInfiniteTransition(label = "island-float")
    val dy by float.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "island-dy",
    )
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(140.dp).offset(y = dy.dp), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(
                    bitmap = image, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
            // The island's own logo sits ON the painting, the way a flag sits on
            // a hill: the painting says "an island", the logo says WHICH.
            // The mirrored logo when the catalogue carries one, the island's own
            // when this device has spoken to it, and the lettered tile when
            // neither: an island whose operator never set a logo is a normal
            // state, not a gap.
            val mirrored by produceState<ByteArray?>(initialValue = null, island.host) {
                value = IslandCatalog.logo(ctx, island)
            }
            val mirroredImage = rememberSampledBitmap(mirrored, maxPx = 128)
            if (mirroredImage != null) {
                Image(
                    bitmap = mirroredImage, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.BottomCenter).size(34.dp)
                        .clip(RoundedCornerShape(34.dp * 0.28f)),
                )
            } else {
                IslandAvatar(
                    island.host,
                    cards[island.host]?.logoVersion,
                    cards[island.host]?.name?.takeIf { it.isNotBlank() } ?: island.name,
                    size = 34.dp,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // What the island CALLS ITSELF wins over what the catalogue says about
        // it. The catalogue is a file edited by hand in a repository; the card
        // is the answer this device got from /server/info, which is the name
        // the operator typed into their own admin console. They drift, and when
        // they do the operator's is the true one (founder, 24.08: "why does it
        // say RCQ (default) when we have a server name").
        //
        // Only for islands this device has spoken to. Asking every island in
        // the catalogue for its name the moment this sheet opens would hand our
        // address to five hosts the person has not chosen yet.
        val name = cards[island.host]?.name?.takeIf { it.isNotBlank() } ?: island.name
        // The island's house rules, a tap away from the deck (founder, 07.09:
        // "a good idea, so you can look before joining"). The same words its
        // own Settings screen shows to the people already living there.
        //
        // ⚠ TWO RESERVED SLOTS, ONE ON EACH SIDE, and both of them are #736.
        // The rules arrive from the island a moment AFTER the swipe lands on
        // the card, so a button that simply appeared would change this row's
        // height under the pager and the whole sheet would twitch — the exact
        // fault the 44dp box below was built to end. The right slot is drawn
        // whether the island answered or not; the left one is its mirror, so
        // the name stays centred instead of sitting 24dp off to the left.
        // ⚠⚠ EVERY RESERVED SIZE ON THIS CARD IS DERIVED FROM THE TEXT, not
        // written in dp. The card's slots were 24dp and 44dp while the lines
        // inside them were 11sp and 16sp, and sp grows with the reader's text
        // size while dp does not: at the largest setting the door line was
        // sliced through the middle, the description lost its second line, and
        // the rules icon sat cramped against a title half again its size
        // (founder, 08.09, with a screenshot). Same trap as #856/#894, where a
        // screen's height was written in dp around text that was not.
        //
        // Deriving from `sp.toDp()` keeps the whole point of the fixed slots:
        // every card computes the SAME number, so the pager still does not
        // re-measure on a swipe (#736), and the number now follows the text.
        val lineSlot = with(androidx.compose.ui.platform.LocalDensity.current) { 14.sp.toDp() }
        val iconSlot = with(androidx.compose.ui.platform.LocalDensity.current) { 16.sp.toDp() * 1.5f }
        val glyphSize = with(androidx.compose.ui.platform.LocalDensity.current) { 16.sp.toDp() * 0.94f }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(iconSlot))
            Text(
                name, color = c.textPrimary, fontSize = 16.sp, textAlign = TextAlign.Center,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // ⚠ An icon ONLY when there is something behind it. An operator
            // who never wrote a welcome is the ordinary case, and a button
            // that opens an empty page is worse than no button at all.
            val rules = door?.rules
            Box(
                Modifier.size(iconSlot).then(
                    if (rules != null) {
                        Modifier.clip(CircleShape).clickable { onRules(name, rules) }
                    } else Modifier,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (rules != null) Icon(
                    Icons.Filled.Gavel,
                    contentDescription = stringResource(R.string.island_rules_title),
                    tint = c.accent,
                    modifier = Modifier.size(glyphSize),
                )
            }
        }
        // ⚠ The headcount rides on THIS line rather than taking one of its own.
        // Every line on this card is measured (see the reserved box below), and
        // an extra one would change the card's height, which is #736 all over
        // again. The host line always exists and always has room.
        //
        // Absent until the island answers, and absent for an island older than
        // the field: a card that says nothing is honest, a card that says 0 is
        // not (founder, 09.09: a number makes a closed club read as a place).
        //
        // ⚠ A GLYPH IN FRONT OF THE NUMBER. A bare "· 2,649" beside a price
        // reads as a second price (founder, 09.09: "what is 2649?"); the little
        // two-person mark says which number is money and which is people, in
        // every language and without spending a word. Inline in the same line
        // rather than a Row, so the host can still take the whole width when
        // there is no count to draw.
        val people = door?.people ?: 0
        val crowdGlyph = "crowd"
        Text(
            buildAnnotatedString {
                append(island.region?.let { "${island.host} · $it" } ?: island.host)
                if (people > 0) {
                    append(" · ")
                    appendInlineContent(crowdGlyph, "@")
                    append(" " + java.text.NumberFormat.getIntegerInstance().format(people))
                }
            },
            inlineContent = mapOf(
                crowdGlyph to InlineTextContent(
                    Placeholder(
                        width = 13.sp,
                        height = 12.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Icon(
                        Icons.Filled.People,
                        contentDescription = null,
                        tint = c.textSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                },
            ),
            color = c.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
        // ⚠ A RESERVED box, not an optional block. Each page used to measure
        // its own height — two lines of description here, none there — so the
        // pager, and the sheet around it, re-measured on every swipe and the
        // whole sheet twitched up and down while browsing («шторка дёргается
        // то вниз, то вверх», #736). Every card now claims the same three
        // lines whether its island has anything to say or not.
        Spacer(Modifier.height(6.dp))
        // One door line plus two description lines, in whatever those three
        // lines actually measure at this text size.
        Box(Modifier.fillMaxWidth().height(lineSlot * 3 + 2.dp), contentAlignment = Alignment.TopCenter) {
            // ⚠⚠ The unreachable line lives INSIDE this box and TAKES THE PLACE
            // of the description. Not a row of its own, not a line above or
            // below: an extra line is a taller page, a taller page re-measures
            // the pager, and that is #736 back — the whole sheet twitching up
            // and down on every swipe. Whatever this box draws, it is 44dp.
            if (unreachable) {
                Text(
                    stringResource(R.string.island_unreachable_relays_off),
                    color = androidx.compose.ui.graphics.Color(0xFFE5484D),
                    fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // ⚠⚠ THE DOOR LINE IS INSIDE THE BOX, AND ITS SLOT IS FIXED.
                // Until 07.09 it was drawn ABOVE this box as an ordinary Text
                // that appeared only for a closed island, and it arrived
                // ASYNCHRONOUSLY: the card grew a line a moment after the swipe
                // landed on it. That is #736 exactly — the sheet twitching up
                // and down while browsing — reintroduced by the very block that
                // was careful not to touch the box below it. The slot is drawn
                // whether there is an answer or not, so an island that has not
                // replied, an open one and a closed one are all the same height.
                //
                // 14dp and lineHeight 14sp on both texts: one door line plus two
                // description lines is 42dp and fits the 44 the box promises.
                Box(Modifier.height(lineSlot), contentAlignment = Alignment.Center) {
                    if (door != null) {
                        val buyUrl = door.buyUrl
                        val label = if (buyUrl != null) {
                            door.label + " · " + stringResource(R.string.island_entry_buy)
                        } else {
                            door.label
                        }
                        Text(
                            label,
                            // Closed is a fact to notice, open is a fact to
                            // glance past: the accent is spent on the one that
                            // changes what happens next.
                            color = if (door.closed) c.accent else c.textSecondary,
                            fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 14.dp)
                                // ⚠ The click stops here. This line sits on the
                                // card that PICKS the island, and one tap that
                                // both opened a shop and chose an island would
                                // be two answers to a question asked once.
                                .then(
                                    if (buyUrl != null) {
                                        Modifier.clickable {
                                            runCatching {
                                                ctx.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(buyUrl),
                                                    ),
                                                )
                                            }
                                        }
                                    } else Modifier,
                                ),
                        )
                    }
                }
                island.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it, color = c.textSecondary, fontSize = 11.sp, lineHeight = 14.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }
}

/// Whether the island's door is open, what it costs when it is not, and what
/// its operator wrote on the wall beside it.
internal class IslandDoor(
    /// The whole line, already worded and already priced.
    val label: String,
    val closed: Boolean,
    /// The operator's own shop, when they named one and it is https.
    val buyUrl: String?,
    /// The house rules, or null when the operator wrote none.
    ///
    /// ⚠ Carried HERE rather than fetched on its own. It is a second field of
    /// the same `/server/info` reply the door is read from, and a rules button
    /// that asked for itself would double the requests this deck already makes
    /// (one per opened card) for a string we have already been handed.
    val rules: String?,
    /// How many people live there, or 0 when the island did not say. Carried
    /// on the same reply as everything else here, for the same reason the
    /// rules are (see above).
    val people: Int = 0,
)

/// What the island says about its OWN door, or null while it has not answered.
///
/// ⚠ THE DECK USED TO SAY NOTHING FOR AN OPEN ISLAND, which is the founder's
/// item 3 of 06.09: "the carousel does not say whether an island is closed or
/// open, it should". Silence is not the same statement as "open" — it is what a
/// card that has not answered yet also looks like — so both answers are now
/// spoken out loud and only "no answer at all" is silent.
///
/// ⚠ Two locks, either of which shuts the door, so both are read.
/// `closed_island` is the island withholding the key that seals an envelope to
/// its residents; `registration_policy == "invite"` is the island refusing to
/// register a new account without a code. is2.rcq.app sets both, and an island
/// may well set only the second: reading one of them would call that island
/// open and send somebody into the refusal this whole change exists to avoid.
///
/// ⚠ Asked of the ISLAND, never of the catalogue. servers.json is a file the
/// team edits by hand and would be stale the day after an operator changed a
/// price or shut their doors, and a wrong answer here is worse than none.
///
/// ⚠ Only for a card the person has already opened — see [IslandCarousel]:
/// asking every island in the deck the moment the sheet opens would hand our
/// address to five hosts nobody has chosen. The answer is remembered for the
/// life of the sheet, so swiping back and forth does not re-ask.
@Composable
private fun islandDoor(host: String): IslandDoor? {
    val ctx = LocalContext.current
    var door by remember(host) { mutableStateOf<IslandDoor?>(null) }
    LaunchedEffect(host) {
        val info = runCatching { RcqApi.serverInfoOf(host) }.getOrNull() ?: return@LaunchedEffect
        val caps = info.capabilities
        val rules = info.welcome.trim().takeIf { it.isNotBlank() }
        // ⚠ "paid" belongs here. There are three shut policies on the server
        // (`registration_policy`: open, invite, paid) and this line listed two,
        // so an island that charges for entry without also sealing its
        // directory was drawn as OPEN and then refused the registration it had
        // just invited.
        val closed = caps.closed_island ||
            !caps.registration_policy.equals("open", ignoreCase = true)
        if (!closed) {
            door = IslandDoor(
                ctx.getString(R.string.island_entry_open), closed = false, buyUrl = null, rules = rules,
                people = caps.user_count,
            )
            return@LaunchedEffect
        }
        // ⚠⚠ IN THE PLAY BUILD, A PRICE ONLY FOR OUR OWN ISLAND, and this is a
        // rule about Google rather than about taste. Entry to the flagship is
        // something we will sell (through Play Billing when it lands), so
        // naming its price is naming the price of something this app sells.
        // Entry to somebody else's island is bought on their site, and a store
        // build that prices a purchase it does not handle — and links out to
        // it — is the shape both stores object to. iOS has drawn exactly this
        // line since 07.09 (AddAccountSheet.label); this is its twin.
        //
        // The sideload build is unaffected: it shows every island's price and
        // its buy link, because nobody's store rules apply to it.
        //
        // "Closed club" still shows for every closed island in both builds: it
        // is not a price, it is the fact that tells a person they need a code,
        // and without it the island looks broken rather than private.
        val isOurs = host.equals(app.rcq.android.net.RcqApi.DEFAULT_HOST, ignoreCase = true)
        val mayPrice = !BuildConfig.PLAY_STORE || isOurs
        val cents = if (mayPrice) caps.entry_price_cents else 0
        val text = if (cents > 0) {
            val price = if (cents % 100 == 0) "$" + (cents / 100) else "$" + "%.2f".format(cents / 100.0)
            ctx.getString(R.string.island_entry_closed) + " · " + ctx.getString(R.string.island_entry_price, price)
        } else {
            ctx.getString(R.string.island_entry_closed)
        }
        // An island that set a price and no address gets the line without a
        // link, rather than a link somewhere we invented for it.
        // ⚠ And no link out of the store build at all, ours included: the price
        // may be named there, the checkout may not be pointed at.
        val url = caps.entry_url.trim()
            .takeIf { !BuildConfig.PLAY_STORE && it.startsWith("https://", ignoreCase = true) }
        door = IslandDoor(text, closed = true, buyUrl = url, rules = rules, people = caps.user_count)
    }
    return door
}

/// Will this island REFUSE a registration that carries no code? Asked by the
/// flows that register, so they can collect the code before spending a try
/// rather than after being refused.
///
/// ⚠ Not the same question as `Session.islandIsClosed`, which asks whether the
/// island's directory is sealed so that a failed card fetch can be called a
/// closed island rather than a wrong number. That one answers false when it
/// does not know; this one answers null, because "we could not ask" must not
/// be turned into "paste a code".
///
/// ⚠ Null means "the island did not answer", which is NOT "open". A caller that
/// treats null as open sends the person into a registration that fails; a
/// caller that treats it as closed asks for a code nobody has. Both callers
/// here go ahead and let the refusal path handle it, which is the only honest
/// answer when the island is unreachable.
internal suspend fun islandRefusesRegistration(host: String): Boolean? {
    val caps = runCatching { RcqApi.serverInfoOf(host)?.capabilities }.getOrNull() ?: return null
    // ⚠ THE POLICY ALONE, mirroring `auth.register`: it refuses on
    // `registration_policy in ("invite", "paid")` and nothing else. Reading
    // `closed_island` here asked for a code on an island that would have taken
    // the registration without one, and missing "paid" let somebody spend a try
    // on an island that was always going to refuse it.
    return !caps.registration_policy.equals("open", ignoreCase = true)
}
