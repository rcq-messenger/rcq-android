package app.rcq.android.net

/**
 * Which islands a key rotation has to reach, and what each answer means
 * (spec 2026-09-15, F3; report #986 third item).
 *
 * Changing the recovery phrase used to rewrite the keys on the HOME island and
 * stop there. Every copy of the identity on another island — a backup home, a
 * guest copy made to join one room — kept the old keys, so the old phrase still
 * opened them: the very thing somebody changing their phrase is trying to stop.
 *
 * Pure on purpose: planning and classification are the parts worth proving
 * without a phone, and the network half in [app.rcq.android.Session] is then
 * small enough to read in one sitting.
 */
object ReissueCascade {

    /** One copy to rotate: the island, our number THERE (the proof names it),
     *  and the token this device already holds for it, if any. */
    data class Target(
        val host: String,
        val uin: Int?,
        val token: String?,
        /** A backup home, as opposed to an island only visited for a room. The
         *  order matters: a backup is where a lost home is recovered FROM, so
         *  it is rotated first. */
        val backup: Boolean,
    )

    /** What one island's answer means for the copy there. */
    enum class Outcome {
        /** Rotated, or already on the new key. Nothing more to do. */
        DONE,

        /** No copy here any more: the row is gone, nothing to rotate. As good
         *  as done, and recorded as such rather than as a failure, because a
         *  deleted copy cannot be opened by the old phrase either. */
        GONE,

        /** A copy exists and is NOT ours to rotate: it holds a key that is
         *  neither the old one nor the new one. Never retried automatically —
         *  it is somebody else's row or a copy rotated from another device, and
         *  hammering it proves nothing. */
        DIFFERENT_KEY,

        /** The island could not be reached or refused for a reason that heals:
         *  a timeout, a 5xx, a rate limit. Worth another try. */
        UNREACHABLE,
    }

    /**
     * Every island that holds a copy, deduped by host, backups first.
     *
     * ⚠ [ownHost] is dropped: the home island is rotated by the caller before
     * the cascade starts, and rotating it twice would burn the second proof's
     * nonce against a key the island no longer holds. A front host is dropped
     * too: it is a way IN to an island, not an island with an account on it.
     */
    fun plan(
        backups: List<Triple<String, Int?, String?>>,
        visited: List<Triple<String, Int?, String?>>,
        ownHost: String,
        skipHost: (String) -> Boolean = { false },
    ): List<Target> {
        val own = ownHost.trim().lowercase()
        val out = LinkedHashMap<String, Target>()
        fun add(rows: List<Triple<String, Int?, String?>>, backup: Boolean) {
            rows.forEach { (host, uin, token) ->
                val h = host.trim().lowercase()
                if (h.isEmpty() || h == own || skipHost(h)) return@forEach
                val existing = out[h]
                if (existing == null) {
                    out[h] = Target(h, uin, token?.takeIf { it.isNotEmpty() }, backup)
                } else if (existing.token.isNullOrEmpty() && !token.isNullOrEmpty()) {
                    // Two rows for one island (a backup and a visited entry):
                    // keep the one that carries a token, and stay a backup if
                    // either row said so.
                    out[h] = existing.copy(token = token, backup = existing.backup || backup)
                } else if (backup && !existing.backup) {
                    out[h] = existing.copy(backup = true)
                }
            }
        }
        add(backups, backup = true)
        add(visited, backup = false)
        return out.values.sortedByDescending { it.backup }
    }

    /**
     * What an island's refusal means. [code] is the island's own error code
     * when it sent one (`GuestPath` style: the code inside the JSON body),
     * [status] the HTTP status, and null status means the request never
     * arrived.
     *
     * ⚠ `old_key_mismatch` is NOT a failure on its own: the island refusing
     * because the key we signed with is not the one it holds is exactly what a
     * copy that was ALREADY rotated (from another device, or by a retry whose
     * reply was lost) looks like. The caller checks with the NEW key before
     * believing this, which is why it maps to DIFFERENT_KEY and not to done.
     */
    fun classify(status: Int?, code: String?): Outcome = when {
        status == null -> Outcome.UNREACHABLE
        status in 200..299 -> Outcome.DONE
        code == "identity_not_found" || code == "user_not_found" || status == 404 -> Outcome.GONE
        code == "old_key_mismatch" || status == 409 -> Outcome.DIFFERENT_KEY
        code == "bad_signature" || status == 403 -> Outcome.DIFFERENT_KEY
        status == 401 -> Outcome.UNREACHABLE          // a stale token, not a stale key
        status == 429 || status >= 500 -> Outcome.UNREACHABLE
        else -> Outcome.UNREACHABLE
    }

    /** The HTTP status out of an [java.io.IOException] message, which is how
     *  every call in RcqApi reports a refusal: `HTTP 409: {"detail":...}`.
     *  Null when the request never got an answer at all. */
    fun statusOf(message: String?): Int? {
        val m = Regex("""HTTP (\d{3})""").find(message.orEmpty()) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** The island's own error code out of the same message, or null. Read with
     *  a regex rather than a JSON parse on purpose: the body may be a proxy's
     *  HTML, and a rotation must not fall over on the shape of an error. */
    fun codeOf(message: String?): String? =
        Regex(""""code"\s*:\s*"([a-z_]{1,64})"""").find(message.orEmpty())?.groupValues?.get(1)

    /** [classify] straight from what an RcqApi call threw. */
    fun classify(message: String?): Outcome = classify(statusOf(message), codeOf(message))

    /** True when every island is settled, so the old signing key may be
     *  destroyed. A copy nobody could reach keeps the old key alive: without
     *  it that island can never be rotated, and the old phrase keeps opening
     *  the copy there for ever. */
    fun settled(outcomes: Collection<Outcome>): Boolean =
        outcomes.all { it == Outcome.DONE || it == Outcome.GONE }
}
