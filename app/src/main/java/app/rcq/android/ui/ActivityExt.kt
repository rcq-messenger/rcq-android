package app.rcq.android.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.fragment.app.FragmentActivity

/** Walk the context-wrapper chain to the hosting [FragmentActivity] (needed to
 *  host a BiometricPrompt), or null if there isn't one. */
internal fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/** How much bottom padding THIS composable still needs to clear the system
 *  navigation bar, when it may or may not already be clear of it.
 *
 *  Inside a `Dialog` `navigationBarsPadding()` is often zero: the dialog's own
 *  window reports no insets, while its content is laid out edge to edge under
 *  the bar all the same. The fullscreen viewers compensated with a fixed 40dp,
 *  which covers a gesture bar (about 24dp) and not the three-button bar
 *  (48dp): on a phone with buttons the album counter sat behind them, which
 *  is what the "wrong picture count" of report #704 was a screenshot of.
 *
 *  ★ Neither number alone is right on every Android, so this asks both and
 *  takes the difference: the bar's real height as the ACTIVITY's window sees
 *  it, minus whatever this window has already been padded by. A dialog laid
 *  out under the bar reports zero of its own and gets the full height; one
 *  the platform already inset gets nothing more and is not double-counted.
 *
 *  ⚠ `getInsetsIgnoringVisibility` and not `getInsets`: below API 30 the
 *  compat layer answers the latter from the system-window insets, which GROW
 *  with the keyboard, so opening an album while typing parked the counter
 *  halfway up the screen. The ignoring-visibility form falls back to the
 *  STABLE insets there, which are the bars and nothing else. */
@androidx.compose.runtime.Composable
internal fun activityNavigationBarBottom(): androidx.compose.ui.unit.Dp {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val activityPx = ctx.findFragmentActivity()?.window?.decorView?.rootWindowInsets?.let {
        androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it)
            .getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
    } ?: 0
    val ownPx = WindowInsets.navigationBars.getBottom(density)
    return with(density) { (activityPx - ownPx).coerceAtLeast(0).toDp() }
}
