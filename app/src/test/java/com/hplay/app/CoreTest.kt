package com.hplay.app

import com.hplay.app.capture.EncoderProfile
import com.hplay.app.cast.Didl
import com.hplay.app.cast.Soap
import com.hplay.app.discovery.Cap
import com.hplay.app.discovery.Device
import com.hplay.app.discovery.ProfileCache
import com.hplay.app.discovery.SinkProfile
import com.hplay.app.discovery.Ssdp
import com.hplay.app.discovery.UpnpDescription
import com.hplay.app.server.HttpServer
import com.hplay.app.server.MediaProxy
import com.hplay.app.server.WebSocket
import com.hplay.app.stream.Adts
import com.hplay.app.stream.HlsSegmenter
import com.hplay.app.stream.MediaHub
import com.hplay.app.stream.TsMuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class CoreTest {

    @Test fun patMatchesFfmpegReference() {
        val psi = TsMuxer(withAudio = true).psi()
        assertEquals(376, psi.size)
        // PAT de referencia de ffmpeg (tsid 1, programa 1, PMT 0x1000): CRC 2A B1 04 B2
        val pat = psi.copyOfRange(0, 21).joinToString(" ") { "%02X".format(it) }
        assertEquals("47 40 00 10 00 00 B0 0D 00 01 C1 00 00 00 01 F0 00 2A B1 04 B2", pat)
    }

    @Test fun tsPacketsAreAlignedAndCountersIncrease() {
        val m = TsMuxer(withAudio = true)
        val frame = ByteArray(5000) { (it % 251).toByte() }
        val v1 = m.video(byteArrayOf(0, 0, 0, 1, 0x65) + frame, 1_000_000, key = true)
        val v2 = m.video(byteArrayOf(0, 0, 0, 1, 0x41) + frame.copyOf(100), 1_033_000, key = false)
        val a = m.audio(Adts.header(300) + ByteArray(300), 1_010_000)
        for (ts in listOf(v1, v2, a)) {
            assertEquals(0, ts.size % 188)
            for (i in ts.indices step 188) assertEquals(0x47, ts[i].toInt())
        }
        val ccs = (v1 + v2).toList().chunked(188).filter { ((it[1].toInt() and 0x1F) shl 8 or (it[2].toInt() and 0xFF)) == TsMuxer.VIDEO_PID }
            .map { it[3].toInt() and 0x0F }
        ccs.zipWithNext().forEach { (x, y) -> assertEquals((x + 1) and 0x0F, y) }
        // Primer paquete de vídeo de un clave: random_access_indicator y PCR
        val firstVideo = v1.toList().chunked(188).first { ((it[1].toInt() and 0x1F) shl 8 or (it[2].toInt() and 0xFF)) == TsMuxer.VIDEO_PID }
        assertEquals(0x50, firstVideo[5].toInt() and 0xFF)
    }

    @Test fun hlsSegmentsStartOnKeyframes() {
        val h = HlsSegmenter()
        h.add(ByteArray(188), 0, isKeyVideo = false) // descartado: no empieza en clave
        var t = 0L
        repeat(4) {
            h.add(ByteArray(188), t, isKeyVideo = true)
            h.add(ByteArray(188), t + 500_000, isKeyVideo = false)
            t += 1_000_000
        }
        h.add(ByteArray(188), t, isKeyVideo = true)
        assertEquals(4, h.count())
        val pl = h.playlist()
        assertTrue(pl.contains("#EXT-X-MEDIA-SEQUENCE:0"))
        assertTrue(pl.contains("#EXTINF:1.000,\nseg/3.ts"))
        assertEquals(376, h.get(0)!!.size)
    }

    @Test fun durationsDoNotDrift() {
        var prev = -1L
        var total = 0L
        var pts = 5_000_000L
        repeat(1000) {
            total += MediaHub.durationMs(prev, pts, 21)
            prev = pts
            pts += 21_333 // trama AAC a 48 kHz
        }
        // 999 deltas de 21,333 ms = 21 312 ms (+21 del primero): sin deriva por redondeo
        assertEquals(21 + (prev / 1000 - 5_000_000 / 1000), total)
    }

    @Test fun nalTypeDetection() {
        assertEquals(7, MediaHub.firstNalType(byteArrayOf(0, 0, 0, 1, 0x67, 1)))
        assertEquals(5, MediaHub.firstNalType(byteArrayOf(0, 0, 1, 0x65, 1)))
    }

    @Test fun adtsHeader() {
        val h = Adts.header(100)
        assertEquals(0xFF, h[0].toInt() and 0xFF)
        val len = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) ushr 5)
        assertEquals(107, len)
    }

    @Test fun websocketAcceptRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", HttpServer.wsAccept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun websocketFramesRoundTrip() {
        assertEquals(2 + 10, WebSocket.frame(WebSocket.TEXT, ByteArray(10)).size)
        assertEquals(4 + 300, WebSocket.frame(WebSocket.BINARY, ByteArray(300)).size)
        assertEquals(10 + 70_000, WebSocket.frame(WebSocket.BINARY, ByteArray(70_000)).size)
        // Trama de cliente enmascarada «Hello» (RFC 6455 §5.7)
        val masked = byteArrayOf(0x81.toByte(), 0x85.toByte(), 0x37, 0xfa.toByte(), 0x21, 0x3d, 0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58)
        val m = WebSocket.read(masked.inputStream())
        assertEquals(WebSocket.TEXT, m.opcode)
        assertEquals("Hello", String(m.payload))
    }

    @Test fun playlistRewriteGoesThroughProxy() {
        val body = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\nhttps://cdn.x/seg1.ts\n"
        val out = MediaProxy.rewritePlaylist(body, URI("https://h.com/live/master.m3u8")) { "P(" + it + ")" }
        assertTrue(out.contains("URI=\"P(https://h.com/live/key.bin)\""))
        assertTrue(out.contains("\nP(https://h.com/live/low/index.m3u8)\n"))
        assertTrue(out.contains("\nP(https://cdn.x/seg1.ts)"))
        assertTrue(out.contains("#EXT-X-STREAM-INF:BANDWIDTH=1"))
        assertEquals("m3u8", MediaProxy.extOf("https://a.b/x/y.M3U8?token=1"))
        assertEquals("bin", MediaProxy.extOf("https://a.b/x/play"))
    }

    @Test fun ssdpParseAndDescription() {
        val h = Ssdp.parse("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.5:1400/xml/desc.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")
        assertEquals("http://192.168.1.5:1400/xml/desc.xml", h["LOCATION"])
        val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>[TV] Salón</friendlyName>
            <manufacturer>Samsung</manufacturer><modelName>UE55</modelName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/AVTransport1</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>upnp/control/RC</controlURL></service>
            </serviceList></device></root>"""
        val d = UpnpDescription.parse(xml, "http://192.168.1.5:1400/xml/desc.xml")
        assertEquals("[TV] Salón", d.friendlyName)
        assertEquals("http://192.168.1.5:1400/upnp/control/AVTransport1", d.avTransport)
        assertEquals("http://192.168.1.5:1400/xml/upnp/control/RC", d.renderingControl)
    }

    @Test fun soapAndDidlEscapeXml() {
        val env = Soap.envelope(Soap.AVT, "SetAVTransportURI", listOf("CurrentURI" to "http://a/b?x=1&y=<2>"))
        assertTrue(env.contains("<CurrentURI>http://a/b?x=1&amp;y=&lt;2&gt;</CurrentURI>"))
        val didl = Didl.item("http://a/v.mp4?a=1&b=2", "Tom & Jerry", "video/mp4", live = false)
        assertTrue(didl.contains("Tom &amp; Jerry"))
        assertTrue(didl.contains("http://a/v.mp4?a=1&amp;b=2</res>"))
        assertEquals("1:02:03", Soap.hms(3723))
        assertEquals(3723, Soap.parseHms("01:02:03.000"))
        assertEquals("PLAYING", Soap.tag("<s:Body><u:R><CurrentTransportState>PLAYING</CurrentTransportState></u:R>", "CurrentTransportState"))
    }

    @Test fun sinkProfileReadsProtocolInfo() {
        val p = SinkProfile(listOf("http-get:*:video/mp4:*", "http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_HD_50_AC3", "http-get:*:application/x-mpegURL:*"))
        assertTrue(p.ts); assertTrue(p.hls); assertTrue(p.mp4); assertFalse(p.hevc)
        assertEquals("video/vnd.dlna.mpeg-tts", p.tsMime())
        assertEquals("application/x-mpegURL", p.hlsMime())
        assertFalse(SinkProfile(emptyList()).known)
    }

    @Test fun deviceMergeKeepsAllCapabilities() {
        val manual = Device("192.168.1.9", "192.168.1.9", caps = setOf(Cap.MANUAL))
        val dlna = Device("192.168.1.9", "LG TV", manufacturer = "LG", caps = setOf(Cap.DLNA), avTransportUrl = "http://x")
        val m = manual.merge(dlna).merge(Device("192.168.1.9", "Otro", caps = setOf(Cap.AIRPLAY)))
        assertEquals("LG TV", m.name)
        assertEquals(setOf(Cap.MANUAL, Cap.DLNA, Cap.AIRPLAY), m.caps)
        assertEquals("http://x", m.avTransportUrl)
    }

    @Test fun encoderProfileKeepsAspectAndAlignment() {
        val c = EncoderProfile.choose(1080, 2400, null)
        assertEquals(0, c.width % 16); assertEquals(0, c.height % 16)
        assertEquals(1280, c.height) // vertical: lado largo 1280
        assertFalse(c.highProfile)
        val tv = EncoderProfile.choose(2400, 1080, ProfileCache.Profile(mse = true, high = true, screenLong = 1920))
        assertEquals(1920, tv.width)
        assertTrue(tv.highProfile)
        assertTrue(tv.bitrate in 1_500_000..8_000_000)
    }
}
