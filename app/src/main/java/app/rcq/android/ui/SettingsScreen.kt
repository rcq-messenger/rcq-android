package app.rcq.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.security.BiometricGate
import app.rcq.android.data.LanguageManager
import app.rcq.android.data.LocalStores
import app.rcq.android.net.MultihomeStore
import app.rcq.android.net.ContactRelayStore
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.launch

/** Sub-screens inside Settings (kept self-contained, no nav graph). */
private enum class SettingsRoute { ROOT, PROFILE, PRIVACY, NOTIFICATIONS, BLOCKED, CUSTOM_SERVER, SOUNDS, LANGUAGE, APP_ICON, CHAT_BG, PIN_CODES, DIAGNOSTICS, RECOVERY_PHRASE, UIN_SHOP, LINKED_DEVICES, BACKUP_ISLAND }

@Composable
internal fun SettingsScreen(
    session: Session,
    uin: Int,
    onBack: () -> Unit,
    onBurned: (Int?) -> Unit,
    onMigrated: (Int) -> Unit,
    // Deep-link: open straight on Network diagnostics (the Home overflow menu
    // entry). Back from it then closes Settings rather than landing in Privacy.
    openDiagnostics: Boolean = false,
) {
    var route by remember { mutableStateOf(if (openDiagnostics) SettingsRoute.DIAGNOSTICS else SettingsRoute.ROOT) }
    // System-back parity with the in-screen ← arrow: pop ONE settings level
    // instead of letting back fall through to the activity (which dumped the
    // user straight out to the chat list). At ROOT the handler is disabled so
    // back bubbles up to leave Settings as before.
    BackHandler(enabled = route != SettingsRoute.ROOT) {
        // Diagnostics opened directly from Home → back closes Settings.
        if (openDiagnostics && route == SettingsRoute.DIAGNOSTICS) { onBack(); return@BackHandler }
        route = when (route) {
            SettingsRoute.DIAGNOSTICS, SettingsRoute.CUSTOM_SERVER -> SettingsRoute.PRIVACY
            else -> SettingsRoute.ROOT
        }
    }
    when (route) {
        SettingsRoute.ROOT -> SettingsRoot(
            session, uin,
            onBack = onBack,
            onBurned = onBurned,
            onMigrated = onMigrated,
            onOpen = { route = it },
        )
        SettingsRoute.PROFILE -> ProfileEditScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.PRIVACY -> PrivacyScreen(
            session,
            onOpenCustomServer = { route = SettingsRoute.CUSTOM_SERVER },
            onOpenDiagnostics = { route = SettingsRoute.DIAGNOSTICS },
        ) { route = SettingsRoute.ROOT }
        // Back from Diagnostics returns to Privacy (where it was opened from),
        // not the Settings root (tester #1) — unless we deep-linked here from
        // Home, in which case back closes Settings entirely.
        SettingsRoute.DIAGNOSTICS -> DiagnosticsScreen(session) {
            if (openDiagnostics) onBack() else route = SettingsRoute.PRIVACY
        }
        SettingsRoute.NOTIFICATIONS -> NotificationsScreen { route = SettingsRoute.ROOT }
        SettingsRoute.SOUNDS -> SoundsScreen { route = SettingsRoute.ROOT }
        SettingsRoute.LANGUAGE -> LanguageScreen { route = SettingsRoute.ROOT }
        SettingsRoute.APP_ICON -> AppIconScreen { route = SettingsRoute.ROOT }
        SettingsRoute.CHAT_BG -> ChatBackgroundScreen { route = SettingsRoute.ROOT }
        SettingsRoute.BLOCKED -> BlockedUsersScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.PIN_CODES -> PinCodesScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.RECOVERY_PHRASE -> RecoveryPhraseScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.LINKED_DEVICES -> LinkedDevicesScreen(session) { route = SettingsRoute.ROOT }
        // Promote rebinds the session to another island (new uin) — bubble it
        // up like a migration so the Home header re-registers immediately.
        SettingsRoute.BACKUP_ISLAND -> BackupIslandScreen(session, onPromoted = onMigrated) { route = SettingsRoute.ROOT }
        SettingsRoute.CUSTOM_SERVER -> CustomServerScreen(
            session,
            // Back returns to Privacy (its parent), not the Settings root (tester #1).
            onBack = { route = SettingsRoute.PRIVACY },
            onSwitched = { newUin -> onMigrated(newUin); onBack() },
        )
        SettingsRoute.UIN_SHOP -> UinShopScreen(
            session,
            onBack = { route = SettingsRoute.ROOT },
            // A purchase migrates the account; bubble the new UIN up + close
            // Settings (same flow as the free move / a server switch).
            onMigrated = { newUin -> onMigrated(newUin); onBack() },
        )
    }
}

