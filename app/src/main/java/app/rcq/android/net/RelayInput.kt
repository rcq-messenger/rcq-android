package app.rcq.android.net

/**
 * What did the user just paste into "Add relay"?
 *
 * One field takes two different things, because from the outside they are the
 * same act: somebody handed me a string and I want the app to use it. A
 * contact shares `rcq-relay://…`, which is ONE node; the cabinet hands out an
 * access key, which unlocks a SET. The cabinet's own instructions point at this
 * exact field, so refusing the key it just issued would be the product
 * contradicting itself.
 *
 * Lives here, outside the composable, so the decision can be tested. It used to
 * be three branches inline in a dialog, where the only way to check it was to
 * paste things by hand.
 */
sealed class RelayInput {
    /** A single shared node. */
    data class Link(val relay: SingBoxTransport.Relay) : RelayInput()

    /** A paid tenant key. Whether it is a GOOD key is not knowable here — only
     *  the broker can say — so this classification is deliberately about SHAPE
     *  and the answer comes from the refresh that follows. */
    data class AccessKey(val key: String) : RelayInput()

    object Unusable : RelayInput()

    companion object {
        /** Shorter than this is a typo, not a secret. The keys the console
         *  issues are 32 characters of base64url; 16 leaves room for a
         *  different issuer without letting "abc" through. */
        const val MIN_KEY_LENGTH = 16

        fun classify(raw: String): RelayInput {
            val s = raw.trim()
            if (s.isEmpty()) return Unusable
            // A link is tried FIRST and on its own terms: a malformed
            // rcq-relay:// must not fall through and get stored as an access
            // key, or a typo in a shared node silently becomes a dead
            // subscription the user cannot explain.
            ContactRelayStore.relayFromToken(s)?.let { return Link(it) }
            if (s.contains("://")) return Unusable
            return if (s.length >= MIN_KEY_LENGTH) AccessKey(s) else Unusable
        }
    }
}
