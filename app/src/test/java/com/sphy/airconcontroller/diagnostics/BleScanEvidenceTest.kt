package com.sphy.airconcontroller.diagnostics

import org.junit.Assert.*
import org.junit.Test

class BleScanEvidenceTest {
    @Test fun requestAloneNeverClaimsRadioReception() {
        val evidence = BleScanEvidence()
        assertTrue(evidence.conclusion().startsWith("NOT TESTED"))
        evidence.start()
        assertTrue(evidence.conclusion().startsWith("REQUESTED, NOT CONFIRMED"))
        evidence.stop()
        assertTrue(evidence.conclusion().startsWith("INCONCLUSIVE"))
    }

    @Test fun countsUnknownAndNonConnectableAdvertisementsBeforeFiltering() {
        val evidence = BleScanEvidence()
        evidence.start()
        evidence.record("A", false, false)
        evidence.record("A", true, true)
        evidence.record("B", true, false)
        assertEquals(3, evidence.callbacks)
        assertEquals(2, evidence.uniqueDevices)
        assertEquals(2, evidence.connectableCallbacks)
        assertEquals(1, evidence.fridgeCallbacks)
        assertTrue(evidence.conclusion().startsWith("CONFIRMED"))
        evidence.stop()
        evidence.record("C", true, true)
        assertEquals(3, evidence.callbacks)
    }

    @Test fun failureIsExplicitAndNewScanResetsEvidence() {
        val evidence = BleScanEvidence()
        evidence.start()
        evidence.record("A", true, false)
        evidence.stop(2)
        assertTrue(evidence.conclusion().contains("APPLICATION_REGISTRATION_FAILED"))
        evidence.start()
        assertNull(evidence.failure)
        assertEquals(0, evidence.callbacks)
        assertEquals(0, evidence.uniqueDevices)
        assertEquals("SCANNING_TOO_FREQUENTLY", BleScanEvidence.failureName(6))
        assertEquals("UNKNOWN", BleScanEvidence.failureName(99))
    }

    @Test fun boundsUniqueDeviceMemoryWithoutDroppingCallbackCounts() {
        val evidence = BleScanEvidence()
        evidence.start()
        repeat(600) { evidence.record(it.toString(), false, false) }
        assertEquals(600, evidence.callbacks)
        assertEquals(512, evidence.uniqueDevices)
    }

    @Test fun synchronousRejectionIsFailureNotAnEmptyScan() {
        val evidence = BleScanEvidence()
        evidence.start()
        evidence.stop(reason = "Permission rejected")
        assertEquals("FAILED: Permission rejected", evidence.conclusion())
        evidence.start()
        assertNull(evidence.rejection)
        assertTrue(evidence.conclusion().startsWith("REQUESTED"))
    }
}