@Composable
private fun SettingsRoot(
    session: Session,
    uin: Int,
    onBack: () -> Unit,
    onBurned: (Int?) -> Unit,
    onMigrated: (Int) -> Unit,
    onOpen: (SettingsRoute) -> Unit,
) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ownStatus by session.status.collectAsState()
    val themeMode by LocalStores.themeMode.collectAsState()
    val contacts by session.contacts.collectAsState()
    val uinShopEnabled by session.uinShopEnabled.collectAsState()
    var confirmBurn by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmMigrate by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showBugReport by remember { mutableStateOf(false) }
    var bugText by remember { mutableStateOf("") }
    var bugSending by remember { mutableStateOf(false) }
    var bugSent by remember { mutableStateOf(false) }
    // Bug-report attachments (#28): picked photo/video URIs (max 3), shown as
    // thumbnails; sealed + uploaded only on send.
    var bugAttachments by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    val bugPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && bugAttachments.size < 3) bugAttachments = bugAttachments + uri
    }
    // Manual update check from the About sheet (so a "Later"-dismissed update is
    // still reachable, tester #2).
    var updChecking by remember { mutableStateOf(false) }
    var updCheckedEmpty by remember { mutableStateOf(false) }
    var updResult by remember { mutableStateOf<app.rcq.android.net.UpdateChecker.Update?>(null) }
    // Download runs at the process level so it survives closing this dialog.
    val downloadState by app.rcq.android.net.UpdateChecker.downloadState.collectAsState()
    val blockedCount = contacts.count { it.blocked }

    fun copyUin() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("UIN", "$uin"))
        Toast.makeText(context, context.getString(R.string.common_uin_copied), Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_title), onBack)

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // Profile header card — opens the editor.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.bgSecondary)
                    .clickable { onOpen(SettingsRoute.PROFILE) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusIcon(ownStatus, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(session.nickname, color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("#$uin", color = c.textMono, fontSize = 13.sp)
                        Icon(Icons.Filled.ContentCopy, stringResource(R.string.common_copy_uin), tint = c.textSecondary,
                            modifier = Modifier.size(15.dp).clickable { copyUin() })
                    }
                }
                Icon(Icons.Filled.ChevronRight, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.settings_sec_appearance))
            SegmentedTheme(themeMode) { LocalStores.setThemeMode(it) }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.settings_text_size), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            val fontScale by LocalStores.fontScale.collectAsState()
            SegmentedFontScale(fontScale) { LocalStores.setFontScale(it) }
            SectionFooter(stringResource(R.string.settings_foot_appearance))
            Spacer(Modifier.height(12.dp))
            val lang by LanguageManager.current.collectAsState()
            SettingsGroup {
                SettingsRow(Icons.Filled.Language, stringResource(R.string.onboard_language), value = LanguageManager.displayName(lang)) { onOpen(SettingsRoute.LANGUAGE) }
                Divider()
                SettingsRow(Icons.Filled.Apps, stringResource(R.string.settings_row_app_icon)) { onOpen(SettingsRoute.APP_ICON) }
                Divider()
                SettingsRow(Icons.Filled.Wallpaper, stringResource(R.string.settings_row_chat_bg)) { onOpen(SettingsRoute.CHAT_BG) }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.settings_sec_privacy))
            SettingsGroup {
                SettingsRow(Icons.Filled.Lock, stringResource(R.string.settings_row_privacy)) { onOpen(SettingsRoute.PRIVACY) }
                Divider()
                SettingsRow(Icons.Filled.Notifications, stringResource(R.string.settings_row_notifications)) { onOpen(SettingsRoute.NOTIFICATIONS) }
                Divider()
                SettingsRow(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.settings_row_sounds)) { onOpen(SettingsRoute.SOUNDS) }
                Divider()
                SettingsRow(Icons.Outlined.Block, stringResource(R.string.settings_row_blocked), value = if (blockedCount > 0) "$blockedCount" else null) { onOpen(SettingsRoute.BLOCKED) }
                Divider()
                SettingsRow(
                    Icons.Filled.Password,
                    stringResource(R.string.settings_row_pin_codes),
                    value = if (session.pinConfigured) stringResource(R.string.pin_on) else null,
                ) { onOpen(SettingsRoute.PIN_CODES) }
                Divider()
                SettingsRow(Icons.Filled.Key, stringResource(R.string.settings_row_recovery)) { onOpen(SettingsRoute.RECOVERY_PHRASE) }
                Divider()
                SettingsRow(Icons.Filled.Devices, stringResource(R.string.settings_row_linked_devices)) { onOpen(SettingsRoute.LINKED_DEVICES) }
                Divider()
                SettingsRow(Icons.Filled.Dns, stringResource(R.string.settings_row_backup_island)) { onOpen(SettingsRoute.BACKUP_ISLAND) }
            }
            SectionFooter(stringResource(R.string.settings_foot_privacy))

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.settings_sec_history))
            SettingsGroup {
                SettingsRow(Icons.Filled.DeleteSweep, stringResource(R.string.settings_row_clear_history), destructive = true) { confirmClear = true }
            }
            SectionFooter(stringResource(R.string.settings_foot_history))

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.settings_sec_about))
            SettingsGroup {
                SettingsRow(Icons.Filled.Info, stringResource(R.string.settings_row_about), value = appVersion(context)) { showAbout = true }
                Divider()
                // Hand the APK to a friend offline — the only way to install RCQ
                // first-time when rcq.app is blocked (the relays live inside the
                // app, so a brand-new user can't reach the download otherwise).
                SettingsRow(Icons.Filled.Share, stringResource(R.string.settings_row_share_app)) {
                    app.rcq.android.net.UpdateChecker.shareApk(context)
                }
                Divider()
                SettingsRow(Icons.Filled.BugReport, stringResource(R.string.settings_row_report_bug)) { bugText = ""; bugSent = false; showBugReport = true }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.settings_sec_account))
            // UIN shop — only on servers that advertise it (api.rcq.app);
            // self-host backends report uin_shop=false and the row hides.
            if (uinShopEnabled) {
                SettingsGroup {
                    SettingsRow(Icons.Filled.Sell, stringResource(R.string.settings_row_uin_shop)) { onOpen(SettingsRoute.UIN_SHOP) }
                }
                Text(
                    stringResource(R.string.settings_foot_uin_shop),
                    color = c.textSecondary, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
                    textAlign = TextAlign.Center,
                )
            }
            SettingsGroup {
                SettingsRow(Icons.Filled.Autorenew, stringResource(R.string.settings_row_move_uin)) { if (!migrating) confirmMigrate = true }
            }
            Text(
                stringResource(R.string.cs_move_footer),
                color = c.textSecondary, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
                textAlign = TextAlign.Center,
            )

            SettingsGroup {
                SettingsRow(Icons.Filled.LocalFireDepartment, stringResource(R.string.settings_row_burn), destructive = true) { confirmBurn = true }
            }
            Text(
                stringResource(R.string.cs_burn_footer),
                color = c.textSecondary, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 20.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.cs_clear_title),
            body = stringResource(R.string.cs_clear_body),
            confirm = stringResource(R.string.common_clear), destructive = true,
            onConfirm = { confirmClear = false; session.clearHistory(); Toast.makeText(context, context.getString(R.string.cs_history_cleared), Toast.LENGTH_SHORT).show() },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmMigrate) {
        ConfirmDialog(
            title = stringResource(R.string.cs_move_title),
            body = stringResource(R.string.cs_move_body),
            confirm = stringResource(R.string.common_move), destructive = false,
            onConfirm = {
                confirmMigrate = false
                migrating = true
                scope.launch {
                    val newUin = runCatching { session.migrateToNewUin() }.getOrNull()
                    migrating = false
                    if (newUin != null) {
                        Toast.makeText(context, context.getString(R.string.cs_moved_toast, newUin), Toast.LENGTH_LONG).show()
                        onMigrated(newUin)
                    } else {
                        Toast.makeText(context, context.getString(R.string.cs_move_failed), Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { confirmMigrate = false },
        )
    }
    if (confirmBurn) {
        ConfirmDialog(
            title = stringResource(R.string.cs_burn_title),
            body = stringResource(R.string.cs_burn_body),
            confirm = stringResource(R.string.cs_burn_cta), destructive = true,
            onConfirm = { confirmBurn = false; scope.launch { onBurned(session.burnAccount()) } },
            onDismiss = { confirmBurn = false },
        )
    }
    if (showBugReport) {
        AlertDialog(
            onDismissRequest = { showBugReport = false },
            containerColor = c.bgSecondary,
            confirmButton = {
                if (bugSent) {
                    TextButton(onClick = { showBugReport = false }) { Text(stringResource(R.string.common_done), color = c.accent) }
                } else {
                    TextButton(
                        enabled = bugText.trim().length >= 5 && !bugSending,
                        onClick = {
                            bugSending = true
                            scope.launch {
                                // Seal + upload each picked attachment first
                                // (images compressed, videos sent raw ≤ 50MB).
                                val atts = withContext(Dispatchers.IO) {
                                    bugAttachments.mapNotNull { uri ->
                                        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                                        val bytes = if (mime.startsWith("image/")) {
                                            compressImageFor(context, uri)
                                        } else {
                                            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                                        } ?: return@mapNotNull null
                                        val outMime = if (mime.startsWith("image/")) "image/jpeg" else mime
                                        session.uploadReportAttachment(bytes, outMime)
                                    }
                                }
                                val ok = session.submitBugReport(bugText.trim(), atts)
                                bugSending = false
                                if (ok) bugSent = true
                            }
                        },
                    ) { Text(stringResource(if (bugSending) R.string.bug_report_sending else R.string.bug_report_send), color = c.accent) }
                }
            },
            dismissButton = { if (!bugSent) TextButton(onClick = { showBugReport = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
            icon = { Icon(Icons.Filled.BugReport, null, tint = c.accent) },
            title = { Text(stringResource(R.string.settings_row_report_bug), color = c.textPrimary) },
            text = {
                if (bugSent) {
                    Text(stringResource(R.string.bug_report_sent), color = c.textSecondary, fontSize = 14.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.bug_report_hint), color = c.textSecondary, fontSize = 12.sp)
                        OutlinedTextField(
                            value = bugText,
                            onValueChange = { if (it.length <= 1000) bugText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            placeholder = { Text(stringResource(R.string.bug_report_placeholder), color = c.textSecondary) },
                        )
                        // Attachments (#28): up to 3 photos/videos, thumbnails
                        // with a remove (×); only uploaded on send.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            bugAttachments.forEach { uri ->
                                Box {
                                    AttachThumb(uri, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                                    Icon(
                                        Icons.Filled.Close, stringResource(R.string.common_cancel), tint = Color.White,
                                        modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                            .clickable { bugAttachments = bugAttachments - uri },
                                    )
                                }
                            }
                            if (bugAttachments.size < 3 && !bugSending) {
                                Box(
                                    Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(c.bgPrimary)
                                        .clickable { bugPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Filled.Add, stringResource(R.string.bug_report_attach), tint = c.accent, modifier = Modifier.size(22.dp)) }
                            }
                        }
                    }
                }
            },
        )
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            containerColor = c.bgSecondary,
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.common_done), color = c.accent) } },
            title = { Text("RCQ", color = c.textPrimary) },
            text = {
                // Scrollable: the update notes can be a long bilingual
                // paragraph that otherwise pushes the "Download and install"
                // button below the (non-scrolling) AlertDialog viewport, so the
                // user saw "update available" but never the install action.
                val aboutScroll = rememberScrollState()
                val downloading = downloadState is app.rcq.android.net.UpdateChecker.DownloadState.Active
                // When a download starts, the progress bar + hint live BELOW the
                // notes — scroll there so the user sees the status (beta report).
                LaunchedEffect(downloading) {
                    if (downloading) aboutScroll.animateScrollTo(aboutScroll.maxValue)
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .verticalScroll(aboutScroll)
                        .simpleVerticalScrollbar(aboutScroll, c.textSecondary),
                ) {
                    Text(stringResource(R.string.cs_about_tagline), color = c.textSecondary, fontSize = 14.sp)
                    Text(stringResource(R.string.cs_about_version, appVersion(context)), color = c.textMono, fontSize = 13.sp)
                    Text(stringResource(R.string.cs_about_features), color = c.textSecondary, fontSize = 12.sp)
                    Divider()
                    val active = downloadState as? app.rcq.android.net.UpdateChecker.DownloadState.Active
                    when {
                        active != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (active.progress < 0f) androidx.compose.material3.LinearProgressIndicator(color = c.accent, modifier = Modifier.fillMaxWidth())
                            else androidx.compose.material3.LinearProgressIndicator(progress = { active.progress }, color = c.accent, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.update_downloading_pct, (active.progress.coerceAtLeast(0f) * 100).toInt()), color = c.textSecondary, fontSize = 13.sp)
                            Text(stringResource(R.string.update_bg_hint), color = c.textSecondary, fontSize = 11.sp)
                            // Cancel keeps the partial download for a later resume (tester #39).
                            TextButton(onClick = { app.rcq.android.net.UpdateChecker.cancelDownload() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                Text(stringResource(R.string.update_cancel), color = c.accent, fontSize = 13.sp)
                            }
                        }
                        downloadState is app.rcq.android.net.UpdateChecker.DownloadState.Failed -> Text(
                            stringResource(R.string.update_failed),
                            color = Color(0xFFE5484D), fontSize = 13.sp,
                            modifier = updResult?.let { up -> Modifier.clickable { app.rcq.android.net.UpdateChecker.startDownload(context, up) } } ?: Modifier,
                        )
                        updResult != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.update_available_short, updResult!!.versionName), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (updResult!!.notes.isNotBlank()) Text(updResult!!.notes, color = c.textSecondary, fontSize = 12.sp)
                            // Prominent primary action (tester #28: "where do I download?").
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.accent)
                                    .clickable { app.rcq.android.net.UpdateChecker.startDownload(context, updResult!!) }
                                    .padding(vertical = 11.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(R.string.update_install), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        updChecking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.CircularProgressIndicator(color = c.accent, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.update_checking), color = c.textSecondary, fontSize = 13.sp)
                        }
                        updCheckedEmpty -> Text(stringResource(R.string.update_uptodate), color = c.textSecondary, fontSize = 13.sp)
                        else -> TextButton(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), onClick = {
                            updChecking = true; updCheckedEmpty = false
                            scope.launch {
                                val u = app.rcq.android.net.UpdateChecker.check()
                                updResult = u; updCheckedEmpty = (u == null); updChecking = false
                            }
                        }) { Text(stringResource(R.string.update_check), color = c.accent) }
                    }
                }
            },
        )
    }
}

