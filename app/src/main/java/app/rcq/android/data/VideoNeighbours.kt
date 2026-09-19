package app.rcq.android.data

import app.rcq.android.model.ChatMessage

/**
 * The clip before and after the one on screen, within one conversation.
 *
 * Report #1027 asks to swipe from one video to the next "если несколько
 * видосов отправлено". The set is every video in the open thread rather than
 * the album the clip arrived in: somebody who sent three clips one after
 * another sent three messages, and from the viewer's point of view they are
 * the same "next one".
 *
 * Pure so the order and the edges can be proven without a phone.
 */
object VideoNeighbours {

    /** Playable clips in the thread, oldest first.
     *
     *  ⚠ `mediaId != null` is not fussiness: a row whose media is gone opens a
     *  black rectangle and a failure toast, and stepping into one by swiping
     *  reads as the swipe being broken. */
    fun clips(messages: List<ChatMessage>): List<ChatMessage> =
        messages.filter { it.kind == "video" && it.mediaId != null }

    /** (previous, next) around [currentId], either of which may be null at the
     *  ends — and BOTH of which are null when the current clip is not in the
     *  list at all, which happens when it was opened from somewhere this
     *  thread does not hold (a forward preview, a deleted row). */
    fun around(messages: List<ChatMessage>, currentId: String): Pair<ChatMessage?, ChatMessage?> {
        val list = clips(messages)
        val at = list.indexOfFirst { it.id == currentId }
        if (at < 0) return null to null
        return list.getOrNull(at - 1) to list.getOrNull(at + 1)
    }
}
