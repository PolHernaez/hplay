package com.hplay.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hplay.app.R
import com.hplay.app.Session
import com.hplay.app.capture.HPlayService
import com.hplay.app.cast.AppLauncher
import com.hplay.app.cast.AppLink
import com.hplay.app.cast.AppLinks
import com.hplay.app.cast.Remote
import com.hplay.app.detect.MediaDetector
import com.hplay.app.detect.Probe
import com.hplay.app.hplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    enum class Screen { HOME, BROWSER, BATTERY, DETECTOR }

    /** Vídeo detectado por el navegador integrado (solo lo que el propio WebView ya pide). */
    data class Media(val url: String, val headers: Map<String, String>, val title: String, val app: AppLink? = null, val mime: String? = null) {
        val kind get() = when {
            app != null -> app.service.label
            mime?.contains("mpegurl", true) == true -> "HLS"
            mime?.contains("dash", true) == true -> "DASH"
            mime != null -> mime.substringAfter('/').uppercase().take(4)
            Regex("\\.m3u8", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "HLS"
            Regex("\\.mpd", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "DASH"
            else -> url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "VIDEO").uppercase().take(5)
        }
    }

    private val discovery = app.hplay.discovery
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    val screen = MutableStateFlow(Screen.HOME)
    val devices = discovery.devices
    val scanning = discovery.scanning
    val selectedIp = MutableStateFlow(prefs.getString("selected", null))
    val session = Session.state
    val remote = HPlayService.remote
    val remoteStatus = MutableStateFlow<Remote.Status?>(null)
    /** Versión nueva disponible (no obligatoria); la obligatoria la muestra la Activity como diálogo. */
    val update = MutableStateFlow<com.hplay.updater.Updater.Manifest?>(null)

    val browserUrl = MutableStateFlow("")
    val detected = MutableStateFlow<List<Media>>(emptyList())
    @Volatile var pageTitle = ""
    /** Resultado de «abrir en la app de la tele» (R.string). */
    val notice = MutableStateFlow<Int?>(null)

    init {
        discovery.start()
        // Lo que llega de otras apps (DLNA, compartir, «abrir con») se comprueba solo; lo del navegador, al pulsar.
        viewModelScope.launch {
            MediaDetector.items.collect { list ->
                list.filter { it.source != MediaDetector.Source.BROWSER && it.availability == MediaDetector.Availability.UNCHECKED }.forEach(::probe)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val r = remote.value
                remoteStatus.value = if (r != null && session.value.mode == Session.Mode.URL) runCatching { r.status() }.getOrNull() else null
                delay(1_000)
            }
        }
    }

    fun select(ip: String?) {
        selectedIp.value = ip
        prefs.edit().putString("selected", ip).apply()
    }

    fun refresh() = discovery.refresh()
    fun addManual(ip: String) = discovery.addManual(ip)
    fun removeManual(ip: String) = discovery.removeManual(ip)

    fun openBrowser(url: String = browserUrl.value) {
        browserUrl.value = url
        screen.value = Screen.BROWSER
    }

    /**
     * Enlace compartido («Compartir») o abierto con «Abrir con» desde otra app: si ya es un vídeo directo, queda listo
     * para enviar. [origin]: paquete que Android declara como emisor (puede faltar). [headers]: los que la app pasa a
     * un reproductor externo (convención MX Player / MediaRouter).
     */
    fun handleShared(url: String, origin: String? = null, headers: Map<String, String> = emptyMap(), title: String = "", mime: String? = null, player: Boolean = false) {
        detected.value = emptyList()
        onPage(url)
        val kind = MediaDetector.kindOf(url, mime)
        val isMedia = MEDIA.containsMatchIn(url.substringBefore('?')) || (mime != null && kind != MediaDetector.Kind.UNKNOWN && kind != MediaDetector.Kind.PAGE)
        MediaDetector.report(
            if (player) MediaDetector.Source.PLAYER else MediaDetector.Source.SHARE, url, mime,
            title.ifBlank { url.substringBefore('?').substringAfterLast('/') }, origin ?: "?", headers = headers,
        )
        if (isMedia) detected.update { it + Media(url, headers, title.ifBlank { url.substringBefore('?').substringAfterLast('/') }, mime = mime) }
        if (player && isMedia) screen.value = Screen.DETECTOR else openBrowser(url)
    }

    /** Página abierta o enlace compartido de un servicio con app de tele (YouTube, Netflix…): primero de la lista. */
    fun onPage(url: String) {
        val link = AppLinks.parse(url) ?: return
        detected.update { list -> listOf(Media(link.url, emptyMap(), link.service.label, link)) + list.filter { it.app == null } }
    }

    /** Llamado desde shouldInterceptRequest (hilo de red del WebView). */
    fun onRequest(url: String, headers: Map<String, String>) {
        if (!url.startsWith("http") || !MEDIA.containsMatchIn(url.substringBefore('?').substringBefore('#'))) return
        // Cabeceras propias del navegador que otro reproductor no debe repetir.
        add(Media(url, headers.filterKeys { !it.startsWith("Sec-", true) && !it.startsWith("Access-Control-", true) }, pageTitle))
    }

    /** Las listas «master» primero y las de solo audio al final: si no, se mandaba el audio.m3u8 de Pluto y sin imagen. */
    private fun add(m: Media) {
        MediaDetector.report(MediaDetector.Source.BROWSER, m.url, m.mime, m.title, MediaDetector.hostOf(browserUrl.value).ifBlank { "HPlay browser" },
            client = browserUrl.value, headers = m.headers)
        addDetected(m)
    }

    private fun addDetected(m: Media) = detected.update { list ->
        if (list.any { it.url == m.url }) list
        else (listOf(m) + list).sortedBy { rank(it) }.take(20)
    }

    private fun rank(m: Media): Int {
        if (m.app != null) return 0
        val file = m.url.substringBefore('?').substringAfterLast('/').lowercase()
        return when {
            "master" in file || "index" in file -> 1
            "audio" in m.url.substringBefore('?').lowercase() -> 3
            else -> 2
        }
    }

    /** Vídeo que el script de la página ha visto (por tipo MIME o en un <video>), aunque la URL no tenga extensión. */
    fun onScriptMedia(url: String, mime: String, pageUrl: String) {
        // Segmentos sueltos (TS, AAC) no son un vídeo que enviar.
        if (!url.startsWith("http") || mime.contains("mp2t", true) || url.substringBefore('?').endsWith(".ts", true)) return
        val headers = buildMap {
            put("Referer", pageUrl)
            android.webkit.CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
        }
        add(Media(url, headers, pageTitle, mime = mime.ifBlank { null }))
    }

    fun send(m: Media) {
        // Sin aparato (navegador de la tele): YouTube se reproduce en la propia web con su reproductor oficial.
        if (m.app != null && selectedIp.value == null && m.app.service == com.hplay.app.cast.Service.YOUTUBE) {
            HPlayService.sendUrl(getApplication(), m.app.url, emptyMap(), m.title, null)
            screen.value = Screen.HOME
            return
        }
        if (m.app != null) {
            notice.value = R.string.app_opening
            viewModelScope.launch(Dispatchers.IO) { notice.value = AppLauncher.launch(discovery.get(selectedIp.value), m.app) }
            return
        }
        HPlayService.sendUrl(getApplication(), m.url, m.headers, m.title.ifBlank { "HPlay" }, selectedIp.value)
        screen.value = Screen.HOME
    }

    fun stop() = HPlayService.stop(getApplication())

    // ---------- Detector multimedia ----------

    /** Comprueba si la URL responde, si es vídeo de verdad y si exige las cabeceras recibidas. */
    fun probe(item: MediaDetector.Item) {
        if (item.availability == MediaDetector.Availability.CHECKING) return
        MediaDetector.set(item.id) { it.copy(availability = MediaDetector.Availability.CHECKING) }
        viewModelScope.launch(Dispatchers.IO) {
            val r = Probe.check(item.url, item.headers, item.kind, item.expiresAt)
            MediaDetector.set(item.id) {
                val mime = it.mime ?: r.mime
                it.copy(
                    availability = r.availability, status = r.status, mime = mime, evidence = r.evidence ?: it.evidence,
                    kind = if (it.kind == MediaDetector.Kind.UNKNOWN) MediaDetector.kindOf(it.url, mime) else it.kind,
                )
            }
            MediaDetector.log("CHECK ${item.domain}: ${r.availability.name} (${r.status})")
        }
    }

    /** Al destino elegido en la pantalla principal, por la misma ruta que el navegador (proxy con cabeceras incluido). */
    fun sendDetected(item: MediaDetector.Item) {
        send(Media(item.url, item.headers, item.title.ifBlank { "HPlay" }, mime = item.mime))
        MediaDetector.log("SEND ${item.domain} → ${selectedIp.value ?: "browser/QR"}")
    }

    fun control(action: Remote.() -> Unit) {
        val r = remote.value ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { r.action() } }
    }

    companion object {
        /** Listas HLS/DASH y ficheros de vídeo; los segmentos .ts sueltos no interesan. */
        val MEDIA = Regex("\\.(m3u8|mpd|mp4|m4v|webm|mov|mkv)$", RegexOption.IGNORE_CASE)
    }
}
