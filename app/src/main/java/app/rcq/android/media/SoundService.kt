package app.rcq.android.media

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import app.rcq.android.R
import app.rcq.android.data.LocalStores

/**
 * Plays RCQ's short tones: the message tone, the missed-call tone, the
 * online/offline chimes. The Android analogue of the iOS SoundService. Bundled
 * tones live in res/raw (snd_message / snd_online / snd_offline).
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
 * messenger on the phone. The volume slider in RCQ's settings is a scale FACTOR
 * on top of that stream, never an absolute level: how loud a notification is on
 * this phone is the system's answer and an app cannot change it.
 *
 * ⚠⚠ #978, and what changed here because of it. The tone used to have TWO
 * players: this one, scaled by the slider, for the open app, and the message
 * notification CHANNEL, whose loudness is Android's and cannot be scaled by an
 * app, for everything else. So the person heard their 5% while they were setting
 * it and the phone's full notification level a minute later, from what is
 * audibly the same chime: "Когда убавляешь громкость в звуках по выходу из
 * настроек орет на полную хотя выставил 5". #545 answered that by relabelling
 * the slider; it came back as #978.
 *
 * There is now one player, this one. The message channel's own sound is silence
 * ([app.rcq.android.push.Push.CHANNEL_MESSAGES]) and
 * [soundMessageNotification] is what a notification sounds like: the same wav,
 * on the same stream, times the number the person chose.
 *
 * The explicit silent/vibrate/DND checks stay, and matter more than they did.
 * Notification usage is meant to honour all three already, but OEM ROMs disagree
 * about whether STREAM_NOTIFICATION follows the ringer, "выключил звук на
 * телефоне" has to mean silence on all of them, and since the channel no longer
 * makes the noise there is nobody else left to get this right.
 *
 * Call [init] once from MainActivity.onCreate (after LocalStores.init). ⚠ The
 * notification path deliberately does NOT need it: see [playOnce].
 */
object SoundService {
    // ⚠⚠ @Volatile, all of them. Everything here used to be touched from the
    // main thread only. The notification tone is asked for from Push.post, which
    // runs on whatever thread delivered the message (a UnifiedPush service
    // callback, the live socket's coroutine), so these are cross-thread reads
    // now and a plain var gives the reader no guarantee of ever seeing init's
    // write.
    @Volatile private var pool: SoundPool? = null
    @Volatile private var audioManager: AudioManager? = null
    @Volatile private var notificationManager: NotificationManager? = null
    @Volatile private var msg = 0
    @Volatile private var online = 0
    @Volatile private var offline = 0

