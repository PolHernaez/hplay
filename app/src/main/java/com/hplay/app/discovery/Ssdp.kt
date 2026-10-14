package com.hplay.app.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** Búsqueda SSDP (UPnP 1.1 §1.3): M-SEARCH a 239.255.255.250:1900 y recogida de respuestas unicast. */
object Ssdp {
    const val HPLAY_RECEIVER = "urn:hplay-org:device:Receiver:1"

    val TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "roku:ecp",
        "urn:dial-multiscreen-org:service:dial:1",
        HPLAY_RECEIVER,
        "ssdp:all",
    )

    data class Hit(val ip: String, val location: String, val st: String, val server: String)

    fun request(st: String, host: String = "239.255.255.250") =
        "M-SEARCH * HTTP/1.1\r\nHOST: $host:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n" +
            "USER-AGENT: Android/10 UPnP/1.1 HPlay/1.0\r\n\r\n"

    /** Cabeceras de una respuesta SSDP, con claves en mayúsculas. */
    fun parse(resp: String): Map<String, String> = resp.split("\r\n", "\n").drop(1).mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
    }.toMap()

    /** Bloqueante (llamar en Dispatchers.IO). [unicastTo] = una IP concreta (entrada manual). */
    fun search(timeoutMs: Int = 3000, unicastTo: String? = null): List<Hit> {
        val hits = LinkedHashMap<String, Hit>()
        DatagramSocket(null).use { sock ->
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(0))
            sock.soTimeout = 400
            val dest = InetAddress.getByName(unicastTo ?: "239.255.255.250")
            repeat(2) { // UDP se pierde: dos rondas
                for (st in TARGETS) {
                    val b = request(st, unicastTo ?: "239.255.255.250").toByteArray()
                    runCatching { sock.send(DatagramPacket(b, b.size, dest, 1900)) }
                }
            }
            val buf = ByteArray(8192)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                val h = parse(String(p.data, 0, p.length, Charsets.UTF_8))
                val loc = h["LOCATION"] ?: continue
                val ip = p.address.hostAddress ?: continue
                // Una entrada por LOCATION; se prefiere el ST más específico.
                val prev = hits[loc]
                val st = h["ST"].orEmpty()
                if (prev == null || prev.st == "ssdp:all" || prev.st.startsWith("uuid:") || prev.st == "upnp:rootdevice") {
                    hits[loc] = Hit(ip, loc, st, h["SERVER"].orEmpty())
                }
            }
        }
        return hits.values.toList()
    }
}
