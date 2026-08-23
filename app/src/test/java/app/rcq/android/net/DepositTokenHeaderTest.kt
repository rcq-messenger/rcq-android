package app.rcq.android.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Stage 3 pieces of [DepositAuthStore], checked on the JVM.
 *
 * The header a bundle fetch carries: the island base64url-decodes the value
 * (padding tolerated) and reads the same `{epoch_id, prepared, sig}` JSON a
 * sealed deposit puts in its body; a padded or non-url-safe encoding would
 * pass locally and be refused by the verifier as a malformed token (403),
 * which the client reads as a rotated epoch and spends a mint on.
 *
 * The reserve, against a fake island that lives in an OkHttp interceptor
 * (a real RSA key, blind-signing with d, a few bits of PoW): the first token
 * is handed over before the batch is done, two callers racing an empty
 * reserve do not mint two batches, a refusal drops only the tokens of its
 * own epoch, a return is taken only for the current epoch, and a 404 on
 * /params rests the host until [DepositAuthStore.forget].
 */
class DepositTokenHeaderTest {

    private fun token(): JsonObject = JsonObject().apply {
        addProperty("epoch_id", "2026-08-23T00:00:00Z")
        // Standard base64 with the characters that differ from the url-safe
        // alphabet, and padding: the header must wrap them, not alter them.
        addProperty("prepared", "+/+/AAECAwQFBgcICQoLDA0ODw==")
        addProperty("sig", "//8AAQ==")
    }

    @Test fun headerRoundTripsTheToken() {
        val t = token()
        val value = DepositAuthStore.headerValue(t)
        val decoded = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        assertEquals(t, JsonParser.parseString(decoded).asJsonObject)
    }

    @Test fun headerIsUrlSafeAndUnpadded() {
        val value = DepositAuthStore.headerValue(token())
        assertFalse("no padding", value.endsWith("="))
        assertTrue("url-safe alphabet only", value.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    // ── the fake island ────────────────────────────────────────────────────

    /** An issuer the store talks to through an interceptor: no network, no
     *  DNS. [epoch] can be rotated and [paramsCode] flipped between tests. */
    private class Island {
        private val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val n: BigInteger = (kp.public as RSAPublicKey).modulus
        val e: BigInteger = (kp.public as RSAPublicKey).publicExponent
        private val d: BigInteger = (kp.private as RSAPrivateKey).privateExponent
        @Volatile var epoch = "e1"
        @Volatile var paramsCode = 200
        /** When set, every issue after the first blocks on it: pins the
         *  background top-up so a test can look at the reserve between the
         *  inline mint and the rest of the batch. */
        @Volatile var holdAfterFirst: CountDownLatch? = null
        val paramsCalls = AtomicInteger()
        val issued = AtomicInteger()
        private val difficulty = 6

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            when (req.url.encodedPath) {
                "/deposit-auth/params" -> {
                    paramsCalls.incrementAndGet()
                    if (paramsCode != 200) return@Interceptor reply(req, paramsCode, "{}")
                    val body = JsonObject().apply {
                        addProperty("epoch_id", epoch)
                        addProperty("suite", "RSABSSA-SHA384-PSS-Randomized")
                        add("pubkey", JsonObject().apply {
                            addProperty("n", Base64.getUrlEncoder().withoutPadding().encodeToString(BlindToken.i2osp(n, 256)))
                            addProperty("e", e.toLong())
                        })
                        add("pow", JsonObject().apply { addProperty("algo", "sha256"); addProperty("difficulty", difficulty) })
                    }
                    reply(req, 200, body.toString())
                }
                "/deposit-auth/issue" -> {
                    val buf = okio.Buffer().also { req.body!!.writeTo(it) }
                    val o = JsonParser.parseString(buf.readUtf8()).asJsonObject
                    if (o.get("epoch_id").asString != epoch) return@Interceptor reply(req, 409, "{\"detail\":\"stale epoch\"}")
                    val blinded = o.get("blinded").asString
                    if (!BlindToken.verifyPow("$epoch:$blinded", o.get("pow_nonce").asString, difficulty)) {
                        return@Interceptor reply(req, 403, "{\"detail\":\"bad pow\"}")
                    }
                    if (issued.incrementAndGet() > 1) holdAfterFirst?.await()
                    val sig = BigInteger(1, Base64.getDecoder().decode(blinded)).modPow(d, n)
                    val body = JsonObject().apply {
                        addProperty("epoch_id", epoch)
                        addProperty("blind_sig", Base64.getEncoder().encodeToString(BlindToken.i2osp(sig, 256)))
                    }
                    reply(req, 200, body.toString())
                }
                else -> reply(req, 404, "{}")
            }
        }).build()

        private fun reply(req: okhttp3.Request, code: Int, json: String): Response =
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(json.toResponseBody("application/json".toMediaType())).build()

        /** A token of this island's key verifies as plain RSA-PSS over `prepared`. */
        fun verifies(t: JsonObject): Boolean = BlindToken.verify(
            n, e,
            Base64.getDecoder().decode(t.get("prepared").asString),
            Base64.getDecoder().decode(t.get("sig").asString),
        )
    }

    private fun epochOf(t: JsonObject) = t.get("epoch_id").asString

