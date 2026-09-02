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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.data.IslandCards
import app.rcq.android.data.IslandCatalog
import app.rcq.android.net.RcqApi

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
                    is app.rcq.android.net.IslandTrust.Entry.Malformed ->
                        addressError = ctx.getString(R.string.csrv_unreachable)
                    is app.rcq.android.net.IslandTrust.Entry.NotAFingerprint ->
                        addressError = ctx.getString(R.string.island_trust_not_fingerprint)
                    is app.rcq.android.net.IslandTrust.Entry.CaOnly ->
                        addressError = ctx.getString(R.string.island_trust_ca_only, e.host)
                    is app.rcq.android.net.IslandTrust.Entry.Disagrees ->
                        addressError = ctx.getString(R.string.island_trust_disagrees, e.changed.hostPort)
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
    run {
            val startAt = islands.indexOfFirst { it.host.equals(current.trim(), ignoreCase = true) }.coerceAtLeast(0)
            val pager = rememberPagerState(initialPage = startAt) { islands.size }
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 28.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth(),
            ) { page -> IslandCard(islands[page]) }
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

/** One island: its painting, its logo, and what it says about itself. */
@Composable
private fun IslandCard(island: IslandCatalog.Entry) {
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    val cards by IslandCards.cards.collectAsState()
    val art by produceState<ByteArray?>(initialValue = null, island.host) {
        value = IslandCatalog.art(ctx, island.host)
    }
    val image = rememberSampledBitmap(art, maxPx = 512)
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
        Text(
            name, color = c.textPrimary, fontSize = 16.sp, textAlign = TextAlign.Center,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            island.region?.let { "${island.host} · $it" } ?: island.host,
            color = c.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
        // ⚠ A RESERVED box, not an optional block. Each page used to measure
        // its own height — two lines of description here, none there — so the
        // pager, and the sheet around it, re-measured on every swipe and the
        // whole sheet twitched up and down while browsing («шторка дёргается
        // то вниз, то вверх», #736). Every card now claims the same three
        // lines whether its island has anything to say or not.
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.TopCenter) {
            island.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, color = c.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
    }
}
