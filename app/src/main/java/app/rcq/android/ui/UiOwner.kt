package app.rcq.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.rcq.android.data.AccountManager

/**
 * Whose screen the UI is drawing: the active roster account, and whether the
 * duress (decoy) view is up over it. A key for UI state that is kept OUTSIDE
 * one screen's composition (the Add sheet, composer drafts, the saved state
 * of the home and settings screens), so none of it is shown to an owner other
 * than the one it was made under (report 1068).
 *
 * ⚠ The account id, never the number. Two accounts on this phone can hold the
 * same number on two islands, and those are two different people.
 *
 * ⚠ The decoy flag is part of the key, not an afterthought. A migrated duress
 * session keeps [AccountManager.activeId] on the REAL account (only a flag
 * flips, see `AccountManager.enterDecoySession`), so keyed by the id alone the
 * real account's typed search, drafts and bug report would come back inside
 * the duress view. The legacy decoy counts too: it is a roster account of its
 * own, but it is shown under the same filter.
 */
internal object UiOwner {
    fun key(accountId: String?, decoy: Boolean): String =
        "${accountId.orEmpty()}/${if (decoy) "decoy" else "real"}"

    /** The owner right now, read straight from [AccountManager]. */
    fun now(): String = key(
        AccountManager.activeId.value,
        AccountManager.decoySession.value || AccountManager.decoyMode.value != null,
    )
}

/** [UiOwner.now] for a composable, recomposing when it changes.
 *
 *  ⚠ Subscribed for the recomposition, but the answer is read from the flows
 *  themselves rather than from the collected copies. The duress view is raised
 *  on a background thread and the lock lifts right after it; a frame composed
 *  because the lock lifted must already see the new owner, and a collected
 *  copy can lag one dispatch behind the flow it mirrors. */
@Composable
internal fun currentUiOwner(): String {
    AccountManager.activeId.collectAsState().value
    AccountManager.decoySession.collectAsState().value
    AccountManager.decoyMode.collectAsState().value
    return UiOwner.now()
}

/** A string map whose entries belong to the [UiOwner] they were written under:
 *  every read and write goes through the owner of the moment, so another
 *  owner's entry under the same key is never seen. For UI state kept outside
 *  composition (ChatDrafts). Main thread only. */
internal class PerOwnerMap {
    private val all = mutableMapOf<String, String>()
    private fun at(key: String) = "${UiOwner.now()}|$key"
    operator fun get(key: String): String? = all[at(key)]
    operator fun set(key: String, value: String) { all[at(key)] = value }
    fun remove(key: String): String? = all.remove(at(key))
    /** Every owner's entries. */
    fun clear() = all.clear()
}
