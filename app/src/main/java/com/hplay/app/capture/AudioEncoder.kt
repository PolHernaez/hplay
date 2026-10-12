package com.hplay.app.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import com.hplay.app.stream.MediaHub
import kotlin.math.abs

/**
 * Audio interno (AudioPlaybackCapture, Android 10+) → AAC-LC 48 kHz estéreo 128 kbps.
 * Solo captura USAGE_MEDIA/GAME/UNKNOWN y solo de apps que lo permiten: las que lo prohíben suenan en el móvil.
 * Marca de tiempo por recuento de muestras (sin jitter) re-anclada al reloj si se desvía >200 ms.
 */
class AudioEncoder private constructor(private val record: AudioRecord, private val hub: MediaHub) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    @Volatile private var running = true
    private var thread: Thread? = null

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        codec.start()
        record.startRecording()
        thread = Thread(::loop, "hplay-audio").apply { start() }
    }

    private fun loop() {
        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        var anchorUs = -1L
        var samples = 0L
        try {
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val nowUs = System.nanoTime() / 1000
                val chunkUs = n / 4 * 1_000_000L / RATE
                if (anchorUs < 0) anchorUs = nowUs - chunkUs
                var pts = anchorUs + samples * 1_000_000L / RATE
                if (abs(nowUs - chunkUs - pts) > 200_000) { anchorUs = nowUs - chunkUs; samples = 0; pts = anchorUs }
                samples += n / 4
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    codec.getInputBuffer(inIdx)!!.apply { clear(); put(pcm, 0, n) }
                    codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                }
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val b = codec.getOutputBuffer(out)!!
                        b.position(info.offset).limit(info.offset + info.size)
                        hub.audio(ByteArray(info.size).also { b.get(it) }, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(out, false)
                }
            }
        } catch (_: IllegalStateException) {
        }
    }

    fun release() {
        running = false
        runCatching { record.stop() } // desbloquea read()
        runCatching { thread?.join(500) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { record.release() }
    }

    companion object {
        const val RATE = 48_000
        private const val FRAME_BYTES = 1024 * 2 * 2 // 1024 muestras estéreo 16 bit = una trama AAC

        /** Null si no hay permiso de micrófono o el sistema no deja capturar. El espejo sigue sin audio. */
        @SuppressLint("MissingPermission") // RECORD_AUDIO se comprueba en la Activity antes de iniciar
        fun create(projection: MediaProjection, hub: MediaHub): AudioEncoder? = runCatching {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 4))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
            AudioEncoder(rec, hub)
        }.getOrNull()
    }
}
