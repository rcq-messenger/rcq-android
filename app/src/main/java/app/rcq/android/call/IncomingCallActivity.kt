package app.rcq.android.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.MainActivity
import app.rcq.android.R
import app.rcq.android.push.Push

/**
 * Full-screen incoming-call UI raised by a UnifiedPush call wake. Shows over
 * the lock screen and rings. Accept hands off to [MainActivity] (which runs the
 * WebRTC answer through the live Session once its WS connects); Decline
 * dismisses locally — the caller sees no-answer after their ring timeout (a
 * push-side decline-signal is a follow-up).
 */
class IncomingCallActivity : ComponentActivity() {
    private var ringer: Ringer? = null
    private var callId: String = ""
    private val current = androidx.compose.runtime.mutableStateOf<IncomingCallStore.Pending?>(null)
    private var receiverRegistered = false
    // Missed-call watchdog held in a field so it's cancelled on teardown rather
    // than pinning the destroyed Activity on the main-thread queue for 60s.
    private val watchdog = Runnable { if (!isFinishing) dismiss() }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(EXTRA_CALL_ID) == callId) dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (answerFromNotification(intent)) return
        showOverLockscreen()
        if (bind(IncomingCallStore.pending) == null) { finish(); return }
        // ⚠ Take the notification down BEFORE ringing, or the phone rings twice.
        //
        // The ring notification goes out on the audible channel whenever the
        // full-screen intent cannot take over the screen (phone unlocked and in
        // hand), and Android loops a CallStyle ringtone for as long as that
        // notification is posted. Tapping it opens this Activity, which rings on
        // its own — so from the tap onwards the system ringtone and ours played
        // over each other, reported as "два рингтона накладываются друг на друга
        // и играют вместе". Cancelling stops the system one; this screen is now
        // the single thing making noise, and [onStop] puts the notification back
        // if the person leaves without answering.
        Push.cancelCallNotification(this)
        // The ring itself starts in onStart, which runs right after this and
        // again on every return to the screen — one place, so coming back from
        // the home button cannot leave a silent incoming call.
        ringer = Ringer(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, IntentFilter(ACTION_CANCEL), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(cancelReceiver, IntentFilter(ACTION_CANCEL))
        }
        receiverRegistered = true
        window.decorView.postDelayed(watchdog, 60_000)

        setContent {
            current.value?.let { p ->
                IncomingCallUi(
                    nickname = p.nickname,
                    video = p.media == "video",
                    onAccept = ::onAccept,
                    onDecline = ::onDecline,
                )
            }
        }
    }

    /** Answer tapped on the notification rather than on this screen. There is no UI
     *  to draw: take the call and hand straight off to MainActivity. Returns true
     *  when the intent was that, so onCreate stops (this Activity is finishing).
     *
     *  Bound to the call id the button was built for, so a button left over from an
     *  earlier ring cannot answer the call that replaced it. */
    private fun answerFromNotification(i: Intent?): Boolean {
        if (i?.action != ACTION_ANSWER) return false
        val wanted = i.getStringExtra(EXTRA_CALL_ID)
        val p = IncomingCallStore.pending
        if (p == null || (wanted != null && p.callId != wanted)) {
            Push.cancelCallNotification(this)
            finish()
            return true
        }
        bind(p)
        onAccept()
        return true
    }

    /** A second call replaced the parked offer while this was showing (singleTask
     *  re-delivery) — resync the UI + cancel-match + ring to the new call. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (answerFromNotification(intent)) return
        if (bind(IncomingCallStore.pending) == null) { dismiss(); return }
        // Re-arm the lock-screen + wake flags. They are a property of a LAUNCH,
        // not of the Activity: the framework spends the turn-screen-on
        // permission when the activity is first resumed, so an instance that is
        // still alive from the previous call (singleTask re-delivers here rather
        // than creating a new one) would show the second call on a dark screen.
        showOverLockscreen()
        ringer?.stop()
        ringer = Ringer(this).also { it.startIncoming() }
        window.decorView.removeCallbacks(watchdog)
        window.decorView.postDelayed(watchdog, 60_000)
    }

    private fun bind(p: IncomingCallStore.Pending?): IncomingCallStore.Pending? {
        callId = p?.callId ?: ""
        current.value = p
        return p
    }

    private fun showOverLockscreen() {
        // Keep the screen lit on every API level so it doesn't sleep mid-ring;
        // turnScreenOn/showWhenLocked only wake + show over the keyguard.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    /** Hand the accepted call to MainActivity: it feeds the offer into the live
     *  CallController and runs accept() once the WS is connected. */
    private fun onAccept() {
        IncomingCallStore.accept(callId)
        Push.cancelCallNotification(this)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        ringer?.stop()
        finish()
    }

    /** Declining goes through the same broadcast the notification's Decline button
     *  sends, so the two buttons cannot behave differently: a live CallController
     *  tells the caller they were declined (rather than leaving them on "Calling…"
     *  until their own 60s timeout), and the manifest receiver tears the ring down
     *  even when no controller exists. [dismiss] still runs locally because this
     *  screen must close whether or not anything answered the broadcast. */
    private fun onDecline() {
        val id = callId
        runCatching {
            sendBroadcast(
                Intent(Push.ACTION_DECLINE_CALL)
                    .setPackage(packageName)
                    .putExtra(Push.EXTRA_CALL_ID, id),
            )
        }
        dismiss()
    }

    /** Local teardown (decline / cancel / missed): stop ringing + remove the
     *  notification + finish. */
    private fun dismiss() {
        IncomingCallStore.clearIf(callId)
        Push.cancelCallNotification(this)
        ringer?.stop()
        finish()
    }

    /** Left without answering — home button, another app, the screen locking
     *  again. The ringing has to follow the person rather than stay on a screen
     *  they are no longer looking at, so the notification goes back up and this
     *  screen goes quiet. Reported as a call that could not be answered after
     *  putting the app aside mid-ring.
     *
     *  Only while the offer is still parked: accept and decline both clear it,
     *  and either of those reaching onStop first would otherwise re-post a
     *  notification for a call that is already over. */
    override fun onStop() {
        // Cleared before super: the process-lifecycle ON_STOP is dispatched from
        // inside it, and CallController must already see the surface as down.
        IncomingCallStore.fsiSurfaceActive = false
        super.onStop()
        if (isFinishing) return
        val p = IncomingCallStore.pending ?: return
        if (p.callId != callId) return
        // ⚠ The ring stops on the same delay as the notification below, and for
        // the same reason: the keyguard bounces this activity through stop and
        // start, and stopping here made the ring cut out and start over from the
        // first note the moment the phone was unlocked (report #680). A real
        // departure outlives the delay and the notification takes the ring over;
        // a bounce cancels this in onStart before a note is lost.
        // Deferred, not immediate: a MIUI keyguard dismiss bounces this activity
        // stop/start, and posting here meant a moment of the AUDIBLE call channel
        // (the screen is unlocked by now) that onStart cancelled milliseconds
        // later — the post/cancel race left the system ringtone playing next to
        // the Ringer (report #638). A real departure survives the delay; the
        // bounce cancels the runnable in onStart before it fires.
        val appCtx = applicationContext
        // The ringer, not the field: a Runnable parked in a STATIC field must
        // not hold this Activity. It is also stopped before the id guard below,
        // because a stop that a guard can skip is a ringtone that goes on
        // playing for a call the screen no longer belongs to.
        val leavingRinger = ringer
        val repost = Runnable {
            pendingRepost = null
            leavingRinger?.stop()
            if (IncomingCallStore.pending?.callId != p.callId) return@Runnable
            Push.showIncomingCall(
                appCtx,
                com.google.gson.JsonObject().apply {
                    addProperty("call_id", p.callId)
                    addProperty("from_uin", p.fromUin)
                    addProperty("sdp", p.sdp)
                    addProperty("media", p.media)
                    addProperty("nickname", p.nickname)
                },
            )
        }
        pendingRepost = repost
        repostHandler.postDelayed(repost, REPOST_DELAY_MS)
    }

    /** Coming back to the screen takes the notification down again, so the two
     *  never ring together. */
    override fun onStart() {
        // Set before super: the process-lifecycle ON_START is dispatched from
        // inside it, and CallController's foreground handoff must already see
        // that this surface owns the ring (report #638).
        IncomingCallStore.fsiSurfaceActive = true
        super.onStart()
        pendingRepost?.let { repostHandler.removeCallbacks(it) }
        pendingRepost = null
        val p = IncomingCallStore.pending ?: return
        if (p.callId != callId || isFinishing) return
        Push.cancelCallNotification(this)
        if (ringer == null) ringer = Ringer(this)
        ringer?.startIncoming()
    }

    override fun onDestroy() {
        IncomingCallStore.fsiSurfaceActive = false
        if (isFinishing) {
            // Answered, declined, or dismissed — a parked re-post would revive a
            // notification for a call that is already over. A non-finishing
            // destroy keeps it: the ring must still follow the person out.
            pendingRepost?.let { repostHandler.removeCallbacks(it) }
            pendingRepost = null
        }
        window.decorView.removeCallbacks(watchdog)
        if (receiverRegistered) runCatching { unregisterReceiver(cancelReceiver) }
        ringer?.stop()
        super.onDestroy()
    }

    companion object {
        /** Survives the MIUI stop/start bounce even if the system recreates the
         *  activity between the two: any instance's onStart cancels it. */
        private val repostHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingRepost: Runnable? = null
        private const val REPOST_DELAY_MS = 800L

        const val ACTION_CANCEL = "app.rcq.android.CALL_CANCELLED"
        /** Answer pressed on the ringing notification (as opposed to on this
         *  screen, which may never have been shown at all). */
        const val ACTION_ANSWER = "app.rcq.android.CALL_ANSWER"
        const val EXTRA_CALL_ID = "call_id"
    }
}

