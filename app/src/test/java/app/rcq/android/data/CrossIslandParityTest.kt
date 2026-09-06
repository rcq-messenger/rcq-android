package app.rcq.android.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cross-island slot, byte for byte against the web.
 *
 * The expected strings are not hand-written: they are the output of
 * web-chat's `canonState(mergeCrossIsland(...))` for the same inputs, taken
 * from the built bundle (`cli/test/crossisland.mjs` runs the same vectors
 * there). Three clients write this one slot and it is the only copy of these
 * contacts in existence, so "close enough" is a rewrite war at best and a lost
 * contact at worst.
 *
 * What each case is really pinning:
 *   union   — a row only one device has survives, from either side.
 *   tofu    — the pinned keys come from the EARLIER row. A second device must
 *             not be able to re-pin a peer to a swapped key card by adding
 *             them again; that is the whole anti-impersonation anchor.
 *   profile — display fields follow the newest profileTs and carry the avatar
 *             pair, without dragging the keys along with them.
 *   tomb    — a removal on one device removes on the other.
 *   readd   — added again after the removal wins, and the tombstone goes, or
 *             re-adding somebody you once removed would be impossible.
 */
class CrossIslandParityTest {
    private val now = 1_757_000_000_000L
    private val day = 86_400_000L

    private fun row(uin: Int, host: String, edit: JsonObject.() -> Unit = {}): JsonObject {
        val o = JsonObject()
        o.addProperty("uin", uin)
        o.addProperty("host", host)
        o.addProperty("nickname", "peer$uin")
        o.addProperty("identityKey", "ident-$uin")
        o.addProperty("signingKey", "sign-$uin")
        o.addProperty("addedAt", now - day)
        o.addProperty("profileTs", 0L)
        o.edit()
        return o
    }

    private fun state(rows: List<JsonObject> = emptyList(), graves: Map<String, Long> = emptyMap()): JsonObject {
        val o = JsonObject()
        o.addProperty("v", 1)
        val c = JsonObject()
        for (r in rows) c.add("${r.get("uin").asLong}@${r.get("host").asString}", r)
        o.add("c", c)
        val g = JsonObject()
        for ((k, v) in graves) g.addProperty(k, v)
        o.add("g", g)
        return o
    }

    private fun same(expected: String, a: JsonObject, b: JsonObject) {
        // Both directions: the merge has to be commutative, or two devices
        // converge on different lists depending on which one synced first.
        assertEquals(expected, CrossIslandVault.merge(a, b, now).toString())
        assertEquals(expected, CrossIslandVault.merge(b, a, now).toString())
        // ...and idempotent, or every sync is a write.
        val once = CrossIslandVault.merge(a, b, now)
        assertEquals(expected, CrossIslandVault.merge(once, once, now).toString())
    }

    @Test fun union_of_two_devices() = same(
        """{"v":1,"c":{"101@is2.rcq.app":{"addedAt":1756913600000,"host":"is2.rcq.app","identityKey":"ident-101","nickname":"peer101","profileTs":0,"signingKey":"sign-101","uin":101},"202@rcqam.mooo.com":{"addedAt":1756913600000,"host":"rcqam.mooo.com","identityKey":"ident-202","nickname":"peer202","profileTs":0,"signingKey":"sign-202","uin":202}},"g":{}}""",
        state(listOf(row(101, "is2.rcq.app"))),
        state(listOf(row(202, "rcqam.mooo.com"))),
    )

    @Test fun keys_come_from_the_earlier_row() = same(
        """{"v":1,"c":{"7@x.example":{"addedAt":1754408000000,"host":"x.example","identityKey":"REAL","nickname":"peer7","profileTs":0,"signingKey":"REAL-S","uin":7}},"g":{}}""",
        state(listOf(row(7, "x.example") {
            addProperty("addedAt", now - 30 * day); addProperty("identityKey", "REAL"); addProperty("signingKey", "REAL-S")
        })),
        state(listOf(row(7, "x.example") {
            addProperty("identityKey", "SWAPPED"); addProperty("signingKey", "SWAPPED-S")
        })),
    )

