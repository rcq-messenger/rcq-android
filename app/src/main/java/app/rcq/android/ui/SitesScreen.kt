package app.rcq.android.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.sites.SiteAddress
import app.rcq.android.sites.SiteError
import app.rcq.android.sites.SitePins
import app.rcq.android.sites.SitesRepository
import kotlinx.coroutines.launch

/**
 * The `.rcq` browser: the network's own pages, and nothing else.
 *
 * Design: `RCQ/docs/rcq-sites-design.md`. What matters for reading this file:
 *
 * * The page is already SAFE before it reaches the WebView. Everything was
 *   fetched, signature-checked, hash-checked and sanitised in
 *   [SitesRepository]; images and stylesheets are inlined, so the document
 *   handed over here is self-contained and refers to nothing.
 * * The WebView is then locked anyway, because two locks are the point: no
 *   scripting, no network at all (`blockNetworkLoads` is a switch in the
 *   network stack, and [shouldInterceptRequest] refuses every request as a
 *   second, independent line), no file or content access, no storage, and no
 *   navigation - a tapped link cannot leave, and cannot hand the URL to
 *   another app either, which is what an unguarded `intent://` does.
 * * `.rcq` is not DNS and never leaves this device as a name: the address is
 *   parsed here into island and site, and the request goes straight to that
 *   island - never through the reader's own, which would otherwise hold a
 *   journal of what its users read elsewhere.
 */
