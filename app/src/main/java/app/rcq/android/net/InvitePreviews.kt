package app.rcq.android.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Invite-card previews for group links: the chat's join card, every chip of a
 * pinned message, the Add sheet (#918, #1051).
 *
 * ⚠⚠ A PIN IS A BURST. A chat draws a join card only when its bubble scrolls
 * into view, a few at a time; the pin sheet composed a chip for every link at
 * once, and the pin of "ГЛАВНОЕ МЕНЮ" holds twenty. Twenty previews in the same
 * instant, over a relay more often than not, against an island that allows
 * thirty a minute per person (`GET /groups/{id}/preview`, a limit that exists
 * because group ids are sequential and the route is how a scraper would walk
 * them). #918 memoised the successes; a failure still came back null, null
 * drew the placeholder, and nothing ever asked again, so a cold start that
 * opened the sheet twice, or one bad moment on the relay, turned the whole
 * list into twenty identical "Группа" rows (#1051).
 *
 * So: successes are kept for the process, per island; at most [parallel]
 * requests are in flight at once; and a 429 is believed. Its Retry-After is
 * remembered for that island and every preview waits it out instead of
 * spending another request to be refused again. A failure is still not
 * cached (one bad minute must not poison a card for the life of the process);
 * the card asks again, see `rememberInvitePreview` in ChatScreen.kt. The one
 * answer that asking again cannot change is a 404 (the room is gone, or it is
 * closed to us): that link is not asked about again for [GONE_MS], so a pin
 * with dead links does not spend the budget its live ones need.
 */
class InvitePreviews(
    private val parallel: Int = 3,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, RcqApi.GroupPreviewOut>()
    private val gate = Semaphore(parallel)
    /** Per island ("" = our own): when its rate limit is expected to let us
     *  in again. */
    private val coolUntil = ConcurrentHashMap<String, Long>()
    /** Per link: a 404, not worth another request until then. */
    private val goneUntil = ConcurrentHashMap<String, Long>()

    private fun key(island: String, id: Int) = "$island/$id"

    fun cached(island: String, id: Int): RcqApi.GroupPreviewOut? = cache[key(island, id)]

    /** Account switch: a card can differ per viewer (the avatar pair goes to
     *  members only), and a cool-down belongs to the identity that earned it. */
    fun clear() {
        cache.clear()
        coolUntil.clear()
        goneUntil.clear()
    }

    suspend fun get(island: String, id: Int, fetch: suspend () -> RcqApi.GroupPreviewOut): RcqApi.GroupPreviewOut? {
        val k = key(island, id)
        cache[k]?.let { return it }
        if ((goneUntil[k] ?: 0L) > now()) return null
        // Waited OUTSIDE the gate: one island refusing us must not hold the
        // permits the other islands' cards are queued for.
        val wait = (coolUntil[island] ?: 0L) - now()
        if (wait > 0) delay(wait)
        return gate.withPermit {
            // A twin card (the bubble and the chip of the same link) may have
            // fetched it while this one queued.
            cache[k] ?: try {
                fetch().also { cache[k] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                retryAfterMs(e)?.let { coolUntil[island] = now() + it }
                if (e.message?.startsWith("HTTP 404") == true) goneUntil[k] = now() + GONE_MS
                null
            }
        }
    }

    companion object {
        const val GONE_MS = 10 * 60_000L

        /** How long a refusal asks us to wait, or null when it was not a 429.
         *  RcqApi throws `HTTP 429: <body>` and the island's body carries
         *  `"retry_after": N` (seconds); a 429 without it still waits a little,
         *  and nothing waits longer than the island's own window. */
        fun retryAfterMs(e: Throwable): Long? {
            val msg = e.message ?: return null
            if (!msg.startsWith("HTTP 429")) return null
            val secs = Regex("\"retry_after\"\\s*:\\s*(\\d+)").find(msg)?.groupValues?.get(1)?.toLongOrNull() ?: 10L
            return secs.coerceIn(1L, 60L) * 1000L
        }
    }
}
