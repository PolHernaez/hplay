package com.hplay.app.detect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * «Detector multimedia»: junta en un sitio lo que HPlay puede saber legítimamente de los vídeos de otras apps.
 *
 * Tres niveles que nunca se mezclan:
 *  - [Item]: una URL que alguien nos ha DADO (DLNA, compartir, «abrir con», o la que pide el propio navegador de HPlay).
 *  - [Domain]: solo un nombre de dominio visto en una consulta DNS (VPN local). NO es la dirección del vídeo.
 *  - [Evidence]: cuánto sabemos de que la URL es vídeo (comprobado leyendo el contenido, por tipo MIME o solo por la forma).
 *
 * Todo vive en memoria del proceso (las URLs llevan tokens): nada se guarda en disco ni sale del móvil.
 */
object MediaDetector {
    enum class Source { DLNA, BROWSER, SHARE, PLAYER }
    enum class Kind { HLS, DASH, MP4, WEBM, MKV, MOV, TS, AUDIO, PAGE, UNKNOWN }
    /** CONTENT: el detector ha leído el principio del recurso. MIME: el servidor/emisor declaró el tipo. URL: solo la extensión. */
    enum class Evidence { CONTENT, MIME, URL, NONE }
    enum class Availability { UNCHECKED, CHECKING, OK, NEEDS_HEADERS, DENIED, EXPIRED, UNREACHABLE }
    enum class DomainClass { VIDEO_SERVICE, CDN, OTHER }

    data class Item(
        val id: Long,
        val time: Long,
        val source: Source,
        val url: String,
        val kind: Kind,
        val mime: String?,
        val evidence: Evidence,
        val title: String,
        /** App o aparato de origen, solo si se sabe de verdad (paquete por el sistema, IP del emisor DLNA…). */
        val origin: String,
        /** Detalle técnico del emisor (User-Agent, IP:puerto, página de origen). */
        val client: String = "",
        val headers: Map<String, String> = emptyMap(),
        /** Datos del DIDL-Lite (tamaño, duración, resolución, protocolInfo) cuando los manda el emisor DLNA. */
        val meta: Map<String, String> = emptyMap(),
        val expiresAt: Long? = null,
        val availability: Availability = Availability.UNCHECKED,
        val status: String = "",
    ) {
        val domain: String get() = hostOf(url)
    }

    data class Domain(val name: String, val app: String, val count: Int, val last: Long, val cls: DomainClass)
    data class Log(val time: Long, val text: String)

    val items = MutableStateFlow<List<Item>>(emptyList())
    val domains = MutableStateFlow<List<Domain>>(emptyList())
    val log = MutableStateFlow<List<Log>>(emptyList())
    /** Receptor DLNA de diagnóstico encendido / monitor DNS (VPN local) encendido. */
    val dlnaOn = MutableStateFlow(false)
    val dnsOn = MutableStateFlow(false)

    private val ids = AtomicLong(1)

    /** Añade (o refresca, si la URL ya estaba) una URL recibida. Devuelve el elemento guardado. */
    fun report(
        source: Source, url: String, mime: String?, title: String, origin: String,
        client: String = "", headers: Map<String, String> = emptyMap(), meta: Map<String, String> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): Item {
        val m = mime?.substringBefore(';')?.trim()?.ifBlank { null }
        var saved: Item? = null
        items.update { list ->
            val old = list.firstOrNull { it.url == url && it.source == source }
            val item = Item(
                id = old?.id ?: ids.getAndIncrement(), time = now, source = source, url = url, kind = kindOf(url, m),
                mime = m ?: old?.mime, evidence = minOf(old?.evidence ?: Evidence.NONE, evidenceOf(url, m)), // menor ordinal = prueba más fuerte
                title = title.ifBlank { old?.title.orEmpty() }, origin = origin, client = client,
                headers = headers.ifEmpty { old?.headers.orEmpty() }, meta = meta.ifEmpty { old?.meta.orEmpty() },
                expiresAt = expiryOf(url), availability = old?.availability ?: Availability.UNCHECKED, status = old?.status.orEmpty(),
            )
            saved = item
            (listOf(item) + list.filter { it.id != item.id }).take(MAX_ITEMS)
        }
        if (source != Source.BROWSER) log("${source.name}: ${kindOf(url, m).name} ${hostOf(url)} ← $origin")
        return saved!!
    }

