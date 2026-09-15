package app.rcq.android.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException

/**
 * Which island the "keep a backup on another island" toggle may register on,
 * and what to do with each island it asks. Pure, so it is checked on the JVM;
 * [Multihome.autoAddBackup] supplies the network.
 *
 * ⚠⚠ REPORT #988. The pick used to ask each catalogue island one question,
 * `/health`, and the flagship answers that perfectly well. But since 09.09 the
 * flagship sells entry (`registration_policy = "paid"`), so a person living on
 * is2 tapped the toggle, the only other island in the signed catalogue was
 * chosen, and `/auth/register` answered `403 entry_required`. The toggle then
 * printed that JSON body under itself. A healthy island is not the same thing
 * as an island that will take a stranger, and the pick has to ask both.
 *
 * ⚠ The rule below is the SAME on Android, web/desktop and iOS
 * (`backup-pick.ts`, `CrossIslandLogic.swift`). Change it in all three or not
 * at all: a cross-client review found them deciding the same island three
 * different ways.
 *
 *  1. Probe. One candidate at a time, in catalogue order: `/health` first, and
 *     `/server/info` only once that host's `/health` succeeded. Redirects off,
 *     an overall deadline per request ([probeDeadlineSeconds]), asked fresh on
 *     every tap. A redirect, a timeout, a network error, a non-2xx, or a
 *     `/server/info` body over [INFO_BODY_CAP], not valid UTF-8 or not a
 *     strict JSON object: [Probe.SILENT].
 *  2. Door. [Probe.OPEN] when `registration_policy` is "open" or missing and
 *     `closed_island` is not true; [Probe.SHUT] otherwise. The entry price is
 *     not part of it. A present field of the wrong type is SHUT, and a JSON
 *     null is a wrong type: only a field that is not there at all is missing.
 *  3. Action. OPEN: register as a manual add does, which recovers first. Any
 *     failure there, a door refusal included, moves on without asking that
 *     island again. SHUT: recover only, adopt an existing copy of this account
 *     if the island has one. SILENT: nothing. Each host at most once, stop at
 *     the first backup.
 *  4. Relays. Only when every candidate was SILENT, or the signed catalogue
 *     itself could not be fetched or verified. A verified catalogue that
 *     leaves nobody to ask is not silence.
 *  5. Words. Nothing answered: [NO_ISLAND]. Something answered, or nobody was
 *     left to ask, and no backup: [NO_OPEN_ISLAND]. See [sentenceOf] for the
 *     rest of the screen.
 */
object BackupIslandPick {
    /** The three door codes `auth.register` refuses with, all as a 403. A
     *  backup registration never carries a code, so `invite_invalid` cannot
     *  really come back, but it is the same door if it ever does. */
    const val ENTRY_REQUIRED = "entry_required"
    const val INVITE_REQUIRED = "invite_required"
    const val INVITE_INVALID = "invite_invalid"

    /** The auto toggle's two failures, as the message the UI maps to a string.
     *  [NO_ISLAND] is the old one and still means "nothing answered at all";
     *  [NO_OPEN_ISLAND] is new and means islands answered and none of them
     *  gave a backup. They need different sentences: the first is a network
     *  problem, the second is not, and "try again later" is untrue for it. */
    const val NO_ISLAND = "no_island"
    const val NO_OPEN_ISLAND = "no_open_island"

    /** The most of a `/server/info` answer accepted. It is a few hundred
     *  bytes; a longer one is REJECTED whole (silence), never cut and parsed. */
    const val INFO_BODY_CAP = 64L * 1024

    /** The overall deadline of one probe request: connect, headers and body
     *  together, not an idle timeout. Longer over the relays, where one request
     *  easily takes more than a direct one is allowed. A probe past it is
     *  silence, never a shut door. Same numbers on every client. */
    const val PROBE_DEADLINE_DIRECT_SECONDS = 6L
    const val PROBE_DEADLINE_RELAYED_SECONDS = 15L

    fun probeDeadlineSeconds(relayed: Boolean) =
        if (relayed) PROBE_DEADLINE_RELAYED_SECONDS else PROBE_DEADLINE_DIRECT_SECONDS

    /** How deep a `/server/info` document may nest before it is not one. The
     *  real answer is two levels deep. */
    private const val MAX_JSON_DEPTH = 32

    /** What an island's refusal of a registration starts with when it is a
     *  door: the status [RcqApi] puts in front of the body, and nothing else. */
    private const val DOOR_STATUS_PREFIX = "HTTP 403: "

