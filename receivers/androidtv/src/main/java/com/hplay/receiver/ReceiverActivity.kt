package com.hplay.receiver

import android.annotation.SuppressLint
import android.app.Activity
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Receptor HPlay para Android TV / Fire TV:
 *  - se anuncia por SSDP como urn:hplay-org:device:Receiver:1;
 *  - sirve /description.xml y acepta POST /open (solo red local) con la URL del receptor web del móvil;
 *  - la abre en un WebView a pantalla completa con sonido sin pulsar nada.
 */
class ReceiverActivity : Activity() {
    private lateinit var web: WebView
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = true
    private var http: ServerSocket? = null
    private var ssdp: MulticastSocket? = null
    private val uuid by lazy {
        getSharedPreferences("r", MODE_PRIVATE).run {
            getString("uuid", null) ?: UUID.randomUUID().toString().also { edit().putString("uuid", it).apply() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false // sonido sin tener que pulsar OK
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(0xFF000000.toInt())
        }
        setContentView(web)
        showIdle()
        multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createMulticastLock("hplay-receiver").apply { setReferenceCounted(false); acquire() }
        thread(name = "hplay-http") { httpLoop() }
        thread(name = "hplay-ssdp") { ssdpLoop() }
    }

    private fun showIdle() {
        val ip = localIp() ?: "?"
        val html = """<html><body style="margin:0;background:#040b26;color:#e8ecff;font-family:sans-serif;display:flex;
            align-items:center;justify-content:center;height:100vh;text-align:center"><div><h1 style="font-size:56px">HPlay</h1>
            <p style="font-size:26px;max-width:900px">${getString(R.string.idle)}</p><p style="font-size:22px;opacity:.7">$ip</p></div></body></html>"""
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.url?.startsWith("http") == true) {
            showIdle()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        running = false
        runCatching { http?.close() }
        runCatching { ssdp?.close() }
        multicastLock?.let { runCatching { it.release() } }
        web.destroy()
        super.onDestroy()
    }

    // ---- HTTP: description.xml + /open ----

    private fun httpLoop() {
        val ss = runCatching { ServerSocket(PORT) }.getOrNull() ?: return
        http = ss
        while (running) {
            val s = runCatching { ss.accept() }.getOrNull() ?: break
            thread { runCatching { s.use { handle(it) } } }
        }
    }

    private fun handle(s: Socket) {
        if (!isLan(s.inetAddress)) return
        s.soTimeout = 5_000
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val first = reader.readLine() ?: return
        var length = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("content-length:", true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val out = s.getOutputStream()
        fun reply(code: Int, type: String, body: String) {
            val b = body.toByteArray()
            out.write("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(b)
            out.flush()
        }
        when {
            first.startsWith("GET /description.xml") -> reply(200, "text/xml; charset=utf-8", description())
            first.startsWith("POST /open") && length in 1..2048 -> {
                val buf = CharArray(length)
                var read = 0
                while (read < length) { val n = reader.read(buf, read, length - read); if (n < 0) break; read += n }
                val url = String(buf, 0, read).trim()
                // Solo receptores HPlay de la red local: nunca una web cualquiera de internet.
                val ok = runCatching { URL(url).let { it.protocol == "http" && isLan(InetAddress.getByName(it.host)) } }.getOrDefault(false)
                if (ok) { runOnUiThread { web.loadUrl(url) }; reply(200, "text/plain", "ok") } else reply(403, "text/plain", "forbidden")
            }
            else -> reply(404, "text/plain", "not found")
        }
    }

    private fun description() = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device>
<deviceType>$DEVICE_TYPE</deviceType><friendlyName>HPlay · ${android.os.Build.MODEL}</friendlyName>
<manufacturer>${android.os.Build.MANUFACTURER}</manufacturer><modelName>HPlay Receiver</modelName><UDN>uuid:$uuid</UDN>
</device></root>"""

    // ---- SSDP: responde a M-SEARCH ----

    private fun ssdpLoop() {
        val sock = runCatching {
            MulticastSocket(1900).apply {
                @Suppress("DEPRECATION") joinGroup(InetAddress.getByName("239.255.255.250"))
            }
        }.getOrNull() ?: return
        ssdp = sock
        notifyAlive(sock)
        val buf = ByteArray(2048)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            if (runCatching { sock.receive(p) }.isFailure) break
            val msg = String(p.data, 0, p.length)
            if (!msg.startsWith("M-SEARCH")) continue
            val st = msg.lines().firstOrNull { it.startsWith("ST:", true) }?.substringAfter(':')?.trim() ?: continue
            if (st != DEVICE_TYPE && st != "ssdp:all" && st != "upnp:rootdevice") continue
            val ip = localIp() ?: continue
            val resp = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: http://$ip:$PORT/description.xml\r\n" +
                "SERVER: Android/${android.os.Build.VERSION.RELEASE} UPnP/1.1 HPlay/1.0\r\nST: $st\r\nUSN: uuid:$uuid::$DEVICE_TYPE\r\n\r\n"
            val b = resp.toByteArray()
            runCatching { sock.send(DatagramPacket(b, b.size, p.address, p.port)) }
        }
    }

    private fun notifyAlive(sock: MulticastSocket) {
        val ip = localIp() ?: return
        val b = ("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://$ip:$PORT/description.xml\r\nNT: $DEVICE_TYPE\r\nNTS: ssdp:alive\r\nUSN: uuid:$uuid::$DEVICE_TYPE\r\n\r\n").toByteArray()
        runCatching { sock.send(DatagramPacket(b, b.size, InetAddress.getByName("239.255.255.250"), 1900)) }
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun isLan(a: InetAddress) = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress

    companion object {
        const val PORT = 8766
        const val DEVICE_TYPE = "urn:hplay-org:device:Receiver:1"
    }
}
