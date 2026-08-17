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
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
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
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Gavel
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
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.security.BiometricGate
import app.rcq.android.data.LanguageManager
import app.rcq.android.data.LocalStores
import app.rcq.android.net.MultihomeStore
import app.rcq.android.net.BrokerRelayStore
import app.rcq.android.net.ContactRelayStore
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.launch

/** Sub-screens inside Settings (kept self-contained, no nav graph). */
private enum class SettingsRoute { ROOT, HOW_IT_WORKS, PROFILE, PRIVACY, NETWORK, NOTIFICATIONS, BLOCKED, CUSTOM_SERVER, SOUNDS, LANGUAGE, APP_ICON, CHAT_BG, HOME_BG, PIN_CODES, DIAGNOSTICS, RECOVERY_PHRASE, BACKUP, UIN_SHOP, MY_UINS, LINKED_DEVICES, BACKUP_ISLAND, MY_REPORTS }

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
    // Deep-link: a tapped "we answered your report" notification lands here
    // directly, because the answer is the only reason the user opened the app.
    openMyReports: Boolean = false,
    // Deep-link: the "your island is not answering" banner on the home screen
    // told people to make the backup primary "in settings" and left them to
    // find it (vss did not). The banner is a link now and lands here.
    openBackupIsland: Boolean = false,
) {
    var route by remember {
        mutableStateOf(
            when {
                openMyReports -> SettingsRoute.MY_REPORTS
                openDiagnostics -> SettingsRoute.DIAGNOSTICS
                openBackupIsland -> SettingsRoute.BACKUP_ISLAND
                else -> SettingsRoute.ROOT
            },
        )
    }
    // System-back parity with the in-screen ← arrow: pop ONE settings level
    // instead of letting back fall through to the activity (which dumped the
    // user straight out to the chat list). At ROOT the handler is disabled so
    // back bubbles up to leave Settings as before.
    BackHandler(enabled = route != SettingsRoute.ROOT) {
        // Diagnostics opened directly from Home → back closes Settings.
        if (openDiagnostics && route == SettingsRoute.DIAGNOSTICS) { onBack(); return@BackHandler }
        if (openBackupIsland && route == SettingsRoute.BACKUP_ISLAND) { onBack(); return@BackHandler }
        route = when (route) {
            SettingsRoute.DIAGNOSTICS, SettingsRoute.CUSTOM_SERVER -> SettingsRoute.NETWORK
            else -> SettingsRoute.ROOT
        }
    }
    // Preserve each settings sub-screen's scroll across the internal route swaps
    // (ROOT <-> a sub-page <-> ROOT). On a route change the outgoing screen
    // LEAVES the composition while the parent "settings" provider stays mounted,
    // so its rememberSaveable scroll state was disposed WITHOUT a performSave and
    // reset to the TOP on return — the #2 fix wrapped Settings at the
    // MainActivity level but missed this inner nav. A nested holder keyed by the
    // route saves the outgoing page's state and restores the incoming one.
    val settingsStateHolder = rememberSaveableStateHolder()
    settingsStateHolder.SaveableStateProvider(route.name) {
    // Keyboard insets for EVERY settings sub-screen at once. Reported against
    // profile editing ("нижние поля ввода проваливаются под клавиатуру"): each
    // sub-screen builds its own root Column and none of them consumed the IME
    // inset, so with adjustResize the scroll area kept its full height and the
    // last fields sat behind the keyboard. Chat/Random/Hood already did this per
    // screen; doing it here covers the ones that come later too.
    Box(Modifier.fillMaxSize().imePadding()) {
    when (route) {
        SettingsRoute.ROOT -> SettingsRoot(
            session, uin,
            onBack = onBack,
            onBurned = onBurned,
            onMigrated = onMigrated,
            onOpen = { route = it },
        )
        SettingsRoute.PROFILE -> ProfileEditScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.HOW_IT_WORKS -> HowItWorksScreen { route = SettingsRoute.ROOT }
        SettingsRoute.PRIVACY -> PrivacyScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.NETWORK -> NetworkScreen(
            session,
            onOpenCustomServer = { route = SettingsRoute.CUSTOM_SERVER },
            onOpenDiagnostics = { route = SettingsRoute.DIAGNOSTICS },
        ) { route = SettingsRoute.ROOT }
        // Back from Diagnostics returns to Network (where it was opened from),
        // not the Settings root (tester #1) — unless we deep-linked here from
        // Home, in which case back closes Settings entirely.
        SettingsRoute.DIAGNOSTICS -> DiagnosticsScreen(session) {
            if (openDiagnostics) onBack() else route = SettingsRoute.NETWORK
        }
        SettingsRoute.NOTIFICATIONS -> NotificationsScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.MY_REPORTS -> MyReportsScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.SOUNDS -> SoundsScreen { route = SettingsRoute.ROOT }
        SettingsRoute.LANGUAGE -> LanguageScreen { route = SettingsRoute.ROOT }
        SettingsRoute.APP_ICON -> AppIconScreen { route = SettingsRoute.ROOT }
        SettingsRoute.CHAT_BG -> ChatBackgroundScreen { route = SettingsRoute.ROOT }
        SettingsRoute.HOME_BG -> HomeBackgroundScreen { route = SettingsRoute.ROOT }
        SettingsRoute.BLOCKED -> BlockedUsersScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.PIN_CODES -> PinCodesScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.RECOVERY_PHRASE -> RecoveryPhraseScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.BACKUP -> BackupScreen(session) { route = SettingsRoute.ROOT }
        SettingsRoute.LINKED_DEVICES -> LinkedDevicesScreen(session) { route = SettingsRoute.ROOT }
        // Promote rebinds the session to another island (new uin) — bubble it
        // up like a migration so the Home header re-registers immediately.
        SettingsRoute.BACKUP_ISLAND -> BackupIslandScreen(session, onPromoted = onMigrated) { route = SettingsRoute.ROOT }
        SettingsRoute.CUSTOM_SERVER -> CustomServerScreen(
            session,
            // Back returns to Network (its parent), not the Settings root (tester #1).
            onBack = { route = SettingsRoute.NETWORK },
            onSwitched = { newUin -> onMigrated(newUin); onBack() },
        )
        SettingsRoute.UIN_SHOP -> UinShopScreen(
            session,
            onBack = { route = SettingsRoute.ROOT },
            // Taking a number no longer migrates by itself, but moving onto one
            // does; bubble the new UIN up + close Settings (same flow as the
            // free move / a server switch).
            onMigrated = { newUin -> onMigrated(newUin); onBack() },
            onOpenMyUins = { route = SettingsRoute.MY_UINS },
        )
        SettingsRoute.MY_UINS -> MyUinsScreen(
            session,
            onBack = { route = SettingsRoute.ROOT },
            onActivated = { newUin -> onMigrated(newUin); onBack() },
        )
    }
    }
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
    // How many numbers this account holds besides the one it uses. Decides
    // whether "My numbers" is worth a row on an island with no shop; a server
    // that predates /uin/mine answers 404 and it stays at zero.
    var heldCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        heldCount = runCatching { session.myUins().owned.size }.getOrDefault(0)
    }
    var confirmBurn by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmMigrate by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showBugReport by remember { mutableStateOf(false) }
    var bugText by remember { mutableStateOf("") }
    var bugSending by remember { mutableStateOf(false) }
    var bugSent by remember { mutableStateOf(false) }
    // Why the last send failed, shown in the dialog; null when there is nothing
    // to report.
    var bugError by remember { mutableStateOf<String?>(null) }
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
                // Settings shows the same face the header does. The status is
                // still there, on the picture, so nothing about presence is lost.
                val ownAv by session.ownAvatar.collectAsState()
                PersonAvatar(ownAv?.first, ownAv?.second, ownStatus, session, 44.dp)
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
                SettingsRow(Icons.Filled.Wallpaper, stringResource(R.string.settings_row_home_bg)) { onOpen(SettingsRoute.HOME_BG) }
            }
            val animAvatars by LocalStores.animateAvatars.collectAsState()
            SettingsGroup {
                SettingToggleRow(
                    stringResource(R.string.settings_anim_avatars_title),
                    stringResource(R.string.settings_anim_avatars_desc),
                    animAvatars,
                ) { LocalStores.setAnimateAvatars(it) }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.settings_swipe_reply), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            val swipeSide by LocalStores.swipeReplySide.collectAsState()
            SegmentedSwipeSide(swipeSide) { LocalStores.setSwipeReplySide(it) }

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.settings_sec_privacy))
            SettingsGroup {
                SettingsRow(Icons.Filled.Info, stringResource(R.string.how_title)) { onOpen(SettingsRoute.HOW_IT_WORKS) }
                SettingsRow(Icons.Filled.Lock, stringResource(R.string.settings_row_privacy)) { onOpen(SettingsRoute.PRIVACY) }
                Divider()
                SettingsRow(Icons.Filled.NetworkCheck, stringResource(R.string.settings_row_network)) { onOpen(SettingsRoute.NETWORK) }
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
                SettingsRow(Icons.Filled.Inventory2, stringResource(R.string.settings_row_backup)) { onOpen(SettingsRoute.BACKUP) }
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
                // Invite a person who does not have RCQ. Distinct from the APK
                // row below, which solves a different problem (installing when
                // rcq.app is blocked) and hands over a 100MB file — not what
                // anyone sends to say "join me".
                SettingsRow(Icons.Filled.PersonAdd, stringResource(R.string.settings_row_invite)) {
                    app.rcq.android.net.UpdateChecker.shareInvite(context, uin)
                }
                Divider()
                SettingsRow(Icons.Filled.Share, stringResource(R.string.settings_row_share_app)) {
                    app.rcq.android.net.UpdateChecker.shareApk(context)
                }
                // An island that runs no report desk gets neither entry: a
                // form that answers 403 and a screen that will always be empty
                // are worse than an absent menu item. Flag comes from
                // /server/info; the default is permissive.
                val reportsOn by session.reportsEnabled.collectAsState()
                if (reportsOn) {
                Divider()
                // Open on an EMPTY form, every field of it. The reset used to
                // clear the text and the sent flag and stop there, so the
                // pictures attached to the last report were still in state and
                // came back attached to the next one (#519: "следующий вызов
                // сообщения о баге показывает ранее приложенный опять
                // прикрепленный файл"). The error line and the in-flight flag
                // are stale for the same reason.
                SettingsRow(Icons.Filled.BugReport, stringResource(R.string.settings_row_report_bug)) {
                    bugText = ""
                    bugSent = false
                    bugSending = false
                    bugError = null
                    bugAttachments = emptyList()
                    showBugReport = true
                }
                Divider()
                // Directly under "Report a bug": this is where someone who just
                // filed one looks for the answer. It sat in the privacy block
                // next to Notifications, which is where the answer NOTIFICATION
                // is configured, not where the answer is read (tester report).
                SettingsRow(Icons.Outlined.Flag, stringResource(R.string.myreports_title)) { onOpen(SettingsRoute.MY_REPORTS) }
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.settings_sec_account))
            // UIN shop — only on servers that advertise it (api.rcq.app);
            // self-host backends report uin_shop=false and the row hides.
            //
            // My numbers has its own condition: it shows whenever this account
            // holds anything, shop or no shop. An operator who closes the shop
            // must not strand people on the wrong number, and a self-hoster can
            // hand a member a second one by hand (POST /admin/uin/grant).
            // Servers too old to know /uin/mine answer 404 and the row hides.
            if (uinShopEnabled || heldCount > 0) {
                SettingsGroup {
                    if (uinShopEnabled) {
                        SettingsRow(Icons.Filled.Sell, stringResource(R.string.settings_row_uin_shop)) { onOpen(SettingsRoute.UIN_SHOP) }
                        Divider()
                    }
                    SettingsRow(Icons.Filled.Inventory2, stringResource(R.string.settings_row_my_uins)) { onOpen(SettingsRoute.MY_UINS) }
                }
                // The footer describes the SHOP; without one it would be
                // advertising a storefront this island does not have.
                Text(
                    if (uinShopEnabled) stringResource(R.string.settings_foot_uin_shop)
                    else stringResource(R.string.settings_foot_my_uins),
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
        ConfirmSheet(
            title = stringResource(R.string.cs_clear_title),
            body = stringResource(R.string.cs_clear_body),
            confirm = stringResource(R.string.common_clear), destructive = true,
            onConfirm = { confirmClear = false; session.clearHistory(); Toast.makeText(context, context.getString(R.string.cs_history_cleared), Toast.LENGTH_SHORT).show() },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmMigrate) {
        ConfirmSheet(
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
        ConfirmSheet(
            title = stringResource(R.string.cs_burn_title),
            body = stringResource(R.string.cs_burn_body),
            confirm = stringResource(R.string.cs_burn_cta), destructive = true,
            onConfirm = { confirmBurn = false; scope.launch { onBurned(session.burnAccount()) } },
            onDismiss = { confirmBurn = false },
        )
    }
    if (showBugReport) {
        RcqSheet(onDismiss = { showBugReport = false }) {
            // Title row rather than RcqSheet's plain title: the bug icon is what
            // makes this sheet recognisable at a glance.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Icon(Icons.Filled.BugReport, null, tint = c.accent)
                Text(
                    stringResource(R.string.settings_row_report_bug),
                    color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            if (bugSent) {
                Text(stringResource(R.string.bug_report_sent), color = c.textSecondary, fontSize = 14.sp)
                SheetGap()
                CapsuleButton(stringResource(R.string.common_done), modifier = Modifier.fillMaxWidth()) { showBugReport = false }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bug_report_hint), color = c.textSecondary, fontSize = 12.sp)
                    bugError?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp) }
                    RcqField(
                        value = bugText,
                        onValueChange = { if (it.length <= session.bugReportTextLimit) bugText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 3,
                        placeholder = stringResource(R.string.bug_report_placeholder),
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
                SheetGap()
                CapsuleButton(
                    label = stringResource(if (bugSending) R.string.bug_report_sending else R.string.bug_report_send),
                    enabled = bugText.trim().length >= 5 && !bugSending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
                        val result = session.submitBugReportResult(bugText.trim(), atts)
                        bugSending = false
                        bugError = null
                        when (result) {
                            Session.BugReportResult.SENT -> bugSent = true
                            // Say WHY. Silently returning the button to
                            // its idle state read as "the app is broken"
                            // and produced a quarter of an hour of retries.
                            Session.BugReportResult.RATE_LIMITED ->
                                bugError = context.getString(R.string.bug_report_too_many)
                            Session.BugReportResult.CLOSED ->
                                bugError = context.getString(R.string.bug_report_closed)
                            Session.BugReportResult.TOO_LONG ->
                                bugError = context.getString(R.string.bug_report_too_long)
                            Session.BugReportResult.FAILED ->
                                bugError = context.getString(R.string.bug_report_failed)
                        }
                    }
                }
                TextButton(onClick = { showBugReport = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
            }
        }
    }
    if (showAbout) {
        RcqSheet(onDismiss = { showAbout = false }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Image(painterResource(R.drawable.rcq_logo), null, modifier = Modifier.size(24.dp))
                Text("RCQ", color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            // Scrollable, and capped: the update notes can be a long
            // bilingual paragraph that otherwise pushes the "Download and
            // install" button (and Done) past the bottom of the sheet, so
            // the user saw "update available" but never the install action.
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
                    .heightIn(max = 380.dp)
                    .verticalScroll(aboutScroll)
                    .simpleVerticalScrollbar(aboutScroll, c.textSecondary),
            ) {
                Text(stringResource(R.string.cs_about_version, appVersion(context)), color = c.textMono, fontSize = 13.sp)
                Text(stringResource(R.string.cs_about_features), color = c.textSecondary, fontSize = 12.sp)
                // "Open source" was a claim with nowhere to go. The repo is
                // public, and the one place a person looks for it is the line
                // that already says the version.
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Text(
                    stringResource(R.string.cs_about_source),
                    color = c.accent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/rcq-messenger/rcq-android")
                    },
                )
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
            SheetGap()
            TextButton(onClick = { showAbout = false }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_done), color = c.accent)
            }
        }
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
    val ownAvatar by session.ownAvatar.collectAsState()
    var avatarBusy by remember { mutableStateOf(false) }
    // GIFs go up as-is (a moving avatar is the point of allowing them);
    // everything else is re-encoded to JPEG like a group avatar, so a 12MP
    // photo does not become a 6MB blob every viewer has to pull.
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            avatarBusy = true
            val bytes = withContext(Dispatchers.IO) {
                val mime = context.contentResolver.getType(uri) ?: ""
                if (mime == "image/gif") {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                        .getOrNull()?.takeIf { it.size <= 2_000_000 }
                } else compressImageFor(context, uri)
            }
            if (bytes != null) runCatching { session.setOwnAvatar(bytes) }
            avatarBusy = false
        }
    }

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

    // ⚠⚠ The keyboard inset belongs to the SCREEN, not to whoever mounted it.
    //
    // This editor is opened from two places: the profile card in Settings, and a
    // tap on the nickname in the home header (plus "укажите возраст" out of
    // Random). Only the Settings host wrapped it in an imePadding()-ed Box, so
    // the very same screen laid out correctly by one road and let its bottom
    // fields — About, Interests, Website — sit under the keyboard by the other.
    // That is the whole of "пару раз отображались нормально, намеренно повторить
    // не удаётся": the behaviour was decided by the entry point, never by timing.
    //
    // The app draws edge to edge, so the system does not resize the window and
    // adjustResize buys nothing; the inset is only published, and somebody has to
    // consume it. Doing it here is idempotent — Compose subtracts the inset a
    // parent already consumed, so the Settings road, which consumes it one level
    // up, gets exactly zero from this one and is unchanged.
    Column(Modifier.fillMaxSize().background(c.bgPrimary).imePadding()) {
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
                // The picture becomes the anchor of this card when there is
                // one, and the status flower stands in when there is not, so
                // nothing moves for people who never set a picture.
                // The picture itself is the button, the way every messenger does
                // it: a separate link between the picture and the name split
                // the header row in two and pushed the nickname sideways. The
                // caption sits UNDER the picture so the name keeps its place.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier.clip(CircleShape).clickable(enabled = !avatarBusy) { avatarPicker.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        PersonAvatar(ownAvatar?.first, ownAvatar?.second, ownStatus, session, 56.dp, animated = true)
                        if (avatarBusy) CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp)
                    }
                    Text(
                        stringResource(if (ownAvatar == null) R.string.pe_avatar_set else R.string.pe_avatar_change),
                        color = c.accent, fontSize = 11.sp,
                        modifier = Modifier.clickable(enabled = !avatarBusy) { avatarPicker.launch("image/*") },
                    )
                    if (ownAvatar != null) {
                        Text(
                            stringResource(R.string.pe_avatar_remove),
                            color = c.textSecondary, fontSize = 11.sp,
                            modifier = Modifier.clickable(enabled = !avatarBusy) {
                                scope.launch { avatarBusy = true; runCatching { session.setOwnAvatar(null) }; avatarBusy = false }
                            },
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
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
private fun PrivacyScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    // Seed pickers from the cached profile so they render instantly with the
    // user's real choices (no "ползунки едут на глазах" snap from defaults); the
    // LaunchedEffect below reconciles with the server.
    val cached = remember { session.cachedProfile() }
    var lastSeen by remember { mutableStateOf(cached?.last_seen_visibility ?: "everyone") }
    var genderVis by remember { mutableStateOf(cached?.gender_visibility ?: "nobody") }
    var profileVis by remember { mutableStateOf(cached?.profile_visibility ?: "everyone") }
    var invitePolicy by remember { mutableStateOf(cached?.group_invite_policy ?: "everyone") }
    var receipts by remember { mutableStateOf(cached?.read_receipts_visibility ?: "everyone") }
    var callPolicy by remember { mutableStateOf(cached?.call_policy ?: "everyone") }
    var presencePersistent by remember { mutableStateOf(cached?.presence_persistent ?: false) }
    var presenceTtl by remember { mutableStateOf(cached?.presence_ttl_minutes ?: 1440) }
    var hofOptIn by remember { mutableStateOf(cached?.hof_opt_in ?: false) }
    var hofAvatar by remember { mutableStateOf(cached?.hof_avatar) }   // data-URI or null
    var hofBusy by remember { mutableStateOf(false) }
    var hofError by remember { mutableStateOf<String?>(null) }
    val screenSec by app.rcq.android.data.LocalStores.screenSecurity.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
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
            // The server has enforced this since calls shipped and iOS has
            // offered it since; on Android the only answer to "a stranger is
            // calling me" was to leave the app.
            VisibilityPicker(stringResource(R.string.pv_calls), callPolicy, listOf("everyone", "contacts", "nobody"), stringResource(R.string.pv_calls_desc)) { callPolicy = it; save(RcqApi.UpdateMeBody(call_policy = it)) }

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
            // Hidden on self-hosted islands (a flagship-only surface) — gated on
            // the server's hall_of_fame capability, exactly like the UIN shop.
            val hofEnabled by session.hallOfFameEnabled.collectAsState()
            if (hofEnabled) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

        }
    }
}

