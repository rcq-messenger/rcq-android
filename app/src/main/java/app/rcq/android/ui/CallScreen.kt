package app.rcq.android.ui

import android.content.Context
import android.os.PowerManager
import android.view.WindowManager
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.rcq.android.Session
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.rcq.android.R
import app.rcq.android.call.CallController
import app.rcq.android.call.WebRtcClient
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Full-screen 1:1 call UI (overlaid by MainActivity while a call is active).
 * Renders incoming/outgoing/connected states, WebRTC video (remote full-screen
 * + local PiP), and the in-call controls. Android has no CallKit, so this is
 * the whole call surface.
 */
@Composable
fun CallScreen(controller: CallController, session: Session? = null) {
    val state by controller.state.collectAsState()
    val info = state.info ?: return

    val micMuted by controller.micMuted.collectAsState()
    val speakerOn by controller.speakerOn.collectAsState()
    val cameraOff by controller.cameraOff.collectAsState()
    val localVideo by controller.localVideo.collectAsState()
    val remoteVideo by controller.remoteVideo.collectAsState()
    val incomingUpgrade by controller.incomingVideoUpgrade.collectAsState()
    val peerOffline by controller.peerOffline.collectAsState()
    val connectedAt by controller.connectedAtMs.collectAsState()
    // When the offer arrived while backgrounded, the full-screen IncomingCallActivity
    // owns accept/decline; don't also show in-app accept/decline for the same call.
    val incomingViaFsi by controller.incomingViaFsi.collectAsState()

    val isVideo = info.media == CallController.Media.VIDEO
    val connected = state is CallController.State.Connected
    val incoming = state is CallController.State.Incoming
    val ended = state is CallController.State.Ended

    KeepScreenOn()
    // Proximity blanking, but only when the earpiece is the output: on speaker,
    // in video, or before the call connects, holding a phone near your face is
    // not a reason to blank.
    ProximityBlanking(connected && !isVideo && !speakerOn)

    Box(Modifier.fillMaxSize().background(Color(0xFF0E0F12))) {
        // Remote video fills the screen when present; else an avatar.
        if (isVideo && remoteVideo != null && connected) {
            VideoRenderer(remoteVideo, mirror = false, modifier = Modifier.fillMaxSize())
        } else {
            // Centred in the space it actually has, not tucked under the name
            // (founder). The paddings are what the two overlays above and below
            // occupy — the name block and the control cluster — so the picture
            // lands in the middle of the gap between them rather than in the
            // middle of the screen, which reads as too low with the buttons
            // sitting underneath.
            Column(
                Modifier.fillMaxSize().padding(top = 120.dp, bottom = 240.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // The person's own picture when they have one. A call screen is
                // the one surface with nothing else on it, so the avatar is
                // exactly what it should show; the lettered disc stays as the
                // fallback for someone who never set one.
                val peer = session?.contact(info.peerUin)
                if (session != null && !peer?.avatarMediaId.isNullOrBlank()) {
                    PersonAvatar(
                        id = peer?.avatarMediaId,
                        key = peer?.avatarMediaKey,
                        status = peer?.presence ?: app.rcq.android.model.UserStatus.OFFLINE,
                        session = session,
                        size = 96.dp,
                        host = peer?.host,
                        animated = true,
                        crossIsland = peer?.host != null,
                    )
                } else {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF2A2D34)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            info.peerNickname.firstOrNull()?.uppercase() ?: "?",
                            color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // Local camera preview (PiP, top-end) once we have a local track.
        if (isVideo && localVideo != null && !cameraOff) {
            VideoRenderer(
                localVideo, mirror = true, overlay = true,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 16.dp)
                    .size(96.dp, 140.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }

        // Header: name + status.
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 64.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(info.peerNickname, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(statusText(state, connectedAt, peerOffline), color = Color(0xFFB8BCC4), fontSize = 15.sp)
        }

        // Video-upgrade prompt.
        if (incomingUpgrade) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.call_upgrade_prompt, info.peerNickname),
                    color = Color.White, fontSize = 16.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CapsuleButton(stringResource(R.string.call_upgrade_accept)) { controller.acceptVideoUpgrade() }
                    CapsuleButton(stringResource(R.string.call_upgrade_decline)) { controller.declineVideoUpgrade() }
                }
            }
        }

        // Bottom controls.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (incoming) {
                // FSI-owned (offer arrived while backgrounded): the lock-screen
                // IncomingCallActivity is the accept/decline surface — show no
                // in-app buttons so the two can't both drive the same call.
                if (!incomingViaFsi) {
                    Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                        RoundCallButton(Icons.Filled.CallEnd, stringResource(R.string.call_decline), Color(0xFFE5484D)) { controller.decline() }
                        RoundCallButton(Icons.Filled.Call, stringResource(R.string.call_accept), Color(0xFF30A46C)) { controller.accept() }
                    }
                }
            } else if (!ended) {
                // Toggles on their own row, hang-up on the next one. All five
                // used to share a single centred Row with fixed 20.dp gaps:
                // 5 × 64.dp + 4 × 20.dp is 400.dp, wider than a 360.dp phone,
                // so Hang up was pushed off the right edge and its label came
                // out stacked one letter per line (tester screenshot, 0.95).
                // fillMaxWidth + SpaceEvenly means the row now divides whatever
                // width there is instead of demanding a fixed total.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundCallButton(
                        if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        stringResource(R.string.call_mute),
                        if (micMuted) Color(0xFF4A4D55) else Color(0xFF2A2D34),
                    ) { controller.toggleMic() }
                    RoundCallButton(
                        Icons.Filled.VolumeUp, stringResource(R.string.call_speaker),
                        if (speakerOn) Color(0xFF3B6FE0) else Color(0xFF2A2D34),
                    ) { controller.toggleSpeaker() }
                    if (isVideo) {
                        RoundCallButton(
                            if (cameraOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                            stringResource(R.string.call_camera),
                            if (cameraOff) Color(0xFF4A4D55) else Color(0xFF2A2D34),
                        ) { controller.toggleCamera() }
                        RoundCallButton(Icons.Filled.Cameraswitch, stringResource(R.string.call_flip), Color(0xFF2A2D34)) { controller.flipCamera() }
                    } else if (connected) {
                        RoundCallButton(Icons.Filled.Videocam, stringResource(R.string.call_video_on), Color(0xFF2A2D34)) { controller.requestVideoUpgrade() }
                    }
                }
                Spacer(Modifier.height(24.dp))
                RoundCallButton(
                    Icons.Filled.CallEnd, stringResource(R.string.call_hangup),
                    Color(0xFFE5484D), size = 76.dp,
                ) { controller.hangUp() }
            }
        }
    }
}

