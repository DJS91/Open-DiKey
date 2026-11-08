package com.sphy.airconcontroller.telemetry

import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.BydAdasController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class TelemetryLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val provider: String?,
    val timestamp: Instant,
)

object TelemetryPayload {
    private fun obj(vararg values: Pair<String, Any?>): JSONObject =
        JSONObject().apply { values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }

    fun create(
        at: Instant,
        adas: BydAdasController.Snapshot,
        climate: BydAcController.AcSnapshot,
        vehicle: BydVehicleInfoController.Snapshot,
        location: TelemetryLocation?,
        locationStatus: String,
    ): String {
        val b = vehicle.battery
        val d = vehicle.dynamics
        val t = vehicle.tyres
        return obj(
            "schema_version" to 1,
            "timestamp" to at.toString(),
            "adas" to obj(
                "bound" to adas.diPilotBound, "sdk_available" to adas.sdkInjected,
                "elka" to adas.elka, "elka_raw" to adas.elkaRaw,
                "lane_departure" to adas.laneDeparture?.name,
                "lane_departure_raw" to adas.laneDepartureRaw,
                "automatic_emergency_braking" to adas.aeb, "automatic_emergency_braking_raw" to adas.aebRaw,
                "driver_monitor" to adas.driverMonitor, "driver_monitor_raw" to adas.driverMonitorRaw,
            ),
            "climate" to obj(
                "bound" to climate.bound, "sdk_available" to climate.sdkInjected,
                "power_on" to climate.powerOn, "driver_temperature_c" to climate.driverTempC,
                "passenger_temperature_c" to climate.passengerTempC, "outside_temperature_c" to climate.outsideTempC,
                "fan_level" to climate.fanLevel, "control_mode_raw" to climate.controlMode,
                "cycle_mode_raw" to climate.cycleMode, "wind_mode_raw" to climate.windMode,
                "compressor_mode_raw" to climate.compressorMode, "max_cool" to climate.maxCool,
                "ventilation" to climate.ventilation, "front_defrost" to climate.frontDefrost,
                "rear_defrost" to climate.rearDefrost, "temperature_synced" to climate.tempSynced,
            ),
            "vehicle" to obj(
                "sdk_available" to vehicle.sdkInjected, "devices_bound" to JSONObject(vehicle.devicesBound),
                "battery" to obj(
                    "bound" to b.bound, "soc_pct" to b.socPct, "remaining_power_pct" to b.remainingPowerPct,
                    "remaining_usable_kwh" to b.remainingUsableKwh, "estimated_pack_kwh" to b.packEnergyKwh,
                    "ev_range_km" to b.evRangeKm, "fuel_pct" to b.fuelPct, "fuel_range_km" to b.fuelRangeKm,
                    "combined_range_km" to b.combinedRangeKm, "odometer_km" to b.odoKm,
                    "ev_distance_km" to b.evMilesKm, "hev_distance_km" to b.hevMilesKm,
                    "drive_time_hours" to b.driveTimeH, "average_speed_kmh" to b.avgSpeedKmh,
                    "lifetime_electric_kwh" to b.lifetimeElecKwh, "lifetime_electric_average_kwh_per_100km" to b.lifetimeElecAvg,
                    "lifetime_charged_kwh" to b.lifetimeChargedKwh, "lifetime_fuel_l" to b.lifetimeFuelL,
                    "lifetime_fuel_average_l_per_100km" to b.lifetimeFuelAvg, "state_of_health_pct" to b.sohPct,
                    "capacity_ah" to b.capacityAh, "trip_electric_raw" to b.tripElec,
                    "last_50km_ev_average_raw" to b.last50KmEvAvg, "trip_ev_average_raw" to b.tripEvAvg,
                    "energy_mode_raw" to b.energyMode, "operation_mode_raw" to b.operationMode,
                ),
                "dynamics" to obj(
                    "speed_kmh" to d.speedKmh, "accelerator_pct" to d.accelPedalPct, "brake_pct" to d.brakePedalPct,
                    "gear" to d.gearLabel, "gear_raw" to d.gearRaw, "park_brake_raw" to d.parkBrake,
                    "engine_power_kw" to d.enginePowerKw, "engine_rpm" to d.engineRpm,
                    "oil_level_raw" to d.oilLevel, "displacement_l" to d.displacementL,
                    "engine_code" to d.engineCode, "slope_raw" to d.slope, "ambient_light_raw" to d.ambientLight,
                    "steering_deg" to d.steeringDeg, "power_level_raw" to d.powerLevel,
                    "battery_12v_level_raw" to d.batt12v, "vin" to d.vin, "outside_temperature_c" to d.outsideTempC,
                ),
                "tyres" to obj(
                    "bound" to t.bound, "battery_state" to t.batteryState,
                    "system_state" to t.systemState, "temperature_state" to t.temperatureState,
                    "corners" to JSONArray().apply {
                        t.corners.forEach { c ->
                            put(obj("corner" to c.corner.name, "pressure_kpa" to c.pressureKpa,
                                "pressure_by_type_raw" to c.pressureByType, "temperature_raw" to c.temperature,
                                "pressure_state" to c.pressureState, "air_leak_state" to c.airLeakState,
                                "signal_state" to c.signalState))
                        }
                    },
                ),
            ),
            "location" to obj(
                "status" to locationStatus, "latitude" to location?.latitude, "longitude" to location?.longitude,
                "accuracy_m" to location?.accuracyMeters, "provider" to location?.provider,
                "timestamp" to location?.timestamp?.toString(),
            ),
        ).toString()
    }
}
