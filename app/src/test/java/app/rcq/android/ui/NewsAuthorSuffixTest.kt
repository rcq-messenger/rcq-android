package app.rcq.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one rule behind the dim suffix on a news post: an island's label is
 * shown next to the island's own name only when it says something the name
 * does not. Host JVM (`./gradlew testDebugUnitTest`), no emulator.
 */
class NewsAuthorSuffixTest {

    @Test
    fun stockLabelsAreDropped() {
        assertNull(newsAuthorSuffix("RCQ Team", "RCQ"))
        assertNull(newsAuthorSuffix("RCQ", "Island Two"))
        // An older island, or a fork, spelling the default its own way.
        assertNull(newsAuthorSuffix("rcq team", "Island Two"))
        assertNull(newsAuthorSuffix("  RCQ TEAM  ", "Island Two"))
    }

    @Test
    fun labelRepeatingTheIslandIsDropped() {
        assertNull(newsAuthorSuffix("Island Two", "Island Two"))
        assertNull(newsAuthorSuffix("island two", "Island Two"))
        // The island never answered: its name IS the host, and a label that
        // repeats the host is just as redundant.
        assertNull(newsAuthorSuffix("is2.rcq.app", "is2.rcq.app"))
    }

    @Test
    fun emptyAndMissingLabelsAreDropped() {
        assertNull(newsAuthorSuffix(null, "Island Two"))
        assertNull(newsAuthorSuffix("", "Island Two"))
        assertNull(newsAuthorSuffix("   ", "Island Two"))
    }

    @Test
    fun customLabelSurvivesTrimmed() {
        assertEquals("Support", newsAuthorSuffix(" Support ", "Island Two"))
        // A custom label that merely CONTAINS the default is still custom.
        assertEquals("RCQ Team Europe", newsAuthorSuffix("RCQ Team Europe", "Island Two"))
    }
}
