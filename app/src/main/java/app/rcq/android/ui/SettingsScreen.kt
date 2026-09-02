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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.material.icons.filled.Palette
// Settings search (#28) index icons.
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
private enum class SettingsRoute { ROOT, HOW_IT_WORKS, PROFILE, APPEARANCE, PRIVACY, NETWORK, NOTIFICATIONS, BLOCKED, CUSTOM_SERVER, SOUNDS, LANGUAGE, APP_ICON, CHAT_BG, HOME_BG, PIN_CODES, DIAGNOSTICS, RECOVERY_PHRASE, BACKUP, UIN_SHOP, MY_UINS, LINKED_DEVICES, BACKUP_ISLAND, MY_REPORTS }

// ── Settings search (#28) ────────────────────────────────────────────
//
// The settings rows are hand-written composables spread over two dozen screens,
// so there is no tree to walk at runtime and no way to read a label back out of
// one. Search therefore runs off a STATIC INDEX declared right here, next to the
// screens it describes, and the index is built from an enum through an
// exhaustive `when`: adding a constant without describing it does not compile.
// That is the whole point of the shape. A new setting can be forgotten in the
// index only by also not adding its constant, which is a visible omission rather
// than a silent one.

/** One constant per row search can find. ⚠ Adding a value here forces a branch
 *  in [SettingsFind.row]; that is deliberate, do not give the `when` an `else`. */
private enum class SettingsFind {
    PROFILE,
    THEME, TEXT_SIZE, LANGUAGE, APP_ICON, CHAT_BG, HOME_BG, ANIM_AVATARS, SWIPE_REPLY,
    HOW_IT_WORKS, PRIVACY, NETWORK, NOTIFICATIONS, SOUNDS, BLOCKED, PIN_CODES,
    RECOVERY, BACKUP, LINKED_DEVICES, BACKUP_ISLAND,
    LAST_SEEN, PROFILE_CARD, CARD_SIDE_LISTS, GENDER_VISIBILITY, INVITE_POLICY,
    READ_RECEIPTS, CALL_POLICY, SCREEN_SECURITY, STRANGERS, HALL_OF_FAME,
    CUSTOM_SERVER, DIAGNOSTICS, RELAY_CALLS, RELAYS, ONION, LOCAL_PROXY, PUSH,
    ISLAND,
    CLEAR_HISTORY,
    UIN_SHOP, MY_UINS, MOVE_UIN, BURN,
    ABOUT, INVITE, SHARE_APK, REPORT_BUG, MY_REPORTS,
}

private class SettingsFindRow(
    val id: SettingsFind,
    val icon: ImageVector,
    /** The label the row really shows. Matched first, and it is already
     *  translated, so every locale searches in its own words for free. */
    val titleRes: Int,
    /** Section heading, shown under the title so a hit says where it lives. */
    val sectionRes: Int,
    /** Sub-screen a hit opens, or null when the row lives on the root list
     *  (a hit then scrolls the root list to it and flashes it). */
    val route: SettingsRoute?,
    /** Extra words that must also find this row.
     *
     *  ⚠ NOT a string resource, on purpose. These are search keys, never drawn,
     *  and they have to work ACROSS languages at once: a Russian speaker running
     *  the English UI types "пин" and must still land on PIN codes, which a
     *  per-locale resource could not do because it would only ever hold the one
     *  locale's words. The visible label above carries the native-language
     *  match; this carries everything else. */
    val aliases: String,
)

