package app.rcq.android.data

import android.content.Context
import android.content.SharedPreferences
import app.rcq.android.ui.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Client-side, non-secret preference state — the Android analogue of the
 * iOS `FavoritesStore` / `ArchiveStore` / `SoundService` mute set /
 * `RemovedContactsStore`, plus the appearance setting.
 *
 * Two scopes:
 *  - **Global** (theme, sound toggles): one value for the whole app,
 *    unprefixed keys, loaded in [init].
 *  - **Per-account** (favorites, muted, archived, removed, unread): keyed
 *    by the active [Account.id] so each identity has its own roster state.
 *    [bindAccount] swaps which account's slots the flows reflect; writes go
 *    to the bound account's prefixed keys.
 *
 * Thread keys are "peer:<uin>" / "group:<id>". Everything mirrors into a
 * plain (unencrypted) SharedPreferences — none of this is sensitive.
 *
 * Lifecycle: [init] once from MainActivity.onCreate, then [bindAccount]
 * with the active account id (and again on every account switch, done by
 * [Session]).
 */
object LocalStores {
    private lateinit var prefs: SharedPreferences

    /** Active account prefix for per-account keys; null before any account
     *  is bound (fresh install, pre-onboarding). */
    private var acct: String? = null

    // ── per-account flows ────────────────────────────────────────────────
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _muted = MutableStateFlow<Set<String>>(emptySet())
    val muted: StateFlow<Set<String>> = _muted.asStateFlow()

    private val _archived = MutableStateFlow<Set<String>>(emptySet())
    val archived: StateFlow<Set<String>> = _archived.asStateFlow()

    /** UINs of contacts the user removed — incoming sealed messages from
     *  them are dropped client-side. Mirrors iOS RemovedContactsStore. */
    private val _removed = MutableStateFlow<Set<Int>>(emptySet())
    val removed: StateFlow<Set<Int>> = _removed.asStateFlow()

    /** Persistent per-thread unread counters, keyed "peer:<uin>"/"group:<id>".
     *  Mirrors the iOS UnreadStore: survives cold starts, bumped on inbound
     *  message, cleared when the chat opens. */
    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    /** Presence "stay online for N hours after exit" window: the epoch-millis
     *  moment that window EXPIRES, or null when the feature is off. Local-only
     *  affordance anchored when the user enables/changes it in Privacy
     *  settings, so the home header can show a live countdown of when they
     *  drop back to offline. Reset (re-anchored) on every active change. */
    private val _presenceWindow = MutableStateFlow<Long?>(null)
    val presenceWindow: StateFlow<Long?> = _presenceWindow.asStateFlow()

    // ── global flows ─────────────────────────────────────────────────────
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Notification-sound toggles (iOS SoundService parity). */
    private val _soundMessages = MutableStateFlow(true)
    val soundMessages: StateFlow<Boolean> = _soundMessages.asStateFlow()

    private val _soundPresence = MutableStateFlow(true)
    val soundPresence: StateFlow<Boolean> = _soundPresence.asStateFlow()

    /** When on, the app window gets FLAG_SECURE: screenshots/screen-recording
     *  are blocked and content is hidden in the app switcher. Device-global,
     *  applied by MainActivity. */
    private val _screenSecurity = MutableStateFlow(false)
    val screenSecurity: StateFlow<Boolean> = _screenSecurity.asStateFlow()

