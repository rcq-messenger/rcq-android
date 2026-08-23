package app.rcq.android.ui

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.rcq.android.R
import app.rcq.android.media.AudioPlayer
import kotlinx.coroutines.delay

/// Feeds MediaPlayer straight out of RAM.
///
/// ⚠⚠ This is the whole point of the in-app player. The blob arrives
/// encrypted and is decrypted into a ByteArray; the moment a video goes to an
/// external player we have to write that plaintext into a FileProvider path and
/// grant a stranger's process read access to it. A MediaDataSource keeps the
/// decrypted bytes inside this process — no cache file, no content URI, no
/// chooser.
///
/// `readAt` is called from the platform's own decoder threads, and the
/// framework probes past the end while it hunts for the moov atom, so a
/// position at or beyond the end is normal and answers -1 (end of stream), not
/// an exception.
private class InMemoryVideoSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= data.size) return -1
        val n = minOf(size.toLong(), data.size - position).toInt()
        if (n <= 0) return -1
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}

/// A clip the player can be handed, in either of the two shapes one arrives in.
///
/// A short clip is still a decrypted ByteArray and behaves exactly as it always
/// did. A long one is an RCQM1 container on disk plus the key that opens it
/// (crypto/MediaStream.kt), decrypted a megabyte at a time as the player asks
/// for it, because holding a whole film in a ByteArray is the thing that made
/// long videos silently fail to open at all (#691).
///
/// ⚠⚠ The privacy contract of [InMemoryVideoSource] is not weakened by the
/// second shape, it is extended: what lands on disk is the ENCRYPTED container,
/// which is what the media cache has always held, and the plaintext still never
/// leaves this process: one chunk at a time, in RAM, handed straight to the
/// decoder. No cache file, no content URI, no chooser.
internal class VideoSource private constructor(
    private val bytes: ByteArray?,
    private val file: java.io.File?,
    private val key: ByteArray?,
    /// Plaintext length, for a caller that wants to say how heavy this is.
    val sizeBytes: Long,
) {
    /// A fresh reader for MediaPlayer. The caller owns it and must close it.
    fun open(): MediaDataSource =
        if (bytes != null) InMemoryVideoSource(bytes)
        else app.rcq.android.crypto.MediaStream.dataSource(file!!, key!!)

    /// Write the plaintext to [out], verifying as it goes. False means the
    /// bytes did not check out and whatever was written must not be published.
    fun writeTo(out: java.io.OutputStream): Boolean =
        if (bytes != null) runCatching { out.write(bytes); out.flush(); true }.getOrDefault(false)
        else app.rcq.android.crypto.MediaStream.streamTo(file!!, key!!, out)

    companion object {
        fun of(bytes: ByteArray) = VideoSource(bytes, null, null, bytes.size.toLong())
        fun of(file: java.io.File, key: ByteArray, plainLen: Long) = VideoSource(null, file, key, plainLen)
    }
}

/// Did the feed the player was reading give up because a chunk did not
/// authenticate? Only a chunked container can answer anything but "no": a
/// monolithic clip is verified whole before a single byte reaches the player.
private fun feedFailedIntegrity(feed: MediaDataSource?): Boolean =
    (feed as? app.rcq.android.crypto.MediaStream.ChunkedDataSource)?.integrityFailed == true

