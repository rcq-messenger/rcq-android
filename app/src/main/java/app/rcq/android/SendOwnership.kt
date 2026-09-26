package app.rcq.android

/**
 * Whose a background media send is, and whether it may touch the session now
 * (#1048 review). Pure, so the rule is pinned by a test; [Session] reads the
 * live state into a [SendScene] and acts on the answer.
 *
 * A send started from a chat ([Session.sendMediaDetached]) outlives the screen,
 * which is the point (#473), and so it outlives a lock and an account switch
 * too. What it must never do is finish in somebody else's name: store its row
 * in another account's database, seal it as another identity, or land a real
 * recording in the decoy. And what it must not do either is vanish because the
 * phone locked: with a PIN and autolock "Immediately" every trip to the home
 * screen locks, and a picture still uploading then was thrown away even when
 * the person came straight back into the same account (the first cut of this
 * fix did that, keyed on the session epoch, which every lock moves).
 *
 * So a send remembers the ACCOUNT it was started in and whether that was the
 * real session. It goes ahead only while that same real account is open and
 * unlocked with its database bound; while the app is locked, or another
 * account or the decoy is in front, it WAITS for its account to come back.
 * It is dropped only when it can never be finished honestly: its account is
 * gone from the device, or it was a decoy session's send (those put nothing
 * on the wire anyway) and that decoy session is over.
 */
internal data class SendOwner(
    /** [app.rcq.android.data.AccountManager.activeId] when the send was pressed. */
    val accountId: String?,
    /** Started inside a decoy session (either kind). */
    val decoy: Boolean,
    /** The session epoch then: what ties a decoy send to its one session. */
    val epoch: Int,
)

/** The session as a send finds it. */
internal data class SendScene(
    val activeId: String?,
    val locked: Boolean,
    val inDecoy: Boolean,
    /** The account whose message database is open right now, or null. */
    val dbOpenFor: String?,
    val epoch: Int,
    /** The owner's account still exists on this device. */
    val ownerKnown: Boolean,
    /** This session tried to open the account's message database and could
     *  not (Session._dbLocked): it will not open by waiting. */
    val dbBroken: Boolean = false,
)

/** FAIL: the owner's account is in front and unlocked, but its database could
 *  not be opened this session and nothing will reopen it by waiting. Reported
 *  as a failed send (the toast 0.206 showed), not parked for ever out of sight. */
internal enum class SendGate { GO, WAIT, DROP, FAIL }

internal fun sendGate(owner: SendOwner, scene: SendScene): SendGate {
    if (owner.decoy || owner.accountId == null) {
        return if (scene.epoch == owner.epoch && !scene.locked) SendGate.GO else SendGate.DROP
    }
    if (!scene.ownerKnown) return SendGate.DROP
    val inFront = !scene.locked && !scene.inDecoy && scene.activeId == owner.accountId
    if (inFront && scene.dbOpenFor == owner.accountId) return SendGate.GO
    // ⚠ Waiting only makes sense for something that ends: a lock, another
    // account in front, a database still being bound. A database this session
    // already failed to open does not end, and a send parked behind it vanished
    // from the strip with no word (the review of 5f9afd3; 0.206 said "not sent").
    if (inFront && scene.dbOpenFor == null && scene.dbBroken) return SendGate.FAIL
    return SendGate.WAIT
}
