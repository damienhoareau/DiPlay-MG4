package com.shilapi.xcertplay.vehicle

import android.content.Context
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.transport.VehicleStatusProvider

/** Selects the vehicle-specific read path without exposing vendor details to the CarPlay host. */
object VehicleIntegration {
    fun isMg4(context: Context): Boolean = Mg4Vehicle.available(context)

    fun onAppOpened(context: Context) {
        if (isMg4(context)) Mg4Vehicle.start(context) else BydNavigationOutputs.onAppOpened(context)
    }

    fun batteryStatus(context: Context): VehicleStatusProvider =
        if (isMg4(context)) Mg4Vehicle.also { it.start(context) }
        else BydNavigationOutputs.batteryStatus(context)

    fun parked(context: Context): Boolean? =
        if (isMg4(context)) Mg4Vehicle.parked(context) else BydNavigationOutputs.parked(context)
}
