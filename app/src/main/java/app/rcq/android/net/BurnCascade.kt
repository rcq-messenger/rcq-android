package app.rcq.android.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Server-side deletes that must never hold up a local wipe (F2 of the 15.09
 * cross-island spec).
 *
 * Client release C0 carries only the wipe-PIN half, [runDetached], and only the
 * home islands the roster already reached before (P0.3). The interactive burn
 * state machine and the remote islands a device visited arrive with C1.
 */
object BurnCascade {

    /** One account's home island, the session token that may delete it there,
     *  and how this phone trusted that island's certificate before the wipe
     *  erased the pin store. No toString: a token must never reach a log line.
     *
     *  [pin] is the [IslandTrust] record on file for the host (null when there
     *  was none) and [caOnly] whether the host is one that is never pinned. Both
     *  are read before the wipe, because the wipe clears the store the live
     *  trust manager consults: a delete judged against the empty store takes
     *  whatever certificate is on the wire as a first use and hands it the
     *  bearer token. */
    class HomeTarget(
        val host: String,
        val token: String,
        val pin: IslandTrust.Record? = null,
        val caOnly: Boolean = false,
    ) {
        override fun toString(): String = "HomeTarget"
    }

    /** What the wipe read out of the stores BEFORE erasing them. Memory only,
     *  never persisted: a wipe PIN promises nothing is left on disk. */
    class Snapshot(val homes: List<HomeTarget>) {
        val isEmpty: Boolean get() = homes.isEmpty()
        override fun toString(): String = "Snapshot(${homes.size})"
    }

    const val DETACHED_DEADLINE_MS = 8_000L

    /** Process-level, not the Session's scope: the wipe tears the session down,
     *  and a delete that dies with it is a delete that never ran. */
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ⚠⚠ Called only AFTER the local wipe has finished, and it returns at once.
     * The founder's rule for the wipe PIN (decision 2): the network never
     * blocks it. The old order ran the deletes first with an 8 s wait, so on a
     * hostile network the person entering the PIN watched a live account on
     * screen for eight seconds.
     *
     * One deadline for everything, all targets in parallel, no retry, and no
     * logs that name an account or a host. [onFinished] runs when every delete
     * is over or when the deadline fires, whichever is first.
     *
     * ⚠ The deletes are NOT children of the coroutine that waits. A child stuck
     * in a thread that ignores cancellation holds a `coroutineScope` open until
     * that thread returns, and "8 s" quietly became OkHttp's 30 or 90 s. Here
     * the wait is a cancellable join under the deadline, and the deadline then
     * cancels each delete, which for the real transport cancels its call.
     */
    fun runDetached(
        snapshot: Snapshot,
        deadlineMs: Long = DETACHED_DEADLINE_MS,
        delete: suspend (HomeTarget) -> Unit = { deleteHome(it, deadlineMs) },
        onFinished: () -> Unit = {},
    ): Job? {
        if (snapshot.isEmpty) return null
        val deletes = snapshot.homes.map { t ->
            detachedScope.launch {
                try {
                    delete(t)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // One island failing never stops the others, and nothing is
                    // logged: the exception text can carry the host.
                }
            }
        }
        return detachedScope.launch {
            try {
                withTimeoutOrNull(deadlineMs) { deletes.joinAll() }
            } finally {
                deletes.forEach { it.cancel() }
                runCatching { onFinished() }
            }
        }
    }

    /**
     * May the detached delete talk to a server presenting a leaf with
     * fingerprint [leafFp]? The live rule of [IslandTrust.decide] with its one
     * writing branch taken out: nothing here is a first use, and nothing is
     * written. [caValid] is chain AND name, as in [IslandTrust].
     *  - A host that is never pinned: an authority, and only an authority.
     *  - A fingerprint the person typed: that fingerprint, CA or not, as live.
     *  - Otherwise an authority is enough, as live (it is how an island moves
     *    to a CA), or a leaf that hashes to the pin on file.
     *  - No record and no authority: refused. Live, that is the first-use
     *    branch; after a wipe it is exactly what a man in the middle presents.
     */
    fun detachedTrusts(pin: IslandTrust.Record?, caOnly: Boolean, caValid: Boolean, leafFp: String): Boolean {
        if (caOnly) return caValid
        if (pin?.source == IslandTrust.Source.TYPED) return pin.fp != null && pin.fp == leafFp
        if (caValid) return true
        return pin?.mode == IslandTrust.Mode.PINNED && pin.fp != null && pin.fp == leafFp
    }

