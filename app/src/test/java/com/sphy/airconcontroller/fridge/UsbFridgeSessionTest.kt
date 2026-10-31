package com.sphy.airconcontroller.fridge

import org.junit.Assert.*
import org.junit.Test

class UsbFridgeSessionTest {
    private val writes = mutableListOf<String>()
    private val session = UsbFridgeSession { writes.add(it); true }
    private val frame = "/SC0/1/0,1,1,+04,-18,+05,-20,2,12.6,1"
    private fun hex(text: String) = text.toByteArray(Charsets.US_ASCII)
        .joinToString("") { "%02X".format(it.toInt() and 255) }

    private fun ready() {
        session.setEnabled(true, 1)
        session.onOpen(10)
        session.onLine("FRIDGE CAP READONLY_V1", 20)
        session.onLine("FRIDGE STATE READY DownUnder_123 AA:BB:CC:DD:EE:FF", 30)
    }

    @Test fun requiresCapabilityAndNeverEmitsFridgeWritesOrDikeyCommands() {
        session.setEnabled(true, 1)
        session.onOpen(10)
        assertEquals(listOf("FRIDGE STATUS"), writes)
        session.onLine("FRIDGE CAP READONLY_V1", 20)
        assertEquals("FRIDGE ON", writes.last())
        session.tick(10_020)
        session.setEnabled(false, 10_021)
        assertEquals("FRIDGE OFF", writes.last())
        assertTrue(writes.all { it in setOf("FRIDGE STATUS", "FRIDGE ON", "FRIDGE OFF") })
    }

    @Test fun parsesEsp32ScanReportWithoutChangingFridgeState() {
        ready()
        val before = session.message
        assertTrue(session.requestDiagnostics(scan = true))
        assertEquals("FRIDGE DIAG SCAN", writes.last())
        assertTrue(session.diagScanning)
        assertTrue(session.onLine("FRIDGE DIAG SCANNING 15", 40))
        assertTrue(session.onLine("FRIDGE DIAG BEGIN scanning=1 adv=2 unique=2", 50))
        assertTrue(session.onLine("FRIDGE SEEN aa:bb:cc:dd:ee:ff -60 C M 1 DownUnder_1", 51))
        assertTrue(session.onLine("FRIDGE SEEN 11:22:33:44:55:66 -80 N - 1 (no name)", 52))
        assertTrue(session.onLine("FRIDGE DIAG END", 53))
        assertFalse(session.diagScanning)
        assertEquals(3, session.diagReport.size)
        assertEquals("scanning=1 adv=2 unique=2", session.diagReport.first())
        assertEquals(before, session.message)
        assertTrue(session.events.any { it.endsWith("→ FRIDGE DIAG SCAN") })
        assertTrue(writes.none { it.startsWith("FRIDGE RX") || it.startsWith("TX") })
    }

    @Test fun diagnosticsRequireOpenBridge() {
        assertFalse(session.requestDiagnostics(scan = false))
        assertTrue(writes.isEmpty())
    }

    @Test fun routesOnlyNamespacedMessages() {
        ready()
        for (line in listOf("RX AABB", "TXOK", "TXERR error", "STATE READY", "HB READY", "ERR unknown")) {
            assertFalse(session.onLine(line, 40))
        }
        assertTrue(session.onLine("FRIDGE invalid", 40))
        assertTrue(session.message.startsWith("Unknown"))
    }

    @Test fun parsesFragmentedStatusAndUnterminatedFramesWithoutGuessing() {
        ready()
        session.onLine("FRIDGE RX ${hex(frame.take(15))}", 100)
        session.tick(400)
        assertNull(session.status)
        session.onLine("FRIDGE RX ${hex(frame.drop(15))}", 500)
        session.tick(999)
        assertNull(session.status)
        session.tick(1000)
        assertEquals(4, session.status?.left)
        assertEquals(-18, session.status?.right)
        assertEquals(5, session.status?.leftTarget)
        assertEquals(-20, session.status?.rightTarget)
        assertEquals(frame, session.lastFrame)
    }

