package com.shilapi.xcertplay.vehicle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.evsuite.hardware.telemetry.EnergyTelemetryReader
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** User-owned ABRP credentials and the opt-in switch for CarPlay-session uploads. */
object AbrpSettings {
    private const val FILE = "diplay_abrp"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_TOKEN = "token"
    const val DEFAULT_API_KEY = "8cfc314b-03cd-4efe-ab7d-4431cd8f2e2d"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun apiKey(context: Context) = prefs(context).getString(KEY_API_KEY, DEFAULT_API_KEY).orEmpty()
    fun token(context: Context) = prefs(context).getString(KEY_TOKEN, "").orEmpty()
    fun configured(context: Context) = apiKey(context).isNotBlank() && token(context).isNotBlank()

    fun save(context: Context, apiKey: String, token: String, enabled: Boolean) {
        prefs(context).edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_TOKEN, token.trim())
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
}

/** Uploads MG telemetry while the foreground CarPlay session keeps this process alive. */
object AbrpUploader {
    private const val TAG = "MG4CPlay-ABRP"
    private const val API_URL = "https://api.iternio.com/1/tlm/send"
    private const val UPLOAD_INTERVAL_SECONDS = 30L
    private const val TIMEOUT_MILLIS = 8_000
    private var executor: ScheduledExecutorService? = null
    private var reader: EnergyTelemetryReader? = null

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (!AbrpSettings.enabled(app) || !AbrpSettings.configured(app) || executor != null) return
        reader = EnergyTelemetryReader(app)
        executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "diplay-abrp-upload").apply { isDaemon = true }
        }.also { service ->
            service.scheduleWithFixedDelay({ upload(app) }, 0, UPLOAD_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
        Log.i(TAG, "ABRP upload started with CarPlay session")
    }

    @Synchronized
    fun stop() {
        executor?.shutdownNow()
        executor = null
        reader = null
        Log.i(TAG, "ABRP upload stopped")
    }

    private fun upload(context: Context) {
        try {
            val snapshot = reader?.read() ?: return
            val soc = snapshot.socPercent ?: return
            val payload = JSONObject().apply {
                put("utc", System.currentTimeMillis() / 1000)
                put("soc", soc.roundToInt())
                snapshot.speedKmh?.let { put("speed", it.roundToInt()) }
                snapshot.rangeKm?.let { put("est_battery_range", it.roundToInt()) }
                snapshot.batteryPowerKw?.let { put("power", round2(it)) }
                val charging = snapshot.chargePortConnected == true ||
                    (snapshot.batteryPowerKw?.let { it < -0.3f } == true)
                put("is_charging", if (charging) 1 else 0)
                snapshot.batteryPowerKw?.let { put("is_dcfc", if (charging && -it > 25f) 1 else 0) }
                snapshot.parked?.let { put("is_parked", if (it) 1 else 0) }
                snapshot.batteryCapacityKwh?.takeIf { it > 0f }?.let { put("capacity", round2(it)) }
                snapshot.batteryEnergyKwh?.takeIf { it > 0f }?.let { put("soe", round2(it)) }
                snapshot.odometerKm?.takeIf { it > 0f }?.let { put("odometer", it.roundToInt()) }
                snapshot.batteryTempCelsius?.takeIf(::plausibleTemperature)?.let { put("batt_temp", it.roundToInt()) }
                snapshot.cabinTempCelsius?.takeIf(::plausibleTemperature)?.let { put("cabin_temp", it.roundToInt()) }
                snapshot.climate.driverTargetCelsius?.takeIf(::plausibleTemperature)?.let { put("hvac_setpoint", it.roundToInt()) }
                snapshot.tirePressures.frontLeftKpa?.takeIf(::plausiblePressure)?.let { put("tire_pressure_fl", it.roundToInt()) }
                snapshot.tirePressures.frontRightKpa?.takeIf(::plausiblePressure)?.let { put("tire_pressure_fr", it.roundToInt()) }
                snapshot.tirePressures.rearLeftKpa?.takeIf(::plausiblePressure)?.let { put("tire_pressure_rl", it.roundToInt()) }
                snapshot.tirePressures.rearRightKpa?.takeIf(::plausiblePressure)?.let { put("tire_pressure_rr", it.roundToInt()) }
                lastLocation(context)?.let { location ->
                    put("lat", location.latitude)
                    put("lon", location.longitude)
                    if (location.hasAltitude()) put("elevation", location.altitude.roundToInt())
                    if (location.hasBearing()) put("heading", location.bearing.roundToInt())
                }
            }.toString()
            val body = "api_key=${encode(AbrpSettings.apiKey(context))}" +
                "&token=${encode(AbrpSettings.token(context))}&tlm=${encode(payload)}"
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setFixedLengthStreamingMode(body.toByteArray(StandardCharsets.UTF_8).size)
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                val code = connection.responseCode
                Log.i(TAG, "ABRP upload HTTP $code soc=${soc.roundToInt()} range=${snapshot.rangeKm?.roundToInt()}")
            } finally {
                connection.disconnect()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "ABRP upload failed: ${error.javaClass.simpleName}")
        }
    }

    @Suppress("MissingPermission")
    private fun lastLocation(context: Context): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return manager.getProviders(true).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { System.currentTimeMillis() - it.time <= 5 * 60_000L }.maxByOrNull { it.time }
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun round2(value: Float) = (value * 100f).roundToInt() / 100.0
    private fun plausibleTemperature(value: Float) = value > -50f && value < 80f && value != 0f
    private fun plausiblePressure(value: Float) = value in 50f..500f
}
