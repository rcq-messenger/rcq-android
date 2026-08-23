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
@Composable
internal fun FullscreenVideoViewer(
    bytes: ByteArray,
    onShare: (ByteArray) -> Unit = {},
    onSave: (ByteArray) -> Unit = {},
    onUnsupported: (ByteArray) -> Unit = {},
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
        val player = remember(bytes) { MediaPlayer() }
        var prepared by remember(bytes) { mutableStateOf(false) }
        var playing by remember(bytes) { mutableStateOf(false) }
        var failed by remember(bytes) { mutableStateOf(false) }
        var durationMs by remember(bytes) { mutableIntStateOf(0) }
        var positionMs by remember(bytes) { mutableIntStateOf(0) }
        // While the finger is on the bar the slider owns the position, not the
        // 200 ms poll — otherwise the thumb fights the ticker and snaps back.
        // -1 means "not scrubbing"; 0 is a legitimate scrub target.
        var scrubMs by remember(bytes) { mutableFloatStateOf(-1f) }
        var ratio by remember(bytes) { mutableFloatStateOf(16f / 9f) }
        var surface by remember(bytes) { mutableStateOf<Surface?>(null) }

        val toggle = {
            if (prepared) {
                if (playing) {
                    runCatching { player.pause() }; playing = false
                } else {
                    runCatching { player.start() }; playing = true
                }
            }
        }

        DisposableEffect(bytes) {
            onDispose {
                runCatching { player.release() }
                runCatching { surface?.release() }
            }
        }

        LaunchedEffect(bytes) {
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
                player.setDataSource(InMemoryVideoSource(bytes))
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
                    playing = false
                    positionMs = durationMs
                }
                // ⚠ Only a flag here. This fires on one of MediaPlayer's own
                // threads, and starting an activity (which is what the fallback
                // does) belongs on the main one — so the handling is an effect.
                player.setOnErrorListener { _, _, _ -> failed = true; true }
                player.prepareAsync()
            }.isSuccess
            if (!ok) failed = true
        }

        LaunchedEffect(failed) {
            if (failed) {
                onUnsupported(bytes)
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = toggle,
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
            ViewerAction(
                Icons.Filled.Close, stringResource(R.string.common_close),
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp), onDismiss,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
            ) {
                ViewerAction(Icons.Filled.Download, stringResource(R.string.media_save)) { onSave(bytes) }
                ViewerAction(Icons.Filled.Share, stringResource(R.string.media_share)) { onShare(bytes) }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // ⚠ The scrim is not decoration. White text laid straight on
                    // the picture vanishes the moment a light frame scrolls
                    // under it — the clock on the right sat on a white bar of
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
                // the smallest notch — "0:0" with the seconds cut off.
                Text(
                    clockOf(shown), color = Color.White, fontSize = 12.sp, maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 10.dp),
                )
                Slider(
                    value = shown.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                    onValueChange = { scrubMs = it },
                    onValueChangeFinished = {
                        val to = scrubMs.toInt()
                        scrubMs = -1f
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

/// m:ss, or h:mm:ss past the hour, from milliseconds.
private fun clockOf(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