/** ⚠ Exhaustive by design (see [SettingsFind]). */
private fun SettingsFind.row(): SettingsFindRow = when (this) {
    SettingsFind.PROFILE -> SettingsFindRow(this, Icons.Filled.Person, R.string.pe_title, R.string.settings_title, SettingsRoute.PROFILE,
        "profile nickname avatar name about age city профиль ник никнейм аватар имя о себе возраст город")
    SettingsFind.THEME -> SettingsFindRow(this, Icons.Filled.DarkMode, R.string.settings_row_theme, R.string.settings_sec_appearance, SettingsRoute.APPEARANCE,
        "theme dark light night colour color тема тёмная темная светлая ночная цвет оформление")
    SettingsFind.TEXT_SIZE -> SettingsFindRow(this, Icons.Filled.FormatSize, R.string.settings_text_size, R.string.settings_sec_appearance, SettingsRoute.APPEARANCE,
        "font size text bigger smaller шрифт размер текст крупнее мельче")
    SettingsFind.LANGUAGE -> SettingsFindRow(this, Icons.Filled.Language, R.string.onboard_language, R.string.settings_sec_appearance, SettingsRoute.LANGUAGE,
        "language locale russian english язык локаль русский английский")
    SettingsFind.APP_ICON -> SettingsFindRow(this, Icons.Filled.Apps, R.string.settings_row_app_icon, R.string.settings_sec_appearance, SettingsRoute.APP_ICON,
        "icon launcher home screen иконка значок ярлык рабочий стол")
    SettingsFind.CHAT_BG -> SettingsFindRow(this, Icons.Filled.Wallpaper, R.string.settings_row_chat_bg, R.string.settings_sec_appearance, SettingsRoute.CHAT_BG,
        "wallpaper background chat picture обои фон подложка чат картинка")
    SettingsFind.HOME_BG -> SettingsFindRow(this, Icons.Filled.Wallpaper, R.string.settings_row_home_bg, R.string.settings_sec_appearance, SettingsRoute.HOME_BG,
        "wallpaper background home list picture обои фон главный экран список картинка")
    SettingsFind.ANIM_AVATARS -> SettingsFindRow(this, Icons.Filled.Mood, R.string.settings_anim_avatars_title, R.string.settings_sec_appearance, SettingsRoute.APPEARANCE,
        "avatar animation gif battery аватар анимация гиф батарея")
    SettingsFind.SWIPE_REPLY -> SettingsFindRow(this, Icons.Filled.SwipeLeft, R.string.settings_swipe_reply, R.string.settings_sec_appearance, SettingsRoute.APPEARANCE,
        "swipe reply quote gesture свайп ответ цитата жест")
    SettingsFind.HOW_IT_WORKS -> SettingsFindRow(this, Icons.Filled.Info, R.string.how_title, R.string.settings_sec_privacy, SettingsRoute.HOW_IT_WORKS,
        "help faq how encryption как это работает справка вопросы шифрование")
    SettingsFind.PRIVACY -> SettingsFindRow(this, Icons.Filled.Lock, R.string.settings_row_privacy, R.string.settings_sec_privacy, SettingsRoute.PRIVACY,
        "privacy visibility who can see приватность приваси видимость кто видит")
    SettingsFind.NETWORK -> SettingsFindRow(this, Icons.Filled.NetworkCheck, R.string.settings_row_network, R.string.settings_sec_privacy, SettingsRoute.NETWORK,
        "network server relay proxy сеть сервер релей прокси обход")
    SettingsFind.NOTIFICATIONS -> SettingsFindRow(this, Icons.Filled.Notifications, R.string.settings_row_notifications, R.string.settings_sec_privacy, SettingsRoute.NOTIFICATIONS,
        "notifications push alerts уведомления пуш пуши оповещения")
    SettingsFind.SOUNDS -> SettingsFindRow(this, Icons.AutoMirrored.Filled.VolumeUp, R.string.settings_row_sounds, R.string.settings_sec_privacy, SettingsRoute.SOUNDS,
        "sound volume ringtone mute звук звуки громкость сигнал беззвучно")
    SettingsFind.BLOCKED -> SettingsFindRow(this, Icons.Outlined.Block, R.string.settings_row_blocked, R.string.settings_sec_privacy, SettingsRoute.BLOCKED,
        "blocked block ban spam блок блокировка чёрный черный список бан спам")
    SettingsFind.PIN_CODES -> SettingsFindRow(this, Icons.Filled.Password, R.string.settings_row_pin_codes, R.string.settings_row_privacy, SettingsRoute.PIN_CODES,
        "pin password passcode lock biometrics duress wipe decoy пин пароль код блокировка отпечаток паника")
    SettingsFind.RECOVERY -> SettingsFindRow(this, Icons.Filled.Key, R.string.settings_row_recovery, R.string.settings_sec_privacy, SettingsRoute.RECOVERY_PHRASE,
        "recovery phrase seed words restore key фраза сид восстановление слова ключ")
    SettingsFind.BACKUP -> SettingsFindRow(this, Icons.Filled.Inventory2, R.string.settings_row_backup, R.string.settings_sec_privacy, SettingsRoute.BACKUP,
        "backup export import history file бэкап бекап резервная копия история экспорт импорт файл")
    SettingsFind.LINKED_DEVICES -> SettingsFindRow(this, Icons.Filled.Devices, R.string.settings_row_linked_devices, R.string.settings_sec_privacy, SettingsRoute.LINKED_DEVICES,
        "devices sessions desktop linked устройства сессии сеансы десктоп компьютер привязанные")
    SettingsFind.BACKUP_ISLAND -> SettingsFindRow(this, Icons.Filled.Dns, R.string.settings_row_backup_island, R.string.settings_sec_privacy, SettingsRoute.BACKUP_ISLAND,
        "standby backup island mirror запасной резервный остров зеркало")
    SettingsFind.LAST_SEEN -> SettingsFindRow(this, Icons.Filled.Visibility, R.string.pv_last_seen, R.string.settings_row_privacy, SettingsRoute.PRIVACY,
        "last seen online presence был в сети последний раз онлайн присутствие")
    SettingsFind.PROFILE_CARD -> SettingsFindRow(this, Icons.Outlined.AccountCircle, R.string.pv_profile_card, R.string.settings_row_privacy, SettingsRoute.PRIVACY,
        "profile card who can open карточка профиля кто может открыть анкета")
    SettingsFind.CARD_SIDE_LISTS -> SettingsFindRow(this, Icons.Filled.VisibilityOff, R.string.pv_card_side_lists, R.string.settings_row_privacy, SettingsRoute.PRIVACY,
        "reactions media viewer members list card карточка реакции просмотр медиа список участников побочные")
    SettingsFind.GENDER_VISIBILITY -> SettingsFindRow(this, Icons.Filled.Person, R.string.common_gender, R.string.settings_row_privacy, SettingsRoute.PRIVACY,
        "gender sex пол гендер")
    SettingsFind.INVITE_POLICY -> SettingsFindRow(this, Icons.Filled.Groups, R.string.pv_invite, R.string.settings_row_privacy, SettingsRoute.PRIVACY,
        "groups invite add me группы приглашение добавить в группу")
    SettingsFind.READ_RECEIPTS -> SettingsFindRow(this, Icons.Filled.DoneAll, R.string.pv_receipts, R.string.settings_row_privacy, SettingsRoute.PRIVACY,
        "read receipts ticks blue seen прочитано галочки отчёт отчет о прочтении")
    SettingsFind.CALL_POLICY -> SettingsFindRow(this, Icons.Filled.Call, R.string.pv_calls, R.string.settings_row_privacy, SettingsRoute.PRIVACY,
        "calls who can call ring звонки кто может звонить вызов")
    SettingsFind.SCREEN_SECURITY -> SettingsFindRow(this, Icons.Filled.NoPhotography, R.string.pv_screen_security, R.string.settings_row_privacy, SettingsRoute.PRIVACY,
        "screenshot screen recording скриншот снимок экрана запись экрана")
    SettingsFind.STRANGERS -> SettingsFindRow(this, Icons.Filled.Inbox, R.string.pv_strangers, R.string.settings_row_privacy, SettingsRoute.PRIVACY,
        "strangers requests quarantine незнакомцы заявки запросы карантин")
    SettingsFind.HALL_OF_FAME -> SettingsFindRow(this, Icons.Filled.EmojiEvents, R.string.pv_hall_of_fame, R.string.settings_row_privacy, SettingsRoute.PRIVACY,
        "hall of fame hof зал славы")
    SettingsFind.CUSTOM_SERVER -> SettingsFindRow(this, Icons.Filled.Dns, R.string.pv_custom_server, R.string.settings_row_network, SettingsRoute.NETWORK,
        "server island host custom self hosted сервер остров хост свой самохостинг")
    SettingsFind.DIAGNOSTICS -> SettingsFindRow(this, Icons.Filled.NetworkCheck, R.string.diag_title, R.string.settings_row_network, SettingsRoute.DIAGNOSTICS,
        "diagnostics connection ping check диагностика соединение связь проверка")
    SettingsFind.RELAY_CALLS -> SettingsFindRow(this, Icons.Filled.VpnLock, R.string.pv_relay_calls, R.string.settings_row_network, SettingsRoute.NETWORK,
        "calls relay ip address звонки релей адрес айпи")
    SettingsFind.RELAYS -> SettingsFindRow(this, Icons.Filled.Shield, R.string.pv_obfuscated, R.string.settings_row_network, SettingsRoute.NETWORK,
        "relay relays blocked censorship tunnel vpn релей релеи обход блокировка туннель")
    SettingsFind.ONION -> SettingsFindRow(this, Icons.Filled.Public, R.string.pv_onion, R.string.settings_row_network, SettingsRoute.NETWORK,
        "onion tor routing луковая маршрутизация тор")
    SettingsFind.LOCAL_PROXY -> SettingsFindRow(this, Icons.Filled.VpnKey, R.string.pv_localproxy, R.string.settings_row_network, SettingsRoute.NETWORK,
        "proxy socks tor i2p прокси прокся сокс тор")
    SettingsFind.PUSH -> SettingsFindRow(this, Icons.Filled.Notifications, R.string.notif_push, R.string.settings_row_notifications, SettingsRoute.NOTIFICATIONS,
        "push delivery ntfy background пуш доставка фон уведомления")
    SettingsFind.ISLAND -> SettingsFindRow(this, Icons.Filled.Dns, R.string.settings_sec_island, R.string.settings_sec_island, null,
        "island server rules welcome остров сервер правила приветствие")
    SettingsFind.CLEAR_HISTORY -> SettingsFindRow(this, Icons.Filled.DeleteSweep, R.string.settings_row_clear_history, R.string.settings_sec_history, null,
        "clear history delete messages очистить историю удалить сообщения переписку")
    SettingsFind.UIN_SHOP -> SettingsFindRow(this, Icons.Filled.Sell, R.string.settings_row_uin_shop, R.string.settings_sec_account, SettingsRoute.UIN_SHOP,
        "uin shop number buy short магазин номер купить короткий")
    SettingsFind.MY_UINS -> SettingsFindRow(this, Icons.Filled.Inventory2, R.string.settings_row_my_uins, R.string.settings_sec_account, SettingsRoute.MY_UINS,
        "my uins numbers owned мои номера уин")
    SettingsFind.MOVE_UIN -> SettingsFindRow(this, Icons.Filled.Autorenew, R.string.settings_row_move_uin, R.string.settings_sec_account, null,
        "move new uin change number сменить номер переехать новый уин")
    SettingsFind.BURN -> SettingsFindRow(this, Icons.Filled.LocalFireDepartment, R.string.settings_row_burn, R.string.settings_sec_account, null,
        "burn delete account wipe удалить аккаунт сжечь стереть")
    SettingsFind.ABOUT -> SettingsFindRow(this, Icons.Filled.Info, R.string.settings_row_about, R.string.settings_sec_about, null,
        "about version update source о программе версия обновление обновить исходники")
    SettingsFind.INVITE -> SettingsFindRow(this, Icons.Filled.PersonAdd, R.string.settings_row_invite, R.string.settings_sec_about, null,
        "invite friend link пригласить друга ссылка")
    SettingsFind.SHARE_APK -> SettingsFindRow(this, Icons.Filled.Share, R.string.settings_row_share_app, R.string.settings_sec_about, null,
        "share apk send app поделиться апк передать приложение")
    SettingsFind.REPORT_BUG -> SettingsFindRow(this, Icons.Filled.BugReport, R.string.settings_row_report_bug, R.string.settings_sec_about, null,
        "bug report problem feedback баг ошибка репорт сообщить проблема отзыв")
    SettingsFind.MY_REPORTS -> SettingsFindRow(this, Icons.Outlined.Flag, R.string.myreports_title, R.string.settings_sec_about, SettingsRoute.MY_REPORTS,
        "my reports answers мои репорты обращения ответы")
}

/** The whole index, in screen order. */
private val settingsFindIndex: List<SettingsFindRow> = SettingsFind.entries.map { it.row() }

