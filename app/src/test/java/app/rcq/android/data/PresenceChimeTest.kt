package app.rcq.android.data

import app.rcq.android.data.LocalStores.PresenceSoundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule behind #1030 ("звук срабатывает, когда никто не уходит") and the
 * half of #1029 that asks to silence one direction without the other.
 *
 * Every case here is a sentence from a report or from the audit that followed
 * it; a case with no such sentence behind it does not belong in this file.
 */
class PresenceChimeTest {

    private val NOW = 10_000_000L

    private fun flip(uin: Int, online: Boolean, favorite: Boolean = false, muted: Boolean = false) =
        PresenceChime.Flip(uin, online, favorite, muted)

    private fun decide(
        flips: List<PresenceChime.Flip>,
        mode: PresenceSoundMode = PresenceSoundMode.ALL,
        departures: Boolean = true,
        last: Map<Int, Long> = emptyMap(),
        now: Long = NOW,
    ) = PresenceChime.decide(flips, mode, departures, last, now)

    // ── one refresh, one sound ──────────────────────────────────────────

    @Test
    fun `a single arrival chimes`() {
        assertEquals(PresenceChime.Decision(134, true), decide(listOf(flip(134, true))))
    }

    @Test
    fun `a single departure chimes while departures are on`() {
        assertEquals(PresenceChime.Decision(134, false), decide(listOf(flip(134, false))))
    }

    @Test
    fun `nothing happened, nothing sounds`() {
        assertNull(decide(emptyList()))
    }

    @Test
    fun `a wave is the network, not a room filling up`() {
        // The reconnect case: the phone wakes and the island answers with a
        // roster whose presence column moved for everyone. This is the sound
        // the reporter hears with every flower on screen still green.
        val wave = (1..12).map { flip(it, online = it % 2 == 0) }
        assertNull(decide(wave))
    }

    @Test
    fun `three is still a room, four is weather`() {
        assertEquals(PresenceChime.BULK_FLOOR, 4)
        val three = listOf(flip(1, true), flip(2, false), flip(3, true))
        assertEquals(PresenceChime.Decision(1, true), decide(three))
        assertNull(decide(three + flip(4, false)))
    }

    @Test
    fun `the bulk test counts what survived the filters, not what arrived`() {
        // A wave of strangers must not swallow the one friend in it, or the
        // fix is the old bug with a new name.
        val wave = (1..20).map { flip(it, online = true) } + flip(999, online = true, favorite = true)
        assertEquals(
            PresenceChime.Decision(999, true),
            decide(wave, mode = PresenceSoundMode.FAVORITES),
        )
    }

    // ── who is worth a sound ────────────────────────────────────────────

    @Test
    fun `OFF is silent, whatever happened`() {
        assertNull(decide(listOf(flip(134, true)), mode = PresenceSoundMode.OFF))
        assertNull(decide(listOf(flip(134, false, favorite = true)), mode = PresenceSoundMode.OFF))
    }

    @Test
    fun `FAVORITES hears only favourites`() {
        assertNull(decide(listOf(flip(134, true)), mode = PresenceSoundMode.FAVORITES))
        assertEquals(
            PresenceChime.Decision(7, true),
            decide(listOf(flip(134, true), flip(7, true, favorite = true)), mode = PresenceSoundMode.FAVORITES),
        )
    }

    @Test
    fun `a muted thread is silent here too, the way the web client always was`() {
        assertNull(decide(listOf(flip(134, true, muted = true))))
        // ...and muting one contact does not silence another.
        assertEquals(
            PresenceChime.Decision(9, false),
            decide(listOf(flip(134, true, muted = true), flip(9, false))),
        )
    }

    // ── the two directions are two switches (#1029, #1030) ──────────────

    @Test
    fun `departures can be silenced without losing arrivals`() {
        assertNull(decide(listOf(flip(134, false)), departures = false))
        assertEquals(
            PresenceChime.Decision(134, true),
            decide(listOf(flip(134, true)), departures = false),
        )
    }

    @Test
    fun `with departures off, a departure does not even count toward the wave`() {
        val mixed = listOf(flip(1, false), flip(2, false), flip(3, false), flip(4, true))
        assertEquals(PresenceChime.Decision(4, true), decide(mixed, departures = false))
    }

    // ── the flap (one missed heartbeat is not a person leaving) ─────────

    @Test
    fun `a contact that just chimed stays quiet`() {
        val last = mapOf(134 to NOW - 1_000L)
        assertNull(decide(listOf(flip(134, false)), last = last))
    }

    @Test
    fun `and speaks again once the cooldown is spent`() {
        val last = mapOf(134 to NOW - PresenceChime.PER_CONTACT_COOLDOWN_MS)
        assertEquals(PresenceChime.Decision(134, false), decide(listOf(flip(134, false)), last = last))
    }

    @Test
    fun `one contact's cooldown does not silence another`() {
        val last = mapOf(134 to NOW - 1_000L)
        assertEquals(
            PresenceChime.Decision(77, true),
            decide(listOf(flip(134, true), flip(77, true)), last = last),
        )
    }

    @Test
    fun `a contact with no history is not held back by the clock`() {
        // `now` early in a boot is a small number; a naive "now - 0 >= cooldown"
        // would silence the first chime after every restart.
        assertEquals(
            PresenceChime.Decision(134, true),
            decide(listOf(flip(134, true)), now = 500L),
        )
    }

    // ── the pick is deliberate, not the island's row order ──────────────

    @Test
    fun `a favourite outranks a stranger in the same refresh`() {
        assertEquals(
            PresenceChime.Decision(88, false),
            decide(listOf(flip(2, true), flip(88, false, favorite = true))),
        )
    }

    @Test
    fun `among equals an arrival outranks a departure`() {
        assertEquals(
            PresenceChime.Decision(5, true),
            decide(listOf(flip(3, false), flip(5, true))),
        )
    }

    @Test
    fun `and the same input always picks the same contact`() {
        val flips = listOf(flip(31, false), flip(12, false), flip(20, false))
        repeat(5) { assertEquals(PresenceChime.Decision(12, false), decide(flips)) }
        // Order of arrival must not change the answer: the island does not sort
        // its roster, and two reads can hand the same three rows back in a
        // different order.
        assertEquals(PresenceChime.Decision(12, false), decide(flips.reversed()))
    }
}
