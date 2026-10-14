package com.hplay.app.detect

/**
 * Lo mínimo de IPv4/UDP/DNS para el monitor DNS: leer una consulta que llega por el túnel y devolver la respuesta.
 * Solo IPv4 sin fragmentar; lo demás se ignora (la VPN solo enruta la IP del DNS falso, nada más llega aquí).
 */
object DnsPacket {
    class Query(
        val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dstPort: Int,
        /** Mensaje DNS completo (cabecera + pregunta), tal cual se reenvía. */
        val dns: ByteArray,
        val name: String,
        val type: Int,
    )

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    /** null si no es una consulta DNS IPv4/UDP al puerto 53 bien formada. */
    fun parse(p: ByteArray, len: Int): Query? {
        if (len < 28 || (u8(p, 0) shr 4) != 4) return null
        val ihl = (u8(p, 0) and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8 || u8(p, 9) != 17) return null
        if ((u16(p, 6) and 0x3FFF) != 0) return null // fragmento
        val total = minOf(u16(p, 2), len)
        val dstPort = u16(p, ihl + 2)
        if (dstPort != 53) return null
        val udpLen = u16(p, ihl + 4)
        val dnsLen = minOf(udpLen - 8, total - ihl - 8)
        if (dnsLen < 12) return null
        val dns = p.copyOfRange(ihl + 8, ihl + 8 + dnsLen)
        if ((u8(dns, 2) and 0x80) != 0 || u16(dns, 4) < 1) return null // es respuesta o sin preguntas
        val (name, end) = readName(dns, 12) ?: return null
        if (end + 4 > dns.size) return null
        return Query(p.copyOfRange(12, 16), p.copyOfRange(16, 20), u16(p, ihl), dstPort, dns, name, u16(dns, end))
    }

    /** Nombre de la pregunta (sin compresión: en una consulta nunca la hay). */
    fun readName(d: ByteArray, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = start
        while (i < d.size) {
            val n = u8(d, i)
            if (n == 0) return sb.toString() to i + 1
            if (n and 0xC0 != 0 || i + 1 + n > d.size || sb.length > 255) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..n) sb.append((d[i + k].toInt() and 0xFF).toChar())
            i += n + 1
        }
        return null
    }

    /** Paquete IPv4/UDP de vuelta: del DNS falso al puerto de quien preguntó, con el mismo ID de consulta. */
    fun reply(q: Query, answer: ByteArray): ByteArray {
        val dns = answer.copyOf()
        if (dns.size >= 2) { dns[0] = q.dns[0]; dns[1] = q.dns[1] }
        val total = 20 + 8 + dns.size
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40 // no fragmentar
        p[8] = 64; p[9] = 17
        System.arraycopy(q.dstIp, 0, p, 12, 4)
        System.arraycopy(q.srcIp, 0, p, 16, 4)
        val sum = checksum(p, 0, 20)
        p[10] = (sum shr 8).toByte(); p[11] = sum.toByte()
        p[20] = (q.dstPort shr 8).toByte(); p[21] = q.dstPort.toByte()
        p[22] = (q.srcPort shr 8).toByte(); p[23] = q.srcPort.toByte()
        p[24] = ((8 + dns.size) shr 8).toByte(); p[25] = (8 + dns.size).toByte()
        // Suma UDP 0 = sin suma (permitido en IPv4).
        System.arraycopy(dns, 0, p, 28, dns.size)
        return p
    }

    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var s = 0L
        var i = off
        while (i + 1 < off + len) { s += u16(b, i); i += 2 }
        if (i < off + len) s += u8(b, i) shl 8
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    /** Respuesta SERVFAIL mínima (cabecera + la pregunta) cuando el DNS real no contesta: la app no espera en vano. */
    fun servfail(q: Query): ByteArray {
        val (_, end) = readName(q.dns, 12) ?: (null to 12)
        val d = q.dns.copyOf(minOf(q.dns.size, end + 4))
        d[2] = (0x80 or (u8(d, 2) and 0x01)).toByte() // QR=1, RD copiado
        d[3] = 0x82.toByte() // RA=1, RCODE=2
        for (i in 6 until 12) d[i] = 0 // sin respuestas ni adicionales
        return d
    }
}