@Composable
private fun NetworkScreen(session: Session, onOpenCustomServer: () -> Unit, onOpenDiagnostics: () -> Unit, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Reality, not just the stored preference: the app engages the tunnel by
    // itself when the island is unreachable, and the switch used to keep saying
    // OFF while the shield in the header said ON. Same state, two answers, and
    // the user is right to call that broken.
    val stealthActive by session.stealthActive.collectAsState()
    var obfuscated by remember { mutableStateOf(app.rcq.android.net.SingBoxTransport.isEnabled(context)) }
    var relayCalls by remember { mutableStateOf(app.rcq.android.call.CallPrivacy.alwaysRelay(context)) }
    var autoDisabled by remember { mutableStateOf(app.rcq.android.net.SingBoxTransport.autoEngageDisabled(context)) }
    var localProxy by remember { mutableStateOf(app.rcq.android.net.SingBoxTransport.localProxyMode()) }
    var lpHost by remember { mutableStateOf(app.rcq.android.net.SingBoxTransport.localProxyHost()) }
    var lpPort by remember { mutableStateOf(app.rcq.android.net.SingBoxTransport.localProxyPort().toString()) }
    var lpType by remember { mutableStateOf(app.rcq.android.net.SingBoxTransport.localProxyType()) }
    var lpTesting by remember { mutableStateOf(false) }
    var lpTestOk by remember { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_network), onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SettingsGroup {
                val host = session.currentServer
                // The island's own name and house rules, asked of the island.
                // ⚠ For EVERY island including ours: the flagship has a name
                // and a rules text set in the admin panel too, and skipping the
                // request for the default host meant an operator could type
                // both and see neither, anywhere. Founder asked about this
                // twice ("когда мы уже починим BRANDING").
                val info by produceState<app.rcq.android.net.RcqApi.ServerInfoResponse?>(
                    initialValue = null, host,
                ) {
                    value = app.rcq.android.net.RcqApi.serverInfoOf(host)
                }
                val islandName = info?.name?.takeIf { it.isNotBlank() }
                val islandRules = info?.welcome?.takeIf { it.isNotBlank() }
                var showRules by remember { mutableStateOf(false) }
                SettingsRow(
                    Icons.Filled.Dns,
                    stringResource(R.string.pv_custom_server),
                    value = when {
                        islandName != null -> "$islandName · $host"
                        host == RcqApi.DEFAULT_HOST -> stringResource(R.string.pv_default)
                        else -> host
                    },
                    onClick = onOpenCustomServer,
                )
                if (islandRules != null) {
                    SettingsRow(
                        Icons.Filled.Gavel,
                        stringResource(R.string.island_rules_title),
                        onClick = { showRules = true },
                    )
                }
                if (showRules && islandRules != null) {
                    RcqSheet(
                        onDismiss = { showRules = false },
                        title = islandName ?: host,
                    ) {
                        Text(
                            islandRules,
                            color = c.textPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .heightIn(max = 380.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
                SettingsRow(
                    Icons.Filled.NetworkCheck,
                    stringResource(R.string.diag_title),
                    onClick = onOpenDiagnostics,
                )
            }

            // Calls through the relay. ON by default, which is the opposite of
            // most messengers: WebRTC opens its own sockets outside our
            // transport, so a direct call hands the peer your real address
            // before a word is spoken. Turning it off buys quality and costs
            // exactly that. Kept next to the other routing switches because it
            // is one, even though it only governs calls.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pv_relay_calls), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.pv_relay_calls_desc), color = c.textSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = relayCalls,
                    onCheckedChange = {
                        relayCalls = it
                        app.rcq.android.call.CallPrivacy.setAlwaysRelay(context, it)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
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
                    checked = obfuscated || stealthActive,
                    enabled = !localProxy,
                    onCheckedChange = {
                        obfuscated = it
                        // setObfuscation, not setEnabled: the preference alone left
                        // a running tunnel running, so switching OFF changed nothing
                        // until the next launch while the shield stayed lit. This
                        // starts or stops it now and rebuilds the API + socket.
                        session.setObfuscation(it)
                        // The push socket is pinned to whichever route it dialled
                        // on; redial it so it follows the tunnel in or out.
                        app.rcq.android.push.embedded.EmbeddedDistributor.reconnectNow(context)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }

            // "Don't engage automatically" (iOS parity): by default the app turns
            // the tunnel on when it can't reach the server directly; a user on their
            // own VPN/proxy can opt out so our sing-box doesn't stack on theirs.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pv_obf_auto_disable), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.pv_obf_auto_disable_desc), color = c.textSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = autoDisabled,
                    enabled = !localProxy,
                    onCheckedChange = {
                        autoDisabled = it
                        app.rcq.android.net.SingBoxTransport.setAutoEngageDisabled(context, it)
                        // "Don't engage automatically" while an AUTO-engaged tunnel
                        // is running means stop that one too: the user is telling us
                        // to stay out of the way now, not from the next launch.
                        if (it && !obfuscated && stealthActive) {
                            session.setObfuscation(false)
                            app.rcq.android.push.embedded.EmbeddedDistributor.reconnectNow(context)
                        }
                    },
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
                    enabled = !localProxy,
                    onCheckedChange = {
                        onion = it
                        app.rcq.android.net.SingBoxTransport.setOnionOptIn(context, it)
                        // Onion implies the protected connection. Flip it on too
                        // so this single switch is all the user touches.
                        if (it && !obfuscated) {
                            obfuscated = true
                            app.rcq.android.net.SingBoxTransport.setEnabled(context, true)
                            app.rcq.android.push.embedded.EmbeddedDistributor.reconnectNow(context)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }

            // Local proxy: route everything through the user's OWN local Tor /
            // i2p SOCKS5/HTTP. Mutually exclusive with relays/onion above (they
            // grey out while this is on). No auto-fallback to relays if the proxy
            // is down — that would leak around Tor.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pv_localproxy), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.pv_localproxy_desc), color = c.textSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = localProxy,
                    onCheckedChange = { on ->
                        val port = lpPort.toIntOrNull()
                        if (on && (lpHost.isBlank() || port == null || port !in 1..65535)) {
                            lpTestOk = false
                        } else {
                            localProxy = on
                            if (on) { obfuscated = false; onion = false }
                            session.setLocalProxy(on, lpHost.trim(), port ?: 9050, lpType)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }
            if (localProxy) {
                val clipboard = LocalClipboardManager.current
                // Persist host/port/type to prefs on EVERY edit (not only on the
                // enable toggle), so a custom port/host survives leaving Settings.
                // SingBoxTransport.setLocalProxy is a bare prefs write (no transport
                // restart), so this is cheap per-keystroke. An invalid/blank port
                // keeps the last persisted value instead of snapping to the default.
                fun persistProxy() {
                    val p = lpPort.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: app.rcq.android.net.SingBoxTransport.localProxyPort()
                    app.rcq.android.net.SingBoxTransport.setLocalProxy(context, lpHost.trim(), p, lpType)
                }
                Column(Modifier.padding(top = 8.dp)) {
                    // Host + port stacked vertically: with a label + paste icon each,
                    // two side-by-side fields don't fit in portrait (report: had to
                    // rotate the phone). Full-width, one per line.
                    Column {
                        RcqField(
                            value = lpHost,
                            onValueChange = { lpHost = it; persistProxy() },
                            placeholder = stringResource(R.string.pv_localproxy_host),
                            singleLine = true,
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.ContentPaste,
                                    contentDescription = stringResource(R.string.common_paste),
                                    tint = c.accent,
                                    modifier = Modifier.clickable {
                                        clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { lpHost = it; persistProxy() }
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        RcqField(
                            value = lpPort,
                            onValueChange = { v -> lpPort = v.filter { it.isDigit() }.take(5); persistProxy() },
                            placeholder = stringResource(R.string.pv_localproxy_port),
                            singleLine = true,
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.ContentPaste,
                                    contentDescription = stringResource(R.string.common_paste),
                                    tint = c.accent,
                                    modifier = Modifier.clickable {
                                        clipboard.getText()?.text?.filter { it.isDigit() }?.take(5)?.takeIf { it.isNotEmpty() }?.let { lpPort = it; persistProxy() }
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        TextButton(onClick = { lpType = "socks"; persistProxy() }) {
                            Text("SOCKS5", color = if (lpType == "socks") c.accent else c.textSecondary)
                        }
                        TextButton(onClick = { lpType = "http"; persistProxy() }) {
                            Text("HTTP", color = if (lpType == "http") c.accent else c.textSecondary)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            enabled = !lpTesting,
                            onClick = {
                                val port = lpPort.toIntOrNull()
                                if (port != null) {
                                    lpTesting = true; lpTestOk = null
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            app.rcq.android.net.SingBoxTransport.testLocalProxy(lpHost.trim(), port, lpType)
                                        }
                                        lpTestOk = ok; lpTesting = false
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.pv_localproxy_test), color = c.accent) }
                    }
                    lpTestOk?.let { ok ->
                        Text(
                            stringResource(if (ok) R.string.pv_localproxy_test_ok else R.string.pv_localproxy_test_fail),
                            color = if (ok) c.accent else c.statusBusy,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.pv_localproxy_hint),
                        color = c.textSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // In-chat bridge sharing: relays a contact shared / you imported,
            // augmenting the transport pool. See RCQ/docs/bridge-sharing-design.md.
            var relayImportOpen by remember { mutableStateOf(false) }
            // Survives the dialog closing: the whole point is to say the key
            // landed, and a message inside a dialog that just disappeared says
            // nothing. Null = nothing to report.
            var keyResult by remember { mutableStateOf<Int?>(null) }
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
            // The paid key, when there is one. Shown as a state and a way out,
            // never as the key itself: the cabinet is where it can be read, and
            // a settings screen that prints it is a screenshot away from
            // handing it to whoever is looking over a shoulder.
            var tenantKeyOn by remember { mutableStateOf(BrokerRelayStore.tenantKey() != null) }
            if (tenantKeyOn) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.relay_key_active),
                        color = c.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        BrokerRelayStore.setTenantKey(null)
                        tenantKeyOn = false
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) { BrokerRelayStore.refresh() }
                    }) { Text(stringResource(R.string.relay_key_remove), color = c.accent, fontSize = 12.sp) }
                }
            }
            TextButton(onClick = { relayImportOpen = true }) {
                Text(stringResource(R.string.relay_import_title), color = c.accent, fontSize = 13.sp)
            }

            keyResult?.let { n ->
                // Nothing to decide, so there is no action row: the sheet's own
                // last row is the acknowledgement, relabelled.
                RcqAskSheet(
                    onDismiss = { keyResult = null },
                    title = stringResource(R.string.relay_key_ok_title),
                    body = pluralStringResource(R.plurals.relay_key_ok_body, n, n),
                    actions = emptyList(),
                    cancelLabel = stringResource(R.string.common_ok),
                )
            }

            if (relayImportOpen) {
                var token by remember { mutableStateOf("") }
                var err by remember { mutableStateOf(false) }
                var keyChecking by remember { mutableStateOf(false) }
                var keyError by remember { mutableStateOf<String?>(null) }
                RcqSheet(
                    onDismiss = { relayImportOpen = false },
                    title = stringResource(R.string.relay_import_title),
                ) {
                    Text(stringResource(R.string.relay_import_body), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    RcqField(
                        value = token,
                        // ⚠ keyError has to clear too. It did not, so a
                        // corrected key sat under the refusal the typo
                        // had earned, and the field looked wrong while
                        // holding the right string.
                        onValueChange = { token = it; err = false; keyError = null },
                        placeholder = stringResource(R.string.relay_import_hint),
                        isError = err,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (err) Text(stringResource(R.string.relay_import_bad), color = c.statusBusy, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    keyError?.let { reason ->
                        Text(
                            stringResource(if (reason == "expired") R.string.relay_key_expired else R.string.relay_key_unknown),
                            color = c.statusBusy, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    SheetGap()
                    CapsuleButton(
                        label = stringResource(if (keyChecking) R.string.relay_key_checking else R.string.relay_import_add),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // One field, two things people are handed; the
                        // decision lives in RelayInput so it can be tested
                        // instead of pasted by hand.
                        when (val parsed = app.rcq.android.net.RelayInput.classify(token)) {
                            is app.rcq.android.net.RelayInput.Link -> {
                                ContactRelayStore.add(parsed.relay, 0, null)
                                sharedRelays = ContactRelayStore.list()
                                relayImportOpen = false
                            }
                            is app.rcq.android.net.RelayInput.AccessKey -> {
                                // Only the broker can say whether the key is
                                // good, and asking it is this refresh — which
                                // also makes the endpoints appear now rather
                                // than at the next boot.
                                //
                                // ⚠ And the answer is now WAITED FOR. The
                                // sheet used to close on the spot and report
                                // success, so a mistyped key looked exactly
                                // like a working one: reported from the
                                // outside on the first day a key existed.
                                BrokerRelayStore.setTenantKey(parsed.key)
                                keyChecking = true
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    BrokerRelayStore.refresh()
                                    val verdict = BrokerRelayStore.keyVerdict()
                                    val mine = BrokerRelayStore.privateRelays().size
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        keyChecking = false
                                        if (verdict == "ok") {
                                            relayImportOpen = false
                                            keyResult = mine
                                        } else {
                                            // Not ours: drop it rather than
                                            // leave a dead key in place
                                            // quietly failing forever.
                                            BrokerRelayStore.setTenantKey(null)
                                            keyError = verdict ?: "unknown"
                                        }
                                    }
                                }
                            }
                            app.rcq.android.net.RelayInput.Unusable -> err = true
                        }
                    }
                    TextButton(onClick = { relayImportOpen = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                    }
                }
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

    val context = LocalContext.current
    var running by remember { mutableStateOf(true) }
    var auditing by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf<app.rcq.android.net.NetworkAudit.Report?>(null) }
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

            // Full network audit. Separate button because it opens raw sockets
            // to a couple of third-party control hosts, which should never
            // happen without the user asking for it.
            Spacer(Modifier.height(4.dp))
            SectionFooter(stringResource(R.string.diag_audit_hint))
            CapsuleButton(stringResource(R.string.diag_audit_run), enabled = !auditing) {
                auditing = true; audit = null
                scope.launch {
                    audit = withContext(Dispatchers.IO) {
                        runCatching { app.rcq.android.net.NetworkAudit.run(session.currentServer) }.getOrNull()
                    }
                    auditing = false
                }
            }
            audit?.let { a ->
                SettingsGroup {
                    a.lines.forEachIndexed { i, l ->
                        if (i > 0) Divider()
                        DiagRow(l.name, l.detail, ok = l.ok)
                    }
                }
                Text(
                    stringResource(
                        when (a.verdict) {
                            app.rcq.android.net.NetworkAudit.Verdict.ALL_FINE -> R.string.diag_audit_fine
                            app.rcq.android.net.NetworkAudit.Verdict.CALLS_BLOCKED -> R.string.diag_audit_calls_blocked
                            app.rcq.android.net.NetworkAudit.Verdict.NO_INTERNET -> R.string.diag_audit_no_net
                            app.rcq.android.net.NetworkAudit.Verdict.BY_NAME -> R.string.diag_audit_by_name
                            app.rcq.android.net.NetworkAudit.Verdict.BY_ADDRESS -> R.string.diag_audit_by_addr
                            else -> R.string.diag_audit_unclear
                        },
                    ),
                    color = c.textPrimary, fontSize = 13.sp,
                )
                Text(a.compact, color = c.textMono, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapsuleButton(stringResource(R.string.common_copy)) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("RCQ network audit", a.compact))
                        Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
                    }
                    CapsuleButton(stringResource(R.string.qr_share)) {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, a.compact)
                                },
                                context.getString(R.string.qr_share),
                            ),
                        )
                    }
                }
            }
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


/** "How this works" — three questions, one screen.
 *
 *  ⚠ NOT a second carousel, and that distinction is the brief. The carousel
 *  shows what the app can DO, and nobody is confused about that. The confusion
 *  in the reports is three other things: who can read what I send, what an
 *  island is and why there is more than one, and what to do when it stops
 *  working.
 *
 *  It lives in Settings permanently rather than at first launch, because the
 *  question arrives on the third day, by which time an onboarding screen is
 *  long gone.
 */
@Composable
private fun HowItWorksScreen(onBack: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val faqUrl = "https://rcq.app/faq"
    // One list drives both the screen and the clipboard, so a sixth answer
    // cannot ship visible but uncopyable.
    val qa = listOf(
        R.string.how_q1 to R.string.how_a1,
        R.string.how_q2 to R.string.how_a2,
        R.string.how_q3 to R.string.how_a3,
        // Circumvention and onion routing, asked for in report #572 ("в
        // «как это работает» я бы добавил про луковое разделение знания
        // сервера об отправителе и получателе") — in plain words, because
        // the person asking has no reason to know what a circuit is.
        R.string.how_q4 to R.string.how_a4,
        R.string.how_q5 to R.string.how_a5,
    )
    val shareable = remember(qa) {
        buildString {
            appendLine(context.getString(R.string.how_title))
            appendLine()
            qa.forEach { (q, a) ->
                appendLine(context.getString(q))
                appendLine(context.getString(a))
                appendLine()
            }
            append(faqUrl)
        }
    }
    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        // Second half of report #572: the person who asked for these answers
        // also asked to be able to hand them to someone else. That makes the
        // whole explanation the unit, not one question — five clipboard
        // fragments would be five pastes, and answers 4 and 5 only make sense
        // together. It sits in the top bar so a screen that is nothing but
        // text gains no extra row and stays copyable without scrolling first.
        SettingsTopBar(stringResource(R.string.how_title), onBack, trailing = {
            Icon(
                Icons.Filled.ContentCopy,
                stringResource(R.string.how_copy),
                tint = c.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.how_title), shareable))
                        Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
                    }
                    .padding(6.dp)
                    .size(22.dp),
            )
        })
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            qa.forEach { (q, a) -> HowAnswer(stringResource(q), stringResource(a)) }
            SettingsGroup {
                Text(
                    stringResource(R.string.how_more),
                    color = c.accent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { runCatching { uriHandler.openUri(faqUrl) } }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun HowAnswer(question: String, answer: String) {
    val c = RcqTheme.colors
    SettingsGroup {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(question, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(answer, color = c.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun SoundsScreen(onBack: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val masterOn by LocalStores.soundMaster.collectAsState()
    val msgOn by LocalStores.soundMessages.collectAsState()
    val presenceMode by LocalStores.presenceSound.collectAsState()
    val volume by LocalStores.soundVolume.collectAsState()
    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_sounds), onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingToggleRow(stringResource(R.string.snd_master_title), stringResource(R.string.snd_master_desc), masterOn) { LocalStores.setSoundMaster(it) }
            SettingToggleRow(stringResource(R.string.snd_message_title), stringResource(R.string.snd_message_desc), msgOn, enabled = masterOn) { LocalStores.setSoundMessages(it) }
            // Presence: everyone / favourites only / off (#552). Not a toggle,
            // because with a full roster the chime is frequent enough to read
            // as a malfunction, and "off" was the only escape.
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.snd_presence_title),
                        color = if (masterOn) c.textPrimary else c.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    SegmentedPresenceSound(presenceMode, enabled = masterOn) { LocalStores.setPresenceSoundMode(it) }
                    Text(stringResource(R.string.snd_presence_desc), color = c.textSecondary, fontSize = 11.sp)
                }
            }
            // Scale factor for the tone the OPEN app plays — say so. The
            // loudness of the notification itself is Android's, and the row
            // below goes to where that lives. Releasing the thumb plays the
            // message tone so the level is audible while choosing it.
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.snd_volume_title), color = if (masterOn) c.textPrimary else c.textSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("${(volume * 100).toInt()}%", color = c.textSecondary, fontSize = 13.sp)
                    }
                    Slider(
                        value = volume,
                        onValueChange = { LocalStores.setSoundVolume(it) },
                        onValueChangeFinished = { app.rcq.android.media.SoundService.previewMessage() },
                        enabled = masterOn,
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                    )
                    Text(stringResource(R.string.snd_volume_desc), color = c.textSecondary, fontSize = 11.sp)
                }
            }
            SettingsGroup {
                SettingsRow(Icons.Filled.Notifications, stringResource(R.string.snd_system_channel)) {
                    app.rcq.android.push.Push.openMessageChannelSettings(context)
                }
            }
            SectionFooter(stringResource(R.string.snd_footer))
        }
    }
}

