package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.rcq.android.R
import app.rcq.android.data.LocalStores

private const val PANEL_CAP = 40
private const val REACTION_CAP = 6

/**
 * One window where the user curates BOTH sets in a single place (founder spec:
 * "so they don't have to bounce around"):
 *  - **Панель** — the emoticons shown in the composer smiley panel (≤40).
 *  - **Реакции** — the quick reactions on the long-press reaction row (≤6).
 *
 * A segmented tab switches which set the taps edit; the grid offers the WHOLE
 * bundled set ([Emoticons.fullSet] = original 40 + the 20 extra koloboks) with
 * a tinted highlight + check badge on the assets already in the active set.
 * Tapping toggles membership (respecting the cap) and writes through to
 * [LocalStores] live, so the composer panel / reaction rows update immediately.
 *
 * Every cell ANIMATES via the OOM-safe shared-frame path ([AnimatedEmoticon] /
 * [decodeGifFrames]): frames decode ONCE process-wide and cells just cycle
 * them — never the per-cell live decoders that OOM-crashed low-RAM devices.
 */
@Composable
internal fun EmojiPickerDialog(onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    val panel by LocalStores.panelEmojis.collectAsState()
    val reactions by LocalStores.reactionEmojis.collectAsState()
    var tab by remember { mutableStateOf(0) } // 0 = panel, 1 = reactions
    val activeSet = (if (tab == 0) panel else reactions).toSet()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .padding(16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(c.bgPrimary)
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.emoji_picker_title),
                color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(if (tab == 0) R.string.emoji_pick_panel_hint else R.string.emoji_pick_reactions_hint),
                color = c.textSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmojiPickerTab(
                    text = stringResource(R.string.emoji_tab_panel) + "  ${panel.size}/$PANEL_CAP",
                    selected = tab == 0, modifier = Modifier.weight(1f),
                ) { tab = 0 }
                EmojiPickerTab(
                    text = stringResource(R.string.emoji_tab_reactions) + "  ${reactions.size}/$REACTION_CAP",
                    selected = tab == 1, modifier = Modifier.weight(1f),
                ) { tab = 1 }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 46.dp),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(Emoticons.fullSet.size) { i ->
                    val asset = Emoticons.fullSet[i]
                    val selected = asset in activeSet
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) c.accent.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable {
                                // Toggle membership in the ACTIVE set, respecting
                                // the cap (at the cap, an add is a no-op). Writes
                                // through to LocalStores → the panel/reaction rows
                                // update live via their collected flows.
                                if (tab == 0) {
                                    val next = when {
                                        asset in panel -> panel - asset
                                        panel.size < PANEL_CAP -> panel + asset
                                        else -> panel
                                    }
                                    LocalStores.setPanelEmojis(next)
                                } else {
                                    val next = when {
                                        asset in reactions -> reactions - asset
                                        reactions.size < REACTION_CAP -> reactions + asset
                                        else -> reactions
                                    }
                                    LocalStores.setReactionEmojis(next)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedEmoticon(asset, Modifier.size(30.dp))
                        if (selected) {
                            Box(
                                Modifier.align(Alignment.TopEnd).size(15.dp).clip(CircleShape).background(c.accent),
                                contentAlignment = Alignment.Center,
                            ) { Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_done), color = c.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** A single segmented-control tab in [EmojiPickerDialog] (Панель / Реакции). */
@Composable
private fun EmojiPickerTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.accent.copy(alpha = 0.22f) else c.bgSecondary)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) c.accent else c.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
