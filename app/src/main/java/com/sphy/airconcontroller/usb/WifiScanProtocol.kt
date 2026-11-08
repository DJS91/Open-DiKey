package com.sphy.airconcontroller.usb

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class WifiNetwork(val ssid: String, val rssi: Int, val secured: Boolean)

object WifiScanProtocol {
    fun parseResult(line: String): WifiNetwork? {
        if (!line.startsWith("WIFI SCAN RESULT ")) return null
        val parts = line.removePrefix("WIFI SCAN RESULT ").split(' ')
        if (parts.size != 3) return null
        val hex = parts[0]
        if (hex.isEmpty() || hex.length > 64 || hex.length % 2 != 0 ||
            hex.any { it !in '0'..'9' && it !in 'A'..'F' && it !in 'a'..'f' }
        ) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val value = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            if (value == 0) return null
            bytes[i] = value.toByte()
        }
        val ssid = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            return null
        }
        val rssi = parts[1].toIntOrNull() ?: return null
        val security = parts[2].toIntOrNull() ?: return null
        if (rssi !in -127..0 || security < 0) return null
        return WifiNetwork(ssid, rssi, security != 0)
    }

    fun addNetwork(networks: List<WifiNetwork>, network: WifiNetwork): List<WifiNetwork> {
        val existing = networks.firstOrNull { it.ssid == network.ssid }
        if (existing != null && existing.rssi >= network.rssi) return networks
        return (networks.filterNot { it.ssid == network.ssid } + network)
            .sortedByDescending { it.rssi }.take(100)
    }
}
