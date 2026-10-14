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
        if (Cap.CAST in device.caps && waitSegments(server)) {
            tryCast(device, server.hlsUrl(ip), "application/x-mpegURL", "HPlay", live = true)?.let { return Result(R.string.route_cast_mirror, it) }
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

        // FCast (receptores HPlay y FCast): el receptor recibe las cabeceras y pide el vídeo él mismo, sin el proxy del
        // móvil, así que el móvil puede apagarse aunque la web exija Referer o cookies.
        if (Cap.FCAST in device.caps) {
            val mime = if (hls) "application/vnd.apple.mpegurl" else MediaProxy.guessType(url).takeIf { it != "application/octet-stream" } ?: "video/mp4"
            tryFcast(device, url, mime, headers)?.let { return Result(R.string.route_fcast, it) }
        }

        // Kodi: las cabeceras van en la propia URL (url|Referer=…), tampoco hace falta el móvil.
        if (Cap.KODI in device.caps) {
            val k = KodiRemote(device.ip, device.ports[Cap.KODI] ?: 8080)
            if (runCatching { k.load(url, headers) }.isSuccess && waitPlaying { k.status().playing }) return Result(R.string.route_kodi, k)
        }

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
        // Chromecast / Google TV (Default Media Receiver): sin cabeceras, así que primero la URL original y luego el proxy.
        if (Cap.CAST in device.caps) {
            val mime = if (hls) "application/x-mpegURL" else MediaProxy.guessType(url).takeIf { it != "application/octet-stream" } ?: "video/mp4"
            for (u in candidates) tryCast(device, u, mime, title, live = false)?.let { return Result(R.string.route_cast, it) }
        }
        return Result(R.string.route_browser_video, browser)
    }

    private fun waitPlaying(timeoutMs: Long = 10_000, playing: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(playing).getOrDefault(false)) return true
            Thread.sleep(400)
        }
        return false
    }

    private fun tryCast(device: Device, url: String, mime: String, title: String, live: Boolean): CastRemote? {
        val r = runCatching { CastRemote(device.ip) }.getOrNull() ?: return null
        if (runCatching { r.load(url, mime, title, live) }.isSuccess && waitPlaying(15_000) { r.idleReason == null && r.status().playing }) return r
        r.stop()
        return null
    }

    /** Vale si el receptor llega a reproducir (o al menos no se queja) en 10 s. */
    private fun tryFcast(device: Device, url: String, mime: String, headers: Map<String, String>): FcastRemote? {
        val r = runCatching { FcastRemote(device.ip, device.ports[Cap.FCAST] ?: 46899) }.getOrNull() ?: return null
        runCatching { r.load(url, mime, headers) }.onFailure { r.stop(); return null }
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (r.error != null) break
            if (r.status().playing) return r
            Thread.sleep(300)
        }
        r.stop()
        return null
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