/** Keep the display awake for as long as the caller is on screen. Android has
 *  no CallKit, so nothing else does it: the system dimmed and locked mid-call
 *  on the way to its normal timeout, which read as "the screen does not come on
 *  during a call" (tester, Android 12). IncomingCallActivity already sets the
 *  same flag for the full-screen-intent path; the in-app surfaces never did. */
@Composable
internal fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context.findFragmentActivity())?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** Blank the panel while something is close to the earpiece, for as long as
 *  [active]. The lock turns the display AND the touchscreen off, which is the
 *  actual point — a cheek on an unblanked screen was hanging up calls and
 *  toggling the mic. Shared by the 1:1 call and the audio room: both are a
 *  conversation held against an ear whenever the loudspeaker is off. */
@Composable
internal fun ProximityBlanking(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        var lock: PowerManager.WakeLock? = null
        if (active) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                lock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "rcq:call-proximity")
                    .also { runCatching { it.acquire(60 * 60 * 1000L) } }
            }
        }
        onDispose { lock?.let { if (it.isHeld) runCatching { it.release() } } }
    }
}

@Composable
private fun statusText(
    state: CallController.State,
    connectedAtMs: Long,
    peerOffline: Boolean,
): String = when (state) {
    // "Calling…" next to silence reads as a broken app. When the island tells
    // us the other side has no live connection and the offer left as a push,
    // the line says that instead — the silence then has a reason (#463).
    is CallController.State.Outgoing ->
        if (peerOffline) stringResource(R.string.call_peer_offline)
        else stringResource(R.string.call_calling)
    is CallController.State.Incoming ->
        if (state.info.media == CallController.Media.VIDEO) stringResource(R.string.call_incoming_video)
        else stringResource(R.string.call_incoming)
    is CallController.State.Connected -> {
        // connectedAtMs stays 0 until ICE actually connects — show "Connecting…"
        // (not a fake 0:00 timer) so silence-with-a-ticking-clock can't happen.
        if (connectedAtMs <= 0) {
            stringResource(R.string.call_connecting)
        } else {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(connectedAtMs) {
                while (true) { now = System.currentTimeMillis(); delay(1000) }
            }
            val secs = ((now - connectedAtMs) / 1000).coerceAtLeast(0)
            "%d:%02d".format(secs / 60, secs % 60)
        }
    }
    is CallController.State.Ended -> stringResource(R.string.call_out_ended)
    CallController.State.Idle -> ""
}

@Composable
private fun RoundCallButton(
    icon: ImageVector,
    label: String,
    bg: Color,
    size: Dp = 64.dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(bg)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.44f))
        }
        Spacer(Modifier.height(6.dp))
        // One line, always. The label wrapped per-character once the row ran
        // out of width, which is how "Завершить" ended up reading vertically.
        Text(label, color = Color(0xFFB8BCC4), fontSize = 12.sp, maxLines = 1)
    }
}

/** WebRTC video surface bound to [track]. Inits with the shared EGL context;
 *  removes its sink and releases on dispose.
 *
 *  [overlay] marks the surface that has to sit ON TOP of another one. A
 *  SurfaceView does not draw inside the window: it punches a hole and the
 *  system composites its own surface there, and two of them are stacked in
 *  CREATION order, not in layout order. The remote track always arrives after
 *  the local camera, so the full-screen remote surface was composited over the
 *  PiP: the preview appeared, then vanished the instant the other side's
 *  picture came up (#456). Toggling the camera brought it back only because
 *  that drops and rebuilds this view, making it the newest surface again. */
@Composable
private fun VideoRenderer(track: VideoTrack?, mirror: Boolean, modifier: Modifier, overlay: Boolean = false) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            // Before init/attach: the flag is read when the surface is created.
            if (overlay) setZOrderMediaOverlay(true)
            init(WebRtcClient.sharedEglContext(), null)
            setMirror(mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
        }
    }
    // Declared first so it disposes LAST (after the sink is removed below).
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { renderer.release() }
    }
    androidx.compose.runtime.DisposableEffect(track) {
        track?.addSink(renderer)
        onDispose { track?.removeSink(renderer) }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}
