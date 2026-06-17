package app.rcq.android.ui

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
fun CallScreen(controller: CallController) {
    val state by controller.state.collectAsState()
    val info = state.info ?: return

    val micMuted by controller.micMuted.collectAsState()
    val speakerOn by controller.speakerOn.collectAsState()
    val cameraOff by controller.cameraOff.collectAsState()
    val localVideo by controller.localVideo.collectAsState()
    val remoteVideo by controller.remoteVideo.collectAsState()
    val incomingUpgrade by controller.incomingVideoUpgrade.collectAsState()
    val connectedAt by controller.connectedAtMs.collectAsState()
    // When the offer arrived while backgrounded, the full-screen IncomingCallActivity
    // owns accept/decline; don't also show in-app accept/decline for the same call.
    val incomingViaFsi by controller.incomingViaFsi.collectAsState()

    val isVideo = info.media == CallController.Media.VIDEO
    val connected = state is CallController.State.Connected
    val incoming = state is CallController.State.Incoming
    val ended = state is CallController.State.Ended

    Box(Modifier.fillMaxSize().background(Color(0xFF0E0F12))) {
        // Remote video fills the screen when present; else an avatar.
        if (isVideo && remoteVideo != null && connected) {
            VideoRenderer(remoteVideo, mirror = false, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(top = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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

        // Local camera preview (PiP, top-end) once we have a local track.
        if (isVideo && localVideo != null && !cameraOff) {
            VideoRenderer(
                localVideo, mirror = true,
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
            Text(statusText(state, connectedAt), color = Color(0xFFB8BCC4), fontSize = 15.sp)
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
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    RoundCallButton(Icons.Filled.CallEnd, stringResource(R.string.call_hangup), Color(0xFFE5484D)) { controller.hangUp() }
                }
            }
        }
    }
}

@Composable
private fun statusText(state: CallController.State, connectedAtMs: Long): String = when (state) {
    is CallController.State.Outgoing -> stringResource(R.string.call_calling)
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
private fun RoundCallButton(icon: ImageVector, label: String, bg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(bg)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color(0xFFB8BCC4), fontSize = 12.sp)
    }
}

/** WebRTC video surface bound to [track]. Inits with the shared EGL context;
 *  removes its sink and releases on dispose. */
@Composable
private fun VideoRenderer(track: VideoTrack?, mirror: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
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
