package com.sphy.airconcontroller.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiScanProtocolTest {
    @Test
    fun parsesUtf8NamesAndSecurity() {
        assertEquals(
            WifiNetwork("Café WiFi", -45, true),
            WifiScanProtocol.parseResult("WIFI SCAN RESULT 436166C3A92057694669 -45 3"),
        )
        assertEquals(WifiNetwork("open", -80, false), WifiScanProtocol.parseResult("WIFI SCAN RESULT 6f70656e -80 0"))
    }

    @Test
    fun rejectsMalformedResults() {
        listOf(
            "WIFI SCAN RESULT 61 -30",
            "WIFI SCAN RESULT 6 -30 0",
            "WIFI SCAN RESULT -1 -30 0",
            "WIFI SCAN RESULT 6100 -30 0",
            "WIFI SCAN RESULT FF -30 0",
            "WIFI SCAN RESULT 61 10 0",
            "WIFI SCAN RESULT 61 -40 -1",
            "61 -30 0",
        ).forEach { assertNull(it, WifiScanProtocol.parseResult(it)) }
    }

    @Test
    fun deduplicatesBySsidAndKeepsStrongestSorted() {
        var networks = emptyList<WifiNetwork>()
        networks = WifiScanProtocol.addNetwork(networks, WifiNetwork("A", -80, true))
        networks = WifiScanProtocol.addNetwork(networks, WifiNetwork("B", -55, false))
        networks = WifiScanProtocol.addNetwork(networks, WifiNetwork("A", -40, true))
        networks = WifiScanProtocol.addNetwork(networks, WifiNetwork("A", -90, false))
        assertEquals(listOf(WifiNetwork("A", -40, true), WifiNetwork("B", -55, false)), networks)
    }

    @Test
    fun capsResults() {
        val networks = (1..110).fold(emptyList<WifiNetwork>()) { list, i ->
            WifiScanProtocol.addNetwork(list, WifiNetwork("network$i", -i, true))
        }
        assertEquals(100, networks.size)
        assertEquals("network1", networks.first().ssid)
        assertEquals("network100", networks.last().ssid)
    }
}
