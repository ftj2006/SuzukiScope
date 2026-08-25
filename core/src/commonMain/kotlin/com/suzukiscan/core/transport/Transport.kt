package com.suzukiscan.core.transport

/**
 * Abstraction over the physical link to an OBD adapter (ELM327 over Bluetooth SPP/BLE/USB/Wi-Fi,
 * or a direct FTDI/PassThru adapter). Platform modules provide `actual`-style implementations.
 */
interface Transport {
    val name: String
    suspend fun connect()
    suspend fun disconnect()
    suspend fun write(bytes: ByteArray)
    /** Reads until [terminator] is seen (e.g. ELM327 '>' prompt) or the transport times out. */
    suspend fun readUntil(terminator: Byte, timeoutMs: Long = 2000): ByteArray
    val isConnected: Boolean
}

/** In-memory transport for unit tests / UI previews, without any real hardware. */
class LoopbackTransport(private val responder: (ByteArray) -> ByteArray) : Transport {
    override val name: String = "loopback"
    private var connected = false
    private var lastResponse: ByteArray = ByteArray(0)

    override suspend fun connect() { connected = true }
    override suspend fun disconnect() { connected = false }
    override suspend fun write(bytes: ByteArray) { lastResponse = responder(bytes) }
    override suspend fun readUntil(terminator: Byte, timeoutMs: Long): ByteArray = lastResponse
    override val isConnected: Boolean get() = connected
}
