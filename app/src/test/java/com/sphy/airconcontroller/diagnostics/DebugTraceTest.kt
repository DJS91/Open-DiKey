package com.sphy.airconcontroller.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTraceTest {
    @Test
    fun telemetryChunksAreNeverRecorded() {
        DebugTrace.usb("TX TELEMETRY DATA 1 746f6b656e")
        val line = DebugTrace.usbSnapshot().last()
        assertTrue(line.contains("redacted"))
        assertFalse(line.contains("746f6b656e"))
    }

    @Test
    fun wifiCredentialsAreNeverRecorded() {
        DebugTrace.usb("TX WIFI SET 736563726574 70617373776f7264")
        val line = DebugTrace.usbSnapshot().last()
        assertTrue(line.contains("redacted"))
        assertFalse(line.contains("736563726574"))
        assertFalse(line.contains("70617373776f7264"))
    }

    @Test
    fun historyIsBounded() {
        repeat(450) { DebugTrace.dikey("test $it") }
        val lines = DebugTrace.dikeySnapshot()
        assertTrue(lines.size <= 400)
        assertTrue(lines.last().endsWith("test 449"))
        assertFalse(lines.any { it.endsWith("test 0") })
    }
}
