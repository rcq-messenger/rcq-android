package app.rcq.android.data

import app.rcq.android.model.ChatMessage

/**
 * The pages of the chat's full-screen viewer: every picture and clip of the
 * open conversation, in the order the chat draws them (#1052).
 *
 * "Первым сообщением отправил 4 картинки, следующим 5, могу смотреть только 4,
 * чтоб следующие 5 посмотреть нужно выйти с режима просмотра." The viewer was
 * handed the ONE album that was tapped, so its last page was the edge of that
 * batch and the next batch was a trip back to the chat. Its pages are the
 * whole conversation now, pictures and clips alike, opening on the tapped one.
 *
 * ⚠ What this costs on a long chat: nothing up front. The thread is already in
 * memory (the chat is drawn from it), so the pages are references to rows that
 * exist anyway; the pager fetches a picture only when its page comes into view,
 * keeps bytes for two pages either side, and fetches a clip only when its play
 * disc is tapped (AlbumPagerViewer).
 *
 * Pure so the order and the edges can be proven without a phone.
 */
object ChatMediaPages {

    /** Pictures and clips in chat order, oldest first.
     *
     *  ⚠ `mediaId != null`, as the media walls ask: a row whose media never
     *  arrived has nothing to show, and a swipe into a page that can only fail
     *  reads as the swipe being broken ([VideoNeighbours.clips] says the same
     *  for clips). */
    fun of(messages: List<ChatMessage>): List<ChatMessage> =
        messages.filter { (it.kind == "photo" || it.kind == "video") && it.mediaId != null }

    /** The pages and where [tapped] stands among them. A tapped item that is
     *  not a page (no media id yet) opens on its own rather than on somebody
     *  else's picture. */
    fun around(messages: List<ChatMessage>, tapped: ChatMessage): Pair<List<ChatMessage>, Int> {
        val pages = of(messages)
        val at = pages.indexOfFirst { it.id == tapped.id }
        return if (at >= 0) pages to at else listOf(tapped) to 0
    }
}
