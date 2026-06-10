package app.rcq.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

// ---------------------------------------------------------------------------
// PURE-JAVA GIF rendering. The platform's GIF decoders — BitmapFactory and
// ImageDecoder/AnimatedImageDrawable — both go through native Skia, which
// SIGSEGV/SIGABRTs on some OEM ROMs (realme/ColorOS Android 14 crashed decoding
// the beta group's GIF avatar AND every Kolobok emoticon; a native crash can't
// be caught). That was the v0.30–0.33 launch crash. Glide's `gifdecoder` is a
// PURE-JAVA decoder: it parses the GIF and renders each frame into a Bitmap in
// managed code, so it never touches the crashing native path. We route ALL GIF
// decoding (avatars, emoticons, photo bubbles) through here; JPEG/PNG keep the
// fast, well-hardened native BitmapFactory path.
// ---------------------------------------------------------------------------

/** A trivial [GifDecoder.BitmapProvider]: allocate fresh, never pool. Frame
 *  bitmaps are small (emoticons ~32px, avatars ~320px) and short-lived, so a
 *  pool isn't worth the lifetime juggling. */
private val gifBitmapProvider = object : GifDecoder.BitmapProvider {
    override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
        Bitmap.createBitmap(width, height, config)
    override fun release(bitmap: Bitmap) {}
    override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)
    override fun release(bytes: ByteArray) {}
    override fun obtainIntArray(size: Int): IntArray = IntArray(size)
    override fun release(array: IntArray) {}
}

/** Build a fresh stateful decoder for [bytes] (frame cursor is per-instance, so
 *  every consumer gets its own). Null on a malformed GIF. */
private fun newGifDecoder(bytes: ByteArray): StandardGifDecoder? = runCatching {
    val header = GifHeaderParser().setData(bytes).parseHeader()
    StandardGifDecoder(gifBitmapProvider, header, ByteBuffer.wrap(bytes), 1)
}.getOrNull()

/** Decode ONLY the first frame of a GIF to a [Bitmap], in pure-managed code.
 *  For static renders (emoticons, GIF avatars, the composer field). Null on a
 *  malformed blob. */
internal fun gifFirstFrame(bytes: ByteArray): Bitmap? = runCatching {
    val dec = newGifDecoder(bytes) ?: return null
    dec.advance()
    dec.nextFrame
}.getOrNull()

/** First frame of a GIF as an async Compose value: decodes off the main thread
 *  (Dispatchers.Default) and returns null until ready / for a non-GIF. For
 *  static GIF renders that shouldn't jank composition (e.g. group avatars in a
 *  list). */
@Composable
internal fun rememberGifFirstFrame(bytes: ByteArray?): ImageBitmap? {
    val img by produceState<ImageBitmap?>(initialValue = null, bytes) {
        value = if (bytes == null || !bytes.isGif()) null
        else withContext(Dispatchers.Default) { gifFirstFrame(bytes)?.asImageBitmap() }
    }
    return img
}

/** Render an animated GIF via the pure-Java decoder — works on every API level
 *  (minSdk 26) and every OEM ROM. Decodes frames off the main thread and posts
 *  the current frame to Compose; a single-frame GIF just shows that frame. Used
 *  for photo-bubble GIFs. Renders nothing until the first frame is ready or if
 *  the blob isn't a decodable GIF. */
@Composable
internal fun SafeAnimatedGif(bytes: ByteArray, modifier: Modifier) {
    var frame by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) {
        val dec = withContext(Dispatchers.Default) { newGifDecoder(bytes) } ?: return@LaunchedEffect
        val count = dec.frameCount
        if (count <= 0) return@LaunchedEffect
        if (count == 1) {
            val bmp = withContext(Dispatchers.Default) { runCatching { dec.advance(); dec.nextFrame }.getOrNull() }
            frame = bmp?.asImageBitmap()
            return@LaunchedEffect
        }
        // Animation loop: advance + render each frame off-thread, honoring the
        // per-frame delay. Cancelled automatically when the composable leaves.
        while (isActive) {
            val bmp = withContext(Dispatchers.Default) { runCatching { dec.advance(); dec.nextFrame }.getOrNull() }
            if (bmp != null) frame = bmp.asImageBitmap()
            // Clamp the GIF's stated delay: 0 (some encoders) would spin hot.
            delay(dec.nextDelay.coerceIn(20, 1000).toLong())
        }
    }
    frame?.let {
        Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}
