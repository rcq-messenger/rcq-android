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

    /** Which account this singleton is currently bound to. For callers that
     *  have to hold on to it across a suspension and pin their writes with it
     *  (see [setMyProfileKey], [setVaultSlotVersion]). */
    fun boundAccount(): String? = acct

    // ── per-account flows ────────────────────────────────────────────────
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()
    /** My own name for a contact, keyed by UIN. DEVICE-ONLY on purpose: what I
     *  chose to call someone says more about the relationship than the contact
     *  row itself, it serves no server-side function, and an island that stores
     *  it is an island that can be made to hand it over. The cost is honest:
     *  aliases do not follow you to another device until the backup does. */
    /** My own names for people, keyed by [aliasKey] — the bare uin for someone
     *  on this island, `uin@host` for someone on another one. ⚠ A uin is
     *  per-island, so keying by the number alone gave `1234` here and
     *  `1234@is2.rcq.app` ONE name between them: renaming the stranger renamed
     *  your friend. */
    private val _aliases = MutableStateFlow<Map<String, String>>(emptyMap())
    val aliases: StateFlow<Map<String, String>> = _aliases.asStateFlow()

    private val _muted = MutableStateFlow<Set<String>>(emptySet())
    val muted: StateFlow<Set<String>> = _muted.asStateFlow()

    /** Group threads in "mentions only" notify mode: silent UNLESS the message
     *  @mentions me (#11). Mutually exclusive with [_muted] (= fully silent);
     *  a thread in neither set rings for everything. */
    private val _mentionsOnly = MutableStateFlow<Set<String>>(emptySet())
    val mentionsOnly: StateFlow<Set<String>> = _mentionsOnly.asStateFlow()

    private val _archived = MutableStateFlow<Set<String>>(emptySet())
    val archived: StateFlow<Set<String>> = _archived.asStateFlow()

    /** The cached chat-list SECTIONS tree (founder item 1 of 23.08). Unlike
     *  every other flow here this is not a device preference: it is a copy of
     *  the account's `sections` vault slot, folded in by [SectionsVault] and
     *  pushed back out by it. The chat list renders from this cache and never
     *  waits on the island.
     *
     *  ⚠ Per-account, like the rest of this block and unlike the collapse set
     *  next door: it holds section NAMES and the uin of every filed chat, so a
     *  flat key would hand one account's list to the next one signed in here.
     *  [bindAccount] is what points it at an account, and nothing may read or
     *  write it before that has run. */
    private val _sections = MutableStateFlow(app.rcq.android.data.Sections.emptyTree())
    val sections: StateFlow<com.google.gson.JsonObject> = _sections.asStateFlow()

    /** Threads the user locked behind the app PIN ("peer:<uin>"/"group:<id>").
     *  Opening a locked chat prompts for the existing PIN first. Only offered
     *  when a PIN is configured; cleared automatically if the PIN is removed. */
    private val _locked = MutableStateFlow<Set<String>>(emptySet())
    val locked: StateFlow<Set<String>> = _locked.asStateFlow()

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

    /** Opt-in same-island stranger quarantine (Privacy): messages from people
     *  outside the contacts wait in the requests list instead of opening a
     *  chat. Device-local like [_blocked]: the mailbox itself stays open
     *  (sealed sender), this only decides where THIS install files a
     *  stranger's first message. Mirrors web-chat's stranger-requests.ts. */
    private val _strangerQuarantine = MutableStateFlow(false)
    val strangerQuarantine: StateFlow<Boolean> = _strangerQuarantine.asStateFlow()

    /** Strangers the user Accepted from the requests list. Accepting means
     *  their FUTURE messages flow too, so the allowance persists here. */
    private val _allowedStrangers = MutableStateFlow<Set<Int>>(emptySet())

    /** Peers whose island answered "no such number" after a send failed — i.e.
     *  they burned the account. Discovered lazily, never polled: asking the
     *  server periodically whether each of your contacts still exists is
     *  exactly the metadata traffic this project avoids, so the question is
     *  only asked when a send has already failed (user report: "I burned my
     *  other account, writing to it from the main one just says it could not
     *  send — mark them instead"). Cleared if they ever come back. */
    private val _gonePeers = MutableStateFlow<Set<Int>>(emptySet())
    val gonePeers: StateFlow<Set<Int>> = _gonePeers.asStateFlow()

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

    /** Per-group "last seen @mention" cut-off (epoch millis). The @-jump FAB only
     *  shows mentions NEWER than this, so re-entering a chat doesn't resurface
     *  mentions you already viewed (Android parity with iOS MentionSeenStore).
     *  Marked (monotonically) on chat exit; a genuinely newer mention brings the
     *  FAB back. Keyed by group id. */
    private val _mentionSeenAt = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val mentionSeenAt: StateFlow<Map<Int, Long>> = _mentionSeenAt.asStateFlow()

    /** Where reading stopped, per thread (founder batch item 13a, iOS parity):
     *  the resting scroll position of an open chat as "rows from the end" +
     *  first-visible pixel offset. Distance from the END so history growing
     *  above does not move the anchor. An entry exists only for threads left
     *  ABOVE the bottom; reading to the bottom clears it, so those chats keep
     *  opening at the newest message. Plain map, no flow: read once on chat
     *  open, nothing observes it. */
    private var _chatPos: Map<String, Pair<Int, Int>> = emptyMap()

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
    /// Whether an animated avatar is allowed to actually animate.
    ///
    /// Lists already draw a still first frame; this covers the places that do
    /// animate — a chat header, a contact card, the group rows on the home
    /// screen. Decoding a GIF frame by frame is the kind of work that costs
    /// battery all day for something nobody is looking at, so it can be turned
    /// off entirely. Device-local: the island never sees it.
    private val _animateAvatars = MutableStateFlow(true)

    /// Which way a message is dragged to quote it.
    ///
    /// There is no right answer: Telegram pulls the row LEFT, WhatsApp and
    /// Signal pull it RIGHT, and people arrive here with the habit of whichever
    /// they used before (#526). So it is a setting rather than a decision.
    /// Device-local, like the rest of this block.
    private val _swipeReplySide = MutableStateFlow(SwipeReplySide.LEFT)

    private val _soundMaster = MutableStateFlow(true)
    val animateAvatars: StateFlow<Boolean> = _animateAvatars.asStateFlow()
    val swipeReplySide: StateFlow<SwipeReplySide> = _swipeReplySide.asStateFlow()
    val soundMaster: StateFlow<Boolean> = _soundMaster.asStateFlow()
    // Scale factor for the in-app tone, 0f..1f. NOT an absolute level: the
    // tone rides the notification stream and only the system sets how loud
    // that is (see SoundService).
    private val _soundVolume = MutableStateFlow(1f)
    val soundVolume: StateFlow<Float> = _soundVolume.asStateFlow()

    private val _soundMessages = MutableStateFlow(true)
    val soundMessages: StateFlow<Boolean> = _soundMessages.asStateFlow()

    /** Who the online/offline chime plays for (#552).
     *
     *  A two-state toggle was not enough: with a full roster the chime fires
     *  often enough that the person cannot tell it apart from a bug ("сейчас
     *  просто звук идёт и я не понимаю это баг или кто-то зашёл"), and the only
     *  cure on offer was losing it entirely. [FAVORITES] is the middle setting
     *  — the handful of people whose arrival is actually worth a sound.
     *
     *  Device-local, like the rest of this block; the web client's presence
     *  toggle is the same idea with one fewer step. */
    enum class PresenceSoundMode { ALL, FAVORITES, OFF }

    private val _presenceSound = MutableStateFlow(PresenceSoundMode.ALL)
    val presenceSound: StateFlow<PresenceSoundMode> = _presenceSound.asStateFlow()

    /** When on, the app window gets FLAG_SECURE: screenshots/screen-recording
     *  are blocked and content is hidden in the app switcher. Device-global,
     *  applied by MainActivity. */
    private val _screenSecurity = MutableStateFlow(false)
    val screenSecurity: StateFlow<Boolean> = _screenSecurity.asStateFlow()

    /** Whether the user permanently dismissed the home-screen "install a push
     *  distributor" nudge (shown when no UnifiedPush distributor is present).
     *  Device-global and persisted: once dismissed it stays dismissed across
     *  navigation and cold starts (a plain remember{} reset on every return to
     *  the home list — v0.66 regression). When a distributor later appears the
     *  banner is gated off anyway, so this never hides a real need. */
    private val _pushNudgeDismissed = MutableStateFlow(false)
    val pushNudgeDismissed: StateFlow<Boolean> = _pushNudgeDismissed.asStateFlow()

    /** PIN re-lock grace in SECONDS (#10): 0 = lock the moment the app
     *  backgrounds (current behaviour); >0 = only re-lock if away longer than
     *  this, so quick app switches don't demand the PIN every time. */
    private val _lockGrace = MutableStateFlow(0)
    val lockGrace: StateFlow<Int> = _lockGrace.asStateFlow()
    fun lockGraceSeconds(): Int = if (::prefs.isInitialized) _lockGrace.value else 0

    /** Home-list UI flags (a set of stable string ids): which sections the
     *  user has folded. Persisted, so a collapsed section stays collapsed
     *  across leaving and re-entering the home screen and across a cold start
     *  (report: the offline section kept re-expanding because the state was
     *  only in-memory remember{}).
     *
     *  ⚠ DEVICE-LOCAL on purpose. The sections themselves sync through the
     *  vault slot; the fold state deliberately sits outside it (sections
     *  design, 23.08: "the collapse set stays device-local"), because a phone
     *  where Offline is forty rows and a desktop window are not the same
     *  screen.
     *
     *  ⚠ PER ACCOUNT since it started holding `sec:u:<id>` keys. A user
     *  section's id means something only inside the account whose tree carries
     *  it, so the flat key filed one account's fold state against another
     *  account's sections and handed the real account's to a decoy session.
     *  Loaded in [bindAccount] like the rest of the per-account block; the flat
     *  key it used to live under is migrated once, built-ins only. */
    private val _sectionFlags = MutableStateFlow<Set<String>>(emptySet())
    val sectionFlags: StateFlow<Set<String>> = _sectionFlags.asStateFlow()

    /** Per-account, per-thread "screen-secure" chats (peer:UIN keys). When a
     *  secure chat is open, ChatScreen adds FLAG_SECURE so screenshots/recording
     *  of THAT chat are blocked; the flag is propagated to the peer so both
     *  sides enforce it (iOS parity). */
    private val _secureThreads = MutableStateFlow<Set<String>>(emptySet())
    val secureThreads: StateFlow<Set<String>> = _secureThreads.asStateFlow()

    /** The threads the PEER asked to keep screen-secure, in the same
     *  `peer:<uin>` keys and kept in a slot of their OWN.
     *
     *  ★★★ #722. The flag is mutual, and an inbound SecureScreen used to be
     *  written straight into [_secureThreads] above: the other side could
     *  therefore switch MY alerts off without a word, take a screenshot with
     *  the notice disarmed, and switch them back on. Their wish is recorded
     *  here instead, mine stays mine, and [isThreadSecure] arms the alerts
     *  while EITHER set holds the thread. So the peer can raise this
     *  protection and can never lower it, and the two bits stay tellable
     *  apart, which is what lets the chat say who turned what on. */
    private val _peerSecureThreads = MutableStateFlow<Set<String>>(emptySet())
    val peerSecureThreads: StateFlow<Set<String>> = _peerSecureThreads.asStateFlow()

    /** Historical fixed reaction set — the default until the user customises
     *  their own. Asset names match iOS exactly so a reaction renders the same
     *  GIF on both clients.
     *
     *  ⚠⚠ EVERY NAME HERE MUST HAVE `assets/emoticons/<name>.gif` BEHIND IT.
     *  This list was written out inline "to keep this `data` store free of UI
     *  deps" and then drifted: `biggrin`, `shok` and `mad` name glyphs this app
     *  has never bundled, so half of the reaction row was BLANK on every fresh
     *  install, for as long as the row has existed. The one list that is drawn
     *  from the bundled pack owns it now. A getter, not a val, so it cannot
     *  depend on which object initialises first. */
    private val DEFAULT_REACTION_EMOJIS: List<String>
        get() = app.rcq.android.ui.Emoticons.defaultReactions

    /** The user's chosen composer-panel emoticons (asset names, in pick order).
     *  EMPTY by default → the composer panel shows a "Choose" CTA until the user
     *  picks their own set. Global (one set across accounts), like the
     *  wallpapers. The `:asset:` codes are the wire form and render anywhere the
     *  asset is bundled. */
    private val _panelEmojis = MutableStateFlow<List<String>>(emptyList())
    val panelEmojis: StateFlow<List<String>> = _panelEmojis.asStateFlow()

    /** The user's chosen quick reactions (asset names, ≤40) offered on the
     *  long-press reaction row. Defaults to [DEFAULT_REACTION_EMOJIS] until
     *  customised. */
    private val _reactionEmojis = MutableStateFlow(DEFAULT_REACTION_EMOJIS)
    val reactionEmojis: StateFlow<List<String>> = _reactionEmojis.asStateFlow()

    /** How many times I have used each reaction asset (founder item 21).
     *
     *  ⚠ PER ACCOUNT, and loaded in [bindAccount] rather than [init]: the same
     *  trap the roster flows above are prefixed for. Which faces a person
     *  reaches for is a portrait of who they talk to, and a global counter would
     *  carry one identity's habits straight onto another identity's reaction
     *  bar, where the other person can watch it change.
     *
     *  A plain map and no flow, like [_chatPos]: the bar reads this once when it
     *  opens and nothing observes it in between. Reading it is a hash lookup, so
     *  a long-press pays nothing.
     *
     *  Bounded twice over: at most [REACTION_USE_MAX_KEYS] assets are kept (the
     *  least-used fall out when a new one pushes past the cap) and one count
     *  stops climbing at [REACTION_USE_MAX_COUNT], so neither the prefs entry
     *  nor a single tally can grow without end. */
    private var _reactionUses: Map<String, Int> = emptyMap()

    /** Disappearing messages (founder item 20): the per-thread timer, in
     *  SECONDS, keyed by the same `peer:<uin>` / `group:<id>` thread key
     *  [_secureThreads] uses — and that iOS and the web use for their own copy
     *  of this setting, so a thread reads the same on all three while it is
     *  being debugged.
     *
     *  ⚠ PER ACCOUNT, which is the whole reason it lives here rather than in
     *  the picker that sets it. "peer:5" IS NOT A PERSON: two local identities
     *  each have a #5, and on two islands those are two different people. Held
     *  in the screen's own process-global map it was unscoped, so one
     *  identity's timer appeared preselected in another identity's chat with
     *  the same number — and with the send path wired that is not a cosmetic
     *  bug at all, it is a ttl on messages that identity never asked to
     *  disappear. [bindAccount] repoints it and [clearAccount] wipes it.
     *
     *  A plain map and no flow, like [_chatPos]: the chat reads it when it
     *  opens and when the user picks, and nothing observes it in between.
     *
     *  "Off" REMOVES the entry rather than storing a zero — "off" and "never
     *  touched" are one state and should not be two shapes on disk. Same rule
     *  as the web's `setThreadTtl`. */
    private var _threadTtls: Map<String, Int> = emptyMap()

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
        _animateAvatars.value = prefs.getBoolean(K_ANIM_AVATARS, true)
        _swipeReplySide.value = runCatching {
            SwipeReplySide.valueOf(prefs.getString(K_SWIPE_SIDE, null) ?: "LEFT")
        }.getOrDefault(SwipeReplySide.LEFT)
        _soundMaster.value = prefs.getBoolean(K_SND_MASTER, true)
        _soundMessages.value = prefs.getBoolean(K_SND_MSG, true)
        // Migration: the old boolean becomes ALL / OFF, so nobody's phone
        // changes behaviour on update. The new key wins once it exists.
        _presenceSound.value = runCatching {
            prefs.getString(K_SND_PRES_MODE, null)?.let { PresenceSoundMode.valueOf(it) }
        }.getOrNull()
            ?: if (prefs.getBoolean(K_SND_PRES, true)) PresenceSoundMode.ALL else PresenceSoundMode.OFF
        _soundVolume.value = prefs.getFloat(K_SND_VOL, 1f).coerceIn(0f, 1f)
        _screenSecurity.value = prefs.getBoolean(K_SCREEN_SEC, false)
        _pushNudgeDismissed.value = prefs.getBoolean(K_PUSH_NUDGE_DISMISSED, false)
        // Stored as comma-joined asset names (asset names never contain commas).
        // Panel: absent/"" → empty (the CTA shows). Reactions: absent → the
        // default six; "" → the user deliberately cleared them all.
        //
        // Both filtered against the CURRENT pack: a set curated before a pack
        // was retired keeps its asset names, and a name with no glyph behind
        // it drew as bare text in the picker (2026-08-20, the day the old pack
        // left). A reaction set the retirement emptied falls back to the
        // defaults — that emptiness is not the user's "cleared them all".
        val valid = app.rcq.android.ui.Emoticons.fullSet.toSet()
        _panelEmojis.value = prefs.getString(K_PANEL_EMOJI, "")!!.split(",")
            .filter { it.isNotBlank() && it in valid }
        val storedReactions = prefs.getString(K_REACTION_EMOJI, null)
            ?.split(",")?.filter { it.isNotBlank() }
        val liveReactions = storedReactions?.filter { it in valid }
        // ⚠ The defaults go through `valid` too. They did not, which is how
        // three names with no glyph behind them survived: a stored set was
        // checked against the pack and the fallback never was. Filtered, a
        // future drift costs a slot instead of showing an empty one.
        _reactionEmojis.value = when {
            storedReactions == null -> DEFAULT_REACTION_EMOJIS.filter { it in valid }
            liveReactions!!.isEmpty() && storedReactions.isNotEmpty() -> DEFAULT_REACTION_EMOJIS.filter { it in valid }
            else -> liveReactions
        }
    }

    /** Point the per-account flows at [accountId]'s slots and reload them.
     *  null (no active account) resets the flows to empty.
     *
     *  ⚠⚠ Takes the same monitor as [updateSectionsTree] and its neighbours, so
     *  a vault answer that is in flight while the account switcher (or the
     *  duress PIN) rebinds cannot land between the scope test and the write.
     *  See the note there. */
    @Synchronized
    fun bindAccount(accountId: String?) {
        acct = accountId
        if (accountId == null) {
            _favorites.value = emptySet()
            _muted.value = emptySet()
            _mentionsOnly.value = emptySet()
            _archived.value = emptySet()
            _sections.value = Sections.emptyTree()
            _locked.value = emptySet()
            _removed.value = emptySet()
            _blocked.value = emptySet()
            _strangerQuarantine.value = false
            _allowedStrangers.value = emptySet()
            _gonePeers.value = emptySet()
            _unread.value = emptyMap()
            _gskeys.value = emptyMap()
            // ⚠ The profile keys of every contact. Added with the feature and
            // left out of this list, so unbinding kept one account's map of
            // "number -> key to their face" live in memory for the next one.
            _pkeys.value = emptyMap()
            _reactionInbox.value = emptySet()
            _reactedMsgIds.value = emptyMap()
            _mentionInbox.value = emptySet()
            _mentionSeenAt.value = emptyMap()
            _chatPos = emptyMap()
            _secureThreads.value = emptySet()
            _peerSecureThreads.value = emptySet()
            _threadTtls = emptyMap()
            _aliases.value = emptyMap()
            _reactionUses = emptyMap()
            _sectionFlags.value = emptySet()
            return
        }
        _favorites.value = prefs.getStringSet(pk(K_FAV), emptySet())!!.toSet()
        // Rows written before the key carried a host are plain "<uin>=<name>",
        // and they stay valid: a bare key IS the same-island key.
        _aliases.value = (prefs.getStringSet(pk(K_ALIAS), emptySet()) ?: emptySet())
            .mapNotNull { row ->
                val i = row.indexOf('=')
                if (i <= 0) null else row.take(i) to row.substring(i + 1)
            }.toMap()
        _muted.value = prefs.getStringSet(pk(K_MUTE), emptySet())!!.toSet()
        _mentionsOnly.value = prefs.getStringSet(pk(K_MENTIONS), emptySet())!!.toSet()
        _archived.value = prefs.getStringSet(pk(K_ARCH), emptySet())!!.toSet()
        _sections.value = parseSections(prefs.getString(pk(K_SECTIONS), null))
        _locked.value = prefs.getStringSet(pk(K_LOCKED), emptySet())!!.toSet()
        _removed.value = prefs.getStringSet(pk(K_REMOVED), emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        _blocked.value = prefs.getStringSet(pk(K_BLOCKED), emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        _strangerQuarantine.value = prefs.getBoolean(pk(K_STRANGER_Q), false)
        _allowedStrangers.value = prefs.getStringSet(pk(K_STRANGER_ALLOW), emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        _gonePeers.value = prefs.getStringSet(pk(K_GONE), emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        _unread.value = loadCounts(pk(K_UNREAD))
        loadRoomKeys()
        loadProfileKeys()
        _reactionInbox.value = prefs.getStringSet(pk(K_REACT_INBOX), emptySet())!!.toSet()
        _reactedMsgIds.value = loadReactedMsgIds(pk(K_REACTED_MSGS))
        _mentionInbox.value = prefs.getStringSet(pk(K_MENTION_INBOX), emptySet())!!.toSet()
        _mentionSeenAt.value = loadMentionSeen(pk(K_MENTION_SEEN))
        _chatPos = loadChatPos(pk(K_CHAT_POS))
        _secureThreads.value = prefs.getStringSet(pk(K_SECURE), emptySet())!!.toSet()
        _peerSecureThreads.value = prefs.getStringSet(pk(K_SECURE_PEER), emptySet())!!.toSet()
        _reactionUses = loadCounts(pk(K_REACTION_USES))
        _threadTtls = loadCounts(pk(K_THREAD_TTL))
        _sectionFlags.value = loadSectionFlags()
    }

    /** The folded sections of the bound account.
     *
     *  One-time migration off the flat key this used to live under: an account
     *  that has never stored a set of its own inherits the BUILT-IN folds
     *  (`sec:fav`, `sec:offline`, the archive marker...) so nobody's home screen
     *  rearranges itself on update. The `sec:u:<id>` keys are left behind
     *  deliberately: those ids belong to whichever account created them and
     *  name nothing in anybody else's tree. The flat key is not deleted, so the
     *  second account on this device migrates from it too.
     *
     *  ⚠⚠ NOT FOR THE DECOY NAMESPACE. The real user's folds are the real
     *  user's: inheriting `sec:offline` there would open the duress view on a
     *  chat list with nothing visible in it, because every seeded decoy contact
     *  is offline and Offline is the only section they land in. It would also
     *  leave a write on disk made from inside a duress session. iOS guards the
     *  same case in `ContactListView.defaultCollapsed` (`!panicPIN.isDecoy`).
     *  Compared against the id rather than [AccountManager.isDecoyMode]: the
     *  bind happens BEFORE `enterDecoySession()`, so the mode is not up yet. */
    private fun loadSectionFlags(): Set<String> {
        val own = prefs.getStringSet(pk(K_SECTION_FLAGS), null)
        if (own != null) return own.toSet()
        if (acct == DecoyStore.STORE_ID) return emptySet()
        val legacy = prefs.getStringSet(K_SECTION_FLAGS, emptySet())!!
            .filterNot { it.startsWith("sec:u:") }
            .toSet()
        if (legacy.isNotEmpty()) prefs.edit().putStringSet(pk(K_SECTION_FLAGS), legacy).apply()
        return legacy
    }

    /** Did I ask for screenshot alerts here myself? */
    fun isThreadSecureByMe(thread: String) = thread in _secureThreads.value

    /** Did the peer ask for them? Their wish alone keeps the alerts armed. */
    fun isThreadSecureByPeer(thread: String) = thread in _peerSecureThreads.value

    /** Are the alerts armed for this thread at all: mine OR theirs. Deliberately
     *  the OR and not the peer's last word (#722): whoever wants the notice gets
     *  it, and nobody can take it away from the other side. */
    fun isThreadSecure(thread: String) = isThreadSecureByMe(thread) || isThreadSecureByPeer(thread)

    /** Set/clear MY screen-secure wish for a thread (local store only: the
     *  caller propagates it to the peer via a SecureScreen envelope). */
    fun setThreadSecure(thread: String, on: Boolean) {
        if (acct == null) return
        _secureThreads.value = if (on) _secureThreads.value + thread else _secureThreads.value - thread
        prefs.edit().putStringSet(pk(K_SECURE), _secureThreads.value).apply()
    }

    /** Record the PEER's wish for a thread. Never touches my own set, so a
     *  remote "off" cannot disarm what I turned on. */
    fun setThreadSecureByPeer(thread: String, on: Boolean) {
        if (acct == null) return
        _peerSecureThreads.value =
            if (on) _peerSecureThreads.value + thread else _peerSecureThreads.value - thread
        prefs.edit().putStringSet(pk(K_SECURE_PEER), _peerSecureThreads.value).apply()
    }

    // ── disappearing messages: the per-thread timer (founder item 20) ──

    /** This thread's timer in seconds, or null when disappearing is off here.
     *  Null before an account is bound, so a caller never inherits a timer from
     *  whoever was signed in last. */
    fun threadTtl(thread: String): Int? =
        if (acct == null) null else _threadTtls[thread]?.takeIf { it > 0 }

    /** Set or clear this thread's timer. Anything non-positive is "off" and
     *  removes the entry. A no-op before an account is bound: there is no
     *  identity to file it under, and the flat namespace is the one place it
     *  must never land. */
    fun setThreadTtl(thread: String, seconds: Int?) {
        if (acct == null) return
        val next = if (seconds != null && seconds > 0) _threadTtls + (thread to seconds)
                   else _threadTtls - thread
        if (next == _threadTtls) return
        _threadTtls = next
        prefs.edit().putStringSet(pk(K_THREAD_TTL), next.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    /** Per-account key for the currently-bound account. */
    private fun pk(key: String) = "$acct.$key"

    // ── thread-key helpers ───────────────────────────────────────────
    fun peerThread(uin: Int) = "peer:$uin"
    fun groupThread(id: Int) = "group:$id"

    /** The map key for a person: the bare uin on this island, `uin@host` on
     *  another. See the note on [_aliases]. */
    fun aliasKey(uin: Int, host: String? = null): String =
        if (host.isNullOrBlank()) uin.toString() else "$uin@${host.lowercase()}"

    /** My name for them, or null when I never set one. */
    fun aliasFor(uin: Int, host: String? = null): String? = _aliases.value[aliasKey(uin, host)]

    /** Set (or, with blank, clear) my own name for them. */
    fun setAlias(uin: Int, name: String?, host: String? = null) {
        if (acct == null) return
        val trimmed = name?.trim()?.take(48)?.takeIf { it.isNotEmpty() }
        val next = _aliases.value.toMutableMap()
        val k = aliasKey(uin, host)
        if (trimmed == null) next.remove(k) else next[k] = trimmed
        _aliases.value = next
        prefs.edit().putStringSet(pk(K_ALIAS), next.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    fun isFavorite(thread: String) = thread in _favorites.value
    fun toggleFavorite(thread: String) = toggle(_favorites, K_FAV, thread)

    fun isMuted(thread: String) = thread in _muted.value
    fun toggleMute(thread: String) = toggle(_muted, K_MUTE, thread)

    fun isMentionsOnly(thread: String) = thread in _mentionsOnly.value

    /** Group notify mode (#11): ALL rings always, MENTIONS rings only on an
     *  @mention, NONE is fully silent. The two sets stay mutually exclusive. */
    /** Which way a message row is dragged to quote it (#526). */
    enum class SwipeReplySide { LEFT, RIGHT }

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

    /** Headless mute check: read [thread]'s NONE-mute flag straight from prefs for
     *  [accountId], WITHOUT binding the global per-account state. Safe to call from
     *  the UnifiedPush service (no Activity, no active account bound). Returns false
     *  if prefs aren't initialised yet (degrade to showing the notification). */
    fun isMutedFor(accountId: String, thread: String): Boolean =
        ::prefs.isInitialized && prefs.getStringSet("$accountId.$K_MUTE", emptySet())!!.contains(thread)

    /** Headless "mentions only" check (companion to [isMutedFor]): read [thread]'s
     *  mentions-only notify flag straight from prefs for [accountId], no account
     *  binding. The UnifiedPush service uses it to stay quiet for a mentions-only
     *  group's ordinary messages — Android can't decrypt the push to confirm an
     *  @mention (unlike the iOS NSE), so mentions-only behaves as quiet here. */
    fun isMentionsOnlyFor(accountId: String, thread: String): Boolean =
        ::prefs.isInitialized && prefs.getStringSet("$accountId.$K_MENTIONS", emptySet())!!.contains(thread)

    fun isArchived(thread: String) = thread in _archived.value
    fun toggleArchive(thread: String) = toggle(_archived, K_ARCH, thread)

    fun isLocked(thread: String) = thread in _locked.value
    fun toggleLocked(thread: String) = toggle(_locked, K_LOCKED, thread)

    fun isRemoved(uin: Int) = uin in _removed.value

    /** Un-hide a thread the user had deleted. Nothing used to do this, so a
     *  deleted conversation stayed invisible forever: the peer could write
     *  again, the push would fire, the message would file correctly, and the
     *  chat simply never came back on the home screen (user report: "I deleted
     *  the request, wrote again from the other account, the push arrives but
     *  nothing appears"). Somebody writing to you is the definition of a live
     *  conversation, so incoming mail resurrects the thread. */
    fun clearRemoved(uin: Int) {
        if (acct == null || uin !in _removed.value) return
        _removed.value = _removed.value - uin
        prefs.edit().putStringSet(pk(K_REMOVED), _removed.value.map(Int::toString).toSet()).apply()
    }

    fun addRemoved(uin: Int) {
        if (acct == null || uin in _removed.value) return
        _removed.value = _removed.value + uin
        prefs.edit().putStringSet(pk(K_REMOVED), _removed.value.map(Int::toString).toSet()).apply()
    }

    fun isGone(uin: Int) = uin in _gonePeers.value
    fun setGone(uin: Int, on: Boolean) {
        if (acct == null || on == (uin in _gonePeers.value)) return
        _gonePeers.value = if (on) _gonePeers.value + uin else _gonePeers.value - uin
        prefs.edit().putStringSet(pk(K_GONE), _gonePeers.value.map(Int::toString).toSet()).apply()
    }

    fun isBlocked(uin: Int) = uin in _blocked.value
    fun setBlocked(uin: Int, on: Boolean) {
        if (acct == null || on == (uin in _blocked.value)) return
        _blocked.value = if (on) _blocked.value + uin else _blocked.value - uin
        prefs.edit().putStringSet(pk(K_BLOCKED), _blocked.value.map(Int::toString).toSet()).apply()
    }

    fun strangerQuarantineEnabled() = _strangerQuarantine.value
    fun setStrangerQuarantine(on: Boolean) {
        if (acct == null) return
        _strangerQuarantine.value = on
        prefs.edit().putBoolean(pk(K_STRANGER_Q), on).apply()
    }

    fun isAllowedStranger(uin: Int) = uin in _allowedStrangers.value
    fun allowStranger(uin: Int) {
        if (acct == null || uin in _allowedStrangers.value) return
        _allowedStrangers.value = _allowedStrangers.value + uin
        prefs.edit().putStringSet(pk(K_STRANGER_ALLOW), _allowedStrangers.value.map(Int::toString).toSet()).apply()
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
    /** How many emoticons the composer panel keeps. */
    const val PANEL_EMOJI_CAP = 40

    /** How many quick reactions the long-press row keeps.
     *
     *  ⚠⚠ THE PICKER MUST NOT ENFORCE ITS OWN NUMBER. This used to be 6 here
     *  while the picker let 40 through, so the store silently threw away most
     *  of what the user had just chosen. Both caps live here, where the
     *  truncation actually happens, and EmojiPicker reads them. */
    const val REACTION_EMOJI_CAP = 40

    /** Set the composer-panel emoticon set (asset names, pick order; capped at
     *  [PANEL_EMOJI_CAP], de-duplicated). Persisted as a comma-joined string. */
    fun setPanelEmojis(list: List<String>) {
        val capped = list.distinct().take(PANEL_EMOJI_CAP)
        _panelEmojis.value = capped
        if (::prefs.isInitialized) prefs.edit().putString(K_PANEL_EMOJI, capped.joinToString(",")).apply()
    }

    /** Set the quick-reaction set (asset names, pick order; capped at
     *  [REACTION_EMOJI_CAP], de-duplicated). Persisted as a comma-joined
     *  string. */
    fun setReactionEmojis(list: List<String>) {
        val capped = list.distinct().take(REACTION_EMOJI_CAP)
        _reactionEmojis.value = capped
        if (::prefs.isInitialized) prefs.edit().putString(K_REACTION_EMOJI, capped.joinToString(",")).apply()
    }

    fun animateAvatarsOn() = _animateAvatars.value
    /** Economy mode: one switch for a slow or old phone (founder's RCQ Lite
     *  decision, 30.08 - a mode inside this app rather than a second client).
     *
     *  It does not add a new rendering path. It TURNS OFF the heavy ones that
     *  already have switches - animated avatars and both wallpapers - so the
     *  person with a five-year-old phone does not have to find three settings
     *  and know which of them cost battery.
     *
     *  ⚠ It also PUTS THEM BACK. The first version wrote the lowered values and
     *  remembered nothing, so a wallpaper somebody had chosen was gone for good
     *  the moment they tried the mode out (#845): a switch that cannot be
     *  undone is a switch nobody dares to touch. What was lowered is written
     *  down at the moment of lowering and restored when the mode goes off.
     *
     *  ⚠ Restored only where the setting is STILL where the mode left it. A
     *  person who picks a wallpaper while the mode is on has said something
     *  newer than what we saved, and turning the mode off must not talk over
     *  them.
     */
    fun setEconomyMode(on: Boolean) {
        val was = economyMode()
        prefs.edit().putBoolean(K_ECONOMY, on).apply()
        if (on) {
            if (!was) {
                prefs.edit()
                    .putBoolean(K_ECO_HAD_ANIM, animateAvatarsOn())
                    .putString(K_ECO_HAD_CHAT_BG, _chatBackground.value)
                    .putString(K_ECO_HAD_HOME_BG, _homeBackground.value)
                    .apply()
            }
            setAnimateAvatars(false)
            setChatBackground("")
            setHomeBackground("")
        } else if (was) {
            if (!animateAvatarsOn() && prefs.getBoolean(K_ECO_HAD_ANIM, true)) setAnimateAvatars(true)
            val chat = prefs.getString(K_ECO_HAD_CHAT_BG, "").orEmpty()
            if (_chatBackground.value.isEmpty() && chat.isNotEmpty()) setChatBackground(chat)
            val home = prefs.getString(K_ECO_HAD_HOME_BG, "").orEmpty()
            if (_homeBackground.value.isEmpty() && home.isNotEmpty()) setHomeBackground(home)
            prefs.edit()
                .remove(K_ECO_HAD_ANIM).remove(K_ECO_HAD_CHAT_BG).remove(K_ECO_HAD_HOME_BG)
                .apply()
        }
    }

    fun economyMode(): Boolean = prefs.getBoolean(K_ECONOMY, false)

    fun setAnimateAvatars(on: Boolean) {
        _animateAvatars.value = on
        if (::prefs.isInitialized) prefs.edit().putBoolean(K_ANIM_AVATARS, on).apply()
    }

    fun setSwipeReplySide(side: SwipeReplySide) {
        _swipeReplySide.value = side
        if (::prefs.isInitialized) prefs.edit().putString(K_SWIPE_SIDE, side.name).apply()
    }

    // ── sound toggles ────────────────────────────────────────────────
    fun soundMasterOn() = _soundMaster.value
    fun soundMessagesOn() = _soundMessages.value
    fun presenceSoundMode() = _presenceSound.value
    fun setSoundMaster(on: Boolean) { _soundMaster.value = on; prefs.edit().putBoolean(K_SND_MASTER, on).apply() }
    fun setSoundMessages(on: Boolean) { _soundMessages.value = on; prefs.edit().putBoolean(K_SND_MSG, on).apply() }
    fun setPresenceSoundMode(mode: PresenceSoundMode) {
        _presenceSound.value = mode
        // The legacy boolean is kept in step so a downgrade (or any code path
        // still reading it) sees "off" as off rather than as the default on.
        prefs.edit()
            .putString(K_SND_PRES_MODE, mode.name)
            .putBoolean(K_SND_PRES, mode != PresenceSoundMode.OFF)
            .apply()
    }
    fun soundVolumeLevel() = _soundVolume.value
    fun setSoundVolume(v: Float) {
        val clamped = v.coerceIn(0f, 1f)
        _soundVolume.value = clamped
        prefs.edit().putFloat(K_SND_VOL, clamped).apply()
    }

    fun screenSecurityOn() = _screenSecurity.value
    fun setScreenSecurity(on: Boolean) { _screenSecurity.value = on; prefs.edit().putBoolean(K_SCREEN_SEC, on).apply() }

    /** Permanently dismiss the push-distributor nudge (see [pushNudgeDismissed]). */
    fun dismissPushNudge() {
        if (_pushNudgeDismissed.value) return
        _pushNudgeDismissed.value = true
        if (::prefs.isInitialized) prefs.edit().putBoolean(K_PUSH_NUDGE_DISMISSED, true).apply()
    }

    // ── home section fold flags (per-account, device-local) ───────────
    fun isSectionFlag(id: String) = id in _sectionFlags.value
    fun setSectionFlag(id: String, on: Boolean) {
        /// No account bound means no namespace to file this under, and the flat
        /// one is the single place it must never land.
        if (acct == null) return
        val next = if (on) _sectionFlags.value + id else _sectionFlags.value - id
        if (next == _sectionFlags.value) return
        _sectionFlags.value = next
        prefs.edit().putStringSet(pk(K_SECTION_FLAGS), next.toSet()).apply()
    }

    /** Drop a deleted section's fold flag. Without this the entry outlives the
     *  section that owned it and would decide the fold state of a later section
     *  that drew the same id. */
    fun forgetSectionFlag(id: String) {
        setSectionFlag("sec:u:$id", false)
    }

    // ── presence stay-online window (removed feature, cleanup only) ──
    /** Drop the leftover "stay visible after you leave" anchor from disk.
     *
     *  The feature itself is gone (it never worked: the client sent
     *  presence_persistent without presence_ttl_minutes, and the island's own
     *  window was anchored on a last_seen the heartbeat rewrites every 25s).
     *  Nothing reads the anchor any more, so this is not a state change, it is
     *  housekeeping: a phone that had the switch on is carrying a stored
     *  timestamp about its owner's habits, and it should not keep it. Called
     *  once per settings open; safe to call when there is nothing to remove.
     *  Delete this, the key below and the call in SettingsRoot together once
     *  enough time has passed that no installed build still has the anchor. */
    fun clearPresenceWindow() {
        if (acct != null) prefs.edit().remove(pk(K_PRES_WIN)).apply()
    }

    /** Whether this account has already told its island that the removed
     *  "stay visible after you leave" flag is OFF.
     *
     *  ⚠⚠ A REMOVED FEATURE HAS TO ANSWER false, NOT VANISH. Dropping the
     *  switch from Privacy took away the only way to turn the flag off, but a
     *  phone that had it ON left `presence_persistent = true` on its island,
     *  and an island that has not taken the 23.08 update still honours it with
     *  a NULL ttl it reads as "forever". That account goes on being reported as
     *  recently-online after it closes the app, with nothing anywhere in the
     *  build able to stop it. So the retirement is SAID once, per account, and
     *  the fact that it was said is remembered here rather than re-sent on
     *  every settings open. Against the updated backend, which pins both fields
     *  to false, the call is a no-op. Delete this together with the rest of the
     *  block once no island in the wild still holds the flag. */
    fun presenceRetired(): Boolean =
        acct != null && ::prefs.isInitialized && prefs.getBoolean(pk(K_PRES_RETIRED), false)

    /** Record that the island acknowledged the retirement (see [presenceRetired]).
     *  Only ever called after a SUCCESSFUL profile update, so a phone that was
     *  offline when Settings opened tries again on the next visit. */
    fun markPresenceRetired() {
        if (acct != null && ::prefs.isInitialized) prefs.edit().putBoolean(pk(K_PRES_RETIRED), true).apply()
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

    // ── room state keys (stage 6 phase 2) ────────────────────────────
    // Per-account gid -> "ver:keyB64". The key that opens a sealed room
    // identity blob; monotonic with one exception mirrored from the web
    // (design doc): a roster-gated key of EQUAL version may replace the
    // stored bytes, which is how a wedged receiver repairs.

    private val _gskeys = MutableStateFlow<Map<Int, Pair<Long, String>>>(emptyMap())
    val gskeys: StateFlow<Map<Int, Pair<Long, String>>> = _gskeys.asStateFlow()

    fun roomKey(gid: Int): Pair<Long, String>? = _gskeys.value[gid]

    fun putRoomKey(gid: Int, ver: Long, keyB64: String, replaceEqual: Boolean = false): Boolean {
        if (acct == null) return false
        val cur = _gskeys.value[gid]
        if (cur != null && (cur.first > ver || (cur.first == ver && (!replaceEqual || cur.second == keyB64)))) return false
        _gskeys.value = _gskeys.value + (gid to (ver to keyB64))
        prefs.edit().putStringSet(
            pk(K_GSKEYS),
            _gskeys.value.map { (g, e) -> "$g=${e.first}:${e.second}" }.toSet(),
        ).apply()
        return true
    }

    // ── profile keys ─────────────────────────────────────────────────────
    // The key that opens a person's avatar. The island used to hold it in
    // users.avatar_media_key, next to the uin and the nickname, so a seized
    // island opened every face; now the owner seals it to their contacts and
    // this is where it lands. docs/profile-key-design.md.

    private val _pkeys = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** The key for [peer]'s picture, or null when we were never given it.
     *  ⚠ Null must render exactly like "no picture at all", or the tile
     *  becomes an oracle for "am I entitled to see this". */
    fun profileKey(peer: Int): String? = _pkeys.value[peer]

    fun putProfileKey(peer: Int, keyB64: String): Boolean {
        if (acct == null || keyB64.isBlank() || _pkeys.value[peer] == keyB64) return false
        _pkeys.value = _pkeys.value + (peer to keyB64)
        prefs.edit().putStringSet(
            pk(K_PKEYS),
            _pkeys.value.map { (u, k) -> "$u=$k" }.toSet(),
        ).apply()
        return true
    }

    /** My own profile key, minted once and reused: changing the picture must
     *  NOT change the key, or every change costs a fan-out and leaves contacts
     *  looking at a blank tile until it lands. */
    fun myProfileKey(): String? = prefs.getString(pk(K_MY_PKEY), null)

    /** [forAccount] pins the write to the account it was computed for, the
     *  same way [setVaultSlotVersion] does. ⚠⚠ Minting this key is three
     *  network round trips against the vault, and this store is a singleton
     *  the session re-points on an account switch: without the pin, a switch
     *  mid-mint writes account A's profile key into account B's slot, and B
     *  then hands A's key to B's whole roster. */
    fun setMyProfileKey(keyB64: String, forAccount: String? = null) {
        if (forAccount != null && forAccount != acct) return
        if (acct == null || keyB64.isBlank()) return
        prefs.edit().putString(pk(K_MY_PKEY), keyB64).apply()
    }

    private fun loadProfileKeys() {
        _pkeys.value = (prefs.getStringSet(pk(K_PKEYS), emptySet()) ?: emptySet()).mapNotNull { row ->
            val eq = row.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val uin = row.substring(0, eq).toIntOrNull() ?: return@mapNotNull null
            uin to row.substring(eq + 1)
        }.toMap()
    }

    private fun loadRoomKeys() {
        _gskeys.value = (prefs.getStringSet(pk(K_GSKEYS), emptySet()) ?: emptySet()).mapNotNull { row ->
            val eq = row.indexOf('=')
            val colon = row.indexOf(':', eq + 1)
            if (eq <= 0 || colon <= eq) return@mapNotNull null
            val gid = row.substring(0, eq).toIntOrNull() ?: return@mapNotNull null
            val ver = row.substring(eq + 1, colon).toLongOrNull() ?: return@mapNotNull null
            gid to (ver to row.substring(colon + 1))
        }.toMap()
    }

    fun clearUnread(thread: String) {
        if (_unread.value[thread] == null) return
        _unread.value = _unread.value - thread
        persistUnread()
    }

    /** Take [by] off a thread's badge, for rows that left the thread without
     *  ever being read.
     *
     *  ⚠ The counter is STORED, not derived from the rows, so anything that
     *  removes a row has to say so here or the badge outlives what it was
     *  counting. A disappearing message that lapses in the background is
     *  exactly that: the row is swept, the badge keeps its "1", and the chat
     *  list offers a conversation with nothing new in it until the user opens
     *  it and [clearUnread] fires. Which rows may be subtracted is the
     *  caller's problem and a real one (expiry takes the OLDEST rows, which
     *  are the ones already read). See `Session.shedLapsedUnread`.
     *
     *  A thread that reaches zero loses its entry entirely, the same shape
     *  [clearUnread] leaves behind. */
    fun decUnread(thread: String, by: Int) {
        if (acct == null || by <= 0) return
        val cur = _unread.value[thread] ?: return
        val next = cur - by
        _unread.value = if (next > 0) _unread.value + (thread to next) else _unread.value - thread
        persistUnread()
    }

    /** Encode the map as a CSV "thread=count" StringSet for SharedPreferences. */
    private fun persistUnread() {
        if (acct == null) return
        prefs.edit().putStringSet(pk(K_UNREAD), _unread.value.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    /** Decode a "name=count" StringSet. Shared by the unread counters and the
     *  reaction tally: same shape, same split-on-the-LAST-'=' rule (a thread key
     *  cannot hold one, but the decoder must not depend on that). */
    private fun loadCounts(key: String): Map<String, Int> =
        prefs.getStringSet(key, emptySet())!!.mapNotNull { entry ->
            val i = entry.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            val k = entry.substring(0, i)
            val v = entry.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
            k to v
        }.toMap()

    // ── mention "seen" cut-off (per group) ───────────────────────────
    fun mentionSeenAt(groupId: Int): Long = _mentionSeenAt.value[groupId] ?: 0L

    /** Advance the per-group mention-seen cut-off (monotonic — never goes back).
     *  Called on chat exit with the newest @mention timestamp loaded. */
    fun markMentionSeen(groupId: Int, upToTimestampMs: Long) {
        if (acct == null) return
        if (upToTimestampMs <= (_mentionSeenAt.value[groupId] ?: 0L)) return
        _mentionSeenAt.value = _mentionSeenAt.value + (groupId to upToTimestampMs)
        prefs.edit().putStringSet(pk(K_MENTION_SEEN), _mentionSeenAt.value.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    private fun loadMentionSeen(key: String): Map<Int, Long> =
        prefs.getStringSet(key, emptySet())!!.mapNotNull { entry ->
            val i = entry.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            val g = entry.substring(0, i).toIntOrNull() ?: return@mapNotNull null
            val v = entry.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
            g to v
        }.toMap()

    // ── chat scroll position (per thread) ────────────────────────────
    /** The saved reading position for [thread], as (rows-from-end, pixel
     *  offset), or null when the thread was last left at the bottom. */
    fun chatPosition(thread: String): Pair<Int, Int>? = _chatPos[thread]

    fun saveChatPosition(thread: String, rowsFromEnd: Int, offsetPx: Int) {
        if (acct == null) return
        val entry = rowsFromEnd to offsetPx
        if (_chatPos[thread] == entry) return
        _chatPos = _chatPos + (thread to entry)
        persistChatPos()
    }

    /** The user read to the bottom: this chat opens at the newest message again. */
    fun clearChatPosition(thread: String) {
        if (acct == null || _chatPos[thread] == null) return
        _chatPos = _chatPos - thread
        persistChatPos()
    }

    private fun persistChatPos() =
        prefs.edit().putStringSet(pk(K_CHAT_POS), _chatPos.map { "${it.key}=${it.value.first}:${it.value.second}" }.toSet()).apply()

    private fun loadChatPos(key: String): Map<String, Pair<Int, Int>> =
        prefs.getStringSet(key, emptySet())!!.mapNotNull { entry ->
            val i = entry.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            val v = entry.substring(i + 1).split(':')
            val dist = v.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val off = v.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            entry.substring(0, i) to (dist to off)
        }.toMap()

    // ── reaction usage tally (per account) ───────────────────────────
    // Founder item 21: the reaction bar should lead with what this person
    // actually uses instead of a fixed order they have to read past every time.
    // Entirely device-local: the island is never told which face anybody
    // likes, and it does not need to be, because the bar is drawn here.

    /** How many times [asset] has been used from this account. 0 when never. */
    fun reactionUseCount(asset: String): Int = _reactionUses[asset] ?: 0

    /** The whole tally, for a picker that wants to show it. Already in memory. */
    fun reactionUses(): Map<String, Int> = _reactionUses

    /** The [limit] most-used assets, most-used first; may be shorter, and is
     *  empty on a fresh account. NOT filtered against the current emoticon pack
     *  or the user's chosen set. The caller knows which list it is drawing, and
     *  an asset that has since left the pack must not silently take a slot in
     *  it. Intersect before use, or use [byMostUsed]. */
    fun topReactions(limit: Int): List<String> =
        if (limit <= 0) emptyList()
        else _reactionUses.entries.sortedByDescending { it.value }.take(limit).map { it.key }

    /** [assets] reordered most-used first.
     *
     *  ⚠ STABLE on purpose. Assets with the same tally (on a fresh account, all
     *  of them) keep exactly the order they arrived in, so the bar opens as the
     *  user's own chosen set and only ever re-sorts because they really did use
     *  something. An unstable sort would shuffle six identical zeroes into a
     *  different arrangement on each open, which reads as the app being broken.
     *  `sortedByDescending` is TimSort underneath, which is stable. */
    fun byMostUsed(assets: List<String>): List<String> =
        assets.sortedByDescending { _reactionUses[it] ?: 0 }

    /** Record one use of [asset]. Cheap, fire-and-forget, safe to call from the
     *  tap handler. A no-op before an account is bound (the trap this whole
     *  block is guarded for) and for a name the encoding cannot round-trip. */
    fun bumpReactionUse(asset: String) {
        if (acct == null || asset.isBlank() || '=' in asset) return
        val bumped = (reactionUseCount(asset) + 1).coerceAtMost(REACTION_USE_MAX_COUNT)
        val next = _reactionUses.toMutableMap()
        next[asset] = bumped
        if (next.size > REACTION_USE_MAX_KEYS) {
            // Evict the LEAST-used, never the oldest. The cap is only ever
            // reached by someone who has tried a lot of faces once each, and the
            // one they send every day must not be the one that falls out.
            //
            // ⚠ AND NEVER THE ONE WE JUST RECORDED. A brand-new asset enters
            // with a count of 1, which is the minimum, and `sortedByDescending`
            // is stable while the copy above puts the new key LAST in iteration
            // order, so "keep the top N" always threw away the very tap that
            // caused the eviction. The tally froze at the cap for good: nothing
            // new could ever reach 1 on disk, and the "most used first" order
            // stopped learning while 64 slots stayed occupied by faces the user
            // may have dropped from their set long ago. Take the new key out of
            // the running, keep the best N-1 of the rest, put it back.
            next.remove(asset)
            val keep = next.entries.sortedByDescending { it.value }.take(REACTION_USE_MAX_KEYS - 1)
            next.clear()
            keep.forEach { next[it.key] = it.value }
            next[asset] = bumped
        }
        _reactionUses = next
        prefs.edit().putStringSet(pk(K_REACTION_USES), next.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    /** Forget the tally (the reaction bar goes back to the user's own order).
     *  Offered because a usage history is a history: someone handing their phone
     *  over should be able to clear it without clearing everything else. */
    fun clearReactionUses() {
        if (acct == null || _reactionUses.isEmpty()) return
        _reactionUses = emptyMap()
        prefs.edit().remove(pk(K_REACTION_USES)).apply()
    }

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

    /** Remove [thread] from [flow] (copied-Set + persist), no-op if absent.
     *  Persisted with a SYNCHRONOUS commit() (not apply): clearing the @-mention
     *  / reaction inbox happens once, on chat open, so an async apply() that
     *  hadn't flushed when the app was killed right after reading left the
     *  indicator to resurface on next launch (report: "mention reappears after
     *  relaunch"). The unread counter never showed this because it's re-cleared
     *  on every message render; these inboxes get a single clear, so it must be
     *  durable. The set is tiny, so the main-thread write is negligible. */
    private fun removeFrom(flow: MutableStateFlow<Set<String>>, key: String, thread: String) {
        if (acct == null || thread !in flow.value) return
        flow.value = flow.value - thread
        prefs.edit().putStringSet(pk(key), flow.value.toSet()).commit()
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

    /** Headless read of a SPECIFIC account's roster cache, for the push
     *  receiver: a wake names the account it is for ([to_uin]), which on a
     *  multi-account phone is often not the bound one, and a background start
     *  has bound nothing at all. Same shape as [isMutedFor]. */
    fun cachedContactsJsonFor(accountId: String): String? =
        if (::prefs.isInitialized) prefs.getString("$accountId.$K_CONTACTS_CACHE", null) else null

    /** The highest version of the vault's `contacts` slot this install has
     *  seen, per account: the floor below which an island's answer is a
     *  rollback rather than data (see ContactsVault). 0 = never read. */
    fun vaultContactsVersion(): Long =
        if (::prefs.isInitialized && acct != null) prefs.getLong(pk(K_VAULT_CONTACTS_VERSION), 0L) else 0L

    fun setVaultContactsVersion(version: Long) {
        if (::prefs.isInitialized && acct != null) prefs.edit().putLong(pk(K_VAULT_CONTACTS_VERSION), version).apply()
    }

    // ── the chat-list sections slot (founder item 1 of 23.08) ────────────

    /** Replace the cached sections tree from a LOCAL edit, which by definition
     *  belongs to whatever these stores are bound to right now (the tap and the
     *  write are the same turn of the main thread). Anything that comes back
     *  from the island must use [updateSectionsTree] instead. */
    @Synchronized
    fun setSectionsTree(tree: com.google.gson.JsonObject) {
        if (acct == null) return
        _sections.value = tree
        if (::prefs.isInitialized) prefs.edit().putString(pk(K_SECTIONS), tree.toString()).apply()
    }

    /**
     * Read-modify-write the cached tree, but ONLY while these stores are still
     * bound to [forAccount]. Returns the stored tree, or null when the scope
     * moved under the caller and nothing was written.
     *
     * ⚠⚠ THE SCOPE IS THE POINT, and `acct != null` is not it. A vault read is
     * a network round trip, and the account these stores answer for can move
     * while it is in the air, in two ways that both end badly:
     *
     * - the account switcher ([Session.rebindTo]) points every per-account
     *   store at the next account, and Session's scope is never cancelled, so
     *   the in-flight coroutine comes back holding account A's tree and would
     *   file it under B. The union merge never unlearns that: B's next push
     *   seals A's section names, and A's filed peer uins, into B's slot.
     * - the duress PIN binds them to [DecoyStore.STORE_ID]. That one is worse.
     *   A MIGRATED decoy leaves `store` (and therefore uin, host and token) on
     *   the REAL account on purpose, so every "is this still us" test the
     *   caller can run says yes, and the real account's section NAMES land on
     *   the flow the duress home screen is drawing, PIN gate and all (gating is
     *   off inside a decoy), and on disk in the decoy's namespace.
     *
     * The caller cannot close either window by testing first and writing after:
     * the rebind runs on the main thread while this runs on IO. The test has to
     * be inside the write, which is what this is, and why it and [bindAccount]
     * take the same monitor.
     */
    @Synchronized
    fun updateSectionsTree(
        forAccount: String,
        transform: (com.google.gson.JsonObject) -> com.google.gson.JsonObject,
    ): com.google.gson.JsonObject? {
        if (acct == null || acct != forAccount) return null
        val next = transform(_sections.value)
        _sections.value = next
        if (::prefs.isInitialized) prefs.edit().putString(pk(K_SECTIONS), next.toString()).apply()
        return next
    }

    /** The cached tree while these stores are still bound to [forAccount], null
     *  otherwise. The read half of [updateSectionsTree]: a tree that is about to
     *  be SEALED for [forAccount]'s slot must not be taken from another
     *  account's cache, or from the decoy's. */
    @Synchronized
    fun sectionsTreeFor(forAccount: String): com.google.gson.JsonObject? =
        if (acct != null && acct == forAccount) _sections.value else null

    private fun parseSections(raw: String?): com.google.gson.JsonObject {
        if (raw.isNullOrEmpty()) return Sections.emptyTree()
        val parsed = runCatching { com.google.gson.JsonParser.parseString(raw) }.getOrNull()
        val o = parsed as? com.google.gson.JsonObject ?: return Sections.emptyTree()
        // A cache this build cannot read is a cache from a newer build. It is
        // NOT overwritten (that is the whole point of the `v > 1` rule): it is
        // simply not rendered, and the tree stays on disk for the build that
        // wrote it.
        val v = runCatching { o.get("v")?.asInt }.getOrNull()
        return if (v == 1 && o.get("s")?.isJsonArray == true) o else Sections.emptyTree()
    }

    /** The highest version of the `sections` slot this install has seen, per
     *  account. ⚠⚠ Keyed by SLOT NAME, not by the account alone: a slot name is
     *  HKDF(identity_priv, ...), so `POST /auth/reissue` does not move the
     *  account's slots to a new version, it moves them to NEW NAMES and empties
     *  the vault in the same transaction. A floor filed under the account then
     *  outlives the derivation it belonged to, the fresh names answer at
     *  version 1, `1 < 12` reads as a rollback, and because the floor is
     *  persisted every later session repeats it, and the account's vault is dead
     *  on this device for good. Keyed by name, a new derivation starts at 0,
     *  which is what it actually is. */
    fun vaultSlotVersion(slot: String): Long =
        if (::prefs.isInitialized && acct != null) prefs.getLong(pk("$K_VAULT_SLOT_VERSION.$slot"), 0L) else 0L

    /** [forAccount], when given, is the account the read this floor came from
     *  was made for; the write is dropped when the stores have moved on since.
     *  Same reason as [updateSectionsTree]. */
    @Synchronized
    fun setVaultSlotVersion(slot: String, version: Long, forAccount: String? = null) {
        if (forAccount != null && forAccount != acct) return
        if (::prefs.isInitialized && acct != null) {
            prefs.edit().putLong(pk("$K_VAULT_SLOT_VERSION.$slot"), version).apply()
        }
    }

    /** Forget a slot's floor. Only on `vault_reset`: the name belongs to a
     *  derivation that will never be read again, and a stale floor is what
     *  locks a fresh derivation out of its own slot for good. */
    fun forgetVaultSlotVersion(slot: String) {
        if (::prefs.isInitialized && acct != null) {
            prefs.edit().remove(pk("$K_VAULT_SLOT_VERSION.$slot")).apply()
        }
    }

    /** "This device has a sections edit the island has not confirmed."
     *  Persisted, because the case that matters most outlives the process: a
     *  section made on a train, a write that failed, the app killed, and on the
     *  next cold start the island's VERSION has not moved either, so the
     *  reconnect sweep would skip the slot entirely. */
    fun sectionsPushPending(): Boolean =
        ::prefs.isInitialized && acct != null && prefs.getBoolean(pk(K_SECTIONS_PENDING), false)

    /** [forAccount]: see [setVaultSlotVersion]. Clearing this flag on behalf of
     *  an account the stores no longer answer for would drop the other
     *  account's outstanding write on the floor. */
    @Synchronized
    fun setSectionsPushPending(on: Boolean, forAccount: String? = null) {
        if (forAccount != null && forAccount != acct) return
        if (::prefs.isInitialized && acct != null) prefs.edit().putBoolean(pk(K_SECTIONS_PENDING), on).apply()
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
        listOf(K_FAV, K_MUTE, K_MENTIONS, K_ARCH, K_LOCKED, K_REMOVED, K_BLOCKED, K_STRANGER_Q, K_STRANGER_ALLOW, K_GONE, K_UNREAD, K_REACT_INBOX, K_REACTED_MSGS, K_REACTION_USES, K_MENTION_INBOX, K_MENTION_SEEN, K_CHAT_POS, K_THREAD_TTL, K_PRIVACY_CACHE, K_CONTACTS_CACHE, K_GROUPS_CACHE, K_VAULT_CONTACTS_VERSION, K_SECTIONS, K_SECTIONS_PENDING, K_SECTION_FLAGS,
            // ⚠⚠ KEY MATERIAL, and it was missing from this list. K_PKEYS is
            // the profile key of every contact, stored BY NUMBER, so leaving it
            // behind keeps a plaintext roster of the burned identity plus the
            // keys that open their faces, in rcq_local.xml, after a burn and
            // after a duress session is taken apart. K_GSKEYS and K_MY_PKEY are
            // the same class. This is the list 36181d1 filled in for two other
            // stores in this release and did not notice here.
            K_PKEYS, K_GSKEYS, K_MY_PKEY).forEach { e.remove("$accountId.$it") }
        // The vault floors are keyed by SLOT NAME (see [vaultSlotVersion]), so
        // they cannot be listed by hand: sweep the prefix instead. Leaving one
        // behind would lock a later account on this device out of a slot whose
        // name it re-derived.
        prefs.all.keys.filter { it.startsWith("$accountId.$K_VAULT_SLOT_VERSION.") }.forEach { e.remove(it) }
        e.apply()
    }

    private const val K_FAV = "favorites"
    private const val K_MUTE = "muted"
    private const val K_MENTIONS = "mentions_only"
    private const val K_ARCH = "archived"
    private const val K_LOCKED = "locked"
    private const val K_REMOVED = "removed"
    private const val K_BLOCKED = "blocked"
    private const val K_STRANGER_Q = "strangers_quarantine"
    private const val K_STRANGER_ALLOW = "strangers_allowed"
    private const val K_GONE = "gone_peers"
    private const val K_THEME = "theme_mode"
    private const val K_CHAT_BG = "chat_background"
    private const val K_HOME_BG = "home_background"
    private const val K_PANEL_EMOJI = "panel_emojis"
    private const val K_REACTION_EMOJI = "reaction_emojis"
    private const val K_FONT_SCALE = "font_scale"
    private const val K_LOCK_GRACE = "lock_grace_seconds"
    private const val K_UNREAD = "unread"
    private const val K_GSKEYS = "gskeys"
    private const val K_ECONOMY = "economy_mode"

    /** What the economy mode lowered, so it can be put back (#845). Written
     *  when the mode goes on, read and cleared when it goes off. */
    private const val K_ECO_HAD_ANIM = "economy_had_anim_avatars"
    private const val K_ECO_HAD_CHAT_BG = "economy_had_chat_bg"
    private const val K_ECO_HAD_HOME_BG = "economy_had_home_bg"
    private const val K_PKEYS = "pkeys"      // peer uin -> their profile key
    private const val K_MY_PKEY = "mypkey"   // my own, handed to contacts
    private const val K_REACT_INBOX = "reaction_inbox"
    private const val K_REACTED_MSGS = "reacted_msg_ids"
    private const val K_REACTION_USES = "reaction_uses"
    /** Distinct assets the tally keeps. 40 is the picker's own ceiling for a
     *  reaction set, so this holds a full custom set plus a working margin of
     *  faces used from the full picker. */
    private const val REACTION_USE_MAX_KEYS = 64
    /** Where one tally stops counting. Only the ORDER matters, and nothing
     *  changes order past four digits. */
    private const val REACTION_USE_MAX_COUNT = 9_999
    private const val K_MENTION_INBOX = "mention_inbox"
    private const val K_MENTION_SEEN = "mention_seen_at"
    private const val K_CHAT_POS = "chat_scroll_pos"
    private const val K_THREAD_TTL = "thread_ttl"
    private const val K_ALIAS = "contact_aliases"
    private const val K_ANIM_AVATARS = "animate_avatars"
    private const val K_SWIPE_SIDE = "swipe_reply_side"
    private const val K_SND_MASTER = "sound_master"
    private const val K_SND_MSG = "sound_messages"
    private const val K_SND_PRES = "sound_presence"          // legacy boolean
    private const val K_SND_PRES_MODE = "sound_presence_mode" // ALL/FAVORITES/OFF
    private const val K_SND_VOL = "sound_volume"
    private const val K_SCREEN_SEC = "screen_security"
    private const val K_PUSH_NUDGE_DISMISSED = "push_nudge_dismissed"
    private const val K_PRES_WIN = "presence_window"
    private const val K_PRES_RETIRED = "presence_retired"
    private const val K_SECURE = "secure_threads"
    private const val K_SECURE_PEER = "secure_threads_peer"
    private const val K_SECTION_FLAGS = "section_flags"
    private const val K_PRIVACY_CACHE = "privacy_cache"
    private const val K_CONTACTS_CACHE = "contacts_cache"
    private const val K_GROUPS_CACHE = "groups_cache"
    private const val K_VAULT_CONTACTS_VERSION = "vault_contacts_version"
    private const val K_SECTIONS = "sections_tree"
    private const val K_SECTIONS_PENDING = "sections_push_pending"
    private const val K_VAULT_SLOT_VERSION = "vault_slot_version"
}
