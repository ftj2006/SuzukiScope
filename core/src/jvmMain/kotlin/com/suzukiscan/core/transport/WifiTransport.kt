package com.suzukiscan.core.transport

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Plain TCP transport for ELM327 Wi-Fi adapters, matching the Android app's
 * android.service.device.wifi.{HostPort, WiFiDevice} (default 192.168.0.10:35000, 3s connect timeout).
 */
class WifiTransport(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = 3000,
) : Transport {
    override val name: String = "wifi://$host:$port"
    private var socket: Socket? = null

    override suspend fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = s
    }

    override suspend fun disconnect() {
        socket?.close()
        socket = null
    }

    override suspend fun write(bytes: ByteArray) {
        val s = socket ?: throw IOException("Not connected")
        s.getOutputStream().write(bytes)
        s.getOutputStream().flush()
    }

    override suspend fun readUntil(terminator: Byte, timeoutMs: Long): ByteArray {
        val s = socket ?: throw IOException("Not connected")
        s.soTimeout = timeoutMs.toInt()
        val input = s.getInputStream()
        val buffer = ArrayList<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) break
            buffer.add(b.toByte())
            if (b.toByte() == terminator) break
        }
        return buffer.toByteArray()
    }

    override val isConnected: Boolean get() = socket?.isConnected == true

    companion object {
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
    }
}
