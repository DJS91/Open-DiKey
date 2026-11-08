package com.sphy.airconcontroller.telemetry

import java.net.URI

object TelemetryConfig {
    fun validUrl(value: String): Boolean {
        if (value.toByteArray(Charsets.UTF_8).size !in 1..512 || value.any { it <= ' ' || it.code > 126 }) return false
        val uri = try {
            URI(value)
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: java.net.URISyntaxException) {
            return false
        }
        val host = uri.host ?: return false
        return uri.scheme == "https" && host.length in 1..253 && ':' !in host &&
            uri.userInfo == null && uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
    }
}
