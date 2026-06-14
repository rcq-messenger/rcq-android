package app.rcq.android.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Default on-screen time for a photo story when the server gives no
 *  duration. Matches the usual ~5s social-story dwell. */
private const val PHOTO_STORY_MS = 5000

/** Full-screen viewer for one poster's group of active stories. Pages
 *  oldest→newest with Instagram-style progress segments, auto-advancing by
 *  duration; tap the left third to go back, the right to go forward (closing
 *  past the last one). Marks each story viewed as it shows. For your OWN
 *  stories it also surfaces the view count → a viewers list, plus delete. */
@Composable
internal fun StoryViewer(session: Session, group: RcqApi.StoryGroupOut, onClose: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stories = group.stories
    if (stories.isEmpty()) { onClose(); return }

    val isOwn = group.owner_uin != null && group.owner_uin == session.uin
    var index by remember { mutableStateOf(0) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var showViewers by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Ignore the left/right navigation taps for a beat after the viewer appears.
    // The full-screen tap zones compose right under the finger that opened the
    // viewer; without this gate a tap landing in the right two-thirds fires
    // next() — which for a single-story group is onClose() — so the viewer
    // "flashes and disappears" the instant you open it. A deliberate tap a
    // moment later still pages/closes as expected (Instagram-style).
    var inputReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(400); inputReady = true }

    val story = stories.getOrNull(index) ?: run { onClose(); return }
    val progress = remember(index) { Animatable(0f) }

    // Decode the current story's media (download + decrypt, cached by id).
    LaunchedEffect(story.id) {
        bytes = null
        bytes = session.fetchImage(story.media_id, story.media_key_b64)
    }

    // Mark viewed (skip own — implicitly seen, doesn't count server-side) and
    // run the progress bar; on natural completion, advance or close. Paused
    // while a sheet/dialog is open so the user can read the viewers list.
    LaunchedEffect(index, showViewers, confirmDelete) {
        if (showViewers || confirmDelete) return@LaunchedEffect
        if (!isOwn) session.markStoryViewed(story.id)
        progress.snapTo(progress.value)
        val durMs = ((story.duration_sec ?: 0) * 1000).coerceAtLeast(PHOTO_STORY_MS)
        val remaining = (durMs * (1f - progress.value)).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(durationMillis = remaining, easing = LinearEasing))
        if (index < stories.lastIndex) index++ else onClose()
    }

    fun prev() { if (index > 0) { index-- } }
    fun next() { if (index < stories.lastIndex) index++ else onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Media.
        bytes?.let { b ->
            // GIF stories via the pure-Java first frame (native GIF decoder
            // SIGSEGVs on some OEM ROMs); JPEG/PNG via the native decoder.
            val bmp = remember(story.id, b) { if (b.isGif()) gifFirstFrame(b) else BitmapFactory.decodeByteArray(b, 0, b.size) }
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }

        // Tap zones: left third = back, right two-thirds = forward.
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxHeight().weight(1f).noRippleClickable { if (inputReady) prev() })
            Box(Modifier.fillMaxHeight().weight(2f).noRippleClickable { if (inputReady) next() })
        }

        // Top overlay: progress segments + byline + close.
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                stories.forEachIndexed { i, _ ->
                    val frac = when {
                        i < index -> 1f
                        i > index -> 0f
                        else -> progress.value
                    }
                    Box(
                        Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.35f)),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Color.White))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val name = when {
                    isOwn -> stringResource(R.string.story_you)
                    group.is_anonymous || group.owner_uin == null -> stringResource(R.string.story_anonymous)
                    else -> group.owner_nickname ?: "${group.owner_uin}"
                }
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    storyAge(story.posted_at)?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp) }
                }
                Icon(
                    Icons.Filled.Close, stringResource(R.string.common_close), tint = Color.White,
                    modifier = Modifier.size(26.dp).clip(CircleShape).noRippleClickable { onClose() },
                )
            }
        }

        // Bottom overlay: caption + (own) viewers / delete.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            story.caption?.takeIf { it.isNotBlank() }?.let { cap ->
                Text(cap, color = Color.White, fontSize = 15.sp)
            }
            if (isOwn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).noRippleClickable { showViewers = true }.weight(1f),
                    ) {
                        Icon(Icons.Filled.RemoveRedEye, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${story.view_count}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Icon(
                        Icons.Filled.Delete, stringResource(R.string.story_delete), tint = Color.White,
                        modifier = Modifier.size(24.dp).clip(CircleShape).noRippleClickable { confirmDelete = true },
                    )
                }
            }
        }
    }

    if (showViewers) {
        var viewers by remember { mutableStateOf<List<RcqApi.StoryViewer>?>(null) }
        LaunchedEffect(story.id) { viewers = session.storyViewers(story.id) }
        AlertDialog(
            onDismissRequest = { showViewers = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.story_viewers), color = c.textPrimary) },
            text = {
                val list = viewers
                when {
                    list == null -> Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
                    list.isEmpty() -> Text(stringResource(R.string.story_no_viewers), color = c.textSecondary)
                    else -> LazyColumn { items(list, key = { it.viewer_uin }) { v ->
                        Text(v.viewer_nickname ?: "#${v.viewer_uin}", color = c.textPrimary, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
                    } }
                }
            },
            confirmButton = { TextButton(onClick = { showViewers = false }) { Text(stringResource(R.string.common_close), color = c.accent) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.story_delete_q), color = c.textPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    val id = story.id
                    confirmDelete = false
                    scope.launch { session.deleteStory(id) }
                    onClose()
                }) { Text(stringResource(R.string.story_delete), color = Color(0xFFE5484D)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }
}

/** A short "2h"/"15m"/"now" age from an ISO-8601 timestamp. */
private fun storyAge(iso: String?): String? {
    iso ?: return null
    val posted = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME) }.getOrNull()
        ?: return null
    val mins = runCatching { Duration.between(posted.toInstant(), java.time.Instant.now()).toMinutes() }.getOrNull() ?: return null
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        else -> "${mins / 60}h"
    }
}

private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick),
)
