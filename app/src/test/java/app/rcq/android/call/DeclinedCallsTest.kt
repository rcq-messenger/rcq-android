package app.rcq.android.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list of calls this phone declined (#1047 review): a caller's missed-call
 *  marker for one of them must not become a "Missed call". */
class DeclinedCallsTest {

    @Test fun remembers_in_order_without_duplicates() {
        var raw: String? = null
        raw = DeclinedCalls.appended(raw, "A")
        raw = DeclinedCalls.appended(raw, "B")
        raw = DeclinedCalls.appended(raw, "A")
        assertEquals(listOf("B", "A"), DeclinedCalls.read(raw))
    }

    @Test fun oldest_falls_off_past_the_cap() {
        var raw: String? = null
        for (i in 0..DeclinedCalls.CAP) raw = DeclinedCalls.appended(raw, "call-$i")
        val ids = DeclinedCalls.read(raw)
        assertEquals(DeclinedCalls.CAP, ids.size)
        assertFalse("call-0" in ids)
        assertTrue("call-${DeclinedCalls.CAP}" in ids)
    }

    @Test fun empty_and_garbage_read_as_nothing() {
        assertTrue(DeclinedCalls.read(null).isEmpty())
        assertTrue(DeclinedCalls.read("").isEmpty())
        assertEquals(listOf("X"), DeclinedCalls.read(",,X,"))
    }
}
