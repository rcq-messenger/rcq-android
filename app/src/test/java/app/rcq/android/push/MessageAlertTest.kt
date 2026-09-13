package app.rcq.android.push

import app.rcq.android.media.SoundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who makes the noise for an arriving message, and how loud (#978, and #545
 * before it).
 *
 * Since v3 the answer is always RCQ: the message channel's own sound is silence,
 * so the notification's tone is the same tone the open app plays, times the
 * number on the volume slider. Which leaves three decisions, all of them pure
 * functions on purpose, because every rule below is a thing that went wrong once
 * and would go wrong again silently:
 *
 *  - [Push.mayMoveMessageChannel]: whether an install still on rcq_messages_v2
 *    can be moved to v3 at all, which is the only place the migration's cost is
 *    paid.
 *  - [Push.ownsToneOn]: whether this person wants a sound on this channel, which
 *    is the whole of what the shade side has to ask now that there is one
 *    channel and not two.
 *  - [SoundService.notificationToneLevel]: at what volume, or not at all.
 *
 * Pinned here rather than on a phone: reproducing "a wake arrives while the
 * channel is set to Silent" by hand is a five-minute ritual per case, and there
 * are more than twenty of them.
 */
class MessageAlertTest {

    private val pkg = "app.rcq.android"

    // NotificationManager's values, written out so this test needs nothing from
    // android.jar at run time: importance NONE 0, MIN 1, LOW 2, DEFAULT 3,
    // HIGH 4; VISIBILITY_NO_OVERRIDE -1000, VISIBILITY_SECRET -1,
    // VISIBILITY_PRIVATE 0.
    private val noOverride = -1000

    private fun facts(
        importance: Int = 4,
        sound: String? = pkg,
        visibility: Int = noOverride,
        bypassesDnd: Boolean = false,
    ) = Push.ChannelFacts(importance, sound, visibility, bypassesDnd)

    // ── the migration: who may be moved off rcq_messages_v2 ──────────────

    /** The ordinary install: v2 exactly as the old ensureChannels made it. */
    @Test fun anUntouchedOldChannelIsMoved() {
        assertTrue(Push.mayMoveMessageChannel(facts(), pkg))
    }

    /** ⚠⚠ Privacy, and the hardest fact in the fix: an app cannot set a
     *  channel's lock-screen visibility (measured, a channel created asking for
     *  VISIBILITY_SECRET reads back -1000). So somebody who set "Don't show
     *  notifications at all" for RCQ messages cannot have that reproduced on a
     *  new channel, and moving them would put message text back on the lock
     *  screen of an app that ships a panic PIN. Asking for less exposure must
     *  never produce more. */
    @Test fun aRedactedLockScreenIsNeverMoved() {
        assertFalse(Push.mayMoveMessageChannel(facts(visibility = -1), pkg))
        assertFalse(Push.mayMoveMessageChannel(facts(visibility = 0), pkg))
    }

    /** setBypassDnd needs notification-policy access the app does not have, so a
     *  channel they allowed through Do Not Disturb would quietly stop coming
     *  through.
     *
     *  ⚠ And since [Push.ownsToneOn] stopped sounding under any filter, this
     *  refusal is the only place a DND override still produces a noise at all:
     *  the install stays on v2 and Android keeps playing the channel's sound,
     *  which is exempt from the mute an app's own tone is not. */
    @Test fun aDndOverrideIsNeverMoved() {
        assertFalse(Push.mayMoveMessageChannel(facts(bypassesDnd = true), pkg))
    }

    /** A sound they picked, or "None". Neither can be carried across, and
     *  neither should be: whichever they chose, the answer to what a message
     *  sounds like stopped being ours. */
    @Test fun aSoundOfTheirOwnIsNeverMoved() {
        assertFalse(Push.mayMoveMessageChannel(facts(sound = "media"), pkg))
        assertFalse(Push.mayMoveMessageChannel(facts(sound = "settings"), pkg))
        assertFalse(Push.mayMoveMessageChannel(facts(sound = null), pkg))
    }

    /** A channel they BLOCKED. Nothing for #978 to fix there (it makes no sound
     *  and shows nothing), and every reason not to risk a new channel arriving
     *  unblocked. */
    @Test fun aBlockedChannelIsNeverMoved() {
        assertFalse(Push.mayMoveMessageChannel(facts(importance = 0), pkg))
    }

    /** Every other importance moves and is carried over, which is what makes
     *  "Silent" and "Pop on screen: off" survive. The reviewers of an earlier
     *  round found the opposite: a rule that demanded IMPORTANCE_HIGH sent
     *  everyone who had turned "Pop on screen" off silently back to the
     *  full-volume shade. */
    @Test fun silentAndNoBannerAreMovedAndCarried() {
        assertTrue(Push.mayMoveMessageChannel(facts(importance = 1), pkg))
        assertTrue(Push.mayMoveMessageChannel(facts(importance = 2), pkg))
        assertTrue(Push.mayMoveMessageChannel(facts(importance = 3), pkg))
    }

