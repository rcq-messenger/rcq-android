package app.rcq.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** GIF magic bytes — "GIF8" (both 87a and 89a start with this). Lets the photo
 *  bubble (and avatars) detect an animated blob in the same "photo" media path
 *  iOS uses, and render it animated instead of as a frozen first frame. */
internal fun ByteArray.isGif(): Boolean =
    size >= 4 && this[0] == 0x47.toByte() && this[1] == 0x49.toByte() &&
        this[2] == 0x46.toByte() && this[3] == 0x38.toByte()

/** True only for JPEG (FF D8 FF) or PNG (89 50 4E 47) — the two ubiquitous,
 *  well-hardened still-image formats whose native Skia decoders are safe on
 *  every device we've seen. GIF and (animated) WebP go through native decoders
 *  that SIGSEGV/SIGABRT on some OEM ROMs (realme/ColorOS crashed decoding a GIF
 *  group avatar — and a native crash can't be caught). Callers that can fall
 *  back to a placeholder (e.g. avatars) decode ONLY these to never crash. */
internal fun ByteArray.isJpegOrPng(): Boolean =
    (size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()) ||
        (size >= 4 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
            this[2] == 0x4E.toByte() && this[3] == 0x47.toByte())

/** Renders an animated GIF/WebP via ImageDecoder → AnimatedImageDrawable
 *  (API 28+). Below 28 the caller falls back to a static first frame. */
@RequiresApi(Build.VERSION_CODES.P)
@Composable
internal fun AnimatedGif(bytes: ByteArray, modifier: Modifier) {
    // The decoded drawable is paired with the DIRECT ByteBuffer that backs it
    // so the buffer stays reachable for as long as the drawable lives.
    //
    // Why a direct buffer (NOT ByteBuffer.wrap): AnimatedImageDrawable decodes
    // each animation frame LAZILY on a native "AnimatedImageTh" worker thread.
    // On Android 10/11 an array-backed source (ByteBuffer.wrap / byte[]) is read
    // back through ByteArrayStream, which needs a JNIEnv on that worker thread —
    // the thread isn't attached to the JVM, so the runtime aborts with
    // "Failed to get JNIEnv for JavaVM" (a native SIGABRT we CAN'T catch, on a
    // different thread). A *direct* buffer is native memory the decoder reads
    // without touching the JVM, so the lazy frame decode never crashes. This was
    // the RCQ-Beta-launch crash (its animated GIF avatar) and the old
    // "smileys crash" (inline animated GIFs in history) — same root cause.
    val holder = remember(bytes) {
        runCatching {
            val direct = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(direct))
            d to direct
        }.getOrNull()
    }
    val drawable = holder?.first
    // If the animated decode failed (some OEM/OS ImageDecoder builds choke on
    // particular GIFs), fall back to a static first frame. A bad emoticon must
    // never take down a whole chat.
    val staticFrame = remember(bytes, drawable) {
        if (drawable != null) null
        else runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }
    AndroidView(
        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { iv ->
            runCatching {
                when {
                    drawable != null -> {
                        iv.setImageDrawable(drawable)
                        (drawable as? AnimatedImageDrawable)?.start()
                    }
                    staticFrame != null -> iv.setImageBitmap(staticFrame)
                    else -> iv.setImageDrawable(null)
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * Decode [bytes] to a [Bitmap] downsampled so its longest side is about [maxPx]
 * — full-resolution decodes of chat photos were a real freeze/OOM source: a
 * 12MP JPEG is a ~48MB ARGB bitmap, and [PhotoBubble] only paints it ~220dp
 * wide. Uses [BitmapFactory.Options.inSampleSize] (a bounds-only pre-pass to
 * read the dimensions, then a power-of-two subsample). Caller runs this OFF the
 * main thread. Null on a corrupt blob.
 */
internal fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sample = 1
    while (longSide / (sample * 2) >= maxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}.getOrNull()

/**
 * [decodeSampled] as an async Compose value: decodes on [Dispatchers.Default]
 * (never the main thread) and returns null until ready. This is the fix for the
 * "tapping the composer freezes everything" reports — the keyboard-open
 * auto-scroll composed photo rows whose main-thread full-res decode stalled the
 * UI thread mid-IME-animation. Re-decodes only when [bytes] or [maxPx] change.
 */
@Composable
internal fun rememberSampledBitmap(bytes: ByteArray?, maxPx: Int = 1080): ImageBitmap? {
    val img by produceState<ImageBitmap?>(initialValue = null, bytes, maxPx) {
        value = if (bytes == null) null
        else withContext(Dispatchers.Default) { decodeSampled(bytes, maxPx)?.asImageBitmap() }
    }
    return img
}
