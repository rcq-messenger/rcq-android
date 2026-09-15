package app.rcq.android.net

/**
 * The order of a burn's last two phases and of the wipe PIN, with the session
 * injected, so the ordering rules of F2 (15.09 spec) are checked on the JVM.
 * Nothing here touches a store or the network; [Host] does, in the session.
 */
object BurnPhases {

    /** A same-key roster account on this device. [onHome]: its home is the
     *  island the active account lives on, so no remote phase reached it. */
    class Sibling(val id: String, val uin: Int?, val host: String, val onHome: Boolean) {
        override fun toString(): String = "Sibling"
    }

    /** What Phases H and W need from the session. */
    interface Host {
        /** The duress view: its own decoy burn, nothing sent. The next number. */
        suspend fun burnDecoy(): Int?

        /** DELETE the active account on the home island, once. True when the
         *  island confirmed. Throws only on cancellation. */
        suspend fun deleteHome(): Boolean

        /** The same-key rows left on the home island once the active row is
         *  gone: stored tokens first, then recover-then-DELETE with our keys,
         *  which now resolves to those rows. */
        suspend fun burnHomeSiblings(): BurnCascade.IslandBurnResult

        fun wipeSibling(id: String, uin: Int?)

        /** Wipe the active account here and rebind. The next number, if any. */
        suspend fun eraseActive(): Int?
    }

    sealed class Outcome {
        /** [kept]: home-island siblings the island did not confirm, left on
         *  this device rather than wiped quietly. */
        data class Burned(val nextUin: Int?, val kept: List<Sibling> = emptyList()) : Outcome()

        /** The home island did not confirm: nothing local was touched. */
        object HomeFailed : Outcome()
    }

    /**
     * Phases H and W:
     *  - a decoy burns its own session and nothing else;
     *  - the home delete runs while every key and store is still readable,
     *    with one retry, and a failure there touches nothing local;
     *  - same-key rows on the home island are burned next. When the island
     *    does not confirm, those accounts stay here: wiping them would leave a
     *    live account that only the recovery phrase can delete, while the
     *    sheet had promised it was burned too;
     *  - siblings are wiped before the active account, because erasing the
     *    active one rebinds the session to the next roster entry, which must
     *    not be one about to be wiped.
     */
    suspend fun finish(decoy: Boolean, siblings: List<Sibling>, host: Host): Outcome {
        if (decoy) return Outcome.Burned(host.burnDecoy())
        if (!host.deleteHome() && !host.deleteHome()) return Outcome.HomeFailed
        val kept = if (siblings.any { it.onHome } && !host.burnHomeSiblings().isDone) {
            siblings.filter { it.onHome }
        } else {
            emptyList()
        }
        siblings.filter { s -> kept.none { it === s } }.forEach { host.wipeSibling(it.id, it.uin) }
        return Outcome.Burned(host.eraseActive(), kept)
    }

    /** Phase R, or nothing at all from a duress view. */
    suspend fun remote(
        decoy: Boolean,
        targets: List<BurnCascade.BurnTarget>,
        transport: BurnCascade.Transport,
    ): Map<String, BurnCascade.IslandBurnResult> =
        if (decoy) emptyMap() else BurnCascade.run(targets, transport)

    /**
     * The wipe PIN (P0.3): with the server flag off nothing is read for the
     * network and nothing is sent. With it on, the snapshot is taken into
     * memory, the local wipe runs to the end, and only then is [launch] handed
     * the snapshot, which must return at once ([BurnCascade.runDetached]).
     */
    suspend fun wipeLocalFirst(
        alsoServer: Boolean,
        snapshot: () -> BurnCascade.Snapshot,
        wipeLocal: suspend () -> Unit,
        launch: (BurnCascade.Snapshot) -> Unit,
    ) {
        val snap = if (alsoServer) snapshot() else null
        wipeLocal()
        if (snap != null) launch(snap)
    }
}
