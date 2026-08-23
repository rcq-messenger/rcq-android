package app.rcq.android.net

/**
 * The bookkeeping of a room-log drain (Stage 5 of the core-metadata plan),
 * shared by the primary drain (Session.drainGroupLog) and the foreign-mailbox
 * drain (Multihome): which seq to ack per room once a page went through
 * ingest, when a row that would not ingest is written off, and when a live
 * frame may be acked on its own. Pure, so the rules are checked on the JVM.
 */
object GroupLogPage {

    /** Drains in a row a row may fail to ingest THE SAME WAY before it is
     *  written off as unreadable on this device and acked past, so one bad
     *  row never silences a room. Small: a drain runs on every reconnect and
     *  push wake, and a row that fails three of those alike is not waiting
     *  on anything. */
    const val FAIL_DRAINS = 3

    /** The ack of one page. Rows are booked in seq order per room; every row
     *  is ingested whatever happened to the ones before it (the dedupe by
     *  message UUID makes that idempotent), only the ACK stops short of the
     *  first row of a room that is not done with. */
    class Acks {
        /** Max seq per room that may be acked. */
        val upto = HashMap<Int, Long>()
        /** Rooms whose ack is pinned below a row that failed this time. */
        val blocked = HashSet<Int>()

        /** [done]: persisted, held, a duplicate, dropped for good, or written
         *  off. False: the failure may pass on a later delivery. */
        fun row(gid: Int, seq: Long, done: Boolean) {
            if (!done) blocked.add(gid)
            if (gid !in blocked) upto[gid] = maxOf(upto[gid] ?: 0L, seq)
        }
    }

    /** One more failed ingest of a row that failed [why]. [prev] is what
     *  [encode] stored for it last time, if anything. A row that fails in a
     *  new way starts over. */
    data class Strike(val count: Int, val why: String) {
        val writtenOff: Boolean get() = count >= FAIL_DRAINS
    }

    fun strike(prev: String?, why: String): Strike {
        val p = prev?.split(':', limit = 2)
        val before = if (p != null && p.size == 2 && p[1] == why) p[0].toIntOrNull() ?: 0 else 0
        return Strike(before + 1, why)
    }

    fun encode(s: Strike): String = "${s.count}:${s.why}"

    /** The live-frame rule: a `gmsg` frame may be acked on its own only when
     *  it is the NEXT seq after what this device is level with. The island's
     *  ack moves the cursor forward to whatever is named, and a frame that
     *  lands on reconnect while the fetch for the gap is still in flight
     *  would otherwise move it past rows never read. A gap is left to the
     *  next drain, full stop: rows sealed to OTHER members share the room's
     *  seq axis and are never served here, so a gap proves nothing either
     *  way and nothing on this side can tell "a row I will never see" from
     *  "a row I was not handed yet". A room never fetched is left alone. */
    fun liveAckable(last: Long?, seq: Long): Boolean = last != null && seq == last + 1

    /** What a room is level with after a drain that ran to its end (the last
     *  page, more == false): the room's HEAD, not the last seq served, since
     *  every row this member can see up to the head went through ingest and
     *  the two differ as soon as one row in the room is sealed to somebody
     *  else. A room whose ack is pinned below a failed row stays where the
     *  ack left it. */
    fun levelAfterDrain(local: Long?, head: Long, blocked: Boolean): Long? =
        if (blocked) local else maxOf(local ?: 0L, head)
}
