package com.hplay.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import com.hplay.app.cast.DlnaRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Lista única de aparatos: SSDP (DLNA, Roku, receptor HPlay) + mDNS + IP manual, fusionados por IP. */
class DiscoveryRepository(private val ctx: Context, private val scope: CoroutineScope, private val profiles: ProfileCache) {
    private val byIp = ConcurrentHashMap<String, Device>()
    val devices = MutableStateFlow<List<Device>>(emptyList())
    val scanning = MutableStateFlow(false)
    private val prefs = ctx.getSharedPreferences("manual", Context.MODE_PRIVATE)
    private val mdns = Mdns(ctx.getSystemService(NsdManager::class.java), scope, ::upsert)
    private var started = false

    fun start() {
        if (started) return
        started = true
        mdns.start()
        prefs.getStringSet("ips", emptySet())!!.forEach { addManual(it, save = false) }
        refresh()
    }

    fun stop() {
        mdns.stop()
        started = false
    }

    fun get(ip: String?): Device? = ip?.let { byIp[it] }

    fun refresh() {
        if (scanning.value) return
        scanning.value = true
        scope.launch(Dispatchers.IO) {
            // Android filtra el multicast entrante para ahorrar batería salvo con este cerrojo.
            val lock = ctx.getSystemService(WifiManager::class.java).createMulticastLock("hplay-ssdp").apply { setReferenceCounted(false) }
            try {
                lock.acquire()
                Ssdp.search().map { hit -> launch { describe(hit) } }.forEach { it.join() }
            } catch (_: Exception) {
            } finally {
                runCatching { lock.release() }
                scanning.value = false
            }
        }
    }

    fun addManual(ip: String, save: Boolean = true) {
        val clean = ip.trim().removePrefix("http://").substringBefore('/').substringBefore(':')
        if (clean.isBlank()) return
        if (save) prefs.edit().putStringSet("ips", prefs.getStringSet("ips", emptySet())!! + clean).apply()
        upsert(Device(ip = clean, name = clean, caps = setOf(Cap.MANUAL)))
        scope.launch(Dispatchers.IO) {
            // M-SEARCH unicast: funciona aunque el router bloquee el multicast.
            runCatching { Ssdp.search(timeoutMs = 1500, unicastTo = clean).forEach { describe(it) } }
            runCatching { describe(Ssdp.Hit(clean, "http://$clean:8766/description.xml", Ssdp.HPLAY_RECEIVER, "")) }
        }
    }

    fun removeManual(ip: String) {
        prefs.edit().putStringSet("ips", prefs.getStringSet("ips", emptySet())!! - ip).apply()
        byIp.remove(ip)
        publish()
    }

    private fun describe(hit: Ssdp.Hit) {
        val xml = runCatching { fetch(hit.location) }.getOrNull() ?: return
        val d = runCatching { UpnpDescription.parse(xml, hit.location) }.getOrNull() ?: return
        val caps = mutableSetOf<Cap>()
        if (d.avTransport != null) caps += Cap.DLNA
        if (hit.st == "roku:ecp" || d.deviceType.contains("roku", true) || d.manufacturer.contains("roku", true)) caps += Cap.ROKU
        val isHplay = d.deviceType.startsWith(Ssdp.HPLAY_RECEIVER.substringBeforeLast(':')) || d.model == "HPlay Receiver"
        if (isHplay) caps += Cap.HPLAY
        // Receptores HPlay 1.5+: FCast en 46899 (se comprueba porque el mDNS no siempre cruza la red).
        if (isHplay && runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(hit.ip, 46899), 600) } }.isSuccess) caps += Cap.FCAST
        if (caps.isEmpty()) return // routers, NAS, etc.: no reproducen nada
        val sinks = d.connectionManager?.let { cm -> runCatching { DlnaRemote.protocolInfo(cm) }.getOrNull() }
            ?.also { profiles.updateSinks(hit.ip, it) }
            ?: profiles.get(hit.ip)?.sinks.orEmpty()
        upsert(
            Device(
                ip = hit.ip,
                name = d.friendlyName.ifBlank { hit.ip },
                manufacturer = d.manufacturer,
                model = d.model,
                caps = caps,
                avTransportUrl = d.avTransport,
                renderingControlUrl = d.renderingControl,
                connectionManagerUrl = d.connectionManager,
                hplayUrl = if (isHplay) URL(URL(hit.location), "/").toString() else null,
                sinks = sinks,
            )
        )
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2_500
        c.readTimeout = 3_000
        return try { c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { c.disconnect() }
    }

    private fun upsert(d: Device) {
        byIp.merge(d.ip, d) { old, new -> old.merge(new) }
        publish()
    }

    private fun publish() {
        devices.value = byIp.values.sortedWith(compareBy({ rank(it) }, { it.name.lowercase() }))
    }

    /** Primero lo que mejor funciona sin tocar la tele. */
    private fun rank(d: Device) = when {
        Cap.HPLAY in d.caps -> 0
        Cap.DLNA in d.caps -> 1
        Cap.ROKU in d.caps -> 2
        Cap.FCAST in d.caps || Cap.KODI in d.caps || Cap.CAST in d.caps -> 2
        else -> 3
    }
}
