package com.hplay.app.detect

import com.hplay.app.detect.MediaDetector.Availability
import com.hplay.app.detect.MediaDetector.Evidence
import com.hplay.app.detect.MediaDetector.Kind
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Comprobación de disponibilidad: pide los primeros 4 KB (Range) con las cabeceras recibidas y, si va bien, otra vez
 * sin ellas para saber si el recurso las exige (Referer/Cookie/token). Lee el principio para confirmar que es vídeo
 * (#EXTM3U, <MPD, cajas MP4, EBML, sincronismo TS). Solo la lanza el usuario o una URL que nos han dado.
 */
object Probe {
    data class Result(val availability: Availability, val status: String, val mime: String?, val evidence: Evidence?)

    private class Hit(val code: Int, val type: String?, val length: Long, val head: ByteArray, val finalUrl: String)

    fun check(url: String, headers: Map<String, String>, kind: Kind, expiresAt: Long?, now: Long = System.currentTimeMillis()): Result {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return Result(Availability.UNREACHABLE, "no http(s)", null, null)
        val hit = try { request(url, headers) } catch (e: IOException) {
            return Result(Availability.UNREACHABLE, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), null, null)
        }
        val ok = hit.code in 200..299
        val content = if (ok) sniff(hit.head, hit.type) else null
        val status = buildString {
            append(hit.code)
            hit.type?.let { append(" · ").append(it.substringBefore(';')) }
            if (hit.length > 0) append(" · ").append(size(hit.length))
            content?.let { append(" · ").append(it.name) }
            if (hit.finalUrl != url) append(" · redirect → ").append(MediaDetector.hostOf(hit.finalUrl))
        }
        if (!ok) {
            val expired = expiresAt != null && expiresAt < now
            val a = when {
                expired || hit.code == 410 -> Availability.EXPIRED
                hit.code == 401 || hit.code == 403 -> Availability.DENIED
                hit.code == 404 -> Availability.EXPIRED // URL temporal ya retirada, o mal formada
                else -> Availability.UNREACHABLE
            }
            return Result(a, status, null, null)
        }
        // ¿Hacen falta las cabeceras? Solo se sabe probando sin ellas.
        val needs = headers.isNotEmpty() && runCatching { request(url, emptyMap()).code !in 200..299 }.getOrDefault(false)
        val ev = if (content != null && content != Kind.PAGE && content != Kind.UNKNOWN) Evidence.CONTENT else null
        return Result(if (needs) Availability.NEEDS_HEADERS else Availability.OK, status, hit.type?.substringBefore(';'), ev)
    }

    private fun request(url: String, headers: Map<String, String>): Hit {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 8_000
            readTimeout = 10_000
            requestMethod = "GET"
            headers.forEach { (k, v) -> if (!k.equals("Range", true)) setRequestProperty(k, v) }
            setRequestProperty("Range", "bytes=0-4095")
        }
        try {
            val code = c.responseCode
            val head = if (code in 200..299) c.inputStream.use { s ->
                val b = ByteArray(4096); var n = 0
                while (n < b.size) { val k = s.read(b, n, b.size - n); if (k < 0) break; n += k }
                b.copyOf(n)
            } else ByteArray(0)
            val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: c.contentLengthLong
            return Hit(code, c.contentType, total, head, c.url.toString())
        } finally {
            c.disconnect()
        }
    }

    /** Tipo real según los primeros bytes (null si no se reconoce). */
    fun sniff(head: ByteArray, type: String?): Kind? {
        val bom = if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) 3 else 0
        val text = String(head, bom, minOf(head.size - bom, 512), Charsets.ISO_8859_1).trimStart(' ', '\r', '\n', '\t')
        return when {
            text.startsWith("#EXTM3U") -> Kind.HLS
            "<MPD" in text -> Kind.DASH
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "styp", "moov", "moof") -> Kind.MP4
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() ->
                if (type?.contains("webm") == true) Kind.WEBM else Kind.MKV
            head.size >= 189 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte() -> Kind.TS
            text.startsWith("ID3") || (head.size > 1 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) -> Kind.AUDIO
            text.startsWith("<!DOCTYPE html", true) || text.startsWith("<html", true) -> Kind.PAGE
            else -> null
        }
    }

    private fun size(b: Long) = when {
        b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
        b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
        b >= 1_000 -> "${b / 1000} KB"
        else -> "$b B"
    }
}
