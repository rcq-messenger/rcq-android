package app.rcq.android.data

/**
 * Which island a number means, when a screen is about to resolve a person by it.
 *
 * Pure and on its own because this is the decision reports #433, #429 and now
 * #1024 were all made of, and because getting it wrong is silent: an island
 * answers for the number it holds, so the wrong island does not fail, it
 * confidently describes somebody else.
 *
 * ⚠⚠ A uin alone does not name a person. Islands number independently, so #134
 * is a different account on every one of them. Every caller that knows WHICH
 * island it means has to say so, and a caller that does not know must be
 * answered from what we hold locally rather than by assuming our own island.
 */
object PeerIsland {

    /**
     * The island a peer's card must be read from, or null for our own.
     *
     * [callerHost] is the island the caller knows the number means, for callers
     * that know: a room's host, for a member tapped inside that room. Null means
     * the caller did not say, which is the 1:1 case, since a thread is opened by
     * number alone.
     *
     * [rosterMatched] and [rosterHost] describe the visible roster's row for
     * this number, if the screen matched one: [rosterHost] is null for a row on
     * our own island. [storeHost] is what [app.rcq.android.net.CrossIslandStore]
     * holds for the number, null when it holds nothing.
     *
     * The order is the point:
     *
     *  - [callerHost] naming our own island is the caller saying "the local one",
     *    and it must NOT fall back to anything. That is report #433 (the row
     *    said is2, the card showed the api account) and #429 (the request went
     *    to the api account while the is2 one sat pending).
     *  - A caller naming a foreign island is believed next, since it knows
     *    something the roster may not hold at all: a room member who is not a
     *    contact has no row anywhere.
     *  - Then the roster, which is authoritative once it HAS a row. A matched
     *    same-island row answers "our island" and is not second-guessed.
     *  - Only then the store, and this is the line #1024 needed: a cross-island
     *    contact can exist in the store while the visible roster has not folded
     *    it in yet (an accept that arrived from another device, a roster still
     *    answering 304). Without it the 1:1 card resolved such a person on OUR
     *    island, drew whoever holds that number there, and sent them a visit
     *    ping for good measure.
     */
    fun cardHost(
        callerHost: String?,
        ourIsland: String?,
        rosterMatched: Boolean,
        rosterHost: String?,
        storeHost: String?,
    ): String? {
        if (callerHost != null) {
            // "Our own island" is an answer, not a missing one.
            if (ourIsland != null && callerHost.equals(ourIsland, true)) return null
            return rosterHost ?: callerHost
        }
        if (rosterMatched) return rosterHost
        // ⚠ A store host equal to our own island still resolves to null: the
        // question this function answers is "somewhere else, and where", and
        // saying "elsewhere" about our own island would send a local card down
        // the cross-island path.
        if (storeHost != null && ourIsland != null && storeHost.equals(ourIsland, true)) return null
        return storeHost
    }
}
