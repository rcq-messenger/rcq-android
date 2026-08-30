package app.rcq.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
