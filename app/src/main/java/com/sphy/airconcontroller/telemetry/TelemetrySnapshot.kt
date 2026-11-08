package com.sphy.airconcontroller.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.BydAdasController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import java.time.Instant

class TelemetrySnapshot(private val context: Context) {
    private val adas = BydAdasController(context)
    private val climate = BydAcController(context)
    private val vehicle = BydVehicleInfoController(context)

    fun collect(includeLocation: Boolean): String {
        val (location, status) = location(includeLocation)
        return TelemetryPayload.create(
            Instant.now(), adas.snapshot(), climate.snapshot(), vehicle.snapshot(includeImu = false),
            location, status,
        )
    }

    private fun location(enabled: Boolean): Pair<TelemetryLocation?, String> {
        if (!enabled) return null to "disabled"
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null to "permission_denied"
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return null to "service_unavailable"
        return try {
            val providers = manager.getProviders(true).filter {
                fine || it != LocationManager.GPS_PROVIDER
            }
            val last = providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?: return null to "no_last_known_location"
            TelemetryLocation(
                last.latitude, last.longitude, if (last.hasAccuracy()) last.accuracy else null,
                last.provider, Instant.ofEpochMilli(last.time),
            ) to "last_known"
        } catch (e: SecurityException) {
            Log.w("Telemetry", "Last-known location unavailable", e)
            null to "permission_denied"
        } catch (e: IllegalArgumentException) {
            Log.w("Telemetry", "Last-known location provider unavailable", e)
            null to "provider_unavailable"
        }
    }
}
