package com.hplay.app.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * mDNS/DNS-SD con NsdManager. Solo sirve para saber qué hay y qué anuncia
 * (Google Cast, AirPlay, RAOP, Android TV): HPlay no habla esos protocolos propietarios.
 */
class Mdns(private val nsd: NsdManager, private val scope: CoroutineScope, private val onFound: (Device) -> Unit) {
    private val types = mapOf(
        "_googlecast._tcp." to Cap.CAST,
        "_airplay._tcp." to Cap.AIRPLAY,
        "_raop._tcp." to Cap.RAOP,
        "_androidtvremote2._tcp." to Cap.ANDROID_TV,
    )
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val pending = Channel<Pair<NsdServiceInfo, Cap>>(Channel.UNLIMITED)
    private var worker: Job? = null

    fun start() {
        if (worker != null) return
        // NsdManager.resolveService solo admite una resolución a la vez: se serializan.
        worker = scope.launch { for ((info, cap) in pending) resolve(info, cap) }
        for ((type, cap) in types) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) { pending.trySend(info to cap) }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, code: Int) {}
                override fun onStopDiscoveryFailed(t: String, code: Int) {}
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners += l }
        }
    }

    fun stop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        worker?.cancel()
        worker = null
    }

    @Suppress("DEPRECATION") // registerServiceInfoCallback es API 34; minSdk 29
    private suspend fun resolve(info: NsdServiceInfo, cap: Cap) {
        val resolved = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) }
                        override fun onServiceResolved(i: NsdServiceInfo) { if (cont.isActive) cont.resume(i) }
                    })
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        } ?: return
        val ip = resolved.host?.hostAddress ?: return
        val attrs = resolved.attributes
        fun attr(k: String) = attrs[k]?.toString(Charsets.UTF_8).orEmpty()
        val name = attr("fn").ifBlank { resolved.serviceName.substringAfter('@') }
        onFound(Device(ip = ip, name = name, model = if (cap == Cap.CAST) attr("md") else attr("am").ifBlank { attr("model") }, caps = setOf(cap)))
    }
}
