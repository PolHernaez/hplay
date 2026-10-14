package com.hplay.app.cast

import java.net.URI
import java.net.URLDecoder

/**
 * Enlaces de servicios con app oficial en la tele. En vez de enviar el vídeo, se le pide a la tele que abra su app
 * en ese contenido: funciona con DRM y el móvil puede apagarse.
 */
enum class Service(
    val label: String,
    /** Paquetes de Android TV / Fire TV, por orden de preferencia (si no está ninguno, decide el sistema). */
    val tvPackages: List<String>,
    /** Canal de Roku para ECP `launch/<canal>?contentId=…` (null = sin app conocida). */
    val rokuChannel: String?,
) {
    YOUTUBE("YouTube", listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube"), "837"),
    NETFLIX("Netflix", listOf("com.netflix.ninja"), "12"),
    DISNEY("Disney+", listOf("com.disney.disneyplus"), "291097"),
    PRIME("Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod"), "13"),
    MAX("Max", listOf("com.wbd.stream"), "61322"),
    TWITCH("Twitch", listOf("tv.twitch.android.app"), null),
    SPOTIFY("Spotify", listOf("com.spotify.tv.android"), "22297"),
    CRUNCHYROLL("Crunchyroll", listOf("com.crunchyroll.crunchyroid"), "2595"),
    APPLE_TV("Apple TV", listOf("com.apple.atve.androidtv.appletv"), "551012"),
    PLEX("Plex", listOf("com.plexapp.android"), "13535"),
}

/** [url] es el enlace que abre la app de la tele; [id] el identificador del contenido (vídeo, título, canal). */
data class AppLink(val service: Service, val url: String, val id: String?)

object AppLinks {
    fun parse(raw: String): AppLink? {
        val text = raw.trim()
        if (text.startsWith("spotify:")) return AppLink(Service.SPOTIFY, text, text.substringAfterLast(':'))
        val uri = runCatching { URI(text.substringBefore(' ')) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val segs = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        fun q(k: String) = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("$k=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }

        return when {
            host == "youtu.be" -> segs.firstOrNull()?.let { yt(it, q("t")) }
            host.endsWith("youtube.com") -> when (segs.firstOrNull()) {
                "watch" -> q("v")?.let { yt(it, q("t")) }
                "shorts", "live", "embed", "v" -> segs.getOrNull(1)?.let { yt(it, q("t")) }
                else -> AppLink(Service.YOUTUBE, text, null)
            }
            host.endsWith("netflix.com") -> {
                val i = segs.indexOfFirst { it == "title" || it == "watch" }
                val id = if (i >= 0) segs.getOrNull(i + 1) else q("jbv")
                AppLink(Service.NETFLIX, id?.let { "https://www.netflix.com/title/$it" } ?: text, id)
            }
            host.endsWith("disneyplus.com") -> AppLink(Service.DISNEY, text, segs.lastOrNull())
            host.endsWith("primevideo.com") || (host.endsWith("amazon.com") && "video" in text) ->
                AppLink(Service.PRIME, text, segs.firstOrNull { it.startsWith("0") || it.startsWith("B0") || it.startsWith("amzn1") })
            host == "max.com" || host == "play.max.com" || host.endsWith("hbomax.com") -> AppLink(Service.MAX, text, segs.lastOrNull())
            host.endsWith("twitch.tv") -> AppLink(Service.TWITCH, text, segs.firstOrNull()?.takeIf { it != "videos" } ?: segs.getOrNull(1))
            host == "open.spotify.com" -> segs.takeLast(2).takeIf { it.size == 2 }?.let { (type, id) ->
                AppLink(Service.SPOTIFY, "spotify:$type:$id", id)
            }
            host.endsWith("crunchyroll.com") -> AppLink(Service.CRUNCHYROLL, text, segs.getOrNull(segs.indexOf("watch") + 1).takeIf { "watch" in segs })
            host == "tv.apple.com" -> AppLink(Service.APPLE_TV, text, segs.lastOrNull())
            host.endsWith("plex.tv") -> AppLink(Service.PLEX, text, null)
            else -> null
        }
    }

    private fun yt(id: String, t: String?): AppLink {
        val secs = t?.removeSuffix("s")?.toIntOrNull()
        return AppLink(Service.YOUTUBE, "https://www.youtube.com/watch?v=$id" + (secs?.let { "&t=$it" } ?: ""), id)
    }
}
