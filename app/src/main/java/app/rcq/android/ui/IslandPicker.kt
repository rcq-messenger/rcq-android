package app.rcq.android.ui

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
                        value = draft, onValueChange = { draft = it }, singleLine = true,
                        textStyle = TextStyle(color = c.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth(),
                    )
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
                onPick(draft.trim())
            }
        } else {
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
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.bgPrimary).padding(bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(
                    bitmap = image, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
            // The island's own logo sits ON the painting, the way a flag sits on
            // a hill: the painting says "an island", the logo says WHICH.
            IslandAvatar(
                island.host,
                cards[island.host]?.logoVersion,
                island.name,
                size = 34.dp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(island.name, color = c.textPrimary, fontSize = 16.sp, textAlign = TextAlign.Center)
        Text(
            island.region?.let { "${island.host} · $it" } ?: island.host,
            color = c.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
        island.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it, color = c.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
    }
}
