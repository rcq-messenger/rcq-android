package app.rcq.android.data

/**
 * What somebody actually pasted into a field that asks for an access code.
 *
 * ⚠⚠ THE APP HANDS OUT A LINK AND THEN ASKS FOR A CODE. The invite sheet shows
 * `rcq://server/<host>?invite=<code>` with a "copy the link" button, and the
 * join field is labelled "access code" and refuses anything but the code
 * itself. The island then answers "already used, expired, or meant for another
 * island", which is three wrong guesses at once. It cost us the first person a
 * paying resident ever invited (#1034): he pasted exactly what the app gave
 * him.
 *
 * So the field takes either. Pure and tiny on purpose: it is the kind of rule
 * that has to be provable without a phone, and it runs on a string a stranger
 * chose.
 */
object InviteCode {

    /** Longest thing we will treat as a code. The island's own are 22-24
     *  characters; the cap is only here so a pasted novel is not carried
     *  around in memory. */
    private const val MAX = 512

    /**
     * The code inside [input], or the trimmed input when there is no link in
     * it.
     *
     * Handles what people actually paste: the `rcq://server/...?invite=CODE`
     * the sheet copies, an `https://rcq.app/...?invite=CODE` from a chat, a
     * link with more parameters after it, and a bare code with stray spaces or
     * a trailing full stop from a sentence. Returns null for nothing usable,
     * so a caller can keep its button disabled.
     */
    fun of(input: String?): String? {
        val raw = input?.trim()?.take(MAX).orEmpty()
        if (raw.isEmpty()) return null
        val marker = raw.indexOf("invite=", ignoreCase = true)
        // ⚠ Only the wrappers a paste carries, never "any punctuation": an
        // operator's own code may legitimately start with one, and a quote or
        // a bracket cannot.
        val body = (if (marker >= 0) raw.substring(marker + "invite=".length) else raw)
            .trimStart('"', '\'', '(', '[', '{', '<', '\u00ab')
        // A query parameter ends at the next separator; a pasted line may also
        // carry whitespace or a quote from wherever it was copied.
        val code = body.takeWhile { it != '&' && it != '#' && !it.isWhitespace() && it != '"' && it != '\'' && it != '<' }
            // Only from the END: a code never starts with punctuation, and
            // trimming both ends would eat a leading character of a code that
            // legitimately began with one.
            .trimEnd('.', ',', ')', ']', '}', ';', ':')
        val decoded = runCatching { java.net.URLDecoder.decode(code, "UTF-8") }.getOrDefault(code)
        return decoded.takeIf { it.isNotEmpty() }
    }

    /** True when [input] carries a link rather than a bare code, so a screen
     *  can say "that is the link, here is the code" instead of silently
     *  changing what somebody typed. */
    fun looksLikeLink(input: String?): Boolean {
        val raw = input?.trim().orEmpty()
        return raw.contains("invite=", ignoreCase = true) ||
            raw.startsWith("rcq://", ignoreCase = true) ||
            raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
    }
}