    /**
     * The delete itself, on a client of its own rather than [RcqApi]:
     *  - trust comes from the snapshot ([detachedTrusts]) and never writes the
     *    pin store, so a wipe leaves no record of the island it dialled;
     *  - one attempt with `callTimeout` at the deadline, and no relay engage.
     *    [RcqApi]'s route ladder retried through a freshly started sing-box, so
     *    one delete ran for minutes and brought the tunnel up on a phone that
     *    now looks freshly installed. A tunnel that is ALREADY up is used,
     *    because going direct around it is the leak it exists to prevent;
     *  - `enqueue` under a cancellable suspension, so the deadline cancels the
     *    call instead of waiting for a blocked `execute()` to give up.
     */
    private suspend fun deleteHome(t: HomeTarget, deadlineMs: Long) {
        app.rcq.android.security.DuressGate.check()
        val client = detachedClient(t, deadlineMs)
        val req = Request.Builder()
            .url("https://${t.host}/auth/account")
            .delete()
            .header("Authorization", "Bearer ${t.token}")
            .build()
        val call = client.newCall(req)
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }
    }

    private val platform: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun detachedClient(t: HomeTarget, deadlineMs: Long): OkHttpClient {
        // Bound to the one host this client exists for, so the decision never
        // depends on what the socket reports.
        val bareHost = IslandTrust.hostAndPort(t.host).first
        val tm = SnapshotTrustManager(platform, bareHost, t.pin, t.caOnly)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder()
            .connectTimeout(deadlineMs, TimeUnit.MILLISECONDS)
            .readTimeout(deadlineMs, TimeUnit.MILLISECONDS)
            .writeTimeout(deadlineMs, TimeUnit.MILLISECONDS)
            .callTimeout(deadlineMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(SingBoxTransport.proxy())
            // A closed (masquerade) island answers only with its access header.
            .addInterceptor(AccessTokenInterceptor)
            .addInterceptor(UserAgentInterceptor)
            .sslSocketFactory(ssl.socketFactory, tm)
            // The trust manager already ran the name gate on the CA branch, and
            // a pinned leaf is the identity itself; the verifier repeats the
            // same rule from the session so neither half can be skipped.
            .hostnameVerifier { _, session ->
                val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
                    ?: return@hostnameVerifier false
                val nameOk = runCatching { OkHostnameVerifier.verify(bareHost, leaf) }.getOrDefault(false)
                val fp = IslandTrust.sha256Hex(leaf.encoded)
                // The chain was already validated by the trust manager; here
                // only the name half of `caValid` is still open.
                detachedTrusts(t.pin, t.caOnly, caValid = nameOk && tm.chainWasCaValid, leafFp = fp)
            }
            .build()
    }

    private class SnapshotTrustManager(
        private val platform: X509TrustManager,
        private val host: String,
        private val pin: IslandTrust.Record?,
        private val caOnly: Boolean,
    ) : X509ExtendedTrustManager() {

        @Volatile var chainWasCaValid: Boolean = false

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client auth not used")

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            throw CertificateException("client auth not used")

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            throw CertificateException("client auth not used")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            judge(chain) { platform.checkServerTrusted(chain, authType) }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            judge(chain) {
                (platform as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket)
                    ?: platform.checkServerTrusted(chain, authType)
            }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            judge(chain) {
                (platform as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine)
                    ?: platform.checkServerTrusted(chain, authType)
            }

        private fun judge(chain: Array<X509Certificate>, platformCheck: () -> Unit) {
            if (chain.isEmpty()) throw CertificateException("empty chain")
            val chainOk = runCatching(platformCheck).isSuccess
            chainWasCaValid = chainOk
            val nameOk = runCatching { OkHostnameVerifier.verify(host, chain[0]) }.getOrDefault(false)
            val fp = IslandTrust.sha256Hex(chain[0].encoded)
            if (!detachedTrusts(pin, caOnly, caValid = chainOk && nameOk, leafFp = fp)) {
                throw CertificateException("detached delete: certificate not trusted")
            }
        }
    }
}
