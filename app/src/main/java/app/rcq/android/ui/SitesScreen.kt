package app.rcq.android.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
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
import app.rcq.android.sites.SiteLink
import app.rcq.android.sites.SitePins
import app.rcq.android.sites.SiteRecents
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
 *   another app either, which is what an unguarded `intent://` does. The one
 *   thing a tap can do is name a page of the same bundle or another `.rcq`
 *   site, through the two private schemes [SitesRepository.door] writes,
 *   and then it is THIS screen that opens it, the way it opens anything.
 * * `.rcq` is not DNS and never leaves this device as a name: the address is
 *   parsed here into island and site, and the request goes straight to that
 *   island - never through the reader's own, which would otherwise hold a
 *   journal of what its users read elsewhere.
 *
 * The start screen is three lists (founder, 02.09): what the island pins,
 * what this device opened last, and the island's catalogue minus those two.
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
    /** The page of that bundle a link with a path asked for; null is the
     *  front page. */
    initialPage: String? = null,
    /**
     * Share [display] as text (report #852). [at] is where the browser
     * stands - the page open now, or null at the start screen - because the
     * "Send to…" picker REPLACES this screen in the activity's `when` and
     * everything remembered here goes with it: the caller re-seeds
     * [initialAddress] and [initialPage] from it, so cancelling the picker
     * brings back the page that was being shared and not whatever address
     * the browser happened to be opened on.
     */
    onShare: (display: String, at: SiteOpen.Req?) -> Unit = { _, _ -> },
) {
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current

    // Editing turns the centred address into an ordinary left-aligned field.
    // It is a state of its own, not "the field has focus": a touch or a
    // focus on the field starts it, and so does typing; Go, Back and the
    // keyboard going away end it.
    var editing by remember { mutableStateOf(false) }
    val addressFocus = remember { MutableInteractionSource() }
    LaunchedEffect(addressFocus) {
        addressFocus.interactions.collect {
            if (it is FocusInteraction.Focus || it is PressInteraction.Press) editing = true
        }
    }

    // At rest the field also lets go of its focus, or the cursor handle from
    // the last tap stays hanging under the centred address.
    //
    // ⚠ Only in touch mode. After a hardware key the device is out of it, and
    // there the View system answers clearFocus() by re-focusing the root,
    // which hands Compose's focus to the first focusable node on the screen -
    // the chevron - and the release of the very Enter that opened the page
    // then clicks it: the page went away, and from the catalogue the browser
    // closed. Seen with adb's keyevent, which is a hardware key like any
    // other. Out of touch mode the field keeps its focus, as it always did.
    fun restFocus() {
        if (view.isInTouchMode) focusManager.clearFocus()
    }

    // Seeded, not left blank and filled in on success: an address that fails
    // to load must still be readable in the bar, so the reader can see what
    // was asked for and try it again.
    var typed by remember { mutableStateOf(initialAddress.orEmpty()) }
    var addr by remember { mutableStateOf<SiteAddress?>(null) }
    var page by remember { mutableStateOf<SitesRepository.SitePage?>(null) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var catalogue by remember { mutableStateOf<List<SitesRepository.Listed>>(emptyList()) }
    var recents by remember { mutableStateOf<List<SiteRecents.Entry>>(emptyList()) }
    // Keyed `name@host` ([SiteAddress.pinKey]): a recent may be on another
    // island, and its mark is that island's, not the same name's here.
    val marks = remember { mutableStateMapOf<String, ImageBitmap?>() }

    // "My island" for a bare `name.rcq`, taken from this session's own host:
    // somebody's first site is reachable before they know what an island is.
    val ownHost = remember { session.islandHost() }

    // Only the latest request may touch the screen. Without this a reload
    // that lands after Back has already cleared the page brings the page
    // straight back, and a slow page overwrites a faster one asked for later.
    var loadGen by remember { mutableStateOf(0) }

    fun fetchMark(a: SiteAddress, fresh: Boolean = false) {
        scope.launch {
            val m = SitesRepository.mark(a, fresh)
            marks[a.pinKey] = m?.let { bm ->
                BitmapFactory.decodeByteArray(bm.bytes, 0, bm.bytes.size)?.asImageBitmap()
            }
        }
    }

    // A site known by identity - a recent, a pinned row, a link - opens as
    // an object; only what is TYPED goes through the parser. See
    // [SiteAddress.of] for why the display of a recent may not parse back.
    fun openAddr(parsed: SiteAddress, path: String = "index.html", fresh: Boolean = false) {
        val gen = ++loadGen
        loading = true
        errorCode = null
        typed = parsed.display
        scope.launch {
            try {
                val got = SitesRepository.page(ctx, parsed, path, fresh)
                if (gen != loadGen) return@launch
                page = got
                addr = parsed
                typed = parsed.display
                // Now, not on the tap: a site that did not open is not one
                // the reader was at.
                SiteRecents.touch(parsed, got.title)
                recents = SiteRecents.list()
                // The mark of the site being read, fetched after the page so a
                // slow icon never holds the page up.
                fetchMark(parsed, fresh)
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

    fun open(raw: String, path: String = "index.html", fresh: Boolean = false) {
        val parsed = SiteAddress.parse(raw, ownHost)
        if (parsed == null) {
            loadGen++
            loading = false
            errorCode = SiteError.Address.code
            page = null
            return
        }
        openAddr(parsed, path, fresh)
    }

    // The address as text, into the app's own "Send to…" picker: the person
    // picks a chat or a contact and the address lands in its composer, to be
    // sent as an ordinary message (report #852). The address alone, bare -
    // `name.rcq` here, `name.island.rcq` elsewhere - which every client
    // already turns into a link on the other end.
    fun share(display: String) {
        val here = addr?.let { a -> page?.let { SiteOpen.Req(a.display, it.path) } }
        onShare(display, here)
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
        editing = false
        keyboard?.hide()
        restFocus()
    }
    BackHandler(enabled = !onCatalogue) { toCatalogue() }
    val back: () -> Unit = { if (onCatalogue) onBack() else toCatalogue() }

    // The keyboard going away ends the editing. Android leaves the field
    // focused when Back dismisses the keyboard, and a bar left-aligned with a
    // cursor in it and nothing to type on is not an address bar at rest.
    KeyboardGoneEffect {
        editing = false
        restFocus()
    }

    // Opened on an address somebody tapped: load it at once. Keyed on the
    // address so re-entering the browser on a different one loads that one,
    // and a recomposition on the same one does not re-fetch.
    LaunchedEffect(initialAddress, initialPage) {
        if (!initialAddress.isNullOrBlank()) open(initialAddress, initialPage ?: "index.html")
    }

    // The catalogue of the reader's own island: what there is to look at at
    // all, and only the sites that asked to be in it. The recents come first
    // and from disk, so the start screen is not blank while the island is
    // asked.
    LaunchedEffect(ownHost) {
        SiteRecents.init(ctx)
        recents = SiteRecents.list()
        catalogue = SitesRepository.catalogue(ownHost)
        // ⚠⚠ Marks are asked of THIS island only. A recent row on somebody
        // else's island is drawn with its letter until it is opened: asking
        // that island for the mark would tell it "this address still has me in
        // its list" every time the reader merely opens the browser, and the
        // promise here is that an island learns about a reader when the reader
        // opens something on it.
        val wanted = catalogue.map { SiteAddress.of(it.name, ownHost, ownHost) } +
            recents.filter { it.host == ownHost }.map { SiteAddress.of(it.name, it.host, ownHost) }
        for (a in wanted.distinctBy { it.pinKey }) {
            if (a.pinKey in marks) continue
            val m = SitesRepository.mark(a)
            marks[a.pinKey] = m?.let { bm ->
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
        //
        // Idle, the address is centred on the CAPSULE, not on the field left
        // over between the chevron and the glyphs: the two side slots are the
        // same width, as wide as the wider of what they hold, so neither the
        // chevron and the mark nor the reload and share glyphs push the
        // address off centre (founder, 02.09). Editing, it is an ordinary
        // field, left-aligned.
        val mark = if (page != null) addr?.let { marks[it.pinKey] } else null
        val leftNeed = if (mark != null) 24.dp + 6.dp + 18.dp else 24.dp
        val rightNeed = if (page != null) 18.dp + 8.dp + 18.dp + 6.dp else 24.dp
        val sideSlot = if (leftNeed > rightNeed) leftNeed else rightNeed
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .height(40.dp)
                .clip(CircleShape)
                .background(c.bgSecondary)
                .padding(horizontal = 6.dp),
        ) {
            Row(Modifier.width(sideSlot), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.common_back),
                    tint = c.accent,
                    modifier = Modifier.size(24.dp).clickable(onClick = back),
                )
                if (mark != null) {
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.foundation.Image(
                        bitmap = mark,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = typed,
                onValueChange = { typed = it; editing = true },
                singleLine = true,
                interactionSource = addressFocus,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = c.textPrimary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = if (editing) TextAlign.Start else TextAlign.Center,
                ),
                // Out of touch mode the field stays focused at rest (see
                // restFocus), so the cursor is what says it is being edited.
                cursorBrush = androidx.compose.ui.graphics.SolidColor(if (editing) c.accent else Color.Transparent),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Go,
                    autoCorrect = false,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                ),
                // Go opens it. There is no button beside the field: that
                // would be a second way to do what the keyboard already
                // does (founder, 01.09).
                keyboardActions = KeyboardActions(onGo = {
                    keyboard?.hide()
                    editing = false
                    restFocus()
                    open(typed)
                }),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = if (editing) Alignment.CenterStart else Alignment.Center,
                    ) {
                        if (typed.isEmpty()) {
                            Text(
                                stringResource(R.string.sites_address_hint),
                                color = c.textSecondary,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // The right slot mirrors the left one; the glyphs sit at its far
            // end so the outer one's distance from the capsule's edge is the
            // chevron's. Reload nearest the address, share at the edge.
            Row(
                Modifier.width(sideSlot),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        color = c.textSecondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 6.dp).size(15.dp),
                    )
                } else if (page != null) {
                    Icon(
                        Icons.Filled.Refresh,
                        stringResource(R.string.sites_reload),
                        tint = c.textSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { addr?.let { openAddr(it, page!!.path, fresh = true) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Share,
                        stringResource(R.string.sites_share),
                        tint = c.textSecondary,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(18.dp)
                            .clickable { addr?.let { share(it.display) } },
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
                            .clickable { addr?.let { openAddr(it, p) } }
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

        // ── the page, the start screen, or what went wrong ───────────────
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
                    // Three lists, in this order, each only when it has
                    // something in it (founder, 02.09). A site shows once:
                    // what is pinned is not repeated among the recents, and
                    // neither is repeated in the catalogue.
                    val pinned = catalogue.filter { it.featured }
                        .map { SiteAddress.of(it.name, ownHost, ownHost) to it.title }
                    val shown = pinned.map { it.first.pinKey }.toMutableSet()
                    val recent = recents
                        .map { SiteAddress.of(it.name, it.host, ownHost) to it }
                        .filter { shown.add(it.first.pinKey) }
                    val rest = catalogue
                        .map { SiteAddress.of(it.name, ownHost, ownHost) to it.title }
                        .filter { shown.add(it.first.pinKey) }
                    if (pinned.isNotEmpty()) {
                        SectionLabel(stringResource(R.string.sites_pinned))
                        pinned.forEach { (a, title) ->
                            SiteRow(a, title, marks[a.pinKey], onOpen = { openAddr(a) }, onShare = { share(a.display) })
                        }
                    }
                    if (recent.isNotEmpty()) {
                        SectionLabel(stringResource(R.string.sites_recents))
                        recent.forEach { (a, e) ->
                            SiteRow(
                                a, e.title, marks[a.pinKey],
                                onOpen = { openAddr(a) },
                                onShare = { share(a.display) },
                                onRemove = { SiteRecents.remove(e); recents = SiteRecents.list() },
                            )
                        }
                    }
                    if (rest.isNotEmpty()) {
                        SectionLabel(stringResource(R.string.sites_catalogue))
                        rest.forEach { (a, title) ->
                            SiteRow(a, title, marks[a.pinKey], onOpen = { openAddr(a) }, onShare = { share(a.display) })
                        }
                    }
                }

                else -> LockedWebView(
                    html = p!!.html,
                    // A page, not any file the manifest signs: a thumbnail
                    // links its full-size photograph, and opening that decoded
                    // its bytes as text and painted the rubble.
                    onPage = { path -> if (isPagePath(path)) addr?.let { openAddr(it, path) } },
                    // ⚠ A name with no island in it belongs to the island THIS
                    // page came from, the way a bare name in a web page belongs
                    // to the site's own zone. Resolved against the reader's
                    // island instead, an author on the flagship writing
                    // `e2ee.rcq` sent every reader on another island to
                    // whoever holds that name over there.
                    onSite = { link ->
                        val here = addr
                        val bare = SiteAddress.parse(link.address, here?.host ?: ownHost)
                        val page = link.page?.takeIf { isPagePath(it) } ?: "index.html"
                        if (here != null && bare != null && bare.host == here.host) {
                            openAddr(SiteAddress.of(bare.name, here.host, ownHost), page)
                        } else {
                            open(link.address, page)
                        }
                    },
                )
            }
        }
    }
}

/** The small uppercase heading over each list of the start screen. */
/**
 * What may be opened as a page. The manifest signs pictures and stylesheets
 * too, and neither is a page: decoded as text and parsed as HTML they paint a
 * screen of rubble, with the address bar claiming the site is showing
 * `photo.jpg`.
 */
private fun isPagePath(path: String): Boolean =
    path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true)

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(
        text.uppercase(),
        color = RcqTheme.colors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * One site in a list: its mark, its address, its title, and at the right a
 * share glyph (report #852) and, on a recent, the cross that forgets it.
 */
@Composable
private fun SiteRow(
    a: SiteAddress,
    title: String?,
    mark: ImageBitmap?,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val c = RcqTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
    ) {
        if (mark != null) {
            androidx.compose.foundation.Image(
                bitmap = mark,
                contentDescription = null,
                modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            // Not a placeholder waiting for a picture: most sites will never
            // have one, and a row that jumps when an icon lands is worse than
            // a row that never had it.
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.bgSecondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    a.name.take(1).uppercase(),
                    color = c.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                a.display,
                color = c.textPrimary,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (!title.isNullOrBlank()) {
                Text(title, color = c.textSecondary, fontSize = 11.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Filled.Share,
            stringResource(R.string.sites_share),
            tint = c.textSecondary,
            modifier = Modifier.size(18.dp).clickable(onClick = onShare),
        )
        if (onRemove != null) {
            Spacer(Modifier.width(14.dp))
            Icon(
                Icons.Filled.Close,
                stringResource(R.string.common_remove),
                tint = c.textSecondary,
                modifier = Modifier.size(18.dp).clickable(onClick = onRemove),
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}

/**
 * Calls [onGone] when the soft keyboard has been dismissed: Back on the
 * keyboard, or its own hide key. Android keeps the field focused through
 * that, so without this the bar would stay in its editing shape with nothing
 * to type on.
 *
 * ⚠ Fires only on the visible-to-hidden edge. At the moment focus lands the
 * keyboard is still on its way in and the inset reads 0, so acting on "hidden"
 * alone would end the editing before it began. Isolated in its own composable
 * so the per-frame IME inset recomposes this and not the whole screen, the way
 * ChatScreen's KeyboardScrollEffect does it.
 */
@Composable
private fun KeyboardGoneEffect(onGone: () -> Unit) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var wasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            wasVisible = true
        } else if (wasVisible) {
            wasVisible = false
            onGone()
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
 * * `shouldOverrideUrlLoading` returns true from BOTH overloads, for EVERY
 *   URL. Without it a tapped `intent://` link is handed to the system and
 *   opens another app - the one hole in a locked WebView that is not about
 *   the web at all. Before returning, it reads the two private schemes the
 *   sanitiser's door pass wrote ([SitesRepository.DOOR_PAGE],
 *   [SitesRepository.DOOR_SITE]) and hands them to [onPage] and [onSite]:
 *   the frame itself still goes nowhere, the screen above opens what was
 *   named. Nothing else in a page has an `href` at all, so nothing else can
 *   reach here.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LockedWebView(
    html: String,
    onPage: (String) -> Unit,
    onSite: (SiteLink) -> Unit,
) {
    // The client is built once in the factory and outlives many
    // recompositions; it must call the CURRENT lambdas, which close over the
    // current address.
    val page by rememberUpdatedState(onPage)
    val site by rememberUpdatedState(onSite)
    fun door(url: String?) {
        url ?: return
        when {
            url.startsWith(SitesRepository.DOOR_PAGE) ->
                page(Uri.decode(url.removePrefix(SitesRepository.DOOR_PAGE)))
            url.startsWith(SitesRepository.DOOR_SITE) ->
                SiteAddress.linkOf(Uri.decode(url.removePrefix(SitesRepository.DOOR_SITE)))?.let { site(it) }
        }
    }
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
                    ): Boolean {
                        door(request?.url?.toString())
                        return true
                    }

                    @Deprecated("Kept for API 23 and below, which still call it")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        door(url)
                        return true
                    }

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
