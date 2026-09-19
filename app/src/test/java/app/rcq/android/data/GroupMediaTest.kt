package app.rcq.android.data

import app.rcq.android.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the room's media card shows, and in what order (#989). */
class GroupMediaTest {

    private fun msg(id: String, kind: String, at: Long, media: String? = "m$id") =
        ChatMessage(id = id, peerUin = 0, fromMe = false, body = "", sentAt = at,
                    kind = kind, mediaId = media, groupId = 5)

    private val room = listOf(
        msg("a", "photo", 100),
        msg("b", "text", 200, media = null),
        msg("c", "video", 300),
        msg("d", "voice", 400),
        msg("e", "photo", 500),
        msg("f", "photo", 600, media = null),   // media never arrived
        msg("g", "file", 700),
    )

    @Test
    fun `pictures and clips only, newest first`() {
        assertEquals(listOf("e", "c", "a"), GroupMedia.of(room).map { it.id })
    }

    @Test
    fun `a row whose media is gone is not a tile`() {
        // A tile for it would answer every tap with a failure toast.
        assertTrue(GroupMedia.of(room).none { it.id == "f" })
    }

    @Test
    fun `voice and documents stay out`() {
        // A grid of identical file glyphs tells nobody anything.
        assertTrue(GroupMedia.of(room).none { it.kind == "voice" || it.kind == "file" })
    }

    @Test
    fun `a room with nothing to show returns nothing, rather than throwing`() {
        assertEquals(emptyList<String>(), GroupMedia.of(emptyList()).map { it.id })
        assertEquals(emptyList<String>(), GroupMedia.of(listOf(msg("x", "text", 1, null))).map { it.id })
    }

    @Test
    fun `the preview is a whole number of rows`() {
        // Otherwise the last row of the collapsed card is a ragged edge that
        // reads as "something failed to load".
        assertEquals(0, GroupMedia.PREVIEW % GroupMedia.COLUMNS)
        assertTrue(GroupMedia.PREVIEW >= GroupMedia.COLUMNS * 3)
    }

    @Test
    fun `order does not depend on the order the rows arrived in`() {
        val shuffled = room.reversed()
        assertEquals(GroupMedia.of(room).map { it.id }, GroupMedia.of(shuffled).map { it.id })
    }
}
