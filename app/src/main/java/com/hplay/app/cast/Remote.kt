package com.hplay.app.cast

import com.hplay.app.server.StreamServer
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Controles comunes del modo «Enviar vídeo». Todas las llamadas son bloqueantes (Dispatchers.IO). */
interface Remote {
    data class Status(val positionSec: Long, val durationSec: Long, val playing: Boolean, val volume: Int?)

    fun play()
    fun pause()
    fun stop()
    fun seek(sec: Long)
    fun setVolume(v: Int)
    fun status(): Status
}

/** Roku External Control Protocol (documentación pública de Roku, puerto 8060). */
class RokuRemote(private val ip: String) : Remote {
    private fun post(path: String) {
        val c = URL("http://$ip:8060$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 4_000
        c.readTimeout = 8_000
        c.doOutput = true
        c.setFixedLengthStreamingMode(0)
        try { c.outputStream.close(); c.responseCode } finally { c.disconnect() }
    }

    /** Abre el reproductor de Roku (canal 15985, «Play on Roku») con la URL. */
    fun load(url: String, title: String, hls: Boolean) {
        val e = { s: String -> URLEncoder.encode(s, "UTF-8") }
        post("/input/15985?t=v&u=${e(url)}&videoName=${e(title)}&videoFormat=${if (hls) "hls" else "mp4"}")
    }

    override fun play() = post("/keypress/Play")
    override fun pause() = post("/keypress/Play") // Roku solo tiene Play/Pausa como conmutador
    override fun stop() = post("/keypress/Back")
    override fun seek(sec: Long) {} // ECP no tiene búsqueda absoluta
    override fun setVolume(v: Int) {}
    override fun status() = Remote.Status(0, 0, true, null)
}

/** Receptor web (navegador de tele, PC, app HPlay de Android TV): órdenes por el WebSocket. */
class BrowserRemote(private val server: StreamServer) : Remote {
    private fun cmd(c: String, v: Any? = null) =
        server.sendText(JSONObject().put("type", "cmd").put("cmd", c).apply { if (v != null) put("value", v) }.toString())

    override fun play() = cmd("play")
    override fun pause() = cmd("pause")
    override fun stop() = cmd("stop")
    override fun seek(sec: Long) = cmd("seek", sec)
    override fun setVolume(v: Int) = cmd("volume", v)
    override fun status(): Remote.Status {
        val o = server.receiverStatus.value ?: return Remote.Status(0, 0, false, null)
        return Remote.Status(
            o.optDouble("pos", 0.0).toLong(),
            o.optDouble("dur", 0.0).let { if (it.isNaN() || it.isInfinite()) 0 else it.toLong() },
            !o.optBoolean("paused", true),
            (o.optDouble("vol", 1.0) * 100).toInt(),
        )
    }
}
