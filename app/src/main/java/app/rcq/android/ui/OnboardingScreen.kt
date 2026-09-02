package app.rcq.android.ui

import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.data.LanguageManager
import app.rcq.android.model.UserStatus
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.launch

/** Hero artwork for an onboarding page. */
private sealed interface Hero {
    data object Logo : Hero
    data class Sym(val icon: ImageVector) : Hero
    data object StatusRow : Hero
}

/** [relayLink] hangs "What is a relay?" under the body. Only the relays page
 *  uses it: naming the relay is the point, so the word has to come with a way
 *  to find out what it means. */
private data class OnbPage(val kicker: Int, val title: Int, val body: Int, val hero: Hero, val relayLink: Boolean = false)

/**
 * First-run onboarding — a swipeable 6-page deck matching the iOS
 * OnboardingView: Skip (top-left) jumps to the end; the language pill
 * (top-right) switches the UI language live; on the last page the
 * top-left slot becomes a server picker. "Get started" mints the account
 * on the chosen server.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OnboardingScreen(onStart: (String?) -> Unit, onRestore: () -> Unit = {}) {
    val c = RcqTheme.colors
    val activity = LocalContext.current as? Activity
    val currentLang by LanguageManager.current.collectAsState()
    val scope = rememberCoroutineScope()

    val pages = listOf(
        OnbPage(R.string.onboard_welcome_kicker, R.string.onboard_welcome_title, R.string.onboard_welcome_body, Hero.Logo),
        OnbPage(R.string.onboard_anon_kicker, R.string.onboard_anon_title, R.string.onboard_anon_body, Hero.Sym(Icons.Filled.Tag)),
        OnbPage(R.string.onboard_mesh_kicker, R.string.onboard_mesh_title, R.string.onboard_mesh_body, Hero.Sym(Icons.Filled.SettingsInputAntenna)),
        OnbPage(R.string.onboard_chat_kicker, R.string.onboard_chat_title, R.string.onboard_chat_body, Hero.Sym(Icons.Filled.Lock)),
        OnbPage(R.string.onboard_pin_kicker, R.string.onboard_pin_title, R.string.onboard_pin_body, Hero.Sym(Icons.Filled.Shield)),
        OnbPage(R.string.onboard_federation_kicker, R.string.onboard_federation_title, R.string.onboard_federation_body, Hero.Sym(Icons.Filled.Hub)),
        OnbPage(R.string.onboard_relay_kicker, R.string.onboard_relay_title, R.string.onboard_relay_body, Hero.Sym(Icons.Filled.VpnLock), relayLink = true),
    )
    val pager = rememberPagerState(pageCount = { pages.size })
    val lastPage = pager.currentPage == pages.size - 1

    var server by remember { mutableStateOf(RcqApi.DEFAULT_HOST) }
    val ctx = LocalContext.current
    // Network check, available before an account exists (see below).
    var auditing by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf<app.rcq.android.net.NetworkAudit.Report?>(null) }
    var showServer by remember { mutableStateOf(false) }
    var showLang by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.bgPrimary).systemBarsPadding()) {
        // Top bar: Skip / server pill (left), language pill (right).
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lastPage) {
                Row(
                    Modifier.clip(RoundedCornerShape(percent = 50)).background(c.bgSecondary)
                        .clickable { showServer = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Dns, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                    Text(serverHostLabel(server), color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.Filled.ExpandMore, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                }
            } else {
                Text(
                    stringResource(R.string.onboard_cta_skip), color = c.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { pager.animateScrollToPage(pages.size - 1) } }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(percent = 50)).background(c.bgSecondary)
                    .clickable { showLang = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Language, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                Text(LanguageManager.displayName(currentLang), color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        // An island refused at the trust layer, decided here: the picker above
        // takes `host:8443#fp`, and before this the accept button of §5.2 only
        // ever existed on a main screen that does not exist yet.
        IslandTrustNotices()

        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { idx ->
            PageContent(pages[idx])
        }

        // Page dots.
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 9.dp else 7.dp).clip(CircleShape).background(if (i == pager.currentPage) c.accent else c.divider))
            }
        }

        CapsuleButton(
            if (lastPage) stringResource(R.string.onboard_cta_start) else stringResource(R.string.onboard_cta_next),
            onClick = {
                if (lastPage) onStart(server)
                else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            },
            modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.onboard_cta_restore),
                color = c.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRestore() }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        // The network check, before there is an account to check it from.
        //
        // It used to live in Settings only, which a person reaches by getting
        // in — and the person we most need a measurement from is exactly the
        // one who cannot. On a filtered network registration is the FIRST
        // thing that fails, and until now the app answered that with a spinner
        // and nothing else. Deliberately quiet: a line of text under Restore,
        // invisible to anyone whose network works.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                if (auditing) stringResource(R.string.diag_audit_running) else stringResource(R.string.onboard_cta_netcheck),
                color = c.textSecondary, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !auditing) {
                        auditing = true; audit = null
                        scope.launch {
                            audit = withContext(Dispatchers.IO) {
                                runCatching { app.rcq.android.net.NetworkAudit.run(server) }.getOrNull()
                            }
                            auditing = false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        audit?.let { a ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(a.compact, color = c.textMono, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.onboard_netcheck_send),
                    color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("RCQ network audit", a.compact))
                        Toast.makeText(ctx, ctx.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
                    }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showServer) {
        ServerPickerDialog(server, onPick = { server = it; showServer = false }, onDismiss = { showServer = false })
    }
    if (showLang) {
        LanguagePickerDialog(currentLang, onPick = { code -> showLang = false; activity?.let { LanguageManager.set(it, code) } }, onDismiss = { showLang = false })
    }
}

@Composable
private fun PageContent(p: OnbPage) {
    val c = RcqTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (val h = p.hero) {
            is Hero.Logo -> Image(painterResource(R.drawable.rcq_logo), "RCQ", modifier = Modifier.size(120.dp))
            is Hero.Sym -> Box(
                Modifier.size(140.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(h.icon, null, tint = c.accent, modifier = Modifier.size(64.dp)) }
            is Hero.StatusRow -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(UserStatus.ONLINE, UserStatus.AWAY, UserStatus.DND, UserStatus.INVISIBLE, UserStatus.OFFLINE)
                    .forEach { StatusIcon(it, size = 44.dp) }
            }
        }
        Spacer(Modifier.height(36.dp))
        Text(stringResource(p.kicker), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(p.title), color = c.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(p.body), color = c.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (p.relayLink) {
            Spacer(Modifier.height(12.dp))
            RelayLearnMore()
        }
    }
}

/** Bare host shown on the server pill ("api.rcq.app" for the default). */
private fun serverHostLabel(server: String): String =
    server.ifBlank { RcqApi.DEFAULT_HOST }

