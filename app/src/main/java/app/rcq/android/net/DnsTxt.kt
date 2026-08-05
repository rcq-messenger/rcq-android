package app.rcq.android.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Reading a signed payload out of a DNS TXT record over DoH.
 *
 * The relay list reaches clients over exactly two names. Block both and no push
 * of ours arrives again — including the one that would hand out a working
 * mirror. A TXT record read over DoH does not depend on either name: it rides
 * a resolver that is reachable because half the internet needs it to be.
 *
 * A resolver cannot forge what it serves. The payload is signed, so a hostile or
 * compelled resolver can only withhold it or replay an old one, and replay is
 * what the version floor in [RelayConfigStore] is for. That is what makes it
 * acceptable to ask a DOMESTIC resolver, which is often the only one reachable
 * on the networks this exists for. What it does leak is that this device asked
 * for our name, so the query rides the tunnel when one is up, exactly like the
 * HTTPS mirrors do.
 *
 * Wire format is RFC 8484 rather than any resolver's JSON API, because the JSON
 * one is Cloudflare's and Google's alone — and those are the two most likely to
 * be unreachable precisely where this matters.
 */
object DnsTxt {

    /** Records we published carry this, so a name that also holds SPF or
     *  verification records yields ours without guessing. */
    private const val PREFIX = "rcq1:"

    private const val TYPE_TXT = 16
    private const val CLASS_IN = 1

    /** A DNS answer is small; anything larger is not one. */
    private const val MAX_RESPONSE = 64 * 1024

    private val DNS_MESSAGE = "application/dns-message".toMediaType()

    /**
     * Fetch and reassemble the payload published at [name], or null.
     *
     * Tries each resolver in turn. A single TXT record's strings arrive in
     * order, which is why the whole payload goes in ONE record: order ACROSS
     * records is not guaranteed by DNS, so a payload split over several could
     * reassemble into garbage.
     */
    fun fetch(name: String, resolvers: List<String>, client: OkHttpClient): String? {
        val query = buildQuery(name) ?: return null
        for (resolver in resolvers) {
            val body = runCatching {
                val req = Request.Builder()
                    .url(resolver)
                    .header("Accept", "application/dns-message")
                    .post(query.toRequestBody(DNS_MESSAGE))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body?.byteStream()?.readNBytes(MAX_RESPONSE)
                }
            }.getOrNull() ?: continue
            val payload = runCatching { parseTxt(body) }.getOrNull() ?: continue
            if (payload != null) return payload
        }
        return null
    }

    /** A minimal query: one question, recursion desired, ID zero as RFC 8484
     *  asks (a cached DoH response must not be keyed on a random id). */
    internal fun buildQuery(name: String): ByteArray? {
        val labels = name.trim('.').split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        val out = ArrayList<Byte>(32 + name.length)
        fun u16(v: Int) { out.add((v shr 8).toByte()); out.add(v.toByte()) }
        u16(0)          // ID
        u16(0x0100)     // RD
        u16(1)          // QDCOUNT
        u16(0); u16(0); u16(0)
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        out.add(0)
        u16(TYPE_TXT)
        u16(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * Pull our payload out of a DNS response, or null when it is not there.
     *
     * Every failure is a null rather than an exception: this parses bytes from a
     * resolver we do not control, on a path whose whole purpose is to be tried
     * when other things are already broken, and the caller's next move is to try
     * the next source.
     */
    internal fun parseTxt(msg: ByteArray): String? {
        if (msg.size < 12) return null
        fun u16(at: Int): Int {
            if (at + 1 >= msg.size) return -1
            return ((msg[at].toInt() and 0xFF) shl 8) or (msg[at + 1].toInt() and 0xFF)
        }
        val answers = u16(6)
        if (answers <= 0) return null

        // Step over the question section.
        var pos = 12
        val questions = u16(4)
        repeat(questions) {
            pos = skipName(msg, pos) ?: return null
            pos += 4
        }

        repeat(answers) {
            pos = skipName(msg, pos) ?: return null
            if (pos + 10 > msg.size) return null
            val type = u16(pos)
            val rdLength = u16(pos + 8)
            pos += 10
            if (rdLength < 0 || pos + rdLength > msg.size) return null
            if (type == TYPE_TXT) {
                // Character-strings, each length-prefixed. Concatenated in the
                // order the record carries them.
                val sb = StringBuilder()
                var p = pos
                val end = pos + rdLength
                while (p < end) {
                    val len = msg[p].toInt() and 0xFF
                    if (p + 1 + len > end) return null
                    sb.append(String(msg, p + 1, len, Charsets.US_ASCII))
                    p += 1 + len
                }
                val text = sb.toString()
                if (text.startsWith(PREFIX)) return text.removePrefix(PREFIX)
            }
            pos += rdLength
        }
        return null
    }

    /** Advance past a NAME, which may end in a compression pointer. Returns null
     *  on a malformed one rather than following it, since a pointer chain from
     *  an untrusted response is a loop waiting to happen. */
    private fun skipName(msg: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= msg.size) return null
            val len = msg[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return if (pos + 2 <= msg.size) pos + 2 else null
                len > 63 -> return null
                else -> pos += 1 + len
            }
        }
    }
}
