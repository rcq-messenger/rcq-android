package app.rcq.android.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/// One player for the whole app: voice notes and audio files alike.
///
/// It is deliberately a singleton and NOT a `remember`ed MediaPlayer inside a
/// bubble. A per-bubble player dies with the composable, so scrolling a long
/// chat stopped the audio mid-word, and two bubbles could play over each other.
/// A four-minute track makes both of those unacceptable, and the phone-side
/// analogue on iOS (`VoicePlayer`) has been a singleton for the same reason.
///
/// State is Compose state so bubbles just read `playingId`/`positionMs` and
/// redraw. Rows must key their play glyph off `playingId == message.id` rather
/// than local state — a recycled row otherwise renders someone else's pause.
object AudioPlayer {

    /// Message id of whatever is playing (or paused mid-track), or null.
    var playingId: String? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var positionMs: Int by mutableStateOf(0)
        private set
    var durationMs: Int by mutableStateOf(0)
        private set
    /// Set while the blob is still being fetched and decrypted, so the bubble
    /// can show a spinner instead of looking like a dead tap.
    var loadingId: String? by mutableStateOf(null)
        private set

    private var player: MediaPlayer? = null
    private var ticker: Thread? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    fun setLoading(id: String?) {
        loadingId = id
    }

    /// Play this file, or pause/resume if it is already the current one.
    /// Returns false when the platform decoder refuses the file, so the caller
    /// can fall back to handing it to an external app — a play button that
    /// produces silence is worse than no play button.
    fun toggle(context: Context, id: String, file: File, onError: () -> Unit = {}): Boolean {
        if (playingId == id && player != null) {
            val p = player!!
            return if (p.isPlaying) {
                runCatching { p.pause() }
                isPlaying = false
                stopTicker()
                abandonFocus()
                true
            } else {
                if (!requestFocus(context)) return false
                runCatching { p.start() }
                isPlaying = true
                startTicker()
                true
            }
        }
        stop()
        if (!requestFocus(context)) return false
        val p = MediaPlayer()
        val ok = runCatching {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
                stopTicker()
                abandonFocus()
            }
            p.setOnErrorListener { _, _, _ ->
                stop()
                onError()
                true
            }
            // Prepared off the main thread: a large file blocks for long enough
            // to drop frames, and `prepare()` is what the old voice bubble did.
            p.setOnPreparedListener { mp ->
                durationMs = runCatching { mp.duration }.getOrDefault(0)
                mp.start()
                isPlaying = true
                startTicker()
            }
            p.prepareAsync()
        }.isSuccess
        if (!ok) {
            runCatching { p.release() }
            abandonFocus()
            onError()
            return false
        }
        player = p
        playingId = id
        positionMs = 0
        durationMs = 0
        return true
    }

    fun seekToFraction(fraction: Float) {
        val p = player ?: return
        val d = durationMs
        if (d <= 0) return
        val target = (d * fraction.coerceIn(0f, 1f)).toInt()
        runCatching { p.seekTo(target) }
        positionMs = target
    }

    fun stop() {
        stopTicker()
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        playingId = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
        abandonFocus()
    }

    // ── audio focus ──────────────────────────────────────────────────────
    // Without this a podcast keeps playing over an incoming RCQ call. Losing
    // focus stops us outright rather than ducking: the other thing wanting the
    // speaker here is always a voice, never background music.

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                player?.let { p ->
                    runCatching { if (p.isPlaying) p.pause() }
                    isPlaying = false
                    stopTicker()
                }
            }
        }
    }

    private fun requestFocus(context: Context): Boolean {
        val am = audioManager
            ?: (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.also { audioManager = it }
            ?: return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return granted
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    // ── position ticker ──────────────────────────────────────────────────

    private fun startTicker() {
        stopTicker()
        val t = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val p = player ?: break
                val pos = runCatching { if (p.isPlaying) p.currentPosition else null }.getOrNull() ?: break
                positionMs = pos
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        t.isDaemon = true
        ticker = t
        t.start()
    }

    private fun stopTicker() {
        ticker?.interrupt()
        ticker = null
    }
}
