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
 * Stream choice (user-report driven): tones play as MEDIA, not NOTIFICATION.
 * The volume rocker and the main "volume" slider control the media stream,
 * so on the old NOTIFICATION stream users lowered the volume and the chime
 * stayed at full blast (the notification stream is hidden or ring-tied on
 * most OEM ROMs). Media usage makes the obvious gesture work. Because media
 * is NOT auto-muted by silent/vibrate or DND, [play] checks those modes
 * explicitly — phone on silent stays silent.
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
            .setUsage(AudioAttributes.USAGE_MEDIA)
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

    /** Silent or vibrate ringer, or any Do-Not-Disturb filter → stay quiet
     *  (media usage bypasses both at the system level, so we honour them
     *  here — this is what "выключить звук на телефоне" means to a user). */
    private fun systemWantsSilence(): Boolean {
        val ringer = runCatching { audioManager?.ringerMode }.getOrNull()
        if (ringer != null && ringer != AudioManager.RINGER_MODE_NORMAL) return true
        val filter = runCatching { notificationManager?.currentInterruptionFilter }.getOrNull()
        return filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun play(id: Int) {
        if (!LocalStores.soundMasterOn() || systemWantsSilence()) return
        pool?.play(id, 1f, 1f, 1, 0, 1f)
    }

    /** Inbound message to a non-active, non-muted thread. */
    fun message() { if (LocalStores.soundMessagesOn()) play(msg) }

    /** A contact transitioned to online. */
    fun contactOnline() { if (LocalStores.soundPresenceOn()) play(online) }

    /** A contact transitioned to offline. */
    fun contactOffline() { if (LocalStores.soundPresenceOn()) play(offline) }
}
