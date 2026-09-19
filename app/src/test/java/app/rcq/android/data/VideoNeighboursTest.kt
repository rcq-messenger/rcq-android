package app.rcq.android.data

import app.rcq.android.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stepping from one clip to the next inside a conversation (#1027). */
class VideoNeighboursTest {

    private fun msg(id: String, kind: String = "video", media: String? = "m$id") =
        ChatMessage(id = id, peerUin = 7, fromMe = false, body = "", sentAt = id.toLong(),
                    kind = kind, mediaId = media)

    private val thread = listOf(
        msg("1"),
        msg("2", kind = "text", media = null),
        msg("3"),
        msg("4", kind = "photo"),
        msg("5"),
    )

    @Test
    fun `only videos with bytes are in the walk`() {
        assertEquals(listOf("1", "3", "5"), VideoNeighbours.clips(thread).map { it.id })
    }

    @Test
    fun `a clip whose media is gone is skipped`() {
        val gone = thread + msg("6", media = null)
        assertEquals(listOf("1", "3", "5"), VideoNeighbours.clips(gone).map { it.id })
    }

    @Test
    fun `the middle clip has both neighbours, and they are the videos, not the rows beside it`() {
        val (prev, next) = VideoNeighbours.around(thread, "3")
        assertEquals("1", prev?.id)
        assertEquals("5", next?.id)
    }

    @Test
    fun `the first clip has nothing before it`() {
        val (prev, next) = VideoNeighbours.around(thread, "1")
        assertNull(prev)
        assertEquals("3", next?.id)
    }

    @Test
    fun `the last clip has nothing after it`() {
        val (prev, next) = VideoNeighbours.around(thread, "5")
        assertEquals("3", prev?.id)
        assertNull(next)
    }

    @Test
    fun `a single clip has no neighbours at all, so no arrows are drawn`() {
        val one = listOf(msg("9"))
        val (prev, next) = VideoNeighbours.around(one, "9")
        assertNull(prev)
        assertNull(next)
    }

    @Test
    fun `a clip this thread does not hold walks nowhere`() {
        // A forward preview, or a row deleted while the viewer was open. The
        // wrong answer here is "the first clip", which would teleport somebody
        // to the top of the conversation on a swipe.
        val (prev, next) = VideoNeighbours.around(thread, "404")
        assertNull(prev)
        assertNull(next)
    }

    @Test
    fun `a thread with no video at all is empty rather than throwing`() {
        val talk = listOf(msg("1", kind = "text", media = null))
        assertEquals(emptyList<String>(), VideoNeighbours.clips(talk).map { it.id })
        assertEquals(null to null, VideoNeighbours.around(talk, "1"))
    }
}