// ── Profile editor ───────────────────────────────────────────────────

@Composable
internal fun ProfileEditScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ownUin = session.uin ?: 0
    val ownStatus by session.status.collectAsState()
    val profileViews by app.rcq.android.data.VisitStore.recentViews.collectAsState()
    var nickname by remember { mutableStateOf(session.nickname) }
    var statusMessage by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf<String?>(null) }
    var age by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var interests by remember { mutableStateOf("") }
    var homepage by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        session.loadProfile()?.let { p ->
            nickname = p.nickname ?: nickname
            statusMessage = p.status_message ?: ""
            firstName = p.first_name ?: ""
            lastName = p.last_name ?: ""
            gender = p.gender
            age = p.age?.toString() ?: ""
            city = p.city ?: ""
            country = p.country ?: ""
            about = p.about ?: ""
            interests = p.interests.joinToString(", ")
            homepage = p.homepage ?: ""
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.pe_title), onBack, trailing = {
            TextButton(enabled = !saving && nickname.isNotBlank(), onClick = {
                saving = true
                scope.launch {
                    session.updateProfile(RcqApi.UpdateMeBody(
                        nickname = nickname.trim(),
                        status_message = statusMessage.trim(),
                        first_name = firstName.trim(),
                        last_name = lastName.trim(),
                        gender = gender,
                        age = age.toIntOrNull(),
                        city = city.trim(),
                        country = country.trim(),
                        about = about.trim(),
                        interests = interests.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        homepage = homepage.trim(),
                    ))
                    saving = false
                    Toast.makeText(context, context.getString(R.string.pe_saved), Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }) { Text(stringResource(R.string.common_save), color = if (nickname.isNotBlank()) c.accent else c.textSecondary) }
        })

        val backupHomes by session.backupHomes.collectAsState()
        fun copyText(label: String, value: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(context, context.getString(R.string.common_uin_copied), Toast.LENGTH_SHORT).show()
        }
        fun shareText(value: String) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, value)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.qr_share)))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Identity header card (avatar + UIN), like the iOS profile.
            // The number is copyable + shareable here too (beta report).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusIcon(ownStatus, size = 48.dp)
                Column {
                    Text(nickname.ifBlank { "—" }, color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("#$ownUin", color = c.textMono, fontSize = 13.sp)
                        Icon(Icons.Filled.ContentCopy, stringResource(R.string.common_copy_uin), tint = c.textSecondary,
                            modifier = Modifier.size(15.dp).clickable { copyText("UIN", "$ownUin") })
                        Icon(Icons.Filled.Share, stringResource(R.string.qr_share), tint = c.textSecondary,
                            modifier = Modifier.size(15.dp).clickable {
                                shareText(context.getString(R.string.qr_share_text, "$ownUin", session.contactLinks().second))
                            })
                    }
                }
            }
            // Backup-island addresses: copyable/shareable too (a self-hoster's
            // number there can differ from the flagship one).
            if (backupHomes.isNotEmpty()) {
                SettingsGroup {
                    backupHomes.forEachIndexed { index, h ->
                        if (index > 0) Divider()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(h.host, color = c.textPrimary, fontSize = 13.sp)
                                Text(stringResource(R.string.backup_island_row_uin, h.uin), color = c.textSecondary, fontSize = 12.sp)
                            }
                            Icon(Icons.Filled.ContentCopy, stringResource(R.string.common_copy_uin), tint = c.textSecondary,
                                modifier = Modifier.size(16.dp).clickable { copyText("UIN", "${h.uin}@${h.host}") })
                            Icon(Icons.Filled.Share, stringResource(R.string.qr_share), tint = c.textSecondary,
                                modifier = Modifier.size(16.dp).clickable {
                                    shareText(context.getString(R.string.qr_share_text, "${h.uin}@${h.host}", "https://${h.host}/u/${h.uin}"))
                                })
                        }
                    }
                }
            }
            // Profile views (own-profile only; tallied locally from sealed visit pings).
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bgSecondary).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pe_views_title), color = c.textPrimary, fontSize = 15.sp)
                    Text(stringResource(R.string.pe_views_desc), color = c.textSecondary, fontSize = 11.sp)
                }
                Text("$profileViews", color = c.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            Field(stringResource(R.string.pe_nickname), nickname) { nickname = it }
            Field(stringResource(R.string.pe_status_message), statusMessage) { statusMessage = it }
            Field(stringResource(R.string.pe_first_name), firstName) { firstName = it }
            Field(stringResource(R.string.pe_last_name), lastName) { lastName = it }
            SectionLabel(stringResource(R.string.common_gender))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("male" to stringResource(R.string.common_male), "female" to stringResource(R.string.common_female), "other" to stringResource(R.string.common_other)).forEach { (key, label) ->
                    val sel = gender == key
                    Box(
                        Modifier.clip(RoundedCornerShape(percent = 50)).background(if (sel) c.accent else c.bgSecondary)
                            .clickable { gender = if (sel) null else key }.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text(label, color = if (sel) Color.White else c.textSecondary, fontSize = 13.sp) }
                }
            }
            Field(stringResource(R.string.pe_age), age, keyboardDigits = true) { age = it.filter(Char::isDigit).take(3) }
            Field(stringResource(R.string.common_city), city) { city = it }
            Field(stringResource(R.string.common_country), country) { country = it }
            Field(stringResource(R.string.common_about), about, minLines = 3) { about = it }
            Field(stringResource(R.string.pe_interests), interests) { interests = it }
            SectionFooter(stringResource(R.string.pe_interests_hint))
            Field(stringResource(R.string.pe_website), homepage) { homepage = it }
        }
    }
}

// ── Privacy & Network ────────────────────────────────────────────────

