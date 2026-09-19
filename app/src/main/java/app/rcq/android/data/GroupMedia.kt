package app.rcq.android.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.rcq.android.model.ChatMessage

/**
 * What counts as "the room's media", in what order, and what the card shows
 * before somebody asks for the rest (#989).
 *
 * Pure apart from decoding a thumbnail, so the rules can be proven without a
 * phone: which rows belong, newest first, and the preview cut.
 */
object GroupMedia {

    /** Tiles per row in the card. Three keeps a tile finger-sized on a 360dp
     *  screen and lets twelve fill exactly four rows. */
    const val COLUMNS = 3

    /** How many tiles the card draws before "show all". A room with a thousand
     *  pictures must not build a thousand bitmaps to draw a card somebody
     *  opened to change the slowmode. */
    const val PREVIEW = 12

    /** Pictures and clips of this room, newest first.
     *
     *  ⚠ `mediaId != null` is the whole membership test, and it is not
     *  fussiness: a row whose media never arrived, or was deleted, has no
     *  thumbnail and nothing to open, so a tile for it is a tile that answers
     *  every tap with a failure toast. Voice messages and documents are left
     *  out on purpose — this is a wall of pictures, and a grid of identical
     *  file glyphs tells nobody anything. */
    fun of(messages: List<ChatMessage>): List<ChatMessage> =
        messages.asSequence()
            .filter { (it.kind == "photo" || it.kind == "video") && it.mediaId != null }
            .sortedByDescending { it.sentAt }
            .toList()

    /** The thumbnail that came with the message, or null when it carried
     *  none. Never a network call: the bytes are already in the row. */
    fun thumb(m: ChatMessage): Bitmap? {
        val b64 = m.thumbB64?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}
