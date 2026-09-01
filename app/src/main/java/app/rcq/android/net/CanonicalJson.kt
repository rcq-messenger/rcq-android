package app.rcq.android.net

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * The one canonical-JSON writer in this app.
 *
 * Byte-for-byte equal to Python `json.dumps(sort_keys=True,
 * separators=(",",":"), ensure_ascii=False)`, to iOS `JSONSerialization
 * [.sortedKeys, .withoutEscapingSlashes]` and to web-chat's `canonicalJSON`:
 * recursively sorted keys, compact separators, numbers written as their source
 * text, slashes and non-ASCII left alone, UTF-8.
 *
 * ⚠ It lives alone because these bytes are what signatures are computed over,
 * and every copy of a signing format is another chance to drift. There were
 * two already ([RelayConfigStore] and [RcqFederation], kept in step by hand
 * and by comment) and the `.rcq` site manifests wanted a third. A drift of one
 * character here does not read as a bug: it reads as every payload on one
 * platform suddenly being unsigned, which is indistinguishable from an attack
 * and is the kind of thing that costs a day to name.
 */
internal object CanonicalJson {

    fun string(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    fun bytes(e: JsonElement): ByteArray = string(e).toByteArray(Charsets.UTF_8)

    /** The usual shape: sign everything in [o] except the field holding the
     *  signature itself. Copies first, so the caller's object keeps its `sig`. */
    fun bytesWithout(o: JsonObject, except: String): ByteArray =
        bytes(o.deepCopy().also { it.remove(except) })

    private fun write(e: JsonElement, sb: StringBuilder) {
        when {
            e.isJsonObject -> {
                sb.append('{')
                e.asJsonObject.keySet().sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    writeString(k, sb); sb.append(':'); write(e.asJsonObject.get(k), sb)
                }
                sb.append('}')
            }
            e.isJsonArray -> {
                sb.append('[')
                e.asJsonArray.forEachIndexed { i, el -> if (i > 0) sb.append(','); write(el, sb) }
                sb.append(']')
            }
            e.isJsonNull -> sb.append("null")
            else -> {
                val p = e.asJsonPrimitive
                when {
                    p.isString -> writeString(p.asString, sb)
                    p.isBoolean -> sb.append(if (p.asBoolean) "true" else "false")
                    else -> sb.append(p.asString) // number: source text (no .0, no exponent)
                }
            }
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}
