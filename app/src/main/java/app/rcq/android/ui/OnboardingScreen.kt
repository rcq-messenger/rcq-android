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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private data class OnbPage(val kicker: Int, val title: Int, val body: Int, val hero: Hero)

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
        OnbPage(R.string.onboard_relay_kicker, R.string.onboard_relay_title, R.string.onboard_relay_body, Hero.Sym(Icons.Filled.VpnLock)),
    )
    val pager = rememberPagerState(pageCount = { pages.size })
    val lastPage = pager.currentPage == pages.size - 1

    var server by remember { mutableStateOf(RcqApi.DEFAULT_HOST) }
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
    }
}

/** Bare host shown on the server pill ("api.rcq.app" for the default). */
private fun serverHostLabel(server: String): String =
    server.ifBlank { RcqApi.DEFAULT_HOST }

@Composable
private fun ServerPickerDialog(server: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    var draft by remember { mutableStateOf(server) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.onboard_server_label), color = c.textPrimary) },
        text = {
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
        },
        confirmButton = { TextButton(onClick = { onPick(draft.trim()) }) { Text("Use", color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.textSecondary) } },
    )
}

@Composable
private fun LanguagePickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.onboard_language), color = c.textPrimary) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(LanguageManager.supported, key = { it.code }) { lang ->
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
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = c.textSecondary) } },
    )
}