    /** Per-account, per-thread "screen-secure" chats (peer:UIN keys). When a
     *  secure chat is open, ChatScreen adds FLAG_SECURE so screenshots/recording
     *  of THAT chat are blocked; the flag is propagated to the peer so both
     *  sides enforce it (iOS parity). */
    private val _secureThreads = MutableStateFlow<Set<String>>(emptySet())
    val secureThreads: StateFlow<Set<String>> = _secureThreads.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("rcq_local", Context.MODE_PRIVATE)
        // Global (app-wide) settings only; per-account flows load in bindAccount.
        _themeMode.value = runCatching { ThemeMode.valueOf(prefs.getString(K_THEME, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
        _soundMessages.value = prefs.getBoolean(K_SND_MSG, true)
        _soundPresence.value = prefs.getBoolean(K_SND_PRES, true)
        _screenSecurity.value = prefs.getBoolean(K_SCREEN_SEC, false)
    }

    /** Point the per-account flows at [accountId]'s slots and reload them.
     *  null (no active account) resets the flows to empty. */
    fun bindAccount(accountId: String?) {
        acct = accountId
        if (accountId == null) {
            _favorites.value = emptySet()
            _muted.value = emptySet()
            _archived.value = emptySet()
            _removed.value = emptySet()
            _unread.value = emptyMap()
            _presenceWindow.value = null
            _secureThreads.value = emptySet()
            return
        }
        _favorites.value = prefs.getStringSet(pk(K_FAV), emptySet())!!.toSet()
        _muted.value = prefs.getStringSet(pk(K_MUTE), emptySet())!!.toSet()
        _archived.value = prefs.getStringSet(pk(K_ARCH), emptySet())!!.toSet()
        _removed.value = prefs.getStringSet(pk(K_REMOVED), emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        _unread.value = loadUnread(pk(K_UNREAD))
        _presenceWindow.value = prefs.getLong(pk(K_PRES_WIN), 0L).takeIf { it > 0L }
        _secureThreads.value = prefs.getStringSet(pk(K_SECURE), emptySet())!!.toSet()
    }

    fun isThreadSecure(thread: String) = thread in _secureThreads.value

    /** Set/clear screen-secure mode for a thread (local store only — the caller
     *  propagates to the peer via a SecureScreen envelope). */
    fun setThreadSecure(thread: String, on: Boolean) {
        if (acct == null) return
        _secureThreads.value = if (on) _secureThreads.value + thread else _secureThreads.value - thread
        prefs.edit().putStringSet(pk(K_SECURE), _secureThreads.value).apply()
    }

    /** Per-account key for the currently-bound account. */
    private fun pk(key: String) = "$acct.$key"

    // ── thread-key helpers ───────────────────────────────────────────
    fun peerThread(uin: Int) = "peer:$uin"
    fun groupThread(id: Int) = "group:$id"

    fun isFavorite(thread: String) = thread in _favorites.value
    fun toggleFavorite(thread: String) = toggle(_favorites, K_FAV, thread)

    fun isMuted(thread: String) = thread in _muted.value
    fun toggleMute(thread: String) = toggle(_muted, K_MUTE, thread)

    fun isArchived(thread: String) = thread in _archived.value
    fun toggleArchive(thread: String) = toggle(_archived, K_ARCH, thread)

    fun isRemoved(uin: Int) = uin in _removed.value
    fun addRemoved(uin: Int) {
        if (acct == null || uin in _removed.value) return
        _removed.value = _removed.value + uin
        prefs.edit().putStringSet(pk(K_REMOVED), _removed.value.map(Int::toString).toSet()).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(K_THEME, mode.name).apply()
    }

    // ── sound toggles ────────────────────────────────────────────────
    fun soundMessagesOn() = _soundMessages.value
    fun soundPresenceOn() = _soundPresence.value
    fun setSoundMessages(on: Boolean) { _soundMessages.value = on; prefs.edit().putBoolean(K_SND_MSG, on).apply() }
    fun setSoundPresence(on: Boolean) { _soundPresence.value = on; prefs.edit().putBoolean(K_SND_PRES, on).apply() }

    fun screenSecurityOn() = _screenSecurity.value
    fun setScreenSecurity(on: Boolean) { _screenSecurity.value = on; prefs.edit().putBoolean(K_SCREEN_SEC, on).apply() }

    // ── presence stay-online window ──────────────────────────────────
    /** (Re)anchor the stay-online window to now + [ttlMinutes], so the home
     *  countdown restarts from the full duration. Called whenever the user
     *  enables the feature or changes the duration in Privacy settings. */
    fun setPresenceWindow(ttlMinutes: Int) {
        if (acct == null) return
        val expires = System.currentTimeMillis() + ttlMinutes.toLong() * 60_000L
        _presenceWindow.value = expires
        prefs.edit().putLong(pk(K_PRES_WIN), expires).apply()
    }

    /** Clear the window (the feature was turned off). */
    fun clearPresenceWindow() {
        _presenceWindow.value = null
        if (acct != null) prefs.edit().remove(pk(K_PRES_WIN)).apply()
    }

    // ── unread counters ──────────────────────────────────────────────
    fun unreadOf(thread: String): Int = _unread.value[thread] ?: 0

    fun bumpUnread(thread: String) {
        if (acct == null) return
        val cur = _unread.value.toMutableMap()
        cur[thread] = (cur[thread] ?: 0) + 1
        _unread.value = cur
        persistUnread()
    }

    fun clearUnread(thread: String) {
        if (_unread.value[thread] == null) return
        _unread.value = _unread.value - thread
        persistUnread()
    }

    /** Encode the map as a CSV "thread=count" StringSet for SharedPreferences. */
    private fun persistUnread() {
        if (acct == null) return
        prefs.edit().putStringSet(pk(K_UNREAD), _unread.value.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    private fun loadUnread(key: String): Map<String, Int> =
        prefs.getStringSet(key, emptySet())!!.mapNotNull { entry ->
            val i = entry.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            val k = entry.substring(0, i)
            val v = entry.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
            k to v
        }.toMap()

    private fun toggle(flow: MutableStateFlow<Set<String>>, key: String, thread: String) {
        if (acct == null) return
        flow.value = if (thread in flow.value) flow.value - thread else flow.value + thread
        // StringSet must be copied — SharedPreferences keeps the same
        // instance otherwise and silently no-ops on the next read.
        prefs.edit().putStringSet(pk(key), flow.value.toSet()).apply()
    }

    // ── multi-account migration / teardown ───────────────────────────

    /** Lift the legacy unprefixed per-account slots under [accountId], then
     *  drop the legacy keys. Idempotent. Called once by AccountManager when
     *  wrapping a pre-multi-account install as Account[0]. */
    fun migrateLegacyToAccount(accountId: String) {
        if (!::prefs.isInitialized) return
        val e = prefs.edit()
        listOf(K_FAV, K_MUTE, K_ARCH, K_REMOVED, K_UNREAD).forEach { k ->
            if (prefs.contains(k)) {
                prefs.getStringSet(k, emptySet())?.let { e.putStringSet("$accountId.$k", it.toSet()) }
                e.remove(k)
            }
        }
        e.apply()
    }

    // ── privacy-settings cache ───────────────────────────────────────
    // Last-known-good privacy/visibility profile, per account, as JSON.
    // The Privacy screen seeds its pickers from this so a transient
    // profile-load failure (bad/censored network) — or a server reply
    // that omits the owner-self fields — never makes the UI fall back to
    // the permissive "Everyone" defaults, which read as a silent reset
    // of the user's chosen restrictions. Non-sensitive; plain prefs.
    fun cachedProfileJson(): String? =
        if (::prefs.isInitialized && acct != null) prefs.getString(pk(K_PRIVACY_CACHE), null) else null

    fun setCachedProfileJson(json: String) {
        if (::prefs.isInitialized && acct != null) prefs.edit().putString(pk(K_PRIVACY_CACHE), json).apply()
    }

    // ── roster cache (offline chat list) ─────────────────────────────
    // The contact/group roster is otherwise network-only, so a cold start
    // with no connection sat on the "Connecting…" screen forever and the
    // user couldn't open any chat offline (report #7). Cache it per account
    // so the chat list (and its locally-stored history) is reachable offline.
    fun cachedContactsJson(): String? =
        if (::prefs.isInitialized && acct != null) prefs.getString(pk(K_CONTACTS_CACHE), null) else null

    fun setCachedContactsJson(json: String) {
        if (::prefs.isInitialized && acct != null) prefs.edit().putString(pk(K_CONTACTS_CACHE), json).apply()
    }

    fun cachedGroupsJson(): String? =
        if (::prefs.isInitialized && acct != null) prefs.getString(pk(K_GROUPS_CACHE), null) else null

    fun setCachedGroupsJson(json: String) {
        if (::prefs.isInitialized && acct != null) prefs.edit().putString(pk(K_GROUPS_CACHE), json).apply()
    }

    /** Remove every per-account slot for [accountId] (local account delete). */
    fun clearAccount(accountId: String) {
        if (!::prefs.isInitialized) return
        val e = prefs.edit()
        listOf(K_FAV, K_MUTE, K_ARCH, K_REMOVED, K_UNREAD, K_PRIVACY_CACHE, K_CONTACTS_CACHE, K_GROUPS_CACHE).forEach { e.remove("$accountId.$it") }
        e.apply()
    }

    private const val K_FAV = "favorites"
    private const val K_MUTE = "muted"
    private const val K_ARCH = "archived"
    private const val K_REMOVED = "removed"
    private const val K_THEME = "theme_mode"
    private const val K_UNREAD = "unread"
    private const val K_SND_MSG = "sound_messages"
    private const val K_SND_PRES = "sound_presence"
    private const val K_SCREEN_SEC = "screen_security"
    private const val K_PRES_WIN = "presence_window"
    private const val K_SECURE = "secure_threads"
    private const val K_PRIVACY_CACHE = "privacy_cache"
    private const val K_CONTACTS_CACHE = "contacts_cache"
    private const val K_GROUPS_CACHE = "groups_cache"
}
