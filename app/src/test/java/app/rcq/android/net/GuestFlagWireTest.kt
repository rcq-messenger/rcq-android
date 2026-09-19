package app.rcq.android.net

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an island's reply can and cannot say about the active account being a
 * guest copy (spec 2026-09-15, 12.1).
 *
 * This is worth pinning because of what the flag DOES rather than what it is: a
 * true here stops this device registering a push endpoint at all
 * (Session.registerPushEndpoint, Push.registerWithBackend), so a false positive
 * silences an ordinary account's notifications completely, with no error on
 * either side. When #1023 arrived the day after 0.196 shipped that gate, this
 * was the first hypothesis, and the reason it can be ruled out is the table
 * below: every unknown resolves to "ordinary account", so only an island that
 * says `guest: true` in as many words can ever set it.
 *
 * ⚠ The docstring on Session.notePrimaryGuest used to claim that `false` came
 * only from a reply that said so. It does not, and that is the safe direction:
 * absent means false. This test is what keeps it that way, since the field
 * carries a Kotlin default and deleting the default would compile, pass every
 * other test, and hand a stale true to anyone who talked to an older island
 * once.
 */
class GuestFlagWireTest {

    private fun reply(json: String): RcqApi.RegisterResponse =
        Gson().fromJson(json, RcqApi.RegisterResponse::class.java)

    /** An island too old to know about guest copies at all: is2.rcq.app was
     *  still on 2026.09.04.11 while this was being reported. */
    @Test fun an_island_that_never_heard_of_guests_means_not_a_guest() {
        assertFalse(reply("""{"uin":1234,"token":"t"}""").guest)
    }

    @Test fun an_explicit_denial_means_not_a_guest() {
        assertFalse(reply("""{"uin":1234,"token":"t","guest":false}""").guest)
    }

    @Test fun only_an_explicit_yes_sets_it() {
        assertTrue(reply("""{"uin":1234,"token":"t","guest":true}""").guest)
    }

    /** ⚠ Gson allocates a data class through Unsafe and leaves a field the JSON
     *  does not mention at its default, so `null` is not "keep what you had"
     *  either. Both of these arrive as false, which is the answer that cannot
     *  silence anybody. */
    @Test fun a_null_or_garbled_flag_falls_to_not_a_guest() {
        assertFalse(reply("""{"uin":1234,"token":"t","guest":null}""").guest)
    }

    /** A refresh against a BACKUP island is an ordinary reply on this path, and
     *  a guest copy is never adopted as a backup home. Worth stating as a case,
     *  since "we asked a different island" is the one route by which a resident
     *  could plausibly be told they are a guest. */
    @Test fun a_plain_reply_from_any_island_carries_no_guest_claim() {
        assertFalse(reply("""{"uin":1234,"token":"t","moved_from":99}""").guest)
    }
}
