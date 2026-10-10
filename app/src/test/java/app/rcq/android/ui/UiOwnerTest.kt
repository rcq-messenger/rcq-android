package app.rcq.android.ui

import app.rcq.android.data.AccountManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report 1068: UI state kept outside a screen (the Add sheet, composer drafts)
 * belongs to the owner it was made under, and the duress view is an owner of
 * its own even though it keeps the real account's id. Host JVM, no emulator.
 */
class UiOwnerTest {

    @After
    fun tearDown() {
        AccountManager.exitDecoySession()
        AccountManager.exitDecoyMode()
        AddSheet.close()
    }

    @Test
    fun ownerIsTheAccountIdAndTheView() {
        assertNotEquals(UiOwner.key("a", decoy = false), UiOwner.key("b", decoy = false))
        assertNotEquals(UiOwner.key("a", decoy = false), UiOwner.key("a", decoy = true))
        assertEquals(UiOwner.key("a", decoy = true), UiOwner.key("a", decoy = true))
    }

    @Test
    fun duressViewIsAnotherOwnerUnderTheSameAccount() {
        val real = UiOwner.now()
        AccountManager.enterDecoySession()
        val duress = UiOwner.now()
        assertNotEquals(real, duress)
        AccountManager.exitDecoySession()
        assertEquals(real, UiOwner.now())
        // The legacy decoy is shown under the same filter.
        AccountManager.enterDecoyMode("decoy-id")
        assertNotEquals(real, UiOwner.now())
    }

    @Test
    fun draftsAreNotSeenByAnotherOwner() {
        val drafts = PerOwnerMap()
        drafts["p:500"] = "meet at six"
        assertEquals("meet at six", drafts["p:500"])
        AccountManager.enterDecoySession()
        assertNull(drafts["p:500"])
        drafts["p:500"] = "decoy text"
        AccountManager.exitDecoySession()
        assertEquals("meet at six", drafts["p:500"])
        drafts.remove("p:500")
        assertNull(drafts["p:500"])
        AccountManager.enterDecoySession()
        assertEquals("decoy text", drafts["p:500"])
        drafts.clear()
        assertNull(drafts["p:500"])
    }

    @Test
    fun addSheetIsDrawnOnlyForItsOwnerAndClosedForAnother() {
        AddSheet.openFor("a/real")
        AddSheet.query.value = "vasya"
        assertTrue(AddSheet.isOpenFor("a/real"))
        assertFalse(AddSheet.isOpenFor("b/real"))
        assertFalse(AddSheet.isOpenFor("a/decoy"))

        // Its own owner coming back (a profile and Back) keeps it.
        AddSheet.closeUnlessFor("a/real")
        assertTrue(AddSheet.isOpenFor("a/real"))
        assertEquals("vasya", AddSheet.query.value)

        // Another owner closes it, query and all.
        AddSheet.keepResults("a/real", "vasya", emptyList(), emptyList())
        AddSheet.closeUnlessFor("b/real")
        assertFalse(AddSheet.open.value)
        assertNull(AddSheet.owner)
        assertEquals("", AddSheet.query.value)
        assertNull(AddSheet.resultsFor)
    }

    @Test
    fun aSearchFinishingAfterTheOwnerChangedKeepsNothing() {
        AddSheet.openFor("a/real")
        AddSheet.close()
        AddSheet.openFor("b/real")
        AddSheet.keepResults("a/real", "vasya", emptyList(), emptyList())
        assertNull(AddSheet.resultsFor)
        AddSheet.keepResults("b/real", "petya", emptyList(), emptyList())
        assertEquals("petya", AddSheet.resultsFor)
    }
}