/// Fullscreen video player — the moving-picture twin of FullscreenImageViewer:
/// black ground, the same disc-backed glyphs for close / save / share, the same
/// "decrypted bytes in, nothing on disk" contract.
///
/// Deliberately small: play/pause, a scrub bar, close. No PiP, no casting, no
/// speed control.
///
/// [onUnsupported] is the escape hatch. A codec this device cannot decode is
/// not hypothetical (10-bit HEVC off a recent iPhone, AV1 on an old handset),
/// and handing those to whatever player is installed is still the right answer
/// *there*. Same rule FileBubble already follows for audio: a play button that
/// produces a black rectangle is worse than no play button.
///
/// [onDamaged] is the OTHER failure, and it is not the same one. A chunked
/// container whose bytes were edited stops mid-playback and the player calls
/// that "completed" — the clip simply ends early, which is indistinguishable
/// from a short video and is therefore a lie told with a straight face. When
/// the feed says a chunk failed to authenticate, this fires instead: nothing is
/// handed to another player (there is nothing wrong with the codec), the person
/// is told the file did not come through in one piece.
///
/// [senderName] / [onSenderClick] are the founder's item 9(c): who sent the
/// clip, at the top, and one tap to their card. Both optional and both null by
/// default, so a caller that does not know who sent it (a share sheet, a clip
/// opened from anywhere but a bubble) simply gets no name rather than a lie.
@Composable
internal fun FullscreenVideoViewer(
    source: VideoSource,
    onShare: (VideoSource) -> Unit = {},
    onSave: (VideoSource) -> Unit = {},
    onUnsupported: (VideoSource) -> Unit = {},
    onDamaged: () -> Unit = {},
    senderName: String? = null,
    onSenderClick: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    // ⚠⚠ `dismissOnClickOutside = false`, and it is not paranoia. A tap on the
    // picture went straight past the interop TextureView to the dialog's own
    // outside-touch handler and CLOSED the player instead of pausing it —
    // watching a clip, touching the screen, and being thrown back into the chat.
    // The photo viewer never hit this because a Compose Image consumes the
    // touch; a View inside `AndroidView` does not. Closing is the X's job.
    Dialog(
        onDismissRequest = onDismiss,
        // Edge to edge on every Android (see the album pager): the controls
        // are placed by the bar's real height.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        // #656: playback used to obey the normal screen timeout, so a clip
        // longer than it went dark mid-watch. Same helper the call screen uses.
        KeepScreenOn()
        val player = remember(source) { MediaPlayer() }
        var prepared by remember(source) { mutableStateOf(false) }
        var playing by remember(source) { mutableStateOf(false) }
        var failed by remember(source) { mutableStateOf(false) }
        // Not a codec this device cannot play: bytes that did not check out.
        // The two get opposite treatment, so they are two flags.
        var damaged by remember(source) { mutableStateOf(false) }
        var durationMs by remember(source) { mutableIntStateOf(0) }
        var positionMs by remember(source) { mutableIntStateOf(0) }
        // While the finger is on the bar the slider owns the position, not the
        // 200 ms poll — otherwise the thumb fights the ticker and snaps back.
        // -1 means "not scrubbing"; 0 is a legitimate scrub target.
        var scrubMs by remember(source) { mutableFloatStateOf(-1f) }
        var ratio by remember(source) { mutableFloatStateOf(16f / 9f) }
        var surface by remember(source) { mutableStateOf<Surface?>(null) }

        val toggle = {
            if (prepared) {
                if (playing) {
                    runCatching { player.pause() }; playing = false
                } else {
                    runCatching { player.start() }; playing = true
                }
            }
        }

        // ⚠ A BOOLEAN, not `scrubMs >= 0f`, and the difference is frames. The
        // pin below is read at the top of this dialog's content, so reading the
        // scrub POSITION here would invalidate the whole player on every drag
        // frame: sixty recompositions a second of a view that is showing a
        // film. Writing `true` to a Boolean that is already `true` invalidates
        // nothing, so this flips exactly twice per drag.
        var scrubbing by remember(source) { mutableStateOf(false) }

        // Item 9(b): the controls fade out while the clip runs and come back on
        // a touch. Pinned (so nothing hides) whenever the person is obviously
        // still using them: a paused clip, or a finger on the scrub bar. Not
        // yet playing counts as paused, which is also what keeps the close
        // button up while a slow clip prepares.
        val chrome = rememberViewerChrome(pinned = !playing || scrubbing)

        // The reader behind a streamed clip holds an open file handle, so it is
        // kept and closed by hand rather than trusted to MediaPlayer's own
        // teardown: a viewer opened and closed a dozen times must not leave a
        // dozen descriptors on a container it is no longer playing.
        val feed = remember(source) { mutableStateOf<MediaDataSource?>(null) }

        DisposableEffect(source) {
            onDispose {
                runCatching { player.release() }
                runCatching { feed.value?.close() }
                runCatching { surface?.release() }
            }
        }

        LaunchedEffect(source) {
            // A voice note and a video talking over each other is nobody's idea
            // of playback, and the shared audio player owns the focus request.
            AudioPlayer.stop()
            val ok = runCatching {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                val ds = source.open()
                feed.value = ds
                player.setDataSource(ds)
                player.setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) ratio = w.toFloat() / h.toFloat()
                }
                player.setOnPreparedListener { mp ->
                    prepared = true
                    durationMs = mp.duration.coerceAtLeast(0)
                    runCatching { mp.start() }
                    playing = true
                }
                player.setOnCompletionListener {
                    // ⚠ A completion is only a completion if the feed vouched
                    // for every byte it handed over. A container with an edited
                    // chunk ends the stream at that chunk, and the player
                    // reports that end exactly like the real one.
                    if (feedFailedIntegrity(feed.value)) damaged = true
                    playing = false
                    positionMs = durationMs
                }
                // ⚠ Only a flag here. This fires on one of MediaPlayer's own
                // threads, and starting an activity (which is what the fallback
                // does) belongs on the main one — so the handling is an effect.
                player.setOnErrorListener { _, _, _ ->
                    if (feedFailedIntegrity(feed.value)) damaged = true else failed = true
                    true
                }
                player.prepareAsync()
            }.isSuccess
            if (!ok) failed = true
        }

        LaunchedEffect(failed) {
            if (failed) {
                onUnsupported(source)
                onDismiss()
            }
        }

        // Deliberately NOT the fallback player: there is nothing wrong with the
        // codec, and handing a damaged container to another app would only move
        // the same silence somewhere else.
        LaunchedEffect(damaged) {
            if (damaged) {
                onDamaged()
                onDismiss()
            }
        }

        // The surface arrives after the first layout pass, and it can go away
        // and come back (rotation, a fold), so attaching it is its own effect
        // rather than part of setup.
        LaunchedEffect(surface) { runCatching { player.setSurface(surface) } }

        LaunchedEffect(playing, prepared) {
            while (playing && prepared) {
                if (scrubMs < 0f) {
                    positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
                }
                delay(200)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Play/pause lives on the WHOLE ground, not on the picture. A
                // clickable wrapped around the TextureView never saw the tap
                // (the interop view swallows it), and the black margin beside a
                // letterboxed clip is part of the player anyway.
                //
                // ⚠ Reveal first, act second. Once the controls auto-hide, the
                // tap that brings them back must NOT also pause the clip:
                // reaching for a button you cannot see and stopping the film
                // instead is the exact frustration the auto-hide would otherwise
                // introduce. So a tap always restarts the countdown, and only
                // toggles playback when the controls were already up.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        val wasVisible = chrome.visible
                        chrome.show()
                        if (wasVisible) toggle()
                    },
                ),
        ) {
            // Letterbox by hand. A TextureView does not preserve the aspect
            // ratio, it stretches to whatever box it is given, and
            // `fillMaxWidth().aspectRatio(r)` overflows the screen for a
            // portrait clip — which is most clips shot on a phone.
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val boxRatio = maxWidth / maxHeight
                val vw = if (boxRatio > ratio) maxHeight * ratio else maxWidth
                val vh = if (boxRatio > ratio) maxHeight else maxWidth / ratio
                Box(Modifier.size(vw, vh)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        surface = Surface(st)
                                    }

                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        surface = null
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                            }
                        },
                    )
                }
            }
            if (!prepared) {
                CircularProgressIndicator(
                    color = RcqTheme.colors.accent,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
            }

            // ⚠ The dialog draws edge to edge, so every control needs the system
            // bars added by hand — without this the scrub bar sits under the
            // gesture pill and the close button under the clock.
            ViewerChrome(chrome, Modifier.align(Alignment.TopStart)) {
                ViewerAction(
                    Icons.Filled.Close, stringResource(R.string.common_close),
                    Modifier.statusBarsPadding().padding(16.dp), onDismiss,
                )
            }
            if (!senderName.isNullOrBlank()) {
                ViewerChrome(chrome, Modifier.align(Alignment.TopCenter)) {
                    ViewerSenderLabel(senderName, onClick = onSenderClick)
                }
            }
            ViewerChrome(chrome, Modifier.align(Alignment.TopEnd)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.statusBarsPadding().padding(16.dp),
                ) {
                    ViewerAction(Icons.Filled.Download, stringResource(R.string.media_save)) { onSave(source) }
                    ViewerAction(Icons.Filled.Share, stringResource(R.string.media_share)) { onShare(source) }
                }
            }

            // The scrub bar fades with the rest of the controls, not on its own
            // clock: a clip playing with its top chrome gone and a bar still
            // burning across the bottom looks broken rather than tidy. One set
            // of controls, one countdown, one fade.
            ViewerChrome(chrome, Modifier.align(Alignment.BottomCenter)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // ⚠ The scrim is not decoration. White text laid straight on
                        // the picture vanishes the moment a light frame scrolls
                        // under it: the clock on the right sat on a white bar of
                        // the test clip and simply was not there. Same lesson the
                        // photo viewer's disc-backed glyphs already record; a
                        // gradient does it for a whole strip without boxing it in.
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                            ),
                        )
                        // `navigationBarsPadding()` is zero inside a Dialog; the
                        // activity's window knows the bar's real height.
                        .padding(bottom = activityNavigationBarBottom())
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                ) {
                    ViewerAction(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (playing) R.string.chat_pause_video else R.string.chat_play_video),
                        onClick = toggle,
                    )
                    val shown = if (scrubMs >= 0f) scrubMs.toInt() else positionMs
                    // ⚠ No fixed width and no wrapping. A hard `width()` truncated
                    // the clock the moment the user's text-size setting was above
                    // the smallest notch, leaving "0:0" with the seconds cut off.
                    Text(
                        clockOf(shown), color = Color.White, fontSize = 12.sp, maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                    Slider(
                        value = shown.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                        onValueChange = { scrubMs = it; scrubbing = true },
                        onValueChangeFinished = {
                            val to = scrubMs.toInt()
                            scrubMs = -1f
                            scrubbing = false
                            if (prepared) {
                                runCatching { player.seekTo(to) }
                                positionMs = to
                            }
                        },
                        // A zero-width range throws, and an unprepared player has no
                        // duration yet, so the bar gets a nominal one until it does.
                        valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                        enabled = prepared && durationMs > 0,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(clockOf(durationMs), color = Color.White, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/// m:ss, or h:mm:ss past the hour, from milliseconds.
private fun clockOf(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
