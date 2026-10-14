package com.hplay.app

import com.hplay.app.detect.DnsPacket
import com.hplay.app.detect.MediaDetector
import com.hplay.app.detect.MediaDetector.DomainClass
import com.hplay.app.detect.MediaDetector.Evidence
import com.hplay.app.detect.MediaDetector.Kind
import com.hplay.app.detect.MediaDetector.Source
import com.hplay.app.detect.Probe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DetectorTest {
    @Before fun reset() = MediaDetector.clear()

    @Test fun kindFromMimeWinsOverExtension() {
        assertEquals(Kind.HLS, MediaDetector.kindOf("https://cdn.example/x/playlist", "application/vnd.apple.mpegurl"))
        assertEquals(Kind.DASH, MediaDetector.kindOf("https://cdn.example/manifest.mpd?t=1", null))
        assertEquals(Kind.MP4, MediaDetector.kindOf("https://e.example/v.MP4#t=3", null))
        assertEquals(Kind.UNKNOWN, MediaDetector.kindOf("https://e.example/watch?v=1", null))
        assertEquals(Kind.PAGE, MediaDetector.kindOf("https://e.example/", "text/html"))
    }

    @Test fun evidenceDistinguishesDeclaredFromGuessed() {
        assertEquals(Evidence.MIME, MediaDetector.evidenceOf("https://e.example/a", "video/mp4"))
        assertEquals(Evidence.URL, MediaDetector.evidenceOf("https://e.example/a.m3u8", null))
        assertEquals(Evidence.NONE, MediaDetector.evidenceOf("https://e.example/page", null))
    }

    @Test fun expiryFromSignedUrls() {
        assertEquals(1_760_000_000_000, MediaDetector.expiryOf("https://r1.googlevideo.com/videoplayback?expire=1760000000&ei=x"))
        assertEquals(1_760_000_000_123, MediaDetector.expiryOf("https://cdn.example/a.m3u8?Expires=1760000000123&Signature=z"))
        assertEquals(1_760_000_000_000, MediaDetector.expiryOf("https://a.akamaized.net/m.m3u8?hdnts=st%3D1%7Eexp%3D1760000000%7Eacl%3D*"))
        // 2026-10-15 12:00:00Z + 3600 s
        assertEquals(1_792_065_600_000 + 3_600_000, MediaDetector.expiryOf("https://b.s3.amazonaws.com/v.mp4?X-Amz-Date=20261015T120000Z&X-Amz-Expires=3600"))
        assertNull(MediaDetector.expiryOf("https://e.example/v.mp4?id=1760000000"))
        assertNull(MediaDetector.expiryOf("https://e.example/v.mp4"))
    }

    @Test fun domainsAreClassifiedNotPromotedToUrls() {
        assertEquals(DomainClass.VIDEO_SERVICE, MediaDetector.classify("rr3---sn-h5q7dnee.googlevideo.com"))
        assertEquals(DomainClass.CDN, MediaDetector.classify("d1abc.cloudfront.net."))
        assertEquals(DomainClass.OTHER, MediaDetector.classify("notgooglevideo.com"))
        MediaDetector.domain("video-weaver.mad01.hls.ttvnw.net", "Twitch (tv.twitch.android.app)")
        MediaDetector.domain("video-weaver.mad01.hls.ttvnw.net", "Twitch (tv.twitch.android.app)")
        val d = MediaDetector.domains.value.single()
        assertEquals(2, d.count)
        assertEquals(0, MediaDetector.items.value.size) // un dominio nunca crea una «dirección recibida»
    }

    @Test fun didlFromPlayShotsStyleMetadata() {
        val didl = "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;&lt;item id=\"0\" parentID=\"-1\" restricted=\"1\"&gt;" +
            "&lt;dc:title&gt;Demo&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;" +
            "&lt;res protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01\" size=\"1048576\" duration=\"0:10:34.000\" resolution=\"1280x720\"&gt;" +
            "http://192.168.1.20:8080/v.mp4&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
        val m = MediaDetector.parseDidl(didl)
        assertEquals("http-get:*:video/mp4:DLNA.ORG_OP=01", m["protocolInfo"])
        assertEquals("0:10:34.000", m["duration"])
        assertEquals("1280x720", m["resolution"])
        assertEquals("object.item.videoItem", m["class"])
        assertEquals(emptyMap<String, String>(), MediaDetector.parseDidl(""))
    }

    @Test fun reportDeduplicatesAndKeepsStrongestEvidence() {
        val url = "https://cdn.example/live/index.m3u8"
        MediaDetector.report(Source.DLNA, url, "application/x-mpegurl", "Live", "LAN 192.168.1.5")
        val again = MediaDetector.report(Source.DLNA, url, null, "", "LAN 192.168.1.5")
        assertEquals(1, MediaDetector.items.value.size)
        assertEquals(Evidence.MIME, again.evidence)
        assertEquals("Live", again.title)
        assertEquals("cdn.example", again.domain)
    }

    @Test fun mxPlayerHeaderConvention() {
        assertEquals(mapOf("Referer" to "https://site.example/", "User-Agent" to "UA"),
            MediaDetector.headersFromArray(arrayOf("Referer", "https://site.example/", "User-Agent", "UA", "dangling")))
        assertEquals(emptyMap<String, String>(), MediaDetector.headersFromArray(null))
    }

    @Test fun sniffRecognisesContainers() {
        assertEquals(Kind.HLS, Probe.sniff("﻿#EXTM3U\n#EXT-X-VERSION:3".toByteArray(), null))
        assertEquals(Kind.DASH, Probe.sniff("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">".toByteArray(), null))
        assertEquals(Kind.MP4, Probe.sniff(byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray(), "video/mp4"))
        assertEquals(Kind.WEBM, Probe.sniff(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), "video/webm"))
        assertEquals(Kind.TS, Probe.sniff(ByteArray(376).also { it[0] = 0x47; it[188] = 0x47 }, null))
        assertEquals(Kind.PAGE, Probe.sniff("<!DOCTYPE html><html>".toByteArray(), "text/html"))
        assertNull(Probe.sniff("{\"error\":1}".toByteArray(), "application/json"))
    }

    /** Consulta A de «example.com» desde 10.111.222.2:40000 al DNS falso, como la escribe el sistema en el túnel. */
    private fun queryPacket(): ByteArray {
        val dns = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0) +
            byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
        val total = 28 + dns.size
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total shr 8).toByte(); p[3] = total.toByte(); p[8] = 64; p[9] = 17
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 12)
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 16)
        p[20] = (40000 shr 8).toByte(); p[21] = 40000.toByte(); p[23] = 53
        p[24] = ((8 + dns.size) shr 8).toByte(); p[25] = (8 + dns.size).toByte()
        dns.copyInto(p, 28)
        return p
    }

    @Test fun dnsQueryParsesAndReplyMirrorsAddresses() {
        val p = queryPacket()
        val q = assertNotNullAnd(DnsPacket.parse(p, p.size))
        assertEquals("example.com", q.name)
        assertEquals(1, q.type)
        assertEquals(40000, q.srcPort)
        val answer = byteArrayOf(0x00, 0x00, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0) + q.dns.copyOfRange(12, q.dns.size)
        val r = DnsPacket.reply(q, answer)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 1), r.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), r.copyOfRange(16, 20))
        assertEquals(53, ((r[20].toInt() and 0xFF) shl 8) or (r[21].toInt() and 0xFF))
        assertEquals(40000, ((r[22].toInt() and 0xFF) shl 8) or (r[23].toInt() and 0xFF))
        assertEquals(0x12.toByte(), r[28]); assertEquals(0x34.toByte(), r[29]) // ID original restaurado
        assertEquals(0, DnsPacket.checksum(r, 0, 20)) // cabecera IP válida
        assertNull(DnsPacket.parse(r, r.size)) // una respuesta no es consulta
    }

    @Test fun servfailKeepsQuestion() {
        val p = queryPacket()
        val q = DnsPacket.parse(p, p.size)!!
        val f = DnsPacket.servfail(q)
        assertEquals(2, f[3].toInt() and 0x0F)
        assertEquals("example.com", DnsPacket.readName(f, 12)!!.first)
    }

    private fun <T> assertNotNullAnd(v: T?): T { assertNotNull(v); return v!! }
}
