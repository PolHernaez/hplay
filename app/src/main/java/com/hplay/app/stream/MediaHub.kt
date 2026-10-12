package com.hplay.app.stream

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Punto único entre los codificadores y los destinos (WebSocket, TS en vivo, HLS).
 * Un solo codificador alimenta a todos: la tele DLNA y el navegador ven lo mismo.
 */
class MediaHub {
    /** [data] en Annex-B; los fotogramas clave llevan SPS/PPS delante. [durMs] sin deriva (ver [durationMs]). */
    class VideoFrame(val data: ByteArray, val ptsUs: Long, val key: Boolean, val durMs: Int)
    class AudioFrame(val adts: ByteArray, val ptsUs: Long, val durMs: Int)

    interface Sink {
        fun onVideo(f: VideoFrame)
        fun onAudio(f: AudioFrame)
        /** El codificador se ha recreado (rotación): nuevo SPS/PPS. */
        fun onReset() {}
    }

    val sinks = CopyOnWriteArrayList<Sink>()

    @Volatile var csd: ByteArray? = null
    @Volatile var hasAudio = false
    @Volatile var requestKeyFrame: () -> Unit = {}

    private var lastVideoPts = -1L
    private var lastAudioPts = -1L

    fun video(annexB: ByteArray, ptsUs: Long, key: Boolean) {
        val cfg = csd
        val data = if (key && cfg != null && firstNalType(annexB) != 7) cfg + annexB else annexB
        val dur = durationMs(lastVideoPts, ptsUs, 33)
        lastVideoPts = ptsUs
        val f = VideoFrame(data, ptsUs, key, dur)
        sinks.forEach { it.onVideo(f) }
    }

    fun audio(raw: ByteArray, ptsUs: Long) {
        val dur = durationMs(lastAudioPts, ptsUs, 21)
        lastAudioPts = ptsUs
        val f = AudioFrame(Adts.header(raw.size) + raw, ptsUs, dur)
        sinks.forEach { it.onAudio(f) }
    }

    fun reset() {
        csd = null
        lastVideoPts = -1
        sinks.forEach { it.onReset() }
    }

    companion object {
        /** Diferencia de tiempos absolutos truncados a ms: las duraciones suman exacto, sin deriva acumulada. */
        fun durationMs(prevUs: Long, ptsUs: Long, default: Int): Int =
            if (prevUs < 0) default else (ptsUs / 1000 - prevUs / 1000).toInt().coerceIn(1, 1000)

        fun firstNalType(b: ByteArray): Int {
            var i = 0
            while (i + 3 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                    if (b[i + 2].toInt() == 1) return b[i + 3].toInt() and 0x1F
                    if (b[i + 2].toInt() == 0 && i + 4 < b.size && b[i + 3].toInt() == 1) return b[i + 4].toInt() and 0x1F
                }
                i++
            }
            return -1
        }
    }
}

object Adts {
    /** Cabecera ADTS para AAC-LC 48 kHz estéreo (lo que sale de [com.hplay.app.capture.AudioEncoder]). */
    fun header(payloadLen: Int, profile: Int = 2, freqIdx: Int = 3, channels: Int = 2): ByteArray {
        val len = payloadLen + 7
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            (((profile - 1) shl 6) or (freqIdx shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
