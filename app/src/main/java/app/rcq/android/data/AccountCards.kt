package app.rcq.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What each local account LOOKS like, cached so the account switcher can draw
 * a face and a name without asking any island anything.
 *
 * The Android side of the desktop's `contacts-cache.ts`. There, every account
 * persists its own roster snapshot under its own storage scope, the snapshot
 * carries `me` (nickname plus `avatar_media_id` / `avatar_media_key`), and the
 * switcher rows in Settings read each account's OWN snapshot with
 * `snapshotFor(uin)`. The comment in that file is the whole of the design:
 * "there is nothing to fetch". This mirrors it, one card per local account:
 *
 *   • Written by the ACTIVE account only, and only for itself. An account can
 *     only ever know its own name and picture, because a session is bound to
 *     one identity at a time. Every other row draws from the card that account
 *     wrote the last time it was active.
 *   • Persisted, so it survives a cold start. Without that the switcher would
 *     be blank on the one screen people see first after a launch, which is the
 *     state this exists to remove.
 *   • Scoped per account id, never global. Two identities on one phone are two
 *     people as far as this app is concerned, and a shared slot would put one
 *     person's face on the other's row for the moment before the first refresh.
 *   • Warmed into memory once per process ([warm]) and read from memory after
 *     that, so a list row costs a map lookup rather than a preferences read on
 *     every recomposition.
 *
 * Nothing here is secret: a nickname, a uin, an island host and the id/key of
 * an ALREADY PUBLIC profile picture (the same pair every contact of that
 * account holds). It therefore lives in plain preferences next to the roster,
 * not in [SecureStore]. The picture bytes are not copied: the id and key are
 * enough for the normal avatar path to find the blob in the shared media cache
 * on disk, exactly as the desktop hands `mediaId` / `mediaKey` plus that
 * account's own `apiBase` to its avatar component.
 */
object AccountCards {

    /** One account's face, as of the last time that account was active. Every
     *  field is nullable on purpose: an account added a minute ago and never
     *  opened has a uin and nothing else, and a card that admits it is empty is
     *  better than one that invents a placeholder. */
    data class Card(
        val nickname: String? = null,
        val uin: Int? = null,
        val avatarMediaId: String? = null,
        val avatarMediaKey: String? = null,
        /** The island this account lives on. The desktop passes the row's own
         *  `apiBase` to the avatar for the same reason: a row for an account
         *  living somewhere else must not ask the ACTIVE island for its
         *  picture, or it gets a 404 and falls back to the glyph. */
        val host: String? = null,
    ) {
        /** Whether there is a picture to draw at all. */
        val hasPicture: Boolean
            get() = !avatarMediaId.isNullOrEmpty() && !avatarMediaKey.isNullOrEmpty()
    }

    private val _cards = MutableStateFlow<Map<String, Card>>(emptyMap())

    /** Every known card, keyed by account id. A flow rather than a plain map so
     *  the switcher redraws the moment the active account changes its picture,
     *  without anyone having to remember to poke it. */
    val cards: StateFlow<Map<String, Card>> = _cards.asStateFlow()

    private var prefs: SharedPreferences? = null

    /** Load the cards from disk into memory. Idempotent and cheap (a handful of
     *  accounts, five at the very most), and safe to call from a composition:
     *  it is the same kind of preferences read [SecureStore.peekNickname]
     *  already does on the way to drawing the switcher.
     *
     *  Call it before the first row is drawn. A cold start that skipped it
     *  would show the empty map, which is the blank switcher this file exists
     *  to prevent. */
    fun warm(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val out = HashMap<String, Card>()
        for (id in p.getStringSet(K_IDS, emptySet()).orEmpty()) {
            out[id] = Card(
                nickname = p.getString("$id.$K_NICK", null),
                uin = if (p.contains("$id.$K_UIN")) p.getInt("$id.$K_UIN", 0) else null,
                avatarMediaId = p.getString("$id.$K_AV_ID", null),
                avatarMediaKey = p.getString("$id.$K_AV_KEY", null),
                host = p.getString("$id.$K_HOST", null),
            )
        }
        _cards.value = out
    }

    /** One account's card, or null when that account has never been active on
     *  this device. Memory only: no disk, no network, no island. */
    fun cardFor(accountId: String?): Card? = accountId?.let { _cards.value[it] }

