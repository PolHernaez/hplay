package com.hplay.app.server

import com.hplay.app.util.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Claves en minúsculas. */
    val headers: Map<String, String>,
    val socket: Socket,
    val input: InputStream,
) {
    val out: OutputStream get() = socket.getOutputStream()
    val isHead get() = method == "HEAD"
    val remoteIp: String get() = socket.inetAddress.hostAddress ?: ""
}

/**
 * Servidor HTTP/1.1 mínimo con WebSocket. Escrito a mano para controlar la latencia
 * (TCP_NODELAY, colas por cliente) y no arrastrar dependencias pesadas a la APK.
 * Solo acepta conexiones de la red local.
 */
class HttpServer(private val scope: CoroutineScope, private val handler: suspend (Request) -> Unit) {
    private var server: ServerSocket? = null
    private var job: Job? = null

    /** Devuelve el puerto real (si [preferred] está ocupado, uno libre). */
    fun start(preferred: Int): Int {
        val ss = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferred)) } }
            .getOrElse { ServerSocket(0) }
        server = ss
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                if (!Net.isLan(s.inetAddress)) { s.close(); continue }
                launch { serve(s) }
            }
        }
        return ss.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        job?.cancel()
    }

    private suspend fun serve(s: Socket) {
        s.use { sock ->
            try {
                sock.tcpNoDelay = true
                sock.soTimeout = 15_000
                val input = BufferedInputStream(sock.getInputStream())
                val req = parse(input, sock) ?: return
                handler(req)
            } catch (e: Exception) {
                // cliente desconectado o petición rara: nunca debe tumbar el servicio
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    private fun parse(input: InputStream, s: Socket): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        var total = first.length
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            total += line.length
            if (total > 16_384 || headers.size > 64) return null
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q < 0) emptyMap() else target.substring(q + 1).split('&').filter { it.isNotEmpty() }.associate {
            val e = it.indexOf('=')
            if (e < 0) dec(it) to "" else dec(it.substring(0, e)) to dec(it.substring(e + 1))
        }
        return Request(parts[0].uppercase(), path, query, headers, s, input)
    }

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    companion object {
        private val REASONS = mapOf(
            200 to "OK", 206 to "Partial Content", 302 to "Found", 400 to "Bad Request", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 416 to "Range Not Satisfiable",
            502 to "Bad Gateway", 503 to "Service Unavailable",
        )

        /** DLNA: muchas teles exigen estas cabeceras para aceptar un flujo en directo. */
        val DLNA_LIVE = mapOf(
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
        )

        fun head(status: Int, type: String?, length: Long?, extra: Map<String, String> = emptyMap()): ByteArray = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(REASONS[status] ?: "OK").append("\r\n")
            if (type != null) append("Content-Type: ").append(type).append("\r\n")
            if (length != null) append("Content-Length: ").append(length).append("\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n")
            extra.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)

        fun respond(req: Request, status: Int, type: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
            val out = req.out
            out.write(head(status, type, body.size.toLong(), extra))
            if (!req.isHead) out.write(body)
            out.flush()
        }

        fun text(req: Request, status: Int, body: String) = respond(req, status, "text/plain; charset=utf-8", body.toByteArray())

        /** Sec-WebSocket-Accept (RFC 6455 §4.2.2). */
        fun wsAccept(key: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        )

        fun tokenEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
