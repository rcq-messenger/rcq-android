package app.rcq.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** [sendGate]: a background media send finishes only as the account that
 *  started it, waits out a lock, and is never lost to one. */
class SendOwnershipTest {
    private val a = SendOwner(accountId = "A", decoy = false, epoch = 3)
    private val home = SendScene(activeId = "A", locked = false, inDecoy = false, dbOpenFor = "A", epoch = 3, ownerKnown = true)

    @Test fun sameAccountOpenGoes() = assertEquals(SendGate.GO, sendGate(a, home))

    @Test fun lockedWaits() = assertEquals(SendGate.WAIT, sendGate(a, home.copy(locked = true, dbOpenFor = null, epoch = 4)))

    @Test fun unlockedIntoTheSameAccountGoesAlthoughTheEpochMoved() =
        assertEquals(SendGate.GO, sendGate(a, home.copy(epoch = 5)))

    @Test fun unlockedButDatabaseNotBoundYetWaits() =
        assertEquals(SendGate.WAIT, sendGate(a, home.copy(epoch = 5, dbOpenFor = null)))

    @Test fun anotherAccountInFrontWaits() =
        assertEquals(SendGate.WAIT, sendGate(a, home.copy(activeId = "B", dbOpenFor = "B", epoch = 4)))

    @Test fun backInTheOwnerAccountAfterASwitchGoes() =
        assertEquals(SendGate.GO, sendGate(a, home.copy(epoch = 6)))

    @Test fun decoyInFrontWaitsAndNeverGoes() =
        assertEquals(SendGate.WAIT, sendGate(a, home.copy(inDecoy = true, epoch = 5)))

    @Test fun legacyDecoyOnTheSameAccountFileStillWaits() =
        // A legacy decoy rides the account's own database path.
        assertEquals(SendGate.WAIT, sendGate(a, home.copy(inDecoy = true)))

    @Test fun ownerAccountRemovedDrops() =
        assertEquals(SendGate.DROP, sendGate(a, home.copy(activeId = "B", dbOpenFor = "B", ownerKnown = false)))

    @Test fun decoySendGoesOnlyInItsOwnSession() {
        val d = SendOwner(accountId = "A", decoy = true, epoch = 7)
        val decoyScene = home.copy(inDecoy = true, dbOpenFor = "decoy", epoch = 7)
        assertEquals(SendGate.GO, sendGate(d, decoyScene))
        assertEquals(SendGate.DROP, sendGate(d, decoyScene.copy(locked = true, epoch = 8)))
        assertEquals(SendGate.DROP, sendGate(d, home.copy(epoch = 9)))
    }
}
