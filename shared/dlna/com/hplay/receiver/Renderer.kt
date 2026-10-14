package com.hplay.receiver

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Media Renderer DLNA: lo que hace que la tele aparezca en el botón «enviar a la tele» de las apps de vídeo.
 * Mismo comportamiento que receivers/windows/Renderer.cs.
 */
class Renderer(private val player: Player) {
    interface Player {
        fun loadMedia(url: String, mime: String, title: String)
        fun play()
        fun pause()
        fun stop()
        fun seek(sec: Double)
        fun setVolume(v: Int)
        fun setMute(m: Boolean)
    }

    private val gate = Any()
    private var uri = ""
    private var meta = ""
    private var state = "NO_MEDIA_PRESENT"
    private var position = 0.0
    private var duration = 0.0
    private var volume = 100
    private var mute = false

    /** Estado que manda el reproductor cada medio segundo. */
    fun update(pos: Double, dur: Double, st: String, vol: Int, muted: Boolean) {
        val avtChanged: Boolean
        val rcChanged: Boolean
        synchronized(gate) {
            position = pos
            duration = dur
            avtChanged = st != state && uri.isNotEmpty()
            if (uri.isNotEmpty()) state = st
            rcChanged = vol != volume || muted != mute
            volume = vol
            mute = muted
        }
        if (avtChanged) notifySubs("avt")
        if (rcChanged) notifySubs("rc")
    }

    /** Devuelve (código HTTP, cuerpo). */
    fun soap(service: String, action: String, body: String): Pair<Int, String> = try {
        200 to when (service) {
            "avt" -> avt(action, body)
            "rc" -> rc(action, body)
            "cm" -> cm(action)
            else -> error(service)
        }
    } catch (e: Exception) {
        500 to Upnp.fault(401, "Invalid Action")
    }

