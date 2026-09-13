package app.rcq.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// Shared chrome for the full-screen media viewers: the auto-hide timer, the
/// fade, and the tappable sender name at the top.
///
/// Lives outside ChatScreen.kt on purpose. The photo viewer, the album pager
/// and the video player each grew their own copy of the same top row (close on
/// the left, save/share on the right) and the three had already started to
/// drift; the timer and the fade are the kind of thing that MUST behave
/// identically in all three or the difference reads as a bug.

/// How long the controls stay up after the last touch.
///
/// 3.5s, not the 2s a video player would use: the buttons here are Save and
/// Share, which people reach for a moment AFTER looking at the picture, and a
/// control that is gone before the hand arrives is worse than one that never
/// hides. Long enough to use, short enough that the picture is clear while you
/// are actually looking at it.
internal const val VIEWER_CHROME_AUTOHIDE_MS = 3500L

/// Fade length. Deliberately not a slide: the controls sit ON the picture, and
/// anything that moves reads as the picture moving.
internal const val VIEWER_CHROME_FADE_MS = 220

/// Visibility of one viewer's controls.
///
/// [revision] is the whole mechanism: every touch bumps it, and the auto-hide
/// effect is keyed on it, so a new touch restarts the countdown instead of
/// stacking a second one on top of the first.
///
/// ⚠ Note what the effect is NOT keyed on: [visible]. Keying an effect on the
/// state it sets itself is the self-killing effect this codebase has been bitten
/// by before. Same reason the timer writes [visible] directly instead of calling
/// [hide]: [hide] bumps [revision], which would re-key the effect that just
/// fired and arm another countdown, for ever, on a screen nobody is touching.
@Stable
internal class ViewerChromeState {
    var visible: Boolean by mutableStateOf(true)
        internal set

    internal var revision by mutableIntStateOf(0)
        private set

    /** Bring the controls up and restart the countdown. */
    fun show() {
        visible = true
        revision++
    }

    /** Put them away now. */
    fun hide() {
        visible = false
        revision++
    }

    /** Show if hidden, hide if shown; returns what it became. */
    fun toggle(): Boolean {
        if (visible) hide() else show()
        return visible
    }
}

/// A [ViewerChromeState] that puts itself away after [autoHideMs] of no touching.
///
/// [pinned] is the escape hatch for "the person is clearly still working the
/// controls": a paused clip, a finger on the scrub bar, a page still loading.
/// While it is true nothing hides, and the moment it goes false the countdown
/// starts from the top.
@Composable
internal fun rememberViewerChrome(
    autoHideMs: Long = VIEWER_CHROME_AUTOHIDE_MS,
    pinned: Boolean = false,
): ViewerChromeState {
    val state = remember { ViewerChromeState() }
    // Pinning brings them BACK, it does not merely stop them leaving: pausing a
    // clip has to show the controls or the pause looks like a freeze.
    LaunchedEffect(pinned) { if (pinned) state.show() }
    LaunchedEffect(state.revision, pinned, autoHideMs) {
        if (pinned || autoHideMs <= 0L) return@LaunchedEffect
        delay(autoHideMs)
        // Direct write, no revision bump. See the note on the class.
        state.visible = false
    }
    return state
}

/// Wraps [content] in the standard viewer fade.
///
/// Put the `Modifier.align(...)` on THIS, not inside it: the alignment belongs
/// to the Box the viewer draws in, and the faded content is a child of the
/// animation rather than of that Box.
@Composable
internal fun ViewerChrome(
    state: ViewerChromeState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier,
        enter = fadeIn(tween(VIEWER_CHROME_FADE_MS)),
        exit = fadeOut(tween(VIEWER_CHROME_FADE_MS)),
    ) {
        content()
    }
}

