package com.hplay.app.capture

import com.hplay.app.discovery.ProfileCache

data class EncoderConfig(val width: Int, val height: Int, val bitrate: Int, val fps: Int, val highProfile: Boolean)

/**
 * Elige resolución, bitrate y perfil H.264 según el destino.
 * Supuesto: 720p (lado largo 1280) por defecto; 1080p solo si el receptor ya dijo que su pantalla lo es.
 * Baseline por defecto: lo decodifica cualquier tele y no usa fotogramas B (menos latencia).
 */
object EncoderProfile {
    fun choose(screenW: Int, screenH: Int, target: ProfileCache.Profile?, maxSupportedLong: Int = 1920): EncoderConfig {
        val wantLong = when {
            target?.screenLong != null && target.screenLong >= 1900 -> 1920
            target?.sinks?.isNotEmpty() == true && target.screenLong == null -> 1280 // tele DLNA: 720p es seguro
            else -> 1280
        }.coerceAtMost(maxSupportedLong)
        val long = maxOf(screenW, screenH)
        val short = minOf(screenW, screenH)
        val scale = minOf(1.0, wantLong.toDouble() / long)
        val l = align16((long * scale).toInt())
        val s = align16((short * scale).toInt())
        val (w, h) = if (screenW >= screenH) l to s else s to l
        val fps = 30
        // ~0,1 bit por píxel y fotograma: 1280×576 ≈ 2,2 Mbps; 1920×864 ≈ 5 Mbps.
        val bitrate = (w.toLong() * h * fps / 10).toInt().coerceIn(1_500_000, 8_000_000)
        return EncoderConfig(w, h, bitrate, fps, highProfile = target?.high == true && target.mse == true)
    }

    private fun align16(v: Int) = maxOf(16, v / 16 * 16)
}
