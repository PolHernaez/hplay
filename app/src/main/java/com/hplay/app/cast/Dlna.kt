package com.hplay.app.cast

import com.hplay.app.util.Net
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** SOAP mínimo para UPnP AV (AVTransport:1, RenderingControl:1, ConnectionManager:1). */
object Soap {
    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"

    class UpnpError(val code: String, msg: String) : IOException("UPnP $code $msg")

    fun envelope(service: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(service).append("\">")
        for ((k, v) in args) append('<').append(k).append('>').append(Net.xmlEscape(v)).append("</").append(k).append('>')
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    /** Bloqueante. Devuelve el cuerpo de la respuesta o lanza [UpnpError]/[IOException]. */
    fun call(controlUrl: String, service: String, action: String, args: List<Pair<String, String>> = emptyList()): String {
        val body = envelope(service, action, args).toByteArray()
        val c = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"$service#$action\"")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw UpnpError(tag(text, "errorCode") ?: code.toString(), tag(text, "errorDescription").orEmpty())
            return text
        } finally {
            c.disconnect()
        }
    }

    /** Valor de una etiqueta sin importar el prefijo de espacio de nombres. */
    fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    fun hms(sec: Long): String = "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)

    fun parseHms(s: String?): Long {
        val p = s?.substringBefore('.')?.split(':') ?: return 0
        return if (p.size == 3) p.fold(0L) { acc, v -> acc * 60 + (v.toLongOrNull() ?: 0) } else 0
    }
}

object Didl {
    /**
     * Metadatos DIDL-Lite. Samsung y otras rechazan SetAVTransportURI sin ellos.
     * [live] = flujo sin búsqueda (DLNA.ORG_OP=00).
     */
    fun item(url: String, title: String, mime: String, live: Boolean, video: Boolean = true): String {
        val op = if (live) "DLNA.ORG_OP=00" else "DLNA.ORG_OP=01"
        val cls = if (video) "object.item.videoItem" else "object.item.audioItem.musicTrack"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${Net.xmlEscape(title)}</dc:title><upnp:class>$cls</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:$op;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\">" +
            "${Net.xmlEscape(url)}</res></item></DIDL-Lite>"
    }
}

/** Mando a distancia de una tele DLNA (Digital Media Renderer). */
class DlnaRemote(private val avt: String, private val rc: String?) : Remote {
    private val id = "InstanceID" to "0"

    fun load(url: String, title: String, mime: String, live: Boolean) {
        runCatching { Soap.call(avt, Soap.AVT, "Stop", listOf(id)) } // varias teles no cambian de URI reproduciendo
        Soap.call(avt, Soap.AVT, "SetAVTransportURI", listOf(id, "CurrentURI" to url, "CurrentURIMetaData" to Didl.item(url, title, mime, live)))
        try {
            Soap.call(avt, Soap.AVT, "Play", listOf(id, "Speed" to "1"))
        } catch (e: Soap.UpnpError) {
            if (e.code != "701") throw e // 701 = ya está reproduciendo (algunas arrancan solas)
        }
    }

    /** Espera a que la tele diga PLAYING. Así se detecta un formato no soportado y se pasa al siguiente modo. */
    fun waitPlaying(timeoutMs: Long = 9_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        var transitions = 0
        while (System.currentTimeMillis() < end) {
            Thread.sleep(700)
            val st = runCatching { Soap.tag(Soap.call(avt, Soap.AVT, "GetTransportInfo", listOf(id)), "CurrentTransportState") }.getOrNull()
            when (st) {
                "PLAYING" -> return true
                "STOPPED", "NO_MEDIA_PRESENT" -> if (++transitions > 4) return false
            }
        }
        return false
    }

    override fun play() { Soap.call(avt, Soap.AVT, "Play", listOf(id, "Speed" to "1")) }
    override fun pause() { Soap.call(avt, Soap.AVT, "Pause", listOf(id)) }
    override fun stop() { Soap.call(avt, Soap.AVT, "Stop", listOf(id)) }
    override fun seek(sec: Long) { Soap.call(avt, Soap.AVT, "Seek", listOf(id, "Unit" to "REL_TIME", "Target" to Soap.hms(sec))) }

    override fun setVolume(v: Int) {
        rc ?: return
        Soap.call(rc, Soap.RC, "SetVolume", listOf(id, "Channel" to "Master", "DesiredVolume" to v.coerceIn(0, 100).toString()))
    }

    override fun status(): Remote.Status {
        val pos = Soap.call(avt, Soap.AVT, "GetPositionInfo", listOf(id))
        val state = runCatching { Soap.tag(Soap.call(avt, Soap.AVT, "GetTransportInfo", listOf(id)), "CurrentTransportState") }.getOrNull()
        val vol = rc?.let { runCatching { Soap.tag(Soap.call(it, Soap.RC, "GetVolume", listOf(id, "Channel" to "Master")), "CurrentVolume")?.toIntOrNull() }.getOrNull() }
        return Remote.Status(
            positionSec = Soap.parseHms(Soap.tag(pos, "RelTime")),
            durationSec = Soap.parseHms(Soap.tag(pos, "TrackDuration")),
            playing = state == "PLAYING",
            volume = vol,
        )
    }

    companion object {
        fun protocolInfo(cmUrl: String): List<String> =
            Soap.tag(Soap.call(cmUrl, Soap.CM, "GetProtocolInfo"), "Sink")
                ?.replace("&amp;", "&")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }
}
