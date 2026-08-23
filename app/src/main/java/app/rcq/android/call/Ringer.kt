package app.rcq.android.call

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Call ringing audio. Incoming → the device ringtone on a loop + vibration;
 * outgoing → a ringback tone. Android has no CallKit, so we drive both
 * ourselves. Idempotent [stop].
 *
 * ⚠⚠ ONE per process, always ([shared]). There used to be two: the controller
 * held one and every IncomingCallActivity built its own, so the ring belonged
 * to whichever of them spoke last. Unlocking the phone destroys and recreates
 * that activity on MIUI, and the new instance's Ringer started the melody from
 * the first note while the old one's was cut off mid-phrase, which is what
 * reports #710 and #711 heard ("обрывается и начинает снова, и так по кругу").
 * Two instances also meant two ringtones could sound at once, which is the
 * shape of #638. A ring is one continuous thing that belongs to one CALL, not
 * to a screen, so it is keyed by call id and outlives any surface.
 */
class Ringer private constructor(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** Is an incoming ring already sounding? A repeated [startIncoming] then
     *  leaves it alone instead of starting it over.
     *
     *  ⚠ The keyguard bounces the incoming-call activity through stop/start
     *  (MIUI does it on every dismiss), and each start called this. The ring
     *  audibly cut out and began again from the first note the moment the phone
     *  was unlocked, which is what report #680 heard. A ring is one continuous
     *  thing; restarting it is never what the caller or the callee wanted. */
    @Volatile private var incomingActive = false

    /** Which call the current ring belongs to. A stop that names another call
     *  is a stale surface going away and is ignored.
     *
     *  ⚠ Every ring MUST name its call. An unowned ring (null here) matches
     *  every stop, so a stop parked by a surface that died during call A
     *  would silence the ring of call B a minute later. That is why the
     *  no-argument start is private: there is no way to ring anonymously. */
    @Volatile private var incomingCallId: String? = null

    /** Ring for [callId]. Already ringing for it (a recreated surface, a second
     *  start from the controller) leaves the melody exactly where it is. */
    @Synchronized
    fun startIncoming(callId: String?) {
        if (incomingActive && (callId == null || incomingCallId == callId)) return
        if (incomingActive) stopIncomingOnly()
        incomingCallId = callId
        startIncomingNow()
    }

    /** Stop the INCOMING ring, and only if it still belongs to [callId].
     *  Never the ringback: a surface that names a call must not be able to
     *  silence the call the user is placing (see [startRingback]). */
    @Synchronized
    fun stopFor(callId: String?) {
        if (callId != null && incomingCallId != null && incomingCallId != callId) return
        incomingCallId = null
        stopIncomingOnly()
    }

    private fun startIncomingNow() {
        if (incomingActive) return
        stopIncomingOnly()
        incomingActive = true
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                isLooping = true
                play()
            }
        }
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 1000), 0),
            )
        }
    }

    /** ⚠ Stops the RINGBACK only, never the incoming ring. The two used to
     *  live in two objects and could not reach each other; in one object a
     *  shared stop meant an incoming call silenced the ringback of the call
     *  the user was placing, and declining the incoming one left that call
     *  ringing back at nobody for the rest of its life. */
    @Synchronized
    fun startRingback() {
        stopRingbackOnly()
        runCatching {
            ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }
    }

    /** Everything quiet: the end of a call, whichever direction it went. */
    @Synchronized
    fun stop() {
        incomingCallId = null
        stopIncomingOnly()
        stopRingbackOnly()
    }

    private fun stopIncomingOnly() {
        incomingActive = false
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
    }

    private fun stopRingbackOnly() {
        runCatching { ringback?.stopTone(); ringback?.release() }
        ringback = null
    }

    companion object {
        @Volatile private var instance: Ringer? = null

        /** The one ringer of this process. */
        fun shared(context: Context): Ringer =
            instance ?: synchronized(this) {
                instance ?: Ringer(context.applicationContext).also { instance = it }
            }
    }
}
