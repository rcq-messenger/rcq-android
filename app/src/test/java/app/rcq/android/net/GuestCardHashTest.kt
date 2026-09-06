package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The guest-card digest, against the island's own Python.
 *
 * A card is 32 bytes a client generates; the island is told only sha256(card)
 * and compares digests. If any client computes that differently by so much as
 * a trimmed byte, every card it registers opens nothing — and the symptom is
 * not an error anywhere. It is a stranger being told "no such number" by a
 * closed island, which is precisely what the refusal is designed to look like
 * when it is working correctly. A silent, undebuggable failure across three
 * languages is worth pinned vectors.
 *
 * The expected values are not hand-written: they are the output of
 * `hashlib.sha256(s.strip().encode("utf-8")).hexdigest()`, the exact
 * expression in `backend/app/models/guest_card.py`. The web pins the same
 * contract in cli/test/guest-card.mjs by shelling out to python3.
 *
 * ⚠ `newCard()` is deliberately NOT tested here: it calls android.util.Base64,
 * which is a stub on a plain JVM. Its shape is covered by the web's test,
 * which uses the same alphabet and length.
 */
class GuestCardHashTest {

    @Test fun matches_the_island_on_ascii() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GuestCardStore.hashCard("abc"),
        )
    }

    @Test fun matches_the_island_on_a_real_card_shape() {
        assertEquals(
            "30f4b6368375ba344c2f252d7678d35a4be82aaa35cc3fa66b04e07437561c63",
            GuestCardStore.hashCard("MEIfP9Zs4nB2xQ7kL0vRtYwUeIoPaSdFgHjKlZxCvBn"),
        )
    }

    @Test fun matches_the_island_on_non_ascii() {
        // Not a card we would ever mint, but it proves both sides agree on
        // UTF-8 rather than on some platform default.
        assertEquals(
            "0fbca81cf75143f47f14db58c3547d991838f92b55411431bff7e7dd1b8fbaf8",
            GuestCardStore.hashCard("кириллица-и-emoji-🙂"),
        )
    }

    @Test fun trims_the_same_way_the_island_does() {
        // A card pasted out of a message or a link often arrives with spaces.
        assertEquals(
            "c7f9b538b93ce513f654b8d199e50252ae037c5bde542c132b04a42cd8b92ea0",
            GuestCardStore.hashCard("  padded  "),
        )
        assertEquals(GuestCardStore.hashCard("abc"), GuestCardStore.hashCard(" abc\n"))
    }

    @Test fun is_lowercase_hex_of_the_right_length() {
        val h = GuestCardStore.hashCard("anything")
        assertEquals(64, h.length)
        assertEquals(h, h.lowercase())
    }
}
