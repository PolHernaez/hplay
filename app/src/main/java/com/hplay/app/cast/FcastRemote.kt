package com.hplay.app.cast

import org.json.JSONObject
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Emisor FCast v2 (docs.fcast.org): receptores FCast oficiales (Android TV, webOS, escritorio) y los de HPlay.
 * A diferencia del DLNA, el receptor recibe las cabeceras (Referer, Cookie…) y las usa él mismo.
 */
class FcastRemote(ip: String, port: Int = 46899) : Remote {
    private val sock = Socket().apply { connect(InetSocketAddress(ip, port), 4_000); tcpNoDelay = true }
    private val out = sock.getOutputStream()
    @Volatile private var last = Remote.Status(0, 0, false, null)
    @Volatile var error: String? = null
        private set

    init {
        send(11, JSONObject().put("version", 2))
        thread(isDaemon = true, name = "fcast-rx") {
            val input = DataInputStream(sock.getInputStream())
            val head = ByteArray(5)
            runCatching {
                while (true) {
                    input.readFully(head)
                    val size = ByteBuffer.wrap(head, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (size < 1 || size > 512 * 1024) break
                    val body = ByteArray(size - 1).also { input.readFully(it) }
                    val m = if (body.isEmpty()) JSONObject() else JSONObject(String(body, Charsets.UTF_8))
                    when (head[4].toInt()) {
                        6 -> last = last.copy(
                            positionSec = m.optDouble("time", 0.0).toLong(),
                            durationSec = m.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toLong() },
                            playing = m.optInt("state") == 1,
                        )
                        7 -> last = last.copy(volume = (m.optDouble("volume", 1.0) * 100).toInt())
                        9 -> error = m.optString("message")
                        12 -> send(13, null)
                    }
                }
            }
            runCatching { sock.close() }
        }
    }

    fun load(url: String, mime: String, headers: Map<String, String>) {
        val h = JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } }
        send(1, JSONObject().put("container", mime).put("url", url).apply { if (headers.isNotEmpty()) put("headers", h) })
    }

    override fun play() = send(3, null)
    override fun pause() = send(2, null)
    override fun stop() { runCatching { send(4, null) }; runCatching { sock.close() } }
    override fun seek(sec: Long) = send(5, JSONObject().put("time", sec.toDouble()))
    override fun setVolume(v: Int) = send(8, JSONObject().put("volume", v / 100.0))
    override fun status() = last

    private fun send(op: Int, body: JSONObject?) {
        val b = body?.toString()?.toByteArray() ?: ByteArray(0)
        val head = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putInt(b.size + 1).put(op.toByte()).array()
        synchronized(out) { out.write(head + b); out.flush() }
    }
}
