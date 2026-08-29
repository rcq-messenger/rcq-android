package app.rcq.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.PollContent

/** The long-press preview for home chat rows (founder item L2.11, iOS
 *  ContactPreviewOverlay + ChatPreviewView parity): a read-only window on the
 *  thread with a floating identity capsule over it, and the row's context
 *  actions in a separate 260dp card below. Deliberately IN-TREE, not a
 *  Dialog: it must stay the last child of the home root Box so it covers the
 *  bottom bar but nothing outside HomeScreen (same lesson as the iOS overlay
 *  living at the ContactListView root).
 *
 *  Rendering here is PURE. Nothing in this file may ack, mark read, or touch
 *  any store: peeking at a chat must be invisible to the other side, which is
 *  the whole point of a preview. */
@Composable
internal fun PreviewOverlay(
    title: String,
    subtitle: String,
    avatar: @Composable () -> Unit,
    messages: List<ChatMessage>,
    isGroup: Boolean,
    senderName: (Int) -> String,
    actions: List<ContextAction>,
    onDismiss: () -> Unit,
) {
    // Back closes the preview, not the app: with the home content blurred
    // behind it this reads as a modal, and a modal that lets Back fall
    // through to the activity exits RCQ from a long-press. (The old centered
    // card had the same hole; the blur is what makes it feel like one.)
    androidx.activity.compose.BackHandler { onDismiss() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    // One progress value drives fade AND scale so they can never disagree.
    val t by animateFloatAsState(if (shown) 1f else 0f, label = "preview")
    // On API 31+ the home content behind this overlay is really blurred (see
    // HomeScreen), so the dim carries less of the separation work there.
    // Below 31 there is no RenderEffect and the heavier dim stands alone.
    val dim = if (android.os.Build.VERSION.SDK_INT >= 31) 0.35f else 0.45f
    // The last ~30 are plenty for a peek and keep the list trivial. Reversed
    // because the LazyColumn below lays out bottom-up (newest anchored).
    val recent = remember(messages) { messages.takeLast(30).asReversed() }
    val previewMaxH = (LocalConfiguration.current.screenHeightDp * 0.45f).dp

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)).clickable(onClick = onDismiss)) {
        // The stack scrolls as a WHOLE: on a short screen preview + actions
        // overflow, and the iOS lesson is to scroll the column rather than
        // crop the actions off the bottom (ContactPreviewOverlay).
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 0.9f + 0.1f * t
                    scaleX = s; scaleY = s; alpha = t
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PreviewMessagesCard(title, subtitle, avatar, recent, isGroup, senderName, previewMaxH)
            Spacer(Modifier.height(14.dp))
            PreviewActionsCard(actions, onDismiss)
        }
    }
}

/** The read-only thread window: simplified bubbles, newest at the bottom,
 *  identity capsule floating over the top edge (iOS ChatPreviewView). */
@Composable
private fun PreviewMessagesCard(
    title: String,
    subtitle: String,
    avatar: @Composable () -> Unit,
    recent: List<ChatMessage>,
    isGroup: Boolean,
    senderName: (Int) -> String,
    maxHeight: Dp,
) {
    val c = RcqTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            // min keeps the capsule on a real surface when the thread is empty.
            .heightIn(min = 96.dp, max = maxHeight)
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(c.bgSecondary)
            // Swallow taps so a tap ON the card does not fall through to the
            // scrim and dismiss (same trick the old centered card used).
            .clickable(enabled = false) {},
    ) {
        // reverseLayout anchors the NEWEST message on screen with zero scroll
        // bookkeeping; `recent` is newest-first for exactly that reason. The
        // top contentPadding keeps the oldest visible line out from under the
        // floating capsule.
        LazyColumn(
            Modifier.fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 52.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(recent, key = { it.id }) { m -> PreviewBubble(m, isGroup, senderName) }
        }
        IdentityCapsule(title, subtitle, avatar, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
    }
}

/** Avatar + name + #uin (or member count) in a translucent pill floated over
 *  the message window, so the preview never spends a header row on identity. */
@Composable
private fun IdentityCapsule(
    title: String,
    subtitle: String,
    avatar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RcqTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.bgPrimary.copy(alpha = 0.85f))
            .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        avatar()
        // fill = false: a short name keeps the pill wrapped tight; a long one
        // ellipsizes instead of shoving the number out of the capsule.
        Text(
            title, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(subtitle, color = c.textMono, fontSize = 11.sp, maxLines = 1)
    }
}

/** One simplified message row: a bubble-shaped fill, no timestamps, no
 *  delivery state, no reactions, no read receipts. NOT the real bubbles on
 *  purpose: this is a peek, not a chat. bubbleOther equals the card surface
 *  in the dark theme, so the fills are bgPrimary vs an accent wash instead;
 *  both read on bgSecondary in both themes. */
@Composable
private fun PreviewBubble(m: ChatMessage, isGroup: Boolean, senderName: (Int) -> String) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (m.fromMe) c.accent.copy(alpha = 0.18f) else c.bgPrimary)
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            if (isGroup && !m.fromMe && m.senderUin != null) {
                Text(
                    senderName(m.senderUin), color = c.accent, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val body = m.body.takeIf { it.isNotBlank() }
            when (m.kind) {
                "text" -> EmoticonText(m.body, color = c.textPrimary, fontSize = 14.sp, maxLines = 3)
                "photo" -> KindChip(Icons.Filled.Photo, stringResource(R.string.kind_photo))
                "video" -> KindChip(Icons.Filled.Videocam, stringResource(R.string.kind_video))
                "voice" -> KindChip(Icons.Filled.Mic, stringResource(R.string.kind_voice))
                // Same caption order as Session.notificationPreview: the body
                // (original file name) when present, the generic word after.
                "file" -> KindChip(Icons.Filled.AttachFile, body ?: m.fileName ?: stringResource(R.string.kind_file))
                "location" -> KindChip(Icons.Filled.Place, stringResource(R.string.kind_location))
                // Same guard as Session.notificationPreview: a poll body is
                // the SERIALIZED BALLOT, never printable as-is.
                "poll" -> KindChip(
                    Icons.Filled.Poll,
                    PollContent.fromJson(m.body)?.question?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.kind_message),
                )
                "relay" -> KindChip(Icons.Filled.Shield, stringResource(R.string.push_kind_relay_share))
                // A call row's body is the human summary the call logger wrote.
                "call" -> KindChip(Icons.Filled.Call, body ?: stringResource(R.string.kind_message))
                else ->
                    if (body != null) EmoticonText(body, color = c.textPrimary, fontSize = 14.sp, maxLines = 3)
                    else KindChip(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.kind_message))
            }
        }
    }
}

/** Compact icon + caption stand-in for a non-text message. */
@Composable
private fun KindChip(icon: ImageVector, caption: String) {
    val c = RcqTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
        Text(caption, color = c.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The row's context actions in their own card below the preview, iOS
 *  actionList parity: 260dp wide, 44dp rows, thin dividers. The items arrive
 *  fully built (order, gating, callbacks) from contactActions/groupActions
 *  and are rendered verbatim. */
@Composable
private fun PreviewActionsCard(actions: List<ContextAction>, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    Column(
        Modifier
            .width(260.dp)
            .shadow(6.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(c.bgSecondary)
            .clickable(enabled = false) {},
    ) {
        actions.forEachIndexed { i, a ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))
            val tint = if (a.destructive) Color(0xFFE5484D) else c.textPrimary
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable { a.onClick(); onDismiss() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A fixed slot, not a bare icon: labels align even though the
                // glyphs vary in optical width.
                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                    Icon(a.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                }
                Text(a.title, color = tint, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
