package com.sphy.airconcontroller.dikey

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderModeConfigTest {
    private fun hex(bytes: ByteArray) = with(DiKeyProtocol) { bytes.toHex() }

    // Frames copied from a Run Diagnostics report on the car (what the app has always sent).
    @Test
    fun defaultRightAllowListMatchesWhatTheCarHasAlwaysBeenSent() {
        val frame = DiKeyProtocol.buildEncoderModeConfig(left = false, DialDisplayType.rightCycleTypes)
        assertEquals("AA 55 13 06 02 03 00 00 00 00 00 00 00 00 00 00 00 00 00 1D", hex(frame))
    }

    @Test
    fun defaultLeftAllowListMatchesWhatTheCarHasAlwaysBeenSent() {
        val frame = DiKeyProtocol.buildEncoderModeConfig(left = true, DialDisplayType.leftCycleTypes)
        assertEquals("AA 55 13 07 00 00 04 05 00 00 00 00 00 00 00 00 00 00 00 22", hex(frame))
    }

    @Test
    fun allowNoneUsesTheFirstSlotWhenTypeTwoIsNotAllowed() {
        val frame = DiKeyProtocol.buildEncoderModeConfig(left = true, DialDisplayType.leftCycleTypes, allowNone = true)
        assertEquals("AA 55 13 07 01 00 04 05 00 00 00 00 00 00 00 00 00 00 00 23", hex(frame))
    }

    @Test
    fun allowNoneNeverOverwritesDriverTemp() {
        val withNone = DiKeyProtocol.buildEncoderModeConfig(left = false, DialDisplayType.rightCycleTypes, allowNone = true)
        val without = DiKeyProtocol.buildEncoderModeConfig(left = false, DialDisplayType.rightCycleTypes)
        assertEquals(hex(without), hex(withNone))
    }

    @Test
    fun wideListAllowsDriverAndPassengerTempAndFan() {
        val wide = setOf(2, 3, 4, 5)
        val frame = DiKeyProtocol.buildEncoderModeConfig(left = true, wide)
        assertEquals("AA 55 13 07 02 03 04 05 00 00 00 00 00 00 00 00 00 00 00 27", hex(frame))
    }
}
