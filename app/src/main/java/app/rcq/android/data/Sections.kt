package app.rcq.android.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.security.SecureRandom

/**
 * Chat-list sections: the format, the merge and the caps.
 *
 * Founder item 1 of 23.08, built to `RCQ/docs/sections-design-2026-08-23.md`
 * and to the web's `src/lib/sections.ts`, which this matches rule for rule. A
 * section is a user-made bucket in the chat list. The list of sections (and
 * which chat sits in which) lives in a SECOND vault slot, "sections", derived
 * exactly like "contacts": the island stores 32 hex characters and a sealed
 * blob and attaches no meaning to either.
 *
 * This file is pure. No network, no prefs, no Compose: the transport is in
 * [SectionsVault] and the cache in [LocalStores], and everything here is a
 * function from trees to trees so the merge can be tested on its own
 * (SectionsMergeTest).
 *
 * ## Two rules that are easy to get wrong
 *
 * ⚠⚠ PATCH, DO NOT REBUILD. The tree is a [JsonObject] and every function here
 * copies it and edits the keys it knows about. Nothing deserialises into a
 * data class and re-serialises from scratch, because that silently deletes
 * whatever a newer build, or another platform, wrote next to it. The accessors
 * below read a plain object; they are not the shape of the object.
 *
 * ⚠⚠ A MEMBER KEY CARRIES THE HOST. A uin is per-island: `1234` here and
 * `1234@is2.rcq.app` are two different people, and keying them the same has
 * already cost this project a bug twice (contact aliases, [LocalStores.aliasKey];
 * the multi-device session collision). ⚠ [LocalStores.peerThread] is
 * `"peer:$uin"` with NO host and is the key for archive/favorites/mute. It is
 * not, and must never become, the key for section membership. A foreign group
 * is worse still: its LOCAL id is a negative alias handed out in first-sight
 * order by [app.rcq.android.net.VisitedIslandsStore], so it is not the same
 * number on two devices. The slot only ever holds (remoteId, host), and
 * [SectionsVault.keyForGroup] is the edge where that translation happens.
 */
object Sections {

    // ── Shape ────────────────────────────────────────────────────────────

    /** Ids the three clients agree on, so the ORDER of the built-in sections
     *  syncs even though their membership is derived rather than stored. */
    const val SYS_SAVED = "sys.saved"
    const val SYS_FAV = "sys.fav"
    const val SYS_CI = "sys.ci"
    const val SYS_GROUPS = "sys.groups"
    const val SYS_ONLINE = "sys.online"
    const val SYS_OFFLINE = "sys.offline"
    const val SYS_ARCHIVE = "sys.archive"

    /** Default order for a built-in that has no record yet. A client that does
     *  not RENDER one of these still keeps its record and writes it back:
     *  dropping it would delete another client's setting. */
    val DEFAULT_ORDER: Map<String, Long> = linkedMapOf(
        SYS_SAVED to 0L,
        SYS_FAV to 1024L,
        SYS_CI to 2048L,
        SYS_GROUPS to 3072L,
        SYS_ONLINE to 4096L,
        SYS_OFFLINE to 5120L,
        SYS_ARCHIVE to 6144L,
    )

    val BUILTIN_IDS: List<String> = DEFAULT_ORDER.keys.toList()

    const val ORDER_STEP = 1024L

    // Caps, enforced in the UI, all well under the island's ceiling. The island
    // caps a slot at 256 KB decoded; the seal costs 1 + 12 + 16 bytes and pads
    // to a 512-byte boundary with a 4-byte length prefix, so the JSON ceiling
    // is 261 628 bytes. Worst case at these caps is under 170 KB.
    const val MAX_SECTIONS = 64
    const val MAX_MEMBERS_PER_SECTION = 512
    const val MAX_MEMBERS_TOTAL = 4096
    const val MAX_NAME_SCALARS = 32
    /** Refuse to write above this, after dropping expired tombstones. Never
     *  truncate, never silently drop a member, never hand the island a blob it
     *  will 413. */
    const val MAX_WRITE_BYTES = 200 * 1024
    const val TOMBSTONE_TTL_MS = 90L * 24 * 3600 * 1000

    /** [code] is one of `too_many_sections`, `section_full`,
     *  `too_many_members`, `too_large`, `unreadable`; the UI turns it into a
     *  string with `sections_err_<code>`. */
    class SectionsException(val code: String) : Exception(code)

