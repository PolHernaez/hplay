package com.hplay.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread

/**
 * Receptor FCast v2 (protocolo abierto de FUTO, docs.fcast.org): TCP 46899, cabecera de 4 bytes (tamaño, LE) + 1 de
 * opcode y cuerpo JSON. Lo usan Grayjay y los emisores FCast. Igual que el .exe (receivers/windows/Fcast.cs).
 */
class Fcast(private val ctx: Context, private val player: Player, private val isLan: (InetAddress) -> Boolean) {
    interface Player : Renderer.Player {
        fun loadMedia(url: String, mime: String, title: String, headers: Map<String, String>)
        /** posición, duración (s), PLAYING/PAUSED_PLAYBACK/STOPPED */
        fun status(): Triple<Double, Double, String>
    }

    @Volatile private var running = true
    private var server: ServerSocket? = null
    private var reg: NsdManager.RegistrationListener? = null

    fun start(name: String) {
        server = runCatching { ServerSocket(PORT) }.getOrNull() ?: return
        thread(name = "hplay-fcast") {
            while (running) {
                val s = runCatching { server!!.accept() }.getOrNull() ?: break
                thread { runCatching { s.use { session(it) } } }
            }
        }
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        runCatching {
            nsd.registerService(NsdServiceInfo().apply { serviceName = name; serviceType = "_fcast._tcp"; port = PORT }, NsdManager.PROTOCOL_DNS_SD, l)
            reg = l
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        reg?.let { l -> runCatching { ctx.getSystemService(NsdManager::class.java).unregisterService(l) } }
    }

    private fun session(s: Socket) {
        if (!isLan(s.inetAddress)) return
        val out = s.getOutputStream()
        val input = DataInputStream(s.getInputStream())
        send(out, 11, JSONObject().put("version", 2))
        val timer: Timer = fixedRateTimer(period = 1_000, initialDelay = 1_000) {
            val (pos, dur, st) = player.status()
            runCatching {
                send(out, 6, JSONObject().put("generationTime", System.currentTimeMillis()).put("time", pos).put("duration", dur)
                    .put("speed", 1.0).put("state", when (st) { "PLAYING" -> 1; "PAUSED_PLAYBACK" -> 2; else -> 0 }))
            }.onFailure { cancel() }
        }
        try {
            val head = ByteArray(5)
            while (running) {
                input.readFully(head)
                val size = ByteBuffer.wrap(head, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (size < 1 || size > MAX_PACKET) break // el protocolo pide cortar
                val body = ByteArray(size - 1).also { input.readFully(it) }
                val m = if (body.isEmpty()) JSONObject() else JSONObject(String(body, Charsets.UTF_8))
                handle(head[4].toInt(), m, out)
            }
        } finally {
            timer.cancel()
        }
    }

    private fun handle(op: Int, m: JSONObject, out: OutputStream) {
        when (op) {
            1 -> { // Play
                val url = m.optString("url")
                if (url.isEmpty()) { send(out, 9, JSONObject().put("message", "HPlay: only url is supported")); return }
                val h = m.optJSONObject("headers")
                val headers = h?.keys()?.asSequence()?.associateWith { h.getString(it) }.orEmpty()
                player.loadMedia(url, m.optString("container"), "FCast", headers)
                val t = m.optDouble("time", 0.0)
                if (t > 0) thread { Thread.sleep(2_500); player.seek(t) }
            }
            2 -> player.pause()
            3 -> player.play()
            4 -> player.stop()
            5 -> player.seek(m.optDouble("time", 0.0))
            8 -> player.setVolume((m.optDouble("volume", 1.0) * 100).toInt())
            12 -> send(out, 13, null) // Ping → Pong
        }
    }

    private fun send(out: OutputStream, op: Int, body: JSONObject?) {
        val b = body?.toString()?.toByteArray() ?: ByteArray(0)
        val head = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putInt(b.size + 1).put(op.toByte()).array()
        synchronized(out) { out.write(head + b); out.flush() }
    }

    companion object {
        const val PORT = 46899
        private const val MAX_PACKET = 512 * 1024
    }
}
