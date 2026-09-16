package app.rcq.android.crypto

/**
 * What a frame delivered as a GROUP post may carry (spec 2026-09-15, section 7).
 *
 * The island cannot tell a group post from a payload one member deposited for
 * exactly one other member: `POST /messages/group-sealed` accepts any subset of
 * the roster and never checks who sent it. So a guest in a room, or anybody in
 * it, can address a single member through the room. The client closes what the
 * island cannot: group frames render only inside their group, and the kinds
 * that only mean something between two people are dropped here and never acted
 * on, whatever room they arrive through.
 *
 * Pure, so `GroupFrameKindDropTest` pins every kind.
 */
object GroupFrameRule {

    /** True for a kind that belongs to a 1:1 conversation and must never be
     *  acted on when it arrives inside a group frame. */
    fun oneToOneOnly(env: Envelope): Boolean = when (env) {
        // §5f consent, and its answer told to our own devices.
        is Envelope.ContactRequest,
        is Envelope.CiAck,
        // Profile keys and the ask for one, §5e profile refresh.
        is Envelope.PKey,
        is Envelope.PKeyAsk,
        is Envelope.ProfileUpdate,
        // Presence ping and call signalling (§5d).
        is Envelope.Visit,
        is Envelope.CallSignal,
        // Our own devices' sync: carbons, the read mark inside them, the
        // home-record self push.
        is Envelope.Carbon,
        is Envelope.ReadMark,
        is Envelope.HomeRecord,
        // Room keys ride 1:1 sealed only, and the screen toggles are per chat.
        is Envelope.GsKey,
        is Envelope.GsKnack,
        is Envelope.SecureScreen,
        is Envelope.ScreenshotTaken,
        -> true
        else -> false
    }
}
