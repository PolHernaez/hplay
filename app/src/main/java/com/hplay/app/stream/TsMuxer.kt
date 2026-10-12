package com.hplay.app.stream

import java.io.ByteArrayOutputStream

/**
 * Muxer MPEG-TS mínimo (ISO/IEC 13818-1): H.264 (PID 0x100) + AAC ADTS (PID 0x101), PCR en el vídeo.
 * TS es el contenedor que más teles DLNA y reproductores HLS aceptan.
 */
class TsMuxer(private val withAudio: Boolean) {
    companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        private const val PTS_OFFSET = 90_000L // 1 s: la PCR (100 ms antes) nunca queda negativa
        private const val MASK33 = (1L shl 33) - 1

        fun crc32(b: ByteArray, off: Int, len: Int): Int {
            var crc = -1
            for (i in off until off + len) {
                crc = crc xor ((b[i].toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }

    private val cc = HashMap<Int, Int>()
    private var baseUs = -1L

    private fun nextCc(pid: Int): Int {
        val v = cc[pid] ?: 0
        cc[pid] = (v + 1) and 0x0F
        return v
    }

    private fun to90k(ptsUs: Long): Long {
        if (baseUs < 0) baseUs = ptsUs
        return ((ptsUs - baseUs) * 9 / 100 + PTS_OFFSET) and MASK33
    }

    /** PAT + PMT. Van delante de cada fotograma clave para que cualquier corte del flujo sea reproducible. */
    fun psi(): ByteArray {
        val out = ByteArrayOutputStream(376)
        val pat = byteArrayOf(
            0x00, 0xB0.toByte(), 13, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte(),
        )
        writeSection(out, 0, pat)
        val streams = ByteArrayOutputStream()
        streams.write(byteArrayOf(0x1B, (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), 0xF0.toByte(), 0x00))
        if (withAudio) streams.write(byteArrayOf(0x0F, (0xE0 or (AUDIO_PID shr 8)).toByte(), (AUDIO_PID and 0xFF).toByte(), 0xF0.toByte(), 0x00))
        val s = streams.toByteArray()
        val pmt = byteArrayOf(
            0x02, 0xB0.toByte(), (9 + s.size + 4).toByte(), 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), 0xF0.toByte(), 0x00,
        ) + s
        writeSection(out, PMT_PID, pmt)
        return out.toByteArray()
    }

    private fun writeSection(out: ByteArrayOutputStream, pid: Int, section: ByteArray) {
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or (pid shr 8)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or nextCc(pid)).toByte()
        pkt[4] = 0 // pointer_field
        System.arraycopy(section, 0, pkt, 5, section.size)
        val crc = crc32(section, 0, section.size)
        val p = 5 + section.size
        pkt[p] = (crc ushr 24).toByte(); pkt[p + 1] = (crc ushr 16).toByte()
        pkt[p + 2] = (crc ushr 8).toByte(); pkt[p + 3] = crc.toByte()
        out.write(pkt)
    }

    fun video(annexB: ByteArray, ptsUs: Long, key: Boolean): ByteArray {
        val out = ByteArrayOutputStream(annexB.size + annexB.size / 180 * 8 + 600)
        if (key) out.write(psi())
        val pts = to90k(ptsUs)
        pes(out, VIDEO_PID, 0xE0, AUD + annexB, pts, key, pcr = (pts - 9000) and MASK33, bounded = false)
        return out.toByteArray()
    }

    fun audio(adts: ByteArray, ptsUs: Long): ByteArray {
        val out = ByteArrayOutputStream(adts.size + 200)
        pes(out, AUDIO_PID, 0xC0, adts, to90k(ptsUs), key = false, pcr = -1, bounded = true)
        return out.toByteArray()
    }

    private fun pes(out: ByteArrayOutputStream, pid: Int, streamId: Int, payload: ByteArray, pts: Long, key: Boolean, pcr: Long, bounded: Boolean) {
        val pesLen = if (bounded) 3 + 5 + payload.size else 0 // vídeo: 0 = longitud no acotada (permitido)
        val head = byteArrayOf(
            0, 0, 1, streamId.toByte(), (pesLen shr 8).toByte(), pesLen.toByte(),
            0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or ((pts shr 29).toInt() and 0x0E)).toByte(),
            (pts shr 22).toByte(),
            (((pts shr 14).toInt() and 0xFE) or 1).toByte(),
            (pts shr 7).toByte(),
            (((pts shl 1).toInt() and 0xFE) or 1).toByte(),
        )
        val full = head + payload
        var pos = 0
        var first = true
        while (pos < full.size) {
            val pkt = ByteArray(188)
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            pkt[2] = (pid and 0xFF).toByte()
            // Campo de adaptación: acceso aleatorio + PCR en el primer paquete; relleno en el último.
            val afContent = if (first && (key || pcr >= 0)) {
                val flags = (if (key) 0x40 else 0) or (if (pcr >= 0) 0x10 else 0)
                if (pcr >= 0) byteArrayOf(
                    flags.toByte(), (pcr shr 25).toByte(), (pcr shr 17).toByte(), (pcr shr 9).toByte(),
                    (pcr shr 1).toByte(), (((pcr and 1).toInt() shl 7) or 0x7E).toByte(), 0,
                ) else byteArrayOf(flags.toByte())
            } else ByteArray(0)
            val afMin = if (afContent.isEmpty()) 0 else 1 + afContent.size
            val payloadLen = minOf(full.size - pos, 184 - afMin)
            val afTotal = 184 - payloadLen // incluye relleno
            val cc = nextCc(pid)
            if (afTotal > 0) {
                pkt[3] = (0x30 or cc).toByte()
                pkt[4] = (afTotal - 1).toByte()
                if (afTotal >= 2) {
                    pkt[5] = if (afContent.isEmpty()) 0 else afContent[0]
                    var i = 6
                    for (k in 1 until afContent.size) pkt[i++] = afContent[k]
                    while (i < 4 + afTotal) pkt[i++] = 0xFF.toByte()
                }
            } else {
                pkt[3] = (0x10 or cc).toByte()
            }
            System.arraycopy(full, pos, pkt, 4 + afTotal, payloadLen)
            out.write(pkt)
            pos += payloadLen
            first = false
        }
    }
}
