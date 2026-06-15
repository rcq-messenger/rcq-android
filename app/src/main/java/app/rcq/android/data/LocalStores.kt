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

    /** Group threads in "mentions only" notify mode: silent UNLESS the message
     *  @mentions me (#11). Mutually exclusive with [_muted] (= fully silent);
     *  a thread in neither set rings for everything. */
    private val _mentionsOnly = MutableStateFlow<Set<String>>(emptySet())
    val mentionsOnly: StateFlow<Set<String>> = _mentionsOnly.asStateFlow()

    private val _archived = MutableStateFlow<Set<String>>(emptySet())
    val archived: StateFlow<Set<String>> = _archived.asStateFlow()

    /** UINs of contacts the user removed — incoming sealed messages from
     *  them are dropped client-side. Mirrors iOS RemovedContactsStore. */
    private val _removed = MutableStateFlow<Set<Int>>(emptySet())
    val removed: StateFlow<Set<Int>> = _removed.asStateFlow()

    /** UINs the user blocked — incoming sealed 1:1 AND group messages from them
     *  are dropped client-side (sealed sender = the server can't filter). Local
     *  + persisted so it works for non-contacts/strangers too, unlike the
     *  server `blocked` contact flag. Mirrors iOS BlockedContactsStore. */
    private val _blocked = MutableStateFlow<Set<Int>>(emptySet())
    val blocked: StateFlow<Set<Int>> = _blocked.asStateFlow()

    /** Persistent per-thread unread counters, keyed "peer:<uin>"/"group:<id>".
     *  Mirrors the iOS UnreadStore: survives cold starts, bumped on inbound
     *  message, cleared when the chat opens. */
    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    /** Threads with an UNSEEN reaction on one of MY messages (iOS reaction-inbox
     *  parity). Keyed "peer:<uin>"/"group:<id>"; marked when someone else reacts
     *  to my message in a thread I'm not looking at, cleared when the chat opens.
     *  Drives the home-row heart indicator. */
    private val _reactionInbox = MutableStateFlow<Set<String>>(emptySet())
    val reactionInbox: StateFlow<Set<String>> = _reactionInbox.asStateFlow()

    /** Per-thread message ids (of MY messages) that got an UNSEEN reaction while
     *  I was away — drives the reaction-jump on chat open (scroll to + flash the
     *  reacted message). Keyed thread -> set of message ids. Persisted next to
     *  [reactionInbox], account-scoped. Cleared once the jump consumes it. */
    private val _reactedMsgIds = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val reactedMsgIds: StateFlow<Map<String, Set<String>>> = _reactedMsgIds.asStateFlow()

    /** Group threads where I was @mentioned and haven't looked yet (iOS parity).
     *  Keyed "group:<id>"; marked on an inbound group message that @mentions me
     *  in a thread I'm not looking at, cleared when the chat opens. Drives the
     *  home-row @ indicator. */
    private val _mentionInbox = MutableStateFlow<Set<String>>(emptySet())
    val mentionInbox: StateFlow<Set<String>> = _mentionInbox.asStateFlow()

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

    /** Chat wallpaper, applied to every chat's message area (global, not
     *  per-chat — founder's choice). "" = default theme background;
     *  "preset:<id>" = a built-in (see ChatBackgrounds); "custom" = the image
     *  saved at [chatBgFile]. */
    private val _chatBackground = MutableStateFlow("")
    val chatBackground: StateFlow<String> = _chatBackground.asStateFlow()

    /** Same as [chatBackground] but for the HOME / chat-list screen (a separate
     *  wallpaper, founder's choice). "" / "preset:<id>" / "custom" ([homeBgFile]). */
    private val _homeBackground = MutableStateFlow("")
    val homeBackground: StateFlow<String> = _homeBackground.asStateFlow()

    /** In-app text-size multiplier ON TOP of the OS font scale (accessibility:
     *  the audience skews 30+ with imperfect vision). Applied app-wide by
     *  overriding LocalDensity.fontScale. 1.0 = system default. */
    private val _fontScale = MutableStateFlow(1.0f)
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    /** Notification-sound toggles (iOS SoundService parity). */
    private val _soundMaster = MutableStateFlow(true)
    val soundMaster: StateFlow<Boolean> = _soundMaster.asStateFlow()

    private val _soundMessages = MutableStateFlow(true)
    val soundMessages: StateFlow<Boolean> = _soundMessages.asStateFlow()

    private val _soundPresence = MutableStateFlow(true)
    val soundPresence: StateFlow<Boolean> = _soundPresence.asStateFlow()

    /** When on, the app window gets FLAG_SECURE: screenshots/screen-recording
     *  are blocked and content is hidden in the app switcher. Device-global,
     *  applied by MainActivity. */
    private val _screenSecurity = MutableStateFlow(false)
    val screenSecurity: StateFlow<Boolean> = _screenSecurity.asStateFlow()

    /** PIN re-lock grace in SECONDS (#10): 0 = lock the moment the app
     *  backgrounds (current behaviour); >0 = only re-lock if away longer than
     *  this, so quick app switches don't demand the PIN every time. */
    private val _lockGrace = MutableStateFlow(0)
    val lockGrace: StateFlow<Int> = _lockGrace.asStateFlow()
    fun lockGraceSeconds(): Int = if (::prefs.isInitialized) _lockGrace.value else 0

    /** Home-list UI flags (set of stable string ids) — currently which sections
     *  the user has folded. Global UI preference that survives leaving and
     *  re-entering the home screen, so a collapsed section stays collapsed
     *  (report: the offline section kept re-expanding because the state was
     *  only in-memory remember{}). */
    private val _sectionFlags = MutableStateFlow<Set<String>>(emptySet())
    val sectionFlags: StateFlow<Set<String>> = _sectionFlags.asStateFlow()

    /** Per-account, per-thread "screen-secure" chats (peer:UIN keys). When a
     *  secure chat is open, ChatScreen adds FLAG_SECURE so screenshots/recording
     *  of THAT chat are blocked; the flag is propagated to the peer so both
     *  sides enforce it (iOS parity). */
    private val _secureThreads = MutableStateFlow<Set<String>>(emptySet())
    val secureThreads: StateFlow<Set<String>> = _secureThreads.asStateFlow()

    /** Historical fixed reaction set — the default until the user customises
     *  their own. Asset names match iOS exactly so a reaction renders the same
     *  GIF on both clients. Defined inline (not imported from the `ui`
     *  Emoticons) to keep this `data` store free of UI deps. MUST stay declared
     *  before [_reactionEmojis] so it's initialised first. */
    private val DEFAULT_REACTION_EMOJIS = listOf("good", "give_heart", "biggrin", "shok", "cray", "mad")

    /** The user's chosen composer-panel emoticons (asset names, in pick order).
     *  EMPTY by default → the composer panel shows a "Choose" CTA until the user
     *  picks their own set. Global (one set across accounts), like the
     *  wallpapers. The `:asset:` codes are the wire form and render anywhere the
     *  asset is bundled. */
    private val _panelEmojis = MutableStateFlow<List<String>>(emptyList())
    val panelEmojis: StateFlow<List<String>> = _panelEmojis.asStateFlow()

    /** The user's chosen quick reactions (asset names, ≤6) offered on the
     *  long-press reaction row. Defaults to [DEFAULT_REACTION_EMOJIS] until
     *  customised. */
    private val _reactionEmojis = MutableStateFlow(DEFAULT_REACTION_EMOJIS)
    val reactionEmojis: StateFlow<List<String>> = _reactionEmojis.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("rcq_local", Context.MODE_PRIVATE)
        // Global (app-wide) settings only; per-account flows load in bindAccount.
        _themeMode.value = runCatching { ThemeMode.valueOf(prefs.getString(K_THEME, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
        _chatBackground.value = prefs.getString(K_CHAT_BG, "") ?: ""
        _homeBackground.value = prefs.getString(K_HOME_BG, "") ?: ""
        _fontScale.value = prefs.getFloat(K_FONT_SCALE, 1.0f)
        _lockGrace.value = prefs.getInt(K_LOCK_GRACE, 0)
        _soundMaster.value = prefs.getBoolean(K_SND_MASTER, true)
        _soundMessages.value = prefs.getBoolean(K_SND_MSG, true)
        _soundPresence.value = prefs.getBoolean(K_SND_PRES, true)
        _screenSecurity.value = prefs.getBoolean(K_SCREEN_SEC, false)
        _sectionFlags.value = prefs.getStringSet(K_SECTION_FLAGS, emptySet())!!.toSet()
        // Stored as comma-joined asset names (asset names never contain commas).
        // Panel: absent/"" → empty (the CTA shows). Reactions: absent → the
        // default six; "" → the user deliberately cleared them all.
        _panelEmojis.value = prefs.getString(K_PANEL_EMOJI, "")!!.split(",").filter { it.isNotBlank() }
        _reactionEmojis.value = prefs.getString(K_REACTION_EMOJI, null)
            ?.split(",")?.filter { it.isNotBlank() }
            ?: DEFAULT_REACTION_EMOJIS
    }

    /** Point the per-account flows at [accountId]'s slots and reload them.
     *  null (no active account) resets the flows to empty. */
    fun bindAccount(accountId: String?) {
        acct = accountId
        if (accountId == null) {
            _favorites.value = emptySet()
            _muted.value = emptySet()
            _mentionsOnly.value = emptySet()
            _archived.value = emptySet()
            _removed.value = emptySet()
            _blocked.value = emptySet()
            _unread.value = emptyMap()
            _reactionInbox.value = emptySet()
            _reactedMsgIds.value = emptyMap()
            _mentionInbox.value = emptySet()
            _presenceWindow.value = null
            _secureThreads.value = emptySet()
            return
        }
        _favorites.value = prefs.getStringSet(pk(K_FAV), emptySet())!!.toSet()
        _muted.value = prefs.getStringSet(pk(K_MUTE), emptySet())!!.toSet()
        _mentionsOnly.value = prefs.getStringSet(pk(K_MENTIONS), emptySet())!!.toSet()
        _archived.value = prefs.getStringSet(pk(K_ARCH), emptySet())!!.toSet()
        _removed.value = prefs.getStringSet(pk(K_REMOVED), emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        _blocked.value = prefs.getStringSet(pk(K_BLOCKED), emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        _unread.value = loadUnread(pk(K_UNREAD))
        _reactionInbox.value = prefs.getStringSet(pk(K_REACT_INBOX), emptySet())!!.toSet()
        _reactedMsgIds.value = loadReactedMsgIds(pk(K_REACTED_MSGS))
        _mentionInbox.value = prefs.getStringSet(pk(K_MENTION_INBOX), emptySet())!!.toSet()
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

    fun isMentionsOnly(thread: String) = thread in _mentionsOnly.value

    /** Group notify mode (#11): ALL rings always, MENTIONS rings only on an
     *  @mention, NONE is fully silent. The two sets stay mutually exclusive. */
    enum class NotifyMode { ALL, MENTIONS, NONE }
    fun notifyMode(thread: String): NotifyMode = when {
        thread in _muted.value -> NotifyMode.NONE
        thread in _mentionsOnly.value -> NotifyMode.MENTIONS
        else -> NotifyMode.ALL
    }
    fun setNotifyMode(thread: String, mode: NotifyMode) {
        if (acct == null) return
        val muted = _muted.value.toMutableSet()
        val mentions = _mentionsOnly.value.toMutableSet()
        muted.remove(thread); mentions.remove(thread)
        when (mode) {
            NotifyMode.ALL -> {}
            NotifyMode.MENTIONS -> mentions.add(thread)
            NotifyMode.NONE -> muted.add(thread)
        }
        _muted.value = muted; _mentionsOnly.value = mentions
        prefs.edit()
            .putStringSet(pk(K_MUTE), muted)
            .putStringSet(pk(K_MENTIONS), mentions)
            .apply()
    }

    /** Whether an account is bound (post-login). Guards the push-mute sync from
     *  PUTting an empty set before the muted threads have loaded. */
    fun isAccountBound(): Boolean = acct != null

    /** Group ids the user has FULLY muted (NotifyMode.NONE), for the server
     *  push-suppression sync (`muted_group_ids`). The server's `is_group_muted`
     *  gate reads this so a muted group never wakes the device. */
    fun mutedGroupIds(): List<Int> =
        _muted.value.mapNotNull { if (it.startsWith("group:")) it.substringAfter("group:").toIntOrNull() else null }

    /** Peer uins the user has fully muted, for the server `muted_uins` sync. */
    fun mutedPeerUins(): List<Int> =
        _muted.value.mapNotNull { if (it.startsWith("peer:")) it.substringAfter("peer:").toIntOrNull() else null }

    fun isArchived(thread: String) = thread in _archived.value
    fun toggleArchive(thread: String) = toggle(_archived, K_ARCH, thread)

    fun isRemoved(uin: Int) = uin in _removed.value
    fun addRemoved(uin: Int) {
        if (acct == null || uin in _removed.value) return
        _removed.value = _removed.value + uin
        prefs.edit().putStringSet(pk(K_REMOVED), _removed.value.map(Int::toString).toSet()).apply()
    }

    fun isBlocked(uin: Int) = uin in _blocked.value
    fun setBlocked(uin: Int, on: Boolean) {
        if (acct == null || on == (uin in _blocked.value)) return
        _blocked.value = if (on) _blocked.value + uin else _blocked.value - uin
        prefs.edit().putStringSet(pk(K_BLOCKED), _blocked.value.map(Int::toString).toSet()).apply()
    }

    fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(0.85f, 1.5f)
        _fontScale.value = clamped
        prefs.edit().putFloat(K_FONT_SCALE, clamped).apply()
    }

    fun setLockGrace(seconds: Int) {
        _lockGrace.value = seconds.coerceAtLeast(0)
        prefs.edit().putInt(K_LOCK_GRACE, _lockGrace.value).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(K_THEME, mode.name).apply()
    }

    /** "" (default) / "preset:<id>" / "custom". */
    fun setChatBackground(value: String) {
        _chatBackground.value = value
        prefs.edit().putString(K_CHAT_BG, value).apply()
    }

    /** The saved custom chat-wallpaper image file. */
    fun chatBgFile(context: Context): java.io.File =
        java.io.File(context.applicationContext.filesDir, "chat_bg.jpg")

    /** Persist a picked image as the custom chat wallpaper and select it. */
    fun saveChatBackgroundImage(context: Context, bytes: ByteArray) {
        runCatching { chatBgFile(context).writeBytes(bytes) }
        setChatBackground("custom")
    }

    /** Home / chat-list wallpaper — parallel to the chat one. */
    fun setHomeBackground(value: String) {
        _homeBackground.value = value
        prefs.edit().putString(K_HOME_BG, value).apply()
    }

    fun homeBgFile(context: Context): java.io.File =
        java.io.File(context.applicationContext.filesDir, "home_bg.jpg")

    fun saveHomeBackgroundImage(context: Context, bytes: ByteArray) {
        runCatching { homeBgFile(context).writeBytes(bytes) }
        setHomeBackground("custom")
    }

    // ── emoji customisation (global) ─────────────────────────────────────
    /** Set the composer-panel emoticon set (asset names, pick order; capped at
     *  40, de-duplicated). Persisted as a comma-joined string. */
    fun setPanelEmojis(list: List<String>) {
        val capped = list.distinct().take(40)
        _panelEmojis.value = capped
        if (::prefs.isInitialized) prefs.edit().putString(K_PANEL_EMOJI, capped.joinToString(",")).apply()
    }

    /** Set the quick-reaction set (asset names, pick order; capped at 6,
     *  de-duplicated). Persisted as a comma-joined string. */
    fun setReactionEmojis(list: List<String>) {
        val capped = list.distinct().take(6)
        _reactionEmojis.value = capped
        if (::prefs.isInitialized) prefs.edit().putString(K_REACTION_EMOJI, capped.joinToString(",")).apply()
    }

    // ── sound toggles ────────────────────────────────────────────────
    fun soundMasterOn() = _soundMaster.value
    fun soundMessagesOn() = _soundMessages.value
    fun soundPresenceOn() = _soundPresence.value
    fun setSoundMaster(on: Boolean) { _soundMaster.value = on; prefs.edit().putBoolean(K_SND_MASTER, on).apply() }
    fun setSoundMessages(on: Boolean) { _soundMessages.value = on; prefs.edit().putBoolean(K_SND_MSG, on).apply() }
    fun setSoundPresence(on: Boolean) { _soundPresence.value = on; prefs.edit().putBoolean(K_SND_PRES, on).apply() }

    fun screenSecurityOn() = _screenSecurity.value
    fun setScreenSecurity(on: Boolean) { _screenSecurity.value = on; prefs.edit().putBoolean(K_SCREEN_SEC, on).apply() }

    // ── home section fold flags (global UI preference) ────────────────
    fun isSectionFlag(id: String) = id in _sectionFlags.value
    fun setSectionFlag(id: String, on: Boolean) {
        val next = if (on) _sectionFlags.value + id else _sectionFlags.value - id
        if (next == _sectionFlags.value) return
        _sectionFlags.value = next
        prefs.edit().putStringSet(K_SECTION_FLAGS, next.toSet()).apply()
    }

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

    // ── reaction / mention home-row inboxes ──────────────────────────
    fun markReaction(thread: String) = addTo(_reactionInbox, K_REACT_INBOX, thread)
    fun clearReaction(thread: String) = removeFrom(_reactionInbox, K_REACT_INBOX, thread)
    fun markMention(thread: String) = addTo(_mentionInbox, K_MENTION_INBOX, thread)
    fun clearMention(thread: String) = removeFrom(_mentionInbox, K_MENTION_INBOX, thread)

    /** Record [msgId] (one of MY messages) as having an unseen reaction in
     *  [thread], for the reaction-jump on chat open. Paired with [markReaction]. */
    fun markReactedMsg(thread: String, msgId: String) {
        if (acct == null) return
        val cur = _reactedMsgIds.value[thread] ?: emptySet()
        if (msgId in cur) return
        _reactedMsgIds.value = _reactedMsgIds.value + (thread to (cur + msgId))
        persistReactedMsgIds()
    }

    /** Drop all recorded reacted-message ids for [thread] (the jump consumed
     *  them, so they don't re-flash on reopen). */
    fun clearReactedMsgs(thread: String) {
        if (_reactedMsgIds.value[thread] == null) return
        _reactedMsgIds.value = _reactedMsgIds.value - thread
        persistReactedMsgIds()
    }

    /** Encode the thread -> ids map as a "thread|id1,id2" StringSet for prefs. */
    private fun persistReactedMsgIds() {
        if (acct == null) return
        val encoded = _reactedMsgIds.value
            .filterValues { it.isNotEmpty() }
            .map { "${it.key}|${it.value.joinToString(",")}" }
            .toSet()
        prefs.edit().putStringSet(pk(K_REACTED_MSGS), encoded).apply()
    }

    private fun loadReactedMsgIds(key: String): Map<String, Set<String>> =
        prefs.getStringSet(key, emptySet())!!.mapNotNull { entry ->
            val i = entry.indexOf('|')
            if (i <= 0) return@mapNotNull null
            val thread = entry.substring(0, i)
            val ids = entry.substring(i + 1).split(',').filter { it.isNotBlank() }.toSet()
            if (ids.isEmpty()) null else thread to ids
        }.toMap()

    /** Add [thread] to [flow] (copied-Set + persist), no-op if already present. */
    private fun addTo(flow: MutableStateFlow<Set<String>>, key: String, thread: String) {
        if (acct == null || thread in flow.value) return
        flow.value = flow.value + thread
        prefs.edit().putStringSet(pk(key), flow.value.toSet()).apply()
    }

    /** Remove [thread] from [flow] (copied-Set + persist), no-op if absent. */
    private fun removeFrom(flow: MutableStateFlow<Set<String>>, key: String, thread: String) {
        if (acct == null || thread !in flow.value) return
        flow.value = flow.value - thread
        prefs.edit().putStringSet(pk(key), flow.value.toSet()).apply()
    }

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
        listOf(K_FAV, K_MUTE, K_ARCH, K_REMOVED, K_UNREAD, K_REACT_INBOX, K_REACTED_MSGS, K_MENTION_INBOX).forEach { k ->
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
        listOf(K_FAV, K_MUTE, K_MENTIONS, K_ARCH, K_REMOVED, K_BLOCKED, K_UNREAD, K_REACT_INBOX, K_REACTED_MSGS, K_MENTION_INBOX, K_PRIVACY_CACHE, K_CONTACTS_CACHE, K_GROUPS_CACHE).forEach { e.remove("$accountId.$it") }
        e.apply()
    }

    private const val K_FAV = "favorites"
    private const val K_MUTE = "muted"
    private const val K_MENTIONS = "mentions_only"
    private const val K_ARCH = "archived"
    private const val K_REMOVED = "removed"
    private const val K_BLOCKED = "blocked"
    private const val K_THEME = "theme_mode"
    private const val K_CHAT_BG = "chat_background"
    private const val K_HOME_BG = "home_background"
    private const val K_PANEL_EMOJI = "panel_emojis"
    private const val K_REACTION_EMOJI = "reaction_emojis"
    private const val K_FONT_SCALE = "font_scale"
    private const val K_LOCK_GRACE = "lock_grace_seconds"
    private const val K_UNREAD = "unread"
    private const val K_REACT_INBOX = "reaction_inbox"
    private const val K_REACTED_MSGS = "reacted_msg_ids"
    private const val K_MENTION_INBOX = "mention_inbox"
    private const val K_SND_MASTER = "sound_master"
    private const val K_SND_MSG = "sound_messages"
    private const val K_SND_PRES = "sound_presence"
    private const val K_SCREEN_SEC = "screen_security"
    private const val K_PRES_WIN = "presence_window"
    private const val K_SECURE = "secure_threads"
    private const val K_SECTION_FLAGS = "section_flags"
    private const val K_PRIVACY_CACHE = "privacy_cache"
    private const val K_CONTACTS_CACHE = "contacts_cache"
    private const val K_GROUPS_CACHE = "groups_cache"
}
