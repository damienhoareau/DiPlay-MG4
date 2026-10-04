package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log

/** Enables the head unit's saved Android 9 SoftAP configuration on MG platform builds. */
object CarHotspotController {
    fun enable(context: Context): Boolean {
        if (CarHotspotStatus.isEnabled(context) == true) {
            Log.i(TAG, "MG hotspot already enabled")
            return true
        }

        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            Log.w(TAG, "MG hotspot enable failed: WifiManager unavailable")
            return false
        }

        val legacyAccepted = runCatching {
            val configuration = WifiManager::class.java
                .getMethod("getWifiApConfiguration")
                .invoke(wifi) as? WifiConfiguration
            val enabled = WifiManager::class.java
                .getMethod(
                    "setWifiApEnabled",
                    WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                .invoke(wifi, configuration, true) as? Boolean ?: false
            if (enabled) {
                Log.i(TAG, "MG hotspot enable requested with saved configuration")
            } else {
                Log.w(TAG, "MG hotspot enable request was rejected")
            }
            enabled
        }.onFailure { error ->
            Log.w(TAG, "Legacy MG hotspot enable failed", error)
        }.getOrDefault(false)
        if (legacyAccepted || hotspotIsStarting(wifi)) return true

        // Some MG Android 9 builds expose the old WifiManager API but reject callers at
        // runtime. Their system tethering service remains available to platform apps.
        val connectivity = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            val serviceField = ConnectivityManager::class.java.getDeclaredField("mService")
                .apply { isAccessible = true }
            val service = serviceField.get(connectivity)
                ?: error("Connectivity service unavailable")
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) {
                    if (resultCode == 0) Log.i(TAG, "MG hotspot started through tethering service")
                    else Log.w(TAG, "MG hotspot start failed through tethering service: $resultCode")
                }
            }
            val method = service.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "startTethering" &&
                    candidate.parameterTypes.all { type ->
                        type == Int::class.javaPrimitiveType ||
                            type == Boolean::class.javaPrimitiveType ||
                            type == String::class.java ||
                            ResultReceiver::class.java.isAssignableFrom(type)
                    }
            } ?: error("No compatible tethering service method")
            val arguments = method.parameterTypes.map { type ->
                when {
                    type == Int::class.javaPrimitiveType -> TETHERING_WIFI
                    type == Boolean::class.javaPrimitiveType -> false
                    type == String::class.java -> context.packageName
                    ResultReceiver::class.java.isAssignableFrom(type) -> receiver
                    else -> error("Unsupported tethering argument ${type.name}")
                }
            }.toTypedArray()
            method.invoke(service, *arguments)
            Log.i(TAG, "MG hotspot start requested through tethering service")
            true
        }.onFailure { error ->
            Log.w(TAG, "MG hotspot fallback failed", error)
        }.getOrDefault(false)
    }

    private fun hotspotIsStarting(wifi: WifiManager): Boolean = runCatching {
        val state = WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int
        state == WIFI_AP_STATE_ENABLING || state == WIFI_AP_STATE_ENABLED
    }.getOrDefault(false)

    private const val TAG = "xcertplay-hotspot"
    private const val WIFI_AP_STATE_ENABLING = 12
    private const val WIFI_AP_STATE_ENABLED = 13
    private const val TETHERING_WIFI = 0
}
