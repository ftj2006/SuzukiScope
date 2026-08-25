package com.suzukiscan.android.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/** All bonded devices, likely ELM327 adapters (name contains OBD/ELM) sorted first. */
fun listBondedDevices(context: Context): List<BluetoothDevice> {
    val adapter = (context.getSystemService(BluetoothManager::class.java) as BluetoothManager).adapter
    val devices = adapter?.bondedDevices?.toList() ?: emptyList()
    return devices.sortedByDescending {
        it.name?.contains("OBD", ignoreCase = true) == true || it.name?.contains("ELM", ignoreCase = true) == true
    }
}

/** Picks the most likely ELM327 adapter from bonded devices (by name, else first bonded). */
fun findBondedElm327Device(context: Context): BluetoothDevice? = listBondedDevices(context).firstOrNull()
