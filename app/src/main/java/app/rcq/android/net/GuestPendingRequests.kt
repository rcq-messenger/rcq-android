package app.rcq.android.net

import app.rcq.android.model.RcqGroup

/**
 * Contact requests addressed to one of our GUEST copies (F1 of the 15.09
 * cross-island spec), as pure decisions. The session feeds in what the island
 * listed and what this device already knows; everything that touches the
 * network or a store stays in the session.
 *
 * Somebody on island B who met us in a room there sends an ordinary same-island
 * request to our guest number. B cannot forward it to our home island, so the
 * only way it ever reaches us is this device asking B with the guest token.
 * An accept is then answered from HOME with a sealed §5f accept, and the row on
 * B is withdrawn (`DELETE /contacts/pending/{id}`), which writes no contact
 * edges on B and tells the requester nothing B could not already see.
 */
object GuestPendingRequests {

    /** At most this many rows of one island are acted on per poll. */
    const val MAX_ROWS_PER_HOST = 20

    /** Withdraws per poll per island. The island allows 60 an hour per account,
     *  and polls come at most every five minutes: 3 x 12 = 36 leaves room for
     *  the ones a person triggers by tapping. */
    const val MAX_WITHDRAWS_PER_POLL = 3

    /** The accept is deposited again by the poll up to this many times in all
     *  (the tap counts as the first), then the row says it gave up. */
    const val MAX_ACCEPT_TRIES = 3

    const val NO_SUCH_REQUEST = "no_such_request"

    data class Row(val id: Int, val fromUin: Int, val nickname: String?)

    enum class Action {
        /** Show it: merge into the request list. */
        UPSERT,
        /** Clear it on the island without showing it. */
        WITHDRAW,
        /** Deposit the accept from home again (a tap that only added locally). */
        REDEPOSIT,
        /** Leave the local row as it is (it already says why). */
        KEEP,
        /** Nothing to do this poll. */
        SKIP,
    }

    data class Step(val row: Row, val action: Action)

    /**
     * What to do with each listed row, in the island's order, at most
     * [MAX_ROWS_PER_HOST] of them:
     *  - blocked sender: withdraw when the island can, never shown;
     *  - already answered here: withdrawn when the island can, never shown;
     *  - an accept that only added locally: deposited again until it gave up;
     *  - someone already in our cross-island contacts at that address: withdraw only;
     *  - otherwise shown.
     * A withdraw past the per-poll budget waits for the next poll.
     *
     * ⚠ An answered id the island still lists IS a withdraw still owed: the
     * island lists only pending rows, and a decline that landed stops the
     * listing. No separate "owed" record is kept. One in memory was lost with
     * every process death, and after that an accepted request whose background
     * withdraw had failed stayed pending on the island for good. The withdraw
     * is idempotent (`no_such_request` counts as done), so asking again is safe.
     */
    fun plan(
        rows: List<Row>,
        canWithdraw: Boolean,
        isBlocked: (uin: Int) -> Boolean,
        isAnswered: (id: Int) -> Boolean,
        hasContact: (uin: Int) -> Boolean,
        acceptTries: (uin: Int, id: Int) -> Int,
    ): List<Step> {
        var withdraws = 0
        var redeposits = 0
        fun withdrawOrSkip(): Action =
            if (canWithdraw && withdraws < MAX_WITHDRAWS_PER_POLL) Action.WITHDRAW.also { withdraws++ } else Action.SKIP
        return rows.take(MAX_ROWS_PER_HOST).map { r ->
            val tries = acceptTries(r.fromUin, r.id)
            val action = when {
                r.id <= 0 || r.fromUin <= 0 -> Action.SKIP
                isBlocked(r.fromUin) -> withdrawOrSkip()
                isAnswered(r.id) -> withdrawOrSkip()
                tries in 1 until MAX_ACCEPT_TRIES ->
                    if (redeposits < MAX_WITHDRAWS_PER_POLL) Action.REDEPOSIT.also { redeposits++ } else Action.KEEP
                tries >= MAX_ACCEPT_TRIES -> Action.KEEP
                hasContact(r.fromUin) -> withdrawOrSkip()
                else -> Action.UPSERT
            }
            Step(r, action)
        }
    }

    enum class Withdraw {
        /** Gone on the island (204, or its own "no such request"). */
        DONE,
        /** A 404 without the endpoint's code: the route is not there, so the
         *  capability is re-read and the row stays hidden. The next poll that
         *  still lists it withdraws it again. */
        ROUTE_LOST,
        UNAUTHORIZED,
        RATE_LIMITED,
        RETRY,
    }

    fun withdrawOutcome(status: Int, code: String?): Withdraw = when {
        status in 200..299 -> Withdraw.DONE
        status == 404 && code == NO_SUCH_REQUEST -> Withdraw.DONE
        status == 404 -> Withdraw.ROUTE_LOST
        status == 401 -> Withdraw.UNAUTHORIZED
        status == 429 -> Withdraw.RATE_LIMITED
        else -> Withdraw.RETRY
    }

    enum class Deposit { SENT, ADDED_ONLY, FAILED }

    enum class AfterAccept {
        /** The accept reached them: withdraw on the island, then clear the row. */
        FINISH,
        /** The island does not advertise withdraw: hide the row here, never decline. */
        FINISH_HIDE_ONLY,
        /** Added here, the accept did not leave: keep the row, the poll retries. */
        RETRYING,
        /** Out of retries: keep the row and say so. */
        GAVE_UP,
        /** Nothing happened (no card): the row stays as it was. */
        FAILED,
    }

    /** [triesBefore] is the row's count before this attempt. */
    fun afterAccept(deposit: Deposit, canWithdraw: Boolean, triesBefore: Int): AfterAccept = when (deposit) {
        Deposit.FAILED -> AfterAccept.FAILED
        Deposit.SENT -> if (canWithdraw) AfterAccept.FINISH else AfterAccept.FINISH_HIDE_ONLY
        Deposit.ADDED_ONLY -> if (triesBefore + 1 >= MAX_ACCEPT_TRIES) AfterAccept.GAVE_UP else AfterAccept.RETRYING
    }

    /** Rooms on [host] whose CACHED roster lists [uin], by name. Never fetches:
     *  the line under the row is a hint, and a roster B serves is B's word. */
    fun sharedRooms(groups: List<RcqGroup>, host: String, uin: Int): List<String> =
        groups.filter { g -> g.host?.equals(host, ignoreCase = true) == true && g.members.any { it.uin == uin } }
            .map { it.name }
            .distinct()

    /**
     * Does the key the island's card serves for the requester differ from every
     * key this device already saw for that number in a room there (critic 6)?
     * False when nothing was seen: there is nothing to compare with. Keys are
     * compared as bytes, so url-safe or unpadded base64 of the same key matches.
     */
    fun keyDiffers(cardSigningKey: String, priorKeys: Collection<String>): Boolean {
        val priors = priorKeys.mapNotNull { decodeKey(it) }
        if (priors.isEmpty()) return false
        val card = decodeKey(cardSigningKey) ?: return true
        return priors.none { java.security.MessageDigest.isEqual(it, card) }
    }

    internal fun decodeKey(b64: String): ByteArray? = runCatching {
        val s = b64.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        if (s.isEmpty()) return null
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        java.util.Base64.getDecoder().decode(padded)
    }.getOrNull()
}
