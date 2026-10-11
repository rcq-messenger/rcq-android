package app.rcq.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import app.rcq.android.Session
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.Contact
import app.rcq.android.model.RcqGroup

/*
 * What ChatScreen reads about its own thread, kept out of ChatScreen.
 *
 * ⚠⚠ Two reasons, and both are about the chat's smoothness (audit 05.09,
 * re-checked 11.10):
 *
 *  1. ChatScreen() is at the size the ART verifier accepts (see the VerifyError
 *     note at its auto-follow effect). Nothing may be ADDED to it; work moves
 *     out into functions like these.
 *  2. Strong skipping keeps a lambda only while everything it captures is the
 *     same. A lambda handed to every bubble that captured `messages` or `group`
 *     was new on every receipt, reaction and presence frame, and every visible
 *     bubble was composed again for it. Reading the thread through [threadOf]
 *     at TAP time, and resolving names from maps keyed by what they contain,
 *     leaves those lambdas nothing that changes.
 */

/** The open thread as the session holds it NOW. For handlers that run on a
 *  tap: they want the list as it is at that moment, and reading it here
 *  instead of capturing the composed list is what keeps them stable. */
internal fun threadOf(session: Session, target: ChatTarget): List<ChatMessage> = when (target) {
    is ChatTarget.Group -> session.groupMessages.value[target.id].orEmpty()
    is ChatTarget.Peer -> session.messages.value[target.uin].orEmpty()
}

/** The room [target] names, as the session holds it now; null for a 1:1. */
internal fun roomOf(session: Session, target: ChatTarget): RcqGroup? =
    (target as? ChatTarget.Group)?.let { t -> session.groups.value.firstOrNull { it.id == t.id } }

/**
 * This thread's messages, recomposing the caller only when THIS thread changes.
 *
 * ChatScreen used to collect the whole map of threads and pick its own out of
 * it, so a message or a receipt in any other conversation (a busy room next to
 * a quiet 1:1) ran the whole chat screen again. The list a thread did not
 * change is the same instance in the next map, and the state here is compared
 * by reference, so another thread's traffic writes nothing.
 *
 * Seeded synchronously from the session, so the first frame already holds the
 * thread (the initial scroll position is computed from it).
 */
@Composable
internal fun rememberThread(session: Session, target: ChatTarget): List<ChatMessage> {
    val thread = remember(session, target) { mutableStateOf(threadOf(session, target), referentialEqualityPolicy()) }
    LaunchedEffect(thread) {
        when (target) {
            is ChatTarget.Group -> session.groupMessages.collect { thread.value = it[target.id].orEmpty() }
            is ChatTarget.Peer -> session.messages.collect { thread.value = it[target.uin].orEmpty() }
        }
    }
    return thread.value
}

/**
 * `#<uin>` -> nick for the bubbles: a room member's nick first, then a
 * contact's. The FIRST row for a number wins on both lists, as the linear
 * search this replaced did (two people can hold one number on two islands).
 *
 * ⚠ Keyed by the nicks, not by the lists. The contact list and the roster are
 * republished on every presence frame, and the resolver used to be rebuilt on
 * each: a new lambda reached every bubble and each re-tokenised its text for
 * a status change. The maps are compared by content, so the same nicks give
 * back the same resolver.
 */
@Composable
internal fun rememberMentionNick(contacts: List<Contact>, group: RcqGroup?, isGroup: Boolean): (Int) -> String? {
    val contactNicks = remember(contacts) { firstNickByUin(contacts.map { it.uin to it.nickname }) }
    val memberNicks = remember(group, isGroup) {
        if (isGroup) group?.members?.let { ms -> firstNickByUin(ms.map { it.uin to it.nickname }) } else null
    }
    return remember(contactNicks, memberNicks) { mentionNickResolver(contactNicks, memberNicks) }
}

/** `@nickname` -> (uin, matched length) for a room's bubbles and composer, or
 *  null outside a room. Same content keying as [rememberMentionNick]: a
 *  presence frame changes the roster, not who is called what. */
@Composable
internal fun rememberMentionMatch(group: RcqGroup?, isGroup: Boolean): ((String, Int) -> Pair<Int, Int>?)? {
    val nicks = remember(group, isGroup) { if (isGroup) group?.members?.map { it.uin to it.nickname } else null }
    return remember(nicks) { nicks?.let(::mentionMatcher) }
}

internal fun firstNickByUin(rows: List<Pair<Int, String>>): Map<Int, String> {
    val out = HashMap<Int, String>(rows.size)
    for ((uin, nick) in rows) out.putIfAbsent(uin, nick)
    return out
}

internal fun mentionNickResolver(contactNicks: Map<Int, String>, memberNicks: Map<Int, String>?): (Int) -> String? =
    { uin -> memberNicks?.get(uin) ?: contactNicks[uin] }

/** Roster longest-match @mention resolver: given the body and the index of an
 *  '@', return (uin, matched-nick-length) for the LONGEST member nick that
 *  follows (case-insensitive), so nicks with spaces or colons ("JO f3 JO",
 *  ".:example") still render as links. Ties go to the earlier roster row. */
internal fun mentionMatcher(members: List<Pair<Int, String>>): (String, Int) -> Pair<Int, Int>? = { text, at ->
    val after = text.substring(at + 1)
    val lower = after.lowercase()
    members
        .filter { it.second.isNotEmpty() && lower.startsWith(it.second.lowercase()) }
        .maxByOrNull { it.second.length }
        ?.let { (uin, nick) ->
            val tail = after.getOrNull(nick.length)
            // Boundary so "@bob" doesn't match inside "@bobsled".
            if (tail == null || !tail.isLetterOrDigit()) uin to nick.length else null
        }
}

/**
 * [next] with every row that has a key and equals the row under that key in
 * [prev] swapped for the earlier instance.
 *
 * The rows are rebuilt from scratch on every change to the thread, so an album
 * row was a new object with a new list of items every time, and everything
 * the album's row handed down (its items, the lambdas that capture the row)
 * was new with it: every visible album was composed again for a receipt on
 * an unrelated message. Equal rows are given back as the very object the list
 * drew last time, and the album skips.
 */
internal fun <T : Any> reuseEqualRows(prev: List<T>?, next: List<T>, keyOf: (T) -> Any?): List<T> {
    if (prev.isNullOrEmpty() || next.none { keyOf(it) != null }) return next
    val old = HashMap<Any, T>()
    for (r in prev) keyOf(r)?.let { old[it] = r }
    if (old.isEmpty()) return next
    var out: ArrayList<T>? = null
    for (i in next.indices) {
        val r = next[i]
        val k = keyOf(r) ?: continue
        val was = old[k] ?: continue
        if (was !== r && was == r) {
            if (out == null) out = ArrayList(next)
            out[i] = was
        }
    }
    return out ?: next
}
