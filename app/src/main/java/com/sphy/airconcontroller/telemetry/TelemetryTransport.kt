package com.sphy.airconcontroller.telemetry

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class TelemetryTransport(
    private val replies: Channel<String>,
    private val write: suspend (String) -> Boolean,
    private val ackTimeoutMs: Long = 20_000,
    private val resultTimeoutMs: Long = 75_000,
) {
    suspend fun send(url: String, json: String): String {
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        val data = urlBytes + json.toByteArray(Charsets.UTF_8)
        if (data.size > MAX_BYTES) return "Telemetry failed: payload too large"
        while (replies.tryReceive().isSuccess) { }
        val id = nextId.updateAndGet { if (it == Int.MAX_VALUE) 1 else it + 1 }
        suspend fun command(line: String, expected: String): String? {
            if (!write(line)) return "USB write failed"
            return withTimeoutOrNull(ackTimeoutMs) {
                while (true) {
                    val reply = replies.receive()
                    if (reply == "TELEMETRY ACK $id $expected") return@withTimeoutOrNull ""
                    if (reply.startsWith("TELEMETRY RESULT $id ")) {
                        return@withTimeoutOrNull reply.removePrefix("TELEMETRY RESULT $id ")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                ""
            } ?: "ESP32 response timed out"
        }
        var error = command("TELEMETRY BEGIN $id ${urlBytes.size} ${data.size - urlBytes.size}", "BEGIN")
        if (error != "") return "Telemetry failed: $error"
        for (chunk in data.asList().chunked(80)) {
            val hex = chunk.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            error = command("TELEMETRY DATA $id $hex", "DATA")
            if (error != "") return "Telemetry failed: $error"
        }
        error = command("TELEMETRY END $id", "END")
        if (error != "") return "Telemetry failed: $error"
        val result = withTimeoutOrNull(resultTimeoutMs) {
            while (true) {
                val reply = replies.receive()
                if (reply.startsWith("TELEMETRY RESULT $id ")) {
                    return@withTimeoutOrNull reply.removePrefix("TELEMETRY RESULT $id ")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        } ?: "ERROR ESP32 response timed out"
        return if (result.startsWith("OK ")) "Telemetry sent: HTTP ${result.removePrefix("OK ")}"
        else "Telemetry failed: $result"
    }

    companion object {
        private const val MAX_BYTES = 8192
        private val nextId = AtomicInteger(0)
    }
}
