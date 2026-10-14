package com.hplay.app.detect

import android.content.Context
import android.system.OsConstants
import com.hplay.receiver.DlnaDevice
import com.hplay.receiver.Upnp

/**
 * Lo que un emisor DLNA (Play Shots, YAACC, BubbleUPnP…) cuenta a un receptor HPlay: la URL del vídeo en
 * `SetAVTransportURI` y, si quiere, metadatos DIDL-Lite (tipo MIME, título, tamaño, duración). DLNA no tiene sitio
 * para cabeceras HTTP: si el vídeo exige Referer/Cookie, el emisor no puede pasarlas (límite del protocolo).
 */
object DlnaCapture {
    /** Acciones que se apuntan en el registro (las Get* se repiten cada segundo y solo harían ruido). */
    private val LOGGED = setOf("SetAVTransportURI", "SetNextAVTransportURI", "Play", "Pause", "Stop", "Seek", "SetVolume", "SetMute")

    fun onControl(ctx: Context, receiver: String, req: DlnaDevice.Req, action: String) {
        val remote = req.remote
        val ip = remote?.address?.hostAddress.orEmpty()
        val sameDevice = remote?.address?.let { it.isLoopbackAddress || ip in DlnaDevice.localIps() } == true
        val app = if (remote != null && req.local != null) runCatching { AppIdentity.owner(ctx, OsConstants.IPPROTO_TCP, remote, req.local) }.getOrNull() else null
        val origin = app ?: if (sameDevice) "this phone ($ip)" else "LAN $ip"
        val client = listOfNotNull(req.headers["user-agent"], remote?.let { "${it.address.hostAddress}:${it.port}" }).joinToString(" · ")
        if (action in LOGGED) MediaDetector.log("DLNA $receiver: $action ← $origin")
        if (action != "SetAVTransportURI") return
        val body = String(req.body, Charsets.UTF_8)
        val uri = Upnp.input(body, "CurrentURI").trim()
        if (uri.isEmpty()) { MediaDetector.log("DLNA $receiver: SetAVTransportURI without URL ← $origin"); return }
        val didl = Upnp.input(body, "CurrentURIMetaData")
        val meta = MediaDetector.parseDidl(didl)
        val mime = Upnp.mimeFromMetadata(didl).ifBlank { meta["protocolInfo"]?.split(':')?.getOrNull(2).orEmpty() }
        MediaDetector.report(
            MediaDetector.Source.DLNA, uri, mime.ifBlank { null }, Upnp.titleFromMetadata(didl), origin, client,
            meta = meta + ("receiver" to receiver),
        )
    }
}
