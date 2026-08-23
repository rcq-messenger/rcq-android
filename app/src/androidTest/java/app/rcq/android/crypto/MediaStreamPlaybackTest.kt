package app.rcq.android.crypto

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom

/**
 * The half of the streamed-video path that cannot be reasoned about: whether
 * the PLATFORM's own extractor is happy reading an RCQM1 container through a
 * [android.media.MediaDataSource] that decrypts on demand.
 *
 * A real mp4 is produced here with MediaCodec + MediaMuxer rather than shipped
 * as an asset, so the test exercises whatever the device actually encodes,
 * including the seek back to patch the moov atom, which is exactly the access
 * pattern that makes a naive chunked reader fall over.
 */
@RunWith(AndroidJUnit4::class)
class MediaStreamPlaybackTest {

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** A few seconds of solid colour, H.264 in an mp4. */
    private fun makeMp4(frames: Int = 90): File {
        val w = 320
        val h = 240
        val fps = 30
        val out = File.createTempFile("rcq-src", ".mp4").apply { deleteOnExit() }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        var fed = 0
        var done = false
        while (!done) {
            if (fed <= frames) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val image = codec.getInputImage(inIdx)
                    if (image != null) {
                        // Y plane walks up so successive frames differ; the two
                        // chroma planes stay mid-grey.
                        val y = image.planes[0].buffer
                        val shade = (16 + (fed * 2) % 200).toByte()
                        while (y.hasRemaining()) y.put(shade)
                        for (p in 1..2) {
                            val c = image.planes[p].buffer
                            while (c.hasRemaining()) c.put(128.toByte())
                        }
                    }
                    val ptsUs = fed.toLong() * 1_000_000L / fps
                    if (fed == frames) {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, image?.planes?.sumOf { it.buffer.capacity() } ?: 0, ptsUs, 0)
                    }
                    fed += 1
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && started && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
        muxer.stop(); muxer.release()
        codec.stop(); codec.release()
        return out
    }

    private fun seal(src: File, chunkSize: Int): File {
        val dst = File.createTempFile("rcq-sealed", ".bin").apply { deleteOnExit() }
        dst.outputStream().use { out ->
            src.inputStream().use { input ->
                MediaStream.seal(input, out, key, src.length(), chunkSize)
            }
        }
        return dst
    }

    @Test
    fun platformExtractorReadsAChunkedContainer() {
        val src = makeMp4()
        assertTrue("encoder produced something", src.length() > 10_000)
        // A deliberately SMALL chunk so a two-second clip spans many of them:
        // this is where a reader that cannot serve a read spanning a boundary,
        // or cannot seek backwards to the moov atom, gives itself away.
        val sealed = seal(src, 64 * 1024)

        // ⚠ try/finally, not `use`: MediaExtractor is not Closeable and
        // MediaMetadataRetriever only became one on API 29, while minSdk is 26.
        val feed = MediaStream.dataSource(sealed, key)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(feed)
            assertTrue("at least one track", ex.trackCount >= 1)
            val fmt = ex.getTrackFormat(0)
            assertTrue("a video track", fmt.getString(MediaFormat.KEY_MIME)!!.startsWith("video/"))
            ex.selectTrack(0)
            // Walk the whole clip through the decrypting reader and count what
            // comes out; a container that only worked for the first chunk would
            // stop here.
            val buf = java.nio.ByteBuffer.allocate(1 shl 20)
            var samples = 0
            var bytes = 0L
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                samples += 1
                bytes += n
                ex.advance()
            }
            assertTrue("read $samples samples", samples > 10)
            assertTrue("read $bytes bytes of samples", bytes > 5_000)
        } finally {
            ex.release()
            feed.close()
        }
    }

    @Test
    fun metadataRetrieverAgreesWithTheOriginal() {
        val src = makeMp4()
        val sealed = seal(src, 64 * 1024)

        fun durationOf(r: MediaMetadataRetriever): Long =
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()

        val plainR = MediaMetadataRetriever()
        val plainDur = try {
            plainR.setDataSource(src.absolutePath)
            durationOf(plainR)
        } finally {
            plainR.release()
        }
        val feed = MediaStream.dataSource(sealed, key)
        val sealedR = MediaMetadataRetriever()
        val sealedDur = try {
            sealedR.setDataSource(feed)
            durationOf(sealedR)
        } finally {
            sealedR.release()
            feed.close()
        }
        assertEquals("same clip through the container", plainDur, sealedDur)
    }

    @Test
    fun aTamperedContainerDoesNotOpen() {
        val src = makeMp4()
        val sealed = seal(src, 64 * 1024)
        val bytes = sealed.readBytes()
        val at = MediaStream.HEADER_LEN + 40
        bytes[at] = (bytes[at].toInt() xor 0x01).toByte()
        val hurt = File.createTempFile("rcq-hurt", ".bin").apply { deleteOnExit() }
        hurt.writeBytes(bytes)
        // The reader answers end-of-stream on a chunk that fails its tag rather
        // than feeding the decoder bytes nobody vouched for, so the extractor
        // sees a file it cannot make sense of instead of a plausible one.
        var opened = true
        val feed = MediaStream.dataSource(hurt, key)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(feed)
            if (ex.trackCount == 0) opened = false
        } catch (e: Exception) {
            opened = false
        } finally {
            ex.release()
            feed.close()
        }
        assertTrue("a tampered container must not present a readable track", !opened)
    }

    @Test
    fun sealAndOpenAreConstantMemoryForALargeFile() {
        // 200 MB of plaintext through a 1 MiB chunk. The assertion is not the
        // timing, it is that this completes at all on a device where holding
        // the file four times over is an OutOfMemoryError.
        val plain = ByteArray(1 shl 20).also { SecureRandom().nextBytes(it) }
        val n = 200L * 1024 * 1024
        val dst = File.createTempFile("rcq-big", ".bin").apply { deleteOnExit() }
        val source = object : java.io.InputStream() {
            var left = n
            override fun read(): Int = if (left-- > 0) plain[0].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val take = minOf(len.toLong(), left, plain.size.toLong()).toInt()
                System.arraycopy(plain, 0, b, off, take)
                left -= take
                return take
            }
        }
        dst.outputStream().use { out -> MediaStream.seal(source, out, key, n) }
        assertEquals(MediaStream.blobLength(n), dst.length())

        var read = 0L
        MediaStream.Reader(dst, key).use { r ->
            for (i in 0 until r.chunkCount) read += r.chunk(i).size
        }
        assertEquals(n, read)
        dst.delete()
    }
}
