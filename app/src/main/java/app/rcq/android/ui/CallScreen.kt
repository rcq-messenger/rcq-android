package app.rcq.android.ui

import android.content.Context
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.mutableStateOf
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
    val relayDead by controller.relayDead.collectAsState()
    val connectedAt by controller.connectedAtMs.collectAsState()
    // When the offer arrived while backgrounded, the full-screen IncomingCallActivity
    // owns accept/decline; don't also show in-app accept/decline for the same call.
    val incomingViaFsi by controller.incomingViaFsi.collectAsState()

    val isVideo = info.media == CallController.Media.VIDEO
    val connected = state is CallController.State.Connected
    val incoming = state is CallController.State.Incoming
    val ended = state is CallController.State.Ended

    // ⚠ Chrome hides itself on a CONNECTED VIDEO call and comes back on a tap
    // (#531): the control cluster and the name block cover about a third of the
    // picture, and in a video call the picture is the point. Audio keeps its
    // controls on screen always — there is nothing underneath them to reveal,
    // and a hidden Hang up would be a cruelty.
    val canAutoHide = connected && isVideo
    var chromeVisible by remember { mutableStateOf(true) }
    // Any change of footing (call connects, video starts or stops, the far side
    // asks to turn video on) brings the controls back and restarts the clock.
    LaunchedEffect(canAutoHide, incomingUpgrade, relayDead) { chromeVisible = true }
    LaunchedEffect(chromeVisible, canAutoHide) {
        if (canAutoHide && chromeVisible) {
            delay(4_000)
            chromeVisible = false
        }
    }
    val showChrome = !canAutoHide || chromeVisible

    // Back puts a live call aside instead of doing nothing. Only once it is
    // connected: while it is still ringing this screen IS the answer surface.
    androidx.activity.compose.BackHandler(enabled = connected) { controller.minimize() }

    KeepScreenOn()
    // Proximity blanking, but only when the earpiece is the output: on speaker,
    // in video, or before the call connects, holding a phone near your face is
    // not a reason to blank.
    ProximityBlanking(connected && !isVideo && !speakerOn)

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0E0F12)).let {
            // A tap anywhere brings the chrome back (and puts it away again).
            // No ripple and no indication: this is the video, not a button.
            if (!canAutoHide) it else it.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { chromeVisible = !chromeVisible }
        },
    ) {
        // Remote video fills the screen when present; else an avatar.
        if (isVideo && remoteVideo != null && connected) {
            VideoRenderer(remoteVideo, mirror = false, modifier = Modifier.fillMaxSize())
        } else if (!relayDead) {
            // The relay-dead prompt occupies this same middle band. Two things
            // centred in one space overlap, and a call screen should be showing
            // one thing at a time anyway: while the user is being asked to make
            // a decision, the decision is the screen.
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
                // The lettered disc is the BASE layer, always drawn; the
                // picture covers it once the blob has been fetched and
                // decrypted. Drawn as an either/or, a peer whose picture was
                // still loading (or whose island we cannot fetch from at all)
                // got a 96dp hole where their face should be — PersonAvatar
                // deliberately renders nothing while it has no image and no
                // flower to fall back on.
                // ⚠ One Box, so the two LAYER instead of stacking: in a Column
                // they would sit one above the other and the screen would show
                // a letter and a picture, not a picture over a letter.
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF2A2D34)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            info.peerNickname.firstOrNull()?.uppercase() ?: "?",
                            color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
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
                        // No flower in a call: it reports "online" to the one
                        // person who can hear them.
                        showStatus = false,
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

        // Put the call aside and use the app. Only on a connected call, and
        // deliberately in the corner rather than near End: the two must not be
        // easy to confuse.
        if (connected && showChrome) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.call_minimize),
                tint = Color(0xFFB8BCC4),
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 60.dp)
                    .size(34.dp)
                    .clickable { controller.minimize() },
            )
        }

        // Header: name + status.
        if (showChrome) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 64.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(info.peerNickname, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (relayDead) stringResource(R.string.call_relay_dead)
                else statusText(state, connectedAt, peerOffline),
                color = if (relayDead) Color(0xFFE5A23D) else Color(0xFFB8BCC4),
                fontSize = 15.sp,
            )
        }
        }

        // The relay could not carry this call, and we know it seconds in rather
        // than at the connect timeout. Offer the only thing that can still work,
        // and say plainly what it costs — connecting directly hands the other
        // side this device's address, which is exactly what relaying prevents.
        // Never taken automatically: that trade is the user's to make.
        if (relayDead) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.call_relay_dead_hint),
                    color = Color(0xFFB8BCC4), fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                CapsuleButton(stringResource(R.string.call_relay_dead_direct)) {
                    controller.retryWithoutRelay()
                }
            }
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
        if (showChrome) {
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
    // The controller resolves the word, with the same answered / connected
    // guards the history writer applies: the screen printed the generic one and
    // left "busy" to be discovered in the log (#683), and resolving it here
    // from the raw reason would have called an answered call missed (#472).
    is CallController.State.Ended -> stringResource(state.labelRes)
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

/** The slim bar that stands in for a call the user put aside.
 *
 *  Shows who and for how long, and takes one tap to go back. Deliberately the
 *  only thing it does: hanging up from here would put End a thumb's width from
 *  every other control on whatever screen is underneath, and the ongoing-call
 *  notification already carries that.
 */
@Composable
fun MinimizedCallBar(controller: CallController, modifier: Modifier = Modifier) {
    val connectedAt by controller.connectedAtMs.collectAsState()
    val info = controller.state.collectAsState().value.info ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAt) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val secs = if (connectedAt > 0) ((now - connectedAt) / 1000).coerceAtLeast(0) else 0
    Row(
        modifier
            .fillMaxWidth()
            .height(MINIMIZED_CALL_BAR_HEIGHT)
            .background(Color(0xFF1F7A3D))
            .clickable { controller.restoreFromMinimized() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(
            "%s · %d:%02d".format(info.peerNickname, secs / 60, secs % 60),
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(2.dp))
        Text(stringResource(R.string.call_return), color = Color(0xFFCDEBD7), fontSize = 12.sp)
    }
}

/** Height of [MinimizedCallBar]; the app content is padded by the same amount
 *  so the bar sits above it instead of over it. */
val MINIMIZED_CALL_BAR_HEIGHT: Dp = 34.dp
