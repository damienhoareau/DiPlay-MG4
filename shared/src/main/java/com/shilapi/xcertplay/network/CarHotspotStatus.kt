package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import java.io.File

/**
 * Reads whether the head unit's own Wi-Fi hotspot is on, for the "Car hotspot" link.
 *
 * DiPlay does not turn the hotspot on itself: that needs a permission Android only grants over
 * ADB. The user turns it on in the car settings, or automates it with a tool such as BYDMate.
 */
object CarHotspotStatus {
    private const val WIFI_AP_STATE_ENABLED = 13

    /**
     * True/false from the Wi-Fi AP state, or null when the firmware hides it (then callers must
     * not block the connection). Interface flags are not used: BYD keeps wlan1 up with an address
     * while tethering is off.
     */
    fun isEnabled(context: Context): Boolean? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        return runCatching {
            WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int == WIFI_AP_STATE_ENABLED
        }.recoverCatching {
            WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
        }.getOrNull()
    }

    /**
     * Best-effort check for a phone associated with the car hotspot. Android 9 has no public
     * SoftAP client API, but its ARP table identifies active clients on the hotspot interface.
     * Null means the firmware did not expose enough information, so callers must not block.
     */
    fun hasConnectedClient(): Boolean? = runCatching {
        parseArpClients(File("/proc/net/arp").readLines()).isNotEmpty()
    }.getOrNull()

    internal fun parseArpClients(lines: List<String>): Set<String> = lines.drop(1).mapNotNull { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 6) return@mapNotNull null
        val flags = columns[2].removePrefix("0x").toIntOrNull(16) ?: return@mapNotNull null
        val mac = columns[3]
        val iface = columns[5]
        if (flags and 0x2 == 0 || mac == "00:00:00:00:00:00" || !isHotspotInterface(iface)) null else mac
    }.toSet()

    private fun isHotspotInterface(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized.startsWith("ap") || normalized.startsWith("swlan") ||
            normalized.startsWith("wlan1") || normalized.startsWith("wlan2") ||
            normalized.startsWith("softap")
    }
}
