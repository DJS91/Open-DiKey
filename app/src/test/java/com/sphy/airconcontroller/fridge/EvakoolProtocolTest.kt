package com.sphy.airconcontroller.fridge

import org.junit.Assert.*
import org.junit.Test

class EvakoolProtocolTest {
    private val status = "/SC0/1/0,1,1,+04,-18,+05,-20,2,12.6,1"

    @Test fun encodesExactAsciiCommands() {
        assertEquals("/SC3/1/+04\n", EvakoolProtocol.temperatureCommand(1, 4))
        assertEquals("/SC5/1/-18\n", EvakoolProtocol.temperatureCommand(2, -18))
        assertEquals("/SC3/1/+00\n", EvakoolProtocol.temperatureCommand(1, 0))
        assertEquals("/SC5/1/-99\n", EvakoolProtocol.temperatureCommand(2, -99))
        assertEquals("/SC3/1/+99\n", EvakoolProtocol.temperatureCommand(1, 99))
        assertArrayEquals(
            byteArrayOf(47, 83, 67, 48, 47, 49, 47, 45, 48, 49, 10),
            EvakoolProtocol.STATUS_QUERY.toByteArray(Charsets.US_ASCII),
        )
    }

    @Test fun rejectsInvalidCommands() {
        assertThrows(IllegalArgumentException::class.java) { EvakoolProtocol.temperatureCommand(0, 4) }
        assertThrows(IllegalArgumentException::class.java) { EvakoolProtocol.temperatureCommand(3, 4) }
        assertThrows(IllegalArgumentException::class.java) { EvakoolProtocol.temperatureCommand(1, 100) }
        assertThrows(IllegalArgumentException::class.java) { EvakoolProtocol.temperatureCommand(1, -100) }
    }

    @Test fun decodesBothTemperaturesAndTargets() {
        val parsed = EvakoolProtocol.parseStatus(status)
        assertTrue(parsed.on)
        assertTrue(parsed.celsius)
        assertEquals(4, parsed.left)
        assertEquals(-18, parsed.right)
        assertEquals(5, parsed.leftTarget)
        assertEquals(-20, parsed.rightTarget)
        assertEquals("12.6", parsed.voltage)
        assertFalse(EvakoolProtocol.parseStatus(status.replace(",1,1,", ",0,2,")).celsius)
        assertTrue(EvakoolProtocol.parseStatus(status.replace(",1,1,", ",001,001,")).on)
    }

    @Test fun assemblesEveryPossibleNotificationSplit() {
        for (split in 0..status.length) {
            val frames = EvakoolProtocol.Frames()
            assertTrue(frames.append(status.take(split).toByteArray()).isEmpty())
            assertEquals(listOf(status), frames.append((status.drop(split) + "\n").toByteArray()))
        }
    }

    @Test fun handlesCoalescedFramesAndCrLf() {
        val frames = EvakoolProtocol.Frames()
        assertEquals(listOf(status, status), frames.append("$status\r\n$status\n".toByteArray()))
        assertNull(frames.finishUnterminated())
        assertEquals(listOf(status), frames.append("$status$status".toByteArray()))
        assertEquals(status, frames.finishUnterminated())
    }

    @Test fun handlesUnterminatedStatusAndFaults() {
        val frames = EvakoolProtocol.Frames()
        assertTrue(frames.append(status.toByteArray()).isEmpty())
        assertEquals(status, frames.finishUnterminated())
        assertTrue(frames.append("/SEE/1/1".toByteArray()).isEmpty())
        assertEquals("/SEE/1/1", frames.finishUnterminated())
        assertTrue(frames.append("/SCE/1/6".toByteArray()).isEmpty())
        assertEquals("/SCE/1/6", frames.finishUnterminated())
        val faultWithStatus = status.replace("/SC0/1/0", "/SEE/1/1")
        frames.append(faultWithStatus.toByteArray())
        assertEquals(faultWithStatus, frames.finishUnterminated())
        assertEquals(104, EvakoolProtocol.parseStatus(status.replace("+04", "104")).left)
    }

    @Test fun preservesIncompleteFinalFieldUntilItArrives() {
        val frames = EvakoolProtocol.Frames()
        frames.append(status.dropLast(1).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { frames.finishUnterminated() }
        assertEquals(listOf(status), frames.append("1\n".toByteArray()))
        assertNull(frames.finishUnterminated())
    }

    @Test fun parsesZeroTargetsAndTrailingComma() {
        val frame = status.replace("+05", "+00").replace("-20", "-00")
        val parsed = EvakoolProtocol.parseStatus("$frame,\r\n")
        assertEquals(0, parsed.leftTarget)
        assertEquals(0, parsed.rightTarget)
    }

    @Test fun rejectsIncompleteMalformedAndOversizedData() {
        val frames = EvakoolProtocol.Frames()
        frames.append(status.dropLast(1).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { frames.finishUnterminated() }
        frames.clear()
        assertThrows(IllegalArgumentException::class.java) { frames.append(ByteArray(2049) { 65 }) }
        frames.clear()
        assertTrue(frames.append(byteArrayOf(-1, 0)).isEmpty())
        assertNull(frames.finishUnterminated())
        for (invalid in listOf(
            status.replace("+04", "bad"), status.replace(",1,1,", ",x,1,"), "/SEE/1/1",
            status.substringBeforeLast(','),
        )) {
            assertThrows(IllegalArgumentException::class.java) { EvakoolProtocol.parseStatus(invalid) }
        }
    }

    @Test fun acceptsExtraFieldsAndUnknownUnitsLikeOfficialApp() {
        assertEquals(4, EvakoolProtocol.parseStatus("$status,extra").left)
        assertFalse(EvakoolProtocol.parseStatus(status.replace(",1,1,", ",1,7,")).celsius)
        assertEquals(-18, EvakoolProtocol.parseStatus(status.replace(",-18,", ", -18 ,")).right)
    }

    @Test fun matchesOfficialDeviceNames() {
        assertTrue(EvakoolProtocol.matchesName("DownUnder_1234"))
        assertTrue(EvakoolProtocol.matchesName("Cool Ride"))
        assertFalse(EvakoolProtocol.matchesName("DiKey"))
    }
}
