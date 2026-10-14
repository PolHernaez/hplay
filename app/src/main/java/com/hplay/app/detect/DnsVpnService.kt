package com.hplay.app.detect

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.VpnService
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Monitor DNS con una VPN local que SOLO enruta la IP de un DNS falso (10.111.222.1/32): el resto del tráfico sigue
 * por la Wi-Fi/datos sin pasar por aquí (batería y velocidad intactas). Cada consulta se apunta (dominio + app, si
 * Android la identifica) y se resuelve con el DNS real del sistema por la red de verdad (`DnsResolver.rawQuery`,
 * respeta el DNS privado del sistema; no se usa ningún DNS de terceros).
 *
 * Lo que NO puede hacer (y no se intenta): ver rutas ni URLs (HTTPS/QUIC van cifrados), descifrar nada, ni ver las
 * consultas de apps con DNS propio (DoH) o con DNS privado en modo estricto. Un dominio ≠ la URL del vídeo.
 */
class DnsVpnService : VpnService() {
    @Volatile private var running = false
    private var tun: ParcelFileDescriptor? = null
    private val pool = Executors.newFixedThreadPool(4)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        val pfd = runCatching {
            Builder()
                .setSession("HPlay · DNS")
                .addAddress(TUN_IP, 32)
                .addDnsServer(DNS_IP)
                .addRoute(DNS_IP, 32)
                .addDisallowedApplication(packageName) // el navegador de HPlay ya ve sus propias URLs completas
                .setBlocking(true)
                .establish()
        }.onFailure { MediaDetector.log("DNS: VPN error ${it.message}") }.getOrNull()
        if (pfd == null) { stopSelf(); return START_NOT_STICKY }
        tun = pfd
        running = true
        MediaDetector.dnsOn.value = true
        warnPrivateDns()
        MediaDetector.log("DNS: monitor on (only DNS goes through the local VPN)")
        thread(name = "hplay-dns") { loop(pfd) }
        return START_STICKY
    }

    private fun loop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32_767)
        while (running) {
            val n = runCatching { input.read(buf) }.getOrDefault(-1)
            if (n < 0) break
            val q = DnsPacket.parse(buf, n) ?: continue
            pool.execute { handle(q, output) }
        }
        if (running) shutdown()
    }

    private fun handle(q: DnsPacket.Query, out: FileOutputStream) {
        val app = runCatching {
            AppIdentity.owner(
                this, OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(q.srcIp), q.srcPort), InetSocketAddress(InetAddress.getByAddress(q.dstIp), q.dstPort),
            )
        }.getOrNull() ?: "?"
        MediaDetector.domain(q.name, app)
        val cm = getSystemService(ConnectivityManager::class.java)
        // HPlay está fuera de la VPN: su red por defecto es la Wi-Fi/datos reales.
        val network = cm.activeNetwork
        val done = java.util.concurrent.CountDownLatch(1)
        var answer: ByteArray? = null
        val cancel = CancellationSignal()
        runCatching {
            DnsResolver.getInstance().rawQuery(network, q.dns, DnsResolver.FLAG_EMPTY, Runnable::run, cancel,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(a: ByteArray, rcode: Int) { answer = a; done.countDown() }
                    override fun onError(e: DnsResolver.DnsException) { done.countDown() }
                })
        }.onFailure { done.countDown() }
        if (!done.await(8, java.util.concurrent.TimeUnit.SECONDS)) cancel.cancel()
        val reply = DnsPacket.reply(q, answer ?: DnsPacket.servfail(q))
        synchronized(out) { runCatching { out.write(reply) } }
    }

    private fun warnPrivateDns() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return
        if (lp.privateDnsServerName != null) MediaDetector.log("DNS: private DNS in strict mode (${lp.privateDnsServerName}): apps may bypass this monitor")
    }

    private fun shutdown() {
        if (!running && tun == null) return
        running = false
        runCatching { tun?.close() }
        tun = null
        MediaDetector.dnsOn.value = false
        MediaDetector.log("DNS: monitor off")
    }

    override fun onRevoke() { shutdown(); stopSelf() } // otra VPN ocupó el sitio o el usuario la cortó

    override fun onDestroy() { shutdown(); pool.shutdownNow(); super.onDestroy() }

    companion object {
        private const val ACTION_STOP = "stop"
        private const val TUN_IP = "10.111.222.2"
        const val DNS_IP = "10.111.222.1"

        /** El permiso de VPN (VpnService.prepare) ya debe estar concedido. */
        fun start(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
    }
}
