package app.rcq.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.rcq.android.Session
import app.rcq.android.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The picture a chat TILE shows (a tile of an album, a square of the media
 * wall): fetched, decoded at about [maxPx] on the long side, and covered when
 * [covered], all off the main thread. Null until it is ready.
 *
 * ⚠⚠ The album tile used to decode the full JPEG inside `remember`, i.e. in
 * composition on the main thread, with no subsampling: a 1200px photo into a
 * 120dp tile, two to four of them per album row, every time the row scrolled
 * into view. That was the jolt on scrolling a thread with albums. The spoiler
 * blur ran there too.
 *
 * Decoded tiles are kept by the session ([Session.cachedTile]), so a tile that
 * comes back into view has its picture on the first frame instead of a
 * spinner. A covered tile also keeps the plain one it was blurred from, so
 * uncovering it is immediate. Nothing uncovered is ever shown for a covered
 * key: the state belongs to the key, and a new key starts from the cache or
 * from nothing.
 */
@Composable
internal fun rememberTileImage(session: Session, m: ChatMessage, maxPx: Int, covered: Boolean): ImageBitmap? {
    val key = tileKey(m, maxPx, covered)
    val image = remember(key) { mutableStateOf(session.cachedTile(key)?.asImageBitmap()) }
    LaunchedEffect(key) {
        if (image.value == null) image.value = loadTile(session, m, maxPx, covered)?.asImageBitmap()
    }
    return image.value
}

/** The cache key of a tile: the picture (a clip's poster rides on the message,
 *  so a clip is keyed by its message), the size, and whether it is covered. */
internal fun tileKey(m: ChatMessage, maxPx: Int, covered: Boolean): String =
    (if (m.kind == "video") "v:" + m.id else "p:" + (m.mediaId ?: m.id)) + ":" + maxPx + (if (covered) ":c" else "")

private suspend fun loadTile(session: Session, m: ChatMessage, maxPx: Int, covered: Boolean): Bitmap? {
    val plainKey = tileKey(m, maxPx, false)
    val plain = session.cachedTile(plainKey) ?: run {
        val decoded = if (m.kind == "video") {
            // A clip's poster is the small thumbnail stored on the message.
            val b64 = m.thumbB64?.takeIf { it.isNotEmpty() } ?: return null
            withContext(Dispatchers.Default) {
                runCatching {
                    val b = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(b, 0, b.size)
                }.getOrNull()
            }
        } else {
            val mid = m.mediaId ?: return null
            val mkey = m.mediaKey ?: return null
            val bytes = session.cachedImage(mid)
                ?: runCatching { session.fetchImage(mid, mkey, m.groupId?.let { session.groupHost(it) }) }.getOrNull()
                ?: return null
            // GIF via the pure-Java first frame (the native GIF decoder
            // SIGSEGVs on some OEM ROMs); JPEG/PNG subsampled.
            withContext(Dispatchers.Default) {
                runCatching { if (bytes.isGif()) gifFirstFrame(bytes) else decodeSampled(bytes, maxPx) }.getOrNull()
            }
        }
        decoded?.also { session.keepTile(plainKey, it) }
    } ?: return null
    if (!covered) return plain
    return withContext(Dispatchers.Default) { runCatching { blurForSpoiler(plain) }.getOrNull() }
        ?.also { session.keepTile(tileKey(m, maxPx, true), it) }
}
