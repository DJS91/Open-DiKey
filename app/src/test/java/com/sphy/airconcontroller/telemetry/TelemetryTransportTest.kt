package com.sphy.airconcontroller.telemetry

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTransportTest {
    @Test
    fun sendsFramedChunksAndWaitsForResult() = runBlocking {
        val replies = Channel<String>(64)
        val commands = mutableListOf<String>()
        val transport = TelemetryTransport(replies, { line ->
            commands += line
            val parts = line.split(' ')
            val id = parts[2]
            replies.send("TELEMETRY ACK $id ${parts[1]}")
            if (parts[1] == "END") replies.send("TELEMETRY RESULT $id OK 204")
            true
        })
        val url = "https://example.com/hook"
        val payload = "{\"value\":\"${"a".repeat(220)}\"}"
        assertEquals("Telemetry sent: HTTP 204", transport.send(url, payload))
        val header = commands.first().split(' ')
        assertEquals(url.toByteArray().size.toString(), header[3])
        assertEquals(payload.toByteArray().size.toString(), header[4])
        val reconstructed = commands.filter { it.startsWith("TELEMETRY DATA ") }
            .flatMap { line -> line.substringAfterLast(' ').chunked(2).map { it.toInt(16).toByte() } }
            .toByteArray().toString(Charsets.UTF_8)
        assertEquals(url + payload, reconstructed)
        assertTrue(commands.all { it.length <= 256 })
    }

    @Test
    fun failsOnTimeoutWithoutSendingData() = runBlocking {
        val commands = mutableListOf<String>()
        val transport = TelemetryTransport(Channel(4), { commands += it; true }, ackTimeoutMs = 10)
        assertTrue(transport.send("https://example.com", "{}").contains("timed out"))
        assertEquals(1, commands.size)
    }

    @Test
    fun reportsBridgeErrorAndUsbFailure() = runBlocking {
        val replies = Channel<String>(4)
        val transport = TelemetryTransport(replies, { line ->
            val id = line.split(' ')[2]
            replies.send("TELEMETRY RESULT $id ERROR BUSY")
            true
        })
        assertTrue(transport.send("https://example.com", "{}").contains("BUSY"))
        assertTrue(TelemetryTransport(Channel(4), { false }).send("https://example.com", "{}")
            .contains("USB write failed"))
    }

    @Test
    fun rejectsOversizeWithoutSendingAnyUsbCommands() = runBlocking {
        var written = false
        val transport = TelemetryTransport(Channel(4), { written = true; true })
        assertTrue(transport.send("https://example.com", "x".repeat(8192)).contains("too large"))
        assertEquals(false, written)
    }

    @Test
    fun acceptsExactly8192Bytes() = runBlocking {
        val replies = Channel<String>(4)
        val url = "https://example.com"
        val transport = TelemetryTransport(replies, { line ->
            val parts = line.split(' ')
            replies.send("TELEMETRY ACK ${parts[2]} ${parts[1]}")
            if (parts[1] == "END") replies.send("TELEMETRY RESULT ${parts[2]} OK 200")
            true
        })
        assertEquals("Telemetry sent: HTTP 200", transport.send(url, "x".repeat(8192 - url.length)))
    }

    @Test
    fun ignoresStaleRepliesForOtherTransfers() = runBlocking {
        val replies = Channel<String>(4)
        val transport = TelemetryTransport(replies, { line ->
            val id = line.split(' ')[2]
            replies.send("TELEMETRY ACK 0 BEGIN")
            replies.send("TELEMETRY RESULT $id ERROR BUSY")
            true
        })
        assertTrue(transport.send("https://example.com", "{}").contains("BUSY"))
    }
}
