package com.hplay.receiver

import android.annotation.SuppressLint
import android.app.Activity
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
class ReceiverActivity : Activity(), Renderer.Player {
    private lateinit var web: WebView
    private lateinit var renderer: Renderer
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = true
    private var http: ServerSocket? = null
    private var ssdp: MulticastSocket? = null
    private var onPlayer = false
    private var pendingJs: String? = null

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
                    js("hplay.setInfo(${JSONObject.quote(name)}, ${JSONObject.quote(localIps().joinToString(" · "))})")
                    pendingJs?.let { js(it) }
                    pendingJs = null
                }

                // Solo el reproductor local y receptores de la red local.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !allowed(request.url.toString())
            }
            addJavascriptInterface(object {
                @JavascriptInterface
                fun status(s: String) {
                    val p = s.split('|')
                    if (p.size >= 5) renderer.update(p[0].toDoubleOrNull() ?: 0.0, p[1].toDoubleOrNull() ?: 0.0, p[2], p[3].toIntOrNull() ?: 100, p[4] == "1")
                }
            }, "HPlayBridge")
            setBackgroundColor(0xFF000000.toInt())
        }
        setContentView(web)
        web.loadUrl(PLAYER)
        multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createMulticastLock("hplay-receiver").apply { setReferenceCounted(false); acquire() }
        thread(name = "hplay-http") { httpLoop() }
        thread(name = "hplay-ssdp") { ssdpLoop() }
        thread(name = "hplay-alive") {
            // Anuncio al arrancar y cada 60 s: algunos controladores solo escuchan NOTIFY.
            while (running) {
                runCatching { announce("ssdp:alive") }
                Thread.sleep(60_000)
            }
        }
    }

    private fun allowed(url: String): Boolean {
        if (url.startsWith(PLAYER)) return true
        val u = runCatching { URL(url) }.getOrNull() ?: return false
        return u.protocol == "http" && runCatching { isLan(InetAddress.getByName(u.host)) }.getOrDefault(false)
    }

    private fun js(script: String) = web.evaluateJavascript(script, null)

    /** Si se está mostrando el duplicado de pantalla, primero se vuelve al reproductor. */
    private fun onPlayerJs(script: String) = runOnUiThread {
        if (onPlayer) js(script) else { pendingJs = script; web.loadUrl(PLAYER) }
    }

    // ---- Renderer.Player: órdenes que llegan por DLNA ----

    override fun loadMedia(url: String, mime: String, title: String) =
        onPlayerJs("hplay.load(${JSONObject.quote(url)}, ${JSONObject.quote(mime)}, ${JSONObject.quote(title)})")

    override fun play() = runOnUiThread { js("hplay.play()") }
    override fun pause() = runOnUiThread { js("hplay.pause()") }
    override fun stop() = runOnUiThread { js("hplay.stop()") }
    override fun seek(sec: Double) = runOnUiThread { js("hplay.seek($sec)") }
    override fun setVolume(v: Int) = runOnUiThread { js("hplay.volume($v)") }
    override fun setMute(m: Boolean) = runOnUiThread { js("hplay.mute($m)") }

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
        runCatching { announce("ssdp:byebye") }
        runCatching { http?.close() }
        runCatching { ssdp?.close() }
        multicastLock?.let { runCatching { it.release() } }
        web.destroy()
        super.onDestroy()
    }

    // ---- HTTP ----

    private fun httpLoop() {
        val ss = runCatching { ServerSocket(PORT) }.getOrNull() ?: return
        http = ss
        while (running) {
            val s = runCatching { ss.accept() }.getOrNull() ?: break
            thread { runCatching { s.use { serve(it) } } }
        }
    }

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    /** Cabeceras byte a byte y cuerpo exacto (los metadatos DIDL traen acentos: Content-Length va en bytes). */
    private fun read(input: InputStream): Req? {
        val head = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            head.write(b)
            val a = head.toByteArray()
            val n = a.size
            if (n >= 4 && a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() && a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()) break
            if (n > 16_384) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val first = lines[0].split(' ')
        if (first.size < 2) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        val len = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..(1 shl 20) } ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val k = input.read(body, read, len - read); if (k < 0) break; read += k }
        return Req(first[0].uppercase(), first[1].substringBefore('?'), headers, body)
    }

    private fun serve(s: Socket) {
        if (!isLan(s.inetAddress)) return
        s.soTimeout = 8_000
        val req = read(s.getInputStream()) ?: return
        val out = s.getOutputStream()
        fun reply(code: Int, type: String?, body: String, extra: String = "") {
            val b = body.toByteArray()
            val reason = when (code) { 200 -> "OK"; 412 -> "Precondition Failed"; 403 -> "Forbidden"; 500 -> "Internal Server Error"; else -> "Not Found" }
            out.write(("HTTP/1.1 $code $reason\r\n" + (type?.let { "Content-Type: $it\r\n" } ?: "") + "Content-Length: ${b.size}\r\n" +
                "Server: $SERVER\r\nEXT:\r\nConnection: close\r\n$extra\r\n").toByteArray())
            if (req.method != "HEAD") out.write(b)
            out.flush()
        }
        val xml = "text/xml; charset=\"utf-8\""
        val last = req.path.substringAfterLast('/')
        when (req.path) {
            "/description.xml" -> reply(200, xml, Upnp.description(uuid, name))
            "/avt.xml" -> reply(200, xml, Upnp.AVT_SCPD)
            "/rc.xml" -> reply(200, xml, Upnp.RC_SCPD)
            "/cm.xml" -> reply(200, xml, Upnp.CM_SCPD)
            "/upnp/control/avt", "/upnp/control/rc", "/upnp/control/cm" -> {
                val action = req.headers["soapaction"].orEmpty().trim('"', ' ').substringAfterLast('#')
                val (code, body) = renderer.soap(last, action, String(req.body, Charsets.UTF_8))
                reply(code, xml, body)
            }
            "/upnp/event/avt", "/upnp/event/rc", "/upnp/event/cm" -> {
                val h = renderer.subscribe(req.method, last, req.headers)
                if (h == null) reply(412, null, "") else reply(200, null, "", h)
            }
            "/open" -> {
                val url = String(req.body, Charsets.UTF_8).trim()
                // Solo receptores HPlay de la red local: nunca una web cualquiera de internet.
                if (req.method == "POST" && url.startsWith("http://") && allowed(url)) {
                    runOnUiThread { web.loadUrl(url) }
                    reply(200, "text/plain", "ok")
                } else reply(403, "text/plain", "forbidden")
            }
            else -> reply(404, "text/plain", "not found")
        }
    }

    // ---- SSDP ----

    private fun targets() = listOf("upnp:rootdevice", "uuid:$uuid", Upnp.RENDERER, Upnp.AVT, Upnp.RC, Upnp.CM, Upnp.HPLAY)
    private fun usn(nt: String) = if (nt.startsWith("uuid:")) nt else "uuid:$uuid::$nt"

    private fun ssdpLoop() {
        val sock = runCatching {
            MulticastSocket(1900).apply { @Suppress("DEPRECATION") joinGroup(InetAddress.getByName(GROUP)) }
        }.getOrNull() ?: return
        ssdp = sock
        val buf = ByteArray(2048)
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            if (runCatching { sock.receive(p) }.isFailure) break
            val msg = String(p.data, 0, p.length)
            if (!msg.startsWith("M-SEARCH", true)) continue
            val st = msg.lines().firstOrNull { it.startsWith("ST:", true) }?.substringAfter(':')?.trim() ?: continue
            val types = if (st == "ssdp:all") targets() else targets().filter { it.equals(st, true) }
            if (types.isEmpty()) continue
            val ip = localIpFor(p.address) ?: continue
            for (t in types) {
                val b = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: ${date.format(Date())}\r\nEXT:\r\n" +
                    "LOCATION: http://$ip:$PORT/description.xml\r\nSERVER: $SERVER\r\nST: $t\r\nUSN: ${usn(t)}\r\nContent-Length: 0\r\n\r\n").toByteArray()
                runCatching { sock.send(DatagramPacket(b, b.size, p.address, p.port)) }
            }
        }
    }

    private fun announce(nts: String) {
        val ip = localIps().firstOrNull() ?: return
        DatagramSocket().use { s ->
            for (t in targets()) {
                val b = ("NOTIFY * HTTP/1.1\r\nHOST: $GROUP:1900\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://$ip:$PORT/description.xml\r\n" +
                    "NT: $t\r\nNTS: $nts\r\nSERVER: $SERVER\r\nUSN: ${usn(t)}\r\n\r\n").toByteArray()
                s.send(DatagramPacket(b, b.size, InetAddress.getByName(GROUP), 1900))
            }
        }
    }

    /** IP propia por la que se llega a quien pregunta (evita interfaces VPN o Ethernet sin uso). */
    private fun localIpFor(remote: InetAddress): String? = runCatching {
        DatagramSocket().use { it.connect(InetSocketAddress(remote, 9)); it.localAddress.hostAddress }
    }.getOrNull()?.takeIf { it != "0.0.0.0" } ?: localIps().firstOrNull()

    private fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    companion object {
        const val PORT = 8766
        private const val GROUP = "239.255.255.250"
        private const val PLAYER = "file:///android_asset/player.html"
        private val SERVER = "Android/${Build.VERSION.RELEASE} UPnP/1.0 HPlay/1.0"

        fun isLan(a: InetAddress) = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
    }
}