    fun set(id: Long, f: (Item) -> Item) = items.update { list -> list.map { if (it.id == id) f(it) else it } }

    /** Consulta DNS vista por la VPN local: solo el nombre (y la app si Android la identifica). */
    fun domain(name: String, app: String, now: Long = System.currentTimeMillis()) {
        val n = name.lowercase().trimEnd('.')
        if (n.isEmpty()) return
        val cls = classify(n)
        var isNew = false
        domains.update { list ->
            val old = list.firstOrNull { it.name == n && it.app == app }
            isNew = old == null
            val d = Domain(n, app, (old?.count ?: 0) + 1, now, cls)
            (listOf(d) + list.filter { it !== old }).sortedWith(compareBy<Domain> { it.cls.ordinal }.thenByDescending { it.last }).take(MAX_DOMAINS)
        }
        if (isNew && cls != DomainClass.OTHER) log("DNS: $n (${cls.name}) ← $app")
    }

    fun log(text: String, now: Long = System.currentTimeMillis()) = log.update { (listOf(Log(now, text)) + it).take(MAX_LOG) }

    fun clear() {
        items.value = emptyList()
        domains.value = emptyList()
        log.value = emptyList()
    }

    // ---------- Clasificación (funciones puras, con tests) ----------

    private const val MAX_ITEMS = 60
    private const val MAX_DOMAINS = 150
    private const val MAX_LOG = 300

    fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull()?.lowercase()
        ?: url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()

