package com.suzukiscope.android.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.suzukiscope.core.transport.Transport
import java.io.IOException
import java.util.UUID

/**
 * Classic Bluetooth RFCOMM (SPP) transport, ported from the Android APK's
 * android.service.device.bt.BTDevice: same well-known SPP UUID, same
 * createRfcommSocketToServiceRecord() call. No BLE — the original app never used it.
 */
class BluetoothSppTransport(
    private val device: BluetoothDevice,
) : Transport {
    override val name: String = "bt://${device.address}"
    private var socket: BluetoothSocket? = null

    override suspend fun connect() {
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
    }

    override suspend fun disconnect() {
        socket?.close()
        socket = null
    }

    override suspend fun write(bytes: ByteArray) {
        val s = socket ?: throw IOException("Not connected")
        s.outputStream.write(bytes)
        s.outputStream.flush()
    }

    override suspend fun readUntil(terminator: Byte, timeoutMs: Long): ByteArray {
        val s = socket ?: throw IOException("Not connected")
        val input = s.inputStream
        val buffer = ArrayList<Byte>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break
                buffer.add(b.toByte())
                if (b.toByte() == terminator) break
            }
        }
        return buffer.toByteArray()
    }

    override val isConnected: Boolean get() = socket?.isConnected == true

    companion object {
        /** Standard Serial Port Profile UUID, same constant used by the original app. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
