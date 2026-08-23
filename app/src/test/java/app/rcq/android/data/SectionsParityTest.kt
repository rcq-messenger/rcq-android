package app.rcq.android.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-for-byte parity of the sections merge with the web's `sections.ts`.
 *
 * ⚠⚠ This is not a nicety. The merge decides whether a client WRITES: both the
 * read path and the write path ask [Sections.sameContent], which is
 * `merge(x, x)` on each side compared as text. Two clients whose normal forms
 * differ by one key therefore each think the other's blob is missing something
 * of theirs, and they spend the account's 240 puts an hour rewriting the slot
 * at each other until the island 429s. That is a live outage of the feature on
 * every device of the account, and it looks like nothing at all in a unit test
 * of either client on its own.
 *
 * The expected strings below were produced by the web's own production code:
 *
 *     node cli/build.mjs
 *     node -e "import('./cli/dist/vault.mjs').then(({mergeSections:m}) => {
 *                const x = m(A, B); console.log(JSON.stringify(m(x, x))) })"
 *
 * so a change to either implementation that moves a key, drops an empty map or
 * reorders a record fails here rather than in the field.
 */
class SectionsParityTest {

    private fun normalForm(a: String, b: String): String {
        val m = Sections.merge(JsonParser.parseString(a).asJsonObject, JsonParser.parseString(b).asJsonObject)
        return Sections.merge(m, m).toString()
    }

    @Test fun per_field_lww_and_a_built_in_record() {
        assertEquals(
            """{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Home","o":7168,"p":1,"h":0,"t":{"n":1756000001000,"o":1756000000000,"p":1756000000000},"m":{"p:100200":1756000000000,"p:4471@is2.rcq.app":1756000000000},"x":{"p:770":1755000000000}},{"id":"sys.archive","k":"d","o":7168,"p":1,"t":{"p":1756000000000}}],"d":{"b7c1d2e3":1755900000000},"w":1756000000000}""",
            normalForm(
                """{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Work","o":7168,"p":1,"h":0,"t":{"n":1756000000000,"o":1756000000000,"p":1756000000000,"h":0},"m":{"p:100200":1756000000000,"p:4471@is2.rcq.app":1756000000000},"x":{"p:770":1755000000000}}],"d":{"b7c1d2e3":1755900000000},"w":1756000000000}""",
                """{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Home","o":9216,"t":{"n":1756000001000,"o":1}},{"id":"sys.archive","k":"d","o":7168,"p":1,"t":{"o":0,"p":1756000000000}}],"d":{},"w":5}""",
            ),
        )
    }

    @Test fun one_chat_one_section() {
        assertEquals(
            """{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"A","t":{},"m":{},"x":{"p:5":100}},{"id":"bbbbbbbb","k":"u","n":"B","t":{},"m":{"p:5":200},"x":{}}],"d":{},"w":0}""",
            normalForm(
                """{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"A","m":{"p:5":100},"x":{}}],"d":{},"w":0}""",
                """{"v":1,"s":[{"id":"bbbbbbbb","k":"u","n":"B","m":{"p:5":200},"x":{}}],"d":{},"w":0}""",
            ),
        )
    }

    @Test fun a_touched_section_outlives_its_tombstone_and_the_tombstone_stays() {
        assertEquals(
            """{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Work","t":{"n":300}}],"d":{"aaaaaaaa":200},"w":0}""",
            normalForm(
                """{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Work","t":{"n":300}}],"d":{},"w":0}""",
                """{"v":1,"s":[],"d":{"aaaaaaaa":200},"w":0}""",
            ),
        )
    }

    @Test fun unknown_keys_ride_along_in_the_same_places() {
        assertEquals(
            """{"qq":9,"v":1,"s":[{"zz":{"future":1},"q":"y","id":"aaaaaaaa","k":"u","n":"A","t":{}}],"d":{},"w":0}""",
            normalForm(
                """{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"A","zz":{"future":1},"q":"x"}],"d":{},"w":0,"qq":7}""",
                """{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"A","q":"y"}],"d":{},"w":0,"qq":9}""",
            ),
        )
    }

    @Test fun a_built_in_this_client_does_not_render_is_written_back_unchanged() {
        assertEquals(
            """{"v":1,"s":[{"id":"sys.saved","k":"d","o":4096,"t":{"o":1756000000000}}],"d":{},"w":0}""",
            normalForm(
                """{"v":1,"s":[{"id":"sys.saved","k":"d","o":4096,"t":{"o":1756000000000}}],"d":{},"w":0}""",
                """{"v":1,"s":[],"d":{},"w":0}""",
            ),
        )
    }

    /**
     * A JSON `null` is an ABSENT value on both sides, not a value that ranks by
     * its serialised text. A Swift-style encoder writes `null` for a nil
     * optional rather than omitting the key, and `k` has no timestamp, so this
     * tie-break is the only thing that ever decides it: the client that ranked
     * `"null"` (0x6e, above every digit and above `"`) deleted `k == "u"`, the
     * other kept it, and the two then rewrote the slot at each other forever.
     * Expected strings from the web's own build, both orders.
     */
    @Test fun a_json_null_loses_to_a_value_and_reads_as_absent() {
        val expected =
            """{"zz":5,"id":"aaaaaaaa","k":"u","n":"Work","o":7168,"p":1,"t":{"n":1756000000000}}"""
        val withNulls =
            """{"v":1,"s":[{"id":"aaaaaaaa","k":null,"n":"Work","o":null,"p":null,"t":{"n":1756000000000},"zz":null}],"d":{},"w":0}"""
        val withValues =
            """{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Work","o":7168,"p":1,"t":{"n":1756000000000},"zz":5}],"d":{},"w":0}"""
        assertEquals("""{"v":1,"s":[$expected],"d":{},"w":0}""", normalForm(withNulls, withValues))
        assertEquals("""{"v":1,"s":[$expected],"d":{},"w":0}""", normalForm(withValues, withNulls))
    }

    /** Null on BOTH sides: a known field goes away like a missing key, an
     *  unknown one is carried through as the null it is. */
    @Test fun a_json_null_on_both_sides_drops_the_known_field_and_keeps_the_stranger() {
        val tree = """{"v":1,"s":[{"id":"aaaaaaaa","k":null,"t":{},"cc":null}],"d":{},"w":0}"""
        assertEquals(
            """{"v":1,"s":[{"cc":null,"id":"aaaaaaaa","t":{}}],"d":{},"w":0}""",
            normalForm(tree, tree),
        )
    }
}
