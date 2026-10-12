package com.hplay.app.server

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/** Tramas WebSocket (RFC 6455). El servidor nunca enmascara; el cliente siempre. */
object WebSocket {
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA

    fun frame(opcode: Int, payload: ByteArray): ByteArray {
        val n = payload.size
        val head = when {
            n < 126 -> byteArrayOf((0x80 or opcode).toByte(), n.toByte())
            n < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126, (n shr 8).toByte(), n.toByte())
            else -> ByteArray(10).also {
                it[0] = (0x80 or opcode).toByte(); it[1] = 127
                for (i in 0 until 8) it[9 - i] = (n.toLong() shr (8 * i)).toByte()
            }
        }
        return head + payload
    }

    class Message(val opcode: Int, val payload: ByteArray)

    /** Lee una trama del cliente (sin fragmentación: los navegadores no fragmentan mensajes pequeños). */
    fun read(input: InputStream, maxLen: Int = 65_536): Message {
        val d = DataInputStream(input)
        val b0 = d.readUnsignedByte()
        val b1 = d.readUnsignedByte()
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = d.readUnsignedShort().toLong()
        else if (len == 127L) len = d.readLong()
        if (len < 0 || len > maxLen) throw IOException("ws frame too big")
        val masked = b1 and 0x80 != 0
        val mask = ByteArray(4)
        if (masked) d.readFully(mask)
        val payload = ByteArray(len.toInt())
        d.readFully(payload)
        if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Message(b0 and 0x0F, payload)
    }
}
