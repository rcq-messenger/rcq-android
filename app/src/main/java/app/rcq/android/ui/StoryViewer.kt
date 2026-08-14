package app.rcq.android.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // Decoded frame of the current story (null while it downloads/decodes) and
    // whether that failed — both gate the progress clock below.
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
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
    // 0f..1f through the current story. Plain state advanced from the wall clock
    // below, deliberately NOT an Animatable — see the timer.
    var progress by remember(index) { mutableFloatStateOf(0f) }

    // Download + decrypt the current story's media, then decode it OFF the main
    // thread. Decoding a full-size JPEG inside composition (which is what this
    // used to do) blocks the frame clock — and the progress tween below is
    // driven by that same clock, so it jumped forward by however long the
    // decode blocked. The photo appeared already ~90% through its slot and
    // vanished a moment later: the "история промелькивает за долю секунды"
    // report. Same for a slow download, which the timer used to run straight
    // through while the spinner was still up.
    LaunchedEffect(story.id) {
        frame = null
        loadFailed = false
        val b = session.fetchImage(story.media_id, story.media_key_b64)
        if (b == null) { loadFailed = true; return@LaunchedEffect }
        // GIF stories via the pure-Java first frame (native GIF decoder
        // SIGSEGVs on some OEM ROMs); JPEG/PNG via the native decoder.
        val bmp = withContext(Dispatchers.Default) {
            if (b.isGif()) gifFirstFrame(b) else BitmapFactory.decodeByteArray(b, 0, b.size)
        }
        if (bmp == null) loadFailed = true else frame = bmp.asImageBitmap()
    }

    // Mark viewed (skip own — implicitly seen, doesn't count server-side) and
    // run the progress bar; on natural completion, advance or close. Paused
    // while a sheet/dialog is open so the user can read the viewers list, and
    // held entirely until the photo is on screen (or known unloadable) so the
    // story always gets its full time in front of the viewer.
    //
    // ★★ The clock is the WALL CLOCK, not an animation.
    //
    // This used to be `progress.animateTo(1f, tween(durationMillis = remaining))`,
    // and Compose scales every animation's duration by MotionDurationScale, which
    // it reads from Settings.Global.animator_duration_scale. Honor's MagicOS and
    // vivo/iQOO's OriginOS zero that setting under power saving (so does the
    // developer option "Animation off"), and at a scale of zero an animation is
    // finished on its first frame: animateTo returned in about 16 ms, the bar
    // jumped to the end and the story was gone — "пролетает за долю секунды", on
    // those two phones and not on the Redmi, with nothing wrong with the story.
    //
    // withFrameNanos takes its beat from the MonotonicFrameClock, which that
    // setting does not touch, and the fraction is computed from elapsedRealtime
    // rather than accumulated per frame, so a dropped frame or a slow decode
    // cannot make the story drift. It still does not start until the photo is on
    // screen, and it still pauses for the sheets.
    LaunchedEffect(index, showViewers, confirmDelete, frame != null, loadFailed) {
        if (showViewers || confirmDelete) return@LaunchedEffect
        if (frame == null && !loadFailed) return@LaunchedEffect
        if (!isOwn) session.markStoryViewed(story.id)
        val durMs = ((story.duration_sec ?: 0) * 1000).coerceAtLeast(PHOTO_STORY_MS)
        // Resume where a pause left off rather than restarting the story.
        val startedAt = SystemClock.elapsedRealtime()
        val alreadyDone = progress.coerceIn(0f, 1f)
        while (true) {
            withFrameNanos { }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val frac = alreadyDone + elapsed.toFloat() / durMs
            if (frac >= 1f) { progress = 1f; break }
            progress = frac
        }
        if (index < stories.lastIndex) index++ else onClose()
    }

    fun prev() { if (index > 0) { index-- } }
    fun next() { if (index < stories.lastIndex) index++ else onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Media: the decoded frame, a spinner while it loads, or a short
        // message when it can't be loaded at all (which still advances, rather
        // than parking the viewer on a spinner forever).
        val shown = frame
        when {
            shown != null -> Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.story_load_failed), color = Color.White, fontSize = 15.sp)
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
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
                        else -> progress
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
        RcqSheet(onDismiss = { showViewers = false }, title = stringResource(R.string.story_viewers)) {
            val list = viewers
            when {
                list == null -> Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
                list.isEmpty() -> Text(stringResource(R.string.story_no_viewers), color = c.textSecondary)
                // Capped so a popular story cannot push Close off the screen —
                // the list scrolls inside the sheet instead.
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) { items(list, key = { it.viewer_uin }) { v ->
                    Text(v.viewer_nickname ?: "#${v.viewer_uin}", color = c.textPrimary, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
                } }
            }
            Text(
                stringResource(R.string.common_close),
                color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { showViewers = false }.padding(vertical = 14.dp),
            )
        }
    }

    if (confirmDelete) {
        RcqAskSheet(
            onDismiss = { confirmDelete = false },
            title = stringResource(R.string.story_delete_q),
            actions = listOf(
                SheetAction(stringResource(R.string.story_delete), destructive = true) {
                    val id = story.id
                    confirmDelete = false
                    scope.launch { session.deleteStory(id) }
                    onClose()
                },
            ),
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
