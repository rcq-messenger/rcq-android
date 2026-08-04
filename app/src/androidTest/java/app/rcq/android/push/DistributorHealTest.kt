package app.rcq.android.push

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.rcq.android.net.RcqApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When may the app move a device off the distributor its user chose?
 *
 * Only when the server cannot REACH the push host at all — which is what
 * happened on 2026-08-01, when ntfy.sh stopped accepting TCP from the flagship
 * droplet and 732 of 877 Android endpoints went dark. A distributor that
 * ANSWERS, even with a refusal, is working as designed and must be left alone:
 * ntfy replies 507 for every phone that is simply switched off, and three
 * accounts on record run their own instance perfectly happily.
 */
@RunWith(AndroidJUnit4::class)
class DistributorHealTest {

    private fun health(vararg rows: RcqApi.PushHealthRow) = RcqApi.PushHealth(rows.toList())

    private fun row(host: String, error: String?) =
        RcqApi.PushHealthRow(platform = "android-up", host = host, last_error = error)

    // ── what counts as "unreachable" ─────────────────────────────────

    @Test
    fun transportFailuresAreUnreachable() {
        assertTrue(Push.isUnreachableError("ConnectTimeout"))
        assertTrue(Push.isUnreachableError("ConnectError"))
        assertTrue(Push.isUnreachableError("ReadTimeout"))
    }

    @Test
    fun httpStatusesAreNot() {
        // The host answered. 507 = "nobody subscribed right now" — the normal
        // state of a phone that is switched off, and the exact case that must
        // NOT drag the user onto another distributor.
        assertFalse(Push.isUnreachableError("507"))
        assertFalse(Push.isUnreachableError("429"))
        assertFalse(Push.isUnreachableError("400"))
    }

    @Test
    fun healthySilenceIsNot() {
        assertFalse(Push.isUnreachableError(null))
        assertFalse(Push.isUnreachableError(""))
        assertFalse(Push.isUnreachableError("   "))
    }

    // ── the decision ─────────────────────────────────────────────────

    @Test
    fun movesOffAHostTheServerCannotReach() {
        assertTrue(Push.shouldSwitchToEmbedded("ntfy.sh", health(row("ntfy.sh", "ConnectTimeout"))))
    }

    @Test
    fun staysOnAHostThatAnswers() {
        assertFalse(Push.shouldSwitchToEmbedded("ntfy.sh", health(row("ntfy.sh", "507"))))
        assertFalse(Push.shouldSwitchToEmbedded("ntfy.sh", health(row("ntfy.sh", null))))
    }

    @Test
    fun leavesASelfHostedInstanceAlone() {
        val h = health(row("ntfy.sh", "ConnectTimeout"), row("nc.sonawo.de", null))
        assertFalse("this device is on its own working instance", Push.shouldSwitchToEmbedded("nc.sonawo.de", h))
    }

    @Test
    fun ignoresRowsForOtherDevices() {
        // Someone else's dead endpoint on the same account says nothing about
        // the host THIS device registered under.
        assertFalse(Push.shouldSwitchToEmbedded("push.rcq.app", health(row("ntfy.sh", "ConnectTimeout"))))
    }

    @Test
    fun matchesHostCaseInsensitively() {
        assertTrue(Push.shouldSwitchToEmbedded("NTFY.SH", health(row("ntfy.sh", "ConnectTimeout"))))
    }

    @Test
    fun withoutAnEndpointThereIsNothingToJudge() {
        assertFalse(Push.shouldSwitchToEmbedded(null, health(row("ntfy.sh", "ConnectTimeout"))))
        assertFalse(Push.shouldSwitchToEmbedded("", health(row("ntfy.sh", "ConnectTimeout"))))
    }

    @Test
    fun anIosRowNeverDrivesThisDecision() {
        val h = health(RcqApi.PushHealthRow(platform = "ios", host = null, last_error = "BadDeviceToken"))
        assertFalse(Push.shouldSwitchToEmbedded("ntfy.sh", h))
    }
}
