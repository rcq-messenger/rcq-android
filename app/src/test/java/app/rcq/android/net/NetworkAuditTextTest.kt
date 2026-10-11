package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The words on the network check, in the language the app is shown in.
 *
 * Every line of the audit used to be a Russian literal in NetworkAudit.kt, so a
 * person running RCQ in Spanish read "контроль ya.ru" and "наш адрес + чужое
 * имя" under a Spanish title. The audit itself needs a network and cannot run
 * here, so this pins the two things that made that possible: no line text left
 * in the code, and every resource it asks for present in every locale with the
 * arguments the code passes. The control hosts are named by position, never by
 * host, in every language.
 */
class NetworkAuditTextTest {

    private fun file(rel: String): File =
        listOf(File("src/main/$rel"), File("app/src/main/$rel")).firstOrNull { it.exists() }
            ?: error("not found: $rel (working dir ${File(".").absolutePath})")

    private val source by lazy { file("java/app/rcq/android/net/NetworkAudit.kt").readText() }

    /** Every `values*` directory that carries strings, the base one included. */
    private val locales: Map<String, Map<String, String>> by lazy {
        file("res").listFiles()!!
            .filter { it.name.startsWith("values") && File(it, "strings.xml").isFile }
            .associate { dir ->
                dir.name to Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(File(dir, "strings.xml").readText())
                    .associate { it.groupValues[1] to it.groupValues[2] }
            }
    }

    private val usedKeys by lazy {
        Regex("""R\.string\.(\w+)""").findAll(source).map { it.groupValues[1] }.toSortedSet()
    }

    private val placeholder = Regex("""%\d+\$[a-z]""")

    /** The source with comments removed and string literals kept. Naive about
     *  templates nested inside templates, which only shuffles text between
     *  "code" and "literal" and never drops any of it. */
    private fun withoutComments(src: String): String = buildString {
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3) + 3
                    append(src, i, end); i = end
                }
                src[i] == '"' || src[i] == '\'' -> {
                    var j = i + 1
                    while (src[j] != src[i]) { if (src[j] == '\\') j++; j++ }
                    append(src, i, j + 1); i = j + 1
                }
                else -> { append(src[i]); i++ }
            }
        }
    }

    @Test
    fun `no line of the audit is written in one language in the code`() {
        val code = withoutComments(source)
        val cyrillic = Regex("""[Ѐ-ӿ]+""").findAll(code).map { it.value }.toList()
        assertTrue("text left in NetworkAudit.kt: $cyrillic", cyrillic.isEmpty())
        // ...and the comments really were what held it, or the check proves nothing.
        assertTrue(Regex("""[Ѐ-ӿ]""").containsMatchIn(source))
    }

    @Test
    fun `every string the audit asks for exists in every locale with the same arguments`() {
        assertTrue("the audit asks for its strings", usedKeys.size > 30)
        val base = locales.getValue("values")
        assertTrue("more than one locale found", locales.size > 1)
        for (key in usedKeys) {
            val expected = placeholder.findAll(base[key] ?: error("$key missing in values")).map { it.value }.toSet()
            for ((dir, strings) in locales) {
                val text = strings[key]
                assertTrue("$key missing in $dir", text != null)
                assertEquals("$key arguments in $dir", expected, placeholder.findAll(text!!).map { it.value }.toSet())
                assertFalse("$key in $dir has a long dash", text.contains('—'))
            }
        }
    }

    @Test
    fun `the control hosts are numbered, not named, in every language`() {
        assertTrue("diag_audit_control" in usedKeys)
        for ((dir, strings) in locales) {
            assertEquals(
                "control label in $dir takes the position only",
                setOf("%1\$d"),
                placeholder.findAll(strings.getValue("diag_audit_control")).map { it.value }.toSet(),
            )
            for (key in usedKeys) {
                val text = strings.getValue(key)
                for (h in NetworkAudit.CONTROL_HOSTS) {
                    assertFalse("$key in $dir names $h", text.contains(h))
                }
            }
        }
        // The code passes a position, and the only other place a host could
        // reach the screen from is the probe line's name argument.
        assertTrue(source.contains("ctx.getString(R.string.diag_audit_control, i + 1)"))
    }
}