    // ── the shade: is the tone ours on this channel ──────────────────────

    private fun owns(
        channelId: String = Push.CHANNEL_MESSAGES,
        f: Push.ChannelFacts = facts(),
        dndOn: Boolean = false,
    ) = Push.ownsToneOn(channelId, f, pkg, dndOn)

    /** The ordinary install after the move: v3, our silent sound, importance as
     *  we created it. */
    @Test fun theToneIsOursOnTheNewChannel() {
        assertTrue(owns())
    }

    /** An install [Push.mayMoveMessageChannel] refused still posts on v2, whose
     *  sound Android plays. Ours on top would be two chimes for one message. */
    @Test fun theToneIsNeverOursOnTheOldChannel() {
        assertFalse(owns(channelId = "rcq_messages_v2"))
        assertFalse(owns(channelId = "rcq_messages_v2", f = facts(importance = 4)))
    }

    /** ⚠ IMPORTANCE_DEFAULT keeps the tone. Turning Android's per-channel
     *  "Silent" back off lands on 3, with "Pop on screen" as the separate switch
     *  that reaches 4, and that person changed nothing about sound. The missing
     *  banner is simply what their own channel's importance means; an earlier cut
     *  arranged it with setSilent instead and threw their vibration away with
     *  it. */
    @Test fun defaultImportanceKeepsTheTone() {
        assertTrue(owns(f = facts(importance = 3)))
    }

    /** Below DEFAULT the channel makes no sound at all, so Android is already
     *  honouring a silence somebody asked for and there is nothing to scale.
     *  Blocked outright is the same answer for a blunter reason. */
    @Test fun aSilencedOrBlockedChannelIsNotOursToSpeakFor() {
        assertFalse(owns(f = facts(importance = 2)))
        assertFalse(owns(f = facts(importance = 1)))
        assertFalse(owns(f = facts(importance = 0)))
    }

    /** They picked a ringtone of their own, or set the sound to None, on the
     *  channel we post on. Android plays whatever that is now, and our tone must
     *  not arrive on top of it. */
    @Test fun aSoundOfTheirOwnHandsTheToneBack() {
        assertFalse(owns(f = facts(sound = "media")))
        assertFalse(owns(f = facts(sound = "settings")))
        assertFalse(owns(f = facts(sound = null)))
    }

    /** ⚠⚠ Do Not Disturb: ANY filter, and the channel's override does not change
     *  the answer. This test asserted the opposite for four rounds, on a reason
     *  that was false (see [Push.ownsToneOn], which now carries the whole
     *  derivation). Two things are true instead: a CATEGORY_MESSAGE notification
     *  is judged by the DND POLICY rather than by the channel's bypass bit, and
     *  an app cannot read that policy without ACCESS_NOTIFICATION_POLICY, so
     *  under a priority filter we cannot know what Android would have done; and
     *  our tone during DND is app audio, which the platform restricts for every
     *  package but its own whatever the channel says.
     *
     *  So this is a DISCLOSED loss, not a rule that gets something right:
     *  somebody with "Messages: Anyone" or a channel override heard the message
     *  before v3 and hears nothing now. The Sounds footer says so in all seven
     *  locales. The one population spared is the one
     *  [Push.mayMoveMessageChannel] refuses to move. */
    @Test fun anyDoNotDisturbFilterTakesTheToneAway() {
        assertFalse(owns(dndOn = true))
        assertFalse(owns(dndOn = true, f = facts(bypassesDnd = true)))
        // And with no filter on, the override is simply not a factor either way.
        assertTrue(owns(dndOn = false, f = facts(bypassesDnd = true)))
    }

    /** Lock screen and dot are deliberately NOT read here, and that is the whole
     *  point of one channel instead of two: the notification posts on the
     *  person's own channel, so Android honours those without this file having an
     *  opinion. The twin design had to compare them and grew a new field to
     *  compare every review round. */
    @Test fun appearanceSettingsAreNotOurBusinessAnyMore() {
        assertTrue(owns(f = facts(visibility = -1)))
        assertTrue(owns(f = facts(visibility = 0)))
    }

    // ── the level: how loud, or not at all ───────────────────────────────

    private fun level(
        volume: Float,
        chosenForShade: Boolean = true,
        masterOn: Boolean = true,
        perKindOn: Boolean = true,
        phoneSilent: Boolean = false,
        telephonyCall: Boolean = false,
        sinceLastTone: Long = 60_000L,
    ) = SoundService.notificationToneLevel(
        volume, chosenForShade, masterOn, perKindOn, phoneSilent, telephonyCall, sinceLastTone,
    )

