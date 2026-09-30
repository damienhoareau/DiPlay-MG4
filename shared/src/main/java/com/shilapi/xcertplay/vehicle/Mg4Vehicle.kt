package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Read-only SWI69 bridge. All values come from the same EVHardware paths used by EVABRPUploader. */
object Mg4Vehicle : VehicleStatusProvider {
    private const val TAG = "DiPlay-MG4"
    private const val READ_MILLIS = 30_000L
    private const val STALE_MILLIS = 3 * 60_000L

    @Volatile private var app: Context? = null
    @Volatile private var latest: VehicleStatusSnapshot? = null
    @Volatile private var latestMillis = 0L
    @Volatile private var started = false
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diplay-mg4-vehicle").apply { isDaemon = true }
    }

    fun available(context: Context): Boolean {
        EVHardware.init(context.applicationContext)
        return FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI69
    }

    @Synchronized
    fun start(context: Context) {
        app = context.applicationContext
        EVHardware.init(context.applicationContext)
        if (started) {
            executor.execute(::poll)
            return
        }
        started = true
        executor.scheduleWithFixedDelay(::poll, 0, READ_MILLIS, TimeUnit.MILLISECONDS)
    }

    override fun snapshot(): VehicleStatusSnapshot? =
        latest?.takeIf { SystemClock.elapsedRealtime() - latestMillis <= STALE_MILLIS }

    /** Fail closed: video remains disabled if the gear cannot be read. */
    fun parked(context: Context): Boolean? {
        if (!available(context)) return null
        return EVHardware.isVehicleInPark()
    }

    private fun poll() {
        val context = app ?: return
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.SWI69) return
        val percent = EVHardware.getVendorBatterySocPercent()?.toDouble() ?: return
        val range = (EVHardware.getVendorRangeKm() ?: EVHardware.getStandardRangeKm()?.roundToInt()) ?: return
        val fraction = percent / 100.0
        val currentKwh = EVHardware.getBatteryEnergyKwh()?.toDouble()
        val capacityKwh = EVHardware.getBatteryCapacityKwh()?.toDouble()
            ?: currentKwh?.takeIf { fraction > 0.0 }?.div(fraction)
        val maxRange = if (fraction > 0.0) (range / fraction).roundToInt() else range
        // iAP2 requires charge and capacity together. If AAOS does not expose Wh on this
        // firmware, use a normalized capacity so the ratio remains exactly the measured SOC.
        val reportedCapacityKwh = capacityKwh ?: 100.0
        val reportedCurrentKwh = currentKwh ?: reportedCapacityKwh * fraction
        latest = VehicleStatusSnapshot(
            rangeKm = range,
            rangeWarning = percent <= BydOutputSettings.lowChargePercent(context),
            batteryPercent = percent,
            currentChargeWh = (reportedCurrentKwh * 1000).roundToLong(),
            maxChargeWh = (reportedCapacityKwh * 1000).roundToLong(),
            maxRangeKm = maxRange,
            charging = EVHardware.isChargePortConnected() == true,
        )
        latestMillis = SystemClock.elapsedRealtime()
        Log.i(TAG, "SWI69 battery ${percent.roundToInt()}% range ${range}km")
    }
}
