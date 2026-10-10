package app.rcq.android

import app.rcq.android.ui.ChatTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A share handed to a chat waits behind its lock gate in a global. Its `to`
 * is only a number or a room id, which the next account can hold for somebody
 * else entirely: a notification tap that switched accounts then sent the files
 * to that other person. The handoff names the owner that picked it.
 */
class ShareHandoffOwnerTest {

    private fun req(to: ChatTarget?, owner: String?) = ShareIntake.Req(null, emptyList(), to, owner)

    @Test fun the_picked_chat_under_the_owner_that_picked_it() {
        assertTrue(ShareIntake.deliverableTo(req(ChatTarget.Peer(500), "A/real"), ChatTarget.Peer(500), "A/real"))
    }

    @Test fun the_same_number_under_another_account_is_somebody_else() {
        assertFalse(ShareIntake.deliverableTo(req(ChatTarget.Peer(500), "A/real"), ChatTarget.Peer(500), "B/real"))
        assertFalse(ShareIntake.deliverableTo(req(ChatTarget.Group(5), "A/real"), ChatTarget.Group(5), "B/real"))
        // The duress view is an owner of its own.
        assertFalse(ShareIntake.deliverableTo(req(ChatTarget.Peer(500), "A/real"), ChatTarget.Peer(500), "A/decoy"))
    }

    @Test fun another_chat_is_not_the_picked_one() {
        assertFalse(ShareIntake.deliverableTo(req(ChatTarget.Peer(500), "A/real"), ChatTarget.Peer(501), "A/real"))
        assertFalse(ShareIntake.deliverableTo(req(ChatTarget.Peer(5), "A/real"), ChatTarget.Group(5), "A/real"))
    }

    @Test fun a_handoff_without_tags_passes() {
        assertTrue(ShareIntake.deliverableTo(req(null, null), ChatTarget.Peer(1), "A/real"))
    }
}
