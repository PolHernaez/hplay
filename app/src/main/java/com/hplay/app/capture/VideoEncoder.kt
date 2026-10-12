package com.hplay.app.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import com.hplay.app.stream.MediaHub

/**
 * H.264 por hardware alimentado por Surface (sin copias por CPU).
 * Decisiones de latencia:
 *  - GOP de 1 s (I_FRAME_INTERVAL=1): un receptor nuevo o un segmento HLS empieza en ≤1 s.
 *  - Sin fotogramas B (MAX_B_FRAMES=0): el orden de decodificación = orden de presentación.
 *  - REPEAT_PREVIOUS_FRAME_AFTER=100 ms: con la pantalla quieta sigue saliendo vídeo (el receptor no se queda sin datos).
 *  - CBR si existe: tasa estable, sin picos que llenen el Wi-Fi.
 *  - Fotograma clave bajo demanda cuando entra un cliente o uno se atasca.
 */
class VideoEncoder(val config: EncoderConfig, private val hub: MediaHub) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val surface: Surface
    @Volatile private var running = true
    private var thread: Thread? = null
    private var lastKeyRequest = 0L

    init {
        val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        fun format(withProfile: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // tiempo real
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            if (withProfile) {
                val p = if (config.highProfile) MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                else MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
                if (caps.profileLevels.any { it.profile == p }) setInteger(MediaFormat.KEY_PROFILE, p)
                else if (!config.highProfile) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
        }
        // Algunos codificadores rechazan claves de perfil: se reintenta sin ellas.
        try {
            codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            codec.reset()
            codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        surface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
        thread = Thread(::drain, "hplay-video").apply { start() }
    }

    fun requestKeyFrame() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyRequest < 300 || !running) return
        lastKeyRequest = now
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val sps = f.getByteBuffer("csd-0")
                        val pps = f.getByteBuffer("csd-1")
                        if (sps != null && pps != null) hub.csd = bytes(sps) + bytes(pps)
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            val data = ByteArray(info.size).also { buf.get(it) }
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                hub.csd = data // SPS/PPS: se reenvían a cada cliente nuevo con el siguiente clave
                            } else {
                                hub.video(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                    }
                }
            }
        } catch (_: IllegalStateException) {
            // el codec se paró mientras drenábamos
        }
    }

    private fun bytes(b: java.nio.ByteBuffer) = ByteArray(b.remaining()).also { b.duplicate().get(it) }

    /** Orden seguro: parar el hilo, luego el codec, luego la Surface. Sin fugas aunque falle algo. */
    fun release() {
        running = false
        runCatching { thread?.join(500) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
    }
}