    @Test fun theSliderIsTheNotificationsLevel() {
        assertEquals(1f, level(1f), 0f)
        assertEquals(0.5f, level(0.5f), 0f)
        assertEquals(0.05f, level(0.05f), 0f)
    }

    /** #978 in its purest form: the slider at zero used to mean the shade shouted
     *  at the phone's full notification level. Zero is a level, so it is silence,
     *  and the title and description say so in all seven locales. */
    @Test fun zeroIsSilenceAndNotAFullVolumeShade() {
        assertEquals(0f, level(0f), 0f)
        assertEquals(0f, level(-0.1f), 0f)
    }

    @Test fun nothingCanAskForMoreThanTheStreamsLevel() {
        assertEquals(1f, level(1.5f), 0f)
    }

    /** ⚠⚠ The migration, and it defers EVERY level it inherits rather than only
     *  zero. Under the old label ("In-app tone volume", with a description that
     *  said the loudness of a notification belongs to Android) every stored
     *  number answered a different question, so reading them under the new one
     *  puts words in people's mouths: 15% would become "my phone barely announces
     *  messages" and 0 would become "my phone never does". Until the slider is
     *  MOVED, an inherited level governs the open app only and the notification
     *  keeps the phone's level, which is exactly what both did before the
     *  upgrade. */
    @Test fun anInheritedLevelDoesNotReachTheNotification() {
        assertEquals(1f, level(0f, chosenForShade = false), 0f)
        assertEquals(1f, level(0.05f, chosenForShade = false), 0f)
        assertEquals(1f, level(0.5f, chosenForShade = false), 0f)
        assertEquals(1f, level(1f, chosenForShade = false), 0f)
    }

    /** RCQ's own switches. The caller has hushed the notification for these
     *  already (Push.post's settingsQuiet, showMissedCall's masterOff); this is
     *  the same answer from the other side so the two cannot drift. */
    @Test fun rcqsOwnSoundSwitchesProduceNoTone() {
        assertEquals(0f, level(0.4f, masterOn = false), 0f)
        assertEquals(0f, level(0.4f, perKindOn = false), 0f)
        // Including for an inherited level, which must not become a way past
        // them.
        assertEquals(0f, level(0.4f, masterOn = false, chosenForShade = false), 0f)
    }

    /** Silence the phone is imposing: a notification stream nothing can be heard
     *  on (silent or vibrate ringer, total-silence Do Not Disturb), or a
     *  TELEPHONY call, in progress or ringing. Do Not Disturb is not a parameter
     *  here at all: [Push.ownsToneOn] answers it for any filter and this is never
     *  reached under one.
     *
     *  ⚠⚠ "Telephony" and not "any call", which is the narrowing this round made
     *  and the reason the parameter was renamed. The first cut read
     *  AudioManager.mode for MODE_IN_COMMUNICATION too, which is any app's VoIP
     *  call and any mode some app forgot to put back, so messages could go silent
     *  for the length of somebody else's call or indefinitely. Android's own rule
     *  is the telephony call state, and what it now costs instead is written out
     *  at SoundService.telephonyCallInProgress.
     *
     *  ⚠ A phone in VIBRATE mode still buzzes for the message, and not by
     *  anything this function does: the channel carries a silent sound precisely
     *  so Android's own sound-to-vibration fallback keeps firing. See
     *  Push.createMessageChannel. */
    @Test fun silenceThePhoneImposesIsNotOursToFillIn() {
        assertEquals(0f, level(0.4f, phoneSilent = true), 0f)
        assertEquals(0f, level(0.4f, telephonyCall = true), 0f)
        assertEquals(0f, level(0.4f, phoneSilent = true, chosenForShade = false), 0f)
    }

    /** Four messages in four different chats are four first-alerts as far as
     *  Push's per-id burst window is concerned. Without this they were four
     *  overlapping copies of one chime. */
    @Test fun aBurstIsOneChime() {
        assertEquals(0f, level(0.4f, sinceLastTone = 0L), 0f)
        assertEquals(0f, level(0.4f, sinceLastTone = 1_199L), 0f)
        assertEquals(0.4f, level(0.4f, sinceLastTone = 1_200L), 0f)
        // And the deferred population is throttled the same way, at the phone's
        // level rather than at the slider's.
        assertEquals(0f, level(0.4f, chosenForShade = false, sinceLastTone = 0L), 0f)
    }

    /** The clock is passed in, and which clock it was measured on is the caller's
     *  choice: a message and a missed call share one, a "new device connected"
     *  notice and a reply to an abuse report have their own. Without that split a
     *  security notice landing a second after a message was swallowed as a
     *  duplicate of it. The parameter name is the contract; this pins the only
     *  behaviour that can express it here. */
    @Test fun aClockFromAnotherKindNeverSilencesThisOne() {
        assertEquals(0.4f, level(0.4f, sinceLastTone = 3_600_000L), 0f)
    }
}
