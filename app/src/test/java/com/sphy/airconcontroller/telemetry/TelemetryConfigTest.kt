package com.sphy.airconcontroller.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryConfigTest {
    @Test
    fun acceptsOnlyBoundedHttpsUrlsWithoutCredentialsOrFragments() {
        assertTrue(TelemetryConfig.validUrl("https://example.com/api/webhook/secret?x=1"))
        listOf("", "http://example.com/hook", "https://user:pass@example.com/hook",
            "https://example.com/#fragment",             "https://example.com/a b", "https://example.com:0/hook", "https://[::1]/hook",
            "https://example.com/" + "a".repeat(513), "https:///hook",
        ).forEach { assertFalse(it, TelemetryConfig.validUrl(it)) }
    }
}