/// Who sent this, at the top of the viewer, as a tappable pill.
///
/// ⚠ The horizontal padding is not cosmetic. This shares the top row with the
/// close disc (40dp plus a 16dp margin on the left) and the action discs on
/// the right, and a long nickname laid across the full width would sit under
/// both groups and eat their taps. The pill centres in what is left, and the
/// name ellipsises instead of wrapping under a button.
///
/// [trailingActions] is how many discs sit on the right: 16 + 40 + (12 + 40)
/// per extra one, so two need 108dp and three need 160dp. Pass the real count
/// — an album page carries a third "more" disc, and 116dp of clearance that
/// was generous for two is 44dp short for three.
///
/// [onClick] null = not tappable (an unknown sender, or a card the island will
/// not open for us): the name is still worth showing, it just must not pretend
/// to be a button.
@Composable
internal fun ViewerSenderLabel(
    name: String,
    modifier: Modifier = Modifier,
    trailingActions: Int = 2,
    onClick: (() -> Unit)? = null,
) {
    if (name.isBlank()) return
    val endClear = (16 + 40 + (12 + 40) * (trailingActions - 1).coerceAtLeast(0) + 8).dp
    Box(
        modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(top = 16.dp, start = 116.dp, end = maxOf(endClear, 116.dp))
            .height(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                // The same dark scrim the discs use: white text on an unknown
                // photo is legible over nothing in particular.
                .background(Color.Black.copy(alpha = 0.45f))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

// ── Swipe down to close ──────────────────────────────────────────────

/// How far the picture has to travel before releasing closes the viewer.
///
/// Generous on purpose. The same finger is used to scroll, and a viewer that
/// closes on 40dp of stray movement is a viewer that closes while you are
/// looking at the picture. 140dp is a deliberate pull.
internal val VIEWER_DISMISS_TRAVEL = 140.dp

/// How far the drag is allowed to carry the picture, as a fraction of the
/// travel: past the threshold the picture stops following, which is the
/// gesture saying "let go now and I close".
private const val VIEWER_DISMISS_MAX_OVERSHOOT = 1.35f

/// The state of a swipe-down-to-close.
///
/// Item 9(b) turned the tap into a chrome toggle, so the tap no longer closes
/// the viewer. That leaves the close disc, which auto-hides with the rest of
/// the chrome, so it must not be the only way out: this is the second way, and
/// the one every gallery on this phone already teaches.
///
/// ⚠ [dragPx] is written from the drag callback SYNCHRONOUSLY, and the settle
/// animation is the only thing that runs in a coroutine. An earlier cut kept
/// the value in an `Animatable` and wrote it with `launch { snapTo(...) }`; the
/// drag callback fires several times per frame and reads the value back to add
/// the next delta, so most of the deltas were added to a value that had not
/// been written yet and the picture crawled a third of the way behind the
/// finger.
///
/// It is snapshot state, but nothing recomposes on it: the viewers read it
/// inside a `graphicsLayer { }` block, which re-runs the draw phase alone.
@Stable
internal class ViewerDismissState(internal val travelPx: Float) {
    /** Live travel in px. Put it on the content as a `translationY`. */
    var dragPx: Float by mutableFloatStateOf(0f)
        private set

    /** 0 at rest, 1 where releasing closes. Drives the fade. */
    val progress: Float get() = if (travelPx <= 0f) 0f else (dragPx / travelPx).coerceIn(0f, 1f)

    /** The picture dims as it is pulled away, so the gesture is legible before
     *  it is complete: nothing moving on a black ground reads as "stuck". */
    val contentAlpha: Float get() = 1f - 0.45f * progress

    /** True when letting go now closes the viewer. */
    internal val armed: Boolean get() = dragPx >= travelPx

    internal fun drag(to: Float) {
        // Upward travel is clamped at rest rather than followed, so pulling up
        // and back down cannot bank distance towards the threshold.
        dragPx = to.coerceIn(0f, travelPx * VIEWER_DISMISS_MAX_OVERSHOOT)
    }

    internal suspend fun settle() {
        val from = dragPx
        if (from <= 0f) { dragPx = 0f; return }
        animate(from, 0f, animationSpec = tween(180)) { value, _ -> dragPx = value }
    }
}

@Composable
internal fun rememberViewerDismiss(travel: Dp = VIEWER_DISMISS_TRAVEL): ViewerDismissState {
    val travelPx = with(LocalDensity.current) { travel.toPx() }
    return remember(travelPx) { ViewerDismissState(travelPx) }
}

/// Drag the picture down to close it.
///
/// Put this on the viewer's OUTER box, the one the content sits inside, and put
/// the matching `translationY` / `alpha` on the content. Two reasons it goes on
/// the parent rather than on the picture: the black margins beside a
/// letterboxed picture are part of the gesture, and inside the album pager the
/// pager's own horizontal drag gets first refusal on every pointer, so a
/// detector under it would be fighting the pager for the same events.
///
/// [enabled] is false while the picture is zoomed in. A pinched-in photo is
/// being PANNED, and a pan that reaches the bottom of the picture must not
/// throw the viewer away.
@Composable
internal fun Modifier.viewerSwipeToDismiss(
    state: ViewerDismissState,
    enabled: Boolean = true,
    onDismiss: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    return this.pointerInput(state, enabled) {
        // Zooming in mid-drag turns the gesture off under the finger; without
        // this the picture would stay parked wherever the drag had carried it.
        if (!enabled) { state.settle(); return@pointerInput }
        detectVerticalDragGestures(
            onDragCancel = { scope.launch { state.settle() } },
            onDragEnd = { if (state.armed) dismiss() else scope.launch { state.settle() } },
        ) { change, dy ->
            state.drag(state.dragPx + dy)
            // Consumed only while the picture is actually being carried: at
            // rest the event still belongs to whatever is underneath.
            if (state.dragPx > 0f) change.consume()
        }
    }
}

// ── The window a full-screen viewer lives in ─────────────────────────

/// Properties for a viewer's `Dialog`, and the ONLY ones any of the three
/// should use.
///
/// ⚠⚠ `usePlatformDefaultWidth = true` is not a typo, and it is the whole of
/// report #979's landscape screenshot. The obvious-looking `false` does not
/// mean "fill the screen": Compose's own DialogLayout then measures the content
/// against `Configuration.screenWidthDp / screenHeightDp` and, on every layout
/// pass, writes THAT MANY PIXELS back into the window as a fixed size with
/// gravity CENTER. Two things go wrong with it.
///
/// A configuration that has not caught up with a rotation, which is what the
/// activity handling `configChanges` itself buys us, hands the dialog the
/// PORTRAIT numbers while the display is already landscape, and the result is a
/// portrait-shaped black box floating in the middle of a landscape screen with
/// the chat visible either side of it. That is the picture the reporter sent,
/// pixel for pixel: the box measured 1080 wide on a 2400-wide screen.
///
/// And even when the numbers are right, "as many pixels as the screen has" is
/// not the same instruction as "as big as the window is". Seen on the
/// emulator, where the landscape picture was pushed down by the status bar and
/// its bottom edge fell off the screen.
///
/// With `true`, DialogLayout leaves the window alone and measures against the
/// window's real size, so [PinViewerWindowToScreen] below can say MATCH_PARENT
/// once and have WindowManager re-resolve it against the actual display on
/// every rotation, for ever.
///
/// `decorFitsSystemWindows = false` goes with it. It buys two things: on API
/// 31+ Compose picks a theme whose dialogs are not floating, and the decor
/// stops padding our content for the bars. It does NOT by itself make the
/// WINDOW cover them, which is [PinViewerWindowToScreen]'s other half. Every
/// control inside adds the bars back by hand, which all three viewers do.
internal fun viewerDialogProperties(dismissOnClickOutside: Boolean = true) = DialogProperties(
    usePlatformDefaultWidth = true,
    decorFitsSystemWindows = false,
    dismissOnClickOutside = dismissOnClickOutside,
)

/// Pin the hosting dialog window to the whole display. Call it as the first
/// thing inside a viewer's `Dialog { }`.
///
/// MATCH_PARENT rather than any number we could compute: a number is a snapshot
/// of one orientation and this window outlives rotations. Gravity TOP|START so
/// there is nothing to centre: a window the size of the screen has no slack to
/// be centred in, and CENTER is exactly what turned a stale size into a box
/// hovering in the middle of the screen.
///
/// ⚠⚠ MATCH_PARENT alone is not the whole display, and this cost an hour.
/// "Parent" for a window WITHOUT `FLAG_LAYOUT_IN_SCREEN` is the content frame,
/// which is the display minus the system bars, so the picture came back inset
/// by the status bar at the top and the gesture bar at the bottom, with two
/// grey bands where a black viewer should be. An Activity's window is born with
/// that flag, which is why `enableEdgeToEdge` is all MainActivity ever needed;
/// a Dialog's window is not. `LAYOUT_INSET_DECOR` is its other half: it keeps
/// the real bar insets being REPORTED to a window that now sits under them,
/// which is what `statusBarsPadding` on every control in here is reading, and
/// what activityNavigationBarBottom (ActivityExt.kt) is comparing against.
///
/// The contrast enforcement goes too. Android 15 paints its own translucent
/// grey behind the bars for anyone who does not say otherwise, and grey stripes
/// across a photograph are the thing the black ground exists to avoid.
///
/// The cutout mode is the same one MainActivity declares. Without it a phone
/// with a notch letterboxes this window away from the notch in landscape, which
/// is a black bar down one side of every picture on exactly the phones people
/// hold sideways to look at one.
// FLAG_LAYOUT_INSET_DECOR and the contrast switches are marked deprecated in
// favour of setDecorFitsSystemWindows, which Compose already calls and which by
// itself leaves this window inside the content frame (see above). Until that is
// not true any more, these are the instruments that work.
@Suppress("DEPRECATION")
@Composable
internal fun PinViewerWindowToScreen() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
        window?.let {
            val lp = it.attributes
            lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.flags = lp.flags or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
            // ⚠ And the flags above are still not enough on their own. A window
            // also carries a set of inset types it wants to be FITTED to, which
            // defaults to the bars; LAYOUT_IN_SCREEN drops the status bar from
            // it and leaves the navigation bar, so the picture stopped one
            // gesture bar short of the bottom of the screen and its centre sat
            // 34px high. Fit nothing: this is a black ground with a picture on
            // it, and every control on top adds the bars back itself.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                lp.fitInsetsTypes = 0
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            it.attributes = lp
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                it.isStatusBarContrastEnforced = false
                it.isNavigationBarContrastEnforced = false
            }
            if (android.os.Build.VERSION.SDK_INT < 35) {
                it.statusBarColor = android.graphics.Color.TRANSPARENT
                it.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
        }
        onDispose {}
    }
}

// ── Zoom ─────────────────────────────────────────────────────────────

/// Smallest zoom ceiling, whatever the picture is. Five times the fitted size
/// is what the viewer has always offered and is plenty for a photograph.
private const val VIEWER_ZOOM_MIN_CEILING = 5f

/// Anything below this counts as "the whole picture is on screen": a float
/// that came back from a pinch is never exactly 1.
private const val VIEWER_ZOOM_EPSILON = 1.001f

/// How far a double tap goes when the picture already fills the screen, i.e.
/// when "fill the screen" is no zoom at all and the tap would otherwise do
/// nothing.
private const val VIEWER_ZOOM_DOUBLE_TAP_MIN = 2.5f

/// Where a picture has been zoomed and panned to.
///
/// Hoisted out of the image on purpose: the viewer around it has to know. The
/// swipe-down-to-close turns off while [zoomed] (a pan that reaches the bottom
/// of the picture must not throw the viewer away), the album pager stops
/// turning pages, and the chrome pins itself so the close button cannot fade
/// out from under someone reading at 4x.
@Stable
internal class ViewerZoomState {
    /// Multiplier ON TOP of fit-to-screen, never an absolute scale: 1 means the
    /// whole picture is visible, whatever shape it is.
    var scale: Float by mutableFloatStateOf(1f)
        internal set

    /// Pan, in screen px, applied after the scale.
    var offset: Offset by mutableStateOf(Offset.Zero)
        internal set

    val zoomed: Boolean get() = scale > VIEWER_ZOOM_EPSILON

    internal fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

@Composable
internal fun rememberViewerZoom(key: Any? = Unit): ViewerZoomState = remember(key) { ViewerZoomState() }

/// A picture that fills the viewer, pinches to zoom, drags to pan and answers a
/// DOUBLE TAP.
///
/// The double tap is report #979's other half. A 2560x1440 desktop screenshot
/// fitted to a portrait phone is a 1080x607 strip with two thirds of the screen
/// black above and below it, and at that size nothing on it can be read. That
/// is the correct answer to "show me all of it" and a useless one to "let me
/// look at it", and until now the only way across was a two-finger pinch nobody
/// is told about. A double tap now jumps straight to the zoom that makes the
/// picture COVER the screen. For a wide picture on a tall phone that is the
/// height, so the strip becomes a full screen you pan sideways, and a second
/// double tap comes back.
///
/// ⚠ The pan is clamped, which the old free-running offset was not: a drag
/// could carry the picture clean off the screen and leave a black rectangle
/// with no gesture that brings it back.
///
/// ⚠ `onTap` is handled HERE and not by a `clickable` on the ground behind.
/// The image fills the viewer (the black margins are inside its own bounds), so
/// a parent's clickable never sees a tap, and a bare `detectTapGestures` with
/// only a double-tap handler would swallow the single one.
// `canPan` on transformable is still experimental; the same opt-in the callers
// carry, for the same modifier.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ZoomableImage(
    bitmap: ImageBitmap,
    zoom: ViewerZoomState,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        if (boxW <= 0f || boxH <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return@BoxWithConstraints
        // What ContentScale.Fit below will do, said in numbers so the gestures
        // can reason about the picture that is actually on the glass.
        val fit = minOf(boxW / bitmap.width, boxH / bitmap.height)
        val fittedW = bitmap.width * fit
        val fittedH = bitmap.height * fit
        // The zoom that turns the fitted picture into a full screen. 1 for a
        // picture the same shape as the phone, ~3 for a 16:9 screenshot held
        // upright, which is the case the report is about.
        val cover = maxOf(boxW / fittedW, boxH / fittedH).coerceAtLeast(1f)
        val maxScale = maxOf(VIEWER_ZOOM_MIN_CEILING, cover * 2f)

        // A rotation changes every number above, so whatever the picture was
        // panned to means nothing any more: start from the whole picture again
        // rather than from a corner of the old one.
        LaunchedEffect(bitmap, boxW, boxH) { zoom.reset() }

        fun clamp(o: Offset, s: Float): Offset {
            val slackX = ((fittedW * s - boxW) / 2f).coerceAtLeast(0f)
            val slackY = ((fittedH * s - boxH) / 2f).coerceAtLeast(0f)
            return Offset(o.x.coerceIn(-slackX, slackX), o.y.coerceIn(-slackY, slackY))
        }

        val transform = rememberTransformableState { zoomChange, panChange, _ ->
            val next = (zoom.scale * zoomChange).coerceIn(1f, maxScale)
            zoom.scale = next
            zoom.offset = if (next > VIEWER_ZOOM_EPSILON) clamp(zoom.offset + panChange, next) else Offset.Zero
        }

        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, maxScale, boxW, boxH) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { at ->
                            if (zoom.zoomed) {
                                zoom.reset()
                            } else {
                                val target = maxOf(cover, VIEWER_ZOOM_DOUBLE_TAP_MIN).coerceAtMost(maxScale)
                                // Keep what is under the finger under the
                                // finger: the layer scales about its centre, so
                                // the point tapped moves by (centre - tap) *
                                // (scale - 1) and this cancels it.
                                val centre = Offset(boxW / 2f, boxH / 2f)
                                zoom.scale = target
                                zoom.offset = clamp((centre - at) * (target - 1f), target)
                            }
                        },
                    )
                }
                // ⚠ `canPan` matches the album pager, and here it is what lets
                // the swipe-down through: without it transformable eats every
                // drag at any zoom, and the close gesture never reaches the box
                // above.
                .transformable(transform, canPan = { zoom.zoomed })
                .graphicsLayer(
                    scaleX = zoom.scale, scaleY = zoom.scale,
                    translationX = zoom.offset.x, translationY = zoom.offset.y,
                ),
        )
    }
}