@Composable
private fun PrivacyScreen(session: Session, onOpenCustomServer: () -> Unit, onOpenDiagnostics: () -> Unit, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    var lastSeen by remember { mutableStateOf("everyone") }
    var genderVis by remember { mutableStateOf("nobody") }
    var profileVis by remember { mutableStateOf("everyone") }
    var invitePolicy by remember { mutableStateOf("everyone") }
    var receipts by remember { mutableStateOf("everyone") }
    var presencePersistent by remember { mutableStateOf(false) }
    var presenceTtl by remember { mutableStateOf(1440) }
    var hofOptIn by remember { mutableStateOf(false) }
    var hofAvatar by remember { mutableStateOf<String?>(null) }   // data-URI or null
    var hofBusy by remember { mutableStateOf(false) }
    var hofError by remember { mutableStateOf<String?>(null) }
    val screenSec by app.rcq.android.data.LocalStores.screenSecurity.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var obfuscated by remember { mutableStateOf(app.rcq.android.net.SingBoxTransport.isEnabled(context)) }
    val hofPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            hofBusy = true; hofError = null
            val dataUri = withContext(Dispatchers.IO) { hofAvatarDataUri(context, uri) }
            if (dataUri == null) {
                hofError = context.getString(R.string.pv_hof_image_too_large)
            } else {
                val ok = runCatching { session.updateProfile(RcqApi.UpdateMeBody(hof_avatar = dataUri)) }.getOrNull() != null
                if (ok) hofAvatar = dataUri else hofError = context.getString(R.string.pv_hof_image_error)
            }
            hofBusy = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        session.loadProfile()?.let { p ->
            lastSeen = p.last_seen_visibility ?: "everyone"
            genderVis = p.gender_visibility ?: "nobody"
            profileVis = p.profile_visibility ?: "everyone"
            invitePolicy = p.group_invite_policy ?: "everyone"
            receipts = p.read_receipts_visibility ?: "everyone"
            presencePersistent = p.presence_persistent ?: false
            presenceTtl = p.presence_ttl_minutes ?: 1440
            hofOptIn = p.hof_opt_in ?: false
            hofAvatar = p.hof_avatar
            // Seed the local countdown anchor if the feature is on but we have
            // no window yet (enabled on another device, or before this feature
            // existed). Active changes below re-anchor it; passive load never
            // overrides an existing anchor.
            if (presencePersistent && app.rcq.android.data.LocalStores.presenceWindow.value == null) {
                app.rcq.android.data.LocalStores.setPresenceWindow(presenceTtl)
            }
        }
    }

    fun save(body: RcqApi.UpdateMeBody) { scope.launch { runCatching { session.updateProfile(body) } } }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_privacy), onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            VisibilityPicker(stringResource(R.string.pv_last_seen), lastSeen, listOf("everyone", "contacts", "nobody"), stringResource(R.string.pv_last_seen_desc)) { lastSeen = it; save(RcqApi.UpdateMeBody(last_seen_visibility = it)) }

            // Persistent presence + how long it lingers (iOS parity).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.pv_stay_visible), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.pv_stay_visible_desc), color = c.textSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = presencePersistent,
                        onCheckedChange = {
                            presencePersistent = it
                            save(RcqApi.UpdateMeBody(presence_persistent = it))
                            if (it) app.rcq.android.data.LocalStores.setPresenceWindow(presenceTtl)
                            else app.rcq.android.data.LocalStores.clearPresenceWindow()
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                    )
                }
                if (presencePersistent) {
                    val ttls = listOf(30 to "30m", 60 to "1h", 180 to "3h", 480 to "8h", 1440 to "24h")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgSecondary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        ttls.forEach { (mins, label) ->
                            val sel = presenceTtl == mins
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(if (sel) c.accent else Color.Transparent)
                                    .clickable { presenceTtl = mins; save(RcqApi.UpdateMeBody(presence_ttl_minutes = mins)); app.rcq.android.data.LocalStores.setPresenceWindow(mins) }.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = if (sel) Color.White else c.textSecondary, fontSize = 12.sp) }
                        }
                    }
                }
            }

            VisibilityPicker(stringResource(R.string.pv_profile_card), profileVis, listOf("everyone", "contacts", "nobody"), stringResource(R.string.pv_profile_card_desc)) { profileVis = it; save(RcqApi.UpdateMeBody(profile_visibility = it)) }
            VisibilityPicker(stringResource(R.string.common_gender), genderVis, listOf("everyone", "contacts", "nobody"), stringResource(R.string.pv_gender_desc)) { genderVis = it; save(RcqApi.UpdateMeBody(gender_visibility = it)) }
            VisibilityPicker(stringResource(R.string.pv_invite), invitePolicy, listOf("everyone", "contacts", "nobody"), stringResource(R.string.pv_invite_desc)) { invitePolicy = it; save(RcqApi.UpdateMeBody(group_invite_policy = it)) }
            VisibilityPicker(stringResource(R.string.pv_receipts), receipts, listOf("everyone", "contacts", "nobody"), stringResource(R.string.pv_receipts_desc)) { receipts = it; save(RcqApi.UpdateMeBody(read_receipts_visibility = it)) }

            // Block screenshots (device-local; FLAG_SECURE applied by MainActivity).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pv_screen_security), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.pv_screen_security_desc), color = c.textSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = screenSec,
                    onCheckedChange = { app.rcq.android.data.LocalStores.setScreenSecurity(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }

            // Hall of Fame opt-in + optional public avatar. Just consent to be
            // considered; the founder curates who actually appears on rcq.app/hof.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.pv_hall_of_fame), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.pv_hall_of_fame_desc), color = c.textSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = hofOptIn,
                        onCheckedChange = { hofOptIn = it; save(RcqApi.UpdateMeBody(hof_opt_in = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                    )
                }
                if (hofOptIn) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val avatarBytes = remember(hofAvatar) { hofAvatar?.let { decodeDataUriBytes(it) } }
                        Box(Modifier.size(48.dp).clip(CircleShape).background(c.bgSecondary), contentAlignment = Alignment.Center) {
                            if (avatarBytes != null) SafeAnimatedGif(avatarBytes, Modifier.fillMaxSize())
                            else Text(stringResource(R.string.pv_hof_image_hint_short), color = c.textSecondary, fontSize = 9.sp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(if (hofAvatar != null) R.string.pv_hof_change_image else R.string.pv_hof_add_image),
                                color = if (hofBusy) c.textSecondary else c.accent, fontSize = 13.sp,
                                modifier = Modifier.clickable(enabled = !hofBusy) { hofPicker.launch("image/*") },
                            )
                            if (hofAvatar != null) {
                                Text(
                                    stringResource(R.string.pv_hof_remove_image),
                                    color = c.textSecondary, fontSize = 13.sp,
                                    modifier = Modifier.clickable(enabled = !hofBusy) {
                                        scope.launch {
                                            hofBusy = true
                                            val ok = runCatching { session.updateProfile(RcqApi.UpdateMeBody(hof_avatar = "")) }.getOrNull() != null
                                            if (ok) hofAvatar = null
                                            hofBusy = false
                                        }
                                    },
                                )
                            }
                        }
                    }
                    hofError?.let { Text(it, color = c.statusBusy, fontSize = 12.sp) }
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.pv_network))
            SettingsGroup {
                val host = session.currentServer
                SettingsRow(
                    Icons.Filled.Dns,
                    stringResource(R.string.pv_custom_server),
                    value = if (host == RcqApi.DEFAULT_HOST) stringResource(R.string.pv_default) else host,
                    onClick = onOpenCustomServer,
                )
                SettingsRow(
                    Icons.Filled.NetworkCheck,
                    stringResource(R.string.diag_title),
                    onClick = onOpenDiagnostics,
                )
            }

            // Obfuscated connection (embedded sing-box). Off by default; takes
            // effect on next launch. Honest framing as "connection reliability".
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pv_obfuscated), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.pv_obfuscated_desc), color = c.textSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = obfuscated,
                    onCheckedChange = { obfuscated = it; app.rcq.android.net.SingBoxTransport.setEnabled(context, it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }

            // Onion routing (M3, experimental). One switch for the user: turning
            // it on ALSO engages the obfuscated connection, because onion routes
            // THROUGH the obfuscated tunnel and can't work without it. So the
            // user never has to think about two toggles.
            var onion by remember { mutableStateOf(app.rcq.android.net.SingBoxTransport.isOnionOptIn(context)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pv_onion), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.pv_onion_desc), color = c.textSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = onion,
                    onCheckedChange = {
                        onion = it
                        app.rcq.android.net.SingBoxTransport.setOnionOptIn(context, it)
                        // Onion implies the protected connection. Flip it on too
                        // so this single switch is all the user touches.
                        if (it && !obfuscated) {
                            obfuscated = true
                            app.rcq.android.net.SingBoxTransport.setEnabled(context, true)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }

            // In-chat bridge sharing: relays a contact shared / you imported,
            // augmenting the transport pool. See RCQ/docs/bridge-sharing-design.md.
            var relayImportOpen by remember { mutableStateOf(false) }
            var sharedRelays by remember { mutableStateOf(ContactRelayStore.list()) }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.relay_shared_section), color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (sharedRelays.isEmpty()) {
                Text(stringResource(R.string.relay_shared_empty), color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            } else {
                sharedRelays.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.relay.proto.uppercase()} · ${e.relay.server}:${e.relay.port}", color = c.textPrimary, fontSize = 13.sp)
                            Text(
                                if (e.fromUin == 0) stringResource(R.string.relay_shared_imported)
                                else stringResource(R.string.relay_shared_from, e.fromUin),
                                color = c.textSecondary, fontSize = 11.sp,
                            )
                        }
                        TextButton(onClick = {
                            ContactRelayStore.remove(e.relay.tag)
                            sharedRelays = ContactRelayStore.list()
                        }) { Text(stringResource(R.string.relay_shared_remove), color = c.accent, fontSize = 12.sp) }
                    }
                }
            }
            TextButton(onClick = { relayImportOpen = true }) {
                Text(stringResource(R.string.relay_import_title), color = c.accent, fontSize = 13.sp)
            }

            if (relayImportOpen) {
                var token by remember { mutableStateOf("") }
                var err by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { relayImportOpen = false },
                    containerColor = c.bgSecondary,
                    title = { Text(stringResource(R.string.relay_import_title), color = c.textPrimary) },
                    text = {
                        Column {
                            Text(stringResource(R.string.relay_import_body), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it; err = false },
                                placeholder = { Text("rcq-relay://...", color = c.textSecondary) },
                                isError = err,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (err) Text(stringResource(R.string.relay_import_bad), color = c.statusBusy, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val r = ContactRelayStore.relayFromToken(token)
                            if (r == null) {
                                err = true
                            } else {
                                ContactRelayStore.add(r, 0, null)
                                sharedRelays = ContactRelayStore.list()
                                relayImportOpen = false
                            }
                        }) { Text(stringResource(R.string.relay_import_add), color = c.accent) }
                    },
                    dismissButton = { TextButton(onClick = { relayImportOpen = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
                )
            }
        }
    }
}