@Composable
private fun IncomingCallUi(
    nickname: String,
    video: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val bg = Color(0xFF101418)
    // §5d: a cross-island wake rings before the envelope has named the caller,
    // so the name slot can legitimately hold the neutral "Incoming call"
    // (Push.showSealedIncomingCall) or, on an older row, nothing at all. Both
    // render as the neutral label — never the hardcoded "RCQ" this used to
    // show, which read as the app itself calling — the subtitle is then dropped
    // rather than printed twice, and the disc shows "?" instead of the first
    // letter of a label that is not a name.
    val neutral = stringRes(R.string.call_incoming)
    val unnamed = nickname.isBlank() || nickname == neutral
    Column(
        modifier = Modifier.fillMaxSize().background(bg).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (unnamed) neutral else nickname,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (!unnamed || video) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringRes(if (video) R.string.call_incoming_video else R.string.call_incoming),
                    color = Color(0xFFB0B8C0),
                    fontSize = 15.sp,
                )
            }
        }
        // The caller, in the middle of the screen, the way the in-app call
        // surface shows them. This screen had a name at the top, two buttons at
        // the bottom and nothing in between. The lettered disc only: this
        // Activity is raised from a push on a locked phone, where the account's
        // media store may not be open, so it must not depend on one.
        // Same disc, same size, same height on the screen as the in-app call
        // surface: this Activity and CallScreen are two renderings of one call,
        // and the picture must not jump when the ring is answered. The bottom
        // padding is what buys that — this screen's buttons are shorter than
        // the in-call cluster, so an untouched centre would sit lower.
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(bottom = 96.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF2A2D34)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (unnamed) "?" else nickname.trim().firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundCallButton(Color(0xFFE0384E), Icons.Filled.CallEnd, R.string.call_decline, onDecline)
            RoundCallButton(Color(0xFF34C759), Icons.Filled.Call, R.string.call_accept, onAccept)
        }
    }
}

@Composable
private fun RoundCallButton(color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, labelRes: Int, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = stringRes(labelRes), tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(stringRes(labelRes), color = Color(0xFFB0B8C0), fontSize = 13.sp)
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
