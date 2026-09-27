package app.rcq.android.data

import app.rcq.android.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/** The chat viewer turns through every picture and clip, not one album (#1052). */
class ChatMediaPagesTest {

    private fun msg(id: String, kind: String = "photo", album: String? = null, media: String? = "m$id") =
        ChatMessage(id = id, peerUin = 7, fromMe = true, body = "", sentAt = id.toLong(),
                    kind = kind, mediaId = media, albumId = album)

    // The report: a batch of four, then a batch of five, with a word and a
    // clip in between for good measure.
    private val thread =
        (1..4).map { msg("$it", album = "a") } +
            msg("5", kind = "text", media = null) +
            msg("6", kind = "video") +
            (7..11).map { msg("$it", album = "b") }

    @Test
    fun `both batches and the clip, in chat order`() {
        assertEquals(
            listOf("1", "2", "3", "4", "6", "7", "8", "9", "10", "11"),
            ChatMediaPages.of(thread).map { it.id },
        )
    }

    @Test
    fun `opens on the tapped picture, wherever its batch is`() {
        val (pages, at) = ChatMediaPages.around(thread, thread.first { it.id == "8" })
        assertEquals(10, pages.size)
        assertEquals("8", pages[at].id)
        val (_, first) = ChatMediaPages.around(thread, thread.first())
        assertEquals(0, first)
    }

    @Test
    fun `rows with nothing to show are not pages`() {
        val gone = thread + msg("12", media = null) + msg("13", kind = "voice") + msg("14", kind = "file")
        assertEquals(10, ChatMediaPages.of(gone).size)
    }

    @Test
    fun `a tapped item that is not a page opens alone rather than on somebody else's`() {
        val pending = msg("99", media = null)
        val (pages, at) = ChatMediaPages.around(thread + pending, pending)
        assertEquals(listOf("99"), pages.map { it.id })
        assertEquals(0, at)
    }
}