    private fun avt(action: String, body: String): String {
        fun r(vararg o: Pair<String, String>) = Upnp.response(Upnp.AVT, action, o.toList())
        synchronized(gate) {
            return when (action) {
                "SetAVTransportURI" -> {
                    uri = Upnp.input(body, "CurrentURI").trim()
                    meta = Upnp.input(body, "CurrentURIMetaData")
                    state = if (uri.isNotEmpty()) "TRANSITIONING" else "NO_MEDIA_PRESENT"
                    position = 0.0; duration = 0.0
                    if (uri.isNotEmpty()) player.loadMedia(uri, Upnp.mimeFromMetadata(meta), Upnp.titleFromMetadata(meta))
                    thread { notifySubs("avt") }
                    r()
                }
                "SetNextAVTransportURI", "Next", "Previous" -> r()
                "Play" -> { check(uri.isNotEmpty()); player.play(); r() }
                "Pause" -> { player.pause(); r() }
                "Stop" -> {
                    player.stop()
                    state = if (uri.isNotEmpty()) "STOPPED" else "NO_MEDIA_PRESENT"
                    thread { notifySubs("avt") }
                    r()
                }
                "Seek" -> {
                    val unit = Upnp.input(body, "Unit")
                    if (unit == "REL_TIME" || unit == "ABS_TIME") player.seek(Upnp.parseHms(Upnp.input(body, "Target")))
                    r()
                }
                "GetTransportInfo" -> r("CurrentTransportState" to state, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
                "GetPositionInfo" -> r(
                    "Track" to (if (uri.isNotEmpty()) "1" else "0"), "TrackDuration" to Upnp.hms(duration), "TrackMetaData" to meta,
                    "TrackURI" to uri, "RelTime" to Upnp.hms(position), "AbsTime" to Upnp.hms(position),
                    "RelCount" to "2147483647", "AbsCount" to "2147483647",
                )
                "GetMediaInfo" -> r(
                    "NrTracks" to (if (uri.isNotEmpty()) "1" else "0"), "MediaDuration" to Upnp.hms(duration), "CurrentURI" to uri,
                    "CurrentURIMetaData" to meta, "NextURI" to "", "NextURIMetaData" to "", "PlayMedium" to (if (uri.isNotEmpty()) "NETWORK" else "NONE"),
                    "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED",
                )
                "GetDeviceCapabilities" -> r("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED")
                "GetTransportSettings" -> r("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
                "GetCurrentTransportActions" -> r("Actions" to when {
                    state == "PLAYING" -> "Pause,Stop,Seek"
                    uri.isNotEmpty() -> "Play,Stop,Seek"
                    else -> ""
                })
                else -> error(action)
            }
        }
    }

    private fun rc(action: String, body: String): String {
        fun r(vararg o: Pair<String, String>) = Upnp.response(Upnp.RC, action, o.toList())
        return when (action) {
            "GetVolume" -> synchronized(gate) { r("CurrentVolume" to volume.toString()) }
            "SetVolume" -> {
                val v = (Upnp.input(body, "DesiredVolume").toIntOrNull() ?: 0).coerceIn(0, 100)
                synchronized(gate) { volume = v }
                player.setVolume(v)
                thread { notifySubs("rc") }
                r()
            }
            "GetMute" -> synchronized(gate) { r("CurrentMute" to if (mute) "1" else "0") }
            "SetMute" -> {
                val m = Upnp.input(body, "DesiredMute").let { it == "1" || it.equals("true", true) }
                synchronized(gate) { mute = m }
                player.setMute(m)
                thread { notifySubs("rc") }
                r()
            }
            "ListPresets" -> r("CurrentPresetNameList" to "FactoryDefaults")
            "SelectPreset" -> r()
            else -> error(action)
        }
    }

    private fun cm(action: String): String {
        fun r(vararg o: Pair<String, String>) = Upnp.response(Upnp.CM, action, o.toList())
        return when (action) {
            "GetProtocolInfo" -> r("Source" to "", "Sink" to Upnp.SINK)
            "GetCurrentConnectionIDs" -> r("ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> r(
                "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
            )
            else -> error(action)
        }
    }

    // ---- GENA: eventos LastChange para los controladores que se suscriben ----

    private class Sub(val sid: String, val callback: String, val service: String) {
        var seq = 0
        var expires = 0L
    }

    private val subs = mutableListOf<Sub>()

    /** Cabeceras de respuesta a SUBSCRIBE/UNSUBSCRIBE, o null si la petición no vale (412). */
    fun subscribe(method: String, service: String, h: Map<String, String>): String? {
        val sid = h["sid"]
        synchronized(subs) {
            val now = System.currentTimeMillis()
            subs.removeAll { it.expires < now }
            if (method == "UNSUBSCRIBE") { subs.removeAll { it.sid == sid }; return "" }
            val sub = if (!sid.isNullOrEmpty()) {
                subs.firstOrNull { it.sid == sid } ?: return null
            } else {
                val cb = h["callback"] ?: return null
                val url = cb.substringAfter('<', "").substringBefore('>', "")
                if (!url.startsWith("http://")) return null
                Sub("uuid:" + UUID.randomUUID(), url, service).also { s ->
                    subs += s
                    thread { Thread.sleep(200); send(s) } // evento inicial obligatorio
                }
            }
            sub.expires = now + 1_800_000
            return "SID: ${sub.sid}\r\nTIMEOUT: Second-1800\r\n"
        }
    }

    private fun notifySubs(service: String) {
        val targets = synchronized(subs) { subs.filter { it.service == service && it.expires > System.currentTimeMillis() } }
        targets.forEach(::send)
    }

    private fun eventBody(service: String): String {
        val inner = synchronized(gate) {
            when (service) {
                "avt" -> "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\"><TransportState val=\"$state\"/>" +
                    "<TransportStatus val=\"OK\"/><AVTransportURI val=\"${Upnp.esc(uri)}\"/><CurrentTrackDuration val=\"${Upnp.hms(duration)}\"/>" +
                    "<CurrentTransportActions val=\"Play,Pause,Stop,Seek\"/></InstanceID></Event>"
                "rc" -> "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\"><Volume channel=\"Master\" val=\"$volume\"/>" +
                    "<Mute channel=\"Master\" val=\"${if (mute) 1 else 0}\"/></InstanceID></Event>"
                else -> return "<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
                    "<e:property><SourceProtocolInfo></SourceProtocolInfo></e:property><e:property><SinkProtocolInfo>${Upnp.SINK}</SinkProtocolInfo></e:property>" +
                    "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property></e:propertyset>"
            }
        }
        return "<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>${Upnp.esc(inner)}</LastChange></e:property></e:propertyset>"
    }

    private fun send(s: Sub) {
        runCatching {
            val u = URI(s.callback)
            val host = InetAddress.getByName(u.host)
            if (!DlnaDevice.isLan(host)) return
            val seq = synchronized(subs) { s.seq++ }
            val body = eventBody(s.service).toByteArray()
            val port = if (u.port > 0) u.port else 80
            val head = "NOTIFY ${u.rawPath.ifEmpty { "/" }}${u.rawQuery?.let { "?$it" } ?: ""} HTTP/1.1\r\nHOST: ${u.host}:$port\r\n" +
                "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: ${s.sid}\r\nSEQ: $seq\r\n" +
                "CONTENT-LENGTH: ${body.size}\r\nConnection: close\r\n\r\n"
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), 2000)
                sock.soTimeout = 2000
                sock.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                runCatching { sock.getInputStream().read() }
            }
        }
    }
}