/** Everyone / favourites / off for the online-offline chime (#552). */
@Composable
private fun SegmentedPresenceSound(
    mode: LocalStores.PresenceSoundMode,
    enabled: Boolean,
    onPick: (LocalStores.PresenceSoundMode) -> Unit,
) {
    val c = RcqTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgPrimary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(
            LocalStores.PresenceSoundMode.ALL to stringResource(R.string.snd_presence_all),
            LocalStores.PresenceSoundMode.FAVORITES to stringResource(R.string.snd_presence_favorites),
            LocalStores.PresenceSoundMode.OFF to stringResource(R.string.snd_presence_off),
        ).forEach { (m, label) ->
            val sel = mode == m
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(percent = 50))
                    .background(if (sel && enabled) c.accent else Color.Transparent)
                    .clickable(enabled = enabled) { onPick(m) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = when {
                        sel && enabled -> Color.White
                        sel -> c.textSecondary
                        else -> c.textSecondary
                    },
                    fontSize = 12.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}


/** Export the history to a file, or add a file's history back.
 *
 *  Deliberately a plain file and nothing else: no cloud of ours, no account
 *  needed to hold it. The person keeps it wherever they keep things, which on
 *  a phone means their own drive, a USB stick, or a chat with themselves. We
 *  cannot lose what we never had, and we cannot be made to hand it over. */
@Composable
private fun BackupScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var includeMedia by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val exportName = remember {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        "rcq-$d.rcqbak"
    }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = context.getString(R.string.backup_working)
            error = null; result = null
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    app.rcq.android.backup.BackupService.export(session, out, includeMedia) { p ->
                        busy = context.getString(R.string.backup_media_progress, p.done, p.total)
                    }
                } ?: error("cannot write there")
            }.onSuccess { r ->
                // Said out loud rather than left to the manifest: attachments
                // are pulled from the island as the file is written, so a blob
                // that has aged off simply is not there, and the only moment
                // the person can act on that is now.
                result = when {
                    !includeMedia -> context.getString(R.string.backup_saved)
                    r.mediaMissed > 0 ->
                        context.getString(R.string.backup_saved_media_missed, r.messages, r.media, r.mediaMissed)
                    else -> context.getString(R.string.backup_saved_media, r.messages, r.media)
                }
            }.onFailure { error = it.message }
            busy = null
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = context.getString(R.string.backup_working)
            error = null; result = null
            val phrase = session.recoveryPhrase()?.joinToString(" ")
            if (phrase == null) {
                error = context.getString(R.string.backup_no_phrase)
                busy = null
                return@launch
            }
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    app.rcq.android.backup.BackupService.restore(session, input, phrase) { p ->
                        busy = context.getString(R.string.backup_restore_progress, p.done, p.total)
                    }
                } ?: error("cannot read that file")
            }.onSuccess { r ->
                // Built up rather than picked from four fixed sentences: a
                // restore can hit any combination of these, and the two that
                // are usually zero should not cost a phrase when they are.
                result = buildString {
                    append(context.getString(R.string.backup_restored, r.added, r.skipped))
                    if (r.deletedHere > 0) {
                        append(' ')
                        append(context.getString(R.string.backup_restored_deleted, r.deletedHere))
                    }
                    if (r.unreadable > 0) {
                        append(' ')
                        append(context.getString(R.string.backup_restored_unreadable, r.unreadable))
                    }
                }
            }.onFailure { error = it.message }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_backup), onBack)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionFooter(stringResource(R.string.backup_intro))
            SettingsGroup {
                SettingToggleRow(
                    stringResource(R.string.backup_media_title),
                    stringResource(R.string.backup_media_desc),
                    includeMedia,
                ) { includeMedia = it }
            }
            CapsuleButton(stringResource(R.string.backup_export), enabled = busy == null) {
                exporter.launch(exportName)
            }
            SectionFooter(stringResource(R.string.backup_restore_desc))
            CapsuleButton(stringResource(R.string.backup_restore), enabled = busy == null) {
                importer.launch("*/*")
            }
            busy?.let { Text(it, color = c.textSecondary, fontSize = 13.sp) }
            result?.let { Text(it, color = c.accent, fontSize = 13.sp) }
            error?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp) }
            SectionFooter(stringResource(R.string.backup_warning))
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
            items(LanguageManager.available, key = { it.code }) { lang ->
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
private fun NotificationsScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pushState by remember { mutableStateOf(app.rcq.android.push.Push.pushState(ctx)) }
    // Enabling only ASKS the distributor; the endpoint lands asynchronously in
    // RcqPushService.onNewEndpoint. Reading pushState once at tap time is why
    // the first tap looked like nothing happened and the block only caught up
    // on the second one. Follow the endpoint instead.
    val liveEndpoint by app.rcq.android.push.Push.endpointFlow.collectAsState()
    LaunchedEffect(liveEndpoint) { pushState = app.rcq.android.push.Push.pushState(ctx) }
    var showDistChooser by remember { mutableStateOf(false) }
    var contactReq by remember { mutableStateOf<Boolean?>(null) }
    // What the server's last wake attempt to THIS device's endpoint did. A
    // UnifiedPush distributor that stops accepting wakes (ntfy.sh answers 507
    // once the topic has no connected subscriber, 429 once the rate bucket
    // behind the subscriber's NAT is drained) is otherwise completely silent:
    // the user just stops getting notifications with nothing to look at.
    var pushError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        contactReq = session.loadPushPrefs()?.contact_requests
        val myHost = app.rcq.android.push.Push.savedEndpoint(ctx)
            ?.substringAfter("://", "")?.substringBefore('/')?.takeIf { it.isNotBlank() }
        val mine = session.loadPushHealth()?.devices.orEmpty()
            .filter { it.platform == "android-up" && myHost != null && it.host == myHost }
        // Only complain when every registration on this host is failing — one
        // healthy row means wakes are landing somewhere.
        pushError = if (mine.isNotEmpty() && mine.all { it.last_error != null }) mine.first().last_error else null
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_notifications), onBack)
        // Scrollable, like every other settings sub-screen. This one was a
        // plain Column, so anything past the fold was simply unreachable: a
        // tester reported it, and the explanatory card added later made the
        // screen taller and the cut-off worse.
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Push delivery (UnifiedPush / ntfy) ──
            SectionLabel(stringResource(R.string.notif_delivery))
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (pushState) {
                        app.rcq.android.push.Push.PushState.CONNECTED -> {
                            Text(stringResource(R.string.notif_push_on), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val dist = app.rcq.android.push.Push.savedDistributor(ctx)
                                ?.let { app.rcq.android.push.Push.distributorLabel(ctx, it) } ?: ""
                            Text(stringResource(R.string.notif_push_via, dist), color = c.textSecondary, fontSize = 12.sp)
                            // Registered, but the distributor is rejecting our
                            // wakes: say which failure it is and what fixes it.
                            pushError?.let { err ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.notif_push_broken_title),
                                    color = c.statusBusy, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    when (err) {
                                        "507" -> stringResource(R.string.notif_push_broken_507)
                                        "429" -> stringResource(R.string.notif_push_broken_429)
                                        else -> stringResource(R.string.notif_push_broken_other, err)
                                    },
                                    color = c.textSecondary, fontSize = 12.sp,
                                )
                                // Both of those failures are the public ntfy's
                                // rate gates, and neither exists on our own
                                // server — so offer the one-tap way out rather
                                // than explaining it and leaving the user to
                                // find the chooser.
                                if (app.rcq.android.push.Push.savedDistributor(ctx) != ctx.packageName) {
                                    Text(
                                        stringResource(R.string.notif_push_switch_builtin),
                                        color = c.accent, fontSize = 14.sp,
                                        modifier = Modifier.padding(top = 4.dp).clickable {
                                            app.rcq.android.push.Push.chooseDistributor(ctx, ctx.packageName)
                                            pushState = app.rcq.android.push.Push.pushState(ctx)
                                            pushError = null
                                        },
                                    )
                                }
                            }
                            // Change / reset the provider — the missing "switch
                            // distributor" affordance. Opens a chooser when more
                            // than one is installed, else lets you disable.
                            Text(
                                stringResource(R.string.notif_push_change), color = c.accent, fontSize = 14.sp,
                                modifier = Modifier.padding(top = 4.dp).clickable { showDistChooser = true },
                            )
                        }
                        app.rcq.android.push.Push.PushState.DISTRIBUTOR_AVAILABLE -> {
                            Text(stringResource(R.string.notif_push_off), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.notif_push_enable_hint), color = c.textSecondary, fontSize = 12.sp)
                            Text(
                                stringResource(R.string.notif_push_enable), color = c.accent, fontSize = 14.sp,
                                modifier = Modifier.padding(top = 4.dp).clickable {
                                    // More than one installed -> let the user pick;
                                    // otherwise just enable the only one.
                                    if (app.rcq.android.push.Push.availableDistributors(ctx).size > 1) {
                                        showDistChooser = true
                                    } else if (app.rcq.android.push.Push.enablePush(ctx)) {
                                        pushState = app.rcq.android.push.Push.pushState(ctx)
                                    }
                                },
                            )
                        }
                        app.rcq.android.push.Push.PushState.NO_DISTRIBUTOR -> {
                            Text(stringResource(R.string.notif_push_off), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.notif_push_ntfy_hint), color = c.textSecondary, fontSize = 12.sp)
                            Text(
                                stringResource(R.string.notif_push_install_ntfy), color = c.accent, fontSize = 14.sp,
                                modifier = Modifier.padding(top = 4.dp).clickable { app.rcq.android.push.Push.openNtfyInstall(ctx) },
                            )
                        }
                    }
                }
            }
            // The built-in distributor's permanent notice: explain WHY it
            // exists and hand the user the honest way to hide it (blocking the
            // rcq_push_service channel; the socket keeps running). Only shown
            // while the built-in delivery is actually the active distributor.
            if (pushState == app.rcq.android.push.Push.PushState.CONNECTED &&
                app.rcq.android.push.Push.savedDistributor(ctx) == ctx.packageName
            ) {
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.notif_push_notice_title), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.notif_push_notice_body), color = c.textSecondary, fontSize = 12.sp)
                        Text(
                            stringResource(R.string.notif_push_notice_hide), color = c.accent, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp).clickable {
                                app.rcq.android.push.Push.openPushServiceChannelSettings(ctx)
                            },
                        )
                    }
                }
            }
            // Full-screen incoming-call access (Android 14+). Without it an
            // incoming call degrades to a heads-up banner that's easy to miss —
            // surface a one-tap grant only while it's actually ungranted.
            if (!app.rcq.android.push.Push.fullScreenIntentGranted(ctx)) {
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.notif_fsi_title), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.notif_fsi_hint), color = c.textSecondary, fontSize = 12.sp)
                        Text(
                            stringResource(R.string.notif_fsi_grant), color = c.accent, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp).clickable { app.rcq.android.push.Push.openFullScreenIntentSettings(ctx) },
                        )
                    }
                }
            }
            if (showDistChooser) {
                val dists = app.rcq.android.push.Push.availableDistributors(ctx)
                val saved = app.rcq.android.push.Push.savedDistributor(ctx)
                // Each option carries a second line, so these stay hand-built
                // rows rather than RcqAskSheet actions.
                RcqSheet(
                    onDismiss = { showDistChooser = false },
                    title = stringResource(R.string.notif_push_choose_title),
                ) {
                    if (dists.isEmpty()) {
                        Text(stringResource(R.string.notif_push_ntfy_hint), color = c.textSecondary, fontSize = 13.sp)
                    }
                    dists.forEach { pkg ->
                        val current = pkg == saved
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                app.rcq.android.push.Push.chooseDistributor(ctx, pkg)
                                showDistChooser = false
                                pushState = app.rcq.android.push.Push.pushState(ctx)
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(
                                app.rcq.android.push.Push.distributorLabel(ctx, pkg) + if (current) "  ✓" else "",
                                color = if (current) c.accent else c.textPrimary, fontSize = 15.sp,
                            )
                            // Name each option's trade-off so "экономный
                            // режим" is a visible choice, not a hidden one.
                            Text(
                                if (pkg == ctx.packageName) stringResource(R.string.notif_push_dist_hint_builtin)
                                else stringResource(R.string.notif_push_dist_hint_other),
                                color = c.textSecondary, fontSize = 11.sp,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.divider))
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            app.rcq.android.push.Push.resetDistributor(ctx)
                            showDistChooser = false
                            pushState = app.rcq.android.push.Push.pushState(ctx)
                        }.padding(vertical = 10.dp),
                    ) {
                        Text(stringResource(R.string.notif_push_disable), color = c.statusBusy, fontSize = 15.sp)
                        Text(stringResource(R.string.notif_push_disable_hint), color = c.textSecondary, fontSize = 11.sp)
                    }
                    SheetGap()
                    TextButton(onClick = { showDistChooser = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_cancel), color = c.accent)
                    }
                }
            }
            // ── Categories (parity with the iOS Notifications screen) ──
            SectionLabel(stringResource(R.string.notif_categories))
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.notif_contact_requests), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.notif_contact_requests_desc), color = c.textSecondary, fontSize = 11.sp)
                    }
                    // No placeholder value into a live Switch: rendering
                    // `?: true` before the async loadPushPrefs answer made the
                    // thumb visibly animate to the real value on screen entry
                    // (the "toggle flips by itself" report). A Switch that
                    // ENTERS composition at its real value doesn't animate; the
                    // fixed-size Spacer keeps the row height stable meanwhile.
                    val cr = contactReq
                    if (cr == null) {
                        // 52x48, not the 52x32 track: M3's Switch applies
                        // minimumInteractiveComponentSize, so the track sits
                        // centred in a 48dp touch target. Sizing to the track
                        // would swap the thumb animation for a 16dp row jump.
                        Spacer(Modifier.size(52.dp, 48.dp))
                    } else {
                        Switch(
                            checked = cr,
                            onCheckedChange = { v ->
                                contactReq = v
                                scope.launch { if (!session.setContactRequestsPush(v)) contactReq = cr }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.notif_perchat_note),
                color = c.textSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
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
/** Empty/error state of the linked-devices list — same shape for both, only
 *  the line of text differs. */
@Composable
private fun LinkedDevicesPlaceholder(text: String) {
    val c = RcqTheme.colors
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Filled.Devices, null, tint = c.textSecondary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, color = c.textPrimary, fontSize = 15.sp)
    }
}

