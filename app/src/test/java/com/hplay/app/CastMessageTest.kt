package com.hplay.app

import com.hplay.app.cast.CastMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CastMessageTest {
    @Test fun roundTrip() {
        val m = CastMessage("sender-0", "receiver-0", "urn:x-cast:com.google.cast.tp.connection", """{"type":"CONNECT"}""")
        assertEquals(m, CastMessage.decode(m.encode()))
    }

    /** Bytes de referencia: protocol_version=0, source, dest, namespace cortos, payload_type=0, payload. */
    @Test fun wireFormat() {
        val b = CastMessage("s", "d", "n", "p").encode()
        assertArrayEquals(byteArrayOf(8, 0, 18, 1, 's'.code.toByte(), 26, 1, 'd'.code.toByte(), 34, 1, 'n'.code.toByte(), 40, 0, 50, 1, 'p'.code.toByte()), b)
    }

    @Test fun longPayloadVarint() {
        val p = "x".repeat(300) // longitud de 2 bytes en varint
        assertEquals(p, CastMessage.decode(CastMessage("a", "b", "c", p).encode()).payload)
    }
}
