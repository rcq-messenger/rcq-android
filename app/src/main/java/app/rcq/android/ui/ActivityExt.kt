package app.rcq.android.ui

import android.content.Context
import android.content.ContextWrapper
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

/** The height of the system navigation bar as the ACTIVITY's window sees it.
 *
 *  Inside a `Dialog` `navigationBarsPadding()` is zero: the dialog's own
 *  window reports no insets, while its content is laid out edge to edge under
 *  the bar all the same. The fullscreen viewers compensated with a fixed 40dp,
 *  which covers a gesture bar (about 24dp) and not the three-button bar
 *  (48dp): on a phone with buttons the album counter sat behind them, which
 *  is what the "wrong picture count" of report #704 was a screenshot of. The
 *  activity's root window still knows the real height, so ask it. */
@androidx.compose.runtime.Composable
internal fun activityNavigationBarBottom(): androidx.compose.ui.unit.Dp {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val px = ctx.findFragmentActivity()?.window?.decorView?.rootWindowInsets?.let {
        androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it)
            .getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
    } ?: 0
    return with(density) { px.toDp() }
}
