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
import com.hplay.app.hplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    enum class Screen { HOME, BROWSER, BATTERY }

    /** Vídeo detectado por el navegador integrado (solo lo que el propio WebView ya pide). */
    data class Media(val url: String, val headers: Map<String, String>, val title: String, val app: AppLink? = null) {
        val kind get() = when {
            app != null -> app.service.label
            Regex("\\.m3u8", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "HLS"
            Regex("\\.mpd", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "DASH"
            else -> url.substringBefore('?').substringAfterLast('.').uppercase().take(4)
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

    /** Enlace compartido desde otra app: si ya es un vídeo directo, queda listo para enviar. */
    fun handleShared(url: String) {
        detected.value = emptyList()
        onPage(url)
        if (MEDIA.containsMatchIn(url.substringBefore('?'))) detected.update { it + Media(url, emptyMap(), url.substringAfterLast('/')) }
        openBrowser(url)
    }

    /** Página abierta o enlace compartido de un servicio con app de tele (YouTube, Netflix…): primero de la lista. */
    fun onPage(url: String) {
        val link = AppLinks.parse(url) ?: return
        detected.update { list -> listOf(Media(link.url, emptyMap(), link.service.label, link)) + list.filter { it.app == null } }
    }

    /** Llamado desde shouldInterceptRequest (hilo de red del WebView). */
    fun onRequest(url: String, headers: Map<String, String>) {
        if (!url.startsWith("http") || !MEDIA.containsMatchIn(url.substringBefore('?').substringBefore('#'))) return
        detected.update { list ->
            if (list.any { it.url == url }) list else (listOf(Media(url, headers, pageTitle)) + list).take(20)
        }
    }

    fun send(m: Media) {
        if (m.app != null) {
            notice.value = R.string.app_opening
            viewModelScope.launch(Dispatchers.IO) { notice.value = AppLauncher.launch(discovery.get(selectedIp.value), m.app) }
            return
        }
        HPlayService.sendUrl(getApplication(), m.url, m.headers, m.title.ifBlank { "HPlay" }, selectedIp.value)
        screen.value = Screen.HOME
    }

    fun stop() = HPlayService.stop(getApplication())

    fun control(action: Remote.() -> Unit) {
        val r = remote.value ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { r.action() } }
    }

    companion object {
        /** Listas HLS/DASH y ficheros de vídeo; los segmentos .ts sueltos no interesan. */
        val MEDIA = Regex("\\.(m3u8|mpd|mp4|m4v|webm|mov|mkv)$", RegexOption.IGNORE_CASE)
    }
}
