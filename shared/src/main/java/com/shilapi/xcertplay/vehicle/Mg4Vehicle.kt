package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicVehicleCondition
import com.evsuite.hardware.telemetry.EnergyTelemetryReader
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Read-only MG firmware bridge. All values come from the EVHardware paths used by EVABRPUploader. */
object Mg4Vehicle : VehicleStatusProvider {
    private const val TAG = "DiPlay-MG4"
    private const val READ_MILLIS = 30_000L
    private const val STALE_MILLIS = 3 * 60_000L
    private const val INITIAL_READ_TIMEOUT_MILLIS = 8_000L
    private const val INITIAL_RETRY_MILLIS = 500L

    @Volatile private var app: Context? = null
    @Volatile private var latest: VehicleStatusSnapshot? = null
    @Volatile private var latestMillis = 0L
    @Volatile private var started = false
    @Volatile private var initialized = false
    @Volatile private var reader: EnergyTelemetryReader? = null
    private val firstReading = CountDownLatch(1)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diplay-mg4-vehicle").apply { isDaemon = true }
    }

    fun available(context: Context): Boolean {
        return runCatching { FirmwareInfo.isDetectedGenerationSupported() }
            .onFailure { Log.e(TAG, "Could not detect MG4 firmware", it) }
            .getOrDefault(false)
    }

    @Synchronized
    fun start(context: Context) {
        app = context.applicationContext
        if (started) {
            if (initialized) executor.execute(::poll)
            return
        }
        started = true
        executor.execute {
            try {
                // Use exactly the same reader as EVABRPUploader. Besides the AAOS/VHAL
                // fallbacks it connects SAIC's vehiclecharging service, which is the most
                // reliable source of SOC and range across supported MG firmware generations.
                reader = EnergyTelemetryReader(context.applicationContext)
                initialized = true
                primeFirstReading()
                executor.scheduleWithFixedDelay(::poll, READ_MILLIS, READ_MILLIS, TimeUnit.MILLISECONDS)
            } catch (error: Throwable) {
                Log.e(TAG, "EVHardware initialization failed; disabling vehicle data", error)
            } finally {
                firstReading.countDown()
            }
        }
    }

    override fun snapshot(): VehicleStatusSnapshot? {
        // Identification runs off the UI thread. Give the EVABRPUploader reader time to bind
        // before iAP2 permanently decides whether this CarPlay connection is an EV session.
        if (started && latest == null) {
            runCatching { firstReading.await(INITIAL_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        }
        return latest?.takeIf { SystemClock.elapsedRealtime() - latestMillis <= STALE_MILLIS }
    }

    /** Fail closed: video remains disabled if the gear cannot be read. */
    fun parked(context: Context): Boolean? {
        if (!available(context)) return null
        return runCatching {
            // On some MG firmware CarStateClient may return its unset value (0). The head unit's own
            // video apps use vehiclecondition gear=1 for P, so prefer that direct signal.
            SaicVehicleCondition.gearOrNull()
                ?.takeIf { it in 1..4 }
                ?.let { it == 1 }
                ?: EVHardware.isVehicleInPark()
        }
            .onFailure { Log.e(TAG, "Could not read MG4 gear", it) }
            .getOrNull()
    }

    private fun poll() {
        try {
            pollSafely()
        } catch (error: Throwable) {
            Log.e(TAG, "MG4 telemetry read failed", error)
        }
    }

    private fun primeFirstReading() {
        val deadline = SystemClock.elapsedRealtime() + INITIAL_READ_TIMEOUT_MILLIS
        do {
            pollSafely()
            if (latest != null) return
            Thread.sleep(INITIAL_RETRY_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
    }

    private fun pollSafely() {
        val context = app ?: return
        if (!FirmwareInfo.isDetectedGenerationSupported()) return
        val vehicle = reader?.read() ?: return
        val percent = vehicle.socPercent?.toDouble() ?: return
        val range = vehicle.rangeKm?.roundToInt() ?: return
        val fraction = percent / 100.0
        val currentKwh = vehicle.batteryEnergyKwh?.toDouble()
        val capacityKwh = vehicle.batteryCapacityKwh?.toDouble()
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
            charging = vehicle.chargePortConnected == true ||
                (vehicle.batteryPowerKw?.let { it < -0.3f } == true),
        )
        latestMillis = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "${FirmwareInfo.getDetectedString()} battery ${percent.roundToInt()}% range ${range}km",
        )
    }
}
