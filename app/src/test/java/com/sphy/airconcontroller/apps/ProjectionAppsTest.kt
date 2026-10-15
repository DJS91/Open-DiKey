package com.sphy.airconcontroller.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionAppsTest {
    // Lines copied from Run Diagnostics reports taken on the car with CarPlay disconnected / connected.
    private val disconnected =
        "      80:B9:89:0F:11:0F {profile connection policy(A2DP=-1|A2DP_SINK=100|HEADSET=-1|HEADSET_CLIENT=100|HID_HOST=-1|PAN=-1)}"
    private val connected =
        "      80:B9:89:0F:11:0F {profile connection policy(A2DP=-1|A2DP_SINK=0|HEADSET=-1|HEADSET_CLIENT=0|HID_HOST=-1|PAN=-1)}"

    @Test
    fun phoneWithBluetoothAudioAndHandsFreeAllowedIsNotProjecting() {
        assertFalse(ProjectionApps.projectingFromPolicyLines(disconnected))
    }

    @Test
    fun phoneWithBluetoothAudioAndHandsFreeForbiddenIsProjecting() {
        assertTrue(ProjectionApps.projectingFromPolicyLines(connected))
    }

    @Test
    fun noBondedPhoneIsNotProjecting() {
        assertFalse(ProjectionApps.projectingFromPolicyLines(""))
    }

    @Test
    fun bothProfilesMustBeForbidden() {
        val onlyAudio = connected.replace("HEADSET_CLIENT=0", "HEADSET_CLIENT=100")
        val onlyCalls = connected.replace("A2DP_SINK=0", "A2DP_SINK=100")
        assertFalse(ProjectionApps.projectingFromPolicyLines(onlyAudio))
        assertFalse(ProjectionApps.projectingFromPolicyLines(onlyCalls))
    }

    @Test
    fun unsetPolicyIsNotTreatedAsForbidden() {
        // -1 means "unknown/unset", which is not the car switching the profiles off.
        val unset = connected.replace("A2DP_SINK=0", "A2DP_SINK=-1").replace("HEADSET_CLIENT=0", "HEADSET_CLIENT=-1")
        assertFalse(ProjectionApps.projectingFromPolicyLines(unset))
    }

    @Test
    fun anyOneOfSeveralPairedPhonesProjectingCounts() {
        assertTrue(ProjectionApps.projectingFromPolicyLines(disconnected + "\n" + connected))
    }
}
