package com.hplay.app.stream

import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Convierte el hub en MPEG-TS y lo reparte a:
 *  - clientes de flujo TS continuo (/live.ts), lo que mejor tragan las teles DLNA (~1–2 s de retardo);
 *  - un segmentador HLS de segmentos de ~1 s (/live.m3u8) para teles y navegadores que solo entienden HLS.
 */
class TsSink(withAudio: Boolean) : MediaHub.Sink {
    private val muxer = TsMuxer(withAudio)
    val hls = HlsSegmenter()

    /** Cliente TS continuo: arranca en un fotograma clave; si se atasca, se descarta. */
    class Client {
        val queue = Channel<ByteArray>(capacity = 120)
        @Volatile var started = false
    }

    val clients = CopyOnWriteArrayList<Client>()

    @Synchronized override fun onVideo(f: MediaHub.VideoFrame) {
        val ts = muxer.video(f.data, f.ptsUs, f.key)
        hls.add(ts, f.ptsUs, isKeyVideo = f.key)
        fanOut(ts, f.key)
    }

    @Synchronized override fun onAudio(f: MediaHub.AudioFrame) {
        val ts = muxer.audio(f.adts, f.ptsUs)
        hls.add(ts, f.ptsUs, isKeyVideo = false)
        fanOut(ts, false)
    }

    @Synchronized override fun onReset() = hls.discontinuity()

    private fun fanOut(ts: ByteArray, key: Boolean) {
        for (c in clients) {
            if (!c.started) { if (!key) continue; c.started = true }
            if (c.queue.trySend(ts).isFailure) { c.queue.close(); clients.remove(c) }
        }
    }
}

class HlsSegmenter(private val targetUs: Long = 1_000_000, private val keep: Int = 10, private val listed: Int = 4) {
    class Segment(val seq: Long, val data: ByteArray, val durationUs: Long, val discontinuity: Boolean)

    private val segments = ArrayDeque<Segment>()
    private var current: ByteArrayOutputStream? = null
    private var startUs = -1L
    private var nextSeq = 0L
    private var pendingDiscontinuity = false

    @Synchronized fun add(ts: ByteArray, ptsUs: Long, isKeyVideo: Boolean) {
        if (isKeyVideo && current != null && ptsUs - startUs >= targetUs * 9 / 10) close(ptsUs)
        if (current == null) {
            if (!isKeyVideo) return // cada segmento empieza en fotograma clave (con PAT/PMT)
            current = ByteArrayOutputStream(512 * 1024)
            startUs = ptsUs
        }
        current!!.write(ts)
    }

    private fun close(endUs: Long) {
        val data = current?.toByteArray() ?: return
        segments.addLast(Segment(nextSeq++, data, (endUs - startUs).coerceAtLeast(1), pendingDiscontinuity))
        pendingDiscontinuity = false
        while (segments.size > keep) segments.removeFirst()
        current = null
    }

    @Synchronized fun discontinuity() {
        current = null
        pendingDiscontinuity = true
    }

    @Synchronized fun count() = segments.size

    @Synchronized fun get(seq: Long): ByteArray? = segments.firstOrNull { it.seq == seq }?.data

    @Synchronized fun playlist(): String {
        val list = segments.toList().takeLast(listed)
        val target = maxOf(2L, list.maxOfOrNull { (it.durationUs + 999_999) / 1_000_000 } ?: 2L)
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(list.firstOrNull()?.seq ?: 0).append('\n')
            for (s in list) {
                if (s.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                append(String.format(Locale.US, "#EXTINF:%.3f,\n", s.durationUs / 1e6))
                append("seg/").append(s.seq).append(".ts\n")
            }
        }
    }
}
