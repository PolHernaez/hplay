package com.hplay.app.server

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proxy local para vídeos por URL: añade las cabeceras que el reproductor de la tele no manda
 * (Referer, User-Agent, cookies), reenvía Range y reescribe las listas HLS para que todo pase por aquí.
 * Rutas: /{token}/p/{entryId}/{base64url(destino)}.{ext}
 */
class MediaProxy(private val token: String, private val hostPort: () -> String) {
    private val entries = ConcurrentHashMap<String, Map<String, String>>()
    private val ids = AtomicInteger(1)

    /** Registra una URL con sus cabeceras y devuelve la URL proxificada que se da a la tele. */
    fun register(url: String, headers: Map<String, String>): String {
        val id = ids.getAndIncrement().toString()
        entries[id] = headers.filterKeys { it.lowercase() in FORWARDED }
        return urlFor(id, url)
    }

    private fun pathFor(id: String, target: String) = "/$token/p/$id/${b64(target)}.${extOf(target)}"
    private fun urlFor(id: String, target: String) = "http://${hostPort()}" + pathFor(id, target)

    /** Ruta sin host: el navegador receptor la resuelve contra la dirección por la que ya llegó. */
    fun pathOf(proxied: String): String = URI(proxied).rawPath

    fun handle(req: Request, id: String, encoded: String) {
        val headers = entries[id] ?: return HttpServer.text(req, 404, "unknown")
        val target = runCatching { String(Base64.getUrlDecoder().decode(encoded.substringBefore('.'))) }.getOrNull()
            ?: return HttpServer.text(req, 400, "bad url")
        val url = runCatching { URL(target) }.getOrNull()
        if (url == null || url.protocol !in setOf("http", "https") ||
            runCatching { InetAddress.getByName(url.host).isLoopbackAddress }.getOrDefault(true)
        ) return HttpServer.text(req, 403, "forbidden")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = if (req.isHead) "HEAD" else "GET"
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            req.headers["range"]?.let { setRequestProperty("Range", it) }
        }
        try {
            val status = conn.responseCode
            val type = conn.contentType ?: guessType(target)
            val isPlaylist = type.contains("mpegurl", true) || extOf(target) == "m3u8"
            if (isPlaylist && status == 200) {
                val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                val base = URI(conn.url.toString())
                val rewritten = rewritePlaylist(body, base) { pathFor(id, it) } // relativas: valen para teles y navegadores
                HttpServer.respond(req, 200, "application/vnd.apple.mpegurl", rewritten.toByteArray())
                return
            }
            val extra = buildMap {
                conn.getHeaderField("Content-Range")?.let { put("Content-Range", it) }
                put("Accept-Ranges", conn.getHeaderField("Accept-Ranges") ?: "bytes")
                put("transferMode.dlna.org", "Streaming")
            }
            val len = conn.contentLengthLong.takeIf { it >= 0 }
            req.out.write(HttpServer.head(if (status in 200..299) status else 502, type, len, extra))
            if (!req.isHead && status in 200..299) conn.inputStream.use { it.copyTo(req.out, 64 * 1024) }
            req.out.flush()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val FORWARDED = setOf("referer", "user-agent", "cookie", "origin", "authorization")

        fun b64(s: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

        fun extOf(url: String): String {
            val path = runCatching { URI(url).path }.getOrNull() ?: url
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else "bin"
        }

        fun guessType(url: String) = when (extOf(url)) {
            "m3u8" -> "application/vnd.apple.mpegurl"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "ts" -> "video/mp2t"
            "aac" -> "audio/aac"
            "mpd" -> "application/dash+xml"
            else -> "application/octet-stream"
        }

        /** Las URL de CDN a veces traen caracteres que URI rechaza; URL es más tolerante. */
        private fun resolve(base: URI, ref: String): String =
            runCatching { base.resolve(ref).toString() }.getOrElse { URL(base.toURL(), ref).toString() }

        private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

        /** Reescribe cada URI (líneas y atributos URI="…") resolviéndola contra la lista original. */
        fun rewritePlaylist(body: String, base: URI, map: (String) -> String): String =
            body.lineSequence().joinToString("\n") { line ->
                val t = line.trim()
                when {
                    t.isEmpty() -> line
                    t.startsWith("#") -> URI_ATTR.replace(line) { m -> "URI=\"${map(resolve(base, m.groupValues[1].trim()))}\"" }
                    else -> map(resolve(base, t))
                }
            }
    }
}
