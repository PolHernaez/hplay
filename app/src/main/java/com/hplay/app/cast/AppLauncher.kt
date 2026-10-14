package com.hplay.app.cast

import com.hplay.app.R
import com.hplay.app.discovery.Cap
import com.hplay.app.discovery.Device
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Pide a la tele que abra su app oficial en el contenido de [AppLink]. Bloqueante: llamar en Dispatchers.IO. */
object AppLauncher {
    /** Devuelve el texto a mostrar (R.string). */
    fun launch(device: Device?, link: AppLink): Int {
        device ?: return R.string.app_no_device
        if (Cap.HPLAY in device.caps && device.hplayUrl != null) {
            val body = (listOf(link.url) + link.service.tvPackages).joinToString("\n")
            return when (runCatching { post(URL(URL(device.hplayUrl), "/launch").toString(), body) }.getOrDefault(-1)) {
                200 -> R.string.app_opened
                404 -> R.string.app_not_installed
                else -> R.string.app_failed
            }
        }
        if (Cap.ROKU in device.caps) {
            val ch = link.service.rokuChannel ?: return R.string.app_not_supported
            // Deep linking de Roku: contentId + mediaType; sin id, solo se abre el canal.
            val q = link.id?.let { "?contentId=${URLEncoder.encode(it, "UTF-8")}&mediaType=${if (link.service == Service.YOUTUBE) "live" else "movie"}" }.orEmpty()
            return if (runCatching { post("http://${device.ip}:8060/launch/$ch$q", "") }.getOrDefault(-1) in 200..299) R.string.app_opened else R.string.app_failed
        }
        return R.string.app_not_supported
    }

    private fun post(url: String, body: String): Int {
        val b = body.toByteArray()
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 4_000
        c.readTimeout = 8_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.setFixedLengthStreamingMode(b.size)
        return try { c.outputStream.use { it.write(b) }; c.responseCode } finally { c.disconnect() }
    }
}
