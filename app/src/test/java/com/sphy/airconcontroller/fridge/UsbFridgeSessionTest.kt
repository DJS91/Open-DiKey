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

    @Test fun rejectsMalformedHexAndBadTemperaturesAndReportsFaults() {
        ready()
        for (hex in listOf("", "F", "GG", "41".repeat(513))) {
            session.onLine("FRIDGE RX $hex", 100)
            assertNull(session.status)
            assertTrue(session.message.contains("Invalid fridge response"))
        }
        session.onLine("FRIDGE RX ${hex("${frame.replace("+04", "bad")}\n")}", 200)
        assertNull(session.status)
        assertTrue(session.message.contains("Invalid temperature field 3"))
        assertEquals("Invalid temperature field 3: bad", session.lastParseError)
        assertTrue(session.events.any { it.contains("parse failed") })
        session.onLine("FRIDGE RX ${hex("$frame\n/SEE/1/2\n")}", 300)
        assertNull(session.status)
        assertTrue(session.message.startsWith("Fridge fault"))
    }

    @Test fun recordsRawDataAndToleratesPaddingBytes() {
        ready()
        val bytes = "$frame\n".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        session.onLine("FRIDGE RX ${bytes.joinToString("") { "%02X".format(it.toInt() and 255) }}", 100)
        assertEquals(4, session.status?.left)
        assertEquals(1, session.rxCount)
        assertEquals("$frame\\n<00>", session.lastRx)
        assertTrue(session.events.any { it.contains("status parsed") })
    }

    @Test fun keepsDiagnosticsWhenSecondFrameInBatchIsBad() {
        ready()
        session.onLine("FRIDGE RX ${hex("garbage\n$frame\n")}", 100)
        assertEquals(-18, session.status?.right)
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

    private fun readyWithControl() {
        session.setEnabled(true, 1)
        session.onOpen(10)
        session.onLine("FRIDGE CAP CONTROL_V1", 20)
        session.onLine("FRIDGE STATE READY DownUnder_123 AA:BB:CC:DD:EE:FF", 30)
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 40)
    }

    @Test fun readOnlyFirmwareRefusesSetpoints() {
        ready()
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 40)
        assertNotNull(session.status)
        assertFalse(session.canControl)
        assertFalse(session.setTarget(1, 3, 50))
        assertTrue(writes.none { it.startsWith("FRIDGE SET") })
        assertTrue(session.targetNote!!.contains("firmware"))
    }

    @Test fun sendsSetpointAndConfirmsFromStatus() {
        readyWithControl()
        assertTrue(session.canControl)
        assertTrue(session.setTarget(1, 3, 50))
        assertEquals("FRIDGE SET 1 3", writes.last())
        assertFalse("one change at a time", session.setTarget(2, -19, 51))
        assertTrue(session.onLine("FRIDGE SETOK 1 3", 60))
        assertEquals(UsbFridgeSession.PendingState.SENT, session.pending!!.state)
        session.onLine("FRIDGE RX ${hex("/SC0/1/0,1,1,+04,-18,+03,-20,2,12.6,1\n")}", 70)
        assertNull(session.pending)
        assertEquals(3, session.status!!.leftTarget)
        assertTrue(session.targetNote!!.contains("confirmed"))
        assertTrue(session.setTarget(2, -19, 80))
        assertEquals("FRIDGE SET 2 -19", writes.last())
    }

    @Test fun setpointErrorsAndTimeoutsClearPending() {
        readyWithControl()
        assertTrue(session.setTarget(2, -21, 50))
        session.onLine("FRIDGE SETERR fridge not ready", 60)
        assertNull(session.pending)
        assertTrue(session.targetNote!!.contains("fridge not ready"))
        assertTrue(session.setTarget(2, -21, 70))
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 5_000)
        assertNotNull("unchanged target keeps waiting", session.pending)
        session.tick(15_100)
        assertNull(session.pending)
        assertTrue(session.targetNote!!.contains("not confirmed"))
    }

    @Test fun setpointNeedsLiveStatusAndValidRange() {
        session.setEnabled(true, 1)
        session.onOpen(10)
        session.onLine("FRIDGE CAP CONTROL_V1", 20)
        session.onLine("FRIDGE STATE READY DownUnder_123 AA:BB:CC:DD:EE:FF", 30)
        assertFalse(session.setTarget(1, 3, 40))
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 50)
        assertFalse(session.setTarget(1, 100, 60))
        assertTrue(writes.none { it.startsWith("FRIDGE SET") })
        session.onLine("FRIDGE STATE DISCONNECTED", 70)
        assertFalse(session.setTarget(1, 3, 80))
    }

    private fun readyWithMode() {
        session.setEnabled(true, 1)
        session.onOpen(10)
        session.onLine("FRIDGE CAP CONTROL_V2", 20)
        session.onLine("FRIDGE STATE READY DownUnder_123 AA:BB:CC:DD:EE:FF", 30)
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 40)
    }

    @Test fun controlV1FirmwareRefusesModeButAllowsSetpoints() {
        readyWithControl()
        assertTrue(session.canControl)
        assertFalse(session.canSetMode)
        assertFalse(session.setMode(true, 50))
        assertTrue(writes.none { it.startsWith("FRIDGE MODE") })
        assertTrue(session.targetNote!!.contains("firmware"))
    }

    @Test fun sendsModeAndConfirmsFromStatus() {
        readyWithMode()
        assertTrue(session.canControl)
        assertTrue(session.canSetMode)
        assertFalse(session.status!!.max)
        assertTrue(session.setMode(true, 50))
        assertEquals("FRIDGE MODE MAX", writes.last())
        assertFalse("one change at a time", session.setMode(false, 51))
        assertTrue(session.onLine("FRIDGE MODEOK MAX", 60))
        assertEquals(true, session.pendingMax)
        session.onLine("FRIDGE RX ${hex(frame.dropLast(1) + "2\n")}", 70)
        assertNull(session.pendingMax)
        assertTrue(session.status!!.max)
        assertTrue(session.targetNote!!.contains("confirmed"))
        assertTrue(session.setMode(false, 80))
        assertEquals("FRIDGE MODE ECO", writes.last())
    }

    @Test fun modeErrorsAndTimeoutsClearPending() {
        readyWithMode()
        assertTrue(session.setMode(true, 50))
        session.onLine("FRIDGE MODEERR fridge not ready", 60)
        assertNull(session.pendingMax)
        assertTrue(session.targetNote!!.contains("fridge not ready"))
        assertTrue(session.setMode(true, 70))
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 5_000)
        assertEquals("unchanged mode keeps waiting", true, session.pendingMax)
        session.tick(15_100)
        assertNull(session.pendingMax)
        assertTrue(session.targetNote!!.contains("not confirmed"))
    }

    private fun readyWithPower() {
        session.setEnabled(true, 1)
        session.onOpen(10)
        session.onLine("FRIDGE CAP CONTROL_V3", 20)
        session.onLine("FRIDGE STATE READY DownUnder_123 AA:BB:CC:DD:EE:FF", 30)
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 40)
    }

    @Test fun olderFirmwareRefusesPower() {
        readyWithMode()
        assertTrue(session.canSetMode)
        assertFalse(session.canSetPower)
        assertFalse(session.setPower(false, 50))
        assertTrue(writes.none { it.startsWith("FRIDGE POWER") })
        assertTrue(session.targetNote!!.contains("firmware"))
    }

    @Test fun sendsPowerAndConfirmsFromStatus() {
        readyWithPower()
        assertTrue(session.canControl)
        assertTrue(session.canSetMode)
        assertTrue(session.canSetPower)
        assertTrue(session.status!!.on)
        assertTrue(session.setPower(false, 50))
        assertEquals("FRIDGE POWER OFF", writes.last())
        assertFalse("one change at a time", session.setPower(true, 51))
        assertTrue(session.onLine("FRIDGE POWEROK OFF", 60))
        assertEquals(false, session.pendingOn)
        session.onLine("FRIDGE RX ${hex(frame.replace("/SC0/1/0,1,", "/SC0/1/0,0,") + "\n")}", 70)
        assertNull(session.pendingOn)
        assertFalse(session.status!!.on)
        assertTrue(session.setPower(true, 80))
        assertEquals("FRIDGE POWER ON", writes.last())
    }

    @Test fun powerErrorsAndTimeoutsClearPending() {
        readyWithPower()
        assertTrue(session.setPower(false, 50))
        session.onLine("FRIDGE POWERERR fridge not ready", 60)
        assertNull(session.pendingOn)
        assertTrue(session.targetNote!!.contains("fridge not ready"))
        assertTrue(session.setPower(false, 70))
        session.onLine("FRIDGE RX ${hex("$frame\n")}", 5_000)
        assertEquals("unchanged power keeps waiting", false, session.pendingOn)
        session.tick(15_100)
        assertNull(session.pendingOn)
        assertTrue(session.targetNote!!.contains("not confirmed"))
    }
}