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

    /** Object storage of the clouds a whitelisted network is most likely to
     *  already permit, because ordinary permitted apps fetch their assets from
     *  them.
     *
     *  This is the question the earlier version could not answer. Knowing the
     *  filter works by ADDRESS tells us our machines are unreachable; it does
     *  NOT tell us where a reachable machine could stand. If one of these
     *  answers TLS while our own address does not, that names the cloud a
     *  relay would have to live in to be reachable at all — and turns "nothing
     *  can be done" into a decision with a price and a jurisdiction attached.
     *
     *  ⚠ Reachability is not a recommendation. Hosting inside the permitted
     *  perimeter means hosting under its jurisdiction, and that is a founder
     *  call, not a technical one. */
    private val CARRIERS = listOf(
        "storage.yandexcloud.net",
        "hb.bizmrg.com",
        "s3.twcstorage.ru",
    )

    /** Does anything UDP get out at all. Hysteria2 is UDP, and a whitelist
     *  that passes TCP:443 while dropping UDP wholesale would make half our
     *  relay pool useless for reasons that have nothing to do with our
     *  addresses. Asked as a plain DNS query to a resolver the network is
     *  likely to permit. */
    private const val UDP_RESOLVER = "77.88.8.8"

    data class Line(val name: String, val ok: Boolean?, val detail: String)

    data class Report(val lines: List<Line>, val verdict: Verdict, val compact: String)

    enum class Verdict { ALL_FINE, CALLS_BLOCKED, NO_INTERNET, BY_NAME, BY_ADDRESS, UNCLEAR }

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

    /** TURN reachability THROUGH a proxy — used when the obfuscated connection
     *  is up, because that is the path calls take then. A plain connect would
     *  measure a road nothing drives on.
     *
     *  ⚠⚠ Two things here are not optional, and this instrument was wrong about
     *  both until 13.08.
     *
     *  UNRESOLVED, because every relay's routing table ends in `reject` and
     *  passes a domain_suffix of rcq.app plus a short ip_cidr list that the TURN
     *  host's address is not on. Resolved here, the destination reaches the
     *  relay as an address it has no rule for and is dropped.
     *
     *  And the CONNECT IS NOT THE MEASUREMENT: sing-box answers a SOCKS request
     *  before it has established the leg outwards, so `Socket.connect` through
     *  it succeeds whether or not anything is there. This used to return true
     *  unconditionally whenever the tunnel was up — a green line for a road
     *  nothing could drive on. Only a byte coming back proves reachability, so
     *  ask TURN for one: a STUN Binding Request, answered by a Binding Success. */
    private fun probeVia(proxy: java.net.Proxy?, host: String, port: Int, timeoutMs: Int = 8000): Boolean {
        if (proxy == null) return false
        return runCatching {
            java.net.Socket(proxy).use { s ->
                s.connect(InetSocketAddress.createUnresolved(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                val txid = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                val req = java.nio.ByteBuffer.allocate(20)
                    .putShort(0x0001)          // Binding Request
                    .putShort(0)               // length
                    .putInt(0x2112A442)        // magic cookie
                    .put(txid)
                    .array()
                s.getOutputStream().apply { write(req); flush() }
                val buf = ByteArray(64)
                val n = s.getInputStream().read(buf)
                n >= 2 && (((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)) == 0x0101
            }
        }.getOrDefault(false)
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

    /** True if a plain DNS query gets an answer over UDP. Hand-rolled rather
     *  than using the resolver so this measures UDP itself and not Android's
     *  cache: a query the system already knows would come back without a
     *  single packet leaving the phone. */
    private fun udpWorks(timeoutMs: Int = 4000): Boolean = runCatching {
        val query = byteArrayOf(
            0x12, 0x34,             // id
            0x01, 0x00,             // standard query, recursion desired
            0x00, 0x01,             // one question
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x02, 'y'.code.toByte(), 'a'.code.toByte(),
            0x02, 'r'.code.toByte(), 'u'.code.toByte(),
            0x00,                   // end of name
            0x00, 0x01,             // A
            0x00, 0x01,             // IN
        )
        java.net.DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(java.net.DatagramPacket(query, query.size, InetAddress.getByName(UDP_RESOLVER), 53))
            val buf = ByteArray(512)
            s.receive(java.net.DatagramPacket(buf, buf.size))
            // Same transaction id back = a real answer, not a stray packet.
            buf[0] == 0x12.toByte() && buf[1] == 0x34.toByte()
        }
    }.getOrDefault(false)

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
        var udpRelays = 0
        var udpRelaysOpen = 0
        val relays = RelayConfigStore.currentRelays()
        val deadTags = ArrayList<String>()
        for (r in relays) {
            // ⚠ This is a TCP connect, and half the pool is Hysteria2, which
            // rides UDP. Both protocols answer on 443 of the same machine, so a
            // TCP probe says "the machine is up", NOT "this entry can carry
            // traffic here". On a network that eats QUIC every hy2 entry probes
            // OPEN and works for nobody, which is what "the bypass works
            // sometimes" looks like from the inside. Count them separately and
            // read the number next to `udp:` below.
            val hy2 = r.proto == "hysteria2"
            if (hy2) udpRelays++
            if (probe(r.server, r.port, null, timeoutMs = 4000).first == Reach.OPEN) {
                relaysOpen++
                if (hy2) udpRelaysOpen++
            } else {
                deadTags += r.tag
            }
        }
        lines += Line(
            "релеи",
            relaysOpen > 0,
            "$relaysOpen из ${relays.size} принимают TCP" +
                (if (deadTags.isEmpty()) "" else ", молчат: " + deadTags.joinToString(", ")),
        )

        // 4. The crossed pair that names the filter.
        //    A: permitted address, our name.
        val controlIp = CONTROL_HOSTS.firstNotNullOfOrNull { resolve(it) }
        val crossName = controlIp?.let { probe(it, 443, islandHost) }
        crossName?.let { add("чужой адрес + наше имя", it) }
        //    B: our address, permitted name.
        val crossAddr = islandIp?.let { probe(it, 443, CONTROL_HOSTS.first()) }
        crossAddr?.let { add("наш адрес + чужое имя", it) }

        // 5. If our own address is unreachable, where COULD a machine stand?
        //    Only worth the seconds when something is actually wrong; on a
        //    healthy network it would just be noise in the report.
        var carriersOpen = 0
        var carrierNames = ""
        /// Carrier address probed under OUR name: tells "the address is allowed"
        /// from "only that service is allowed". Null when no carrier answered.
        var carrierOurName: Pair<Reach, String>? = null
        // ⚠ Measured on EVERY run, not only when the island is unreachable.
        // Somebody whose island answers fine still turns the bypass on, and on
        // a network that blocks UDP the Hysteria2 half of the pool cannot carry
        // them — that is the difference between "the bypass is flaky" and "the
        // bypass has half as many relays as it looks like it has". Skipping the
        // measurement on a healthy direct path meant every such report arrived
        // saying ALL_FINE with nothing to go on.
        val udpOk: Boolean = udpWorks()
        lines += Line("UDP наружу", udpOk, if (udpOk) "проходит" else "не проходит")
        if (!udpOk && udpRelays > 0) {
            lines += Line(
                "релеи на UDP",
                false,
                "$udpRelays из ${relays.size} работают по UDP (Hysteria2), а UDP на этой сети не проходит",
            )
        }
        if (direct.first != Reach.OPEN) {
            val reachable = ArrayList<String>()
            var firstCarrierIp: String? = null
            for (c in CARRIERS) {
                if (probe(c, 443, c, timeoutMs = 4000).first != Reach.BLOCKED) {
                    carriersOpen++
                    reachable += c.substringBefore('.')
                    if (firstCarrierIp == null) firstCarrierIp = resolve(c)
                }
            }
            carrierNames = reachable.joinToString("+")
            lines += Line(
                "разрешённые облака",
                carriersOpen > 0,
                if (carriersOpen > 0) "$carriersOpen из ${CARRIERS.size} отвечают ($carrierNames)"
                else "ни одно из ${CARRIERS.size} не отвечает",
            )
            // ⚠⚠ The question the line above does NOT answer, and the one that
            // decides whether renting a machine in that cloud is worth anything:
            // is the ADDRESS permitted, or only that service's own name on it?
            //
            // "storage.yandexcloud.net answers" proves the filter admits THAT
            // endpoint. A VM we rent in the same cloud has a different address,
            // and under a per-IP whitelist it would be as dead as our own. So
            // re-probe the carrier's address while claiming OUR name: if the
            // connection still stands, the permission follows the address and a
            // machine standing in that range works. If it dies, the permission
            // follows the service, and the only way in is to ride INSIDE it.
            //
            // Same shape as the xname/xaddr pair above, which is what tells
            // BY_NAME from BY_ADDRESS in the first place.
            carrierOurName = firstCarrierIp?.let { probe(it, 443, islandHost, timeoutMs = 4000) }
            carrierOurName?.let {
                lines += Line(
                    "адрес облака + наше имя",
                    it.first != Reach.BLOCKED,
                    if (it.first != Reach.BLOCKED) "проходит: разрешён АДРЕС, машина в этом облаке заработает"
                    else "не проходит: разрешён только сам сервис, аренда машины там не поможет",
                )
            }
        }

        // ⚠⚠ The relay that carries call MEDIA, tested the way calls reach it:
        // straight out, with no transport in front. That is not a detail — the
        // obfuscated connection covers this app's own sockets, and WebRTC does
        // not use them. It opens its own, so on a network that blocks RCQ the
        // messages ride the tunnel and the audio has nowhere to go.
        //
        // Nothing above tests this host, which is why a phone that could not
        // place a single call still reported ALL_FINE (report #468). A verdict
        // that cannot see the thing that is broken is worse than no verdict.
        var turnOk: Boolean? = null
        app.rcq.android.call.CallDiagnostics.turnHost?.let { th ->
            // ⚠ Measure the road the call will actually take. With the tunnel up
            // the media is forwarded through it (TurnTunnel), so testing the
            // direct path would condemn a set-up that works; with the tunnel
            // down the media does go straight out, so the direct path is the
            // truth. Same reason either way: report what calls will do, not what
            // some other configuration would have done.
            val tunnelled = SingBoxTransport.isActive
            if (tunnelled) {
                val viaTunnel = probeVia(SingBoxTransport.proxy(), th, 3478)
                turnOk = viaTunnel
                lines += Line(
                    "релей звонков",
                    turnOk,
                    if (viaTunnel) "доступен через обход" else "НЕДОСТУПЕН даже через обход",
                )
            } else {
                // 443 first: it is the port most likely to survive a filter, and
                // the one the island advertises for TURN-over-TLS.
                val tls = probe(th, 443, th, timeoutMs = 5000)
                val plain = probe(th, 3478, null, timeoutMs = 4000)
                turnOk = tls.first != Reach.BLOCKED || plain.first != Reach.BLOCKED
                lines += Line(
                    "релей звонков",
                    turnOk,
                    if (turnOk == true) "доступен напрямую (443 ${short(tls.first)}, 3478 ${short(plain.first)})"
                    else "НЕДОСТУПЕН напрямую",
                )
                if (turnOk != true) {
                    lines += Line(
                        "что делать",
                        null,
                        "включить обход блокировок: тогда звонки пойдут через него",
                    )
                }
            }
        }

        // The last call, if there has been one. Shown to everybody rather than
        // only on a bad network: the calls that fail most often are exactly the
        // ones where every other check here comes back fine.
        app.rcq.android.call.CallDiagnostics.last?.let { c ->
            lines += Line(
                "последний звонок",
                c.connected,
                if (c.connected) "соединился за ${c.seconds} с"
                else "не соединился, ${c.seconds} с · ${c.ice}",
            )
            if (!c.connected) {
                lines += Line(
                    "путь для звонков",
                    c.relayReachable,
                    when (c.relayReachable) {
                        true -> "ретранслятор доступен"
                        false -> "ретранслятор недоступен с этой сети"
                        null -> "не проверялся"
                    },
                )
            }
        }

        val verdict = when {
            !controlOk && direct.first == Reach.BLOCKED -> Verdict.NO_INTERNET
            // ⚠ Everything can be reachable and calls still impossible: the
            // media relay is a separate host on separate ports, and no other
            // check here touches it. Saying ALL_FINE to someone whose calls
            // cannot connect is how #468 came in reading ALL_FINE.
            direct.first == Reach.OPEN && turnOk == false -> Verdict.CALLS_BLOCKED
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
            // Bumped to /2 when the carrier + udp fields were added, /3 when the
            // last-call fields were, /4 for `cip` (carrier address under our
            // name): a line without them is from an older build, not from a
            // network that lacked them.
            append("RCQ-NET/4 ")
            append(if (controlOk) "ctl:ok " else "ctl:dead ")
            append("dns:${if (islandIp != null) "ok" else "fail"} ")
            append("dir:${short(direct.first)} ")
            append("front:${short(frontR.first)} ")
            append("relay:$relaysOpen/${relays.size}")
            // hy2 entries are UDP, so their TCP count is not what carries them.
            if (udpRelays > 0) append("(hy2:$udpRelaysOpen/$udpRelays)")
            append(" udp:${if (udpOk) "ok" else "block"} ")
            append("xname:${crossName?.first?.let { short(it) } ?: "-"} ")
            append("xaddr:${crossAddr?.first?.let { short(it) } ?: "-"} ")
            turnOk?.let { append("turn:${if (it) (if (SingBoxTransport.isActive) "tunnel" else "ok") else "BLOCKED"} ") }
            // What the last call on this device actually managed. Absent until
            // one has been made — the audit is also run by people whose problem
            // has nothing to do with calls.
            app.rcq.android.call.CallDiagnostics.compact()?.let { append("$it ") }
            // Only present when something was wrong, which is also the only
            // time they were measured. `carrier` names where a reachable
            // machine could stand; `udp` says whether Hysteria2 has any chance
            // on this network at all.
            if (direct.first != Reach.OPEN) {
                append("carrier:$carriersOpen/${CARRIERS.size}")
                if (carrierNames.isNotEmpty()) append("($carrierNames)")
                // The half of the carrier answer that decides whether renting a
                // machine in that cloud is worth the money. `cip:ok` = the
                // address is permitted; `cip:block` = only the service is.
                carrierOurName?.let { append("/cip:${short(it.first)}") }
                append(" ")
            }
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
