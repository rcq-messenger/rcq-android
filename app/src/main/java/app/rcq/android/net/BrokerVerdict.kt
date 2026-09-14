package app.rcq.android.net

import com.google.gson.JsonParser

/**
 * What the broker made of the relay access key, or why it could not be asked.
 *
 * Four answers, and the fourth is the point of this object: a round trip that
 * never reached the island, or that the island answered with something that is
 * not about the key at all, is [OFFLINE], never a bad key. Before this existed
 * the app read "no verdict" as "not one of ours", deleted the key and told the
 * person to check their typing while their network was down. Same semantics as
 * the desktop's broker.rs, which answers "offline" for a failed fetch.
 *
 * ⚠ The rate limit is part of that: the broker allows 30 asks per minute per
 * client address, and through the relay tunnel the address is the RELAY's,
 * shared with everybody else riding it. A 429 says nothing about the key.
 *
 * Only an explicit [UNKNOWN] or [EXPIRED] from the island (a 2xx whose `key`
 * field says so, or a 2xx that does not judge the key at all) may remove or
 * flag the key. Pure, so the rule is checked on the JVM.
 */
object BrokerVerdict {
    const val OK = "ok"
    const val UNKNOWN = "unknown"
    const val EXPIRED = "expired"
    /** The question never got an answer about the key. The key is kept and
     *  asked about again at the next refresh. */
    const val OFFLINE = "offline"

    /** The verdict for one attempt.
     *
     *  @param status the HTTP status, or null when there was no HTTP answer at
     *    all (timeout, DNS, connection refused, a thrown exception).
     *  @param keyField the `key` field of a 2xx body, null when absent.
     *  @param keySent whether a key rode on the request. With none, there is
     *    nothing to judge and the verdict is null, as it always was.
     */
    fun of(status: Int?, keyField: String?, keySent: Boolean): String? {
        if (!keySent) return null
        if (status == null || status !in 200..299) return OFFLINE
        return when (keyField) {
            OK, EXPIRED -> keyField
            // The island answered but did not say "ok": a broker that does not
            // recognise the key, or one too old to judge keys at all. Either
            // way the key does not work here, and that is what "unknown" says.
            else -> UNKNOWN
        }
    }

    /** [of], reading the `key` field out of a raw body. A body that is not
     *  JSON at all (a proxy's error page with a 200 on it) is no answer. */
    fun ofResponse(status: Int?, body: String?, keySent: Boolean): String? {
        if (!keySent) return null
        if (status == null || status !in 200..299 || body == null) return OFFLINE
        val field = runCatching {
            JsonParser.parseString(body).asJsonObject.get("key")?.takeIf { !it.isJsonNull }?.asString
        }.getOrElse { return OFFLINE }
        return of(status, field, keySent = true)
    }

    /** Whether this verdict is the island's own word that the key is no good.
     *  Only then may the key be dropped or the person told to check it. */
    fun removesKey(verdict: String?): Boolean =
        verdict != null && verdict != OK && verdict != OFFLINE
}
