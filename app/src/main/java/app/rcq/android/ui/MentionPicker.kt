package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The @-mention picker, shared by the composer and the edit sheet.
 *
 * It used to live inside ChatScreen's composer and nowhere else, which is why
 * a message being EDITED had no way to name anybody (#862, vss): the edit sheet
 * is its own field. It also answered only once a letter had been typed after
 * the `@`, listed the roster in whatever order the island sent it, and cut the
 * list at eight — so `@L` could miss `Li` while showing people whose names
 * merely contain an l.
 *
 * ⚠ It lives in its own file because ChatScreen's register map sits at the ART
 * verifier's limit; see [MessageLongPressOverlay].
 */
object Mentions {

    /** How many rows the picker will ever show. */
    private const val LIMIT = 8

    /**
     * The `@token` the caret is standing in, as (index of the `@`, the letters
     * after it). Null when the caret is not in one.
     *
     * The token is looked for BEHIND THE CARET, not at the end of the text: a
     * person editing a sentence puts the cursor back into the middle of it, and
     * the tail is somebody else's word. An empty partial counts - the list is
     * what tells you the `@` did something.
     */
    fun query(text: String, cursor: Int): Pair<Int, String>? {
        val end = cursor.coerceIn(0, text.length)
        var i = end
        while (i > 0) {
            val ch = text[i - 1]
            if (ch == '@') return (i - 1) to text.substring(i, end)
            // A name has no spaces, and an address like a@b is not a mention.
            if (ch.isWhitespace() || ch == '@') return null
            i--
        }
        return null
    }

    /**
     * Who to offer for `partial`, best first.
     *
     * A name that BEGINS with what was typed comes before one that merely
     * contains it, and within each half the order is alphabetical - the roster's
     * own order is arrival order, which to a reader is no order at all. The
     * number matches too, so `@123` finds a member whose nickname you never
     * learned.
     */
    fun <T> candidates(
        members: List<T>,
        partial: String,
        nickname: (T) -> String,
        uin: (T) -> Int,
        exclude: Int?,
    ): List<T> {
        val p = partial.lowercase()
        val hits = members.filter {
            uin(it) != exclude &&
                (p.isEmpty() || nickname(it).lowercase().contains(p) || uin(it).toString().contains(p))
        }
        return hits.sortedWith(
            compareBy(
                { if (p.isNotEmpty() && nickname(it).lowercase().startsWith(p)) 0 else 1 },
                { nickname(it).lowercase() },
                { uin(it) },
            ),
        ).take(LIMIT)
    }

    /** The text a pick leaves behind, and where the caret should stand in it. */
    fun applied(text: String, at: Int, partial: String, nickname: String): Pair<String, Int> {
        val head = text.substring(0, at) + "@" + nickname + " "
        return (head + text.substring((at + 1 + partial.length).coerceAtMost(text.length))) to head.length
    }
}

@Composable
fun <T> MentionPickerList(
    candidates: List<T>,
    nickname: (T) -> String,
    uin: (T) -> Int,
    onPick: (T) -> Unit,
) {
    if (candidates.isEmpty()) return
    val c = RcqTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)).background(c.bgSecondary),
    ) {
        LazyColumn {
            items(candidates, key = { uin(it) }) { mbr ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(mbr) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(nickname(mbr), color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${uin(mbr)}", color = c.textMono, fontSize = 12.sp)
                }
            }
        }
    }
}
