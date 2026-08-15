package app.rcq.android.media

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import app.rcq.android.R
import app.rcq.android.data.LocalStores

/**
 * Plays short in-app tones for message + presence events, gated by the
 * [LocalStores] sound toggles (master + per-kind). The Android analogue of
 * the iOS SoundService. Bundled tones live in res/raw (snd_message /
 * snd_online / snd_offline). Loading is async; [SoundPool.play] on a
 * not-yet-loaded id is a silent no-op, so no readiness gate is needed.
 *
 * Stream choice: NOTIFICATION usage, the same one the message notification
 * channel carries ([app.rcq.android.push.Push.ensureChannels]).
 *
 * ⚠ This was USAGE_MEDIA for a while and the reasoning was wrong. The argument
 * was that the media stream is the one the volume rocker moves by default, so
 * lowering it is "the obvious gesture". What that actually did was put a chat
 * alert into the stream people reserve for what they are LISTENING to: "я могу
 * слушать громко музыку, и из-за этого мне нужно RCQ убавлять? и наоборот"
 * (#541). One knob cannot mean both "how loud is my music" and "how loud is my
 * messenger", and when it tries, the user loses either way.
 *
 * A message alert belongs on the notification stream, next to every other
 * messenger on the phone, and that is also the stream the SAME tone rides when
 * it comes from the shade — so the app now sounds the same whether it is open
 * or closed, which is the whole of #545's complaint. The in-app slider below
 * is a scale FACTOR on top of that stream, not a second volume control: see
 * [play].
 *
 * The explicit silent/vibrate/DND checks stay. Notification usage is honoured
 * by stock Android, but OEM ROMs disagree about whether STREAM_NOTIFICATION
 * follows the ringer, and "выключил звук на телефоне" has to mean silence on
 * all of them.
 *
 * Call [init] once from MainActivity.onCreate (after LocalStores.init).
 */
object SoundService {
    private var pool: SoundPool? = null
    private var audioManager: AudioManager? = null
    private var notificationManager: NotificationManager? = null
    private var msg = 0
    private var online = 0
    private var offline = 0

    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        val app = context.applicationContext
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        msg = p.load(app, R.raw.snd_message, 1)
        online = p.load(app, R.raw.snd_online, 1)
        offline = p.load(app, R.raw.snd_offline, 1)
        pool = p
    }

    /** Silent or vibrate ringer, or any Do-Not-Disturb filter → stay quiet.
     *  Notification usage is meant to honour both already; OEM ROMs vary, and
     *  this is what "выключить звук на телефоне" means to a user. */
    private fun systemWantsSilence(): Boolean {
        val ringer = runCatching { audioManager?.ringerMode }.getOrNull()
        if (ringer != null && ringer != AudioManager.RINGER_MODE_NORMAL) return true
        val filter = runCatching { notificationManager?.currentInterruptionFilter }.getOrNull()
        return filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    /** A call is up — a cellular one, an RCQ one, or anyone else's VoIP.
     *
     *  `AudioManager.mode` is the right question here rather than our own call
     *  state: report #424 was someone on an ordinary phone call hearing a
     *  contact come online at full volume, and that call is not ours to know
     *  about. MODE_IN_CALL is telephony, MODE_IN_COMMUNICATION is VoIP
     *  (including ours), and neither needs a permission to read. */
    private fun inAnyCall(): Boolean {
        val mode = runCatching { audioManager?.mode }.getOrNull() ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /** Shortest gap between two tones. Four messages landing together used to
     *  play four overlapping copies of the same 300ms chime (SoundPool is built
     *  with four streams), which reads as a stutter rather than as four
     *  messages. One chime says "something arrived" just as well. */
    private const val MIN_GAP_MS = 1_200L
    private var lastPlayedAt = 0L

    private fun play(id: Int, throttle: Boolean = true) {
        if (!LocalStores.soundMasterOn() || systemWantsSilence()) return
        // Presence chirps during a call are pure interruption: the person is
        // talking, and "someone came online" can wait.
        if (inAnyCall()) return
        // Own volume knob. This is a SCALE FACTOR on the notification stream,
        // never an absolute level: how loud a notification is on this phone is
        // the system's answer, and an app cannot change it. The slider can only
        // make our tone quieter than that, which is what it is labelled as now
        // — the old label implied it set "the notification volume" and #545 is
        // someone discovering it does not ("звук пуш-уведомления всегда
        // проигрывается на полной громкости").
        val vol = LocalStores.soundVolumeLevel()
        if (vol <= 0f) return
        if (throttle) {
            val now = android.os.SystemClock.elapsedRealtime()
            synchronized(this) {
                if (now - lastPlayedAt < MIN_GAP_MS) return
                lastPlayedAt = now
            }
        }
        pool?.play(id, vol, vol, 1, 0, 1f)
    }

    /** Play the message tone regardless of the per-kind toggle, for the
     *  settings slider preview. Still honours master + system silence.
     *  Not throttled: the whole point is to answer every drag of the slider. */
    fun previewMessage() = play(msg, throttle = false)

    /** Inbound message to a non-active, non-muted thread. */
    fun message() { if (LocalStores.soundMessagesOn()) play(msg) }

    /** Whether an online/offline transition for a contact is worth a chime
     *  under the current [LocalStores.PresenceSoundMode]. [favorite] is that
     *  contact's favourite flag; the caller has the roster, we do not. */
    private fun presenceAudible(favorite: Boolean): Boolean =
        when (LocalStores.presenceSoundMode()) {
            LocalStores.PresenceSoundMode.ALL -> true
            LocalStores.PresenceSoundMode.FAVORITES -> favorite
            LocalStores.PresenceSoundMode.OFF -> false
        }

    /** A contact transitioned to online. */
    fun contactOnline(favorite: Boolean) { if (presenceAudible(favorite)) play(online) }

    /** A contact transitioned to offline. */
    fun contactOffline(favorite: Boolean) { if (presenceAudible(favorite)) play(offline) }
}