@Composable
fun SitesScreen(
    session: Session,
    /**
     * Leave the browser. Called from the catalogue only: with a page or an
     * address error showing, Back - the chevron and the system gesture alike -
     * returns to the catalogue first.
     */
    onBack: () -> Unit,
    /**
     * An address to open straight away, e.g. the one tapped in a message.
     * Null (the default, and what the overflow menu passes) is the browser as
     * it has always been: the catalogue, and an empty address bar.
     */
    initialAddress: String? = null,
) {
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    // Seeded, not left blank and filled in on success: an address that fails
    // to load must still be readable in the bar, so the reader can see what
    // was asked for and try it again.
    var typed by remember { mutableStateOf(initialAddress.orEmpty()) }
    var addr by remember { mutableStateOf<SiteAddress?>(null) }
    var page by remember { mutableStateOf<SitesRepository.SitePage?>(null) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var catalogue by remember { mutableStateOf<List<Pair<String, String?>>>(emptyList()) }
    val marks = remember { mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap?>() }

    // "My island" for a bare `name.rcq`, taken from this session's own host:
    // somebody's first site is reachable before they know what an island is.
    val ownHost = remember { session.islandHost() }

    // Only the latest request may touch the screen. Without this a reload
    // that lands after Back has already cleared the page brings the page
    // straight back, and a slow page overwrites a faster one asked for later.
    var loadGen by remember { mutableStateOf(0) }

    fun open(raw: String, path: String = "index.html", fresh: Boolean = false) {
        val gen = ++loadGen
        val parsed = SiteAddress.parse(raw, ownHost)
        if (parsed == null) {
            loading = false
            errorCode = SiteError.Address.code
            page = null
            return
        }
        loading = true
        errorCode = null
        scope.launch {
            try {
                val got = SitesRepository.page(ctx, parsed, path, fresh)
                if (gen != loadGen) return@launch
                page = got
                addr = parsed
                typed = parsed.display
                // The mark of the site being read, fetched after the page so a
                // slow icon never holds the page up.
                scope.launch {
                    val m = SitesRepository.mark(parsed, fresh)
                    marks[parsed.name] = m?.let { bm ->
                        BitmapFactory.decodeByteArray(bm.bytes, 0, bm.bytes.size)?.asImageBitmap()
                    }
                }
            } catch (e: SiteError) {
                if (gen != loadGen) return@launch
                page = null
                errorCode = e.code
            } catch (e: Exception) {
                if (gen != loadGen) return@launch
                page = null
                errorCode = SiteError.Offline.code
            } finally {
                if (gen == loadGen) loading = false
            }
        }
    }

    // Back from a page or an address error returns to the catalogue, not out
    // of the browser: the reader came in to look around, and one wrong
    // address must not throw them back into the chat (founder, 02.09). Only
    // the catalogue itself hands Back to the caller.
    //
    // ⚠ This handler is composed INSIDE the activity's, so it is consulted
    // first while enabled; at the catalogue it is disabled and the activity's
    // entry closes the browser as before. The chevron in the bar goes through
    // the same closure as the system gesture, so the two cannot drift apart.
    val onCatalogue = page == null && errorCode == null
    fun toCatalogue() {
        loadGen++
        loading = false
        page = null
        addr = null
        errorCode = null
        typed = ""
    }
    BackHandler(enabled = !onCatalogue) { toCatalogue() }
    val back: () -> Unit = { if (onCatalogue) onBack() else toCatalogue() }

    // Opened on an address somebody tapped: load it at once. Keyed on the
    // address so re-entering the browser on a different one loads that one,
    // and a recomposition on the same one does not re-fetch.
    LaunchedEffect(initialAddress) {
        if (!initialAddress.isNullOrBlank()) open(initialAddress)
    }

    // The catalogue of the reader's own island: what there is to look at at
    // all, and only the sites that asked to be in it.
    LaunchedEffect(ownHost) {
        catalogue = SitesRepository.catalogue(ownHost)
        for ((name, _) in catalogue) {
            val a = SiteAddress.parse("$name.rcq", ownHost) ?: continue
            val m = SitesRepository.mark(a)
            marks[name] = m?.let { bm ->
                BitmapFactory.decodeByteArray(bm.bytes, 0, bm.bytes.size)?.asImageBitmap()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        // ── the address bar ──────────────────────────────────────────────
        //
        // One capsule across the full width, and the chevron lives inside it
        // at the left edge: before the site's mark on a page, before the hint
        // at the catalogue. Not a field with a button beside it (founder,
        // 02.09).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .height(40.dp)
                .clip(CircleShape)
                .background(c.bgSecondary)
                .padding(start = 6.dp, end = 12.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_back),
                tint = c.accent,
                modifier = Modifier.size(24.dp).clickable(onClick = back),
            )
            Spacer(Modifier.width(6.dp))
            val mark = addr?.let { marks[it.name] }
            if (page != null && mark != null) {
                androidx.compose.foundation.Image(
                    bitmap = mark,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(8.dp))
            }
            androidx.compose.foundation.text.BasicTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = c.textPrimary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Go,
                    autoCorrect = false,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                ),
                // Go opens it. There is no button beside the field: that
                // would be a second way to do what the keyboard already
                // does (founder, 01.09).
                keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); open(typed) }),
                decorationBox = { inner ->
                    if (typed.isEmpty()) {
                        Text(
                            stringResource(R.string.sites_address_hint),
                            color = c.textSecondary,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    inner()
                },
                modifier = Modifier.weight(1f),
            )
            if (page != null) {
                Spacer(Modifier.width(8.dp))
                if (loading) {
                    CircularProgressIndicator(
                        color = c.textSecondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        stringResource(R.string.sites_reload),
                        tint = c.textSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { addr?.let { open(it.display, page!!.path, fresh = true) } },
                    )
                }
            }
        }

        // ── the other pages of this site ─────────────────────────────────
        //
        // With no scripts in the view, a link inside a page cannot navigate,
        // so the doors live out here.
        val pages = page?.pages.orEmpty()
        if (pages.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pages.forEach { p ->
                    val here = p == page?.path
                    Text(
                        p,
                        color = if (here) c.textPrimary else c.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (here) c.bgSecondary else c.bgPrimary)
                            .clickable { addr?.let { open(it.display, p) } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // ── a key that changed since the last visit ──────────────────────
        //
        // The one thing worth interrupting for: these bytes are signed by
        // somebody other than last time, which is exactly what the signature
        // exists to make visible.
        val p = page
        if (p != null && p.keyChanged) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.statusBusy.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.sites_key_changed),
                    color = c.textPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.sites_key_changed_accept),
                    color = c.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        addr?.let { SitePins.repin(it, p.key) }
                        page = p.copy(keyChanged = false)
                    },
                )
            }
        }

        // ── the page, the catalogue, or what went wrong ──────────────────
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                errorCode != null -> Text(
                    stringResource(errorText(errorCode!!)),
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )

                page == null -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.sites_empty_title),
                        color = c.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.sites_empty_body),
                        color = c.textSecondary,
                        fontSize = 12.sp,
                    )
                    if (catalogue.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.sites_catalogue).uppercase(),
                            color = c.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        catalogue.forEach { (name, title) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { open("$name.rcq") }
                                    .padding(vertical = 8.dp),
                            ) {
                                val bm = marks[name]
                                if (bm != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bm,
                                        contentDescription = null,
                                        modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)),
                                    )
                                } else {
                                    // Not a placeholder waiting for a picture:
                                    // most sites will never have one, and a row
                                    // that jumps when an icon lands is worse
                                    // than a row that never had it.
                                    Box(
                                        Modifier
                                            .size(26.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(c.bgSecondary),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            name.take(1).uppercase(),
                                            color = c.textSecondary,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "$name.rcq",
                                        color = c.textPrimary,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    if (!title.isNullOrBlank()) {
                                        Text(title, color = c.textSecondary, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                else -> LockedWebView(html = p!!.html)
            }
        }
    }
}

/** The island's answer, in the reader's language. */
private fun errorText(code: String): Int = when (code) {
    "address" -> R.string.sites_error_address
    "frozen" -> R.string.sites_error_frozen
    "unsigned" -> R.string.sites_error_unsigned
    "tampered" -> R.string.sites_error_tampered
    "offline" -> R.string.sites_error_offline
    else -> R.string.sites_error_missing
}

/**
 * A WebView that can render and can do nothing else.
 *
 * ⚠⚠ Every line here is load-bearing, and the order they are argued in is:
 *
 * * `javaScriptEnabled` is false by default and set false anyway, because a
 *   default is a thing somebody changes.
 * * `blockNetworkLoads` is a switch inside the network stack rather than a
 *   callback we might get wrong, and [shouldInterceptRequest] is the second,
 *   independent line: it answers every request with nothing. The document is
 *   self-contained, so neither should ever fire - which is exactly why they
 *   are cheap to keep.
 * * `loadDataWithBaseURL(null, …)` gives the document an opaque origin, and
 *   with no base URL a relative reference resolves to nowhere.
 * * `shouldOverrideUrlLoading` returns true from BOTH overloads. Without it a
 *   tapped `intent://` link is handed to the system and opens another app -
 *   the one hole in a locked WebView that is not about the web at all.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LockedWebView(html: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true
                settings.blockNetworkImage = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                settings.setGeolocationEnabled(false)
                settings.mediaPlaybackRequiresUserGesture = true
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                isVerticalScrollBarEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = true

                    @Deprecated("Kept for API 23 and below, which still call it")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse = WebResourceResponse(
                        "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)),
                    )
                }
            }
        },
        update = { view ->
            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
    )
}
