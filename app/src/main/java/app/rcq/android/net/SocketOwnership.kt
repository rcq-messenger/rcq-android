package app.rcq.android.net

/**
 * Whether a callback still speaks for the object that registered it.
 *
 * A Session holds ONE socket in a field and rebuilds it on every route change
 * and account switch. The socket it replaced does not know that: OkHttp still
 * delivers its close, and a socket that was orphaned rather than closed keeps
 * redialling and keeps firing onState/onEvent into closures that write the
 * Session's connection dot and feed its event handler. Those closures ask
 * this before writing anything: the socket that fired is compared, by
 * identity, with the socket the Session holds NOW.
 *
 * Generic and free of Android so the rule can be checked on the JVM.
 *
 * @param live reads the field as it is at the moment of the callback, never a
 *   copy taken when the callback was registered (that copy is the very thing
 *   that goes stale).
 */
class SocketOwnership<T : Any>(private val live: () -> T?) {

    /** True while [issued] is the object the owner holds now. */
    fun stillOwns(issued: T): Boolean = live() === issued

    /** Run [block] only while [issued] is still owned; null when it is not. */
    fun <R> ifOwned(issued: T, block: () -> R): R? = if (stillOwns(issued)) block() else null
}