    fun emptyTree(): JsonObject = JsonObject().apply {
        addProperty("v", 1)
        add("s", JsonArray())
        add("d", JsonObject())
        addProperty("w", 0L)
    }

    // ── Member keys ──────────────────────────────────────────────────────

    /** `p:<uin>` on our own island, `p:<uin>@<host>` for a peer on another. */
    fun peerKey(uin: Int, host: String? = null): String =
        if (host.isNullOrBlank()) "p:$uin" else "p:$uin@${host.lowercase()}"

    /** `g:<id>` for a group on our island, `g:<remoteId>@<host>` for a foreign
     *  one. ⚠ [remoteId] is the id on ITS island, never the negative local
     *  alias. */
    fun groupKey(remoteId: Int, host: String? = null): String =
        if (host.isNullOrBlank()) "g:$remoteId" else "g:$remoteId@${host.lowercase()}"

    fun isGroupKey(key: String): Boolean = key.startsWith("g:")

    // ── Codec ────────────────────────────────────────────────────────────

    /**
     * Decode a slot's plaintext.
     *
     * Returns null for "this build cannot read it": a `v` above 1, or bytes
     * that are not the tree at all. The caller then renders the built-in
     * sections and NEVER writes the slot. ⚠ This is deliberately not the
     * contacts slot's rule ("unknown version reads as empty"): that rule
     * overwrites, and overwriting here would erase the sections the user made
     * on a newer client.
     *
     * An absent slot (the island has nothing) is an empty tree, which is
     * readable and writable.
     */
    fun decode(plaintext: ByteArray?): JsonObject? {
        if (plaintext == null) return emptyTree()
        val parsed = runCatching {
            JsonParser.parseString(String(plaintext, Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val o = parsed.asJsonObject
        if (numberOr(o.get("v"), 0L) != 1L) return null
        val s = o.get("s")
        if (s == null || !s.isJsonArray) return null
        val kept = JsonArray()
        for (e in s.asJsonArray) {
            if (!e.isJsonObject) continue
            val r = e.asJsonObject
            val id = r.get("id")
            if (id != null && id.isJsonPrimitive && id.asJsonPrimitive.isString) kept.add(r)
        }
        // Keeps every other top-level key in its place (§2.1: patch, do not
        // rebuild): `add` on an existing key overwrites the value and leaves
        // the position alone.
        val out = o.deepCopy()
        out.addProperty("v", 1)
        out.add("s", kept)
        out.add("d", mapToJson(plainMap(o.get("d"))))
        out.addProperty("w", numberOr(o.get("w"), 0L))
        return out
    }

    fun encode(tree: JsonObject): ByteArray = tree.toString().toByteArray(Charsets.UTF_8)

    fun treeBytes(tree: JsonObject): Int = encode(tree).size

    // ── Merge ────────────────────────────────────────────────────────────

    /**
     * The whole of the conflict story, in one commutative, idempotent function.
     *
     * Both paths use it: the read path folds what the island serves into the
     * cache (`cache = merge(cache, remote)`), and the write path folds the
     * cache into what the island holds right now, inside the read-merge-write
     * loop ([SectionsVault.push]).
     *
     * Per-FIELD last-writer-wins, so a rename on the phone and a reorder on the
     * desktop both survive. Equal timestamps with different values fall back to
     * "the larger value wins", which exists only to keep the function
     * commutative and is never the interesting case.
     */
    fun merge(a: JsonObject, b: JsonObject): JsonObject {
        if (numberOr(a.get("v"), 1L) > 1L || numberOr(b.get("v"), 1L) > 1L) {
            throw SectionsException("unreadable")
        }
        val d = LinkedHashMap(plainMap(a.get("d")))
        for ((id, ts) in plainMap(b.get("d"))) d[id] = maxOf(d[id] ?: 0L, ts)

        val byId = LinkedHashMap<String, JsonObject>()
        for (r in records(a)) byId[idOf(r)] = r
        for (r in records(b)) {
            val id = idOf(r)
            val cur = byId[id]
            byId[id] = if (cur != null) mergeRecord(cur, r) else r
        }

        // A tombstone only wins over a record nobody has touched since: a
        // section deleted here and renamed there comes back, named.
        val kept = byId.values.filter { r ->
            val dead = d[idOf(r)]
            dead == null || dead < newestStamp(r)
        }

        // One chat lives in exactly one user section (§7). Two devices that
        // filed the same chat differently while offline are resolved here
        // rather than by whoever renders last: the larger add ts wins, ties go
        // to the smaller id, and the losers get a tombstone at the same ts so
        // the outcome survives the next merge from either side.
        val owner = HashMap<String, Pair<String, Long>>()
        for (r in kept) {
            if (kindOf(r) == "d") continue
            for ((key, ts) in plainMap(r.get("m"))) {
                val cur = owner[key]
                if (cur == null || ts > cur.second || (ts == cur.second && idOf(r) < cur.first)) {
                    owner[key] = idOf(r) to ts
                }
            }
        }
        val out = kept.map { r ->
            if (kindOf(r) == "d") return@map r
            val m = plainMap(r.get("m"))
            val stolen = m.filterKeys { key -> owner[key]?.first?.let { it != idOf(r) } == true }
            if (stolen.isEmpty()) return@map r
            val movedM = LinkedHashMap(m)
            val movedX = LinkedHashMap(plainMap(r.get("x")))
            for ((key, ts) in stolen) {
                movedM.remove(key)
                movedX[key] = maxOf(movedX[key] ?: 0L, ts)
            }
            r.deepCopy().apply {
                add("m", mapToJson(movedM))
                add("x", mapToJson(movedX))
            }
        }.sortedWith(compareBy({ orderOf(it) }, { idOf(it) }))

        val result = mergeUnknownKeys(a, b, TOP_KEYS)
        result.addProperty("v", 1)
        result.add("s", JsonArray().apply { out.forEach { add(it) } })
        result.add("d", mapToJson(d))
        result.addProperty("w", maxOf(numberOr(a.get("w"), 0L), numberOr(b.get("w"), 0L)))
        return result
    }

    /**
     * The newest thing anyone claims to have USED this record for. A section
     * tombstone older than this loses: somebody was working in the section
     * after it was deleted, which is a resurrection on purpose.
     *
     * ⚠ Member TOMBSTONES (`x`) are deliberately not counted, and counting
     * them was a real bug on the web: unticking one chat in a section's picker
     * on an offline device stamps `x` and nothing else, so it read as "the
     * section was touched" and brought a section deleted on the other device
     * back from the dead, stably, carrying whatever was left in it. Emptying a
     * section is not a reason to keep it. Adds (`m`) do count: filing a chat
     * into a section is somebody using it, and the alternative loses their
     * filing entirely.
     *
     * ⚠⚠ This is `max(t.*, m.*)` and the design doc (§2) says the same in the
     * same words. Three clients each implement this line; two that disagree
     * about one section do not merely render differently, they write the slot
     * back and forth at each other until the island 429s.
     */
    private fun newestStamp(r: JsonObject): Long {
        val t = r.get("t")
        var n = 0L
        for (f in FIELD_KEYS) n = maxOf(n, numberOr(objOrNull(t)?.get(f), 0L))
        for (ts in plainMap(r.get("m")).values) n = maxOf(n, ts)
        return n
    }

    private fun mergeRecord(a: JsonObject, b: JsonObject): JsonObject {
        val ta = objOrNull(a.get("t"))
        val tb = objOrNull(b.get("t"))
        // Unknown keys first, so the fields below always win over a same-named
        // stranger, and so a key only one side knows about survives untouched.
        val out = mergeUnknownKeys(a, b, RECORD_KEYS)
        out.addProperty("id", idOf(a))
        val k = pickLarger(a.get("k"), b.get("k"))
        if (k != null) out.add("k", k) else out.remove("k")
        assignField(out, "n", a, b, ta, tb)
        assignField(out, "o", a, b, ta, tb)
        assignField(out, "p", a, b, ta, tb)
        assignField(out, "h", a, b, ta, tb)
        val t = JsonObject()
        for (f in FIELD_KEYS) {
            val v = maxOf(numberOr(ta?.get(f), 0L), numberOr(tb?.get(f), 0L))
            if (v > 0L) t.addProperty(f, v)
        }
        out.add("t", t)
        val merged = mergeMembers(
            plainMap(a.get("m")), plainMap(a.get("x")),
            plainMap(b.get("m")), plainMap(b.get("x")),
        )
        if (merged.first.isNotEmpty() || a.has("m") || b.has("m")) out.add("m", mapToJson(merged.first))
        if (merged.second.isNotEmpty() || a.has("x") || b.has("x")) out.add("x", mapToJson(merged.second))
        return out
    }

    /**
     * Add versus tombstone: the larger ts wins, and ON A TIE THE TOMBSTONE
     * WINS. A removal racing an add leaves the chat out of the section, which
     * the user can undo by adding it again; a chat resurrected into a section
     * the user emptied is the version nobody can undo by hand.
     */
    private fun mergeMembers(
        am: Map<String, Long>,
        ax: Map<String, Long>,
        bm: Map<String, Long>,
        bx: Map<String, Long>,
    ): Pair<LinkedHashMap<String, Long>, LinkedHashMap<String, Long>> {
        val adds = LinkedHashMap<String, Long>()
        val dels = LinkedHashMap<String, Long>()
        for ((k, ts) in am) adds[k] = maxOf(adds[k] ?: 0L, ts)
        for ((k, ts) in bm) adds[k] = maxOf(adds[k] ?: 0L, ts)
        for ((k, ts) in ax) dels[k] = maxOf(dels[k] ?: 0L, ts)
        for ((k, ts) in bx) dels[k] = maxOf(dels[k] ?: 0L, ts)
        val m = LinkedHashMap<String, Long>()
        for ((k, ts) in adds) {
            val gone = dels[k]
            if (gone != null && gone >= ts) continue
            m[k] = ts
        }
        return m to dels
    }

    private fun assignField(
        out: JsonObject,
        field: String,
        a: JsonObject,
        b: JsonObject,
        ta: JsonObject?,
        tb: JsonObject?,
    ) {
        val at = numberOr(ta?.get(field), 0L)
        val bt = numberOr(tb?.get(field), 0L)
        val av = a.get(field)
        val bv = b.get(field)
        val v = when {
            at == bt -> pickLarger(av, bv)
            at > bt -> av
            else -> bv
        }
        if (v == null) out.remove(field) else out.add(field, v)
    }

    /**
     * Deterministic tie-break, identical on every client: numbers numerically,
     * strings by UTF-8 byte order (NOT by the platform's own string compare,
     * which on Java and on JS alike walks UTF-16 units and disagrees about
     * surrogate pairs), 1 > 0, and anything else by its JSON.
     *
     * ⚠⚠ A JSON `null` IS AN ABSENT VALUE HERE, on both sides, exactly like a
     * key that is not there (design §2, "the larger value wins"). It has to be
     * spelled out because the platforms do not agree on their own: a key a
     * Swift-style encoder wrote for a nil optional arrives as `null` rather
     * than missing, and ranking that `null` by its serialised text instead
     * makes `"null"` beat almost every real value, since `n` (0x6e) is above
     * every digit and above `"`. On `k`, which has NO timestamp and is
     * therefore always decided right here, that silently deletes `k == "u"`:
     * the section drops out of [userSections] and [memberIndex], its filed
     * chats fall back to their derived sections, and the client that kept the
     * value and the client that kept the `null` then disagree about the
     * content of the tree forever, each concluding the island is missing
     * something of its own and pushing, until the account's 240 puts an hour
     * run out. Same on `n`/`o`/`p`/`h` when the stamps tie, and on every
     * unknown key, which never has a stamp either.
     *
     * ⚠ The fallback branch compares SERIALISED JSON, so it also assumes every
     * client prints a number the one canonical way (`1`, never `1.0` or `1e3`).
     * All three do today: Gson writes what [mapToJson]/`addProperty` were
     * handed, `JSON.stringify` canonicalises, and `JSONSerialization` prints a
     * whole Double without a point. A future writer that emits `1.0` inside an
     * object-valued unknown key would split this tie-break without splitting
     * the value.
     */
    private fun pickLarger(a: JsonElement?, b: JsonElement?): JsonElement? {
        val x = a?.takeIf { !it.isJsonNull }
        val y = b?.takeIf { !it.isJsonNull }
        if (x == null) return y
        if (y == null) return x
        val pa = x as? JsonPrimitive
        val pb = y as? JsonPrimitive
        if (pa != null && pb != null && pa.isNumber && pb.isNumber) {
            return if (pa.asDouble >= pb.asDouble) x else y
        }
        if (pa != null && pb != null && pa.isString && pb.isString) {
            return if (utf8Greater(pa.asString, pb.asString)) x else y
        }
        return if (utf8Greater(x.toString(), y.toString())) x else y
    }

    private fun utf8Greater(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            if (x[i] != y[i]) return (x[i].toInt() and 0xff) > (y[i].toInt() and 0xff)
        }
        return x.size >= y.size
    }

    private val TOP_KEYS = setOf("v", "s", "d", "w")
    private val RECORD_KEYS = setOf("id", "k", "n", "o", "p", "h", "t", "m", "x")
    private val FIELD_KEYS = listOf("n", "o", "p", "h")

    /**
     * Keys neither this build nor the merge above knows about. They belong to
     * a newer client and are carried through untouched (§2.1: patch, do not
     * rebuild). A key both sides carry with different values is settled by the
     * same deterministic tie-break the fields use, because an unknown key has
     * no timestamp to compare and the merge still has to be commutative.
     */
    private fun mergeUnknownKeys(a: JsonObject, b: JsonObject, known: Set<String>): JsonObject {
        val out = JsonObject()
        for ((k, v) in a.entrySet()) if (k !in known) out.add(k, v)
        for ((k, v) in b.entrySet()) {
            if (k in known) continue
            if (!out.has(k)) {
                out.add(k, v)
                continue
            }
            val winner = pickLarger(out.get(k), v)
            if (winner != null) out.add(k, winner)
        }
        return out
    }

    // ── Reads ────────────────────────────────────────────────────────────

    fun records(tree: JsonObject): List<JsonObject> {
        val s = tree.get("s")
        if (s == null || !s.isJsonArray) return emptyList()
        return s.asJsonArray.mapNotNull { e ->
            (e as? JsonObject)?.takeIf {
                it.get("id")?.let { i -> i.isJsonPrimitive && i.asJsonPrimitive.isString } == true
            }
        }
    }

    fun idOf(r: JsonObject): String = r.get("id")?.asString ?: ""

    fun kindOf(r: JsonObject): String? =
        r.get("k")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    fun nameOf(r: JsonObject): String? =
        r.get("n")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    fun isPinnedRecord(r: JsonObject): Boolean = numberOr(r.get("p"), 0L) == 1L

    fun orderOf(r: JsonObject): Long {
        val o = r.get("o")
        if (o != null && o.isJsonPrimitive && o.asJsonPrimitive.isNumber) return o.asLong
        return DEFAULT_ORDER[idOf(r)] ?: ((BUILTIN_IDS.size + 1) * ORDER_STEP)
    }

    fun recordFor(tree: JsonObject, id: String): JsonObject? = records(tree).firstOrNull { idOf(it) == id }

    /** Every section this client should draw, built-ins included (synthesised
     *  at their default order when the slot has no record for them), in the one
     *  total order every device agrees on: `o` ascending, ties by id. */
    fun orderedSections(tree: JsonObject): List<JsonObject> {
        val out = records(tree).filter { kindOf(it) != "u" || nameOf(it) != null }.toMutableList()
        for (id in BUILTIN_IDS) {
            if (out.none { idOf(it) == id }) {
                out.add(JsonObject().apply {
                    addProperty("id", id)
                    addProperty("k", "d")
                    addProperty("o", DEFAULT_ORDER[id]!!)
                })
            }
        }
        return out.sortedWith(compareBy({ orderOf(it) }, { idOf(it) }))
    }

    fun userSections(tree: JsonObject): List<JsonObject> = orderedSections(tree).filter { kindOf(it) == "u" }

    fun isPinned(tree: JsonObject, id: String): Boolean = recordFor(tree, id)?.let { isPinnedRecord(it) } == true

    /** member key -> the id of the user section holding it. The merge
     *  guarantees one section per key, so this is a function and not a
     *  multimap. */
    fun memberIndex(tree: JsonObject): Map<String, String> {
        val out = HashMap<String, String>()
        for (r in records(tree)) {
            if (kindOf(r) != "u") continue
            for (key in plainMap(r.get("m")).keys) out[key] = idOf(r)
        }
        return out
    }

    fun membersOf(tree: JsonObject, id: String): Set<String> = plainMap(recordFor(tree, id)?.get("m")).keys

    fun totalMembers(tree: JsonObject): Int {
        var n = 0
        for (r in records(tree)) if (kindOf(r) == "u") n += plainMap(r.get("m")).size
        return n
    }

    fun sameTree(a: JsonObject, b: JsonObject): Boolean = a.toString() == b.toString()

    /**
     * The tree as JSON with every object key sorted, at every depth.
     *
     * ⚠⚠ The depth is the whole point. [merge] normalises the RECORD array and
     * the record's own key sequence, and nothing normalises the key order
     * inside `m`, `x` and `d`: those are maps keyed by chat and by section id,
     * they are built by walking whatever order the two sides happened to hold
     * (`mergeMembers` seeds from `a` first, so `merge(x, x)` keeps a's), and
     * Gson's [JsonObject] preserves insertion order. A `toString` comparison
     * then answers "different" about two trees that agree on every member of
     * every section.
     *
     * A stranger's container is sorted here too. That is safe BECAUSE these
     * bytes are never written: [encode] still serialises the tree as it
     * stands, so another build's object is put back in the order it arrived.
     */
    private fun canonical(e: JsonElement): String {
        val sb = StringBuilder()
        fun write(v: JsonElement) {
            when {
                v.isJsonObject -> {
                    sb.append('{')
                    var first = true
                    for (k in v.asJsonObject.keySet().sorted()) {
                        if (!first) sb.append(',')
                        first = false
                        sb.append(JsonPrimitive(k).toString()).append(':')
                        write(v.asJsonObject.get(k))
                    }
                    sb.append('}')
                }
                v.isJsonArray -> {
                    sb.append('[')
                    var first = true
                    for (item in v.asJsonArray) {
                        if (!first) sb.append(',')
                        first = false
                        write(item)
                    }
                    sb.append(']')
                }
                else -> sb.append(v.toString())
            }
        }
        write(e)
        return sb.toString()
    }

    /**
     * Do these two trees SAY the same thing? `merge(x, x)` is the identity on
     * content and puts every record, and every key inside a record, in the one
     * order all merge output has; sorting the rest of the keys takes out the
     * one piece of order the merge does not own.
     *
     * ⚠ Use this, not [sameTree], to decide whether to WRITE. A blob written
     * by the web or by iOS orders its JSON their way, and a byte comparison
     * then says "different" about a tree that is identical: this client
     * rewrites it in its own order, the other client rewrites it back, and two
     * devices that both do that spend the account's 240 puts an hour telling
     * each other nothing. iOS answers this question over `.sortedKeys` bytes,
     * so a comparison that reads the order of `m` is not merely wasteful, it
     * DISAGREES with iOS about the same pair of trees: the phone settles and
     * this client pushes forever.
     */
    fun sameContent(a: JsonObject, b: JsonObject): Boolean = canonical(merge(a, a)) == canonical(merge(b, b))

    // ── Writes. Every one returns a NEW tree and mutates nothing. ─────────

    /** ⚠ Clamp by SCALARS, not by UTF-16 units and not by bytes. The
     *  pinned-message 422 (22.08) was exactly this: a slot measured in one unit
     *  and filled in another, on all three clients at once. */
    fun clampName(name: String): String = limitName(name.trim())

    /** [clampName] WITHOUT the trim, for the text field.
     *
     *  ⚠ The field cannot trim on every keystroke. Trimming there eats the
     *  space the moment it is typed, so "Work" + " " comes back as "Work" and a
     *  two-word section name can never be entered at all. The trim belongs on
     *  save, which is where [clampName] does it. */
    fun limitName(name: String): String {
        val out = StringBuilder()
        var n = 0
        var i = 0
        while (i < name.length && n < MAX_NAME_SCALARS) {
            val cp = name.codePointAt(i)
            out.appendCodePoint(cp)
            i += Character.charCount(cp)
            n++
        }
        return out.toString()
    }

    private val rng = SecureRandom()

    fun newSectionId(): String {
        val b = ByteArray(4)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /**
     * ⚠ The one place a record is edited. It deep-copies the record and hands
     * the copy to [edit], so every key this build does not know about rides
     * along. A record for a built-in the slot has never carried is created
     * here, which is how `sys.archive` gets a `p` without anybody seeding the
     * tree.
     */
    private fun patch(tree: JsonObject, id: String, now: Long, edit: (JsonObject) -> Unit): JsonObject {
        val out = tree.deepCopy()
        val s = JsonArray()
        var found = false
        for (r in records(out)) {
            if (idOf(r) != id) {
                s.add(r)
                continue
            }
            found = true
            edit(r)
            s.add(r)
        }
        if (!found) {
            val fresh = JsonObject().apply { addProperty("id", id) }
            edit(fresh)
            s.add(fresh)
        }
        out.add("s", s)
        out.addProperty("w", now)
        return out
    }

    /**
     * ⚠ The cap counts the sections that EXIST, not the ones that ever
     * existed. Counting tombstones too dead-ends the feature on pure churn:
     * make and delete 64 sections while trying out names and the next "new
     * section" is refused while the screen shows none at all, on every device,
     * for 90 days, with no action that clears it. Tombstones are bounded
     * instead, by [dropExpired].
     */
    fun createSection(tree: JsonObject, name: String, now: Long = System.currentTimeMillis()): JsonObject {
        if (records(tree).size >= MAX_SECTIONS) throw SectionsException("too_many_sections")
        val top = orderedSections(tree).fold(0L) { max, r -> maxOf(max, orderOf(r)) }
        val rec = JsonObject().apply {
            addProperty("id", newSectionId())
            addProperty("k", "u")
            addProperty("n", clampName(name))
            addProperty("o", top + ORDER_STEP)
            add("t", JsonObject().apply { addProperty("n", now); addProperty("o", now) })
            add("m", JsonObject())
            add("x", JsonObject())
        }
        val out = tree.deepCopy()
        val s = JsonArray()
        records(out).forEach { s.add(it) }
        s.add(rec)
        out.add("s", s)
        out.addProperty("w", now)
        return out
    }

    fun renameSection(tree: JsonObject, id: String, name: String, now: Long = System.currentTimeMillis()): JsonObject =
        patch(tree, id, now) { r ->
            r.addProperty("n", clampName(name))
            r.add("t", stamp(r, "n", now))
        }

    fun setPinned(tree: JsonObject, id: String, on: Boolean, now: Long = System.currentTimeMillis()): JsonObject =
        patch(tree, id, now) { r ->
            if (kindOf(r) == null) r.addProperty("k", if (id in BUILTIN_IDS) "d" else "u")
            r.addProperty("p", if (on) 1 else 0)
            r.add("t", stamp(r, "p", now))
        }

    /** Delete a user section. The chats are not touched: they fall back into
     *  their derived sections on the next render. */
    fun deleteSection(tree: JsonObject, id: String, now: Long = System.currentTimeMillis()): JsonObject {
        val out = tree.deepCopy()
        val s = JsonArray()
        records(out).forEach { if (idOf(it) != id) s.add(it) }
        out.add("s", s)
        val d = LinkedHashMap(plainMap(out.get("d")))
        d[id] = now
        out.add("d", mapToJson(d))
        out.addProperty("w", now)
        return out
    }

    /** Apply a whole new order at once (a drop, or a renormalise). Every
     *  section that MOVES gets `t.o = now`; the ones that did not move are left
     *  alone so they do not stamp over another device's reorder. */
    fun setOrder(tree: JsonObject, orders: Map<String, Long>, now: Long = System.currentTimeMillis()): JsonObject {
        var out = tree
        for ((id, o) in orders) {
            val cur = recordFor(out, id)
            if (cur != null && orderOf(cur) == o && cur.get("o")?.isJsonPrimitive == true) continue
            out = patch(out, id, now) { r ->
                if (kindOf(r) == null) r.addProperty("k", if (id in BUILTIN_IDS) "d" else "u")
                r.addProperty("o", o)
                r.add("t", stamp(r, "o", now))
            }
        }
        val res = out.deepCopy()
        res.addProperty("w", now)
        return res
    }

    /**
     * File chats into a section. One write for the whole picker sheet.
     *
     * A key that sits in another user section is moved: removed there with a
     * tombstone at the same ts, added here. That is the same outcome [merge]
     * would compute, done locally so the list does not flicker through a state
     * where the chat is in two places.
     */
    fun addMembers(tree: JsonObject, id: String, keys: List<String>, now: Long = System.currentTimeMillis()): JsonObject {
        val cur = plainMap(recordFor(tree, id)?.get("m"))
        val fresh = keys.filter { it !in cur }
        if (cur.size + fresh.size > MAX_MEMBERS_PER_SECTION) throw SectionsException("section_full")
        if (totalMembers(tree) + fresh.size > MAX_MEMBERS_TOTAL) throw SectionsException("too_many_members")
        var out = tree
        for (key in fresh) {
            val holder = memberIndex(out)[key]
            if (holder != null && holder != id) out = removeMemberFrom(out, holder, key, now)
        }
        return patch(out, id, now) { r ->
            if (kindOf(r) == null) r.addProperty("k", "u")
            val m = LinkedHashMap(plainMap(r.get("m")))
            val x = LinkedHashMap(plainMap(r.get("x")))
            for (key in keys) {
                if (key !in m) m[key] = now
                x.remove(key)
            }
            r.add("m", mapToJson(m))
            r.add("x", mapToJson(x))
        }
    }

    fun removeMemberFrom(tree: JsonObject, id: String, key: String, now: Long = System.currentTimeMillis()): JsonObject =
        patch(tree, id, now) { r ->
            val m = LinkedHashMap(plainMap(r.get("m")))
            val x = LinkedHashMap(plainMap(r.get("x")))
            m.remove(key)
            x[key] = now
            r.add("m", mapToJson(m))
            r.add("x", mapToJson(x))
        }

    /**
     * Take a chat out of whatever section holds it. This is the ONLY pruning
     * there is, and it happens on an explicit local action: removing a contact,
     * leaving a group, deleting a cross-island peer.
     *
     * ⚠ Never prune because a chat did not resolve while rendering. A second
     * device that has not synced yet, or one failed roster fetch, would then
     * delete the membership for the whole account. That is the hazard the
     * contacts mirror was written around, and it is worse here: there is no
     * server-side list to rebuild from.
     */
    fun forgetMember(tree: JsonObject, key: String, now: Long = System.currentTimeMillis()): JsonObject? {
        val holder = memberIndex(tree)[key] ?: return null
        return removeMemberFrom(tree, holder, key, now)
    }

    /** Stamp the tree as touched without changing anything anybody can see.
     *  See the write-timing note on [SectionsVault.forgetMember] for why this
     *  is not cosmetic. */
    fun touchTree(tree: JsonObject, now: Long = System.currentTimeMillis()): JsonObject =
        tree.deepCopy().apply { addProperty("w", now) }

    /**
     * Drop expired tombstones. Called before every write, never on read: a
     * read that pruned would fight with a device whose clock is behind.
     *
     * Age is not the only bound. Churn inside the 90 days is: a section whose
     * membership is edited every day accumulates an `x` entry per removal, and
     * a slot that grows past [MAX_WRITE_BYTES] stops being writable at all,
     * which is a worse failure than forgetting the oldest removal. So both maps
     * are also capped by COUNT, newest kept.
     */
    fun dropExpired(tree: JsonObject, now: Long = System.currentTimeMillis()): JsonObject {
        val out = tree.deepCopy()
        out.add("d", mapToJson(newestFew(plainMap(out.get("d")), now, MAX_SECTIONS)))
        for (r in records(out)) {
            val x = plainMap(r.get("x"))
            val keep = newestFew(x, now, MAX_MEMBERS_PER_SECTION)
            if (keep.size != x.size) r.add("x", mapToJson(keep))
        }
        return out
    }

    /** The `cap` newest entries of a tombstone map that are also inside the
     *  TTL, in the map's OWN key order: which entries survive is decided by
     *  age, and the order they are written in is left alone, because a
     *  reshuffle there is a write nobody asked for. */
    private fun newestFew(map: Map<String, Long>, now: Long, cap: Int): LinkedHashMap<String, Long> {
        val live = map.entries.filter { now - it.value <= TOMBSTONE_TTL_MS }
        var keep: Set<String>? = null
        if (live.size > cap) {
            // Newest first, ties by key so every client drops the same ones.
            val ranked = live.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            keep = ranked.take(cap).map { it.key }.toSet()
        }
        val out = LinkedHashMap<String, Long>()
        for (e in live) if (keep == null || e.key in keep) out[e.key] = e.value
        return out
    }

    /** The last gate before the seal. Refuse rather than truncate: a slot the
     *  island 413s is a slot that stops syncing for everyone. */
    fun assertWritable(tree: JsonObject) {
        if (treeBytes(tree) > MAX_WRITE_BYTES) throw SectionsException("too_large")
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private fun stamp(r: JsonObject, field: String, now: Long): JsonObject {
        val t = objOrNull(r.get("t"))?.deepCopy() ?: JsonObject()
        t.addProperty(field, now)
        return t
    }

    private fun objOrNull(e: JsonElement?): JsonObject? = e as? JsonObject

    /** Only the finite-number entries of a JSON object, as a Long map in the
     *  object's own key order. Anything else a stranger wrote there is not a
     *  timestamp and is not ours to interpret. */
    fun plainMap(e: JsonElement?): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        val o = e as? JsonObject ?: return out
        for ((k, v) in o.entrySet()) {
            val p = v as? JsonPrimitive ?: continue
            if (!p.isNumber) continue
            val d = runCatching { p.asDouble }.getOrNull() ?: continue
            if (d.isNaN() || d.isInfinite()) continue
            out[k] = runCatching { p.asLong }.getOrElse { d.toLong() }
        }
        return out
    }

    private fun mapToJson(map: Map<String, Long>): JsonObject =
        JsonObject().apply { for ((k, v) in map) addProperty(k, v) }

    private fun numberOr(e: JsonElement?, fallback: Long): Long {
        val p = e as? JsonPrimitive ?: return fallback
        if (!p.isNumber) return fallback
        return runCatching { p.asLong }.getOrDefault(fallback)
    }
}
