package app.rcq.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message store's fresh schema and its upgrade path describe the same
 * table (report 1063 added `fwd_name`, v21). SQLCipher cannot load on the JVM,
 * so this holds the SQL itself up against each other; the on-device twin is
 * androidTest `MessageDbFwdNameMigrationTest`.
 */
class MessageDbSchemaTest {

    /** The columns the very first table had: nothing in the upgrade path adds
     *  them, everything after them is added by exactly one step. */
    private val v1 = listOf("id", "peer_uin", "from_me", "body", "sent_at")

    private val createColumns: List<String> =
        MessageDbSchema.CREATE_MESSAGES
            .substringAfter("(").substringBeforeLast(")")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { it.substringBefore(' ') }

    private fun addedBy(statements: List<String>): List<String> =
        statements.mapNotNull { Regex("""^ALTER TABLE messages ADD COLUMN (\w+) """).find(it)?.groupValues?.get(1) }

    @Test fun v21_adds_the_forward_label_and_nothing_else() {
        assertEquals(21, MessageDbSchema.VERSION)
        assertEquals(listOf("ALTER TABLE messages ADD COLUMN fwd_name TEXT"), MessageDbSchema.upgradeStatements(20))
        assertTrue(MessageDbSchema.upgradeStatements(21).isEmpty())
    }

    @Test fun an_upgraded_table_has_exactly_the_fresh_columns_in_order() {
        assertEquals(createColumns, v1 + addedBy(MessageDbSchema.upgradeStatements(1)))
    }

    @Test fun every_upgrade_start_reaches_the_same_columns() {
        // From any stamped version, the steps that run add exactly what that
        // version was missing, each once.
        for (from in 1 until MessageDbSchema.VERSION) {
            val added = addedBy(MessageDbSchema.upgradeStatements(from))
            assertEquals("from v$from", added.distinct(), added)
            assertTrue("from v$from", createColumns.containsAll(added))
            assertEquals("from v$from", createColumns.takeLast(added.size), added)
        }
    }

    @Test fun the_reader_reads_every_column_in_table_order() {
        assertEquals(createColumns, MessageDbSchema.COLUMNS)
        // MessageDb.all() reads the label by index.
        assertEquals(27, MessageDbSchema.COLUMNS.indexOf("fwd_name"))
        assertTrue(MessageDbSchema.SELECT_ALL.startsWith("SELECT id, peer_uin, "))
        assertTrue(MessageDbSchema.SELECT_ALL.contains(", call_id, fwd_name FROM messages"))
    }

    @Test fun a_fresh_database_gets_every_side_table() {
        val fresh = MessageDbSchema.createStatements()
        listOf(
            MessageDbSchema.DELETED_IDS_DDL,
            MessageDbSchema.DECOY_CONTACTS_DDL,
            MessageDbSchema.MEMBER_NAMES_DDL,
            MessageDbSchema.CALL_ID_INDEX_DDL,
        ).forEach { assertTrue(it, it in fresh) }
        // ...and so does the oldest database, on its way up.
        val old = MessageDbSchema.upgradeStatements(1)
        listOf(
            MessageDbSchema.DELETED_IDS_DDL,
            MessageDbSchema.DECOY_CONTACTS_DDL,
            MessageDbSchema.MEMBER_NAMES_DDL,
            MessageDbSchema.CALL_ID_INDEX_DDL,
        ).forEach { assertTrue(it, it in old) }
    }
}