/** Connection diagnostics (iOS ConnectionDiagnosticsView parity) — the tool
 *  for debugging "why won't it connect" on a censored network: shows the live
 *  route (direct vs tunnel), whether the backend is reachable directly and via
 *  the current route, the real-time channel state, and which relay list is in
 *  use. Re-runnable. */
@Composable
private fun DiagnosticsScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val transport = app.rcq.android.net.SingBoxTransport
    val store = app.rcq.android.net.RelayConfigStore
    val connected by session.connected.collectAsState()

    var running by remember { mutableStateOf(true) }
    var directOk by remember { mutableStateOf<Boolean?>(null) }
    var routeOk by remember { mutableStateOf<Boolean?>(null) }

    fun run() {
        running = true; directOk = null; routeOk = null
        scope.launch {
            val host = session.currentServer
            directOk = withContext(Dispatchers.IO) { transport.probeDirect(host) }
            routeOk = withContext(Dispatchers.IO) { transport.probeCurrentRoute(host) }
            running = false
        }
    }
    LaunchedEffect(Unit) { run() }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.diag_title), onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup {
                DiagRow(
                    stringResource(R.string.diag_transport),
                    if (transport.isActive) stringResource(R.string.diag_mode_tunnel) else stringResource(R.string.diag_mode_direct),
                    ok = if (transport.isActive) null else true,
                )
                DiagRow(
                    stringResource(R.string.diag_backend_direct),
                    statusText(directOk, stringResource(R.string.diag_reachable), stringResource(R.string.diag_blocked)),
                    ok = directOk,
                )
                DiagRow(
                    stringResource(R.string.diag_backend_route),
                    statusText(routeOk, stringResource(R.string.diag_reachable), stringResource(R.string.diag_unreachable)),
                    ok = routeOk,
                )
                DiagRow(
                    stringResource(R.string.diag_ws),
                    if (connected) stringResource(R.string.diag_connected) else stringResource(R.string.diag_disconnected),
                    ok = connected,
                )
                DiagRow(
                    stringResource(R.string.diag_relays),
                    if (store.usingRemote()) stringResource(R.string.diag_relays_remote, store.relayCount(), store.version ?: 0)
                    else stringResource(R.string.diag_relays_bundled, store.relayCount()),
                    ok = null,
                )
            }
            SectionFooter(stringResource(R.string.diag_footer))
            CapsuleButton(stringResource(R.string.diag_run_again), enabled = !running) { run() }
        }
    }
}

/** A label + a status value tinted by [ok] (true=green, false=red, null=neutral). */
@Composable
private fun DiagRow(label: String, value: String, ok: Boolean?) {
    val c = RcqTheme.colors
    val tint = when (ok) {
        true -> Color(0xFF4CAF50)
        false -> Color(0xFFE5484D)
        null -> c.textSecondary
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun statusText(ok: Boolean?, yes: String, no: String): String = when (ok) {
    true -> yes
    false -> no
    null -> "…"
}

@Composable
private fun SoundsScreen(onBack: () -> Unit) {
    val c = RcqTheme.colors
    val masterOn by LocalStores.soundMaster.collectAsState()
    val msgOn by LocalStores.soundMessages.collectAsState()
    val presOn by LocalStores.soundPresence.collectAsState()
    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_sounds), onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingToggleRow(stringResource(R.string.snd_master_title), stringResource(R.string.snd_master_desc), masterOn) { LocalStores.setSoundMaster(it) }
            SettingToggleRow(stringResource(R.string.snd_message_title), stringResource(R.string.snd_message_desc), msgOn, enabled = masterOn) { LocalStores.setSoundMessages(it) }
            SettingToggleRow(stringResource(R.string.snd_presence_title), stringResource(R.string.snd_presence_desc), presOn, enabled = masterOn) { LocalStores.setSoundPresence(it) }
            SectionFooter(stringResource(R.string.snd_footer))
        }
    }
}

/** A thin always-on vertical scrollbar thumb for a [ScrollState] column, so a
 *  user can SEE there's more content below the fold (Compose has no built-in;
 *  beta report on the update dialog). No-op when nothing scrolls. */
private fun Modifier.simpleVerticalScrollbar(state: ScrollState, color: Color, width: Dp = 3.dp): Modifier =
    drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max > 0) {
            val viewport = size.height
            val thumbH = (viewport / (viewport + max)) * viewport
            val thumbY = (state.value.toFloat() / max) * (viewport - thumbH)
            val w = width.toPx()
            drawRoundRect(
                color = color.copy(alpha = 0.5f),
                topLeft = Offset(size.width - w, thumbY),
                size = Size(w, thumbH),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }

/** Small thumbnail for a picked bug-report attachment (#28): a downsampled
 *  image preview, or a film icon for video / undecodable picks. */
@Composable
private fun AttachThumb(uri: android.net.Uri, modifier: Modifier) {
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    val img by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val mime = ctx.contentResolver.getType(uri) ?: ""
                if (!mime.startsWith("image/")) return@runCatching null
                ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                        ?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val bmp = img
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(c.bgPrimary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Videocam, null, tint = c.textSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun SettingToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = RcqTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bgSecondary).alpha(if (enabled) 1f else 0.45f).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.textPrimary, fontSize = 15.sp)
            Text(subtitle, color = c.textSecondary, fontSize = 11.sp)
        }
        // Explicit OFF-state colours: the default M3 unchecked switch on our
        // dark theme reads as "disabled" (flat grey blob). A visible thumb +
        // border makes OFF look like a tappable-but-off switch (beta report).
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent,
                uncheckedThumbColor = c.textSecondary,
                uncheckedTrackColor = c.bgPrimary,
                uncheckedBorderColor = c.textSecondary,
            ),
        )
    }
}

@Composable
private fun LanguageScreen(onBack: () -> Unit) {
    val c = RcqTheme.colors
    val activity = LocalContext.current as? android.app.Activity
    val current by LanguageManager.current.collectAsState()
    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.onboard_language), onBack)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(LanguageManager.supported, key = { it.code }) { lang ->
                Row(
                    Modifier.fillMaxWidth().clickable { activity?.let { LanguageManager.set(it, lang.code) } }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(lang.nativeName, color = c.textPrimary, fontSize = 16.sp)
                        if (lang.englishName != lang.nativeName) Text(lang.englishName, color = c.textSecondary, fontSize = 12.sp)
                    }
                    if (lang.code == current) Icon(Icons.Filled.Check, null, tint = c.accent, modifier = Modifier.size(20.dp))
                }
                Divider()
            }
        }
        SectionFooter(stringResource(R.string.lang_footer))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun NotificationsScreen(onBack: () -> Unit) {
    val c = RcqTheme.colors
    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_notifications), onBack)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(40.dp))
            Icon(Icons.Filled.Notifications, null, tint = c.textSecondary, modifier = Modifier.size(44.dp))
            Text(stringResource(R.string.notif_push), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.notif_desc), color = c.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

// ── Blocked users ────────────────────────────────────────────────────

@Composable
private fun BlockedUsersScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val contacts by session.contacts.collectAsState()
    val blockedSet by app.rcq.android.data.LocalStores.blocked.collectAsState()
    // Union of server-blocked contacts + the local blocked set (incl. blocked
    // strangers with no contact row, rendered as #uin stubs).
    val blocked = remember(contacts, blockedSet) { session.blockedContacts() }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_blocked), onBack)
        if (blocked.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(60.dp))
                Icon(Icons.Outlined.Block, null, tint = c.textSecondary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.blocked_empty), color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(blocked, key = { it.uin }) { ct ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusIcon(ct.presence, size = 26.dp)
                        Column(Modifier.weight(1f)) {
                            Text(ct.nickname, color = c.textPrimary, fontSize = 15.sp)
                            Text("#${ct.uin}", color = c.textMono, fontSize = 12.sp)
                        }
                        TextButton(onClick = { scope.launch { runCatching { session.toggleBlock(ct.uin) } } }) {
                            Text(stringResource(R.string.blocked_unblock), color = c.accent)
                        }
                    }
                }
            }
        }
    }
}

// ── Linked devices ───────────────────────────────────────────────────

/** Web sessions linked to this account (connect-to-web). Lists them and lets
 *  the user disconnect any — removing the last one drops the account back to
 *  single-device (and v=2 resumes). */
