package com.hplay.app.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.hplay.app.MainActivity
import com.hplay.app.R
import com.hplay.app.Session
import com.hplay.app.cast.CastRouter
import com.hplay.app.cast.BrowserRemote
import com.hplay.app.cast.DlnaRemote
import com.hplay.receiver.DlnaDevice
import com.hplay.receiver.Renderer
import com.hplay.app.cast.Remote
import com.hplay.app.hplay
import com.hplay.app.server.StreamServer
import com.hplay.app.stream.MediaHub
import com.hplay.app.stream.TsSink
import com.hplay.app.util.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Servicio en primer plano que vive mientras se emite.
 *  - Espejo: tipo mediaProjection (Android 14+: consentimiento nuevo en cada sesión, el Intent no se reutiliza).
 *  - Vídeo por URL: tipo mediaPlayback (mantiene vivo el proxy local con la pantalla apagada).
 * Un único MediaHub alimenta navegador (WebSocket), DLNA (TS/HLS) y Roku a la vez.
 */
class HPlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val hub = MediaHub()
    private var server: StreamServer? = null
    private var projection: MediaProjection? = null
    private var projCallback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var video: VideoEncoder? = null
    private var pump: FramePump? = null
    private var audio: AudioEncoder? = null
    private var ts: TsSink? = null
    private var targetIp: String? = null
    private var routeJob: Job? = null
    private var lastIp: String? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    private var relay: DlnaDevice? = null
    private var relayLock: android.net.wifi.WifiManager.MulticastLock? = null

    companion object {
        private const val ACTION_MIRROR = "mirror"
        private const val ACTION_URL = "url"
        private const val ACTION_STOP = "stop"
        private const val ACTION_RELAY = "relay"
        private const val CHANNEL = "hplay"
        private const val NOTIF_ID = 7

        /** Mando del modo actual (DLNA, Roku o navegador); null si no hay nada que controlar. */
        val remote = MutableStateFlow<Remote?>(null)

        fun mirror(ctx: Context, resultCode: Int, data: Intent, deviceIp: String?) = ctx.startForegroundService(
            Intent(ctx, HPlayService::class.java).setAction(ACTION_MIRROR)
                .putExtra("code", resultCode).putExtra("data", data).putExtra("device", deviceIp)
        )

        fun sendUrl(ctx: Context, url: String, headers: Map<String, String>, title: String, deviceIp: String?) = ctx.startForegroundService(
            Intent(ctx, HPlayService::class.java).setAction(ACTION_URL)
                .putExtra("url", url).putExtra("headers", HashMap(headers)).putExtra("title", title).putExtra("device", deviceIp)
        )

        /** Navegador de la tele como receptor: web fija + receptor DLNA en el móvil para las demás apps. */
        fun relay(ctx: Context) = ctx.startForegroundService(Intent(ctx, HPlayService::class.java).setAction(ACTION_RELAY))

        fun stop(ctx: Context) {
            if (Session.state.value.mode != Session.Mode.IDLE) {
                ctx.startService(Intent(ctx, HPlayService::class.java).setAction(ACTION_STOP))
            }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MIRROR -> {
                startFg(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                targetIp = intent.getStringExtra("device")
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
                try {
                    ensureServer()
                    stopCapture()
                    startCapture(intent.getIntExtra("code", 0), data ?: error("no consent"))
                } catch (e: Exception) {
                    Session.state.value = Session.State(errorRes = R.string.err_projection)
                    shutdown()
                }
            }
            ACTION_URL -> {
                startFg(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                stopCapture() // enviar un vídeo sustituye al espejo
                targetIp = intent.getStringExtra("device")
                ensureServer()
                @Suppress("DEPRECATION", "UNCHECKED_CAST")
                val headers = (intent.getSerializableExtra("headers") as? HashMap<String, String>).orEmpty()
                startUrl(intent.getStringExtra("url").orEmpty(), headers, intent.getStringExtra("title") ?: "HPlay")
            }
            ACTION_RELAY -> {
                startFg(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                ensureServer()
                startRelay()
                if (Session.state.value.mode == Session.Mode.IDLE || Session.state.value.mode == Session.Mode.URL) {
                    targetIp = null
                    update { it.copy(mode = Session.Mode.URL, routeRes = R.string.relay_waiting, errorRes = null, deviceName = null) }
                }
            }
            ACTION_STOP -> shutdown()
            else -> shutdown() // reinicio del sistema sin Intent: no hay consentimiento que recuperar
        }
        return START_NOT_STICKY
    }

    // ---------- Espejo ----------

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: error("projection null")
        projection = proj
        // Android 14: el callback debe registrarse antes de crear la pantalla virtual.
        val cb = object : MediaProjection.Callback() {
            // El usuario paró desde la barra del sistema o se bloqueó la pantalla (Android 14+).
            override fun onStop() { main.post { shutdown() } }
            override fun onCapturedContentResize(width: Int, height: Int) { main.post { resizeCapture(width, height) } }
        }
        projCallback = cb
        proj.registerCallback(cb, main)

        val (sw, sh) = screenSize()
        val profile = targetIp?.let { hplay.profiles.get(it) }
        val v = VideoEncoder(EncoderProfile.choose(sw, sh, profile), hub)
        video = v
        hub.requestKeyFrame = { video?.requestKeyFrame() }
        v.start()
        val p = FramePump(v.surface, v.config.width, v.config.height)
        pump = p
        display = proj.createVirtualDisplay(
            "hplay", v.config.width, v.config.height, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, p.inputSurface, null, null,
        )
        lastOrientation = resources.configuration.orientation

        val canAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        audio = if (canAudio) AudioEncoder.create(proj, hub) else null
        audio?.start()
        hub.hasAudio = audio != null

        val sink = TsSink(withAudio = audio != null)
        ts = sink
        hub.sinks.add(sink)
        server!!.ts = sink
        server!!.setState(JSONObject().put("type", "mirror").put("audio", audio != null))
        update {
            it.copy(
                mode = Session.Mode.MIRROR, audio = audio != null, routeRes = R.string.route_starting,
                errorRes = if (audio == null) R.string.err_no_audio else null,
                deviceName = hplay.discovery.get(targetIp)?.name,
            )
        }
        routeMirror()
    }

    private fun routeMirror() {
        val srv = server ?: return
        routeJob?.cancel()
        routeJob = scope.launch(Dispatchers.IO) {
            val r = CastRouter.mirror(srv, hplay.discovery.get(targetIp))
            remote.value = r.remote
            update { it.copy(routeRes = r.routeRes, errorRes = r.errorRes ?: it.errorRes) }
        }
    }

    /** Rotación o cambio de tamaño de la app compartida: nuevo codificador, misma pantalla virtual. */
    private fun resizeCapture(w: Int, h: Int) {
        val old = video ?: return
        val cfg = EncoderProfile.choose(w, h, targetIp?.let { hplay.profiles.get(it) })
        if (cfg.width == old.config.width && cfg.height == old.config.height) return
        val nv = VideoEncoder(cfg, hub)
        display?.surface = null
        pump?.release()
        old.release()
        hub.reset()
        video = nv
        nv.start()
        val np = FramePump(nv.surface, cfg.width, cfg.height)
        pump = np
        display?.resize(cfg.width, cfg.height, resources.displayMetrics.densityDpi)
        display?.surface = np.inputSurface
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (video != null && newConfig.orientation != lastOrientation) {
            lastOrientation = newConfig.orientation
            val (w, h) = screenSize()
            resizeCapture(w, h)
        }
    }

    @Suppress("DEPRECATION") // getRealMetrics: la alternativa (WindowMetrics) es API 30 y aquí no hay ventana
    private fun screenSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun stopCapture() {
        routeJob?.cancel()
        audio?.release(); audio = null
        display?.release(); display = null
        pump?.release(); pump = null
        video?.release(); video = null
        ts?.let { hub.sinks.remove(it); it.clients.forEach { c -> c.queue.close() } }
        ts = null
        server?.ts = null
        projection?.let { p ->
            projection = null
            projCallback?.let { p.unregisterCallback(it) } // si no, stop() dispararía onStop → shutdown
            runCatching { p.stop() }
        }
        hub.reset()
    }

    // ---------- Vídeo por URL ----------

    private fun startUrl(url: String, headers: Map<String, String>, title: String) {
        val srv = server ?: return
        update {
            it.copy(mode = Session.Mode.URL, routeRes = R.string.route_starting, errorRes = null, audio = true,
                deviceName = hplay.discovery.get(targetIp)?.name)
        }
        routeJob?.cancel()
        routeJob = scope.launch(Dispatchers.IO) {
            val r = CastRouter.sendUrl(srv, hplay.discovery.get(targetIp), url, headers, title)
            remote.value = r.remote
            update { it.copy(routeRes = r.routeRes, errorRes = r.errorRes) }
        }
    }

    // ---------- Puente: apps DLNA del móvil (Play Shots…) → navegador de la tele ----------

    private fun startRelay() {
        if (relay != null) return
        val srv = server ?: return
        val web = BrowserRemote(srv)
        val player = object : Renderer.Player {
            // La app manda la URL; la web de la tele la pide directamente (el móvil solo hace de mando).
            override fun loadMedia(url: String, mime: String, title: String) = main.post { targetIp = null; startUrl(url, emptyMap(), title) }.let {}
            override fun play() = web.play()
            override fun pause() = web.pause()
            override fun stop() = web.stop()
            override fun seek(sec: Double) = web.seek(sec.toLong())
            override fun setVolume(v: Int) = web.setVolume(v)
            override fun setMute(m: Boolean) = web.setVolume(if (m) 0 else 100)
        }
        val renderer = Renderer(player)
        scope.launch {
            srv.receiverStatus.collect { o ->
                o ?: return@collect
                val dur = o.optDouble("dur", 0.0).let { if (it.isNaN() || it.isInfinite()) 0.0 else it }
                renderer.update(o.optDouble("pos", 0.0), dur, if (o.optBoolean("paused", true)) "PAUSED_PLAYBACK" else "PLAYING", (o.optDouble("vol", 1.0) * 100).toInt(), false)
            }
        }
        val prefs = getSharedPreferences("server", MODE_PRIVATE)
        val uuid = prefs.getString("relay_uuid", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("relay_uuid", it).apply() }
        // Android filtra el multicast entrante salvo con este cerrojo: sin él, las apps no encuentran el puente.
        relayLock = getSystemService(android.net.wifi.WifiManager::class.java).createMulticastLock("hplay-relay").apply { setReferenceCounted(false); acquire() }
        relay = DlnaDevice(
            8767, uuid, "HPlay · " + getString(R.string.relay_name), "HPlay Relay", renderer, "Android/${Build.VERSION.RELEASE} UPnP/1.0 HPlay/1.0",
            onControl = { req, action -> com.hplay.app.detect.DlnaCapture.onControl(this, getString(R.string.relay_name), req, action) },
        ).apply { start() }
    }

    // ---------- Común ----------

    private fun ensureServer() {
        if (server != null) return
        val s = StreamServer(this, scope, hub, hplay.profiles)
        s.start()
        server = s
        lastIp = Net.localIpv4()
        update { it.copy(receiverUrl = lastIp?.let { ip -> s.receiverUrl(ip) }) }
        scope.launch { s.clientCount.collect { n -> update { it.copy(clients = n) } } }
        watchNetwork()
    }

    /** Cambio de Wi-Fi: nueva URL/QR y, si se estaba duplicando a una tele, se le vuelve a enviar. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val ip = Net.localIpv4()
                if (ip == lastIp) return
                lastIp = ip
                main.post {
                    val s = server ?: return@post
                    update { it.copy(receiverUrl = ip?.let { s.receiverUrl(it) }) }
                    if (video != null && ip != null) routeMirror()
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    private fun update(f: (Session.State) -> Session.State) {
        Session.state.value = f(Session.state.value)
        if (Session.state.value.mode != Session.Mode.IDLE) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        }
    }

    private fun shutdown() {
        val r = remote.value
        remote.value = null
        if (r is DlnaRemote) Thread { runCatching { r.stop() } }.start() // la tele deja de esperar el flujo
        stopCapture()
        relay?.stop(); relay = null
        relayLock?.let { runCatching { it.release() } }; relayLock = null
        server?.stop(); server = null
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        netCallback = null
        val err = Session.state.value.errorRes.takeIf { Session.state.value.mode == Session.Mode.IDLE }
        Session.state.value = Session.State(errorRes = err)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (server != null || projection != null) shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startFg(type: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, notification(), type)
    }

    private fun notification(): Notification {
        val s = Session.state.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HPlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val title = getString(if (s.mode == Session.Mode.URL) R.string.notif_url else R.string.notif_mirror)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hplay)
            .setContentTitle(title)
            .setContentText(s.receiverUrl ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }
}
