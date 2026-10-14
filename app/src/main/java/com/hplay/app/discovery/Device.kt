package com.hplay.app.discovery

/** Qué sabe hacer un aparato. Los nombres comerciales solo se usan como descripción de compatibilidad. */
enum class Cap { DLNA, ROKU, CAST, AIRPLAY, RAOP, ANDROID_TV, HPLAY, FCAST, MANUAL }

data class Device(
    /** Clave única: la IP (así se fusionan SSDP, mDNS y la entrada manual del mismo aparato). */
    val ip: String,
    val name: String,
    val manufacturer: String = "",
    val model: String = "",
    val caps: Set<Cap> = emptySet(),
    val avTransportUrl: String? = null,
    val renderingControlUrl: String? = null,
    val connectionManagerUrl: String? = null,
    /** Receptor HPlay de Android TV / Fire TV: base http://ip:puerto/ */
    val hplayUrl: String? = null,
    /** ProtocolInfo «Sink» del ConnectionManager (formatos que la tele dice aceptar). */
    val sinks: List<String> = emptyList(),
    /** Puerto FCast anunciado por mDNS. */
    val fcastPort: Int? = null,
) {
    fun merge(o: Device): Device = Device(
        ip = ip,
        name = if (name.isBlank() || name == ip) o.name.ifBlank { name } else name, // el primer nombre real gana
        manufacturer = manufacturer.ifBlank { o.manufacturer },
        model = model.ifBlank { o.model },
        caps = caps + o.caps,
        avTransportUrl = avTransportUrl ?: o.avTransportUrl,
        renderingControlUrl = renderingControlUrl ?: o.renderingControlUrl,
        connectionManagerUrl = connectionManagerUrl ?: o.connectionManagerUrl,
        hplayUrl = hplayUrl ?: o.hplayUrl,
        sinks = sinks.ifEmpty { o.sinks },
        fcastPort = fcastPort ?: o.fcastPort,
    )

    val profile get() = SinkProfile(sinks)
}

/** Lectura de la lista ProtocolInfo. Lista vacía = desconocido: se prueba todo. */
data class SinkProfile(val sinks: List<String>) {
    private fun has(s: String) = sinks.any { it.contains(s, ignoreCase = true) }
    val known get() = sinks.isNotEmpty()
    val ts get() = has("video/mpeg") || has("mp2t") || has("mpeg-tts") || has("MPEG_TS")
    val hls get() = has("mpegurl")
    val mp4 get() = has("video/mp4")
    val hevc get() = has("hevc") || has("h265")

    /** El MIME exacto que anuncia la tele para TS (algunas solo aceptan el suyo). */
    fun tsMime(): String = sinks.map { it.split(':') }.firstOrNull { p ->
        p.size >= 3 && (p[2].contains("mp2t", true) || p[2].contains("mpeg-tts", true) || p[2].equals("video/mpeg", true))
    }?.get(2) ?: "video/mpeg"

    fun hlsMime(): String = sinks.map { it.split(':') }.firstOrNull { it.size >= 3 && it[2].contains("mpegurl", true) }?.get(2)
        ?: "application/vnd.apple.mpegurl"
}