    enum class Door {
        /** The island sells entry (or wants a voucher some other way). */
        ENTRY,
        /** The island lets people in by invite only. */
        INVITE,
    }

    /**
     * The door this refusal names, or null when it is anything else.
     *
     * Exactly an HTTP 403 whose body is a strict JSON object with `detail` an
     * OBJECT and `code` a string in the three door codes. A `detail` that is a
     * bare string, a code that is not a string, a message without the status,
     * the same code under another status, or a body that is not strict JSON is
     * not a door. Stricter than [RcqApi.refusalOf] on purpose, and the same on
     * every client.
     */
    fun doorRefusal(message: String?): Door? {
        if (message == null || !message.startsWith(DOOR_STATUS_PREFIX)) return null
        val detail = parseStrictObject(message.removePrefix(DOOR_STATUS_PREFIX))
            ?.get("detail")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val code = detail.get("code")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return when (code) {
            ENTRY_REQUIRED -> Door.ENTRY
            INVITE_REQUIRED, INVITE_INVALID -> Door.INVITE
            else -> null
        }
    }

    /** What one candidate came to after probing. */
    enum class Probe {
        /** Answered, and its door lets strangers register. */
        OPEN,

        /** Answered, and its door does not: paid, invite, sealed, or a door
         *  field we cannot trust. Recover only, never register. */
        SHUT,

        /** Nothing we can attribute to the island. Skipped, never registered
         *  or recovered on, and the only outcome worth a relay pass. */
        SILENT,
    }

    /**
     * The door of an island whose `/server/info` answered [infoJson] (a 2xx
     * body, from the very host asked, already decoded as strict UTF-8).
     *
     *  - Not strict JSON, or not a JSON object: [Probe.SILENT]. That is not the
     *    island's word (a block page, a captive portal, a cut-off body).
     *  - `capabilities` missing: [Probe.OPEN], an island older than the
     *    fields, which was always open. Present and not an object (null
     *    included): SHUT.
     *  - `registration_policy` "open" (exactly) or missing, AND `closed_island`
     *    missing or false: OPEN. Anything else, null included: SHUT.
     *
     * ⚠ `entry_price_cents` is deliberately NOT read. An island may sell
     * residency while registration stays open, and the policy is what
     * `auth.register` actually enforces.
     *
     * ⚠ A present but malformed field counts as SHUT, and a JSON null is
     * present. A garbled field is not evidence of an open door, and SHUT still
     * adopts an existing copy, so being wrong in that direction costs a
     * registration, not a backup we already had.
     */
    fun doorOf(infoJson: String?): Probe {
        if (infoJson == null) return Probe.SILENT
        val doc = parseStrictObject(infoJson) ?: return Probe.SILENT
        if (!doc.has("capabilities")) return Probe.OPEN
        val caps = doc.get("capabilities").takeIf { it.isJsonObject }?.asJsonObject ?: return Probe.SHUT
        val open = policyOpen(caps) && notSealed(caps)
        return if (open) Probe.OPEN else Probe.SHUT
    }

