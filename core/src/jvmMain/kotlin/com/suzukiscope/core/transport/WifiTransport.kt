package com.suzukiscope.core.transport

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private var input: BufferedInputStream? = null

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            // Nagle's algorithm can add tens of ms of pure waiting to every single
            // request/response round trip on a short request-then-wait-for-reply protocol like
            // this one — disabling it is a meaningful, free latency win for live-data polling.
            s.tcpNoDelay = true
            socket = s
            input = BufferedInputStream(s.getInputStream())
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            socket?.close()
            socket = null
            input = null
        }
    }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val s = socket ?: throw IOException("Not connected")
            s.getOutputStream().write(bytes)
            s.getOutputStream().flush()
        }
    }

    override suspend fun readUntil(terminator: Byte, timeoutMs: Long): ByteArray {
        return withContext(Dispatchers.IO) {
            val s = socket ?: throw IOException("Not connected")
            val stream = input ?: throw IOException("Not connected")
            s.soTimeout = timeoutMs.toInt()
            val buffer = ArrayList<Byte>()
            while (true) {
                val b = stream.read()
                if (b == -1) break
                buffer.add(b.toByte())
                if (b.toByte() == terminator) break
            }
            buffer.toByteArray()
        }
    }

    override val isConnected: Boolean get() = socket?.isConnected == true

    companion object {
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
    }
}
