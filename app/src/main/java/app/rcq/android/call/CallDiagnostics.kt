package app.rcq.android.call

/**
 * What the last call actually did, kept so a user can tell us.
 *
 * ⚠ Why this exists: when a call fails to carry media the app already knows
 * almost exactly why — how many ICE candidates of each type it managed to
 * gather, whether the TURN relay was reachable at all, whether media ever
 * started. Until now all of it went to logcat, which nobody outside a debug
 * cable can read, so every "calls do not work" report (#472 and the three
 * before it on 0.104) had to be answered by guessing from server logs that do
 * not carry call signalling. One line on the diagnostics screen turns the next
 * one into an answer.
 *
 * Deliberately holds nothing about WHO was called: no UIN, no nickname, no
 * address. Direction, media kind, outcome and candidate counts are enough to
 * tell a symmetric-NAT failure from a blocked relay from a dead signalling
 * path, and none of them say anything about the other person.
 *
 * In memory only — a diagnostic for the call you just made, not a call log.
 */
object CallDiagnostics {

    data class Last(
        val outgoing: Boolean,
        val video: Boolean,
        /** Did media ever flow? False here is the whole point of the record. */
        val connected: Boolean,
        /** Wire reason the call ended with. */
        val reason: String,
        /** Seconds spent on SETUP: from placing/receiving the call to the moment
         *  media started flowing, or to the moment we gave up. Deliberately not
         *  the call's length — how long a call lasted says nothing about the
         *  network, and how long it took to connect says almost everything. */
        val seconds: Long,
        /** Local ICE candidates gathered, e.g. "host=4 srflx=2 relay=0". */
        val ice: String,
        /** Did this call force every candidate through the relay? */
        val relayOnly: Boolean,
        /** Was the relay reachable when last measured? null = never measured. */
        val relayReachable: Boolean?,
        /** TURN servers the island handed out for this call. */
        val turnServers: Int,
    )

    @Volatile
    var last: Last? = null
        private set

    /** Host the island last handed out for TURN, so the network audit can
     *  test the one address calls actually depend on. */
    @Volatile
    var turnHost: String? = null


    fun record(l: Last) {
        last = l
    }

    /** ★ The single line worth pasting into a report. Reads, in order: which way
     *  the call went, whether media flowed, how long it took to give up, what
     *  candidates were gathered, and what we knew about the relay.
     *
     *  `r0` with `fail` is the signature of the failure we cannot otherwise see:
     *  no relay candidate was ever gathered, so two peers behind carrier NAT had
     *  nothing to connect through. */
    fun compact(): String? {
        val l = last ?: return null
        return buildString {
            append("call:")
            append(if (l.outgoing) "out/" else "in/")
            append(if (l.video) "video " else "audio ")
            append(if (l.connected) "ok " else "fail ")
            append("${l.seconds}s ")
            append(l.ice.replace("host=", "h").replace("srflx=", "s").replace("relay=", "r")
                .replace(" ", "/"))
            append(" turn:${l.turnServers}")
            append(if (l.relayOnly) "/only" else "")
            append(
                when (l.relayReachable) {
                    true -> "/reach"
                    false -> "/unreach"
                    null -> "/unmeasured"
                },
            )
            // Which echo canceller ran. An echo report is unanswerable without
            // it: "hw" means the ROM's own effect, which is the one that can be
            // declared and not work (#532).
            append(" aec:${WebRtcClient.echoCancellerKind}")
        }
    }
}
