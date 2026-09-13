package app.rcq.android.data

import app.rcq.android.model.ChatMessage

/**
 * The last nickname this device saw for each member of each group (#982).
 *
 * ⚠⚠ WHY IT EXISTS. The name over a group bubble was looked up in the CURRENT
 * roster, and the island hard-deletes a membership on leave, so the moment
 * somebody left, every message they had ever written was signed with their
 * number. Quotes of the same messages kept the name, because a quote carries
 * its author label with it, which made the number look like a bug rather than
 * a rule. Worse, quoting a former member then put their NUMBER into the
 * outgoing quote, and that label reaches everyone in the room.
 *
 * Filled on every roster fetch (the newest name wins) and never pruned when a
 * member leaves, because leaving is exactly when it is needed. Dropped when
 * THIS account leaves or loses the group, with the history, and with the
 * account.
 *
 * ⚠ KEYED BY ISLAND HOST AND GROUP ID. A uin is per island, and a foreign
 * group's local id is an alias this device made up, so neither number alone
 * names one person or one room.
 *
 * Persisted by [Session] in the SQLCipher message store, next to the history
 * it labels, so it is sealed under the same key and a decoy session (its own
 * file) never sees it. Lookups read an immutable snapshot and take no lock;
 * the lock only orders writers against [clear], so a write that started for
 * one account can never land in memory after the switch to the next.
 */
class MemberNameCache {

    data class Key(val host: String, val groupId: Int)

    private val lock = Any()
    @Volatile private var snapshot: Map<Key, Map<Int, String>> = emptyMap()
    /** The account's home island, for groups whose row carries no host. Null
     *  until [load] or [record] names it; lookups for home groups miss until
     *  then, which is the same as an empty map. */
    @Volatile private var ownHost: String? = null
    @Volatile private var gen = 0L
    @Volatile private var quoted: Pair<List<ChatMessage>, Map<Int, String>>? = null

    /** A change token: [record] and [forget] called with a stale one do nothing. */
    fun generation(): Long = gen

    /** Replace everything with what the store holds for the bound account. */
    fun load(homeHost: String, rows: Map<Key, Map<Int, String>>) = synchronized(lock) {
        gen++
        ownHost = homeHost.lowercase()
        snapshot = rows
        quoted = null
    }

    /** Forget everything in memory (account switch, lock, duress, burn). */
    fun clear() = synchronized(lock) {
        gen++
        ownHost = null
        snapshot = emptyMap()
        quoted = null
    }

    fun lookup(host: String?, groupId: Int, uin: Int): String? {
        val h = host ?: ownHost ?: return null
        return snapshot[Key(h.lowercase(), groupId)]?.get(uin)
    }

    /**
     * Fold one fetched roster in. [persist] receives only the entries that are
     * new or renamed, and is not called at all when nothing changed: a room of
     * two thousand refetched with the same names costs a map compare and no
     * disk write. Runs [persist] under the lock, so call it off the main thread.
     */
    fun record(
        expected: Long,
        homeHost: String,
        host: String?,
        groupId: Int,
        members: List<Pair<Int, String>>,
        persist: (Key, Map<Int, String>) -> Unit,
    ) = synchronized(lock) {
        if (expected != gen) return@synchronized
        ownHost = homeHost.lowercase()
        val key = Key((host ?: homeHost).lowercase(), groupId)
        val current = snapshot[key] ?: emptyMap()
        val changed = namesToRemember(current, members)
        if (changed.isEmpty()) return@synchronized
        snapshot = snapshot + (key to (current + changed))
        persist(key, changed)
    }

    /** Drop one group's names (this account left it, deleted it, or lost it).
     *  Bumps the generation too, so a [record] of that roster still queued
     *  behind the leave cannot write the names back. */
    fun forget(expected: Long, homeHost: String, host: String?, groupId: Int, persist: (Key) -> Unit) =
        synchronized(lock) {
            if (expected != gen) return@synchronized
            gen++
            val key = Key((host ?: homeHost).lowercase(), groupId)
            snapshot = snapshot - key
            persist(key)
        }

    /** [quotedAuthorNames] for [messages], computed once per list. The chat
     *  asks for it per bubble and the list only changes when a row does. */
    fun quotedNames(messages: List<ChatMessage>): Map<Int, String> {
        if (messages.isEmpty()) return emptyMap()
        quoted?.let { (list, names) -> if (list === messages) return names }
        val names = quotedAuthorNames(messages)
        quoted = messages to names
        return names
    }
}

/** The roster entries worth writing down: a real name that differs from what
 *  is already held. The mapper puts the bare number where the island sent no
 *  nickname, and that is a placeholder, not a name to remember. */
internal fun namesToRemember(current: Map<Int, String>, members: List<Pair<Int, String>>): Map<Int, String> {
    val out = HashMap<Int, String>()
    for ((uin, nick) in members) {
        if (!isRealName(nick, uin)) continue
        if (current[uin] != nick) out[uin] = nick
    }
    return out
}

/**
 * The name for a group member, first answer wins (#982):
 * my alias for them (screen only), the current roster, the last roster that
 * had them, my contact row for them, the author label on a quote of them in
 * this chat, and only then their number.
 *
 * ⚠⚠ Pass a null [alias] for anything that leaves the device. A quote label
 * reaches the person it names, and my name for somebody is mine.
 */
fun resolveMemberName(
    uin: Int,
    alias: String?,
    rosterName: String?,
    lastKnown: String?,
    contactNick: String?,
    quotedAs: String?,
): String = sequenceOf(alias, rosterName, lastKnown, contactNick, quotedAs)
    .firstOrNull { isRealName(it, uin) } ?: "$uin"

/**
 * uin -> the author label other people (or I) put on a quote of that member's
 * message, from the rows of one chat. The newest quote wins. A quote of a
 * message that is not loaded names nobody, and a label that is only digits is
 * skipped: that is the number this bug wrote into quotes, not a name.
 */
fun quotedAuthorNames(messages: List<ChatMessage>): Map<Int, String> {
    val senderById = HashMap<String, Int>()
    for (m in messages) {
        val s = m.senderUin
        if (!m.fromMe && s != null) senderById[m.id] = s
    }
    if (senderById.isEmpty()) return emptyMap()
    val out = HashMap<Int, String>()
    for (m in messages.sortedBy { it.sentAt }) {
        val label = m.replyToAuthor?.trim() ?: continue
        val target = m.replyToId?.let { senderById[it] } ?: continue
        if (label.isEmpty() || label.all { it.isDigit() }) continue
        out[target] = label
    }
    return out
}

private fun isRealName(name: String?, uin: Int): Boolean =
    !name.isNullOrBlank() && name != uin.toString()