@Composable
private fun LinkedDevicesScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<app.rcq.android.net.RcqApi.DeviceInfo>?>(null) } // null = loading
    var failed by remember { mutableStateOf(false) }
    var showHow by remember { mutableStateOf(false) }

    suspend fun reload() {
        failed = false
        runCatching { session.listDevices() }
            .onSuccess { devices = it }
            .onFailure { failed = true; devices = emptyList() }
    }
    LaunchedEffect(Unit) { reload() }

    if (showHow) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showHow = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.linked_devices_connect), color = c.textPrimary) },
            text = { Text(stringResource(R.string.linked_devices_connect_steps), color = c.textSecondary, fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { showHow = false }) { Text(stringResource(R.string.common_close), color = c.accent) } },
        )
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_linked_devices), onBack)
        Text(
            stringResource(R.string.linked_devices_hint),
            color = c.textSecondary, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(percent = 50))
                .background(c.accent).clickable { showHow = true }.padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.linked_devices_connect), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        when (val list = devices) {
            null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent, modifier = Modifier.size(28.dp))
            }
            else -> if (list.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(40.dp))
                    Icon(Icons.Filled.Devices, null, tint = c.textSecondary, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(if (failed) R.string.linked_devices_error else R.string.linked_devices_empty),
                        color = c.textPrimary, fontSize = 15.sp,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.device_id }) { d ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Filled.Computer, null, tint = c.accent, modifier = Modifier.size(26.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.label.ifEmpty { "Web" }, color = c.textPrimary, fontSize = 15.sp)
                                if (d.created_at.length >= 10) {
                                    Text(stringResource(R.string.linked_devices_connected, d.created_at.take(10)), color = c.textSecondary, fontSize = 12.sp)
                                }
                            }
                            TextButton(onClick = {
                                scope.launch { runCatching { session.revokeDevice(d.device_id) }; reload() }
                            }) {
                                Text(stringResource(R.string.linked_devices_disconnect), color = Color(0xFFE5484D))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Custom server ────────────────────────────────────────────────────

/** Point this device at a different backend (iOS CustomServerSheet
 *  parity). Switching is destructive — the current UIN/token/contacts
 *  only exist on the current server — so we confirm, then burn the
 *  account and mint a fresh identity on the chosen server. */
@Composable
private fun CustomServerScreen(session: Session, onBack: () -> Unit, onSwitched: (Int) -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val current = session.currentServer
    var draft by remember { mutableStateOf(current) }
    var invite by remember { mutableStateOf("") }
    var switching by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }

    // Bare host the user typed (scheme/path stripped); blank → default.
    fun normalized(s: String): String = s.trim()
        .removePrefix("https://").removePrefix("http://").removePrefix("wss://").removePrefix("ws://")
        .substringBefore('/').trim()
        .ifBlank { RcqApi.DEFAULT_HOST }

    val target = normalized(draft)
    val isDirty = target != current
    val onCustom = current != RcqApi.DEFAULT_HOST

    fun applySwitch(input: String?, inviteCode: String?) {
        switching = true
        scope.launch {
            val newUin = runCatching { session.registerNewAccount("user-${(1000..9999).random()}", input, inviteCode) }.getOrNull()
            switching = false
            if (newUin != null) {
                Toast.makeText(context, context.getString(R.string.csrv_connected, session.currentServer), Toast.LENGTH_LONG).show()
                onSwitched(newUin)
            } else {
                Toast.makeText(context, context.getString(R.string.csrv_unreachable), Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.pv_custom_server), onBack, trailing = {
            TextButton(enabled = isDirty && !switching, onClick = { confirm = true }) {
                Text(stringResource(R.string.common_save), color = if (isDirty && !switching) c.accent else c.textSecondary)
            }
        })

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(
                stringResource(R.string.csrv_intro),
                color = c.textSecondary, fontSize = 14.sp,
            )

            // Current server card.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bgSecondary).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.csrv_current), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(current, color = c.textPrimary, fontSize = 15.sp)
            }

            Field(stringResource(R.string.csrv_host), draft) { draft = it }

            // Invite token — required only for closed servers
            // (REGISTRATION_POLICY=invite). Leave blank for open self-hosts.
            Field(stringResource(R.string.csrv_invite), invite) { invite = it }
            Text(stringResource(R.string.csrv_invite_hint), color = c.textSecondary, fontSize = 11.sp)

            // Destructive-switch warning.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Warning, null, tint = Color(0xFFE0A106), modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.csrv_warning),
                    color = c.textSecondary, fontSize = 12.sp,
                )
            }

            if (onCustom) {
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgSecondary)
                        .clickable(enabled = !switching) { resetting = true }.padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Restore, null, tint = Color(0xFFE5484D), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.csrv_reset_btn), color = Color(0xFFE5484D), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (switching) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.csrv_switching), color = c.textSecondary, fontSize = 13.sp)
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.csrv_confirm_title, target),
            body = stringResource(R.string.csrv_confirm_body, target, current),
            confirm = stringResource(R.string.common_switch), destructive = true,
            onConfirm = { confirm = false; applySwitch(draft, invite.trim().ifBlank { null }) },
            onDismiss = { confirm = false },
        )
    }
    if (resetting) {
        ConfirmDialog(
            title = stringResource(R.string.csrv_reset_title),
            body = stringResource(R.string.csrv_reset_body, RcqApi.DEFAULT_HOST, current),
            confirm = stringResource(R.string.common_reset), destructive = true,
            onConfirm = { resetting = false; applySwitch(null, null) },
            onDismiss = { resetting = false },
        )
    }
}

// ── shared bits ──────────────────────────────────────────────────────

@Composable
private fun AppIconScreen(onBack: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIconManager.current(context)) }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgPrimary)
    ) {
        SettingsTopBar(stringResource(R.string.settings_row_app_icon), onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            SettingsGroup {
                AppIconManager.options.forEachIndexed { index, opt ->
                    if (index > 0) Divider()
                    val selected = opt.alias == current.alias
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                AppIconManager.set(context, opt)
                                current = opt
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(opt.labelRes),
                            color = c.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(Icons.Filled.Check, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            SectionFooter(stringResource(R.string.app_icon_footer))
        }
    }
}

// ── Backup island (multihoming, federation v1) ───────────────────────

@Composable
private fun BackupIslandScreen(session: Session, onPromoted: (Int) -> Unit, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val homes by session.backupHomes.collectAsState()
    var host by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var autoBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val autoHomes = homes.filter { it.auto }
    val manualHomes = homes.filter { !it.auto }
    // The manual block starts open only for self-hosters who already added an
    // island by hand; everyone else just sees the toggle.
    var advanced by remember { mutableStateOf(manualHomes.isNotEmpty()) }

    fun errorText(e: Throwable): String = when (e.message) {
        "invalid_host" -> context.getString(R.string.backup_island_err_invalid)
        "primary_island" -> context.getString(R.string.backup_island_err_primary)
        "already_added" -> context.getString(R.string.backup_island_err_already)
        "no_island" -> context.getString(R.string.backup_island_err_none)
        "unreachable" -> context.getString(R.string.backup_island_err_unreachable)
        // Keep the cause visible — "could not connect" alone is undebuggable
        // for a self-hoster pointing at their own island.
        else -> context.getString(R.string.backup_island_err_generic) +
            " (${e.message ?: e.javaClass.simpleName})"
    }

    // §5a.5 promote: confirm-first — the number and the connected island change.
    var promoteTarget by remember { mutableStateOf<MultihomeStore.Home?>(null) }
    promoteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) promoteTarget = null },
            title = { Text(stringResource(R.string.backup_island_promote_title)) },
            text = { Text(stringResource(R.string.backup_island_promote_body, target.host)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { session.promoteBackupToPrimary(target.host) }
                                .onSuccess {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.backup_island_promoted, target.host),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    session.uin?.let(onPromoted)
                                }
                                .onFailure { error = errorText(it) }
                            busy = false
                            promoteTarget = null
                        }
                    },
                ) { Text(stringResource(R.string.backup_island_promote_confirm)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { promoteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_backup_island), onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.backup_island_body), color = c.textSecondary, fontSize = 14.sp)

            // One toggle for normal users: the island comes from the catalogue.
            SettingToggleRow(
                title = stringResource(R.string.backup_island_auto_title),
                subtitle = stringResource(R.string.backup_island_auto_sub),
                checked = autoHomes.isNotEmpty(),
            ) { on ->
                if (autoBusy) return@SettingToggleRow
                autoBusy = true; error = null
                scope.launch {
                    runCatching {
                        if (on) session.enableAutoBackup() else session.disableAutoBackup()
                    }.onFailure { error = errorText(it) }
                    autoBusy = false
                }
            }
            if (autoBusy) {
                Text(stringResource(R.string.backup_island_auto_busy), color = c.textSecondary, fontSize = 13.sp)
            }
            if (autoHomes.isNotEmpty()) {
                SettingsGroup {
                    autoHomes.forEachIndexed { index, h ->
                        if (index > 0) Divider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(h.host, color = c.textPrimary)
                            Text(
                                stringResource(R.string.backup_island_row_uin, h.uin),
                                color = c.textSecondary, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            error?.let { Text(it, color = c.statusBusy, fontSize = 13.sp) }

            // Manual host entry stays for self-hosters, tucked away.
            Text(
                (if (advanced) "▾ " else "▸ ") + stringResource(R.string.backup_island_advanced),
                color = c.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.clickable { advanced = !advanced },
            )
            if (advanced) {
                if (manualHomes.isNotEmpty()) {
                    SettingsGroup {
                        manualHomes.forEachIndexed { index, h ->
                            if (index > 0) Divider()
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(h.host, color = c.textPrimary)
                                    Text(
                                        stringResource(R.string.backup_island_row_uin, h.uin),
                                        color = c.textSecondary, fontSize = 12.sp,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        stringResource(R.string.backup_island_remove),
                                        color = c.accent,
                                        fontSize = 14.sp,
                                        modifier = Modifier.clickable(enabled = !busy) { session.removeBackupIsland(h.host) },
                                    )
                                    Text(
                                        stringResource(R.string.backup_island_promote),
                                        color = c.textSecondary,
                                        fontSize = 14.sp,
                                        modifier = Modifier.clickable(enabled = !busy) { promoteTarget = h },
                                    )
                                }
                            }
                        }
                    }
                }

                Field(stringResource(R.string.backup_island_host_hint), host) { host = it }
                Button(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { session.addBackupIsland(host) }
                                .onSuccess { host = "" }
                                .onFailure { error = errorText(it) }
                            busy = false
                        }
                    },
                    enabled = !busy && host.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (busy) R.string.backup_island_busy else R.string.backup_island_add))
                }
            }

            Text(stringResource(R.string.backup_island_footer), color = c.textSecondary, fontSize = 12.sp)
        }
    }
}