    /** Wait for the background top-up to bring the reserve to [size], then
     *  a moment more for its final "full" check, so it has exited. */
    private fun awaitReserve(host: String, size: Int) {
        val deadline = System.currentTimeMillis() + 20_000
        while (DepositAuthStore.reserveSize(host) < size) {
            assertTrue("reserve for $host reached $size", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
        Thread.sleep(100)
    }

    @Test fun firstTokenIsHandedOverBeforeTheBatchIsDone() {
        val island = Island()
        val host = "first.island.test"
        val hold = CountDownLatch(1)
        island.holdAfterFirst = hold
        try {
            val t = DepositAuthStore.tokenFor(host, island.client)
            assertNotNull("a token is minted inline", t)
            assertTrue("it is the island's signature", island.verifies(t!!))
            // The caller did not wait for the rest of the batch: with the
            // island holding every issue after the first, nothing else has
            // landed in the reserve by the time the token is back.
            assertEquals("the batch is still being minted", 0, DepositAuthStore.reserveSize(host))
            hold.countDown()
            awaitReserve(host, 4)
            assertEquals("one inline token plus a full reserve", 5, island.issued.get())
            // The next callers pay nothing.
            val before = island.issued.get()
            repeat(3) { assertNotNull(DepositAuthStore.tokenFor(host, island.client)) }
            assertEquals(before, island.issued.get())
        } finally {
            DepositAuthStore.forget(host)
        }
    }

    @Test fun mintIsSingleFlightPerHost() {
        val island = Island()
        val host = "race.island.test"
        val pool = Executors.newFixedThreadPool(2)
        try {
            val go = CountDownLatch(1)
            val a = pool.submit<JsonObject?> { go.await(); DepositAuthStore.tokenFor(host, island.client) }
            val b = pool.submit<JsonObject?> { go.await(); DepositAuthStore.tokenFor(host, island.client) }
            go.countDown()
            val ta = a.get(20, TimeUnit.SECONDS)
            val tb = b.get(20, TimeUnit.SECONDS)
            assertNotNull(ta); assertNotNull(tb)
            assertNotEquals("two callers get two different tokens", ta, tb)
            // The loser of the lock either minted its own token or took one
            // the top-up had just left (then the top-up fills the reserve
            // back to four, unless it had already finished): three spares at
            // least, and never two whole batches (that was eight issues,
            // two PoW batches on the send path).
            awaitReserve(host, 3)
            assertTrue("issued ${island.issued.get()}", island.issued.get() <= 6)
        } finally {
            pool.shutdownNow()
            DepositAuthStore.forget(host)
        }
    }

    @Test fun aRefusalDropsOnlyItsOwnEpochAndAReturnNeedsTheCurrentOne() {
        val island = Island()
        val host = "epoch.island.test"
        try {
            val old = DepositAuthStore.tokenFor(host, island.client)!!
            awaitReserve(host, 4)
            assertEquals("e1", epochOf(old))

            // The island rotates. The refused token takes its epoch's reserve
            // with it and the next mint comes back under the new key.
            island.epoch = "e2"
            DepositAuthStore.forget(host, old)
            assertEquals("every e1 token is gone", 0, DepositAuthStore.reserveSize(host))
            val fresh = DepositAuthStore.tokenFor(host, island.client)!!
            assertEquals("e2", epochOf(fresh))
            assertTrue(island.verifies(fresh))
            awaitReserve(host, 4)

            // A second caller still holding an e1 token gets its 403 late:
            // its refusal must not throw away the e2 batch.
            val straggler = JsonObject().apply {
                addProperty("epoch_id", "e1"); addProperty("prepared", "AA=="); addProperty("sig", "AA==")
            }
            DepositAuthStore.forget(host, straggler)
            assertEquals("the e2 reserve survives an e1 refusal", 4, DepositAuthStore.reserveSize(host))

            // A dead-epoch token is not taken back; a current one goes to the
            // front and is the next one handed out.
            DepositAuthStore.giveBack(host, old)
            assertEquals(4, DepositAuthStore.reserveSize(host))
            DepositAuthStore.giveBack(host, fresh)
            assertEquals(5, DepositAuthStore.reserveSize(host))
            assertEquals(fresh, DepositAuthStore.tokenFor(host, island.client))
        } finally {
            DepositAuthStore.forget(host)
        }
    }

    @Test fun aMissingParamsRouteRestsTheHostUntilForgotten() {
        val island = Island().apply { paramsCode = 404 }
        val host = "rest.island.test"
        try {
            assertNull("no token from an island without the route", DepositAuthStore.tokenFor(host, island.client))
            assertEquals(1, island.paramsCalls.get())
            assertEquals(0, island.issued.get())
            // The island is upgraded, but the rest holds: no second probe.
            island.paramsCode = 200
            assertNull(DepositAuthStore.tokenFor(host, island.client))
            assertEquals("rested, not re-asked", 1, island.paramsCalls.get())
            // A refusal (or the explicit drop) clears the rest.
            DepositAuthStore.forget(host)
            assertNotNull("probed again once the rest is cleared", DepositAuthStore.tokenFor(host, island.client))
            assertEquals(2, island.paramsCalls.get())
        } finally {
            DepositAuthStore.forget(host)
        }
    }
}
