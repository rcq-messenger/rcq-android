package app.rcq.android.backup

import app.rcq.android.backup.BackupService.toMessageOrNull
import app.rcq.android.backup.BackupService.toRecord
import app.rcq.android.model.ChatMessage
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Report 1063: "forwarded from" survives an archive, under the key iOS
 *  writes (`BackupRecordMapping.swift` `forwarded_from`). */
class BackupForwardTest {

    private val gson = Gson()

    private fun line(m: ChatMessage): String = gson.toJson(m.toRecord())

    private fun back(json: String): ChatMessage? =
        gson.fromJson(json, BackupService.Record::class.java).toMessageOrNull()

    @Test fun a_forward_keeps_its_label_through_an_archive() {
        val m = ChatMessage(id = "A", peerUin = 7, fromMe = true, body = "hi", sentAt = 5L, fwdName = "Alice")
        val json = line(m)
        assertEquals("Alice", gson.fromJson(json, JsonObject::class.java).get("forwarded_from").asString)
        assertEquals("Alice", back(json)!!.fwdName)
    }

    @Test fun not_a_forward_writes_no_key() {
        val json = line(ChatMessage(id = "B", peerUin = 7, fromMe = false, body = "x", sentAt = 1L))
        assertFalse(json.contains("forwarded_from"))
        assertNull(back(json)!!.fwdName)
    }

    /** An archive is a file anyone can hand this client: the label is
     *  cleaned on the way in like the wire field. */
    @Test fun an_ios_line_is_read_and_cleaned() {
        val json = """{"id":"C","peer":9,"from_me":false,"sent_at":3,"kind":"photo","body":"","forwarded_from":"Bob\n‮evil"}"""
        assertEquals("Bob  evil", back(json)!!.fwdName)
        assertNull(back("""{"id":"D","peer":9,"sent_at":3,"forwarded_from":"   "}""")!!.fwdName)
    }
}