/** Decode a `data:<mime>;base64,<b64>` URI back to bytes (for the preview). */
private fun decodeDataUriBytes(dataUri: String): ByteArray? = runCatching {
    val b64 = dataUri.substringAfter(";base64,", "")
    if (b64.isEmpty()) null else android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
}.getOrNull()

/** Turn a picked image into a small data-URI for the HoF wall. Caps at ~256KB
 *  (the server limit): a small animated GIF is kept raw so it still animates;
 *  anything else (or an oversized GIF) is downscaled + JPEG-compressed through
 *  the PURE-JAVA path (the native GIF decoder SIGSEGVs on some OEM ROMs).
 *  Returns null if it can't get the bytes under the cap. */
private fun hofAvatarDataUri(context: android.content.Context, uri: android.net.Uri): String? {
    val cap = 256 * 1024
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val isGif = raw.size >= 4 && raw[0] == 0x47.toByte() && raw[1] == 0x49.toByte() &&
        raw[2] == 0x46.toByte() && raw[3] == 0x38.toByte()
    fun encode(bytes: ByteArray, mime: String) =
        "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    if (isGif && raw.size <= cap) return encode(raw, "image/gif")
    val src = (if (isGif) gifFirstFrame(raw) else android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)) ?: return null
    val maxSide = 256
    val longest = maxOf(src.width, src.height)
    val scaled = if (longest > maxSide) {
        val f = maxSide.toFloat() / longest
        android.graphics.Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
    } else src
    // Step the JPEG quality down until it fits the cap.
    for (q in intArrayOf(85, 70, 55, 40)) {
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
        val bytes = out.toByteArray()
        if (bytes.size <= cap) return encode(bytes, "image/jpeg")
    }
    return null
}

