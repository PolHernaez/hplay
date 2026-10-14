package com.hplay.app.cast

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Emisor Google Cast (Cast V2) hacia Chromecast / Google TV con el Default Media Receiver (CC1AD845, no hace falta
 * registrarse). TLS al 8009 con certificado del propio aparato; mensajes CastMessage (protobuf) con JSON dentro.
 * El receptor no recibe cabeceras: si la web las exige, se le da la URL del proxy del móvil.
 */
class CastRemote(private val ip: String) : Remote {
    private val sock: SSLSocket
    private val out: DataOutputStream
    private val lock = Any()
    private var requestId = 1
    @Volatile private var transportId: String? = null
    @Volatile private var mediaSessionId: Int? = null
    @Volatile private var last = Remote.Status(0, 0, false, null)
    @Volatile private var lastAt = 0L
    @Volatile var idleReason: String? = null
        private set

    init {
        // El Chromecast presenta un certificado autofirmado del aparato: no hay CA que comprobar.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        sock = (ctx.socketFactory.createSocket() as SSLSocket).apply {
            connect(java.net.InetSocketAddress(ip, 8009), 4_000)
            soTimeout = 0
            startHandshake()
        }
        out = DataOutputStream(sock.outputStream)
        send(NS_CONN, "receiver-0", JSONObject().put("type", "CONNECT"))
        thread(isDaemon = true, name = "cast-rx") { readLoop() }
        thread(isDaemon = true, name = "cast-ping") {
            while (!sock.isClosed) { runCatching { send(NS_HEART, "receiver-0", JSONObject().put("type", "PING")) }; Thread.sleep(5_000) }
        }
    }

    /** Abre el Default Media Receiver y le pide reproducir [url]. */
    fun load(url: String, mime: String, title: String, live: Boolean) {
        send(NS_RECV, "receiver-0", JSONObject().put("type", "LAUNCH").put("appId", APP_ID).put("requestId", nextId()))
        val end = System.currentTimeMillis() + 15_000
        while (transportId == null && System.currentTimeMillis() < end) Thread.sleep(150)
        val t = transportId ?: error("Cast: el receptor no se abrió")
        send(NS_CONN, t, JSONObject().put("type", "CONNECT"))
        val media = JSONObject().put("contentId", url).put("contentType", mime).put("streamType", if (live) "LIVE" else "BUFFERED")
            .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
        send(NS_MEDIA, t, JSONObject().put("type", "LOAD").put("media", media).put("autoplay", true).put("currentTime", 0).put("requestId", nextId()))
    }

    override fun play() = mediaCmd("PLAY")
    override fun pause() = mediaCmd("PAUSE")
    override fun stop() {
        runCatching { mediaCmd("STOP") }
        runCatching { send(NS_RECV, "receiver-0", JSONObject().put("type", "STOP").put("requestId", nextId())) }
        runCatching { sock.close() }
    }
    override fun seek(sec: Long) = mediaCmd("SEEK") { it.put("currentTime", sec.toDouble()) }
    override fun setVolume(v: Int) =
        send(NS_RECV, "receiver-0", JSONObject().put("type", "SET_VOLUME").put("volume", JSONObject().put("level", v / 100.0)).put("requestId", nextId()))

    override fun status(): Remote.Status {
        val s = last
        // La posición solo llega con cada MEDIA_STATUS: se avanza a mano mientras reproduce.
        return if (s.playing) s.copy(positionSec = s.positionSec + (System.currentTimeMillis() - lastAt) / 1000) else s
    }

    private fun mediaCmd(type: String, extra: (JSONObject) -> Unit = {}) {
        val t = transportId ?: return
        val id = mediaSessionId ?: return
        send(NS_MEDIA, t, JSONObject().put("type", type).put("mediaSessionId", id).put("requestId", nextId()).also(extra))
    }

    private fun nextId() = synchronized(lock) { ++requestId }

    private fun readLoop() {
        val input = DataInputStream(sock.inputStream)
        runCatching {
            while (true) {
                val len = input.readInt()
                if (len <= 0 || len > 1 shl 20) break
                val msg = CastMessage.decode(ByteArray(len).also { input.readFully(it) })
                val p = runCatching { JSONObject(msg.payload) }.getOrNull() ?: continue
                when (p.optString("type")) {
                    "PING" -> send(NS_HEART, msg.source, JSONObject().put("type", "PONG"))
                    "RECEIVER_STATUS" -> {
                        val st = p.optJSONObject("status")
                        st?.optJSONArray("applications")?.let { apps ->
                            for (i in 0 until apps.length()) apps.getJSONObject(i).takeIf { it.optString("appId") == APP_ID }
                                ?.let { transportId = it.optString("transportId") }
                        }
                        st?.optJSONObject("volume")?.let { last = last.copy(volume = (it.optDouble("level", 1.0) * 100).toInt()) }
                    }
                    "MEDIA_STATUS" -> p.optJSONArray("status")?.optJSONObject(0)?.let { s ->
                        mediaSessionId = s.optInt("mediaSessionId")
                        val dur = s.optJSONObject("media")?.optDouble("duration", 0.0) ?: 0.0
                        last = last.copy(
                            positionSec = s.optDouble("currentTime", 0.0).toLong(),
                            durationSec = if (dur > 0) dur.toLong() else last.durationSec,
                            playing = s.optString("playerState") == "PLAYING" || s.optString("playerState") == "BUFFERING",
                        )
                        lastAt = System.currentTimeMillis()
                        if (s.optString("playerState") == "IDLE") idleReason = s.optString("idleReason").ifEmpty { null }
                    }
                }
            }
        }
        runCatching { sock.close() }
    }

    private fun send(ns: String, dest: String, payload: JSONObject) {
        val b = CastMessage("sender-0", dest, ns, payload.toString()).encode()
        synchronized(out) { out.writeInt(b.size); out.write(b); out.flush() }
    }

    companion object {
        const val APP_ID = "CC1AD845" // Default Media Receiver
        private const val NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECV = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    }
}

/** CastMessage de cast_channel.proto, solo los campos de texto (payload_type = STRING). */
data class CastMessage(val source: String, val dest: String, val namespace: String, val payload: String) {
    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        varintField(1, 0)          // protocol_version = CASTV2_1_0
        stringField(2, source)
        stringField(3, dest)
        stringField(4, namespace)
        varintField(5, 0)          // payload_type = STRING
        stringField(6, payload)
    }.toByteArray()

    companion object {
        fun decode(b: ByteArray): CastMessage {
            var i = 0
            fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt(); r = r or ((x and 0x7f).toLong() shl s); if (x and 0x80 == 0) return r; s += 7 } }
            val str = HashMap<Int, String>()
            while (i < b.size) {
                val key = varint().toInt()
                when (key and 7) {
                    0 -> varint()
                    2 -> { val n = varint().toInt(); str[key ushr 3] = String(b, i, n, Charsets.UTF_8); i += n }
                    else -> error("CastMessage: tipo de campo ${key and 7}")
                }
            }
            return CastMessage(str[2].orEmpty(), str[3].orEmpty(), str[4].orEmpty(), str[6].orEmpty())
        }

        private fun ByteArrayOutputStream.varint(v: Long) { var x = v; while (x >= 0x80) { write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; write(x.toInt()) }
        private fun ByteArrayOutputStream.varintField(f: Int, v: Long) { varint((f shl 3).toLong()); varint(v) }
        private fun ByteArrayOutputStream.stringField(f: Int, s: String) { val b = s.toByteArray(); varint(((f shl 3) or 2).toLong()); varint(b.size.toLong()); write(b) }
    }
}
