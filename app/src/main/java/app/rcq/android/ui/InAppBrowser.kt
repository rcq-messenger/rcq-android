package app.rcq.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** In-app browser: a web link tapped in a chat opens in a Chrome Custom Tab
 *  over the app (Telegram-style) instead of kicking the user out to the
 *  system browser; the tab's overflow menu keeps the stock "Open in browser"
 *  escape hatch. Installed app-wide as the [androidx.compose.ui.platform.LocalUriHandler]
 *  in MainActivity, so every LinkAnnotation.Url / uriHandler.openUri resolves here.
 *
 *  RCQ's own deep links must NOT land in a web view: rcq:// and the https
 *  rcq.app forms the manifest declares (/g/, /u/, /link) stay on ACTION_VIEW
 *  so the intent filters route them back into the app's join/add/link flows.
 *  Any Custom-Tabs failure (no browser installed) falls back to ACTION_VIEW. */
object InAppBrowser {

    /** https rcq.app path prefixes the manifest deep-link filters catch. */
    private val DEEP_PATHS = listOf("/g/", "/u/", "/link")

    fun open(context: Context, raw: String) {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase()
        val isWeb = scheme == "http" || scheme == "https"
        val isOwnDeepLink = scheme == "rcq" ||
            (isWeb && uri.host.equals("rcq.app", ignoreCase = true) &&
                DEEP_PATHS.any { uri.path.orEmpty().startsWith(it) })
        if (!isWeb || isOwnDeepLink) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            return
        }
        runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, uri)
        }.recoverCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
}
