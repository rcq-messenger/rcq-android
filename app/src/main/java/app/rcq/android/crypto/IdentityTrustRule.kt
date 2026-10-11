package app.rcq.android.crypto

/**
 * Which peer identity [SignalStores] trusts, and when a receive may move the
 * pin. Pure (no libsignal types), so the rules are checked on the JVM.
 *
 * ⚠ A SignalMessage opens only on a session state this store already holds,
 * and every state was built on an identity that was trusted when it was made
 * (pinned, admitted against the island, or island-served on our own send). Our
 * send path pins the identity of the bundle it builds from, so when the island
 * serves a new one (a restore on a second phone took the primary slot while
 * the first phone still runs), the pin moves and the first phone's replies on
 * the old session were refused as untrusted, struck and written off after a
 * few drains without the island ever being asked. Only a PreKeySignalMessage,
 * which builds a NEW state, has to clear the pin (or the island's vouch).
 */
object IdentityTrustRule {

    /**
     * [sending]: libsignal is building or using a session to send.
     * [established]: a SignalMessage is being opened on a state we hold.
     * [pinnedMatches]: null when nothing is pinned for the address, else
     * whether the identity is the pinned one. [admitted]: checked against the
     * island for this receive.
     */
    fun trusted(sending: Boolean, established: Boolean, pinnedMatches: Boolean?, admitted: Boolean): Boolean = when {
        sending -> true
        established -> true
        pinnedMatches == null -> true
        else -> pinnedMatches || admitted
    }

    /** Whether saving an identity on receive leaves the pin where it is: an
     *  established session's state (an archived one included) must not pull
     *  the pin back, or the pin would ping-pong and say "safety number
     *  changed" for nothing. A first identity is still pinned. */
    fun keepsPin(established: Boolean, hasPin: Boolean): Boolean = established && hasPin
}