@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    val c = RcqTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent, modifier = Modifier.size(26.dp).clickable(onClick = onBack))
        Spacer(Modifier.width(12.dp))
        Text(title, color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

// ── PIN codes (panic-PIN, Phase 1: real PIN) ─────────────────────────

@Composable
private fun PinCodesScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var configured by remember { mutableStateOf(session.pinConfigured) }
    var editing by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Wipe PIN (panic-PIN phase 2): a second PIN that erases everything.
    var wipeConfigured by remember { mutableStateOf(session.hasWipePin) }
    var wipeEditing by remember { mutableStateOf(false) }
    var wpin by remember { mutableStateOf("") }
    var wconfirm by remember { mutableStateOf("") }
    var werror by remember { mutableStateOf<String?>(null) }
    // Decoy PIN (panic-PIN phase 2): a PIN that reveals only a chosen account.
    val roster by app.rcq.android.data.AccountManager.accounts.collectAsState()
    var decoyConfigured by remember { mutableStateOf(session.hasDecoyPin) }
    var decoyEditing by remember { mutableStateOf(false) }
    var dpin by remember { mutableStateOf("") }
    var dconfirm by remember { mutableStateOf("") }
    var derror by remember { mutableStateOf<String?>(null) }
    var decoyAccount by remember { mutableStateOf<String?>(null) }
    // Biometric unlock (panic-PIN phase 4): mutually exclusive with the duress
    // PINs, since a fingerprint/face reveals the real account.
    val activity = remember(context) { context.findFragmentActivity() }
    val bioHardware = remember { activity != null && session.biometricHardwareAvailable() }
    var bioEnabled by remember { mutableStateOf(session.biometricEnabled) }

    fun onlyDigits(s: String) = s.length <= 12 && s.all { it.isDigit() }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.pin_codes_title), onBack)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (editing) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (onlyDigits(it)) pin = it },
                    label = { Text(stringResource(R.string.pin_new)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (onlyDigits(it)) confirm = it },
                    label = { Text(stringResource(R.string.pin_confirm)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp) }
                CapsuleButton(
                    label = if (busy) stringResource(R.string.pin_busy) else stringResource(R.string.common_save),
                    enabled = pin.length >= 4 && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (pin != confirm) { error = context.getString(R.string.pin_mismatch); return@CapsuleButton }
                    scope.launch {
                        busy = true; error = null
                        val ok = withContext(Dispatchers.Default) {
                            if (configured) session.changePin(pin) else session.setPin(pin)
                        }
                        busy = false
                        if (ok) { configured = true; editing = false; pin = ""; confirm = "" }
                        else error = context.getString(R.string.pin_too_short)
                    }
                }
                TextButton(onClick = { editing = false; error = null }) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
            } else if (wipeEditing) {
                Text(stringResource(R.string.pin_wipe_desc), color = c.textSecondary, fontSize = 13.sp)
                OutlinedTextField(
                    value = wpin,
                    onValueChange = { if (onlyDigits(it)) wpin = it },
                    label = { Text(stringResource(R.string.pin_wipe_new)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = wconfirm,
                    onValueChange = { if (onlyDigits(it)) wconfirm = it },
                    label = { Text(stringResource(R.string.pin_confirm)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                werror?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp) }
                CapsuleButton(
                    label = if (busy) stringResource(R.string.pin_busy) else stringResource(R.string.common_save),
                    enabled = wpin.length >= 4 && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (wpin != wconfirm) { werror = context.getString(R.string.pin_mismatch); return@CapsuleButton }
                    scope.launch {
                        busy = true; werror = null
                        val ok = withContext(Dispatchers.Default) { session.setWipePin(wpin) }
                        busy = false
                        if (ok) { wipeConfigured = true; wipeEditing = false; wpin = ""; wconfirm = "" }
                        else werror = context.getString(R.string.pin_wipe_taken)
                    }
                }
                TextButton(onClick = { wipeEditing = false; werror = null }) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
            } else if (decoyEditing) {
                Text(stringResource(R.string.pin_decoy_desc), color = c.textSecondary, fontSize = 13.sp)
                Text(stringResource(R.string.pin_decoy_pick), color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                SettingsGroup {
                    roster.forEachIndexed { i, a ->
                        if (i > 0) Divider()
                        val nick = app.rcq.android.data.SecureStore.peekNickname(context, a.id) ?: "—"
                        val uin = app.rcq.android.data.SecureStore.peekUin(context, a.id)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { decoyAccount = a.id }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (decoyAccount == a.id) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                null, tint = if (decoyAccount == a.id) c.accent else c.textSecondary, modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column { Text(nick, color = c.textPrimary, fontSize = 14.sp); uin?.let { Text("#$it", color = c.textSecondary, fontSize = 12.sp) } }
                        }
                    }
                }
                OutlinedTextField(
                    value = dpin, onValueChange = { if (onlyDigits(it)) dpin = it },
                    label = { Text(stringResource(R.string.pin_decoy_new)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dconfirm, onValueChange = { if (onlyDigits(it)) dconfirm = it },
                    label = { Text(stringResource(R.string.pin_confirm)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                derror?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp) }
                CapsuleButton(
                    label = if (busy) stringResource(R.string.pin_busy) else stringResource(R.string.common_save),
                    enabled = dpin.length >= 4 && decoyAccount != null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (dpin != dconfirm) { derror = context.getString(R.string.pin_mismatch); return@CapsuleButton }
                    val acc = decoyAccount ?: return@CapsuleButton
                    scope.launch {
                        busy = true; derror = null
                        val ok = withContext(Dispatchers.Default) { session.setDecoyPin(dpin, acc) }
                        busy = false
                        if (ok) { decoyConfigured = true; decoyEditing = false; dpin = ""; dconfirm = "" }
                        else derror = context.getString(R.string.pin_wipe_taken)
                    }
                }
                TextButton(onClick = { decoyEditing = false; derror = null }) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
            } else if (!configured) {
                CapsuleButton(stringResource(R.string.pin_set), modifier = Modifier.fillMaxWidth()) {
                    editing = true; pin = ""; confirm = ""; error = null
                }
            } else {
                SettingsGroup {
                    SettingsRow(Icons.Filled.Password, stringResource(R.string.pin_change)) {
                        editing = true; pin = ""; confirm = ""; error = null
                    }
                    Divider()
                    SettingsRow(Icons.Filled.DeleteSweep, stringResource(R.string.pin_remove), destructive = true) {
                        if (!busy) scope.launch {
                            busy = true
                            withContext(Dispatchers.Default) { session.removePin() }
                            busy = false; configured = false; bioEnabled = false
                        }
                    }
                }
                if (bioHardware) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.pin_biometric_label))
                    SettingsGroup {
                        when {
                            bioEnabled -> SettingsRow(Icons.Filled.Fingerprint, stringResource(R.string.pin_biometric_disable), destructive = true) {
                                session.disableBiometric(); bioEnabled = false
                            }
                            // Biometric reveals the real account, so it can't coexist
                            // with a decoy/wipe duress PIN (parity with iOS).
                            wipeConfigured || decoyConfigured -> SettingsRow(
                                Icons.Filled.Fingerprint, stringResource(R.string.pin_biometric_enable),
                                value = stringResource(R.string.pin_biometric_unavailable_duress),
                            ) {}
                            else -> SettingsRow(Icons.Filled.Fingerprint, stringResource(R.string.pin_biometric_enable)) {
                                val act = activity ?: return@SettingsRow
                                val blob = session.realPinPayloadBlob() ?: return@SettingsRow
                                BiometricGate.enable(
                                    act,
                                    context.getString(R.string.pin_biometric_enroll_title),
                                    context.getString(R.string.pin_biometric_enroll_subtitle),
                                    context.getString(R.string.common_cancel),
                                    blob,
                                ) { ok ->
                                    if (ok) bioEnabled = true
                                    else android.widget.Toast.makeText(context, context.getString(R.string.pin_biometric_failed), android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.pin_duress_label))
                SettingsGroup {
                    if (!wipeConfigured) {
                        if (bioEnabled) {
                            SettingsRow(
                                Icons.Filled.DeleteForever, stringResource(R.string.pin_wipe_set),
                                value = stringResource(R.string.pin_duress_unavailable_bio),
                            ) {}
                        } else SettingsRow(Icons.Filled.DeleteForever, stringResource(R.string.pin_wipe_set)) {
                            wipeEditing = true; wpin = ""; wconfirm = ""; werror = null
                        }
                    } else {
                        SettingsRow(Icons.Filled.DeleteForever, stringResource(R.string.pin_wipe_remove), destructive = true) {
                            if (!busy) scope.launch {
                                busy = true
                                withContext(Dispatchers.Default) { session.removeWipePin() }
                                busy = false; wipeConfigured = false
                            }
                        }
                    }
                    Divider()
                    if (!decoyConfigured) {
                        if (bioEnabled) {
                            SettingsRow(Icons.Filled.Lock, stringResource(R.string.pin_decoy_set), value = stringResource(R.string.pin_duress_unavailable_bio)) {}
                        } else if (roster.size >= 2) {
                            SettingsRow(Icons.Filled.Lock, stringResource(R.string.pin_decoy_set)) {
                                decoyEditing = true; dpin = ""; dconfirm = ""; derror = null
                                decoyAccount = roster.firstOrNull()?.id
                            }
                        } else {
                            SettingsRow(Icons.Filled.Lock, stringResource(R.string.pin_decoy_set), value = stringResource(R.string.pin_decoy_needs_two)) {}
                        }
                    } else {
                        SettingsRow(Icons.Filled.Lock, stringResource(R.string.pin_decoy_remove), destructive = true) {
                            if (!busy) scope.launch {
                                busy = true
                                withContext(Dispatchers.Default) { session.removeDecoyPin() }
                                busy = false; decoyConfigured = false
                            }
                        }
                    }
                }
            }
            // Auto-lock grace (#10): how long the app can sit in the background
            // before it demands the PIN again. Only meaningful with a PIN set.
            if (configured) {
                Spacer(Modifier.height(18.dp))
                SectionLabel(stringResource(R.string.pin_autolock_title))
                val grace by LocalStores.lockGrace.collectAsState()
                val c2 = RcqTheme.colors
                val presets = listOf(
                    0 to stringResource(R.string.pin_autolock_now),
                    60 to stringResource(R.string.pin_autolock_1m),
                    300 to stringResource(R.string.pin_autolock_5m),
                    900 to stringResource(R.string.pin_autolock_15m),
                )
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c2.bgSecondary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    presets.forEach { (secs, label) ->
                        val sel = grace == secs
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(if (sel) c2.accent else Color.Transparent)
                                .clickable { LocalStores.setLockGrace(secs) }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) Color.White else c2.textSecondary, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
                    }
                }
                SectionFooter(stringResource(R.string.pin_autolock_footer))
            }
            SectionFooter(stringResource(R.string.pin_codes_footer))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = RcqTheme.colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
}

/** Small grey explanation under a settings group, iOS section-footer style. */
@Composable
private fun SectionFooter(text: String) {
    Text(text, color = RcqTheme.colors.textSecondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp))
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RcqTheme.colors.bgSecondary)) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 48.dp).background(RcqTheme.colors.divider))
}

@Composable
private fun SettingsRow(icon: ImageVector, label: String, value: String? = null, destructive: Boolean = false, onClick: () -> Unit) {
    val c = RcqTheme.colors
    val tint = if (destructive) Color(0xFFE5484D) else c.accent
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = if (destructive) Color(0xFFE5484D) else c.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = c.textSecondary, fontSize = 14.sp)
        Icon(Icons.Filled.ChevronRight, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SegmentedTheme(mode: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val c = RcqTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgSecondary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(ThemeMode.SYSTEM to stringResource(R.string.theme_auto), ThemeMode.LIGHT to stringResource(R.string.theme_light), ThemeMode.DARK to stringResource(R.string.theme_dark)).forEach { (m, label) ->
            val sel = mode == m
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(if (sel) c.accent else Color.Transparent)
                    .clickable { onPick(m) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (sel) Color.White else c.textSecondary, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

/** Text-size presets (#3 accessibility). The glyph grows with each step so the
 *  control previews itself. Multiplies the OS font scale app-wide. */
@Composable
private fun SegmentedFontScale(scale: Float, onPick: (Float) -> Unit) {
    val c = RcqTheme.colors
    val steps = listOf(0.85f to 13, 1.0f to 16, 1.15f to 19, 1.3f to 22)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgSecondary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        steps.forEach { (s, glyph) ->
            // Selected when within half a step of this preset.
            val sel = kotlin.math.abs(scale - s) < 0.08f
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(if (sel) c.accent else Color.Transparent)
                    .clickable { onPick(s) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text("A", color = if (sel) Color.White else c.textSecondary, fontSize = glyph.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
private fun VisibilityPicker(label: String, value: String, options: List<String>, desc: String? = null, onPick: (String) -> Unit) {
    val c = RcqTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgSecondary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEach { opt ->
                val sel = value == opt
                val optLabel = when (opt) {
                    "everyone" -> stringResource(R.string.vis_everyone)
                    "contacts" -> stringResource(R.string.vis_contacts)
                    "nobody" -> stringResource(R.string.vis_nobody)
                    else -> opt.replaceFirstChar { it.uppercase() }
                }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(if (sel) c.accent else Color.Transparent)
                        .clickable { onPick(opt) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(optLabel, color = if (sel) Color.White else c.textSecondary, fontSize = 12.sp) }
            }
        }
        if (desc != null) Text(desc, color = c.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun Field(label: String, value: String, keyboardDigits: Boolean = false, minLines: Int = 1, onChange: (String) -> Unit) {
    val c = RcqTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = c.textSecondary) },
        singleLine = minLines == 1,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, destructive: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(title, color = c.textPrimary) },
        text = { Text(body, color = c.textSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (destructive) Color(0xFFE5484D) else c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
    )
}

private fun appVersion(context: Context): String = runCatching {
    val pm = context.packageManager.getPackageInfo(context.packageName, 0)
    "${pm.versionName}"
}.getOrDefault("0.1")
