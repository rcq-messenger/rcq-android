package app.rcq.android.net

import java.io.IOException

/**
 * The account's copies on OTHER islands (§5c guest registrations and backup
 * homes) and the one account action this client repeats on them: the
 * nickname (#985(2), first half).
 *
 * Islands never talk to each other, so `PUT /users/me` on the home island
 * changes only the home row, and a room on island B keeps showing the name the
 * guest row was registered with. This client holds a token for every copy it
 * made, which makes it the only party that can repeat the change there.
 *
 * ⚠ Only islands in THIS account's own stores are ever written to, never an
 * island learned from a peer, and only the nickname is sent: B already shows
 * that name to the room, so nothing new is disclosed.
 */
object GuestCopies {

    enum class Source { VISITED, BACKUP }

    /** One copy of the account: our number and token on [host]. */
    data class Target(val host: String, val uin: Int, val jwt: String, val source: Source)

    /** Every island to push to, once per host. A host that is both a visited
     *  island and a backup home is the same row there (same signing key, same
     *  recover), so it is written once, through the visited entry. The primary
     *  island and anything in [skipHost] (the front, which is the primary by
     *  another road) are left out: the session's own api already covered them. */
    fun targets(
        visited: List<VisitedIslandsStore.Visited>,
        backups: List<MultihomeStore.Home>,
        ownHost: String,
        skipHost: (String) -> Boolean = { false },
    ): List<Target> {
        val all = visited.map { Target(it.host, it.uin, it.jwt, Source.VISITED) } +
            backups.map { Target(it.host, it.uin, it.jwt, Source.BACKUP) }
        return all
            .filter { it.host.isNotBlank() && !it.host.equals(ownHost, ignoreCase = true) && !skipHost(it.host) }
            .distinctBy { it.host.lowercase() }
    }

    /**
     * `PUT /users/me {nickname}` on [t]'s island with its token. A 401 refreshes
     * the token through recover once (same challenge as every other foreign
     * mailbox); [stillOurs] is asked before the fresh token is written, because
     * the stores behind [onFreshCreds] are singletons re-pointed on an account
     * switch. Best effort: returns whether the island took the name, never
     * throws.
     */
    suspend fun pushNickname(
        t: Target,
        nickname: String,
        signingPriv: ByteArray,
        signingPub: ByteArray,
        stillOurs: () -> Boolean,
        onFreshCreds: (Target, RcqApi.RegisterResponse) -> Unit,
    ): Boolean = runCatching {
        if (!stillOurs()) return@runCatching false
        val api = RcqApi("https://${t.host}").apply { setToken(t.jwt) }
        val body = RcqApi.UpdateMeBody(nickname = nickname)
        try {
            api.updateMe(body)
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP 401") != true) throw e
            if (!stillOurs()) return@runCatching false
            val fresh = Multihome.recoverOn(t.host, signingPriv, signingPub) ?: return@runCatching false
            if (!stillOurs()) return@runCatching false
            onFreshCreds(t, fresh)
            api.setToken(fresh.token)
            api.updateMe(body)
        }
        true
    }.onFailure {
        android.util.Log.w("RCQfed", "nickname push ${t.host}: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)
}
