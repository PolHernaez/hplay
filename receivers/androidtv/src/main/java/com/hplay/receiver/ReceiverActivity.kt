package com.hplay.receiver

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hplay.updater.UpdateFlow
import com.hplay.updater.Updater
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Receptor HPlay para Android TV / Fire TV:
 *  1. Media Renderer DLNA: aparece en el botón «enviar a la tele» de las apps de vídeo y reproduce la URL que mandan.
 *  2. Receptor HPlay (POST /open): la app HPlay del móvil le abre el duplicado de pantalla sin tocar el mando.
 */
class ReceiverActivity : Activity(), Fcast.Player {
    private lateinit var web: WebView
    private lateinit var renderer: Renderer
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = true
    private lateinit var dlna: DlnaDevice
    private var onPlayer = false
    private var pendingJs: String? = null
    private lateinit var fcast: Fcast
    // Cabeceras que pide el emisor FCast (Referer, Cookie…) para el host del vídeo: se añaden en shouldInterceptRequest.
    @Volatile private var mediaHeaders: Map<String, String> = emptyMap()
    @Volatile private var mediaHost: String? = null
    @Volatile private var last = Triple(0.0, 0.0, "STOPPED")

    private val uuid by lazy {
        getSharedPreferences("r", MODE_PRIVATE).run {
            getString("uuid", null) ?: UUID.randomUUID().toString().also { edit().putString("uuid", it).apply() }
        }
    }
    private val name by lazy {
        val dev = runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        "HPlay · " + (dev?.takeIf { it.isNotBlank() } ?: Build.MODEL)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderer = Renderer(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false // sonido sin tener que pulsar OK
            settings.allowFileAccess = true
            // El reproductor es un fichero local: así hls.js puede leer listas de CDN que no permiten CORS.
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    onPlayer = url.startsWith(PLAYER)
                    if (!onPlayer) return
                    js("hplay.setInfo(${JSONObject.quote(name)}, ${JSONObject.quote(DlnaDevice.localIps().joinToString(" · "))})")
                    pendingJs?.let { js(it) }
                    pendingJs = null
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val hs = mediaHeaders
                    if (hs.isEmpty() || request.url.host != mediaHost || request.method != "GET") return null
                    return runCatching { fetchWithHeaders(request, hs) }.getOrNull()
                }

                // Solo el reproductor local y receptores de la red local.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !allowed(request.url.toString())
            }
            addJavascriptInterface(object {
                @JavascriptInterface
                fun status(s: String) {
                    val p = s.split('|')
                    if (p.size >= 5) last = Triple(p[0].toDoubleOrNull() ?: 0.0, p[1].toDoubleOrNull() ?: 0.0, p[2])
                    if (p.size >= 5) renderer.update(p[0].toDoubleOrNull() ?: 0.0, p[1].toDoubleOrNull() ?: 0.0, p[2], p[3].toIntOrNull() ?: 100, p[4] == "1")
                }
            }, "HPlayBridge")
            setBackgroundColor(0xFF000000.toInt())
        }
        setContentView(web)
        web.loadUrl(PLAYER)
        multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createMulticastLock("hplay-receiver").apply { setReferenceCounted(false); acquire() }
        dlna = DlnaDevice(PORT, uuid, name, "HPlay Receiver", renderer, SERVER, listOf(Upnp.HPLAY), ::extraRoute).apply { start() }
        fcast = Fcast(this, this, ::isLan).apply { start(name) }
    }

    private fun allowed(url: String): Boolean {
        if (url.startsWith(PLAYER)) return true
        val u = runCatching { URL(url) }.getOrNull() ?: return false
        return u.protocol == "http" && runCatching { isLan(InetAddress.getByName(u.host)) }.getOrDefault(false)
    }

    /** Abre la app oficial (YouTube, Netflix…) en el contenido: primero los paquetes preferidos, luego lo que elija el sistema. */
    private fun launchApp(link: Uri, packages: List<String>): Boolean {
        val base = Intent(Intent.ACTION_VIEW, link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (p in packages + listOf<String?>(null)) {
            if (runCatching { startActivity(Intent(base).setPackage(p)) }.isSuccess) return true
        }
        return false
    }

    /** Rutas propias del receptor HPlay (además del DLNA): /open (espejo del móvil) y /launch (apps oficiales). */
    private fun extraRoute(req: DlnaDevice.Req): DlnaDevice.Reply? = when (req.path) {
        "/open" -> {
            val url = String(req.body, Charsets.UTF_8).trim()
            // Solo receptores HPlay de la red local: nunca una web cualquiera de internet.
            if (req.method == "POST" && url.startsWith("http://") && allowed(url)) {
                runOnUiThread { web.loadUrl(url) }
                DlnaDevice.Reply(200, "text/plain", "ok")
            } else DlnaDevice.Reply(403, "text/plain", "forbidden")
        }
        "/launch" -> {
            // Cuerpo: 1.ª línea el enlace (https o spotify:), las siguientes los paquetes preferidos.
            val lines = String(req.body, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val link = lines.firstOrNull()
            if (req.method == "POST" && link?.let { Uri.parse(it).scheme } in setOf("https", "http", "spotify")) {
                val ok = launchApp(Uri.parse(link), lines.drop(1))
                DlnaDevice.Reply(if (ok) 200 else 404, "text/plain", if (ok) "ok" else "no app")
            } else DlnaDevice.Reply(403, "text/plain", "forbidden")
        }
        else -> null
    }

    private fun js(script: String) = web.evaluateJavascript(script, null)

    /** Si se está mostrando el duplicado de pantalla, primero se vuelve al reproductor. */
    private fun onPlayerJs(script: String) = runOnUiThread {
        if (onPlayer) js(script) else { pendingJs = script; web.loadUrl(PLAYER) }
    }

    // ---- Renderer.Player: órdenes que llegan por DLNA ----

    override fun loadMedia(url: String, mime: String, title: String) = loadMedia(url, mime, title, emptyMap())

    override fun loadMedia(url: String, mime: String, title: String, headers: Map<String, String>) {
        mediaHeaders = headers
        mediaHost = Uri.parse(url).host
        onPlayerJs("hplay.load(${JSONObject.quote(url)}, ${JSONObject.quote(mime)}, ${JSONObject.quote(title)})")
    }

    override fun status() = last

    /** Pide el recurso con las cabeceras del emisor (el WebView no deja ponerlas desde JS). */
    private fun fetchWithHeaders(req: WebResourceRequest, extra: Map<String, String>): WebResourceResponse {
        val c = URL(req.url.toString()).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        (req.requestHeaders + extra).forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val type = c.contentType ?: "application/octet-stream"
        val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(", ") } + ("Access-Control-Allow-Origin" to "*")
        val body = if (code >= 400) c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0)) else c.inputStream
        return WebResourceResponse(type.substringBefore(';').trim(), null, code, c.responseMessage?.ifBlank { null } ?: "OK", headers, body)
    }

    override fun play() = runOnUiThread { js("hplay.play()") }
    override fun pause() = runOnUiThread { js("hplay.pause()") }
    override fun stop() = runOnUiThread { js("hplay.stop()") }
    override fun seek(sec: Double) = runOnUiThread { js("hplay.seek($sec)") }
    override fun setVolume(v: Int) = runOnUiThread { js("hplay.volume($v)") }
    override fun setMute(m: Boolean) = runOnUiThread { js("hplay.mute($m)") }

    override fun onResume() {
        super.onResume()
        // Tele: se avisa al abrir. La versión obligatoria no deja seguir sin actualizar.
        UpdateFlow.check(this) { state, m ->
            if (state != Updater.State.LATEST && m != null) UpdateFlow.show(this, "HPlay-Receptor-TV.apk", m, forced = state == Updater.State.FORCED)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!onPlayer) { web.loadUrl(PLAYER); return true } // salir del duplicado
            js("hplay.stop()")
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            js("(function(v){ if (v.paused) v.play(); else v.pause(); })(document.getElementById('v'))")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        running = false
        dlna.stop()
        fcast.stop()
        multicastLock?.let { runCatching { it.release() } }
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val PORT = 8766
        private const val PLAYER = "file:///android_asset/player.html"
        private val SERVER = "Android/${Build.VERSION.RELEASE} UPnP/1.0 HPlay/1.0"

        fun isLan(a: InetAddress) = DlnaDevice.isLan(a)
    }
}
