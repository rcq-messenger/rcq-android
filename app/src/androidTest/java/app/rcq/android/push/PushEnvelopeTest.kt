package app.rcq.android.push

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rcq.android.crypto.Envelope
import app.rcq.android.crypto.GeneratedIdentity
import app.rcq.android.crypto.IdentityKeys
import app.rcq.android.crypto.SealedSender
import app.rcq.android.data.LocalStores
import app.rcq.android.data.SecureStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The push receiver's envelope reader ([PushEnvelope]): it must name the sender
 * and preview the text of a v=1 wake, stay silent for control envelopes, keep a
 * v=2 envelope SEALED (opening one would advance the ratchet and lose the
 * message for the app), and refuse to name a cross-island sender whose request
 * has not been accepted.
 *
 * Runs headless on purpose — no Session, nothing bound — because that is the
 * state a background wake actually arrives in.
 */
@RunWith(AndroidJUnit4::class)
class PushEnvelopeTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val acct = "test-push-envelope"
    private val myUin = 700100
    private val senderUin = 700200
    private val myHost = "api.rcq.app"

    private lateinit var me: GeneratedIdentity
    private lateinit var sender: GeneratedIdentity

    @Before
    fun setUp() {
        LocalStores.init(ctx)
        me = IdentityKeys.generate()
        sender = IdentityKeys.generate()
        SecureStore(ctx, acct).saveIdentity(
            uin = myUin,
            token = "test-token",
            nickname = "Тестер",
            identityPrivate = me.identityPrivate,
            signingPrivate = me.signingPrivate,
            serverHost = myHost,
        )
    }

    @After
    fun tearDown() {
        SecureStore.wipeAccount(ctx, acct)
        ctx.getSharedPreferences("rcq_local", Context.MODE_PRIVATE).edit()
            .remove("$acct.contacts_cache").apply()
        ctx.getSharedPreferences("rcq_crossisland", Context.MODE_PRIVATE).edit()
            .remove("$acct.contacts.v1").apply()
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** Seal [env] to this account the way a real sender does. */
    private fun sealed(
        env: Envelope,
        fromUin: Int = senderUin,
        fromHost: String = myHost,
        signer: GeneratedIdentity = sender,
    ): String = SealedSender.encryptV1(
        envelope = env,
        recipientIdentityPub = me.identityPublic,
        ownUin = fromUin,
        signingPriv = signer.signingPrivate,
        signingPub = signer.signingPublic,
        ownHost = fromHost,
    )

    private fun cacheContact(uin: Int, nickname: String) {
        val json = """[{"uin":$uin,"nickname":"$nickname","identityKey":"","signingKey":null}]"""
        ctx.getSharedPreferences("rcq_local", Context.MODE_PRIVATE).edit()
            .putString("$acct.contacts_cache", json).apply()
    }

    private fun addCrossIslandContact(uin: Int, host: String, nickname: String) {
        val json = """{"$uin@$host":{"uin":$uin,"host":"$host","nickname":"$nickname",""" +
            """"identityKey":"","signingKey":"","signalIdentityKey":null,"addedAt":1}}"""
        ctx.getSharedPreferences("rcq_crossisland", Context.MODE_PRIVATE).edit()
            .putString("$acct.contacts.v1", json).apply()
    }

    // ── the point of the feature ─────────────────────────────────────

    @Test
    fun namesTheSenderAndPreviewsTheText() {
        cacheContact(senderUin, "Вася")
        val opened = PushEnvelope.open(ctx, acct, sealed(Envelope.Text("m1", "как дела")))
        assertNotNull("a v=1 wake must open", opened)
        assertEquals(senderUin, opened!!.senderUin)
        assertEquals("Вася", opened.senderName)
        assertEquals("как дела", opened.preview)
        assertFalse(opened.quarantined)
    }

    @Test
    fun unknownSenderFallsBackToTheHandle() {
        val opened = PushEnvelope.open(ctx, acct, sealed(Envelope.Text("m2", "привет")))
        assertEquals("#$senderUin", opened?.senderName)
    }

    @Test
    fun mediaGetsAKindPreviewOrItsCaption() {
        val photo = PushEnvelope.open(
            ctx, acct,
            sealed(Envelope.Photo("m3", "mid", "mkey", caption = null)),
        )
        assertTrue("a captionless photo previews as its kind", photo?.preview?.startsWith("📷") == true)
        val captioned = PushEnvelope.open(
            ctx, acct,
            sealed(Envelope.Photo("m4", "mid", "mkey", caption = "на море")),
        )
        assertEquals("на море", captioned?.preview)
    }

    // ── mentions-only, the gate that used to behave as full mute ──────

    @Test
    fun mentionByHandleIsSeen() {
        val opened = PushEnvelope.open(ctx, acct, sealed(Envelope.Text("m5", "глянь #$myUin пожалуйста")))
        assertTrue(opened!!.mentionsMe)
    }

    @Test
    fun mentionByNicknameIsSeen() {
        val opened = PushEnvelope.open(ctx, acct, sealed(Envelope.Text("m6", "@тестер глянь")))
        assertTrue("@nick is matched case-insensitively, like the in-app gate", opened!!.mentionsMe)
    }

    @Test
    fun ordinaryMessageIsNotAMention() {
        val opened = PushEnvelope.open(ctx, acct, sealed(Envelope.Text("m7", "всем привет")))
        assertFalse(opened!!.mentionsMe)
    }

    // ── things that must NOT raise a banner ───────────────────────────

    @Test
    fun controlEnvelopeHasNoPreview() {
        val receipt = PushEnvelope.open(ctx, acct, sealed(Envelope.ReadReceipt(listOf("m1"))))
        assertNotNull(receipt)
        assertNull("a read receipt is not a new message", receipt!!.preview)
        val reaction = PushEnvelope.open(ctx, acct, sealed(Envelope.Reaction("m1", "👍")))
        assertNull("a reaction is not a new message", reaction?.preview)
    }

    /** Opened, but with nothing to show — NOT a failure to open. Reporting it
     *  as a failure would send the caller down the generic-wake fallback and
     *  buzz the phone about the message the user just sent themselves. */
    @Test
    fun ourOwnCarbonStaysSilent() {
        val mine = PushEnvelope.open(ctx, acct, sealed(Envelope.Text("m8", "с другого устройства"), fromUin = myUin))
        assertNotNull(mine)
        assertNull("our own message must not wake this device", mine!!.preview)
    }

    @Test
    fun anEnvelopeSealedToSomeoneElseIsNotOpened() {
        val other = IdentityKeys.generate()
        val notForUs = SealedSender.encryptV1(
            envelope = Envelope.Text("m9", "не нам"),
            recipientIdentityPub = other.identityPublic,
            ownUin = senderUin,
            signingPriv = sender.signingPrivate,
            signingPub = sender.signingPublic,
        )
        assertNull(PushEnvelope.open(ctx, acct, notForUs))
    }

    /** The guard the whole design rests on: a v=2 decrypt would advance the
     *  Double Ratchet out of band, and the copy arriving over the socket or the
     *  offline queue would then be undecryptable — the message would be lost. */
    @Test
    fun v2EnvelopeIsLeftSealed() {
        val v2 = SealedSender.wrapV2(
            libsignalBytes = byteArrayOf(1, 2, 3, 4),
            kind = "signal",
            recipientIdentityPub = me.identityPublic,
            ownUin = senderUin,
        )
        assertEquals("fixture must really be v=2", 2, SealedSender.wireVersion(v2))
        assertNull("v=2 must stay sealed in the receiver", PushEnvelope.open(ctx, acct, v2))
    }

    @Test
    fun garbageIsNotOpened() {
        assertNull(PushEnvelope.open(ctx, acct, "not-base64-at-all"))
    }

    // ── cross-island consent ─────────────────────────────────────────

    @Test
    fun unacceptedCrossIslandSenderIsQuarantined() {
        val opened = PushEnvelope.open(
            ctx, acct,
            sealed(Envelope.Text("m10", "с другого острова"), fromHost = "is2.example"),
        )
        assertNotNull(opened)
        assertTrue("their message goes to the request quarantine, not a thread", opened!!.quarantined)
    }

    @Test
    fun acceptedCrossIslandSenderOpensNormally() {
        addCrossIslandContact(senderUin, "is2.example", "Петя с is2")
        val opened = PushEnvelope.open(
            ctx, acct,
            sealed(Envelope.Text("m11", "уже принят"), fromHost = "is2.example"),
        )
        assertNotNull(opened)
        assertFalse(opened!!.quarantined)
        assertEquals("Петя с is2", opened.senderName)
        assertEquals("уже принят", opened.preview)
    }
}
