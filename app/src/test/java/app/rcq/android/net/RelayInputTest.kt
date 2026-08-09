package app.rcq.android.net

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one branch in "Add relay" a person can get wrong, checked without pasting
 * anything by hand. `classify` deliberately does not touch Android APIs, so
 * this runs on the JVM.
 *
 * ⚠ A malformed rcq-relay:// must come out Unusable, NOT AccessKey. Falling
 * through would turn a typo in a shared node into a stored subscription key
 * that never works and that the user has no way to connect to what they did.
 */
class RelayInputTest {

    @Test fun blankIsUnusable() {
        assertTrue(RelayInput.classify("") is RelayInput.Unusable)
        assertTrue(RelayInput.classify("    ") is RelayInput.Unusable)
    }

    @Test fun shortStringIsNotAKey() {
        assertTrue(RelayInput.classify("abc") is RelayInput.Unusable)
        assertTrue(RelayInput.classify("x".repeat(RelayInput.MIN_KEY_LENGTH - 1)) is RelayInput.Unusable)
    }

    @Test fun longOpaqueStringIsAKey() {
        val k = "myio3eLbeYLIWdlX4KLXga1kLgVMmnIM"
        val out = RelayInput.classify(k)
        assertTrue(out is RelayInput.AccessKey && out.key == k)
    }

    @Test fun keyIsTrimmed() {
        val out = RelayInput.classify("  myio3eLbeYLIWdlX4KLXga1kLgVMmnIM \n")
        assertTrue(out is RelayInput.AccessKey && !out.key.contains(" "))
    }

    @Test fun malformedLinkNeverBecomesAKey() {
        // Long enough to pass the length test, and a URL, so the only correct
        // answer is "unusable".
        assertTrue(RelayInput.classify("rcq-relay://vless?missing=everything") is RelayInput.Unusable)
        assertTrue(RelayInput.classify("https://example.com/some/long/path/here") is RelayInput.Unusable)
    }
}
