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
        showOverLockscreen()
        if (bind(IncomingCallStore.pending) == null) { finish(); return }
        ringer = Ringer(this).also { it.startIncoming() }

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

    /** A second call replaced the parked offer while this was showing (singleTask
     *  re-delivery) — resync the UI + cancel-match + ring to the new call. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (bind(IncomingCallStore.pending) == null) { dismiss(); return }
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

    private fun onDecline() {
        IncomingCallStore.clearIf(callId)
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

    override fun onDestroy() {
        window.decorView.removeCallbacks(watchdog)
        if (receiverRegistered) runCatching { unregisterReceiver(cancelReceiver) }
        ringer?.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "app.rcq.android.CALL_CANCELLED"
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
                text = nickname.ifBlank { "RCQ" },
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringRes(if (video) R.string.call_incoming_video else R.string.call_incoming),
                color = Color(0xFFB0B8C0),
                fontSize = 15.sp,
            )
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