    fun kindOf(url: String, mime: String?): Kind {
        val t = mime.orEmpty().lowercase()
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            "mpegurl" in t || path.endsWith(".m3u8") -> Kind.HLS
            "dash+xml" in t || path.endsWith(".mpd") -> Kind.DASH
            "mp2t" in t || path.endsWith(".ts") -> Kind.TS
            t == "video/mp4" || t == "video/x-m4v" || path.endsWith(".mp4") || path.endsWith(".m4v") -> Kind.MP4
            "webm" in t || path.endsWith(".webm") -> Kind.WEBM
            "matroska" in t || path.endsWith(".mkv") -> Kind.MKV
            "quicktime" in t || path.endsWith(".mov") -> Kind.MOV
            t.startsWith("audio/") || Regex("\\.(mp3|m4a|aac|flac|ogg|wav|opus)$").containsMatchIn(path) -> Kind.AUDIO
            t.startsWith("video/") -> Kind.MP4.takeIf { "mp4" in t } ?: Kind.UNKNOWN
            t.startsWith("text/html") -> Kind.PAGE
            else -> Kind.UNKNOWN
        }
    }

    fun evidenceOf(url: String, mime: String?): Evidence {
        val t = mime.orEmpty().lowercase()
        return when {
            t.startsWith("video/") || t.startsWith("audio/") || "mpegurl" in t || "dash+xml" in t -> Evidence.MIME
            kindOf(url, null) != Kind.UNKNOWN -> Evidence.URL
            else -> Evidence.NONE
        }
    }

    /** MIME razonable para «abrir con» cuando el emisor no lo dijo. */
    fun mimeFor(kind: Kind): String = when (kind) {
        Kind.HLS -> "application/vnd.apple.mpegurl"
        Kind.DASH -> "application/dash+xml"
        Kind.WEBM -> "video/webm"
        Kind.MKV -> "video/x-matroska"
        Kind.MOV -> "video/quicktime"
        Kind.TS -> "video/mp2t"
        Kind.AUDIO -> "audio/*"
        else -> "video/*"
    }

    /**
     * Caducidad declarada en la propia URL (CDN con URL firmada): `expire`/`expires`/`exp`/`validto` en segundos o ms,
     * Akamai `hdnts=exp=…`, AWS `X-Amz-Date` + `X-Amz-Expires`. null si la URL no lo dice (no significa que no caduque).
     */
    fun expiryOf(url: String): Long? {
        val q = url.substringAfter('?', "").let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        if (q.isEmpty()) return null
        Regex("(?:^|[?&~;,])(?:expires?|exp|validto|valid_to)=(\\d{10,13})(?:\\D|$)", RegexOption.IGNORE_CASE).find(q)?.let {
            val v = it.groupValues[1].toLong()
            return if (it.groupValues[1].length >= 13) v else v * 1000
        }
        val date = Regex("X-Amz-Date=(\\d{8}T\\d{6}Z)", RegexOption.IGNORE_CASE).find(q)?.groupValues?.get(1)
        val secs = Regex("X-Amz-Expires=(\\d+)", RegexOption.IGNORE_CASE).find(q)?.groupValues?.get(1)?.toLongOrNull()
        if (date != null && secs != null) {
            val f = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return runCatching { f.parse(date)!!.time + secs * 1000 }.getOrNull()
        }
        return null
    }

    /** Dominios de servicios de vídeo conocidos y CDN genéricas. Un dominio de la lista NO es una URL de vídeo. */
    fun classify(host: String): DomainClass {
        val h = host.lowercase().trimEnd('.')
        fun under(vararg d: String) = d.any { h == it || h.endsWith(".$it") }
        return when {
            under(
                "googlevideo.com", "youtube.com", "youtu.be", "ttvnw.net", "twitch.tv", "jtvnw.net", "nflxvideo.net", "netflix.com",
                "dssott.com", "bamgrid.com", "disneyplus.com", "aiv-cdn.net", "primevideo.com", "hbomaxcdn.com", "max.com",
                "vimeocdn.com", "vimeo.com", "dmcdn.net", "dailymotion.com", "pluto.tv", "plutotv.net", "jwpcdn.com", "jwplayer.com",
                "mux.com", "brightcovecdn.com", "kaltura.com", "video.twimg.com", "tiktokcdn.com", "rtve.es", "3cat.cat", "ccma.cat",
                "atresmedia.com", "mediasetinfinity.es", "crunchyroll.com", "spotifycdn.com", "scdn.co", "dazn.com", "dai.google.com",
            ) -> DomainClass.VIDEO_SERVICE
            under(
                "akamaihd.net", "akamaized.net", "akamai.net", "edgesuite.net", "cloudfront.net", "fastly.net", "fastly.com", "llnwd.net",
                "edgecastcdn.net", "b-cdn.net", "bunnycdn.com", "cdn77.org", "cdn77-vid.com", "azureedge.net", "gcdn.co", "cdnvideo.ru",
                "fbcdn.net", "cdninstagram.com", "r2.dev", "workers.dev",
            ) -> DomainClass.CDN
            else -> DomainClass.OTHER
        }
    }

    /** Atributos del primer `<res>` de un DIDL-Lite (lo que el emisor DLNA cuenta del vídeo) y la clase UPnP. */
    fun parseDidl(didl: String): Map<String, String> {
        if (didl.isBlank()) return emptyMap()
        val x = if ("&lt;" in didl) com.hplay.receiver.Upnp.unesc(didl) else didl
        val out = linkedMapOf<String, String>()
        Regex("<res\\b([^>]*)>", RegexOption.IGNORE_CASE).find(x)?.groupValues?.get(1)?.let { attrs ->
            Regex("([\\w:]+)=\"([^\"]*)\"").findAll(attrs).forEach { out[it.groupValues[1]] = com.hplay.receiver.Upnp.unesc(it.groupValues[2]) }
        }
        Regex("<upnp:class>(.*?)</upnp:class>", RegexOption.DOT_MATCHES_ALL).find(x)?.let { out["class"] = it.groupValues[1].trim() }
        Regex("<upnp:albumArtURI[^>]*>(.*?)</upnp:albumArtURI>", RegexOption.DOT_MATCHES_ALL).find(x)?.let { out["albumArtURI"] = com.hplay.receiver.Upnp.unesc(it.groupValues[1].trim()) }
        return out
    }

    /**
     * Cabeceras que algunas apps pasan a un reproductor externo con «Abrir con»: convención de MX Player
     * (`headers` = String[] clave, valor, clave, valor…) y la de MediaRouter (`android.media.intent.extra.HTTP_HEADERS`, Bundle).
     */
    fun headersFromArray(a: Array<String>?): Map<String, String> {
        if (a == null) return emptyMap()
        return a.toList().chunked(2).filter { it.size == 2 && it[0].isNotBlank() }.associate { it[0].trim() to it[1] }
    }
}
