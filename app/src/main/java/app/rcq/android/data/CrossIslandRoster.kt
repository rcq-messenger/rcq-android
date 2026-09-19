package app.rcq.android.data

import app.rcq.android.model.Contact

/**
 * How the cross-island half of the visible roster is folded into the half the
 * island serves.
 *
 * Pure and on its own so the rule is pinned in a JVM test, because the bug it
 * exists to fix (#1024) was invisible in exactly the way an untested rule is:
 * nothing threw, nothing logged, the chat worked, and the list was simply wrong
 * until the account was switched away and back.
 *
 * ⚠⚠ What went wrong, and why "append the store's rows" is not enough. The
 * roster is read with a conditional GET, and since 0.174 a 304 returns from
 * [app.rcq.android.Session.refreshContacts] before the fold at the end of it
 * ever runs (it has to: re-folding the kept rows repainted presence that the
 * websocket had just painted, report #909). A cross-island add or remove touches
 * nothing on our island, so the island's ETag does not move, so the answer is
 * 304 forever and the fold never ran again. Adding somebody appeared to do
 * nothing; removing somebody appeared to do nothing; both showed up at the next
 * account switch, which is the one thing that clears the ETag.
 *
 * So this is a SYNC and not a merge: it adds the store's rows that the list is
 * missing AND drops the list's cross-island rows the store no longer has. Both
 * directions, or removal stays broken.
 *
 * ⚠ [Contact.host] is the whole of what makes a row "ours to manage" here. It is
 * local-only, never sent by an island (Gson leaves it null for served rows), and
 * the one place that sets it is the mapper over
 * [app.rcq.android.net.CrossIslandStore]. Anything with a null host belongs to
 * the island and must survive untouched, including a same-numbered contact on
 * our own island.
 */
object CrossIslandRoster {

    /** [current] with its cross-island rows replaced by exactly [fromStore].
     *
     *  Same-island rows keep their order and their contents. A store row whose
     *  number is already held by a same-island contact is skipped, as the old
     *  merge did: one number is one thread in the message store, so two rows for
     *  it would share one history (see
     *  [app.rcq.android.Session.clashesWithKnownNumber], which is what stops
     *  that pair from being created in the first place).
     *
     *  Returns [current] itself when nothing would change, so a caller can hand
     *  the result straight to a StateFlow without waking every collector on
     *  every presence frame. */
    fun fold(current: List<Contact>, fromStore: List<Contact>): List<Contact> {
        val local = current.filter { it.host == null }
        val localUins = local.mapTo(HashSet()) { it.uin }
        // Keep the row the list already has rather than the store's fresh copy:
        // the displayed fields are refreshed by their own path
        // ([app.rcq.android.Session.refreshCrossIslandDisplay]) and a row that is
        // already on screen must not be rebuilt under the user for no reason.
        val kept = current.filter { it.host != null }.associateBy { it.uin to it.host!!.lowercase() }
        val cross = fromStore
            .filter { it.uin !in localUins }
            .map { row -> kept[row.uin to (row.host ?: "").lowercase()] ?: row }
        val next = local + cross
        return if (next == current) current else next
    }
}