/// The island picker, now one shared view (see [IslandPickerSheet]): the
/// catalogue as cards you swipe through, with typing an address kept one tap
/// away for self-hosters. Onboarding and the in-app "add a server" flow show
/// the same thing.
@Composable
private fun ServerPickerDialog(server: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    IslandPickerSheet(current = server, onPick = onPick, onDismiss = onDismiss)
}

@Suppress("unused")
@Composable
private fun LegacyServerPickerDialog(server: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    var draft by remember { mutableStateOf(server) }
    RcqSheet(onDismiss = onDismiss, title = stringResource(R.string.onboard_server_label)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgPrimary).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (draft.isEmpty()) Text("server host", color = c.textSecondary, fontSize = 14.sp)
                BasicTextField(
                    value = draft, onValueChange = { draft = it }, singleLine = true,
                    textStyle = TextStyle(color = c.textPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Default is the public RCQ server. Point at an organisation's island or your own self-host.", color = c.textSecondary, fontSize = 11.sp)
            Text("Reset to default", color = c.accent, fontSize = 13.sp, modifier = Modifier.clickable { draft = RcqApi.DEFAULT_HOST })
        }
        SheetGap(16)
        CapsuleButton("Use", modifier = Modifier.fillMaxWidth()) { onPick(draft.trim()) }
        Text(
            "Cancel", color = c.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss).padding(vertical = 14.dp),
        )
    }
}

@Composable
private fun LanguagePickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    RcqSheet(onDismiss = onDismiss, title = stringResource(R.string.onboard_language)) {
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(LanguageManager.available, key = { it.code }) { lang ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(lang.code) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(lang.nativeName, color = c.textPrimary, fontSize = 15.sp)
                        if (lang.englishName != lang.nativeName) Text(lang.englishName, color = c.textSecondary, fontSize = 12.sp)
                    }
                    if (lang.code == current) Icon(Icons.Filled.Check, null, tint = c.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(
            "Close", color = c.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss).padding(vertical = 14.dp),
        )
    }
}
