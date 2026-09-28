package app.rcq.android.data

/**
 * Whom a `pkeyask` may go to from a ROOM ROSTER: a member whose picture we
 * cannot open, when asking has any chance of an answer.
 *
 * ⚠⚠ ONLY AN ACCEPTED CONTACT ON OUR OWN ISLAND. That is not our rule, it is
 * the answerer's: every client hands its profile key to "every accepted
 * contact ... nobody else" (docs/profile-key-design.md). Android's ingest
 * refuses an asker outside its roster, the web's `entitledToMyProfileKey`
 * does the same, and iOS gates on its roster. So an ask to a room member who
 * is not a contact is refused by construction, every time, and the face stays
 * a lettered tile whether we asked or not.
 *
 * Asking anyway was not free. The member mapper (e4d6324, 0.204) asked every
 * member with a picture, and a seal to somebody outside the roster first reads
 * their card and device list from the island. In the 2271-member beta room that
 * was 22 to 29 card reads plus as many device lists, all at once, every time a
 * phone mapped the roster after a cold start. Those bursts are what exhausted
 * the island's database pool (28.09: 24 stalls of 2 to 11 minutes in 30 days;
 * 35 of the 36 pool-exhaustion episodes since 20.09 began 20 s after somebody
 * fetched `/groups/21`).
 *
 * A contact in the room keeps the ask: their row in the contacts mapper asks
 * too, and the shared six-hour throttle makes it one ask either way.
 */
object ProfileKeyAsk {

    /** [roomHost] is the room's island, null for our own. [sameIslandContacts]
     *  is the accepted roster's numbers with cross-island rows left out: the
     *  same digits on another island are a different person, and the answerer
     *  tests exactly that. */
    fun worthAsking(memberUin: Int, roomHost: String?, sameIslandContacts: Set<Int>): Boolean =
        roomHost == null && memberUin in sameIslandContacts
}