    @Test fun newest_profile_wins_the_display_only() = same(
        """{"v":1,"c":{"9@x.example":{"addedAt":1754408000000,"avatarMediaId":"blob-9","avatarMediaKey":"k9","gender":"f","host":"x.example","identityKey":"ident-9","nickname":"new name","profileTs":2000,"signingKey":"sign-9","uin":9}},"g":{}}""",
        state(listOf(row(9, "x.example") {
            addProperty("addedAt", now - 30 * day); addProperty("nickname", "old name"); addProperty("profileTs", 1000L)
        })),
        state(listOf(row(9, "x.example") {
            addProperty("nickname", "new name"); addProperty("profileTs", 2000L)
            addProperty("gender", "f"); addProperty("avatarMediaId", "blob-9"); addProperty("avatarMediaKey", "k9")
        })),
    )

    @Test fun a_removal_travels() = same(
        """{"v":1,"c":{},"g":{"21@x.example":1756913600000}}""",
        state(listOf(row(21, "x.example") { addProperty("addedAt", now - 10 * day) })),
        state(graves = mapOf("21@x.example" to now - day)),
    )

    @Test fun re_adding_beats_the_tombstone() = same(
        """{"v":1,"c":{"22@x.example":{"addedAt":1756996400000,"host":"x.example","identityKey":"ident-22","nickname":"peer22","profileTs":0,"signingKey":"sign-22","uin":22}},"g":{}}""",
        state(listOf(row(22, "x.example") { addProperty("addedAt", now - 3_600_000L) })),
        state(graves = mapOf("22@x.example" to now - day)),
    )

    @Test fun an_empty_slot_never_erases_the_device() {
        val mine = state(listOf(row(41, "x.example"), row(42, "y.example")))
        val out = CrossIslandVault.merge(mine, CrossIslandVault.empty(), now)
        assertEquals(2, out.getAsJsonObject("c").size())
    }

    @Test fun a_row_without_keys_is_dropped_not_half_carried() {
        val broken = JsonObject().apply {
            addProperty("uin", 31); addProperty("host", "x.example"); addProperty("nickname", "x"); addProperty("addedAt", now)
        }
        val out = CrossIslandVault.merge(state(listOf(broken)), CrossIslandVault.empty(), now)
        assertEquals(0, out.getAsJsonObject("c").size())
    }

    @Test fun half_an_avatar_pair_is_no_avatar() {
        val r = row(11, "x.example") { addProperty("avatarMediaId", "blob") }
        val out = CrossIslandVault.merge(state(listOf(r)), CrossIslandVault.empty(), now)
        assertEquals(false, out.getAsJsonObject("c").getAsJsonObject("11@x.example").has("avatarMediaId"))
    }

    @Test fun tombstones_expire_after_ninety_days() {
        val old = state(graves = mapOf("23@x.example" to now - 100 * day, "24@x.example" to now - 10 * day))
        val out = CrossIslandVault.merge(old, CrossIslandVault.empty(), now)
        val g = out.getAsJsonObject("g")
        assertEquals(false, g.has("23@x.example"))
        assertEquals(true, g.has("24@x.example"))
    }

    @Test fun an_android_shaped_row_and_a_web_shaped_row_are_not_a_difference() {
        // What a web write looks like coming back, and what this client would
        // have written for the same contact. Equal, or the two take turns
        // rewriting the slot and burn the account's 240-puts-an-hour budget.
        val fromWeb = JsonParser.parseString(
            """{"v":1,"g":{},"c":{"52@x.example":{"signingKey":"sign-52","identityKey":"ident-52","host":"x.example","uin":52,"addedAt":1756913600000,"nickname":"peer52","profileTs":0,"gender":null}}}""",
        ).asJsonObject
        val mine = state(listOf(row(52, "x.example")))
        assertEquals(true, CrossIslandVault.sameContent(mine, fromWeb))
    }
}