@Composable
private fun LinkedDevicesScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Held by Session, not here: a device linked or revoked ANYWHERE (this
    // phone, the desktop signing itself out) arrives as a socket event and
    // refreshes the list while it is on screen. It used to be a local
    // remember loaded exactly once, so the only way to see a change was to
    // leave the screen and come back.
    val devices by session.devices.collectAsState() // null = loading
    var failed by remember { mutableStateOf(false) }
    var showHow by remember { mutableStateOf(false) }

    // In-app QR scanner: decode chat.rcq.app's connect-phone QR and feed it into
    // the same WebLinkRequest confirm flow a deep link uses. Removes the reliance
    // on the stock camera understanding the rcq:// scheme.
    val scanLauncher = rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        result.contents?.trim()?.let { raw ->
            val req = app.rcq.android.WebLinkRequest.fromUri(android.net.Uri.parse(raw))
            if (req != null) app.rcq.android.WebLinkRequest.pending.value = req
            else Toast.makeText(context, context.getString(R.string.linked_devices_scan_invalid), Toast.LENGTH_SHORT).show()
        }
    }
    fun launchScan() {
        val opts = com.journeyapps.barcodescanner.ScanOptions().apply {
            setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            setPrompt(context.getString(R.string.linked_devices_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        scanLauncher.launch(opts)
    }

    suspend fun reload() {
        failed = false
        runCatching { session.refreshDevices() }.onFailure { failed = true }
    }
    LaunchedEffect(Unit) { reload() }

    if (showHow) {
        // Instructions, nothing to choose: the sheet's own last row closes it.
        RcqAskSheet(
            onDismiss = { showHow = false },
            title = stringResource(R.string.linked_devices_connect),
            body = stringResource(R.string.linked_devices_connect_steps),
            actions = emptyList(),
            cancelLabel = stringResource(R.string.common_close),
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
                .background(c.accent).clickable { launchScan() }.padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.linked_devices_scan), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { showHow = true }) {
                Text(stringResource(R.string.linked_devices_how), color = c.accent, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        when (val list = devices) {
            // Nothing loaded yet: the spinner while the first read is in
            // flight, the error state once it has failed. The list stays null
            // on failure so a later refresh still fills it in, instead of
            // being frozen as a convincing-looking "no devices".
            null -> if (failed) {
                LinkedDevicesPlaceholder(stringResource(R.string.linked_devices_error))
            } else {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.accent, modifier = Modifier.size(28.dp))
                }
            }
            else -> if (list.isEmpty()) {
                LinkedDevicesPlaceholder(stringResource(R.string.linked_devices_empty))
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
        ConfirmSheet(
            title = stringResource(R.string.csrv_confirm_title, target),
            body = stringResource(R.string.csrv_confirm_body, target, current),
            confirm = stringResource(R.string.common_switch), destructive = true,
            onConfirm = { confirm = false; applySwitch(draft, invite.trim().ifBlank { null }) },
            onDismiss = { confirm = false },
        )
    }
    if (resetting) {
        ConfirmSheet(
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
        RcqAskSheet(
            // Both rows stay inert while the promotion is in flight: that is
            // the `enabled = !busy` the two buttons used to carry.
            onDismiss = { if (!busy) promoteTarget = null },
            title = stringResource(R.string.backup_island_promote_title),
            body = stringResource(R.string.backup_island_promote_body, target.host),
            actions = listOf(
                SheetAction(stringResource(R.string.backup_island_promote_confirm)) {
                    if (!busy) {
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
                    }
                },
            ),
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
                                    // Islands number independently: we ASK for the
                                    // same UIN and take what we get. A short number
                                    // is usually taken elsewhere, and saying so
                                    // beats leaving the user to wonder why their
                                    // backup has a different number (user report).
                                    Text(
                                        if (h.uin == session.uin) stringResource(R.string.backup_island_row_uin, h.uin)
                                        else stringResource(R.string.backup_island_row_uin_diff, h.uin, session.uin ?: 0),
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
    // Orientation applied here too (#527) — the Hall of Fame picture is picked
    // the same way, from the same camera.
    val src = (if (isGif) gifFirstFrame(raw) else decodeUpright(raw)) ?: return null
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
        // The WEIGHT belongs on the title, not on a spacer after it. Row
        // measures unweighted children first, so an unweighted title claimed
        // the whole width and whatever came after it was squeezed into what
        // was left — on "Редактировать профиль" that broke "Сохранить" into a
        // column of single letters (reported by vss). Weighted, the title
        // takes the leftovers instead of the trailing action, and truncates
        // rather than wrapping when even that is not enough.
        Text(
            title,
            color = c.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
    // Default OFF, and re-defaulted every time the form opens.
    var wipeServer by remember { mutableStateOf(false) }
    // Report #237 (deniability): while unlocked into a DECOY session, the PIN
    // screen must not reveal that a decoy/wipe PIN — or any hidden account —
    // exists. In decoy mode we hide the whole Duress + biometric surface and
    // show only a plausible Change/Remove PIN (Remove is duress-aware in
    // Session.removePin: it wipes the hidden accounts instead of exposing them).
    val decoyModeId by app.rcq.android.data.AccountManager.decoyMode.collectAsState()
    val decoyOwnStore by app.rcq.android.data.AccountManager.decoySession.collectAsState()
    val inDecoyMode = decoyModeId != null || decoyOwnStore
    // Decoy PIN (panic-PIN phase 2): a PIN that reveals only a chosen account.
    var decoyConfigured by remember { mutableStateOf(session.hasDecoyPin) }
    var decoyEditing by remember { mutableStateOf(false) }
    var dpin by remember { mutableStateOf("") }
    var dconfirm by remember { mutableStateOf("") }
    var derror by remember { mutableStateOf<String?>(null) }
    // The decoy is no longer a roster account: it is its own store, seeded
    // with copies of conversations the user picks here.
    var decoyThreads by remember { mutableStateOf(emptySet<Int>()) }
    var decoyCandidates by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
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
                RcqField(
                    value = pin,
                    onValueChange = { if (onlyDigits(it)) pin = it },
                    placeholder = stringResource(R.string.pin_new),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RcqField(
                    value = confirm,
                    onValueChange = { if (onlyDigits(it)) confirm = it },
                    placeholder = stringResource(R.string.pin_confirm),
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
                // "Also erase the account on the server", DEFAULT OFF. The flag
                // is written into the WIPE SLOT itself, never into prefs: prefs
                // are readable and writable by anyone holding an unlocked
                // phone, and switching this off is the first thing someone who
                // found the feature would do. Default off is also what every
                // locale's copy has always promised ("on this device").
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { wipeServer = !wipeServer }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (wipeServer) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        null,
                        tint = if (wipeServer) Color(0xFFE5484D) else c.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.pin_wipe_server_label), color = c.textPrimary, fontSize = 14.sp)
                }
                Text(stringResource(R.string.pin_wipe_server_desc), color = c.textSecondary, fontSize = 12.sp)
                RcqField(
                    value = wpin,
                    onValueChange = { if (onlyDigits(it)) wpin = it },
                    placeholder = stringResource(R.string.pin_wipe_new),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RcqField(
                    value = wconfirm,
                    onValueChange = { if (onlyDigits(it)) wconfirm = it },
                    placeholder = stringResource(R.string.pin_confirm),
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
                        val ok = withContext(Dispatchers.Default) { session.setWipePin(wpin, wipeServer) }
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
                Text(stringResource(R.string.pin_decoy_plausibility), color = c.textSecondary, fontSize = 13.sp)
                Text(stringResource(R.string.pin_decoy_pick), color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                SettingsGroup {
                    DecoyThreadPicker(decoyCandidates, decoyThreads) { uin ->
                        decoyThreads = if (uin in decoyThreads) decoyThreads - uin else decoyThreads + uin
                    }
                }
                RcqField(
                    value = dpin, onValueChange = { if (onlyDigits(it)) dpin = it },
                    placeholder = stringResource(R.string.pin_decoy_new),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                RcqField(
                    value = dconfirm, onValueChange = { if (onlyDigits(it)) dconfirm = it },
                    placeholder = stringResource(R.string.pin_confirm),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                derror?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp) }
                CapsuleButton(
                    label = if (busy) stringResource(R.string.pin_busy) else stringResource(R.string.common_save),
                    enabled = dpin.length >= 4 && decoyThreads.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (dpin != dconfirm) { derror = context.getString(R.string.pin_mismatch); return@CapsuleButton }
                    if (decoyThreads.isEmpty()) { derror = context.getString(R.string.pin_decoy_needs_chats); return@CapsuleButton }
                    scope.launch {
                        busy = true; derror = null
                        val ok = withContext(Dispatchers.Default) { session.setDecoyPin(dpin, decoyThreads.toList()) }
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
                if (bioHardware && !inDecoyMode) {
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
                if (!inDecoyMode) {
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
                            wipeEditing = true; wpin = ""; wconfirm = ""; werror = null; wipeServer = false
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
                        } else {
                            // No account requirement any more: the decoy has its
                            // own store and its own identity, so one account is
                            // enough. What it needs is conversations to show.
                            SettingsRow(Icons.Filled.Lock, stringResource(R.string.pin_decoy_set)) {
                                decoyEditing = true; dpin = ""; dconfirm = ""; derror = null
                                decoyThreads = emptySet()
                                decoyCandidates = session.decoySeedCandidates()
                            }
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
                } // end !inDecoyMode duress section
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

/** Which way a message is dragged to quote it (#526). Telegram pulls left,
 *  WhatsApp and Signal pull right, and people arrive with the habit of whichever
 *  they used before, so this is a choice rather than a decision. */
@Composable
private fun SegmentedSwipeSide(side: LocalStores.SwipeReplySide, onPick: (LocalStores.SwipeReplySide) -> Unit) {
    val c = RcqTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgSecondary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(
            LocalStores.SwipeReplySide.LEFT to stringResource(R.string.settings_swipe_reply_left),
            LocalStores.SwipeReplySide.RIGHT to stringResource(R.string.settings_swipe_reply_right),
        ).forEach { (v, label) ->
            val sel = side == v
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(if (sel) c.accent else Color.Transparent)
                    .clickable { onPick(v) }.padding(vertical = 9.dp),
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
    RcqField(
        value = value,
        onValueChange = onChange,
        placeholder = label,
        singleLine = minLines == 1,
        minLines = minLines,
        keyboardOptions = if (keyboardDigits) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Confirm/cancel prompt. Cancel comes from [RcqAskSheet] itself. */
@Composable
private fun ConfirmSheet(title: String, body: String, confirm: String, destructive: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    RcqAskSheet(
        onDismiss = onDismiss,
        title = title,
        body = body,
        actions = listOf(SheetAction(confirm, destructive = destructive, onClick = onConfirm)),
    )
}

private fun appVersion(context: Context): String = runCatching {
    val pm = context.packageManager.getPackageInfo(context.packageName, 0)
    "${pm.versionName}"
}.getOrDefault("0.1")
