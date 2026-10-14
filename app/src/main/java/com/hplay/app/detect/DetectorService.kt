package com.hplay.app.detect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.hplay.app.MainActivity
import com.hplay.app.R
import com.hplay.receiver.DlnaDevice
import com.hplay.receiver.Renderer
import kotlin.concurrent.thread

/**
 * Receptor DLNA «HPlay · Detector»: aparece en el botón de enviar de las apps DLNA del móvil y de la red, pero no
 * reproduce nada; solo guarda lo que llega (URL, metadatos, emisor) para verlo, copiarlo o reenviarlo desde HPlay.
 * Servicio en primer plano «connectedDevice»: sin él, Android congela el proceso y las apps no lo encuentran.
 */
class DetectorService : Service() {
    private var device: DlnaDevice? = null
    private var lock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll(); return START_NOT_STICKY }
        if (device != null) return START_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.detector_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, DetectorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hplay)
            .setContentTitle(getString(R.string.detector_notif))
            .setContentText("HPlay · Detector")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, n)

        lateinit var renderer: Renderer
        // No reproduce: solo responde como un reproductor para que el emisor no dé error y siga mandando acciones.
        val player = object : Renderer.Player {
            override fun loadMedia(url: String, mime: String, title: String) {}
            override fun play() { thread { renderer.update(0.0, 0.0, "PLAYING", 100, false) } }
            override fun pause() { thread { renderer.update(0.0, 0.0, "PAUSED_PLAYBACK", 100, false) } }
            override fun stop() { thread { renderer.update(0.0, 0.0, "STOPPED", 100, false) } }
            override fun seek(sec: Double) {}
            override fun setVolume(v: Int) {}
            override fun setMute(m: Boolean) {}
        }
        renderer = Renderer(player)
        val prefs = getSharedPreferences("server", MODE_PRIVATE)
        val uuid = prefs.getString("detector_uuid", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("detector_uuid", it).apply() }
        lock = getSystemService(WifiManager::class.java).createMulticastLock("hplay-detector").apply { setReferenceCounted(false); acquire() }
        device = DlnaDevice(
            PORT, uuid, "HPlay · Detector", "HPlay Detector", renderer, "Android/${Build.VERSION.RELEASE} UPnP/1.0 HPlay/1.0",
            onControl = { req, action -> DlnaCapture.onControl(this, "Detector", req, action) },
        ).apply { start() }
        MediaDetector.dlnaOn.value = true
        MediaDetector.log("DLNA: «HPlay · Detector» listening on port $PORT")
        return START_STICKY
    }

    private fun stopAll() {
        device?.stop(); device = null
        lock?.let { runCatching { it.release() } }; lock = null
        if (MediaDetector.dlnaOn.value) MediaDetector.log("DLNA: detector off")
        MediaDetector.dlnaOn.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (device != null) stopAll()
        super.onDestroy()
    }

    companion object {
        const val PORT = 8768
        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "hplay-detector"
        private const val NOTIF_ID = 8

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, DetectorService::class.java))
        fun stop(ctx: Context) {
            if (MediaDetector.dlnaOn.value) ctx.startService(Intent(ctx, DetectorService::class.java).setAction(ACTION_STOP))
        }
    }
}