    /**
     * Remember what the ACTIVE account looks like right now.
     *
     * Write-through and idempotent: an unchanged card touches neither the map
     * nor the disk, so this can be driven straight off the screen's own state
     * without turning every presence tick into a preferences write.
     *
     * ⚠ Never call this for an account that is not the one the session is bound
     * to. The whole cache rests on "an account only ever describes itself"; a
     * caller that wrote somebody else's row would be writing a guess.
     *
     * ⚠⚠ [avatarKnown] is not ceremony, it is the difference between "this
     * account has no picture" and "the profile has not landed yet", and those
     * two arrive at the caller as the same null. A launch reaches the home
     * screen well before the island answers with a profile, so writing that
     * early null would wipe the face this cache exists to have ready on the
     * first frame, and an app killed before the profile arrived would keep the
     * blank. False means "leave the stored picture alone"; the nulls are only
     * believed, and a picture only actually cleared, when the caller says it
     * knows. The desktop dodges this by only ever persisting a snapshot it has
     * finished loading (`persistSnapshot`); this screen has no such moment, so
     * it says so out loud instead.
     */
    fun record(
        context: Context,
        accountId: String,
        nickname: String?,
        uin: Int?,
        avatarMediaId: String?,
        avatarMediaKey: String?,
        avatarKnown: Boolean,
        host: String?,
    ) {
        warm(context)
        val p = prefs ?: return
        val held = _cards.value[accountId]
        val next = Card(
            nickname = nickname?.takeIf { it.isNotBlank() },
            uin = uin,
            avatarMediaId = if (avatarKnown) avatarMediaId?.takeIf { it.isNotEmpty() } else held?.avatarMediaId,
            avatarMediaKey = if (avatarKnown) avatarMediaKey?.takeIf { it.isNotEmpty() } else held?.avatarMediaKey,
            host = host?.takeIf { it.isNotBlank() },
        )
        if (_cards.value[accountId] == next) return
        _cards.value = _cards.value + (accountId to next)
        val ids = p.getStringSet(K_IDS, emptySet()).orEmpty() + accountId
        val e = p.edit()
        // A fresh set instance: SharedPreferences hands back the live one and
        // silently ignores a mutation of it (the same trap LocalStores
        // documents on its own string-set writes).
        e.putStringSet(K_IDS, ids.toSet())
        // A null value removes the key, which is what an account with no
        // picture wants: the absence of a card field, not an empty string that
        // later reads as "there is a blob, id """.
        e.putString("$accountId.$K_NICK", next.nickname)
        if (next.uin != null) e.putInt("$accountId.$K_UIN", next.uin) else e.remove("$accountId.$K_UIN")
        e.putString("$accountId.$K_AV_ID", next.avatarMediaId)
        e.putString("$accountId.$K_AV_KEY", next.avatarMediaKey)
        e.putString("$accountId.$K_HOST", next.host)
        e.apply()
    }

    /** Drop an account's card when the account itself is deleted from the
     *  device. A face left behind after a local delete is a leak of exactly the
     *  kind the delete was asked to prevent. */
    fun forget(context: Context, accountId: String) {
        warm(context)
        val p = prefs ?: return
        _cards.value = _cards.value - accountId
        p.edit()
            .putStringSet(K_IDS, (p.getStringSet(K_IDS, emptySet()).orEmpty() - accountId).toSet())
            .remove("$accountId.$K_NICK")
            .remove("$accountId.$K_UIN")
            .remove("$accountId.$K_AV_ID")
            .remove("$accountId.$K_AV_KEY")
            .remove("$accountId.$K_HOST")
            .apply()
    }

    /**
     * Drop every card whose account is no longer on the device.
     *
     * [forget] covers the delete the user makes from the account manager, but
     * that is not the only way an account leaves: burning it from Privacy, and
     * a duress wipe, both remove it without passing through that screen. A
     * nickname and a face left in preferences for an account that no longer
     * exists is a residue of exactly the kind those actions were asked to
     * remove, so the roster gets the final say every time it is read.
     *
     * ⚠ [keep] must be the FULL roster, never the decoy-filtered one. The
     * visible list holds a single account while the panic PIN is active, and
     * pruning against it would delete the real accounts' cards in the one
     * situation where nothing about them may be touched. Callers guard on
     * [AccountManager.isDecoyMode] and skip this entirely there.
     */
    fun prune(context: Context, keep: Set<String>) {
        warm(context)
        val p = prefs ?: return
        val stale = _cards.value.keys - keep
        if (stale.isEmpty()) return
        _cards.value = _cards.value.filterKeys { it in keep }
        val e = p.edit()
        e.putStringSet(K_IDS, (p.getStringSet(K_IDS, emptySet()).orEmpty() - stale).toSet())
        for (id in stale) {
            e.remove("$id.$K_NICK")
            e.remove("$id.$K_UIN")
            e.remove("$id.$K_AV_ID")
            e.remove("$id.$K_AV_KEY")
            e.remove("$id.$K_HOST")
        }
        e.apply()
    }

    private const val FILE = "rcq_account_cards"
    private const val K_IDS = "ids"
    private const val K_NICK = "nick"
    private const val K_UIN = "uin"
    private const val K_AV_ID = "avatar_id"
    private const val K_AV_KEY = "avatar_key"
    private const val K_HOST = "host"
}
