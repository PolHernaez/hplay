package com.hplay.app.server

import android.content.Context
import com.hplay.app.discovery.ProfileCache
import com.hplay.app.stream.MediaHub
import com.hplay.app.stream.TsSink
import com.hplay.app.util.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rutas (todas bajo el token de sesión salvo la portada):
 *   /                      portada para teclear el código
 *   /{t}/                  receptor web (MSE + jmuxer)
 *   /{t}/ws                WebSocket: [tipo 1 B][duración ms 4 B][H.264 Annex-B | AAC ADTS] + mensajes JSON
 *   /{t}/live.ts           MPEG-TS continuo (DLNA)
 *   /{t}/live.m3u8, seg/N  HLS en vivo
 *   /{t}/p/…               proxy de vídeos por URL
 */
class StreamServer(
    private val ctx: Context,
    private val scope: CoroutineScope,
    val hub: MediaHub,
    private val profiles: ProfileCache,
) : MediaHub.Sink {
    val token = Net.newToken()
    var port = 0
        private set
    private val http = HttpServer(scope, ::route)
    val proxy = MediaProxy(token) { "${Net.localIpv4() ?: "127.0.0.1"}:$port" }

    @Volatile var ts: TsSink? = null
    private val clients = CopyOnWriteArrayList<WsClient>()
    @Volatile private var stateJson = JSONObject().put("type", "idle").toString()

    val clientCount = MutableStateFlow(0)
    /** Último estado que manda el receptor web cuando reproduce un vídeo por URL. */
    val receiverStatus = MutableStateFlow<JSONObject?>(null)

    fun start() {
        port = http.start(8765)
        hub.sinks.add(this)
    }

    fun stop() {
        hub.sinks.remove(this)
        http.stop()
        clients.forEach { it.queue.close() }
        clients.clear()
    }

    fun receiverUrl(ip: String = Net.localIpv4() ?: "127.0.0.1") = "http://$ip:$port/$token/"
    fun tsUrl(ip: String) = "http://$ip:$port/$token/live.ts"
    fun hlsUrl(ip: String) = "http://$ip:$port/$token/live.m3u8"

    /** Cambia lo que muestra el receptor (espejo, vídeo por URL o espera) y lo avisa a todos. */
    fun setState(json: JSONObject) {
        stateJson = json.toString()
        sendText(stateJson)
    }

    fun sendText(s: String) {
        val frame = WebSocket.frame(WebSocket.TEXT, s.toByteArray())
        clients.forEach { it.queue.trySend(frame) }
    }

    // ---- MediaHub.Sink: espejo hacia los navegadores ----

    override fun onVideo(f: MediaHub.VideoFrame) {
        if (clients.isEmpty()) return
        val frame = WebSocket.frame(WebSocket.BINARY, packet(1, f.durMs, f.data))
        clients.forEach { it.offerVideo(frame, f.key) }
    }

    override fun onAudio(f: MediaHub.AudioFrame) {
        if (clients.isEmpty()) return
        val frame = WebSocket.frame(WebSocket.BINARY, packet(2, f.durMs, f.adts))
        clients.forEach { it.offerAudio(frame) }
    }

    override fun onReset() {
        clients.forEach { it.waitKey = true }
        sendText(stateJson) // el receptor rehace el demuxer con el nuevo SPS
    }

    private fun packet(type: Int, durMs: Int, data: ByteArray): ByteArray {
        val p = ByteArray(5 + data.size)
        p[0] = type.toByte()
        p[1] = (durMs ushr 24).toByte(); p[2] = (durMs ushr 16).toByte()
        p[3] = (durMs ushr 8).toByte(); p[4] = durMs.toByte()
        System.arraycopy(data, 0, p, 5, data.size)
        return p
    }

    private inner class WsClient {
        val queue = Channel<ByteArray>(capacity = 90)
        @Volatile var waitKey = true

        fun offerVideo(frame: ByteArray, key: Boolean) {
            if (waitKey) { if (!key) return; waitKey = false }
            // Cliente lento: se descartan fotogramas hasta el siguiente clave (latencia antes que fluidez).
            if (queue.trySend(frame).isFailure) { waitKey = true; hub.requestKeyFrame() }
        }

        fun offerAudio(frame: ByteArray) {
            if (!waitKey) queue.trySend(frame)
        }
    }

    // ---- Rutas ----

    private val assetCache = ConcurrentHashMap<String, ByteArray>()

    private suspend fun route(req: Request) {
        if (req.method != "GET" && req.method != "HEAD") return HttpServer.text(req, 405, "method")
        val segs = req.path.split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return asset(req, "landing.html")
        if (!HttpServer.tokenEquals(segs[0], token)) {
            delay(300) // frena el tanteo de códigos
            return HttpServer.text(req, 404, "not found")
        }
        if (segs.size == 1 && !req.path.endsWith("/")) {
            req.out.write(HttpServer.head(302, null, 0, mapOf("Location" to "/$token/")))
            return req.out.flush()
        }
        when (val name = segs.getOrElse(1) { "" }) {
            "", "index.html" -> asset(req, "index.html")
            "receiver.js", "jmuxer.min.js", "hls.light.min.js" -> asset(req, name)
            "ws" -> websocket(req)
            "live.ts" -> liveTs(req)
            "live.m3u8" -> {
                val t = ts
                if (t == null || t.hls.count() == 0) HttpServer.text(req, 503, "starting")
                else HttpServer.respond(req, 200, "application/vnd.apple.mpegurl", t.hls.playlist().toByteArray())
            }
            "seg" -> {
                val data = segs.getOrNull(2)?.substringBefore('.')?.toLongOrNull()?.let { ts?.hls?.get(it) }
                if (data == null) HttpServer.text(req, 404, "gone")
                else HttpServer.respond(req, 200, "video/mp2t", data, HttpServer.DLNA_LIVE)
            }
            "p" -> if (segs.size >= 4) proxy.handle(req, segs[2], segs[3]) else HttpServer.text(req, 404, "not found")
            else -> HttpServer.text(req, 404, "not found")
        }
    }

    private fun asset(req: Request, name: String) {
        val bytes = assetCache.getOrPut(name) { ctx.assets.open("receiver/$name").use { it.readBytes() } }
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            else -> "application/octet-stream"
        }
        HttpServer.respond(req, 200, type, bytes)
    }

    private suspend fun liveTs(req: Request) {
        val sink = ts ?: return HttpServer.text(req, 503, "not mirroring")
        req.out.write(HttpServer.head(200, "video/mp2t", null, HttpServer.DLNA_LIVE))
        req.out.flush()
        if (req.isHead) return
        val client = TsSink.Client()
        sink.clients.add(client)
        hub.requestKeyFrame()
        try {
            for (chunk in client.queue) {
                req.out.write(chunk)
                req.out.flush()
            }
        } finally {
            sink.clients.remove(client)
        }
    }

    private suspend fun websocket(req: Request) {
        val key = req.headers["sec-websocket-key"]
        if (key == null || !req.headers["upgrade"].orEmpty().equals("websocket", true)) {
            return HttpServer.text(req, 400, "websocket only")
        }
        val out = req.out
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${HttpServer.wsAccept(key)}\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.flush()
        req.socket.soTimeout = 0
        val client = WsClient()
        client.queue.trySend(WebSocket.frame(WebSocket.TEXT, stateJson.toByteArray()))
        clients.add(client)
        clientCount.value = clients.size
        hub.requestKeyFrame()

        val writer = scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    // Sin datos 5 s → ping, así se detectan teles que se apagan sin cerrar.
                    val msg = withTimeoutOrNull(5_000) { client.queue.receiveCatching() }
                    if (msg != null && msg.isClosed) break
                    out.write(msg?.getOrNull() ?: WebSocket.frame(WebSocket.PING, ByteArray(0)))
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { req.socket.close() }
            }
        }
        try {
            while (true) {
                val m = WebSocket.read(req.input)
                when (m.opcode) {
                    WebSocket.TEXT -> onClientText(String(m.payload), req.remoteIp)
                    WebSocket.PING -> client.queue.trySend(WebSocket.frame(WebSocket.PONG, m.payload))
                    WebSocket.CLOSE -> break
                }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(client)
            clientCount.value = clients.size
            client.queue.close()
            writer.cancel()
        }
    }

    private fun onClientText(text: String, ip: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (o.optString("type")) {
            "hello" -> profiles.updateFromBrowser(ip, o)
            "status" -> receiverStatus.value = o
        }
    }
}