    private fun policyOpen(caps: JsonObject): Boolean {
        if (!caps.has("registration_policy")) return true
        val p = caps.get("registration_policy")
            .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString } ?: return false
        return p.asString == "open"
    }

    private fun notSealed(caps: JsonObject): Boolean {
        if (!caps.has("closed_island")) return true
        val p = caps.get("closed_island")
            .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } ?: return false
        return !p.asBoolean
    }

    /**
     * The `/server/info` body read from [input] as text, or null when it is
     * not one we accept: longer than [INFO_BODY_CAP] (by the declared
     * [declaredLength] or by what actually arrives, whichever shows it first),
     * or not valid UTF-8.
     *
     * ⚠ Rejected, never truncated. A cut body used to be handed to a lenient
     * parser, and "the first 64 KB of something" is not the island's answer.
     * Reading stops as soon as the cap is passed, so a huge answer costs at
     * most one buffer more than the cap.
     */
    fun readInfoBody(input: InputStream, declaredLength: Long = -1L): String? {
        if (declaredLength > INFO_BODY_CAP) return null
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > INFO_BODY_CAP) return null
            out.write(buf, 0, n)
        }
        return decodeUtf8Strict(out.toByteArray())
    }

    /** [bytes] as UTF-8, or null when any of it is not valid UTF-8 (the
     *  platform decoder would quietly put U+FFFD in its place). */
    fun decodeUtf8Strict(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /**
     * [text] as a JSON object when it is exactly one RFC 8259 JSON object and
     * nothing else, otherwise null.
     *
     * ⚠ Gson's [JsonParser] is lenient whatever it is asked: single quotes,
     * unquoted keys and strings, comments, `=` and `;` as separators, `NaN`,
     * trailing content. A block page or a half-broken proxy answer could come
     * out of that as an object with the right keys. So the text is checked
     * against the grammar here first, and Gson only builds the tree of a
     * document that already passed.
     */
    fun parseStrictObject(text: String): JsonObject? {
        if (!StrictJson(text).isDocument()) return null
        return runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
    }

    /** An RFC 8259 recognizer. Answers only "is this exactly one JSON value",
     *  with nesting capped at [MAX_JSON_DEPTH]. */
    private class StrictJson(private val s: String) {
        private var i = 0

        fun isDocument(): Boolean {
            ws()
            if (!value(0)) return false
            ws()
            return i == s.length
        }

        private fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun value(depth: Int): Boolean {
            if (i >= s.length) return false
            return when (s[i]) {
                '{' -> obj(depth + 1)
                '[' -> arr(depth + 1)
                '"' -> str()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> num()
            }
        }

        private fun obj(depth: Int): Boolean {
            if (depth > MAX_JSON_DEPTH) return false
            i++ // {
            ws()
            if (i < s.length && s[i] == '}') { i++; return true }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"' || !str()) return false
                ws()
                if (i >= s.length || s[i] != ':') return false
                i++
                ws()
                if (!value(depth)) return false
                ws()
                if (i >= s.length) return false
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return true }
                    else -> return false
                }
            }
        }

        private fun arr(depth: Int): Boolean {
            if (depth > MAX_JSON_DEPTH) return false
            i++ // [
            ws()
            if (i < s.length && s[i] == ']') { i++; return true }
            while (true) {
                ws()
                if (!value(depth)) return false
                ws()
                if (i >= s.length) return false
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return true }
                    else -> return false
                }
            }
        }

        private fun str(): Boolean {
            i++ // opening quote
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return true }
                    c < ' ' -> return false
                    c == '\\' -> {
                        i++
                        if (i >= s.length) return false
                        when (s[i]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> i++
                            'u' -> {
                                if (i + 4 >= s.length) return false
                                for (k in 1..4) {
                                    val h = s[i + k]
                                    if (h !in '0'..'9' && h !in 'a'..'f' && h !in 'A'..'F') return false
                                }
                                i += 5
                            }
                            else -> return false
                        }
                    }
                    else -> i++
                }
            }
            return false
        }

        private fun literal(word: String): Boolean {
            if (!s.startsWith(word, i)) return false
            i += word.length
            return true
        }

        private fun num(): Boolean {
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) return false
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                return false
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || s[i] !in '0'..'9') return false
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') return false
                while (i < s.length && s[i] in '0'..'9') i++
            }
            return true
        }
    }

    /** The catalogue minus our own island, the hosts already added and the
     *  fronts, in catalogue order (the order is the project's preference).
     *  Each host once: a catalogue that lists an island twice must not get it
     *  asked, or registered on, twice. */
    fun candidates(
        islands: List<String>,
        ownHost: String,
        exclude: Set<String>,
        isFront: (String) -> Boolean,
    ): List<String> = islands.distinct().filter { it != ownHost && it !in exclude && !isFront(it) }

    /** What reading a candidate's `/server/info` came to. */
    sealed class Info {
        /** A 2xx body, from the very host we asked, accepted by
         *  [readInfoBody]. Still [Probe.SILENT] if it does not parse, see
         *  [doorOf]. */
        data class Read(val json: String) : Info()

        /** Nothing we can attribute to the island: a timeout, a dropped
         *  connection, a redirect, a non-2xx status, no body or a rejected
         *  one, or an answer from some other host. */
        object Unreadable : Info()
    }

    /**
     * Classify one `/server/info` exchange. [asked] and [answeredFrom] are
     * `host:port` of the request we built and of the request the response
     * belongs to. [body] is what [readInfoBody] made of it, null when it
     * rejected it.
     *
     * ⚠ A redirect is Unreadable, never followed and never judged. If island A
     * answered with a 30x to island B, B's "open" would be read as A's and the
     * registration would go to A (reviewer finding on #988). On the networks
     * this project exists for, a redirect on a plain GET is also the usual
     * shape of a block page. An answer whose request is not the one we asked
     * is the same thing by another road.
     *
     * Any other non-2xx (a 404 from an island without the endpoint, a 500) is
     * Unreadable too: the door cannot be read, and the rule counts that as
     * silence on every client.
     */
    fun classifyInfo(asked: String, answeredFrom: String, status: Int, body: String?): Info = when {
        !asked.equals(answeredFrom, ignoreCase = true) -> Info.Unreadable
        status !in 200..299 -> Info.Unreadable
        body == null -> Info.Unreadable
        else -> Info.Read(body)
    }

    /** One candidate's [Probe] from its two answers: `/health` ([healthOk])
     *  first, and the info only when that got through. */
    inline fun probe(healthOk: Boolean, info: () -> Info): Probe {
        if (!healthOk) return Probe.SILENT
        return when (val i = info()) {
            is Info.Read -> doorOf(i.json)
            Info.Unreadable -> Probe.SILENT
        }
    }

    sealed class Pass<out T> {
        /** A backup on [host]; [value] is the home it came to. */
        data class Added<out T>(val host: String, val value: T) : Pass<T>()

        /** At least one island answered (OPEN or SHUT) and none gave a
         *  backup, or the verified catalogue left nobody to ask. A tunnel would
         *  not change any of that. */
        object NoneOpen : Pass<Nothing>()

        /** Not one candidate answered, or the catalogue itself could not be
         *  fetched or verified. The only outcome worth bringing the relays up
         *  for (see [attempt]). */
        object Silent : Pass<Nothing>()
    }

    /**
     * One pass from the signed catalogue: [islands] is the verified list, or
     * null when it could not be fetched or failed verification, which is
     * silence. A verified list is narrowed by [candidates] and walked by
     * [runPass]; if nothing is left to ask, that is [Pass.NoneOpen], not
     * silence, so the relays are not brought up for it.
     */
    inline fun <T> catalogPass(
        islands: List<String>?,
        ownHost: String,
        exclude: Set<String>,
        noinline isFront: (String) -> Boolean,
        answers: (String) -> Boolean,
        infoOf: (String) -> Info,
        register: (String) -> T,
        recover: (String) -> T?,
    ): Pass<T> {
        if (islands == null) return Pass.Silent
        return runPass(candidates(islands, ownHost, exclude, isFront), answers, infoOf, register, recover)
    }

    /**
     * One pass over [candidates], one at a time and in order, stopping at the
     * first backup. No host is asked anything until every host before it has
     * been ruled out.
     *
     *  - [answers] is the `/health` probe and [infoOf] the `/server/info` read,
     *    together a [Probe] (see [probe]). [infoOf] is never called for a host
     *    whose `/health` failed.
     *  - OPEN: [register], exactly as a manual add does, which RECOVERS FIRST
     *    and only registers when the island holds no copy. So any failure from
     *    it moves on to the next island. A door refusal in particular means
     *    "no copy here, door shut": the recover already ran on this host, and
     *    it is not called a second time.
     *  - SHUT: [recover] only, once. A copy of this account it already holds is
     *    a backup; null or a failure moves on. [register] is never called.
     *  - SILENT: neither.
     *
     * An empty [candidates] is [Pass.NoneOpen]. Every host is tried at most
     * once, so this cannot loop. Cancellation is never swallowed. Inline so
     * the network lambdas may suspend when the caller does.
     */
    inline fun <T> runPass(
        candidates: List<String>,
        answers: (String) -> Boolean,
        infoOf: (String) -> Info,
        register: (String) -> T,
        recover: (String) -> T?,
    ): Pass<T> {
        val hosts = candidates.distinct()
        if (hosts.isEmpty()) return Pass.NoneOpen
        var anyAnswered = false
        for (host in hosts) {
            val door = probe(answers(host)) { infoOf(host) }
            if (door == Probe.SILENT) continue
            anyAnswered = true
            if (door == Probe.OPEN) {
                try {
                    return Pass.Added(host, register(host))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // No backup here, a door refusal included: the recover-first
                    // step inside [register] already asked this island.
                    continue
                }
            }
            val copy = try {
                recover(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (copy != null) return Pass.Added(host, copy)
        }
        return if (anyAnswered) Pass.NoneOpen else Pass.Silent
    }

    /**
     * The whole toggle attempt: one [pass] over the direct route, and a second
     * one only when the first was [Pass.Silent] and [engageRelays] brought the
     * relays up. Returns the backup, or throws IllegalArgumentException with
     * [NO_OPEN_ISLAND] for [Pass.NoneOpen] and [NO_ISLAND] when all was silent.
     *
     * ⚠ A [Pass.NoneOpen] never engages the relays. Islands ANSWERED (or the
     * verified catalogue left nobody to ask), so the network is not the
     * problem, and a tunnel over a shut door would move every later request in
     * the process onto the relays and still find the door shut.
     */
    inline fun <T> attempt(pass: () -> Pass<T>, engageRelays: () -> Boolean): T {
        when (val direct = pass()) {
            is Pass.Added -> return direct.value
            Pass.NoneOpen -> throw IllegalArgumentException(NO_OPEN_ISLAND)
            Pass.Silent -> Unit
        }
        if (!engageRelays()) throw IllegalArgumentException(NO_ISLAND)
        return when (val relayed = pass()) {
            is Pass.Added -> relayed.value
            Pass.NoneOpen -> throw IllegalArgumentException(NO_OPEN_ISLAND)
            Pass.Silent -> throw IllegalArgumentException(NO_ISLAND)
        }
    }

    /** Every sentence the backup screen (auto toggle, manual add, make
     *  primary) can show for a failure. */
    enum class Sentence {
        INVALID_HOST,
        PRIMARY_ISLAND,
        ALREADY_ADDED,
        NO_ISLAND,
        NO_OPEN_ISLAND,
        NO_ACCOUNT_HERE,
        NO_ROUTE,
        UNREACHABLE,
        /** Make primary: the island answered with a failure, nothing changed. */
        SWITCH_NOT_DONE,
        ENTRY,
        INVITE,
        GENERIC,
    }

    /**
     * The sentence for a failure [message] on the backup screen.
     *
     * ⚠ No status, no body, no exception text ever reaches the screen (#988:
     * `(HTTP 403: {"detail":{"code":"entry_required"}})` under the toggle).
     * A door refusal is its own sentence, the client's own reason codes are
     * theirs, and everything else, statuses included, is [Sentence.GENERIC]
     * alone. The cause goes to the log through [causeLabel].
     */
    fun sentenceOf(message: String?): Sentence {
        if (message != null && message.startsWith("island_said:")) return Sentence.SWITCH_NOT_DONE
        when (doorRefusal(message)) {
            Door.ENTRY -> return Sentence.ENTRY
            Door.INVITE -> return Sentence.INVITE
            null -> Unit
        }
        return when (message) {
            "invalid_host" -> Sentence.INVALID_HOST
            "primary_island" -> Sentence.PRIMARY_ISLAND
            "already_added" -> Sentence.ALREADY_ADDED
            NO_ISLAND -> Sentence.NO_ISLAND
            NO_OPEN_ISLAND -> Sentence.NO_OPEN_ISLAND
            "no_account_here" -> Sentence.NO_ACCOUNT_HERE
            "no_route" -> Sentence.NO_ROUTE
            "unreachable" -> Sentence.UNREACHABLE
            else -> Sentence.GENERIC
        }
    }

    /**
     * The sentence for a failed make primary, whatever [message] it failed with.
     *
     * A door refusal keeps its own sentence; every other failure (an island
     * answer, `no_route`, `no_account_here`, `unreachable`, an unparseable
     * reply, any exception at all) is [Sentence.SWITCH_NOT_DONE], because the
     * one thing the user needs to know is that nothing moved. The same on every
     * client (#988 D8). The toggle and the manual add keep [sentenceOf].
     */
    fun promoteSentenceOf(message: String?): Sentence = when (doorRefusal(message)) {
        Door.ENTRY -> Sentence.ENTRY
        Door.INVITE -> Sentence.INVITE
        null -> Sentence.SWITCH_NOT_DONE
    }

    /**
     * A short label of the cause, for the LOG only (a self-hoster debugging
     * their own island reads logcat). The status of an island's answer, never
     * its body: that body is JSON the island phrased for a program (#988 put
     * `{"detail":{"code":...}}` on the screen, and a log line is one copy-paste
     * away from a report). A local exception keeps its message unless it
     * smells of a body too, and then only its class name is kept.
     */
    fun causeLabel(message: String?, className: String): String {
        val m = message?.trim().orEmpty()
        val status = Regex("^HTTP (\\d{3})").find(m)?.groupValues?.get(1)
        return when {
            status != null -> "HTTP $status"
            m.isEmpty() || m.contains('{') || m.contains('[') -> className
            else -> m.take(120)
        }
    }
}
