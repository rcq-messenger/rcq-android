package app.rcq.android.data

/**
 * Which presence chime, if any, a roster refresh is allowed to make.
 *
 * ⚠⚠ THE SOUND USED TO BEAR NO RELATION TO WHAT WAS ON SCREEN, and this file
 * is the answer to why. Reports #1030 and #1029, and the mechanism is worth
 * spelling out because every rule below is one sentence of it.
 *
 * A refresh applies the island's whole roster at once, so it can carry SEVERAL
 * transitions. The old code looped over them and called the player for each,
 * and the player has a 1200 ms throttle shared with the message tone, so what
 * actually reached the speaker was ONE arbitrary tone: the first row in an
 * order the island does not sort, for a contact the person may not have been
 * looking at, in the direction that row happened to move. The contact on
 * screen stayed green while a departure chimed for somebody else. In the
 * reporter's words: "у собеседников может цветок быть зеленым (никто не
 * уходит), а он издает звук".
 *
 * It is worse than arbitrary, because the transitions themselves are often not
 * real. The island calls somebody online when their `last_seen` is inside a 60
 * second window, refreshed by a 25 second heartbeat, with no hysteresis: one
 * missed ping — a tunnel, a screen-off, a throttled background tab — flips a
 * person to offline and the next ping flips them back. Nobody went anywhere.
 *
 * So the client's job is not "announce every transition". It is: say something
 * only when a transition is probably ABOUT A PERSON, and never more than once
 * per refresh. Pure and side-effect free, because the phone is a bad place to
 * find out whether a rule is right.
 */
object PresenceChime {

    /** One contact's presence changing between two roster reads. */
    data class Flip(
        val uin: Int,
        /** True = they appeared, false = they disappeared. */
        val online: Boolean,
        val favorite: Boolean,
        /** Their thread is muted. Web has always honoured this for presence and
         *  Android never did, on the platform the complaint came from. */
        val muted: Boolean,
    )

    /** The one chime this refresh may make. */
    data class Decision(val uin: Int, val online: Boolean)

    /**
     * How many transitions in a single refresh stop being news.
     *
     * Three people changing state between two reads happens; a wave does not,
     * and when it does it is the network, not a room filling up. The wave is
     * the reconnect case: the phone wakes, the socket comes back, the island
     * answers with a roster whose presence column moved for everyone at once.
     * That is the loudest false chime there is, and it is also the one the
     * user is most likely to hear, because they just picked the phone up.
     */
    const val BULK_FLOOR = 4

    /**
     * How long one contact stays silent after they have chimed.
     *
     * Aimed squarely at the flap: with a 60 second freshness window over a 25
     * second heartbeat, a contact on a bad connection can cross the line every
     * couple of minutes forever. The first crossing is worth a sound. The
     * twentieth is what makes somebody write "бьёт по ушам".
     */
    const val PER_CONTACT_COOLDOWN_MS = 5 * 60_000L

    /**
     * @param flips every presence change this refresh saw, in roster order
     * @param mode who is worth a sound at all
     * @param departuresOn whether "left" is worth a sound, separately from
     *        "arrived": the two used to be one switch, so the only way to
     *        silence the departure tone was to silence both (#1030 asks for
     *        exactly the first without the second)
     * @param lastChimedAt when each uin last chimed, by the same clock as [now]
     * @param now a monotonic clock (SystemClock.elapsedRealtime)
     */
    fun decide(
        flips: List<Flip>,
        mode: LocalStores.PresenceSoundMode,
        departuresOn: Boolean,
        lastChimedAt: Map<Int, Long>,
        now: Long,
    ): Decision? {
        if (mode == LocalStores.PresenceSoundMode.OFF) return null

        val worth = flips.filter { f ->
            when {
                f.muted -> false
                mode == LocalStores.PresenceSoundMode.FAVORITES && !f.favorite -> false
                !f.online && !departuresOn -> false
                else -> true
            }
        }
        if (worth.isEmpty()) return null

        // ⚠ The bulk test counts what SURVIVED the filters, not what arrived.
        // Counting the raw list would let a single friend's arrival be
        // swallowed by a wave of strangers nobody asked to hear about — which
        // is the old behaviour wearing a new name.
        if (worth.size >= BULK_FLOOR) return null

        // ⚠ ABSENT is not "long ago", it is "never", and the difference is an
        // overflow: `now - Long.MIN_VALUE` wraps negative, so a sentinel made
        // every contact this app had not chimed for yet look as if they had
        // chimed a moment ago, and the rule returned silence for everything.
        // Caught by the test named for exactly this.
        val fresh = worth.filter { f ->
            val last = lastChimedAt[f.uin]
            last == null || now - last >= PER_CONTACT_COOLDOWN_MS
        }
        if (fresh.isEmpty()) return null

        // At most three left. Choose DELIBERATELY rather than taking the first,
        // because "the first" is the island's row order, which is an index scan
        // and means nothing to a person: favourites first, then an arrival over
        // a departure (somebody appearing is the one you might act on), then the
        // lower number so two identical candidates never depend on the weather.
        val pick = fresh.minWithOrNull(
            compareBy<Flip>({ if (it.favorite) 0 else 1 }, { if (it.online) 0 else 1 }, { it.uin })
        ) ?: return null
        return Decision(pick.uin, pick.online)
    }
}