    @Test fun staleDisconnectAndUsbCloseRemoveLiveReadings() {
        ready()
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 100)
        assertNotNull(session.status)
        session.onLine("FRIDGE STATE READY same-device", 19_000)
        session.tick(20_100)
        assertNull(session.status)
        assertTrue(session.message.contains("20 seconds"))
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 20_200)
        assertNotNull(session.status)
        session.onLine("FRIDGE STATE DISCONNECTED link lost", 20_300)
        assertNull(session.status)
        assertTrue(session.message.contains("retrying"))
        session.onClose()
        assertNull(session.status)
        assertTrue(session.safeForAndroid)
    }

    @Test fun androidBackupWaitsForOffAcknowledgementAndIgnoresLateFridgeData() {
        ready()
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 100)
        session.setEnabled(false, 200)
        assertNull(session.status)
        assertFalse(session.safeForAndroid)
        session.onLine("FRIDGE STATE READY late-connect", 300)
        assertEquals("FRIDGE OFF", writes.last())
        assertFalse(session.safeForAndroid)
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 400)
        assertNull(session.status)
        session.onLine("FRIDGE STATE OFF", 500)
        assertTrue(session.safeForAndroid)
        session.onOpen(600)
        assertFalse(session.safeForAndroid)
    }

    @Test fun oldFirmwareAndMissingOffAcknowledgementAreExplicit() {
        session.onOpen(10)
        session.tick(5010)
        assertTrue(session.message.contains("Flash"))
        assertFalse(session.safeForAndroid)
        session.onLine("FRIDGE CAP READONLY_V1", 5020)
        session.tick(10_020)
        assertTrue(session.message.contains("acknowledge"))
        assertFalse(session.safeForAndroid)
    }

    @Test fun rejectsMalformedHexInvalidUnitsAndReportsFaults() {
        ready()
        for (hex in listOf("", "F", "GG", "FF", "41".repeat(513))) {
            session.onLine("FRIDGE RX $hex", 100)
            assertNull(session.status)
            assertTrue(session.message.contains("Invalid fridge response"))
        }
        session.onLine("FRIDGE RX ${hex("${frame.replace(",1,1,", ",1,7,")}\n")}", 200)
        assertNull(session.status)
        assertTrue(session.message.contains("Unknown temperature unit"))
        session.onLine("FRIDGE RX ${hex("$frame\n/SEE/1/2\n")}", 300)
        assertNull(session.status)
        assertTrue(session.message.startsWith("Fridge fault"))
    }

    @Test fun usbWriteFailureIsVisible() {
        val failed = UsbFridgeSession { false }
        failed.onOpen(10)
        assertTrue(failed.message.contains("USB write failed"))
        assertFalse(failed.safeForAndroid)
        assertNull(failed.status)
    }

    @Test fun reconnectRechecksCapabilitiesAndNeverReplaysAnyTarget() {
        ready()
        session.reconnect(100)
        assertFalse(session.supported)
        assertNull(session.status)
        assertEquals("FRIDGE STATUS", writes.last())
        session.onLine("FRIDGE CAP READONLY_V1", 200)
        assertEquals("FRIDGE OFF", writes.last())
        session.onLine("FRIDGE STATE READY old-link", 250)
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 260)
        assertNull(session.status)
        session.onLine("FRIDGE STATE OFF", 300)
        assertEquals("FRIDGE ON", writes.last())
        assertFalse(session.safeForAndroid)
    }

    @Test fun inactiveOrAndroidModeKeepsBridgeFridgeOffAcrossReboots() {
        session.onOpen(10)
        session.onLine("FRIDGE CAP READONLY_V1", 20)
        assertEquals("FRIDGE OFF", writes.last())
        session.onLine("FRIDGE STATE OFF", 30)
        assertTrue(session.safeForAndroid)
        val commands = writes.size
        session.onLine("FRIDGE CAP READONLY_V1", 40)
        assertEquals(commands, writes.size)
        session.onOpen(50)
        assertFalse(session.safeForAndroid)
        session.onLine("FRIDGE CAP READONLY_V1", 60)
        assertEquals("FRIDGE OFF", writes.last())
        session.onLine("FRIDGE STATE OFF", 70)
        assertTrue(session.safeForAndroid)
    }

    @Test fun missingInitialStatusAndSilentUsbAreNotLive() {
        ready()
        session.onLine("FRIDGE STATE READY same-device", 19_000)
        session.tick(20_030)
        assertNull(session.status)
        assertTrue(session.message.contains("20 seconds"))
        session.tick(44_000)
        assertTrue(session.message.contains("timed out"))
        assertFalse(session.safeForAndroid)
    }
}
