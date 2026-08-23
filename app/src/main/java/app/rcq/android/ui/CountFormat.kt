package app.rcq.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.rcq.android.R

/** Shared compact-count formatter (founder item 27).
 *
 *  ★ These thresholds are the CONTRACT the other clients mirror; iOS, the
 *  desktop app and the web chat must produce the same string for the same
 *  number, or the same group reads "1000" in one place and "1K" in another.
 *
 *  THE CANONICAL RULE, exactly (agreed 2026-08-23, all four clients):
 *    1. two thresholds, and only two: 1000 and 1000000.
 *    2. below 1000                  the plain number: 0, 1, 999
 *       1000 up to 999999           thousands with a K: 1000 -> "1K"
 *       1000000 and above           millions with an M: 1000000 -> "1M"
 *    3. one decimal, DROPPED when it is zero:
 *         1000 -> "1K", 1100 -> "1.1K", 2100 -> "2.1K", 12000 -> "12K"
 *    4. that decimal is TRUNCATED, never rounded: 1999 -> "1.9K", not "2K".
 *       A member count that rounds UP claims people who are not there, and
 *       "2K" on a group of 1999 is a number the user can catch us getting
 *       wrong. Truncation can only ever understate, which reads as honest.
 *       This is the point the four clients disagreed on and the one the web
 *       is being changed to match: TRUNCATE.
 *    5. the separator is always '.', in every locale.
 *    6. the suffixes are always the literal ASCII "K" and "M", in every
 *       locale. They are NOT in strings.xml and must never be moved there.
 *    7. negatives are not a thing a count can be; they pass through as-is
 *       rather than being dressed up.
 *
 *  Points 5 and 6 are the ones a well-meaning translation pass breaks. The
 *  separator is deliberately a plain '.', not the locale's, and the suffix a
 *  plain "K", because this is a compact badge: "1,1 тыс." in a Russian UI next
 *  to "1.1K" in a shared screenshot is the kind of drift that makes two
 *  clients look like two products. The surrounding words ARE translated, in
 *  R.string.members_compact and friends; only the number itself is not.
 *  If that is ever to change, it changes here and in the other three clients
 *  in the same week, not one client at a time. */
internal fun compactCount(n: Int): String = when {
    n < 0 -> n.toString()
    n < COMPACT_COUNT_THRESHOLD -> n.toString()
    n < 1_000_000 -> compactUnit(n, 1_000, "K")
    else -> compactUnit(n, 1_000_000, "M")
}

/** The number at which [compactCount] stops printing digits. 999 stays 999;
 *  1000 becomes "1K". */
internal const val COMPACT_COUNT_THRESHOLD = 1_000

/** Whole part + at most one truncated decimal + the unit suffix. Integer
 *  arithmetic throughout, so there is no floating-point rounding to argue
 *  with when another client reimplements this. */
private fun compactUnit(n: Int, unit: Int, suffix: String): String {
    val whole = n / unit
    val tenth = (n % unit) * 10 / unit
    return if (tenth == 0) "$whole$suffix" else "$whole.$tenth$suffix"
}

/** "N members", compacted from 1000 up (founder item 27).
 *
 *  999 stays 999; 1000 reads "1K" and 2100 reads "2.1K", so a big room cannot
 *  push a chat-list row or a preview card out of shape. [compactCount] above
 *  is the shared formatter whose thresholds the other three clients mirror
 *  exactly, and the compact form takes its own string because a plural cannot
 *  be selected on "2.1K".
 *
 *  EVERY place that counts members goes through here, chat list and chat
 *  header alike: the same room reading "12.5K" in the list and "12480" in
 *  the header two taps later is precisely the inconsistency the shared
 *  formatter exists to prevent, and two copies of these two lines in two
 *  files is how that drift starts. */
@Composable
internal fun memberCountLabel(n: Int): String =
    if (n >= COMPACT_COUNT_THRESHOLD) stringResource(R.string.members_compact, compactCount(n))
    else pluralStringResource(R.plurals.members, n, n)
