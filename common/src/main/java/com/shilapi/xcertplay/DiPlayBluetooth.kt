package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings

internal object DiPlayBluetooth {
    fun localAddress(context: Context): String? {
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull()
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }

    /** True/false when the Android firmware exposes the classic-link state; null otherwise. */
    fun isConnected(context: Context, address: String): Boolean? = runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return@runCatching null
        val device = adapter.bondedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: return@runCatching false
        val method = device.javaClass.getMethod("isConnected")
        method.invoke(device) as? Boolean
    }.getOrNull()
}
