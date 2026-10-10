package app.rcq.android.data

/**
 * What MainActivity.onCreate may do with the per-account plaintext stores
 * (LocalStores, VisitStore).
 *
 * ⚠⚠ THE DURESS VIEW USED TO FALL OPEN ON A LANGUAGE CHANGE (report 1072, side
 * finding). onCreate bound both stores to the active roster account every time
 * it ran, and it runs far more often than once per process: a language change
 * calls `recreate()`, a system locale or font-size change recreates the
 * Activity, and a swipe-away relaunch inside the PIN grace builds a new one
 * over the same process. The [app.rcq.android.Session] is one per process and
 * outlives all of them, so in a decoy session `db` stayed on the decoy file
 * while the stores went back to the REAL account: the duress view then drew the
 * real account's sections, nicknames, favourites, mutes and unread counts, and
 * wrote into its slots, in front of whoever had been handed the duress PIN.
 *
 * The rule: once the Session exists it owns the binding. Every transition that
 * moves it (an account switch, the decoy unlock, the lock that leaves it, the
 * last account going) already re-points every store in one place
 * (`Session.bindPerAccountStores`), so a recreate has nothing to add and
 * everything to break. Only the first onCreate of a process binds, and a
 * process cannot be in a decoy session before its Session exists; the duress
 * case below is there so the answer stays right if that ever changes.
 */
object StoreBinding {

    sealed interface OnCreate {
        /** Leave the stores exactly where they are. */
        data object Keep : OnCreate

        /** Point them at [accountId]; null means no account, every read empty. */
        data class Bind(val accountId: String?) : OnCreate
    }

    fun onActivityCreate(sessionBuilt: Boolean, duressViewUp: Boolean, activeId: String?): OnCreate = when {
        sessionBuilt -> OnCreate.Keep
        duressViewUp -> OnCreate.Bind(DecoyStore.STORE_ID)
        else -> OnCreate.Bind(activeId)
    }
}
