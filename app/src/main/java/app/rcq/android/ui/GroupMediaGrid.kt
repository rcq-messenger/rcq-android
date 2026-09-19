package app.rcq.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.data.GroupMedia
import app.rcq.android.media.MediaSaver
import app.rcq.android.model.ChatMessage
import kotlinx.coroutines.launch

/**
 * Everything a room has sent, as pictures, inside the room's own card.
 *
 * Report #989: "в группу кто нибудь кидает медиа, чтоб по ленте не искать,
 * сделайте чтоб... в описании группы". Scrolling a busy room to find a photo
 * somebody posted yesterday is the whole complaint, and the answer is a grid
 * of what is already on the device.
 *
 * ⚠ WHAT A TILE COSTS. A clip's tile is free: its poster frame travelled in
 * the message row itself (`thumbB64`, a few hundred bytes) and no clip is ever
 * downloaded to draw this card. A picture's tile is the picture, taken from
 * the two caches the chat already filled, and fetched once if this device has
 * never held it -- the same blob the bubble would fetch, and the reason the
 * card stops at [GroupMedia.PREVIEW] tiles until somebody asks for the rest.
 * Decoding is downsampled to tile size, so twelve tiles are twelve small
 * bitmaps and not twelve full-resolution ones.
 */
@Composable
internal fun GroupMediaGrid(session: Session, groupId: Int, modifier: Modifier = Modifier) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by session.groupMessages.collectAsState()
    val media = remember(all, groupId) { GroupMedia.of(all[groupId].orEmpty()) }
    if (media.isEmpty()) return

    var expanded by remember(groupId) { mutableStateOf(false) }
    // A room with a thousand pictures must not build a thousand bitmaps to
    // draw a card somebody opened to change the slowmode. Twelve fills three
    // rows on every phone; the rest is one tap away.
    val shown = if (expanded) media else media.take(GroupMedia.PREVIEW)
    var fetching by remember { mutableStateOf<String?>(null) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var clip by remember { mutableStateOf<VideoSource?>(null) }

    fun open(m: ChatMessage) {
        if (fetching != null) return
        fetching = m.id
        scope.launch {
            try {
                if (m.kind == "video") {
                    when (val r = playableVideo(session, m)) {
                        is PlayableVideo.Ready -> clip = r.source
                        else -> sayVideoFailure(context, r)
                    }
                } else {
                    val id = m.mediaId
                    val key = m.mediaKey
                    val bytes = if (id == null || key == null) null
                                else session.fetchImage(id, key, session.groupHost(groupId))
                    if (bytes == null) {
                        android.widget.Toast.makeText(
                            context, context.getString(R.string.media_fetch_failed),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } else photo = bytes
                }
            } finally {
                fetching = null
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.gi_media, media.size),
            color = c.textPrimary, fontSize = 14.sp,
        )
        // A plain wrapped Row rather than a LazyVerticalGrid: this card is
        // already inside a scrolling column, and nesting a lazy grid in one is
        // the crash Compose throws for "infinite height constraints".
        shown.chunked(GroupMedia.COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { m ->
                    MediaTile(
                        session, m, groupId, busy = fetching == m.id,
                        modifier = Modifier.weight(1f),
                        onOpen = { open(m) },
                    )
                }
                // The last row of an incomplete grid keeps its tiles square by
                // filling the gap rather than stretching them.
                repeat(GroupMedia.COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (media.size > GroupMedia.PREVIEW) {
            Text(
                stringResource(if (expanded) R.string.gi_media_less else R.string.gi_media_all, media.size),
                color = c.accent, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded }.padding(vertical = 4.dp),
            )
        }
    }

    photo?.let { bytes ->
        FullscreenImageViewer(
            bytes,
            onShare = { b -> MediaSaver.share(context, b, "RCQ_${System.currentTimeMillis()}.jpg", "image/jpeg") },
            onSave = { b ->
                scope.launch {
                    val ok = MediaSaver.saveToGallery(context, b, "RCQ_${System.currentTimeMillis()}.jpg", "image/jpeg")
                    val said = if (ok) context.getString(R.string.media_saved_to, "Pictures/RCQ")
                               else context.getString(R.string.media_save_failed)
                    android.widget.Toast.makeText(context, said, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { photo = null },
        )
    }
    clip?.let { src ->
        FullscreenVideoViewer(
            src,
            onShare = { s -> MediaSaver.share(context, s::writeTo, "RCQ_${System.currentTimeMillis()}.mp4", "video/mp4") },
            onSave = { s ->
                scope.launch {
                    val ok = MediaSaver.saveToGallery(context, s::writeTo, "RCQ_${System.currentTimeMillis()}.mp4", "video/mp4")
                    val said = if (ok) context.getString(R.string.media_saved_to, "Movies/RCQ")
                               else context.getString(R.string.media_save_failed)
                    android.widget.Toast.makeText(context, said, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { clip = null },
        )
    }
}

/**
 * One square of the wall.
 *
 * A clip shows the poster frame that came with its row; a picture shows itself,
 * seeded from the memory cache so a tile that has been drawn before does not
 * blink through grey on every visit, then the disk cache, then -- only if this
 * device has never held those bytes -- one fetch. GIFs go through the pure-Java
 * decoder for the same reason avatars do: the platform one has crashed natively
 * on some ROMs (SafeGif.kt).
 */
@Composable
private fun MediaTile(
    session: Session,
    m: ChatMessage,
    groupId: Int,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val c = RcqTheme.colors
    val isVideo = m.kind == "video"
    val poster = remember(m.id) { if (isVideo) GroupMedia.thumb(m) else null }
    // ⚠ No fetch for a clip: a poster frame it did not carry is not worth
    // pulling a film for.
    val bytes by produceState<ByteArray?>(session.cachedImage(m.mediaId), m.id) {
        if (isVideo || value != null) return@produceState
        val id = m.mediaId ?: return@produceState
        val key = m.mediaKey ?: return@produceState
        value = session.fetchImage(id, key, session.groupHost(groupId))
    }
    val still = rememberSampledBitmap(bytes?.takeIf { it.isJpegOrPng() }, maxPx = 320)
    val gif = rememberGifFirstFrame(bytes?.takeIf { it.isGif() })
    val image = still ?: gif

    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp))
            .background(c.bgSecondary).clickable(onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (poster != null) {
            Image(
                bitmap = poster.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else if (image != null) {
            Image(
                bitmap = image, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo) {
            Icon(
                Icons.Filled.PlayArrow, null, tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        if (busy) {
            CircularProgressIndicator(color = c.accent, modifier = Modifier.size(20.dp))
        }
    }
}