    private fun notificationAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(notificationAttributes()).build()
        val app = context.applicationContext
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        msg = p.load(app, R.raw.snd_message, 1)
        online = p.load(app, R.raw.snd_online, 1)
        offline = p.load(app, R.raw.snd_offline, 1)
        pool = p
    }

    /** The AudioManager, from a Context when the caller has one.
     *
     *  ⚠⚠ The notification path must never read the cached field alone, and this
     *  is the fail-OPEN it exists to prevent. [audioManager] is written by
     *  [init], which the in-app tone guarantees (it runs from
     *  MainActivity.onCreate) and a headless push wake does not. On a null
     *  manager [ringerWantsSilence] answers "not silent" and
     *  [telephonyCallInProgress] answers "no call", so a process that never
     *  initialised would chime through silent mode and through a phone call.
     *  Resolving from the Context removes the question instead of documenting
     *  it. */
    private fun audioOf(ctx: Context?): AudioManager? = audioManager
        ?: (ctx?.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)

    private fun notifierOf(ctx: Context?): NotificationManager? = notificationManager
        ?: (ctx?.applicationContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)

    /** Silent or vibrate ringer. Notification usage is meant to honour both
     *  already; OEM ROMs vary, and this is what "выключить звук на телефоне"
     *  means to a user.
     *
     *  ⚠ A phone in VIBRATE mode still BUZZES for a message, and not by anything
     *  this file does: the message channel carries a (silent) sound, and Android
     *  turns a sound it cannot play in vibrate mode into its fallback vibration.
     *  See Push.createMessageChannel, which is where that is kept working. */
    private fun ringerWantsSilence(ctx: Context? = null): Boolean {
        val ringer = runCatching { audioOf(ctx)?.ringerMode }.getOrNull()
        return ringer != null && ringer != AudioManager.RINGER_MODE_NORMAL
    }

    /** Any Do Not Disturb filter, including the UNKNOWN the platform answers
     *  when it cannot say. Anything other than "no filter" is treated as DND
     *  here; [app.rcq.android.push.Push.mayPlayOurTone] asks the same question
     *  the same way so one phone has one answer. */
    private fun dndFilterOn(ctx: Context? = null): Boolean {
        val filter = runCatching { notifierOf(ctx)?.currentInterruptionFilter }.getOrNull()
        return filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    /** Silent or vibrate ringer, or any Do-Not-Disturb filter, for the IN-APP
     *  tone, which has no channel and so no per-channel DND override to read.
     *  Unchanged since before #978. */
    private fun systemWantsSilence(): Boolean = ringerWantsSilence() || dndFilterOn()

    /** Whether a notification sound would be inaudible on this phone right now.
     *
     *  ⚠⚠ The NOTIFICATION stream's volume, and NOT [ringerWantsSilence], and
     *  the difference is a setting somebody explicitly asked for. Measured on the
     *  API 35 emulator, one row per state, `AudioManager.ringerMode` first and
     *  `getStreamVolume(STREAM_NOTIFICATION)` second:
     *
     *      normal ringer, no DND         NORMAL(2)  5
     *      normal ringer, DND priority   SILENT(0)  5   <- ringerMode LIES
     *      normal ringer, DND total      SILENT(0)  0
     *      vibrate ringer, no DND        VIBRATE(1) 0
     *      silent ringer, no DND         SILENT(0)  0
     *
     *  The public `ringerMode` is overridden to SILENT by any Zen mode while the
     *  real (hidden) internal mode stays NORMAL, so reading it would silence the
     *  tone for a channel the person granted "Override Do Not Disturb", which is
     *  the one case where DND is supposed to let a sound through. The stream's
     *  volume is 0 in exactly the states where nothing can be heard, which is
     *  also the question the platform itself asks: buzzBeepBlinkLocked's
     *  sound-to-vibration fallback is gated on
     *  `getStreamVolume(toLegacyStreamType(attrs)) == 0`.
     *
     *  ⚠ The ringer is still consulted, but only while no filter is on. The
     *  reason is the one this file has always given: OEM ROMs disagree about
     *  whether STREAM_NOTIFICATION follows the ringer, and "выключил звук на
     *  телефоне" has to mean silence on all of them. */
    private fun notificationWouldBeInaudible(ctx: Context): Boolean {
        // No AudioManager at all means a phone where getSystemService failed, and
        // the Context makes that unreachable here rather than merely unlikely: see
        // [audioOf].
        val am = audioOf(ctx) ?: return false
        val vol = runCatching { am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) }.getOrNull()
        if (vol != null && vol <= 0) return true
        if (dndFilterOn(ctx)) return false
        return ringerWantsSilence(ctx)
    }

    /** A call is up: a cellular one, an RCQ one, or anyone else's VoIP. The
     *  IN-APP tone's question, and only the in-app tone's: see
     *  [telephonyCallInProgress] for the notification's.
     *
     *  `AudioManager.mode` is the right question here rather than our own call
     *  state: report #424 was someone on an ordinary phone call hearing a
     *  contact come online at full volume, and that call is not ours to know
     *  about. MODE_IN_CALL is telephony, MODE_IN_COMMUNICATION is VoIP
     *  (including ours), and neither needs a permission to read.
     *
     *  ⚠ Wide on purpose here, because of what it governs: a presence chirp and
     *  an in-app chime for somebody who is talking and looking at the screen.
     *  "Someone came online" can always wait. A MESSAGE in the shade cannot, so
     *  it is not asked this question. */
    private fun inAnyCall(ctx: Context? = null): Boolean {
        val mode = runCatching { audioOf(ctx)?.mode }.getOrNull() ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /** The platform's own "there is a call" mute, for a NOTIFICATION's tone,
     *  reproduced no wider than the platform draws it.
     *
     *  ⚠⚠ This was [inAnyCall] in the first cut of #978, and that was stricter
     *  than Android in a way nobody asked for: MODE_IN_COMMUNICATION is set by
     *  any VoIP app, ours included, and by any app that sets it and forgets to
     *  put it back, so "a call is in progress" could mean "some app left the
     *  audio mode behind an hour ago" and messages made no sound for as long as
     *  it stayed that way. Before v3 the shade was Android's business, and
     *  Android's rule is narrower: NotificationAttentionHelper's
     *  `disableNotificationEffects` (dexdump of classes2.dex out of
     *  /system/framework/services.jar pulled off the API 35 image, offsets 0039
     *  to 0047, reached from `shouldMuteNotificationLocked` at 001b) mutes a
     *  notification's effects when
     *  `mCallState != CALL_STATE_IDLE && !ZenModeHelper.isCall(record)`, which
     *  is a TELEPHONY call, in-call or ringing, and nothing else.
     *
     *  MODE_IN_CALL and MODE_RINGTONE are the closest an app with no
     *  READ_PHONE_STATE can get to that call state, and both are set by the
     *  telephony/dialer side rather than by us. MODE_RINGTONE is here and was
     *  not before: a message landing while the phone RINGS used to be muted by
     *  the platform and was chiming over the ringtone.
     *
     *  ⚠ WHAT REMAINS, disclosed rather than papered over:
     *
     *  - During a cellular call a message makes no sound from RCQ at all. The
     *    platform used to answer with a short in-call blip of its own
     *    (`mInCallNotificationUri`, same class) and that is not something an app
     *    can ask it for. We do not imitate it.
     *  - During somebody ELSE's VoIP call, or during an RCQ call the person has
     *    left running in another app, a message now chimes. That is what the
     *    channel sound did before v3. When RCQ's own call is in FRONT the
     *    notification is silent anyway (Push.post's foregroundQuiet).
     *  - `AudioManager.mode` is not the telephony call state. This is as close
     *    as we can read without a permission, and we do not ask for one to
     *    decide how loud a chime is. */
    private fun telephonyCallInProgress(ctx: Context? = null): Boolean {
        val mode = runCatching { audioOf(ctx)?.mode }.getOrNull() ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    /** Shortest gap between two tones. Four messages landing together used to
     *  play four overlapping copies of the same chime (SoundPool is built with
     *  four streams), which reads as a stutter rather than as four messages. One
     *  chime says "something arrived" just as well. */
    private const val MIN_GAP_MS = 1_200L

    /** Shortest gap between two tones of ANY kind: enough that two samples
     *  never start on top of each other, and short enough that a presence
     *  chime and a message tone can both be heard when both are true. */
    private const val MIN_OVERLAP_MS = 250L

    /** Which throttle a tone belongs to. Separate clocks, because a throttle is
     *  about one sound arriving on top of another sound of the SAME kind. */
    internal enum class ToneClock {
        /** A message, in the app or in the shade, and a missed call: the same wav
         *  for the same "somebody reached you", and a burst of them is the thing
         *  [MIN_GAP_MS] exists for.
         *
         *  ⚠ Sharing one clock has a direction the first cut of #978 did not
         *  state: a message arriving within [MIN_GAP_MS] of an IN-APP tone or of a
         *  missed-call tone posts with no sound, where before v3 the channel would
         *  have chimed for it. That is the same "one chime says something arrived"
         *  trade the throttle is for, now reaching one case it did not reach
         *  before. */
        MESSAGE,

        /** A new device connected to the account, or a moderator's reply to an
         *  abuse report. Their own clock, so a security notice landing a second
         *  after a message is never swallowed as a duplicate of it. */
        NOTICE,

        /** Somebody appeared or disappeared.
         *
         *  ⚠ ITS OWN CLOCK SINCE #1030, and the missing clock is half of why
         *  that report says "хаотично". Presence used to pass `null` here,
         *  which meant it was throttled by [lastPlayedAt] alone — the one
         *  counter every kind of tone stamps. So a message arriving within
         *  1200 ms of a roster refresh swallowed the presence chime, and a
         *  presence chime swallowed the next message's in-app tone, and the
         *  same transition therefore made a sound or no sound depending on
         *  traffic that had nothing to do with it. */
        PRESENCE,
    }

    /** When the last tone of ANY kind started. The in-app throttle's clock, and
     *  only the in-app throttle's: the four-overlapping-copies problem is about
     *  streams, not about meanings, so a message tone and a presence chirp inside
     *  one window still count as one tone here. Guarded by this object's
     *  monitor. */
    private var lastPlayedAt = 0L

    /** When a tone on each [ToneClock] was last DECIDED ON, in-app or in the
     *  shade. The notification path stamps before the media stack runs (see
     *  [toneThread]), so this is "we have announced this", not "a speaker moved".
     *
     *  ⚠⚠ Separate from [lastPlayedAt] on purpose, and the reason is a bug the
     *  first cut of #978 shipped. The notification tone turns "a tone played
     *  within [MIN_GAP_MS]" into silence for the notification it is about to
     *  announce, which is right for a burst of messages and catastrophic for
     *  anything else: with a shared clock a contact coming online
     *  ([contactOnline], which fires on every presence event under the default
     *  ALL setting, often enough that #552 is about how often) took the
     *  message's sound away ENTIRELY. A presence chirp must never be able to
     *  answer for a message. Guarded by this object's monitor. */
    private var lastMessageToneAt = 0L
    private var lastNoticeToneAt = 0L
    private var lastPresenceToneAt = 0L

    private fun lastToneOn(clock: ToneClock): Long = when (clock) {
        ToneClock.MESSAGE -> lastMessageToneAt
        ToneClock.NOTICE -> lastNoticeToneAt
        ToneClock.PRESENCE -> lastPresenceToneAt
    }

    /**
     * The slider position as a GAIN, and the reason it is not the position
     * itself.
     *
     * Loudness is roughly logarithmic and a player's volume parameter is
     * linear amplitude, so passing the slider straight through means the whole
     * upper half of its travel spans about 6 dB: drag it from the end to the
     * middle and almost nothing happens. That is one of the three mechanisms
     * behind "пробовал ползунком уменьшить звук, и всё равно... то как
     * заорёт" (#1030). Squaring turns the same travel into about 12 dB and
     * makes the middle of the slider sound like the middle.
     *
     * 1.0 still means 1.0 and 0 still means silence, so nobody who left the
     * slider where it was hears a change.
     */
    internal fun toneGain(level: Float): Float {
        // ⚠ CLAMP FIRST. Squaring a negative makes it positive, so a level
        // below zero — which is what a legacy preference or a bad write can
        // hold — came back as a quiet but audible tone instead of as silence.
        // Caught by the test that pins zero as silence.
        val clamped = level.coerceIn(0f, 1f)
        return clamped * clamped
    }

    /** Start [id] on the pool at [vol]. False when no stream started.
     *
     *  ⚠ SoundPool decodes asynchronously and answers 0 for an id it does not
     *  know, for a sample whose state is not yet READY, and when it cannot
     *  allocate a stream, so a pool that was built moments ago can refuse to
     *  play and a busy one can too. That is survivable for the
     *  in-app tone (nothing is listening to an app with no window yet) and is
     *  NOT survivable for a notification, which is why the notification path
     *  uses [playOnce] instead. */
    private fun start(id: Int, vol: Float): Boolean =
        (pool?.play(id, vol, vol, 1, 0, 1f) ?: 0) != 0

    private fun play(id: Int, throttle: Boolean = true, clock: ToneClock? = null) {
        if (!LocalStores.soundMasterOn() || systemWantsSilence()) return
        // Presence chirps during a call are pure interruption: the person is
        // talking, and "someone came online" can wait.
        if (inAnyCall()) return
        val vol = toneGain(LocalStores.soundVolumeLevel())
        if (vol <= 0f) return
        if (!throttle) { start(id, vol); return }
        synchronized(this) {
            val now = android.os.SystemClock.elapsedRealtime()
            // TWO gates, and they answer different questions (#1030).
            //
            // [MIN_OVERLAP_MS] is about STREAMS: two samples starting in the
            // same instant read as a stutter whatever they mean, so nothing
            // may start on top of anything else. It is short.
            //
            // [MIN_GAP_MS] is about MEANING: a burst of four messages is one
            // "something arrived". That one belongs to the kind of tone, not
            // to the speaker, so it is measured per clock. Measuring it
            // globally — which is what a single counter did — let an unrelated
            // message tone decide whether a contact's arrival was audible.
            if (now - lastPlayedAt < MIN_OVERLAP_MS) return
            if (clock != null && now - lastToneOn(clock) < MIN_GAP_MS) return
            if (clock == null && now - lastPlayedAt < MIN_GAP_MS) return
            // Stamped only when a stream really started, so a tone lost to a
            // pool that is not up yet does not eat the next second of gap.
            if (start(id, vol)) stampTone(now, clock)
        }
    }

    /** Every clock, from one place, so a new caller cannot stamp half of them.
     *  Call with this object's monitor held. */
    private fun stampTone(now: Long, clock: ToneClock?) {
        lastPlayedAt = now
        when (clock) {
            ToneClock.MESSAGE -> lastMessageToneAt = now
            ToneClock.NOTICE -> lastNoticeToneAt = now
            ToneClock.PRESENCE -> lastPresenceToneAt = now
            null -> Unit
        }
    }

    /** Play the message tone regardless of the per-kind toggle, for the settings
     *  slider preview. Still honours master + system silence. Not throttled: the
     *  whole point is to answer every drag of the slider.
     *
     *  ⚠ Stamps no clock. A person dragging the slider is producing tones on
     *  purpose and by the dozen; letting those count as "this message has been
     *  announced" would mean a message arriving mid-drag posts silently. */
    fun previewMessage() = play(msg, throttle = false)

    /** Play a presence sample on demand, from the settings screen.
     *
     *  Unthrottled, because the person asked for it, and the same wav at the
     *  same gain they will hear in the wild: the volume slider's own preview
     *  plays the MESSAGE tone, which is a different and objectively louder
     *  sample, so "turn that online-offline sound down" was being judged
     *  against a sound that is never used in that role (#1030). */
    fun previewPresence(online: Boolean) = play(if (online) this.online else offline, throttle = false)

    /** The level to play a NOTIFICATION's tone at, 0f meaning "no tone".
     *
     *  Pure and internal so every rule that matters is pinned down in a JVM test
     *  instead of on a phone: reproducing "a wake arrives while the ringer is on
     *  vibrate" by hand is a five-minute ritual per case and there are more than
     *  twenty of them.
     *
     *  ⚠ Do Not Disturb is NOT a parameter here:
     *  [app.rcq.android.push.Push.ownsToneOn] answers it for ANY filter and this
     *  function is never reached under one. Why it is answered there, why the
     *  channel's DND override does not change the answer, and what that costs is
     *  written out at that call. What IS here is [phoneSilent]
     *  ([notificationWouldBeInaudible]), which is a different question from "is
     *  the ringer on silent" and covers total-silence DND on the way.
     *  [sinceLastTone] is measured on this notification's own [ToneClock].
     *
     *  ⚠ One platform mute nothing here reproduces, and it cannot be: a
     *  notification listener (a watch or a car companion) can ask the phone to
     *  stop making notification noise with HINT_HOST_DISABLE_NOTIFICATION_EFFECTS,
     *  and `disableNotificationEffects` honours it (same dexdump, offsets 0007 to
     *  0027). There is no API for an app to read another listener's hints, so
     *  when the channel made the noise this was handled for us and now it is not:
     *  on a phone paired with a companion that sets that hint, RCQ's tone plays
     *  where the channel's would have been muted. Stated because we cannot fix
     *  it, not because it is fine. */
    internal fun notificationToneLevel(
        volume: Float,
        chosenForShade: Boolean,
        masterOn: Boolean,
        perKindOn: Boolean,
        phoneSilent: Boolean,
        telephonyCall: Boolean,
        sinceLastTone: Long,
    ): Float {
        // RCQ's own switches. The caller has already hushed the notification for
        // these (Push.post's settingsQuiet, showMissedCall's masterOff); this is
        // the same answer from the other side so neither can drift.
        if (!masterOn || !perKindOn) return 0f
        // Silence the phone is imposing. A notification stream nothing can be
        // heard on (silent or vibrate ringer, total-silence DND), or a telephony
        // call in progress or ringing, which is the one call the platform itself
        // muted a message for ([telephonyCallInProgress]).
        if (phoneSilent || telephonyCall) return 0f
        // Already announced, just now, by a tone on this clock. Push coalesces
        // per notification id, so four messages in four chats are four
        // first-alerts as far as it is concerned; this gap is what keeps that
        // from being four overlapping copies of one chime.
        if (sinceLastTone < MIN_GAP_MS) return 0f
        // ⚠⚠ The migration, and the only reason this parameter exists. Until
        // #978 this slider was labelled "In-app tone volume" and its description
        // said in so many words that the loudness of a notification belongs to
        // Android, so every number stored under it is an answer to a DIFFERENT
        // question. Reading those numbers under the new meaning would quietly
        // turn somebody's "the chime is too loud while I am reading" into "my
        // phone barely announces messages", or at zero into "my phone never
        // does". So a level that predates the new label governs the open app
        // only, exactly as it always did, and the notification keeps the phone's
        // level until the slider is next MOVED. See
        // LocalStores._soundVolumeForShade, and the one line of copy on the
        // Sounds screen that says so while it is true.
        if (!chosenForShade) return 1f
        // Zero is a level, not an absence of one, and handing a zero back to a
        // full-volume channel is #978 in its purest form. It silences the
        // missed-call tone too: the slider is every tone RCQ plays for a MESSAGE,
        // which is what its title and description now say. ⚠ Not every tone RCQ
        // plays full stop: the incoming-call ringtone
        // ([app.rcq.android.call.Ringer]) is the phone's own, at the phone's
        // ringtone level, and neither this slider nor the master switch reaches
        // it. The description names that exception instead of implying zero
        // silences a call.
        // ⚠ The same curve the open app applies ([toneGain]), for the same
        // reason and so the two cannot drift: one slider, one meaning. Linear
        // amplitude spent the top half of the travel on about 6 dB, which is
        // why "ползунком... всё равно... как заорёт" (#1030).
        return toneGain(volume)
    }

    /** Sound an arriving MESSAGE's notification. True when a tone was played.
     *
     *  ⚠ The caller settles the shade's half first (Push.mayPlayOurTone):
     *  notifications switched off, a channel given a sound of its own, a channel
     *  dropped to Silent. All of it has to be decided BEFORE this call, because
     *  this call makes a noise. */
    fun soundMessageNotification(ctx: Context): Boolean =
        soundNotification(ctx, ToneClock.MESSAGE, perKindOn = LocalStores.soundMessagesOn())

    /** [soundMessageNotification] for a "new device connected" notice or a reply
     *  to an abuse report, which ride the message channel and would otherwise
     *  have been the one path still shouting at full volume for the person who
     *  filed #978, reports being what they do. Their own clock: see
     *  [ToneClock.NOTICE]. */
    fun soundNoticeNotification(ctx: Context): Boolean =
        soundNotification(ctx, ToneClock.NOTICE, perKindOn = LocalStores.soundMessagesOn())

    /** [soundMessageNotification] for a missed call.
     *
     *  ⚠ The per-kind switch is deliberately NOT consulted, because the
     *  notification it belongs to does not consult it either: a missed call is
     *  not a message, so "message sounds: off" leaves it audible and only the
     *  master switch silences it (#890). */
    fun soundMissedCallNotification(ctx: Context): Boolean =
        soundNotification(ctx, ToneClock.MESSAGE, perKindOn = true)

    /** The one thread every notification tone is prepared and started on.
     *
     *  ⚠⚠ #978 review, and the reason it exists at all: [playOnce] is
     *  synchronous by design (that is why it is MediaPlayer and not the pool),
     *  and the thread it used to be synchronous ON is the app's MAIN thread.
     *  `RcqPushService` extends `android.app.Service`, the UnifiedPush connector
     *  binds it with the three-argument `bindService` and calls `onMessage` from
     *  the `ServiceConnection` callback with no Handler and no executor anywhere
     *  in between, so a push-woken message ran `prepare()` on the main looper:
     *  the measured 50-110ms typical, and one 865ms of eight cold wakes while the
     *  media stack warmed up. With the monitor held across it, the live socket's
     *  IO coroutine ([app.rcq.android.Session]) and the main thread could also
     *  queue behind each other for the sum of the two.
     *
     *  So the throttle stays under the monitor and the media stack goes here. One
     *  thread and not a pool of them, because two tones at once is the thing
     *  [MIN_GAP_MS] exists to prevent, and because it keeps [playOnce]
     *  single-threaded by construction.
     *
     *  ⚠⚠ What it costs, and it is not nothing: the caller no longer learns
     *  whether the tone played, and anything that stops this thread between the
     *  dispatch and `prepare()` loses that tone silently. The first cut of this
     *  named only one such thing, a process killed in the window, and missed the
     *  one that made report #1028: with the screen off there is no guarantee the
     *  CPU stays up at all. A wake arrives over the socket, the kernel holds the
     *  SoC awake for the network packet and for the binder call that posts the
     *  notification, this dispatch returns immediately, every stack unwinds, and
     *  the device suspends before `prepare()` (50-110ms typical, 865ms cold) plus
     *  the ~450ms of wav have run. The cached-app freezer does the same thing to
     *  a backgrounded process for the same reason. Both are ordinary Android; the
     *  defect was moving the sound into our process without holding the CPU for
     *  it, since before v3 the channel's own sound was played by system_server,
     *  which suspend cannot reach. So the dispatch takes a [PARTIAL_WAKE_LOCK]
     *  ([acquireToneWake]) and holds it until the player really finishes.
     *
     *  ⚠ The lock alone is not enough either, and the second half of #1028 says
     *  why: the reporter heard the missing chimes arrive when the screen came
     *  back on, which is this queue draining at once. A tone is about an event
     *  that just happened, so one that waited longer than
     *  [TONE_DISPATCH_MAX_AGE_MS] is dropped rather than played late
     *  ([toneStillWanted]). */
    private val toneThread: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "rcq-tone") }
    }

    /** How long the tone's wake lock may be held, as a safety net and nothing
     *  more: the lock is released the moment the player reports it is done. Only
     *  a MediaPlayer that neither completes nor errors ever reaches this, and 3s
     *  is comfortably longer than the ~450ms wav plus the worst measured cold
     *  `prepare()`. */
    private const val TONE_WAKE_MS = 3_000L

    /** How stale a dispatched tone may be and still be worth playing.
     *
     *  Above [MIN_GAP_MS] on purpose: a tone that lost its gap was never
     *  dispatched, so this window only ever cuts off a dispatch the DEVICE
     *  delayed, and it has to be wide enough that an ordinary cold wake (the
     *  865ms `prepare()` case, measured once in eight) still chimes. */
    internal const val TONE_DISPATCH_MAX_AGE_MS = 1_500L

    /** Whether a tone dispatched at [dispatchedAt] is still worth playing at
     *  [now], both on [android.os.SystemClock.elapsedRealtime]'s clock, which
     *  counts time the device spent suspended and is the whole reason this
     *  question can be asked.
     *
     *  ⚠ A negative age plays. elapsedRealtime is monotonic so it should not
     *  happen, and "the clock did something we do not understand" must not be a
     *  reason to swallow the alert for an arriving message. */
    internal fun toneStillWanted(dispatchedAt: Long, now: Long): Boolean =
        now - dispatchedAt <= TONE_DISPATCH_MAX_AGE_MS

    /** One dispatch's CPU lock, released exactly once however the tone ends.
     *
     *  ⚠ Idempotent by construction and not by convention: the release is
     *  reached from the completion listener (MAIN looper, see [playOnce]), from
     *  the error listener, and from [toneThread] when the player never started,
     *  and a WakeLock released twice throws. */
    private class ToneWake(private val lock: android.os.PowerManager.WakeLock?) {
        private val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release() {
            if (!done.compareAndSet(false, true)) return
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    /** Hold the CPU for one notification tone. Never throws and never returns
     *  null: a phone that refuses the lock still gets the chime attempt it would
     *  have got before, it just keeps the old odds of losing it to suspend.
     *
     *  PARTIAL and not SCREEN_BRIGHT: this is a sound, and a message must not
     *  light up a dark room. The screen lock exists on the call path only
     *  ([app.rcq.android.push.Push.wakeScreenForRing]), where the point IS to be
     *  looked at. WAKE_LOCK is already declared for that one. */
    private fun acquireToneWake(ctx: Context): ToneWake {
        val lock = runCatching {
            ctx.getSystemService(android.os.PowerManager::class.java)
                ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "rcq:notif-tone")
        }.getOrNull()
        // Not reference counted: every release below has to be able to be the
        // last one, and each dispatch holds its own lock object anyway.
        runCatching { lock?.setReferenceCounted(false) }
        runCatching { lock?.acquire(TONE_WAKE_MS) }
        return ToneWake(lock)
    }

    /** True when a tone was DISPATCHED for this notification, not when one was
     *  heard: the media stack runs on [toneThread] now and is not waited for. */
    private fun soundNotification(ctx: Context, clock: ToneClock, perKindOn: Boolean): Boolean {
        // Read outside the lock: each of these is a binder call or a preference
        // read and none of them is the sound.
        val volume = LocalStores.soundVolumeLevel()
        val chosenForShade = LocalStores.soundVolumeChosenForShade()
        val masterOn = LocalStores.soundMasterOn()
        val phoneSilent = notificationWouldBeInaudible(ctx)
        val telephonyCall = telephonyCallInProgress(ctx)
        // The throttle question and the answer to it are one atom, or two threads
        // (a wake and the live socket, exactly the pair #553 is about) each see an
        // empty gap and both play. Nothing slow is inside it any more: the stamp
        // goes down here and the tone is started on [toneThread].
        val level = synchronized(this) {
            val now = android.os.SystemClock.elapsedRealtime()
            val decided = notificationToneLevel(
                volume = volume,
                chosenForShade = chosenForShade,
                masterOn = masterOn,
                perKindOn = perKindOn,
                phoneSilent = phoneSilent,
                telephonyCall = telephonyCall,
                sinceLastTone = now - lastToneOn(clock),
            )
            // ⚠ Stamped on the DECISION, where it used to be stamped on a
            // started player. It has to be: the decision is what the next caller
            // races against, and nobody is waiting to hear how [playOnce] went.
            // The cost is one lost chime if the player fails within the window,
            // which for a local wav in our own apk means "the process is dying".
            if (decided > 0f) stampTone(now, clock)
            decided
        }
        if (level <= 0f) return false
        // applicationContext, because this outlives the caller's frame now.
        val app = ctx.applicationContext
        // ⚠⚠ Both of these are taken HERE, on the delivering thread, and not
        // inside the task: the whole point is that the task may not run for a
        // while, and a lock acquired after the device suspended is too late
        // (#1028). The stamp is the dispatch moment for the same reason.
        val wake = acquireToneWake(app)
        val dispatchedAt = android.os.SystemClock.elapsedRealtime()
        return runCatching {
            toneThread.execute {
                if (!toneStillWanted(dispatchedAt, android.os.SystemClock.elapsedRealtime())) {
                    wake.release()
                    return@execute
                }
                // playOnce releases through the listener it is handed; this
                // covers the case where nothing was ever started to listen to.
                if (!playOnce(app, R.raw.snd_message, level) { wake.release() }) wake.release()
            }
            true
        }.getOrElse {
            // The executor refused the task, so nothing will ever release it.
            wake.release()
            false
        }
    }

    /** The player the notification tone uses, and the one thing held between
     *  tones so the platform does not collect it mid-chime. */
    @Volatile private var notificationPlayer: MediaPlayer? = null

    /** Play [resId] once, now, at [vol]. True when playback started.
     *
     *  ⚠⚠ MediaPlayer and not the [pool], and this is the load-bearing choice of
     *  the whole #978 fix. Since the message channel's sound is silence, THIS is
     *  the only thing that makes a noise for an arriving message, on every
     *  install and not just the ones that lowered the slider. A push that wakes a
     *  dead process asks for the tone within milliseconds of the process
     *  starting, and a SoundPool cannot answer then: it decodes on a native
     *  thread and [start] returns 0 until the sample is READY. An earlier cut of
     *  this fix slept up to 120ms on the delivery thread polling for that, and
     *  measured three of six cold wakes needing the wait; the ones that ran out
     *  of budget fell back to a full-volume channel, which no longer exists to
     *  fall back to. MediaPlayer has no such state: prepare() on a local
     *  resource is synchronous, so when this returns true the tone is playing.
     *
     *  ⚠ No init, no pool, no cached anything. The only thing it needs is the
     *  Context the caller already has, which is what makes the notification path
     *  independent of whether anything ever opened a window in this process.
     *
     *  ⚠⚠ Called only from [toneThread], never from the thread that delivered the
     *  message, and that is where the main-thread `prepare()` went. Being
     *  single-threaded is also what makes the release-then-create below safe.
     *  [notificationPlayer] stays @Volatile all the same: MediaPlayer delivers its
     *  listeners on the looper of the thread that created it, and [toneThread] has
     *  no looper, so they arrive on the MAIN one.
     *
     *  ⚠ Released on completion and on error, and kept in a field until then: a
     *  MediaPlayer collected by the platform while it is playing stops playing.
     *  The previous one is released first, and that is free WITHIN one clock,
     *  since [MIN_GAP_MS] is longer than the tone. Across the two clocks it is
     *  not: [ToneClock.NOTICE] is independent of [ToneClock.MESSAGE] on purpose,
     *  so a device-connect notice landing inside snd_message's ~450ms cuts the
     *  message chime off mid-note. Cosmetic, in a window that needs two different
     *  kinds of event inside half a second, and the alternative is a second player
     *  to hold the overlap. */
    private fun playOnce(ctx: Context, resId: Int, vol: Float, onDone: () -> Unit = {}): Boolean {
        val uri = android.net.Uri.parse("android.resource://${ctx.packageName}/$resId")
        runCatching { notificationPlayer?.release() }
        notificationPlayer = null
        val mp = MediaPlayer()
        return runCatching {
            mp.setAudioAttributes(notificationAttributes())
            mp.setDataSource(ctx.applicationContext, uri)
            mp.setVolume(vol, vol)
            // ⚠ [onDone] is what keeps the CPU held for the ~450ms of wav and
            // not merely for the prepare() (#1028), so it belongs on every
            // terminal edge of the player and on no other.
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (notificationPlayer === it) notificationPlayer = null
                onDone()
            }
            mp.setOnErrorListener { p, _, _ ->
                runCatching { p.release() }
                if (notificationPlayer === p) notificationPlayer = null
                onDone()
                true
            }
            mp.prepare()
            mp.start()
            notificationPlayer = mp
            true
        }.getOrElse {
            runCatching { mp.release() }
            onDone()
            false
        }
    }

    /** Inbound message to a non-active, non-muted thread, with the app on
     *  screen. */
    fun message() { if (LocalStores.soundMessagesOn()) play(msg, clock = ToneClock.MESSAGE) }

    /** A contact appeared.
     *
     *  ⚠ NO POLICY HERE ANY MORE. Who is worth a sound, whether the direction
     *  is wanted, whether the thread is muted, whether this refresh moved one
     *  person or forty — all of it is [app.rcq.android.data.PresenceChime],
     *  which is pure and tested. This object's remaining job is the speaker.
     *  The split is the fix for #1030: the old code asked one question per
     *  transition and let a shared throttle silently answer for the rest. */
    fun contactOnline() = play(online, clock = ToneClock.PRESENCE)

    /** A contact disappeared. */
    fun contactOffline() = play(offline, clock = ToneClock.PRESENCE)
}
