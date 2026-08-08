package app.rcq.android.push

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rcq.android.crypto.Envelope
import app.rcq.android.crypto.GeneratedIdentity
import app.rcq.android.crypto.IdentityKeys
import app.rcq.android.crypto.SealedSender
import app.rcq.android.data.AccountManager
import app.rcq.android.data.LocalStores
import app.rcq.android.data.SecureStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End of the wake path: a {type:"msg"} payload goes into [Push.showMessage] and
 * a real system notification has to come out — named after the sender, previewing
 * the text, filed under a per-sender id, and carrying the extras that open that
 * exact thread. Also pins the four cases that must produce NO notification at
 * all: a muted peer, a mentions-only group without a mention, a control envelope,
 * and our own carbon.
 *
 * Uses the real NotificationManager on the device rather than a mock, because
 * the bug class this guards against (a wake that silently never posts, or one
 * that clobbers another thread's notification through a shared id) only shows up
 * against the real thing.
 */
@RunWith(AndroidJUnit4::class)
class PushMessageNotificationTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val myUin = 700300
    private val senderUin = 700400
    private val otherSenderUin = 700500
    private val myHost = "api.rcq.app"
    private val groupId = 4242

    private lateinit var me: GeneratedIdentity
    private lateinit var sender: GeneratedIdentity
    private lateinit var acctId: String

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            ctx.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        AccountManager.init(ctx)
        LocalStores.init(ctx)
        me = IdentityKeys.generate()
        sender = IdentityKeys.generate()
        acctId = requireNotNull(AccountManager.add(myHost, "push-test")).id
        SecureStore(ctx, acctId).saveIdentity(
            uin = myUin,
            token = "test-token",
            nickname = "Тестер",
            identityPrivate = me.identityPrivate,
            signingPrivate = me.signingPrivate,
            serverHost = myHost,
        )
        ctx.getSharedPreferences("rcq_local", Context.MODE_PRIVATE).edit()
            .putString(
                "$acctId.contacts_cache",
                """[{"uin":$senderUin,"nickname":"Вася","identityKey":"","signingKey":null}]""",
            ).apply()
        nm.cancelAll()
    }

    @After
    fun tearDown() {
        nm.cancelAll()
        SecureStore.wipeAccount(ctx, acctId)
        AccountManager.remove(acctId)
        ctx.getSharedPreferences("rcq_local", Context.MODE_PRIVATE).edit()
            .remove("$acctId.contacts_cache")
            .remove("$acctId.muted")
            .remove("$acctId.mentions_only")
            .apply()
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun sealed(env: Envelope, fromUin: Int = senderUin): String =
        SealedSender.encryptV1(
            envelope = env,
            recipientIdentityPub = me.identityPublic,
            ownUin = fromUin,
            signingPriv = sender.signingPrivate,
            signingPub = sender.signingPublic,
            ownHost = myHost,
        )

    /** The exact shape `unifiedpush.send_to_user` POSTs. */
    private fun wake(
        env: String?,
        envType: String = "message",
        group: Int? = null,
        groupName: String? = null,
    ): JsonObject = JsonParser.parseString("{}").asJsonObject.apply {
        addProperty("v", 1)
        addProperty("type", "msg")
        addProperty("to_uin", myUin)
        addProperty("title", "RCQ")
        addProperty("body", "New message")
        if (env != null) {
            addProperty("env", env)
            addProperty("envType", envType)
        }
        if (group != null) addProperty("group_id", group)
        if (groupName != null) addProperty("group_name", groupName)
    }

    private fun posted(id: Int) = nm.activeNotifications.firstOrNull { it.id == id }
    private fun peerNotif(uin: Int) = awaitPosted("peer:$uin".hashCode())
    private fun genericNotif() = posted("dm".hashCode())
    private fun title(id: Int) = posted(id)?.notification?.extras?.getString("android.title")
    private fun text(id: Int) = posted(id)?.notification?.extras?.getString("android.text")

    /** `notify()` hands the post to the notification service asynchronously, so
     *  reading activeNotifications straight after it is a race — it passed in
     *  isolation and failed in a full run. Wait for the post instead. */
    private fun awaitPosted(id: Int, timeoutMs: Long = 3_000) = run {
        val until = android.os.SystemClock.uptimeMillis() + timeoutMs
        var found = posted(id)
        while (found == null && android.os.SystemClock.uptimeMillis() < until) {
            Thread.sleep(25)
            found = posted(id)
        }
        found
    }

    /** For the silences: give a post the window it would have needed to show
     *  up, so "nothing there" is an answer and not just an early read.
     *
     *  ⚠ Counts the MESSAGE channel only. On a device where the app is actually
     *  running, the push socket's foreground service holds a permanent
     *  notification of its own that no `cancelAll` may remove, and asserting on
     *  the whole app made these four tests fail for a reason that has nothing to
     *  do with wakes. */
    private fun assertNothingPosted() {
        Thread.sleep(750)
        val onMessageChannel = nm.activeNotifications
            .filter { it.notification.channelId == Push.CHANNEL_MESSAGES }
        assertEquals(
            "no message notification should be posted, got " +
                onMessageChannel.map { it.notification.extras.getString("android.title") },
            0,
            onMessageChannel.size,
        )
    }

    private fun mute(thread: String) {
        ctx.getSharedPreferences("rcq_local", Context.MODE_PRIVATE).edit()
            .putStringSet("$acctId.muted", setOf(thread)).apply()
    }

    private fun mentionsOnly(thread: String) {
        ctx.getSharedPreferences("rcq_local", Context.MODE_PRIVATE).edit()
            .putStringSet("$acctId.mentions_only", setOf(thread)).apply()
    }

    // ── what the user asked for ──────────────────────────────────────

    @Test
    fun directMessageIsNamedAndPreviewed() {
        Push.showMessage(ctx, wake(sealed(Envelope.Text("n1", "как дела"))))
        val id = "peer:$senderUin".hashCode()
        assertNotNull("a v=1 wake must post a notification", awaitPosted(id))
        assertEquals("Вася", title(id))
        assertEquals("как дела", text(id))
        assertNull("it must not also post the anonymous one", genericNotif())
    }

    @Test
    fun twoSendersGetTwoNotifications() {
        Push.showMessage(ctx, wake(sealed(Envelope.Text("n2", "первый"))))
        Push.showMessage(ctx, wake(sealed(Envelope.Text("n3", "второй"), fromUin = otherSenderUin)))
        assertNotNull(peerNotif(senderUin))
        assertNotNull("a second sender must not clobber the first", peerNotif(otherSenderUin))
        assertEquals("как раньше — второй отправитель без имени", "#$otherSenderUin", title("peer:$otherSenderUin".hashCode()))
    }

    @Test
    fun groupMessagePrefixesTheSender() {
        Push.showMessage(
            ctx,
            wake(sealed(Envelope.Text("n4", "всем привет")), group = groupId, groupName = "Команда"),
        )
        val id = groupId.toString().hashCode()
        assertNotNull(awaitPosted(id))
        assertEquals("Команда", title(id))
        assertEquals("Вася: всем привет", text(id))
    }

    @Test
    fun sealedV2WakeStaysGeneric() {
        val v2 = SealedSender.wrapV2(byteArrayOf(9, 9, 9), "signal", me.identityPublic, senderUin)
        Push.showMessage(ctx, wake(v2))
        assertNotNull("a v=2 wake still notifies, just anonymously", awaitPosted("dm".hashCode()))
        assertEquals("RCQ", title("dm".hashCode()))
        assertEquals("New message", text("dm".hashCode()))
    }

    // ── and the silences ─────────────────────────────────────────────

    @Test
    fun mutedPeerDoesNotWake() {
        mute("peer:$senderUin")
        Push.showMessage(ctx, wake(sealed(Envelope.Text("n5", "тихо"))))
        assertNothingPosted()
    }

    @Test
    fun mentionsOnlyGroupStaysQuietWithoutAMention() {
        mentionsOnly("group:$groupId")
        Push.showMessage(
            ctx,
            wake(sealed(Envelope.Text("n6", "обычное сообщение")), group = groupId, groupName = "Команда"),
        )
        assertNothingPosted()
    }

    /** The regression the founder's report is about: "Mentions" used to behave
     *  exactly like "None" in the background, swallowing real mentions too. */
    @Test
    fun mentionsOnlyGroupWakesOnARealMention() {
        mentionsOnly("group:$groupId")
        Push.showMessage(
            ctx,
            wake(sealed(Envelope.Text("n7", "глянь #$myUin")), group = groupId, groupName = "Команда"),
        )
        val id = groupId.toString().hashCode()
        assertNotNull("a real mention must get through", awaitPosted(id))
        assertTrue(text(id)!!.contains("#$myUin"))
    }

    @Test
    fun controlEnvelopeDoesNotWake() {
        Push.showMessage(ctx, wake(sealed(Envelope.ReadReceipt(listOf("n1")))))
        assertNothingPosted()
    }

    @Test
    fun ownCarbonDoesNotWake() {
        Push.showMessage(ctx, wake(sealed(Envelope.Text("n8", "себе"), fromUin = myUin)))
        assertNothingPosted()
    }

    // ── one message, one alert ───────────────────────────────────────

    /** Waits for [id] to be posted with the marks androidx puts on a silenced
     *  notification: group "silent" + GROUP_ALERT_SUMMARY, which is the only
     *  way to keep a channel with a sound from playing it on API 26+. */
    private fun awaitSilent(id: Int, timeoutMs: Long = 3_000): Boolean {
        val until = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < until) {
            val n = posted(id)?.notification
            if (n != null && n.group == "silent" &&
                n.groupAlertBehavior == android.app.Notification.GROUP_ALERT_SUMMARY
            ) {
                return true
            }
            Thread.sleep(25)
        }
        return false
    }

    private fun alerting(id: Int) = posted(id)?.notification?.group == null

    /**
     * The tester's report of 2026-08-08: one message, several "о-оу" — some
     * quiet (the app's own tone, on the media stream) and some loud (the
     * notification channel, which carries the same tone at the system's
     * notification volume).
     *
     * A message that the running app receives over its socket now raises the
     * SAME notification a wake would, so when both paths fire for one message
     * only the first one alerts.
     */
    @Test
    fun socketMessageAndItsWakeAlertOnlyOnce() {
        val peer = 700601
        val id = "peer:$peer".hashCode()
        assertTrue(
            "a socket-delivered message must reach the shade",
            Push.showLocalMessage(ctx, "Вася", "как дела", null, peer, myUin),
        )
        assertNotNull(awaitPosted(id))
        assertTrue("the first announcement is the one that alerts", alerting(id))

        Push.showLocalMessage(ctx, "Вася", "и ещё раз", null, peer, myUin)
        assertTrue("the second one inside the burst window must be silent", awaitSilent(id))
    }

    /** The server retries a publish whose response it lost, and ntfy replays its
     *  cache to a distributor that reconnects with `since=`. Both hand the app a
     *  byte-identical envelope, and the second copy used to ring again. */
    @Test
    fun theSameWakeTwiceRingsOnce() {
        val env = sealed(Envelope.Text("dup1", "дубль"), fromUin = 700602)
        val id = "peer:700602".hashCode()
        Push.showMessage(ctx, wake(env))
        assertNotNull(awaitPosted(id))
        assertTrue(alerting(id))

        Push.showMessage(ctx, wake(env))
        assertTrue("the same ciphertext must never sound twice", awaitSilent(id))
    }

    /** "Message sounds: off" used to silence only the app's own tone while the
     *  channel kept chiming, because the wake path never read the setting. */
    @Test
    fun appSoundSwitchSilencesTheNotificationToo() {
        LocalStores.setSoundMessages(false)
        try {
            val id = "peer:700603".hashCode()
            Push.showMessage(ctx, wake(sealed(Envelope.Text("q1", "тихо"), fromUin = 700603)))
            assertNotNull(awaitPosted(id))
            assertTrue("sounds are off, so the notification must be silent", awaitSilent(id))
        } finally {
            LocalStores.setSoundMessages(true)
        }
    }

    // ── the tap ──────────────────────────────────────────────────────

    @Test
    fun tapExtrasCarryTheSender() {
        val intent = android.content.Intent().apply {
            putExtra(Push.EXTRA_OPEN_TO_UIN, myUin)
            putExtra(Push.EXTRA_OPEN_PEER_UIN, senderUin)
        }
        val req = app.rcq.android.NotificationOpen.fromIntent(intent)
        assertNotNull(req)
        assertEquals(senderUin, req!!.peerUin)
        assertEquals(myUin, req.toUin)
        assertNull("no group on a 1:1 wake", req.groupId)
        assertNull(
            "extras are consumed so a recreate() cannot re-fire the navigation",
            app.rcq.android.NotificationOpen.fromIntent(intent),
        )
    }
}
