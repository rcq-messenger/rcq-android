package app.rcq.android.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * What this network actually lets through, measured with raw sockets.
 *
 * Written for the reports that say "не работает при белых списках". The
 * existing diagnostics answer "island reachable: yes/no", which is the one
 * thing we already know by then. What we cannot answer without the device is
 * WHY, and the answer decides what is even worth building:
 *
 *  * filtering by NAME (SNI) → a different name in front of the same bytes
 *    gets through, so fronting and SNI rotation are worth the work;
 *  * filtering by ADDRESS → nothing we say about ourselves matters, only
 *    being hosted at an address the network already permits;
 *  * everything dead including a permitted control host → the device has no
 *    usable internet at all and none of this is about us.
 *
 * The discriminator is deliberately crude and reliable: a TCP connect that
 * times out or is refused means the packets never landed, while a TLS
 * handshake that fails AFTER connecting means our bytes reached a real server
 * and were rejected there. So two crossed probes settle it:
 *
 *  A. permitted address + OUR name  → reached ⇒ our name is not what is cut
 *  B. our address + permitted name  → reached ⇒ our address is not what is cut
 *
 * Deliberately NOT routed through OkHttp or the tunnel: this measures the bare
 * network the app is sitting on. Runs only when the user taps the button, and
 * produces a short line they can copy out, because a device under a whitelist
 * cannot send us anything at the moment it matters.
 */
object NetworkAudit {

    /** A host the network is expected to permit even under a whitelist: if
     *  THIS is dead the device simply has no internet, and nothing below means
     *  anything. Two of them so one operator's quirk does not decide it. */
    private val CONTROL_HOSTS = listOf("ya.ru", "dzen.ru")

    data class Line(val name: String, val ok: Boolean?, val detail: String)

    data class Report(val lines: List<Line>, val verdict: Verdict, val compact: String)

    enum class Verdict { ALL_FINE, NO_INTERNET, BY_NAME, BY_ADDRESS, UNCLEAR }

    /** Outcome of a single connection attempt, kept coarse on purpose. */
    private enum class Reach {
        /** Full TLS handshake completed. */
        OPEN,
        /** TCP connected and TLS spoke, then failed (wrong cert / alert).
         *  The bytes crossed the filter, which is what we are measuring. */
        REACHED,
        /** Never got a working TCP connection: timeout or refused. */
        BLOCKED,
    }

    private fun probe(host: String, port: Int, sni: String?, timeoutMs: Int = 6000): Pair<Reach, String> {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            runCatching { sock.close() }
            return Reach.BLOCKED to (e.javaClass.simpleName)
        }
        if (sni == null) {
            runCatching { sock.close() }
            return Reach.OPEN to "tcp"
        }
        return try {
            sock.soTimeout = timeoutMs
            val ssl = insecureContext().socketFactory.createSocket(sock, sni, port, true) as SSLSocket
            // The name we claim to be talking to IS the measurement here, so it
            // is set explicitly rather than inherited from the address.
            ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(sni)) }
            ssl.startHandshake()
            val proto = ssl.session.protocol
            runCatching { ssl.close() }
            Reach.OPEN to proto
        } catch (e: Exception) {
            runCatching { sock.close() }
            // Reaching a server and being rejected by it is a PASS for the
            // question being asked, so it is reported apart from a dead connect.
            Reach.REACHED to e.javaClass.simpleName
        }
    }

    /** Trust-all context: certificate validity is irrelevant to "did the bytes
     *  get there", and the crossed probes deliberately present a name the peer
     *  has no certificate for. Never used for real traffic. */
    private fun insecureContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(
            null,
            arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }),
            java.security.SecureRandom(),
        )
    }

    private fun resolve(host: String): String? =
        runCatching { InetAddress.getAllByName(host).firstOrNull()?.hostAddress }.getOrNull()

    fun run(islandHost: String): Report {
        val lines = ArrayList<Line>()
        fun add(name: String, r: Pair<Reach, String>) {
            lines += Line(name, r.first == Reach.OPEN, "${r.first.name.lowercase()} (${r.second})")
        }

        // 1. Is there any usable internet at all.
        var controlOk = false
        for (h in CONTROL_HOSTS) {
            val r = probe(h, 443, h)
            if (r.first == Reach.OPEN) controlOk = true
            add("контроль $h", r)
        }

        // 2. Does the name even resolve. A whitelist that works by DNS answers
        //    here and nowhere else.
        val islandIp = resolve(islandHost)
        lines += Line("DNS $islandHost", islandIp != null, islandIp ?: "не резолвится")

        // 3. The three paths the app itself would take.
        val direct = probe(islandHost, 443, islandHost)
        add("остров напрямую", direct)
        val front = RelayConfigStore.frontHost
        val frontR = probe(front, 443, front)
        add("фронт $front", frontR)
        var relaysOpen = 0
        val relays = RelayConfigStore.currentRelays()
        for (r in relays) {
            if (probe(r.server, r.port, null, timeoutMs = 4000).first == Reach.OPEN) relaysOpen++
        }
        lines += Line("релеи", relaysOpen > 0, "$relaysOpen из ${relays.size} принимают TCP")

        // 4. The crossed pair that names the filter.
        //    A: permitted address, our name.
        val controlIp = CONTROL_HOSTS.firstNotNullOfOrNull { resolve(it) }
        val crossName = controlIp?.let { probe(it, 443, islandHost) }
        crossName?.let { add("чужой адрес + наше имя", it) }
        //    B: our address, permitted name.
        val crossAddr = islandIp?.let { probe(it, 443, CONTROL_HOSTS.first()) }
        crossAddr?.let { add("наш адрес + чужое имя", it) }

        val verdict = when {
            !controlOk && direct.first == Reach.BLOCKED -> Verdict.NO_INTERNET
            direct.first == Reach.OPEN -> Verdict.ALL_FINE
            // Our address answers when we ask under another name → the address
            // is permitted and the name is what gets cut.
            crossAddr != null && crossAddr.first != Reach.BLOCKED -> Verdict.BY_NAME
            // Our address is dead under any name, while a permitted address
            // takes our name fine → the filter is on the address.
            crossAddr != null && crossAddr.first == Reach.BLOCKED &&
                crossName != null && crossName.first != Reach.BLOCKED -> Verdict.BY_ADDRESS
            else -> Verdict.UNCLEAR
        }

        // One line, short enough to retype off a screen if it comes to that.
        val compact = buildString {
            append("RCQ-NET/1 ")
            append(if (controlOk) "ctl:ok " else "ctl:dead ")
            append("dns:${if (islandIp != null) "ok" else "fail"} ")
            append("dir:${short(direct.first)} ")
            append("front:${short(frontR.first)} ")
            append("relay:$relaysOpen/${relays.size} ")
            append("xname:${crossName?.first?.let { short(it) } ?: "-"} ")
            append("xaddr:${crossAddr?.first?.let { short(it) } ?: "-"} ")
            append("=> ${verdict.name}")
        }
        return Report(lines, verdict, compact)
    }

    private fun short(r: Reach) = when (r) {
        Reach.OPEN -> "ok"
        Reach.REACHED -> "reach"
        Reach.BLOCKED -> "block"
    }
}
