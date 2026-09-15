package app.rcq.android.net

/**
 * What a refused /auth/refresh means for the local copy of an account. Pure, so
 * the one decision that can erase a live account is checked on the JVM (P0.2 of
 * the 15.09 cross-island spec).
 *
 * ⚠⚠ The island answers 404 with THREE different codes, and only one of them is
 * a burn:
 *  - `identity_not_found`: no row carries this key for this number. A burn from
 *    another device, unless we were told the account merely moved.
 *  - `identity_ambiguous`: the number is vacant and the key answers for more
 *    than one account. Alive somewhere; the person finishes the move by phrase.
 *  - `identity_rotated`: the key we hold was RETIRED by a key change made on
 *    another device (or by this one, while a rotation is pending). The account
 *    is alive under new keys, and the local history is exactly what the person
 *    wants to keep.
 *
 * The check used to be `message.contains("identity_not_found")` over the raw
 * error text. That is a substring match over whatever the island wrote, so any
 * future code that happens to contain those letters, or a prose detail quoting
 * them, would have wiped. The code is now read out of the JSON detail and
 * compared exactly, and only a 404 counts.
 */
object AuthRefusal {
    const val IDENTITY_NOT_FOUND = "identity_not_found"
    const val IDENTITY_AMBIGUOUS = "identity_ambiguous"
    const val IDENTITY_ROTATED = "identity_rotated"

    /** What the 4401 probe ([app.rcq.android.Session] probeBurnedAccount) does. */
    enum class ProbeOutcome {
        /** The account row is gone: wipe the local copy (#655). */
        ERASE,
        /** Keys were changed elsewhere: keep everything, ask for the new phrase. */
        ROTATED_ELSEWHERE,
        /** The account moved and this device cannot follow: keep everything, say so. */
        MOVE_REFUSED,
        /** Anything else (offline, 5xx, an unknown code, a pending rotation): change nothing. */
        KEEP,
    }

    /** The exact detail code of a 404, or null for any other status or body. */
    fun code404(message: String?): String? {
        val r = RcqApi.refusalOf(message)
        return if (r.status == 404) r.code else null
    }

    /**
     * [movedAwayFromMe]: the island told this process, on our own socket, that
     * the account left this number. [rotationPending]: this account holds a
     * PendingRotation, so a key change started HERE may already have applied
     * and our own old key is the retired one; the rotation machinery decides
     * what that means, never this probe.
     *
     * ERASE is reachable from exactly one input: a 404 `identity_not_found`
     * with neither marker set.
     */
    fun probeOutcome(message: String?, movedAwayFromMe: Boolean, rotationPending: Boolean): ProbeOutcome {
        val code = code404(message) ?: return ProbeOutcome.KEEP
        return when {
            code == IDENTITY_AMBIGUOUS -> ProbeOutcome.MOVE_REFUSED
            rotationPending -> ProbeOutcome.KEEP
            code == IDENTITY_ROTATED -> ProbeOutcome.ROTATED_ELSEWHERE
            code == IDENTITY_NOT_FOUND -> if (movedAwayFromMe) ProbeOutcome.MOVE_REFUSED else ProbeOutcome.ERASE
            else -> ProbeOutcome.KEEP
        }
    }

    /** What following an `account_moved` frame does when its refresh is refused.
     *  It never erases on any input. */
    enum class FollowOutcome { ROTATED_ELSEWHERE, MOVE_REFUSED, RETRY_LATER }

    fun followOutcome(message: String?, rotationPending: Boolean): FollowOutcome {
        val code = code404(message) ?: return FollowOutcome.RETRY_LATER
        return when {
            code == IDENTITY_ROTATED -> if (rotationPending) FollowOutcome.RETRY_LATER else FollowOutcome.ROTATED_ELSEWHERE
            // `identity_not_found` here is the refusal from an island too old
            // to say `identity_ambiguous`; the move frame came first, so it is
            // not read as a burn.
            code == IDENTITY_AMBIGUOUS || code == IDENTITY_NOT_FOUND -> FollowOutcome.MOVE_REFUSED
            else -> FollowOutcome.RETRY_LATER
        }
    }
}
