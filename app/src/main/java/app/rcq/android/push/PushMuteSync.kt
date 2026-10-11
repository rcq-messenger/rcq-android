package app.rcq.android.push

/**
 * What this device tells the island about its mutes, and when.
 *
 * The island keeps ONE pair of lists per number (`muted_uins`,
 * `muted_group_ids`) and replaces each list wholesale on a PUT. Every install
 * of the account writes the same pair: a phone, a second phone, an iPhone.
 *
 * ⚠⚠ REPORT 1072, cause 2. Android PUT its whole local set on every start, on
 * every PIN unlock and on every account switch, because the collector that
 * drives it replays the current value on subscribe. Whatever another install
 * had muted was therefore taken back the next time this one opened, and the
 * person saw mutes "reset themselves" on the other device for no reason they
 * could find. Two rules end it:
 *
 *  - Speak only after a change made HERE. The set last delivered is kept per
 *    account ([sent]); a start that finds the same set says nothing at all.
 *  - When there is something to say, say only that. The island's current lists
 *    are read fresh, this device's additions go in and its removals come out,
 *    and everything else on them (another install's mutes) stays.
 *
 * Pure, so the rules are pinned in a test rather than on two phones.
 */
object PushMuteSync {

    // Thread keys as LocalStores mints them (peerThread / groupThread), spelled
    // out so this file loads without it.
    private const val PEER = "peer:"
    private const val GROUP = "group:"

    /** Only the keys the island can act on: a number or a room OF ITS OWN.
     *  Anything that does not parse stays out, as it did from the old full-list
     *  PUT.
     *
     *  ⚠ That rule is also what keeps other islands out of these lists. A mute
     *  of somebody on another island is `peer:<uin>@<host>`
     *  ([app.rcq.android.data.LocalStores.peerMuteKey]): the number alone would
     *  silence whoever holds it HERE. A room on another island has a negative
     *  local alias for an id, which names no room here. Neither parses as a
     *  positive id, so neither goes up, and one that went up from an older
     *  build is taken back as a removal by [merge]. */
    fun syncable(local: Set<String>): Set<String> = local.filterTo(LinkedHashSet()) { key ->
        when {
            key.startsWith(PEER) -> (key.removePrefix(PEER).toIntOrNull() ?: 0) > 0
            key.startsWith(GROUP) -> (key.removePrefix(GROUP).toIntOrNull() ?: 0) > 0
            else -> false
        }
    }

    /** True when there is nothing new to tell the island: the set it last
     *  received from here is the set we hold now. */
    fun upToDate(local: Set<String>, sent: Set<String>?): Boolean = sent != null && sent == local

    /** The island's two lists as thread keys. A list the island did not send
     *  (an older island) is empty. */
    fun serverThreads(uins: List<Int>?, groupIds: List<Int>?): Set<String> =
        LinkedHashSet<String>().apply {
            uins.orEmpty().forEach { add("$PEER$it") }
            groupIds.orEmpty().forEach { add("$GROUP$it") }
        }

    /**
     * What the island should hold once this device's change is applied to what
     * it holds now ([server]).
     *
     * [sent] null means this install has never delivered a set for the account
     * (first run after the update, a restore): everything muted here goes in,
     * since the island may never have received it. Taking a mute back is
     * something only a recorded removal may do.
     *
     * [legacy] is where removals are counted from until the first set is
     * delivered: the set this install held the first time it tried to sync
     * after the update, which is what older builds PUT on every start. ⚠ The
     * two baselines are kept apart on purpose. Without [legacy], an unmute made
     * before the first sync got through (offline at start, an island that did
     * not answer) was no removal at all: the island kept the mute, the snapshot
     * was then saved without it, and that chat never woke the phone again
     * while the app showed it unmuted. Seeding [sent] instead would have
     * stopped the first sync adding anything.
     */
    fun merge(local: Set<String>, sent: Set<String>?, server: Set<String>, legacy: Set<String>? = null): Set<String> {
        val added = local - sent.orEmpty()
        val removed = (sent ?: legacy).orEmpty() - local
        return (server + added) - removed
    }

    fun uinsOf(threads: Set<String>): List<Int> =
        threads.mapNotNull { if (it.startsWith(PEER)) it.removePrefix(PEER).toIntOrNull() else null }.sorted()

    fun groupIdsOf(threads: Set<String>): List<Int> =
        threads.mapNotNull { if (it.startsWith(GROUP)) it.removePrefix(GROUP).toIntOrNull() else null }.sorted()
}
