package com.sphy.airconcontroller.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.BydAdasController
import com.sphy.airconcontroller.byd.BydTyreController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TelemetryPayloadTest {
    @Test
    fun encodesSectionsAndUnavailableValues() {
        val adas = BydAdasController.Snapshot(false, false, null, null, null, null, null, null, null, null, null)
        val ac = BydAcController.AcSnapshot(false, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null)
        val tyres = BydTyreController.TyreSnapshot(false, false, null, emptyList(), null, null, null)
        val vehicle = BydVehicleInfoController.Snapshot(false, null, BydVehicleInfoController.BatteryInfo(false),
            BydVehicleInfoController.DynamicsInfo(vin = "example"), BydVehicleInfoController.ImuInfo(),
            tyres, emptyMap())
        val json = JSONObject(TelemetryPayload.create(
            Instant.parse("2026-01-01T00:00:00Z"), adas, ac, vehicle, null, "permission_denied",
        ))
        assertEquals(1, json.getInt("schema_version"))
        assertEquals("2026-01-01T00:00:00Z", json.getString("timestamp"))
        assertTrue(json.getJSONObject("adas").isNull("elka"))
        assertTrue(json.getJSONObject("climate").isNull("driver_temperature_c"))
        assertEquals("example", json.getJSONObject("vehicle").getJSONObject("dynamics").getString("vin"))
        assertEquals("permission_denied", json.getJSONObject("location").getString("status"))
        assertTrue(json.getJSONObject("location").isNull("latitude"))
    }
}
