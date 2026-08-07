package com.fbt.app

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Tiny OSC 1.0 sender - just enough to talk to VRChat's OSC input.
 * No external dependency; OSC packets are simple enough to hand-roll.
 */
class OscSender {

    @Volatile var host: String = "127.0.0.1"
    @Volatile var port: Int = 9000

    private var socket: DatagramSocket? = null

    fun start() {
        if (socket == null) socket = DatagramSocket()
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    /** Send an OSC message with an arbitrary list of float args. */
    fun sendFloats(address: String, values: FloatArray) {
        val sock = socket ?: return
        try {
            val packet = buildPacket(address, values)
            val addr = InetAddress.getByName(host)
            sock.send(DatagramPacket(packet, packet.size, addr, port))
        } catch (_: Exception) {
            // Swallow send errors (e.g. transient network hiccups) - tracking loop must not crash.
        }
    }

    private fun buildPacket(address: String, values: FloatArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(padString(address))

        val typeTag = StringBuilder(",")
        repeat(values.size) { typeTag.append('f') }
        out.write(padString(typeTag.toString()))

        val buf = ByteBuffer.allocate(4 * values.size)
        for (v in values) buf.putFloat(v)
        out.write(buf.array())

        return out.toByteArray()
    }

    /** OSC strings are null-terminated and padded to a multiple of 4 bytes. */
    private fun padString(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        val len = bytes.size + 1 // +1 for at least one null terminator
        val padded = ((len + 3) / 4) * 4
        val out = ByteArray(padded)
        System.arraycopy(bytes, 0, out, 0, bytes.size)
        return out
    }
}
