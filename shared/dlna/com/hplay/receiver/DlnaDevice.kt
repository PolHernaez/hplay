package com.hplay.receiver

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Media Renderer DLNA completo (HTTP de descripción/SOAP/eventos + SSDP), compartido por la app de la tele y por el
 * puente del móvil («navegador de la tele»). [extra] atiende rutas propias (devuelve null si no son suyas).
 * [onControl] ve cada acción SOAP con la conexión aún abierta (el detector del móvil la usa para saber quién envía).
 */
class DlnaDevice(
    private val port: Int,
    private val uuid: String,
    private val name: String,
    private val model: String,
    private val renderer: Renderer,
    private val server: String,
    private val extraTypes: List<String> = emptyList(),
    private val extra: (Req) -> Reply? = { null },
    private val onControl: (Req, String) -> Unit = { _, _ -> },
) {
    /** [remote]/[local]: extremos de la conexión TCP (para identificar al emisor). */
    class Req(
        val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray,
        val remote: InetSocketAddress? = null, val local: InetSocketAddress? = null,
    )
    class Reply(val code: Int, val type: String, val body: String)

    @Volatile private var running = true
    private var http: ServerSocket? = null
    private var ssdp: MulticastSocket? = null

    fun start() {
        thread(name = "dlna-http") { httpLoop() }
        thread(name = "dlna-ssdp") { ssdpLoop() }
        thread(name = "dlna-alive") {
            // Anuncio al arrancar y cada 60 s: algunos controladores solo escuchan NOTIFY.
            while (running) {
                runCatching { announce("ssdp:alive") }
                runCatching { Thread.sleep(60_000) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { announce("ssdp:byebye") }
        runCatching { http?.close() }
        runCatching { ssdp?.close() }
    }

    // ---- HTTP ----

    private fun httpLoop() {
        val ss = runCatching { ServerSocket(port) }.getOrNull() ?: return
        http = ss
        while (running) {
            val s = runCatching { ss.accept() }.getOrNull() ?: break
            thread { runCatching { s.use { serve(it) } } }
        }
    }

    /** Cabeceras byte a byte y cuerpo exacto (los metadatos DIDL traen acentos: Content-Length va en bytes). */
    private fun read(input: InputStream): Req? {
        val head = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            head.write(b)
            val a = head.toByteArray()
            val n = a.size
            if (n >= 4 && a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() && a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()) break
            if (n > 16_384) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val first = lines[0].split(' ')
        if (first.size < 2) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        val len = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..(1 shl 20) } ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val k = input.read(body, read, len - read); if (k < 0) break; read += k }
        return Req(first[0].uppercase(), first[1].substringBefore('?'), headers, body)
    }

    private fun serve(s: Socket) {
        if (!isLan(s.inetAddress)) return
        s.soTimeout = 8_000
        val req = read(s.getInputStream())?.let {
            Req(it.method, it.path, it.headers, it.body, s.remoteSocketAddress as? InetSocketAddress, s.localSocketAddress as? InetSocketAddress)
        } ?: return
        val out = s.getOutputStream()
        fun reply(code: Int, type: String?, body: String, extraHeaders: String = "") {
            val b = body.toByteArray()
            val reason = when (code) { 200 -> "OK"; 412 -> "Precondition Failed"; 403 -> "Forbidden"; 500 -> "Internal Server Error"; else -> "Not Found" }
            out.write(("HTTP/1.1 $code $reason\r\n" + (type?.let { "Content-Type: $it\r\n" } ?: "") + "Content-Length: ${b.size}\r\n" +
                "Server: $server\r\nEXT:\r\nConnection: close\r\n$extraHeaders\r\n").toByteArray())
            if (req.method != "HEAD") out.write(b)
            out.flush()
        }
        val xml = "text/xml; charset=\"utf-8\""
        val last = req.path.substringAfterLast('/')
        when (req.path) {
            "/description.xml" -> reply(200, xml, Upnp.description(uuid, name, model))
            "/avt.xml" -> reply(200, xml, Upnp.AVT_SCPD)
            "/rc.xml" -> reply(200, xml, Upnp.RC_SCPD)
            "/cm.xml" -> reply(200, xml, Upnp.CM_SCPD)
            "/upnp/control/avt", "/upnp/control/rc", "/upnp/control/cm" -> {
                val action = req.headers["soapaction"].orEmpty().trim('"', ' ').substringAfterLast('#')
                runCatching { onControl(req, action) }
                val (code, body) = renderer.soap(last, action, String(req.body, Charsets.UTF_8))
                reply(code, xml, body)
            }
            "/upnp/event/avt", "/upnp/event/rc", "/upnp/event/cm" -> {
                val h = renderer.subscribe(req.method, last, req.headers)
                if (h == null) reply(412, null, "") else reply(200, null, "", h)
            }
            else -> extra(req)?.let { reply(it.code, it.type, it.body) } ?: reply(404, "text/plain", "not found")
        }
    }

    // ---- SSDP ----

    private fun targets() = listOf("upnp:rootdevice", "uuid:$uuid", Upnp.RENDERER, Upnp.AVT, Upnp.RC, Upnp.CM) + extraTypes
    private fun usn(nt: String) = if (nt.startsWith("uuid:")) nt else "uuid:$uuid::$nt"

    private fun ssdpLoop() {
        val sock = runCatching {
            MulticastSocket(1900).apply {
                // En cada interfaz IPv4 (no solo la por defecto): las apps del mismo móvil (Play Shots, YAACC) buscan
                // por la Wi-Fi concreta y, si el grupo está unido en otra, su M-SEARCH no llega.
                val group = InetSocketAddress(InetAddress.getByName(GROUP), 1900)
                ipv4Interfaces().forEach { runCatching { joinGroup(group, it) } }
                @Suppress("DEPRECATION") runCatching { joinGroup(InetAddress.getByName(GROUP)) }
            }
        }.getOrNull() ?: return
        ssdp = sock
        val buf = ByteArray(2048)
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            if (runCatching { sock.receive(p) }.isFailure) break
            val msg = String(p.data, 0, p.length)
            if (!msg.startsWith("M-SEARCH", true)) continue
            val st = msg.lines().firstOrNull { it.startsWith("ST:", true) }?.substringAfter(':')?.trim() ?: continue
            val types = if (st == "ssdp:all") targets() else targets().filter { it.equals(st, true) }
            if (types.isEmpty()) continue
            val ip = localIpFor(p.address) ?: continue
            for (t in types) {
                val b = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: ${date.format(Date())}\r\nEXT:\r\n" +
                    "LOCATION: http://$ip:$port/description.xml\r\nSERVER: $server\r\nST: $t\r\nUSN: ${usn(t)}\r\nContent-Length: 0\r\n\r\n").toByteArray()
                runCatching { sock.send(DatagramPacket(b, b.size, p.address, p.port)) }
            }
            notifyBurst()
        }
    }

    @Volatile private var lastBurst = 0L

    /** Tras una búsqueda, tres NOTIFY: Play Shots (Cling) borra su lista antes de cada búsqueda (igual que el .exe). */
    private fun notifyBurst() {
        val now = System.currentTimeMillis()
        if (now - lastBurst < 5_000) return
        lastBurst = now
        thread { for (wait in longArrayOf(700, 800, 1500)) { Thread.sleep(wait); if (running) runCatching { announce("ssdp:alive") } } }
    }

    /** NOTIFY por cada interfaz IPv4, con su propia IP en LOCATION. */
    private fun announce(nts: String) {
        for (nif in ipv4Interfaces()) {
            val ip = nif.inetAddresses.toList().firstOrNull { it is Inet4Address }?.hostAddress ?: continue
            runCatching {
                MulticastSocket().use { s ->
                    s.networkInterface = nif
                    for (t in targets()) {
                        val b = ("NOTIFY * HTTP/1.1\r\nHOST: $GROUP:1900\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://$ip:$port/description.xml\r\n" +
                            "NT: $t\r\nNTS: $nts\r\nSERVER: $server\r\nUSN: ${usn(t)}\r\n\r\n").toByteArray()
                        s.send(DatagramPacket(b, b.size, InetAddress.getByName(GROUP), 1900))
                    }
                }
            }
        }
    }

    /** IP propia por la que se llega a quien pregunta (evita interfaces VPN o Ethernet sin uso). */
    private fun localIpFor(remote: InetAddress): String? = runCatching {
        DatagramSocket().use { it.connect(InetSocketAddress(remote, 9)); it.localAddress.hostAddress }
    }.getOrNull()?.takeIf { it != "0.0.0.0" } ?: localIps().firstOrNull()

    companion object {
        private const val GROUP = "239.255.255.250"

        fun isLan(a: InetAddress) = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress

        private fun ipv4Interfaces(): List<NetworkInterface> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { n ->
                n.isUp && !n.isLoopback && n.supportsMulticast() && n.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress }
            }
        }.getOrDefault(emptyList())

        fun localIps(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
