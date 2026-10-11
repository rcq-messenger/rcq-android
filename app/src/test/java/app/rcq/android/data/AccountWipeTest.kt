package app.rcq.android.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a burn, a wipe PIN, a removal from Manage accounts and the end of a
 * duress session leave of an account in rcq_local.xml: nothing.
 *
 * [LocalStores.clearAccount] named its slots one by one and fell behind: the
 * names the person gave people (contact_aliases), the numbers whose random chat
 * had just ended, the read watermarks from their other devices, both
 * screen-secure sets and the presence leftovers all outlived every wipe. It is
 * a sweep of the account's prefix now; these tests pin that every key the
 * store can name goes with it, and nothing of anybody else's.
 */
class AccountWipeTest {

    private val a = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val b = "9a8b7c6d-5e4f-3a2b-1c0d-ffeeddccbbaa"
    private lateinit var prefs: FakePrefs

    private fun field(name: String) = LocalStores::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before fun setUp() {
        prefs = FakePrefs()
        field("prefs").set(null, prefs)
    }

    @After fun tearDown() {
        LocalStores.bindAccount(null)
        field("prefs").set(null, null)
    }

    /** Every `K_` constant in [LocalStores], read off the class so a slot added
     *  later is in this list without anybody editing it. */
    private fun allSlotNames(): List<String> =
        LocalStores::class.java.declaredFields
            .filter { it.name.startsWith("K_") && it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.isAccessible = true; it.get(null) as String }

    @Test fun everySlotTheStoreCanNameGoesAndNothingElse() {
        val names = allSlotNames()
        assertTrue("the store names its slots in K_ constants", names.size > 40)
        for (n in names) {
            prefs.map["$a.$n"] = "x"
            prefs.map["$b.$n"] = "y"
            prefs.map[n] = "global"
        }
        // The vault floors are keyed by slot name under one of those names.
        prefs.map["$a.vault_slot_version.contacts"] = 3L
        prefs.map["island_info.is2.example"] = "{}"

        LocalStores.clearAccount(a)

        assertEquals(emptyList<String>(), prefs.map.keys.filter { it.startsWith("$a.") })
        for (n in names) {
            assertEquals("another account keeps $n", "y", prefs.map["$b.$n"])
            assertEquals("the global $n stays", "global", prefs.map[n])
        }
        assertEquals("{}", prefs.map["island_info.is2.example"])
    }

    @Test fun theSlotsTheListForgotAreWrittenUnderThePrefixAndGo() {
        LocalStores.bindAccount(a)
        LocalStores.setAlias(7, "Mum")
        LocalStores.setAlias(7, "Work", host = "is2.example")
        LocalStores.setFinishedStrangers(mapOf(42 to 1_000L))
        LocalStores.noteRemoteRead("peer:7", 2_000L)
        LocalStores.setThreadSecure("peer:7", true)
        LocalStores.setThreadSecureByPeer("peer:8", true)
        LocalStores.markPresenceRetired()
        LocalStores.toggleMute("peer:7")
        LocalStores.setThreadTtl("peer:7", 60)
        LocalStores.setMyProfileKey("bXkta2V5")
        LocalStores.bindAccount(b)
        LocalStores.setAlias(7, "Someone else")
        val keptForB = prefs.map.keys.filter { it.startsWith("$b.") }.toSet()
        assertTrue(prefs.map.keys.any { it.startsWith("$a.") })

        LocalStores.clearAccount(a)

        assertEquals(emptyList<String>(), prefs.map.keys.filter { it.startsWith("$a.") })
        assertEquals(keptForB, prefs.map.keys.filter { it.startsWith("$b.") }.toSet())
        // And bound again, the account reads as never having been here.
        LocalStores.bindAccount(a)
        assertEquals(null, LocalStores.aliasFor(7))
        assertEquals(emptyMap<Int, Long>(), LocalStores.finishedStrangers())
        assertEquals(0L, LocalStores.readUpTo("peer:7"))
        assertEquals(false, LocalStores.isThreadSecure("peer:7"))
        assertEquals(false, LocalStores.presenceRetired())
    }

    @Test fun aBlankIdNamesNoKeys() {
        assertEquals(emptyList<String>(), LocalStores.accountKeys(listOf(".x", "x", "a.x"), ""))
    }

    @Test fun anIdIsMatchedWholeNotAsTheStartOfAnother() {
        val keys = listOf("$a.muted", "${a}0.muted", "$a", "muted")
        assertEquals(listOf("$a.muted"), LocalStores.accountKeys(keys, a))
    }
}