/** Fold a query or a haystack down to what matching should ignore: case, and
 *  the ё/е split that makes "тёмная" and "темная" two different words. */
private fun settingsSearchFold(s: String): String =
    s.lowercase().replace('ё', 'е').replace('ў', 'у')

/** Every whitespace-separated word of [query] has to appear somewhere in the
 *  row's title, section or aliases. AND, not OR: "фон чат" should narrow, not
 *  return every row that mentions a chat. */
private fun settingsFindMatches(haystack: String, queryWords: List<String>): Boolean =
    queryWords.all { haystack.contains(it) }

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
    // Deep-link: a tapped "a new device connected to this account" wake. The
    // only reason to open that notification is to look at the list, and it
    // used to land on the chat list and leave the person to find it (#672).
    openLinkedDevices: Boolean = false,
) {
    fun deepLinkRoute() = when {
        openMyReports -> SettingsRoute.MY_REPORTS
        openLinkedDevices -> SettingsRoute.LINKED_DEVICES
        openDiagnostics -> SettingsRoute.DIAGNOSTICS
        openBackupIsland -> SettingsRoute.BACKUP_ISLAND
        else -> SettingsRoute.ROOT
    }
    var route by remember { mutableStateOf(deepLinkRoute()) }
    // ⚠ Not only the initial value. Settings is kept alive by the state holder,
    // so a notification tapped while it is already open changes the flags and
    // nothing else: the screen sat where it was and the tap did nothing.
    LaunchedEffect(openMyReports, openLinkedDevices, openDiagnostics, openBackupIsland) {
        val wanted = deepLinkRoute()
        if (wanted != SettingsRoute.ROOT) route = wanted
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
            // Their rows moved off the root (founder item L2.16): the pickers
            // live on Appearance, the PIN screen on Privacy. Back follows the
            // rows, same rule as Diagnostics above.
            SettingsRoute.APP_ICON, SettingsRoute.CHAT_BG, SettingsRoute.HOME_BG -> SettingsRoute.APPEARANCE
            SettingsRoute.PIN_CODES -> SettingsRoute.PRIVACY
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
    // last fields sat behind the keyboard. Chat and Random already did this per
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
        SettingsRoute.APPEARANCE -> AppearanceScreen(
            onOpen = { route = it },
        ) { route = SettingsRoute.ROOT }
        SettingsRoute.PRIVACY -> PrivacyScreen(
            session,
            onOpenPinCodes = { route = SettingsRoute.PIN_CODES },
        ) { route = SettingsRoute.ROOT }
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
        // Back from the icon/wallpaper pickers returns to Appearance, where
        // their rows live now, mirroring the Diagnostics -> Network rule.
        SettingsRoute.APP_ICON -> AppIconScreen { route = SettingsRoute.APPEARANCE }
        SettingsRoute.CHAT_BG -> ChatBackgroundScreen { route = SettingsRoute.APPEARANCE }
        SettingsRoute.HOME_BG -> HomeBackgroundScreen { route = SettingsRoute.APPEARANCE }
        SettingsRoute.BLOCKED -> BlockedUsersScreen(session) { route = SettingsRoute.ROOT }
        // The PIN row sits on the Privacy screen now; back follows it there.
        // A search hit can still open PIN codes straight from the root, and
        // back then lands on Privacy, exactly like Diagnostics -> Network.
        SettingsRoute.PIN_CODES -> PinCodesScreen(session) { route = SettingsRoute.PRIVACY }
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
    val contacts by session.contacts.collectAsState()
    val uinShopEnabled by session.uinShopEnabled.collectAsState()
    // An island that runs no report desk gets neither the bug form nor the
    // answers screen; hoisted because search has to hide those rows too.
    val reportsOn by session.reportsEnabled.collectAsState()
    // Flagship-only surface, gated like the UIN shop. Its row lives on the
    // Privacy screen, and search must not point at it where it is hidden.
    val hofOffered by session.hallOfFameEnabled.collectAsState()
    // How many numbers this account holds besides the one it uses. Decides
    // whether "My numbers" is worth a row on an island with no shop; a server
    // that predates /uin/mine answers 404 and it stays at zero.
    var heldCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        heldCount = runCatching { session.myUins().owned.size }.getOrDefault(0)
        // "Stay visible after you leave" is gone (see PrivacyScreen). Its local
        // anchor is what the home countdown chip ticks off, and a phone that had
        // the switch on keeps a live anchor for up to 24h after the update, so
        // the chip would outlive the feature. Dropping it here retires it on the
        // first visit to Settings; the chip itself is HomeScreen's to delete.
        app.rcq.android.data.LocalStores.clearPresenceWindow()
        // ⚠⚠ A REMOVED FEATURE HAS TO ANSWER false, NOT VANISH. Dropping the
        // switch from Privacy also dropped the only way to turn the island's
        // copy of the flag off, and an island that has not taken the 23.08
        // update still honours a `presence_persistent = true` a previous build
        // set. Said once per account, remembered only when the island took it,
        // so an offline visit tries again next time. See
        // [LocalStores.presenceRetired].
        if (!app.rcq.android.data.LocalStores.presenceRetired()) {
            val retired = runCatching {
                session.updateProfile(
                    RcqApi.UpdateMeBody(presence_persistent = false, presence_ttl_minutes = 0),
                )
            }.getOrNull() != null
            if (retired) app.rcq.android.data.LocalStores.markPresenceRetired()
        }
    }
    var confirmBurn by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmMigrate by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showBugReport by remember { mutableStateOf(false) }
    // rememberSaveable, not remember: the draft has to outlive leaving this
    // screen for another settings page and a rotation, not only the accidental
    // sheet dismiss it was hoisted for (#685). Attachments stay in `remember`;
    // they are content Uris whose permission grants do not survive the process
    // anyway, so saving them would restore rows that cannot be read.
    var bugText by rememberSaveable { mutableStateOf("") }
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

    // ── search (#28) ─────────────────────────────────────────────────
    // The magnifier lives in the top bar, where Android puts it, and takes the
    // bar over while it is open (the platform SearchView pattern). iOS puts it
    // where its Share button was; the behaviour either side is the same: type,
    // tap a hit, land on the row.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // A hit on a row that lives on THIS list scrolls to it and flashes it.
    // ⚠ The jump carries a nonce, so asking for the same row twice still fires,
    // and so the effect below never has to clear its own key (an effect keyed on
    // state it resets kills itself before it has done anything).
    var jump by remember { mutableStateOf<Pair<Int, SettingsFind>?>(null) }
    var flash by remember { mutableStateOf<SettingsFind?>(null) }
    val rootScroll = rememberScrollState()
    // Where every anchored row sits, filled in on layout. The root list is a
    // plain scrolling Column, so every row composes and measures whether or not
    // it is on screen and the map is complete after the first frame.
    //
    // ⚠ Each callback records ONLY what it can see by itself, and the two are
    // subtracted later, in the effect. Recording "row minus container" at layout
    // time would depend on the container's callback having already run this
    // frame, and the first jump after opening Settings would scroll to the wrong
    // place whenever it had not.
    val rowInRoot = remember { mutableStateMapOf<SettingsFind, Int>() }
    var listTopInRoot by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(jump) {
        val target = jump?.second ?: return@LaunchedEffect
        // The results list was covering the settings list a frame ago. Let it
        // lay out before measuring anything: the offsets left over from before
        // the search opened are usually right, but the SCROLL RANGE is not
        // published until the list has been through a layout, and animating to
        // an offset while the range still reads zero clamps the jump to the top.
        kotlinx.coroutines.delay(48)
        var y = rowInRoot[target]
        var tries = 0
        while (y == null && tries < 8) {
            kotlinx.coroutines.delay(24)
            y = rowInRoot[target]
            tries++
        }
        y?.let { rootScroll.animateScrollTo((it - listTopInRoot.toInt() - 120).coerceAtLeast(0)) }
        flash = target
        kotlinx.coroutines.delay(1600)
        if (flash == target) flash = null
    }

    fun openHit(hit: SettingsFindRow) {
        searching = false
        query = ""
        val r = hit.route
        // A row that leads somewhere opens it. A row that lives on this list is
        // shown, never fired: search must not be a second way to press "Burn
        // account".
        if (r != null) onOpen(r) else jump = ((jump?.first ?: 0) + 1) to hit.id
    }

    /** Marks a root-list row as [id]'s: records where it sits so a hit can
     *  scroll to it, and tints it while it is the flashed one. Reading `flash`
     *  here is what subscribes this list to the flash, so no row needs to know
     *  about search at all. */
    fun anchor(id: SettingsFind): Modifier = Modifier
        .onGloballyPositioned {
            // Un-scrolled position in the window; the list's own top is taken
            // off later (see the effect above).
            rowInRoot[id] = (it.positionInRoot().y + rootScroll.value).toInt()
        }
        .clip(RoundedCornerShape(14.dp))
        .background(if (flash == id) c.accent.copy(alpha = 0.20f) else Color.Transparent)

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        if (searching) {
            SettingsSearchBar(
                query = query,
                onQuery = { query = it },
                onClose = { searching = false; query = "" },
            )
        } else {
            SettingsTopBar(stringResource(R.string.settings_title), onBack, trailing = {
                Icon(
                    Icons.Filled.Search,
                    stringResource(R.string.settings_search_hint),
                    tint = c.accent,
                    modifier = Modifier.size(24.dp).clickable { searching = true },
                )
            })
        }

        if (searching) {
            // Rows an island does not offer must not be findable either: a hit
            // that opens a screen this build hides is a dead end (a removed
            // feature answers, it does not vanish, and here the answer is "no
            // such row on this island").
            val hidden = remember(uinShopEnabled, heldCount, reportsOn, hofOffered) {
                buildSet {
                    if (!uinShopEnabled) add(SettingsFind.UIN_SHOP)
                    if (!uinShopEnabled && heldCount == 0) add(SettingsFind.MY_UINS)
                    if (!reportsOn) { add(SettingsFind.REPORT_BUG); add(SettingsFind.MY_REPORTS) }
                    if (!hofOffered) add(SettingsFind.HALL_OF_FAME)
                }
            }
            SettingsSearchResults(
                query = query,
                hidden = hidden,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onPick = ::openHit,
            )
        } else {

        Column(
            Modifier.fillMaxWidth().weight(1f)
                .onGloballyPositioned { listTopInRoot = it.positionInRoot().y }
                .verticalScroll(rootScroll).padding(horizontal = 16.dp),
        ) {
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
            val lang by LanguageManager.current.collectAsState()
            SettingsGroup {
                // One door for every visual knob (founder item L2.16): theme,
                // text size, wallpapers, animated avatars and the swipe side
                // moved to [AppearanceScreen]. Language stays out here: it is
                // the row people hunt for first on a phone in the wrong
                // language, and burying it one level down defeats it.
                SettingsRow(Icons.Filled.Palette, stringResource(R.string.settings_sec_appearance)) { onOpen(SettingsRoute.APPEARANCE) }
                Divider()
                SettingsRow(Icons.Filled.Language, stringResource(R.string.onboard_language), value = LanguageManager.displayName(lang)) { onOpen(SettingsRoute.LANGUAGE) }
            }

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
                // PIN codes moved onto the Privacy screen (founder item L2.16,
                // mirroring iOS): one lock door on the root instead of two.
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
                SettingsRow(Icons.Filled.DeleteSweep, stringResource(R.string.settings_row_clear_history), destructive = true, modifier = anchor(SettingsFind.CLEAR_HISTORY)) { confirmClear = true }
            }
            SectionFooter(stringResource(R.string.settings_foot_history))

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.settings_sec_about))
            SettingsGroup {
                SettingsRow(Icons.Filled.Info, stringResource(R.string.settings_row_about), value = appVersion(context), modifier = anchor(SettingsFind.ABOUT)) { showAbout = true }
                Divider()
                // Hand the APK to a friend offline — the only way to install RCQ
                // first-time when rcq.app is blocked (the relays live inside the
                // app, so a brand-new user can't reach the download otherwise).
                // Invite a person who does not have RCQ. Distinct from the APK
                // row below, which solves a different problem (installing when
                // rcq.app is blocked) and hands over a 100MB file — not what
                // anyone sends to say "join me".
                SettingsRow(Icons.Filled.PersonAdd, stringResource(R.string.settings_row_invite), modifier = anchor(SettingsFind.INVITE)) {
                    app.rcq.android.net.UpdateChecker.shareInvite(context, uin)
                }
                Divider()
                SettingsRow(Icons.Filled.Share, stringResource(R.string.settings_row_share_app), modifier = anchor(SettingsFind.SHARE_APK)) {
                    app.rcq.android.net.UpdateChecker.shareApk(context)
                }
                // An island that runs no report desk gets neither entry: a
                // form that answers 403 and a screen that will always be empty
                // are worse than an absent menu item. Flag comes from
                // /server/info; the default is permissive. (Collected at the top
                // of this composable, because search needs it too.)
                if (reportsOn) {
                Divider()
                // Open on an EMPTY form, every field of it. The reset used to
                // clear the text and the sent flag and stop there, so the
                // pictures attached to the last report were still in state and
                // came back attached to the next one (#519: "следующий вызов
                // сообщения о баге показывает ранее приложенный опять
                // прикрепленный файл"). The error line and the in-flight flag
                // are stale for the same reason.
                // ⚠ The draft is NOT cleared here any more. The sheet closes on
                // a swipe, a scrim tap or Back, none of which the user means as
                // "throw this away", and reopening then showed an empty form:
                // an hour of writing gone, more than once, to the person who
                // writes us the most (#685). What must be cleared is the
                // transient state of the LAST send; the text and the pictures
                // are cleared when a report actually goes out, and by Cancel,
                // which is the button that means it.
                SettingsRow(Icons.Filled.BugReport, stringResource(R.string.settings_row_report_bug), modifier = anchor(SettingsFind.REPORT_BUG)) {
                    bugSent = false
                    // ⚠ `bugSending` is NOT reset here. It is the accurate
                    // in-flight flag, set by the coroutine that is still
                    // uploading; clearing it on reopen re-enabled the button and
                    // let the same report go twice.
                    bugError = null
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

            // The island this account lives on, in its own words, in the same
            // place iOS and the desktop put it. It lived only inside Settings →
            // Network, folded into the row that CHANGES the server, and the
            // founder went looking for it in the root list and did not find it.
            // The two jobs are different: this card says where you are, that row
            // is for moving somewhere else.
            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.settings_sec_island))
            Column(anchor(SettingsFind.ISLAND).fillMaxWidth()) {
            SettingsGroup {
                val islandHost = session.currentServer
                val islandInfo by produceState<app.rcq.android.net.RcqApi.ServerInfoResponse?>(
                    initialValue = serverInfoCache[islandHost], islandHost,
                ) {
                    app.rcq.android.net.RcqApi.serverInfoOf(islandHost)?.let {
                        serverInfoCache[islandHost] = it
                        value = it
                    }
                }
                val islandName = islandInfo?.name?.takeIf { it.isNotBlank() }
                val islandRules = islandInfo?.welcome?.takeIf { it.isNotBlank() }
                var showIslandRules by remember { mutableStateOf(false) }
                // Purely informational: which island this account lives on.
                // No chevron and no tap — the rules line below is the one door
                // to the sheet, and a second tappable row opening the same
                // sheet read as a promise of something more (founder, 20.08).
                //
                // The server glyph gave way to the island's own picture: it was
                // the same drawing on every island, on the one row whose whole
                // job is to say WHICH island. An island with no logo keeps its
                // lettered tile, which is still a per-island mark rather than
                // one shared glyph.
                SettingsRow(
                    Icons.Filled.Dns,
                    islandName ?: islandHost,
                    // The host repeats under a name and nowhere else: two lines
                    // saying the same host is one line of noise.
                    value = if (islandName != null) islandHost else null,
                    chevron = false,
                    leading = {
                        IslandAvatar(
                            host = islandHost,
                            logoVersion = islandInfo?.logo_version,
                            name = islandName,
                            // 20dp, matching the glyph on the row below it:
                            // a settings label starts at icon width + 12dp, so
                            // a wider picture on one row alone pushes its text
                            // out of the column every other row shares.
                            size = 20.dp,
                        )
                    },
                ) { }
                // How this island is trusted (design §5.3): through a
                // certificate authority, or by the fingerprint pinned on this
                // device, shown so it can be compared and copied as an
                // address to hand to somebody. Nothing before the first
                // handshake has written a record.
                IslandTrustRow(host = islandHost)
                if (islandRules != null) {
                    Divider()
                    SettingsRow(Icons.Filled.Gavel, stringResource(R.string.island_rules_title)) {
                        showIslandRules = true
                    }
                }
                if (showIslandRules && islandRules != null) {
                    RcqSheet(onDismiss = { showIslandRules = false }, title = islandName ?: islandHost) {
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
                SettingsRow(Icons.Filled.Autorenew, stringResource(R.string.settings_row_move_uin), modifier = anchor(SettingsFind.MOVE_UIN)) { if (!migrating) confirmMigrate = true }
            }
            Text(
                stringResource(R.string.cs_move_footer),
                color = c.textSecondary, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
                textAlign = TextAlign.Center,
            )

            SettingsGroup {
                SettingsRow(Icons.Filled.LocalFireDepartment, stringResource(R.string.settings_row_burn), destructive = true, modifier = anchor(SettingsFind.BURN)) { confirmBurn = true }
            }
            Text(
                stringResource(R.string.cs_burn_footer),
                color = c.textSecondary, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 20.dp),
                textAlign = TextAlign.Center,
            )
        }
        } // end of the "not searching" branch
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
                        // take(), not reject: refusing the whole edit made a long
                        // PASTE look like the field was broken, and the author
                        // trimmed "наугад" (#618). Keep the head, show the meter.
                        onValueChange = { bugText = it.take(session.bugReportTextLimit) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 3,
                        placeholder = stringResource(R.string.bug_report_placeholder),
                    )
                    Text(
                        "${bugText.length} / ${session.bugReportTextLimit}",
                        color = if (session.bugReportTextLimit - bugText.length < 50) Color(0xFFE5484D) else c.textSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
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
                            Session.BugReportResult.SENT -> {
                                // Sent, so the draft has served its purpose and
                                // must not come back attached to the next report
                                // (#519).
                                bugSent = true
                                bugText = ""
                                bugAttachments = emptyList()
                            }
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
                // Cancel is the one exit that means "drop it": the swipe and the
                // scrim keep the draft for the next open (#685).
                TextButton(
                    onClick = {
                        bugText = ""
                        bugAttachments = emptyList()
                        bugError = null
                        showBugReport = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
            // #737: the one place about the app is also where to learn what a
            // relay is when the shield is not on screen to ask.
            RelayLearnMore()
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
                    // ⚠ NOT clipped to a circle. PersonAvatar rounds the picture
                    // itself, and its status badge deliberately sticks out past
                    // the lower-left edge; a circular clip on the wrapper cut
                    // that badge in half, which is what the founder saw here and
                    // nowhere else (24.08). The tap target stays the whole
                    // square, which is what it always was.
                    Box(
                        Modifier.clickable(enabled = !avatarBusy) { avatarPicker.launch("image/*") },
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

/**
 * Privacy choices this device keeps for itself, because the island has no
 * field for them yet.
 *
 * Its own preferences file, keyed by UIN, so two accounts on one phone answer
 * separately and nothing here can collide with [app.rcq.android.data.LocalStores].
 *
 * ⚠⚠ A flag stored here is a STATED PREFERENCE, never a rule. Whatever it says,
 * the surface that would honour it runs on another person's phone against data
 * their app already has. Anything written here needs an island-side field and a
 * server that refuses the fetch before it becomes enforcement, and the string
 * shown next to the switch has to say so.
 */
private object SettingsLocalPrivacy {
    private const val FILE = "rcq_settings_privacy"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "Do not open my profile card from reaction lists, media viewers and
     *  member lists" (#22). Off by default, which is how it has always behaved. */
    private fun cardSideListsKey(uin: Int) = "card_side_lists:$uin"

    fun cardSideListsClosed(context: Context, uin: Int): Boolean =
        prefs(context).getBoolean(cardSideListsKey(uin), false)

    fun setCardSideListsClosed(context: Context, uin: Int, closed: Boolean) {
        prefs(context).edit().putBoolean(cardSideListsKey(uin), closed).apply()
    }
}

/**
 * May we offer a tap through to [subjectUin]'s card from an incidental surface
 * (a media viewer's sender name, a reaction list, a member row)?
 *
 * The one place that question is answered, so a new side list cannot quietly
 * grow a different rule. Item 9(b) added the viewer's sender name to the list
 * of surfaces that ask.
 *
 * ⚠⚠ Read the [SettingsLocalPrivacy] header before trusting this. The switch
 * behind it is a STATED PREFERENCE stored on the device the person set it on,
 * keyed by their UIN, and the island carries no field for it yet. So this
 * answers honestly for an account that lives on THIS phone and answers the
 * default (allowed) for everybody else, exactly as pv_card_side_lists_desc
 * tells the user. When the island grows the field, this function consults the
 * cached profile too and every surface starts honouring it at once, which is
 * the entire reason the check is not inlined at the call sites.
 */
internal fun cardOpenableFromSideList(context: Context, subjectUin: Int): Boolean =
    !SettingsLocalPrivacy.cardSideListsClosed(context, subjectUin)

// ── Appearance (founder item L2.16) ──────────────────────────────────

/** Every visual knob in one place, off the root list: the root had grown to
 *  where theme pills and wallpaper rows buried the doors people actually hunt
 *  for. Same controls, same stores, new address; the root keeps one
 *  "Appearance" row. [onOpen] serves the picker rows (app icon, wallpapers),
 *  whose screens pop back here, not to the root. */
@Composable
private fun AppearanceScreen(onOpen: (SettingsRoute) -> Unit, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val themeMode by LocalStores.themeMode.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgPrimary)
    ) {
        SettingsTopBar(stringResource(R.string.settings_sec_appearance), onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            SegmentedTheme(themeMode) { LocalStores.setThemeMode(it) }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.settings_text_size), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            val fontScale by LocalStores.fontScale.collectAsState()
            SegmentedFontScale(fontScale) { LocalStores.setFontScale(it) }
            SectionFooter(stringResource(R.string.settings_foot_appearance))
            Spacer(Modifier.height(12.dp))
            val animAvatars by LocalStores.animateAvatars.collectAsState()
            SettingsGroup {
                SettingsRow(Icons.Filled.Apps, stringResource(R.string.settings_row_app_icon)) { onOpen(SettingsRoute.APP_ICON) }
                Divider()
                SettingsRow(Icons.Filled.Wallpaper, stringResource(R.string.settings_row_chat_bg)) { onOpen(SettingsRoute.CHAT_BG) }
                SettingsRow(Icons.Filled.Wallpaper, stringResource(R.string.settings_row_home_bg)) { onOpen(SettingsRoute.HOME_BG) }
                Divider()
                // In the same container as the rows above it (founder, 24.08):
                // a lone toggle in a group of its own read as a second group
                // holding a single switch.
                SettingToggleRow(
                    stringResource(R.string.settings_anim_avatars_title),
                    stringResource(R.string.settings_anim_avatars_desc),
                    animAvatars,
                ) { LocalStores.setAnimateAvatars(it) }
                Divider()
                // RCQ Lite as a MODE, not a second app (founder, 30.08). It
                // switches off the heavy things that already have their own
                // toggles, so somebody on an old phone does not have to find
                // three settings and know which of them cost battery. The
                // individual switches stay theirs afterwards: this writes
                // them rather than shadowing them, so turning economy off
                // leaves the phone exactly where the person last set it.
                var economy by remember { mutableStateOf(LocalStores.economyMode()) }
                SettingToggleRow(
                    stringResource(R.string.settings_economy_title),
                    stringResource(R.string.settings_economy_desc),
                    economy,
                ) {
                    economy = it
                    LocalStores.setEconomyMode(it)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.settings_swipe_reply), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            val swipeSide by LocalStores.swipeReplySide.collectAsState()
            SegmentedSwipeSide(swipeSide) { LocalStores.setSwipeReplySide(it) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PrivacyScreen(session: Session, onOpenPinCodes: () -> Unit, onBack: () -> Unit) {
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
    var hofOptIn by remember { mutableStateOf(cached?.hof_opt_in ?: false) }
    var hofAvatar by remember { mutableStateOf(cached?.hof_avatar) }   // data-URI or null
    var hofBusy by remember { mutableStateOf(false) }
    var hofError by remember { mutableStateOf<String?>(null) }
    val screenSec by app.rcq.android.data.LocalStores.screenSecurity.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // "Keep my card out of side lists" (#22). Device-local, per account: the
    // island has no field for it yet, so there is nothing to load from the
    // profile and nothing to send. See [SettingsLocalPrivacy] for why this is
    // a stated preference and not an enforced rule.
    val ownUin = session.uin ?: 0
    var cardSideLists by remember(ownUin) {
        mutableStateOf(SettingsLocalPrivacy.cardSideListsClosed(context, ownUin))
    }
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
            callPolicy = p.call_policy ?: "everyone"
            hofOptIn = p.hof_opt_in ?: false
            hofAvatar = p.hof_avatar
        }
    }

    fun save(body: RcqApi.UpdateMeBody) { scope.launch { runCatching { session.updateProfile(body) } } }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(stringResource(R.string.settings_row_privacy), onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            VisibilityPicker(stringResource(R.string.pv_last_seen), lastSeen, listOf("everyone", "contacts", "nobody"), stringResource(R.string.pv_last_seen_desc)) { lastSeen = it; save(RcqApi.UpdateMeBody(last_seen_visibility = it)) }

            // ⚠ "Stay visible after you leave" USED TO SIT HERE and is gone on
            // purpose (founder, 23.08). It never worked: this client only ever
            // sent presence_persistent and never presence_ttl_minutes, so the
            // island read a NULL ttl as "forever", and the window the island did
            // keep was anchored on last_seen, which the 25s heartbeat rewrites.
            // The duration chips picked a number nobody ever read, and the home
            // countdown chip was a purely local clock with no relation to the
            // server's. The backend has dropped the feature and now pins both
            // fields to false, so nothing here sends them any more.

            VisibilityPicker(stringResource(R.string.pv_profile_card), profileVis, listOf("everyone", "contacts", "nobody"), stringResource(R.string.pv_profile_card_desc)) { profileVis = it; save(RcqApi.UpdateMeBody(profile_visibility = it)) }

            // "Keep my card out of side lists" (#22): the narrow sibling of the
            // picker above. It sits here because that is where a person looks
            // for it, and its copy says outright what it does and does not do.
            //
            // ⚠⚠ THIS SWITCH CANNOT ENFORCE ANYTHING BY ITSELF, and the string
            // under it must never pretend otherwise. The reaction list, the
            // media viewer and the member list that open my card are drawn on
            // SOMEONE ELSE'S phone, out of data their app already holds; no flag
            // on my device is in that code path. Enforcement needs the island to
            // refuse the profile fetch when the opener came from one of those
            // surfaces, which needs a field on the profile and a reason on the
            // request. Until then this records the choice so it is ready to be
            // published the day the island grows the field.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pv_card_side_lists), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.pv_card_side_lists_desc), color = c.textSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = cardSideLists,
                    onCheckedChange = {
                        cardSideLists = it
                        SettingsLocalPrivacy.setCardSideListsClosed(context, ownUin, it)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }

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

            // Same-island stranger quarantine (device-local, per account, like
            // the web client's Privacy switch): the mailbox itself stays open
            // (sealed sender), this decides where THIS install files a
            // stranger's first message.
            val strangers by app.rcq.android.data.LocalStores.strangerQuarantine.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pv_strangers), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.pv_strangers_desc), color = c.textSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = strangers,
                    onCheckedChange = { app.rcq.android.data.LocalStores.setStrangerQuarantine(it) },
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

            // The PIN row lives here now (founder item L2.16, mirroring iOS,
            // whose Privacy pane ends on Panic PIN): the root keeps a single
            // Privacy door instead of two lock rows. Same gating as the root
            // row had: the "On" value only when a PIN is configured.
            SettingsGroup {
                SettingsRow(
                    Icons.Filled.Password,
                    stringResource(R.string.settings_row_pin_codes),
                    value = if (session.pinConfigured) stringResource(R.string.pin_on) else null,
                ) { onOpenPinCodes() }
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
                // Seeded from the last answer so the row paints instantly on
                // re-entry; the round-trip only refreshes it. Without the seed
                // the island name blinked in seconds late on a slow network
                // and the screen read as broken (#619).
                val info by produceState<app.rcq.android.net.RcqApi.ServerInfoResponse?>(
                    initialValue = serverInfoCache[host], host,
                ) {
                    app.rcq.android.net.RcqApi.serverInfoOf(host)?.let {
                        serverInfoCache[host] = it
                        value = it
                    }
                }
                val islandName = info?.name?.takeIf { it.isNotBlank() }
                SettingsRow(
                    Icons.Filled.Dns,
                    stringResource(R.string.pv_custom_server),
                    value = when {
                        islandName != null -> "$islandName · $host"
                        host == RcqApi.DEFAULT_HOST -> stringResource(R.string.pv_default)
                        else -> host
                    },
                    leading = {
                        IslandAvatar(
                            host = host,
                            logoVersion = info?.logo_version,
                            name = islandName,
                            // 20dp, matching the glyph on the row below it:
                            // a settings label starts at icon width + 12dp, so
                            // a wider picture on one row alone pushes its text
                            // out of the column every other row shares.
                            size = 20.dp,
                        )
                    },
                    onClick = onOpenCustomServer,
                )
                // The rules row moved to the root list, next to the island's
                // name. What stays here is the name inside the row above, which
                // is the useful half on a screen about CHANGING the server.
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

            // "RCQ relays" (embedded sing-box). A privacy layer first: the
            // island never learns your address and the network never learns you
            // run RCQ. Where RCQ is blocked it also happens to be the only way
            // through, which is a consequence and not the headline.
            // ⚠ Wording only. The default and the auto-engage logic are
            // untouched; renaming and re-defaulting are separate decisions.
            //
            // Header and link as ONE block: the screen's 18dp rhythm otherwise
            // scatters them into three unrelated lines instead of a section.
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(stringResource(R.string.pv_relays_section), color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                // The section names the relay, so it carries the way to find out
                // what one is. Deliberately a plain link, not a euphemism: the
                // founder wants the user to meet the word.
                RelayLearnMore()
            }
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

            // "Don't turn relays on automatically" (iOS parity): by default the app
            // turns the relays on when it can't reach the island directly; a user on
            // their own VPN/proxy can opt out so our sing-box doesn't stack on theirs.
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
                        // "Don't turn relays on automatically" while an AUTO-engaged
                        // relay route is running means stop that one too: the user is
                        // telling us to stay out of the way now, not from next launch.
                        if (it && !obfuscated && stealthActive) {
                            session.setObfuscation(false)
                            app.rcq.android.push.embedded.EmbeddedDistributor.reconnectNow(context)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }

            // Onion routing (M3, experimental). One switch for the user: turning
            // it on ALSO turns the RCQ relays on, because onion routes THROUGH
            // the relay tunnel and can't work without it. So the user never has
            // to think about two toggles.
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
            // i2p SOCKS5/HTTP. Mutually exclusive with the RCQ relays and onion
            // above (they grey out while this is on). No auto-fallback to our
            // relays if the proxy is down, that would leak around Tor.
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

    // ⚠⚠ A duress session must not probe. `probeDirect` opens a RAW TCP+TLS
    // socket to the real account's island, and `NetworkAudit` opens sockets to
    // third-party control hosts on top of that — neither goes through OkHttp,
    // so neither is stopped by `DuressGate`, which sits under the HTTP clients.
    // Opening this screen under coercion therefore put a TLS handshake carrying
    // the REAL island's SNI on the wire, from a session whose entire claim is
    // that it is somebody else's phone. Same shape as the outgoing call that
    // walked around the gate on iOS.
    //
    // So under duress the screen answers from what the session already claims
    // and touches nothing: the header dot is green, so the backend is reachable
    // and the channel is up. It is the same fiction the rest of the decoy tells,
    // and it stays consistent with the one thing a coercer can see.
    val duress = app.rcq.android.security.DuressGate.isActive

    fun run() {
        running = true; directOk = null; routeOk = null
        if (duress) {
            directOk = true; routeOk = true; running = false
            return
        }
        scope.launch {
            val host = session.currentServer
            // A trust refusal reads as "not reachable" here on purpose: the
            // island answered, but not in a way this device will talk to, and
            // the banner on the main screen is where that is explained.
            directOk = withContext(Dispatchers.IO) { transport.probeDirect(host) } ==
                app.rcq.android.net.SingBoxTransport.Reachability.REACHABLE
            routeOk = withContext(Dispatchers.IO) { transport.probeCurrentRoute(host) } ==
                app.rcq.android.net.SingBoxTransport.Reachability.REACHABLE
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
            RelayLearnMore()
            CapsuleButton(stringResource(R.string.diag_run_again), enabled = !running) { run() }

            // Full network audit. Separate button because it opens raw sockets
            // to a couple of third-party control hosts, which should never
            // happen without the user asking for it.
            Spacer(Modifier.height(4.dp))
            // Hidden under duress rather than disabled: it opens sockets to
            // third-party control hosts, and a dead button invites a second tap.
            if (!duress) {
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
    // #643: the account's key slots — every install with encryption keys of
    // its own, the one list a recovery-phrase login cannot stay out of.
    var slots by remember { mutableStateOf<List<app.rcq.android.net.RcqApi.PeerDeviceRow>?>(null) }
    var ownSlot by remember { mutableStateOf<Int?>(null) }
    // Пункт 13: the slot the revoke confirm sheet is up for, and the one in
    // flight. Any slot that is neither the primary nor OUR OWN can be retired.
    var revokeAsk by remember { mutableStateOf<Int?>(null) }
    var revoking by remember { mutableStateOf<Int?>(null) }

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
        // Best-effort beside the registry: an island too old for per-device
        // keys 404s here, and that just leaves the section out.
        runCatching { session.keySlots() }.onSuccess { (list, own) ->
            slots = list
            ownSlot = own
        }
    }
    // Keyed on the slot-revoke announce (пункт 13): a slot retired from any
    // session of the account refreshes the list while it is on screen. The
    // initial value covers the first load.
    val slotsTick by session.keySlotsChanged.collectAsState()
    LaunchedEffect(slotsTick) { reload() }

    /** Retire a key slot. The island's cooldown 403 (a young linked session
     *  revoking something older than itself) becomes the human sentence it
     *  means, with the hours left. */
    fun revokeSlot(deviceId: Int) {
        if (revoking != null) return
        revoking = deviceId
        scope.launch {
            runCatching { session.revokeKeySlot(deviceId) }
                .onSuccess { slots = slots?.filterNot { it.device_id == deviceId } }
                .onFailure { e ->
                    val msg = e.message.orEmpty()
                    if ("revoke_cooldown" in msg) {
                        val secs = Regex("\"wait_seconds\"\\s*:\\s*(\\d+)").find(msg)
                            ?.groupValues?.get(1)?.toLongOrNull() ?: 86400L
                        val hours = maxOf(1L, (secs + 3599L) / 3600L)
                        Toast.makeText(context, context.getString(R.string.linked_devices_revoke_cooldown, hours), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.linked_devices_revoke_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            revoking = null
        }
    }

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

    // Second step of the revoke: the row button arms this sheet (пункт 13).
    revokeAsk?.let { slotId ->
        RcqAskSheet(
            onDismiss = { revokeAsk = null },
            title = stringResource(R.string.linked_devices_revoke_title),
            body = stringResource(R.string.linked_devices_revoke_body),
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.linked_devices_revoke),
                    destructive = true,
                    onClick = { revokeAsk = null; revokeSlot(slotId) },
                ),
            ),
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
        // ⚠ ONE scrolling list for both sections. The key slots used to sit
        // in the outer (non-scrolling) Column: an account with a handful of
        // installs then pushed the web-session rows — and their Disconnect
        // buttons — off the bottom of a short screen, unreachable.
        val slotList = slots.orEmpty()
        LazyColumn(Modifier.fillMaxSize()) {
            if (slotList.isNotEmpty()) {
                item(key = "slots-header") {
                    Column {
                        Text(
                            stringResource(R.string.linked_devices_slots_title).uppercase(),
                            color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        Text(
                            stringResource(R.string.linked_devices_slots_hint),
                            color = c.textSecondary, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                items(slotList, key = { "slot-${it.device_id}" }) { d ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // The glyph follows the LABEL, not the slot index
                        // (founder batch 21.08, item 12: the iPhone on slot 2
                        // wore a laptop). The island names slot 1 "primary"
                        // with no platform in it, so the primary slot falls
                        // back to a phone - a guess, and what it most often is.
                        val slotLabel = (d.label ?: "").lowercase()
                        Icon(
                            when {
                                listOf("desktop", "mac", "windows", "linux").any { it in slotLabel } -> Icons.Filled.Computer
                                listOf("web", "chrome", "safari", "firefox", "browser").any { it in slotLabel } -> Icons.Filled.Language
                                listOf("iphone", "ipod", "android", "phone").any { it in slotLabel } -> Icons.Filled.Smartphone
                                // ⚠ Slot 1 is not a device and never was: it
                                // is the key slot the account started with,
                                // which any install without its own speaks
                                // through. Drawing a phone there invented a
                                // third device the person does not own, and
                                // calling it "primary" made two things primary
                                // at once, one on each client (#671, #673).
                                d.device_id == 1 -> Icons.Filled.VpnKey
                                else -> Icons.Filled.Computer
                            },
                            null, tint = c.accent, modifier = Modifier.size(22.dp),
                        )
                        Text(
                            when {
                                d.device_id == 1 -> stringResource(R.string.linked_devices_slots_primary)
                                !d.label.isNullOrBlank() -> d.label
                                else -> stringResource(R.string.linked_devices_slots_unnamed)
                            },
                            color = c.textPrimary, fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (ownSlot != null && d.device_id == ownSlot) {
                            Text(
                                stringResource(R.string.linked_devices_slots_this),
                                color = c.accent, fontSize = 12.sp,
                            )
                        }
                        // Пункт 13: retire a slot that is neither the primary
                        // nor our own. First tap arms the confirm sheet above.
                        if (d.device_id != 1 && d.device_id != ownSlot) {
                            TextButton(
                                onClick = { revokeAsk = d.device_id },
                                enabled = revoking == null,
                            ) {
                                Text(
                                    if (revoking == d.device_id) "…" else stringResource(R.string.linked_devices_revoke),
                                    color = Color(0xFFE5484D), fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
                item(key = "web-header") {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.linked_devices_web_title).uppercase(),
                            color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            when (val list = devices) {
                // Nothing loaded yet: the spinner while the first read is in
                // flight, the error state once it has failed. The list stays
                // null on failure so a later refresh still fills it in,
                // instead of being frozen as a convincing-looking "no devices".
                null -> item(key = "web-state") {
                    if (failed) {
                        LinkedDevicesPlaceholder(stringResource(R.string.linked_devices_error))
                    } else {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = c.accent, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                else -> if (list.isEmpty()) {
                    item(key = "web-empty") {
                        LinkedDevicesPlaceholder(stringResource(R.string.linked_devices_empty))
                    }
                } else {
                    items(list, key = { "web-${it.device_id}" }) { d ->
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

    // Bare host[:port] the user typed (scheme, path and the `#fp` fragment
    // stripped, the fragment FIRST — see IslandTrust.splitAddress); blank →
    // default. The fragment itself is pinned by Session.normalizeHost when
    // the switch actually runs, never here while typing.
    fun normalized(s: String): String =
        app.rcq.android.net.IslandTrust.splitAddress(s)?.hostPort ?: RcqApi.DEFAULT_HOST

    val target = normalized(draft)
    // A fragment that is not a fingerprint, or one on a host that is never
    // pinned, or one the store disagrees with, is an address error: said
    // under the field and nothing is dialled (design §3).
    val entry = app.rcq.android.net.IslandTrust.inspect(draft, commit = false)
    val addressError: String? = islandAddressError(context, entry)
    val isDirty = target != current && addressError == null
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
            addressError?.let { Text(it, color = Color(0xFFE5484D), fontSize = 12.sp) }

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

    fun errorText(e: Throwable): String {
        val m = e.message
        // The island answered, and WHAT it answered is the whole diagnosis: a
        // 401 is a key it will not take, a 404 an endpoint it does not have.
        // Calling those "unreachable" sent a reporter chasing his own network
        // while three healthy islands sat there answering him (#687).
        if (m != null && m.startsWith("island_said:")) {
            return context.getString(R.string.backup_island_err_said, m.removePrefix("island_said:"))
        }
        return when (m) {
            "invalid_host" -> context.getString(R.string.backup_island_err_invalid)
            "primary_island" -> context.getString(R.string.backup_island_err_primary)
            "already_added" -> context.getString(R.string.backup_island_err_already)
            "no_island" -> context.getString(R.string.backup_island_err_none)
            "no_account_here" -> context.getString(R.string.backup_island_err_no_account)
            "no_route" -> context.getString(R.string.backup_island_err_no_route)
            "unreachable" -> context.getString(R.string.backup_island_err_unreachable)
            // Keep the cause visible: "could not connect" alone is undebuggable
            // for a self-hoster pointing at their own island.
            else -> context.getString(R.string.backup_island_err_generic) +
                " (${e.message ?: e.javaClass.simpleName})"
        }
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

                Field(stringResource(R.string.backup_island_host_hint), host) { host = it; error = null }
                // The same picker onboarding uses, so "which islands are there"
                // is answered in one place and looks the same in both. Typing a
                // host stays exactly where it was: a self-hoster's island is
                // never in a catalogue.
                var pickIsland by remember { mutableStateOf(false) }
                Text(
                    stringResource(R.string.island_pick_title),
                    color = c.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { pickIsland = true }.padding(vertical = 4.dp),
                )
                if (pickIsland) IslandPickerSheet(
                    current = host,
                    onPick = { host = it; pickIsland = false },
                    onDismiss = { pickIsland = false },
                )
                Button(
                    onClick = {
                        // The `#fp` fragment is judged here, before anything is
                        // dialled, the way the picker and the other forms do:
                        // a fragment against a null record is pinned as typed,
                        // one the store disagrees with raises the banner and
                        // stops, and what goes on is the bare host:port with
                        // its port intact (design §3).
                        val entry = app.rcq.android.net.IslandTrust.adopt(host)
                        val addressError = islandAddressError(context, entry)
                        if (addressError != null) { error = addressError; return@Button }
                        val target = (entry as? app.rcq.android.net.IslandTrust.Entry.Ok)?.hostPort
                            ?: return@Button
                        busy = true; error = null
                        scope.launch {
                            runCatching { session.addBackupIsland(target) }
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

/** The top bar while search is open: back closes it, the field takes the rest.
 *  This is the platform's SearchView shape, so nobody has to learn it. */
@Composable
private fun SettingsSearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val c = RcqTheme.colors
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // A frame late on purpose: requesting focus on a node that has not been
    // attached yet throws, and the keyboard is what makes an opened search bar
    // feel opened.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    // Back closes search rather than leaving Settings. The screen's own
    // BackHandler is disabled at the root, so this one is free to take it.
    BackHandler(onBack = onClose)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent,
            modifier = Modifier.size(26.dp).clickable(onClick = onClose),
        )
        RcqField(
            value = query,
            onValueChange = onQuery,
            placeholder = stringResource(R.string.settings_search_hint),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.textSecondary, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close, stringResource(R.string.common_clear), tint = c.textSecondary,
                        modifier = Modifier.size(18.dp).clickable { onQuery("") },
                    )
                }
            },
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
    }
}

/** Hits for [query] over [settingsFindIndex]. An empty query lists everything,
 *  which doubles as a flat map of Settings and costs nothing to offer. */
@Composable
private fun SettingsSearchResults(
    query: String,
    hidden: Set<SettingsFind>,
    modifier: Modifier = Modifier,
    onPick: (SettingsFindRow) -> Unit,
) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    // Built through the Context rather than stringResource() so the whole
    // haystack is one plain memoized value instead of a composable call per row
    // per keystroke. The Compose context carries the app's locale, so the titles
    // come out in the language on screen and a person can search in their own
    // words without any of it being listed in the aliases.
    val index = remember(hidden, context) {
        settingsFindIndex.filter { it.id !in hidden }.map { row ->
            row to settingsSearchFold(
                context.getString(row.titleRes) + " " +
                    context.getString(row.sectionRes) + " " + row.aliases,
            )
        }
    }
    val words = remember(query) {
        settingsSearchFold(query).split(' ', '\n', '\t').filter { it.isNotBlank() }
    }
    val hits = remember(index, words) {
        if (words.isEmpty()) index.map { it.first }
        else index.filter { settingsFindMatches(it.second, words) }.map { it.first }
    }
    if (hits.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.search_no_match), color = c.textSecondary, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(
        modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        items(hits, key = { it.id.name }) { row ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(row) }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(row.icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(row.titleRes), color = c.textPrimary, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(row.sectionRes), color = c.textSecondary, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Filled.ChevronRight, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
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

/** Last /server/info answer per host — process-lifetime, tiny, lets the
 *  network screen paint the island's name instantly on re-entry (#619). */
private val serverInfoCache = mutableMapOf<String, app.rcq.android.net.RcqApi.ServerInfoResponse>()

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    destructive: Boolean = false,
    chevron: Boolean = true,
    /// Slot for the search anchor (#28). Applied AFTER fillMaxWidth and BEFORE
    /// clickable, so an anchor's flash tint covers the whole row and the ripple
    /// still sits on top of it.
    modifier: Modifier = Modifier,
    /// Draw something else in place of [icon]. One caller: the island rows,
    /// which carry that island's own picture rather than a server glyph. The
    /// glyph is still the parameter and still the default, so every other row
    /// on the screen is untouched.
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = RcqTheme.colors
    val tint = if (destructive) Color(0xFFE5484D) else c.accent
    Row(
        Modifier.fillMaxWidth().then(modifier).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) leading() else
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = if (destructive) Color(0xFFE5484D) else c.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        // ⚠ The value MUST carry a weight too, or a long one ("RCQ Exodus ·
        // api.rcq.app") is measured at full intrinsic width first and the
        // weighted label is left a one-character column — on a narrow screen
        // "Свой сервер" rendered VERTICALLY, letter per line (#619).
        //
        // And the weight must FILL, pinning the text to the slot's END:
        // fill=false left the row's slack AFTER the chevron, so a short value
        // ("0.132 ›") sat mid-row while every neighbouring chevron hugged the
        // edge (founder, 20.08). A long value still ellipsizes in its half.
        if (value != null) {
            Text(
                value, color = c.textSecondary, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End),
            )
        }
        if (chevron) Icon(Icons.Filled.ChevronRight, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
    }
}

/** The island row's companion: how [host] is trusted on this device. A CA
 *  island (the flagship by rule, or any island whose chain the platform took)
 *  gets one line; a pinned island gets its fingerprint in display form and a
 *  copy of `host:port#fp`, the address `install.sh` prints for the operator
 *  to hand out. Draws nothing while no record exists yet. */
@Composable
private fun IslandTrustRow(host: String) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val records by app.rcq.android.net.IslandTrust.records.collectAsState()
    val rec = records[app.rcq.android.net.IslandTrust.keyOf(host)]
    val viaCa = app.rcq.android.net.IslandTrust.isCaOnly(host) || rec?.mode == app.rcq.android.net.IslandTrust.Mode.CA
    val fp = rec?.fp?.takeIf { rec.mode == app.rcq.android.net.IslandTrust.Mode.PINNED }
    if (!viaCa && fp == null) return
    Divider()
    if (viaCa || fp == null) {
        SettingsRow(Icons.Filled.Lock, stringResource(R.string.island_trust_settings_ca), chevron = false) { }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Fingerprint, null, tint = c.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.island_trust_settings_pinned), color = c.textPrimary, fontSize = 16.sp)
            Text(
                app.rcq.android.net.IslandTrust.displayFingerprint(fp),
                color = c.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        val copyLabel = stringResource(R.string.island_trust_copy)
        Icon(
            Icons.Filled.ContentCopy, copyLabel, tint = c.accent,
            modifier = Modifier.size(20.dp).clickable {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("island", "$host#$fp"))
                Toast.makeText(context, context.getString(R.string.island_trust_copied), Toast.LENGTH_SHORT).show()
            },
        )
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
