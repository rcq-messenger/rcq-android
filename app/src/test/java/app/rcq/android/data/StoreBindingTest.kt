package app.rcq.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Report 1072, side finding: a language change in a duress session recreated
 * MainActivity, and its onCreate bound the per-account stores back to the REAL
 * account under the duress view. The Session owns the binding once it exists.
 */
class StoreBindingTest {

    private val real = "acct-real"

    @Test fun aRecreateInTheDuressViewLeavesTheDecoyNamespaceAlone() {
        assertEquals(
            StoreBinding.OnCreate.Keep,
            StoreBinding.onActivityCreate(sessionBuilt = true, duressViewUp = true, activeId = real),
        )
    }

    @Test fun aRecreateInAnOrdinarySessionDoesNotRebindEither() {
        // The Session re-points every store on each switch, lock and unlock;
        // a second binder can only disagree with it.
        assertEquals(
            StoreBinding.OnCreate.Keep,
            StoreBinding.onActivityCreate(sessionBuilt = true, duressViewUp = false, activeId = real),
        )
    }

    @Test fun theFirstCreateOfAProcessBindsTheActiveAccount() {
        assertEquals(
            StoreBinding.OnCreate.Bind(real),
            StoreBinding.onActivityCreate(sessionBuilt = false, duressViewUp = false, activeId = real),
        )
    }

    @Test fun aFreshInstallBindsNothing() {
        assertEquals(
            StoreBinding.OnCreate.Bind(null),
            StoreBinding.onActivityCreate(sessionBuilt = false, duressViewUp = false, activeId = null),
        )
    }

    @Test fun aDuressFlagWithoutASessionStillNeverNamesTheRealAccount() {
        assertEquals(
            StoreBinding.OnCreate.Bind(DecoyStore.STORE_ID),
            StoreBinding.onActivityCreate(sessionBuilt = false, duressViewUp = true, activeId = real),
        )
    }
}
