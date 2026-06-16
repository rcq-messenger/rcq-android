package app.rcq.android.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/**
 * Owns the local account roster — the Android port of the iOS
 * `AccountManager`. The roster + active pointer persist as JSON in a plain
 * (non-secret) SharedPreferences; the secrets each account holds live in
 * [SecureStore] under that account's id prefix.
 *
 * Bootstrap ([init], once from MainActivity before [Session] is built):
 *   1. Load the persisted roster + active id.
 *   2. If empty, detect a pre-multi-account single-identity install and
 *      wrap it as Account[0], migrating every per-account storage layer
 *      (SecureStore / MessageDb / LocalStores / VisitStore) under the new
 *      id so an upgrading user sees zero change.
 *   3. Otherwise: empty roster (truly fresh install) — the first
 *      registration creates Account[0].
 *
 * AccountManager is the single source of truth for which account is
 * active; [Session] reads [activeId] when (re)binding its stores.
 */
object AccountManager {
    /** Hard cap on the local roster. Single-account is the 99% case; a
     *  handful covers the realistic multi-identity uses (main + work +
     *  persona). Beyond that we suspect abuse or test setups. Local-only;
     *  the server can't tell two UINs share a device anyway. */
    /** Hard ceiling the app supports regardless of server. */
    const val HARD_CAP = 5

    /** Operator-advertised cap from the active server's /server/info
     *  (max_accounts_per_device). Session writes it on caps refresh; @Volatile
     *  so the limit checks (UI thread) see writes from the refresh coroutine. */
    @Volatile var serverMaxAccounts: Int = HARD_CAP

    /** Effective per-device cap: the operator can LOWER it, never above HARD_CAP. */
    val MAX_ACCOUNTS: Int get() = maxOf(1, minOf(HARD_CAP, serverMaxAccounts))

    private lateinit var appCtx: Context
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    // Decoy mode (panic-PIN): the id of the decoy account when the decoy PIN
    // is active, else null. Runtime-only (never persisted — a cold start is
    // always locked, never pre-decoyed). While set, [visibleAccounts] shows
    // ONLY the decoy account so the real ones are hidden from the UI.
    private val _decoyMode = MutableStateFlow<String?>(null)
    val decoyMode: StateFlow<String?> = _decoyMode.asStateFlow()

    /** The roster as the UI should show it: the full list normally, or just
     *  the decoy account while decoy mode is active. Account switcher + manage
     *  screen read THIS, never [accounts], so the real accounts stay hidden. */
    val visibleAccounts: Flow<List<Account>> =
        combine(_accounts, _decoyMode) { accts, decoy ->
            if (decoy != null) accts.filter { it.id == decoy } else accts
        }

    fun enterDecoyMode(decoyAccountId: String) { _decoyMode.value = decoyAccountId }
    fun exitDecoyMode() { _decoyMode.value = null }
    val isDecoyMode: Boolean get() = _decoyMode.value != null

    /** Current filtered snapshot (decoy-aware) — the [collectAsState] initial
     *  value, so the UI never shows the full roster for even one frame while
     *  decoy mode is active. */
    fun visibleNow(): List<Account> {
        val d = _decoyMode.value
        return if (d != null) _accounts.value.filter { it.id == d } else _accounts.value
    }

    /** The currently active account, or null on a fresh install before any
     *  onboarding has run. */
    val active: Account? get() = _accounts.value.firstOrNull { it.id == _activeId.value }

    /** True once the roster is full; UI consults this before offering Add. */
    val isAtLimit: Boolean get() = _accounts.value.size >= MAX_ACCOUNTS

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appCtx = context.applicationContext
        prefs = appCtx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        load()
        if (_accounts.value.isEmpty()) migrateLegacyIfPresent()
    }

    private fun load() {
        prefs.getString(K_ACCOUNTS, null)?.let { json ->
            val type = object : TypeToken<List<Account>>() {}.type
            _accounts.value = runCatching { gson.fromJson<List<Account>>(json, type) }.getOrNull() ?: emptyList()
        }
        val stored = prefs.getString(K_ACTIVE, null)
        _activeId.value = stored?.takeIf { id -> _accounts.value.any { it.id == id } }
            ?: _accounts.value.firstOrNull()?.id
    }

    private fun save() {
        prefs.edit()
            .putString(K_ACCOUNTS, gson.toJson(_accounts.value))
            .putString(K_ACTIVE, _activeId.value)
            .apply()
    }

    /** Add a new (still-empty) account and make it active. The caller then
     *  registers an identity into its per-account stores. Returns null at
     *  the cap (defence in depth — UI gates this too). */
    fun add(serverHost: String?, displayLabel: String? = null): Account? {
        if (isAtLimit) return null
        val acct = Account(UUID.randomUUID().toString(), serverHost, System.currentTimeMillis(), displayLabel)
        _accounts.value = _accounts.value + acct
        _activeId.value = acct.id
        save()
        return acct
    }

    /** Make an existing account active. [Session] rebinds its stores to it. */
    fun setActive(id: String) {
        if (_accounts.value.none { it.id == id }) return
        _activeId.value = id
        save()
    }

    /** Drop an account from the roster. Caller is responsible for wiping
     *  its storage first. If it was active, falls back to the first
     *  remaining account (or null = fresh-install state). */
    fun remove(id: String) {
        _accounts.value = _accounts.value.filterNot { it.id == id }
        if (_activeId.value == id) _activeId.value = _accounts.value.firstOrNull()?.id
        save()
    }

    /** Repoint an account at a different backend (after switchServer). */
    fun setServerHost(id: String, host: String?) {
        _accounts.value = _accounts.value.map { if (it.id == id) it.copy(serverHost = host) else it }
        save()
    }

    // ── legacy migration ────────────────────────────────────────────────

    /** Detect a pre-multi-account install (a single unprefixed identity in
     *  SecureStore) and wrap it as Account[0], moving every per-account
     *  storage layer under the new id. Runs at most once: once an account
     *  exists, [load] populates the roster and init skips this. */
    private fun migrateLegacyIfPresent() {
        if (!SecureStore.hasLegacyIdentity(appCtx)) return
        val id = UUID.randomUUID().toString()
        val host = SecureStore.peekLegacyServerHost(appCtx)
        _accounts.value = listOf(Account(id, host, System.currentTimeMillis(), null))
        _activeId.value = id
        save()
        // Move the legacy unprefixed slots under the new account id BEFORE
        // anything reads them. Each helper is idempotent / a no-op when the
        // legacy slot is absent.
        SecureStore.migrateLegacyToAccount(appCtx, id)
        MessageDb.migrateLegacyToAccount(appCtx, id)
        LocalStores.migrateLegacyToAccount(id)
        VisitStore.migrateLegacyToAccount(id)
    }

    private const val FILE = "rcq_accounts"
    private const val K_ACCOUNTS = "accounts"
    private const val K_ACTIVE = "active_id"
}
