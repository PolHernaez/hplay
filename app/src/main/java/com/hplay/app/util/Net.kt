package com.hplay.app.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

object Net {
    /** IPv4 privada del Wi-Fi (o del punto de acceso si el móvil hace de router). */
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { iface ->
                when {
                    iface.name.startsWith("wlan") -> 0
                    iface.name.startsWith("eth") -> 1
                    iface.name.startsWith("ap") || iface.name.startsWith("swlan") -> 2
                    else -> 3
                }
            }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** Solo se atiende a la red local: privadas, enlace local, loopback y ULA IPv6 (fc00::/7). */
    fun isLan(a: InetAddress): Boolean =
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
            (a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC)

    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    /** Token de sesión corto (31^6 ≈ 8,9·10^8): se teclea con el mando de la tele. */
    fun newToken(len: Int = 6): String = String(CharArray(len) { ALPHABET[random.nextInt(ALPHABET.length)] })

    fun xmlEscape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
