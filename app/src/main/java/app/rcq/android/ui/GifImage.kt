package app.rcq.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** GIF magic bytes — "GIF8" (both 87a and 89a start with this). Lets the photo
 *  bubble (and avatars) detect an animated blob in the same "photo" media path
 *  iOS uses, and route it through the pure-Java decoder (see SafeGif.kt). */
internal fun ByteArray.isGif(): Boolean =
    size >= 4 && this[0] == 0x47.toByte() && this[1] == 0x49.toByte() &&
        this[2] == 0x46.toByte() && this[3] == 0x38.toByte()

/** True only for JPEG (FF D8 FF) or PNG (89 50 4E 47) — the two ubiquitous,
 *  well-hardened still-image formats whose native Skia decoders are safe on
 *  every device we've seen. GIF (and animated WebP) go through native decoders
 *  that SIGSEGV/SIGABRT on some OEM ROMs — GIFs are handled by the pure-Java
 *  decoder in SafeGif.kt instead; anything that's neither JPEG/PNG nor GIF
 *  falls back to a placeholder so it can never crash. */
internal fun ByteArray.isJpegOrPng(): Boolean =
    (size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()) ||
        (size >= 4 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
            this[2] == 0x4E.toByte() && this[3] == 0x47.toByte())

/**
 * Decode [raw] the way a viewer would SEE it: pixels rotated to match the JPEG
 * orientation tag, which [BitmapFactory] ignores.
 *
 * ⚠ A camera sensor is mounted landscape, so a shot taken in portrait is written
 * landscape plus an Orientation tag telling everyone to turn it. Every path that
 * recompresses a picked image used to decode the raw grid and write a fresh JPEG
 * with no tag at all, which turned "sideways, but labelled" into "sideways,
 * full stop" for every recipient on every platform (report #527). Gallery
 * pictures looked fine only because screenshots and re-encodes are already
 * upright — a camera original picked from the gallery was rotated too.
 *
 * Returns null on a corrupt blob. Never call this on a GIF (see [decodeSampled]).
 */
internal fun decodeUpright(raw: ByteArray): Bitmap? {
    val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(raw))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        // Mirrored diagonals: rare (some front cameras), cheap to cover.
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        else -> return src
    }
    // The rotated copy is written back out as a bare JPEG with no tag, so a
    // viewer cannot turn it a second time.
    val rotated = runCatching {
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }.getOrNull() ?: return src
    if (rotated !== src) src.recycle()
    return rotated
}

/**
 * Decode [bytes] to a [Bitmap] downsampled so its longest side is about [maxPx]
 * — full-resolution decodes of chat photos were a real freeze/OOM source: a
 * 12MP JPEG is a ~48MB ARGB bitmap, and [PhotoBubble] only paints it ~220dp
 * wide. Uses [BitmapFactory.Options.inSampleSize] (a bounds-only pre-pass to
 * read the dimensions, then a power-of-two subsample). Caller runs this OFF the
 * main thread. Null on a corrupt blob.
 *
 * NEVER decodes a GIF: the native Skia GIF decoder SIGSEGVs on some OEM ROMs, so
 * a GIF returns null here and the caller routes it to the pure-Java decoder.
 */
internal fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? = runCatching {
    if (bytes.isGif()) return null
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
