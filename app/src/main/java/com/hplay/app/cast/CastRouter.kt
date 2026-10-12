package com.hplay.app.cast

import com.hplay.app.R
import com.hplay.app.discovery.Cap
import com.hplay.app.discovery.Device
import com.hplay.app.server.MediaProxy
import com.hplay.app.server.StreamServer
import com.hplay.app.util.Net
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cadena de modos con caída automática (de mejor a peor). El usuario nunca elige protocolo.
 * Todas las funciones son bloqueantes: llamarlas en Dispatchers.IO.
 */
object CastRouter {
    data class Result(val routeRes: Int, val remote: Remote?, val errorRes: Int? = null)

    /** Espejo: app HPlay de la tele → DLNA TS en vivo → DLNA HLS → Roku HLS → URL/QR. */
    fun mirror(server: StreamServer, device: Device?): Result {
        val ip = Net.localIpv4() ?: return Result(R.string.route_qr, null)
        device ?: return Result(R.string.route_qr, null)
        if (Cap.HPLAY in device.caps && device.hplayUrl != null) {
            if (runCatching { openOnHplayReceiver(device.hplayUrl, server.receiverUrl(ip)) }.isSuccess) {
                return Result(R.string.route_hplay_app, BrowserRemote(server))
            }
        }
        val avt = device.avTransportUrl
        if (Cap.DLNA in device.caps && avt != null) {
            val r = DlnaRemote(avt, device.renderingControlUrl)
            val p = device.profile
            // 1) TS continuo: lo que más teles DLNA reproducen y con menos retardo.
            if (!p.known || p.ts) {
                if (tryLoad(r, server.tsUrl(ip), "HPlay", p.tsMime(), live = true)) return Result(R.string.route_dlna_ts, r)
            }
            // 2) HLS en vivo: hace falta tener segmentos antes de dar la URL.
            if (waitSegments(server) && tryLoad(r, server.hlsUrl(ip), "HPlay", p.hlsMime(), live = true)) {
                return Result(R.string.route_dlna_hls, r)
            }
            return Result(R.string.route_qr, null, R.string.err_dlna_failed)
        }
        if (Cap.ROKU in device.caps && waitSegments(server)) {
            val roku = RokuRemote(device.ip)
            if (runCatching { roku.load(server.hlsUrl(ip), "HPlay", hls = true) }.isSuccess) return Result(R.string.route_roku, roku)
        }
        return Result(R.string.route_qr, null)
    }

    /**
     * Vídeo por URL: DLNA directo → DLNA por el proxy (cabeceras) → app HPlay → Roku → navegador receptor (QR).
     * El receptor web siempre recibe el vídeo por el proxy, así sirve también como reserva.
     */
    fun sendUrl(server: StreamServer, device: Device?, url: String, headers: Map<String, String>, title: String): Result {
        val hls = isHls(url)
        val proxied = server.proxy.register(url, headers)
        server.setState(JSONObject().put("type", "url").put("src", server.proxy.pathOf(proxied)).put("kind", if (hls) "hls" else "file").put("title", title))
        val browser = BrowserRemote(server)
        device ?: return Result(R.string.route_browser_video, browser)

        // Las cookies o la autorización no viajan si la tele pide la URL directamente.
        val needsProxy = headers.keys.any { it.equals("cookie", true) || it.equals("authorization", true) }
        val candidates = if (needsProxy) listOf(proxied) else listOf(url, proxied)
        val avt = device.avTransportUrl
        if (Cap.DLNA in device.caps && avt != null) {
            val r = DlnaRemote(avt, device.renderingControlUrl)
            val mime = if (hls) device.profile.hlsMime() else MediaProxy.guessType(url).takeIf { it != "application/octet-stream" } ?: "video/mp4"
            for (u in candidates) if (tryLoad(r, u, title, mime, live = false)) return Result(R.string.route_dlna_video, r)
            if (Cap.HPLAY !in device.caps) return Result(R.string.route_browser_video, browser, R.string.err_dlna_failed)
        }
        // Receptor HPlay sin DLNA (versión antigua) o DLNA fallido: abre el receptor web, que reproduce por el proxy.
        if (Cap.HPLAY in device.caps && device.hplayUrl != null) {
            val ip = Net.localIpv4()
            if (ip != null && runCatching { openOnHplayReceiver(device.hplayUrl, server.receiverUrl(ip)) }.isSuccess) {
                return Result(R.string.route_hplay_app, browser)
            }
        }
        if (Cap.ROKU in device.caps) {
            val roku = RokuRemote(device.ip)
            if (runCatching { roku.load(candidates.first(), title, hls) }.isSuccess) return Result(R.string.route_roku_video, roku)
        }
        return Result(R.string.route_browser_video, browser)
    }

    fun isHls(url: String) = url.substringBefore('?').endsWith(".m3u8", true) || url.contains(".m3u8?", true)

    private fun tryLoad(r: DlnaRemote, url: String, title: String, mime: String, live: Boolean): Boolean =
        runCatching { r.load(url, title, mime, live); r.waitPlaying() }.getOrDefault(false)

    private fun waitSegments(server: StreamServer, min: Int = 3, timeoutMs: Long = 8_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if ((server.ts?.hls?.count() ?: 0) >= min) return true
            Thread.sleep(250)
        }
        return false
    }

    /** La app receptora de Android TV / Fire TV abre la URL a pantalla completa. */
    fun openOnHplayReceiver(base: String, receiverUrl: String) {
        val body = receiverUrl.toByteArray()
        val c = URL(URL(base), "/open").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 3_000
        c.readTimeout = 3_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain")
        c.setFixedLengthStreamingMode(body.size)
        try {
            c.outputStream.use { it.write(body) }
            check(c.responseCode == 200)
        } finally {
            c.disconnect()
        }
    }
}
