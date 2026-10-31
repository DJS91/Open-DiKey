package com.sphy.airconcontroller.fridge

import java.util.Locale

object EvakoolProtocol {
    const val SERVICE_UUID = "0000e530-1212-efde-1523-785feabcd123"
    const val WRITE_UUID = "0000e531-1212-efde-1523-785feabcd123"
    const val NOTIFY_UUID = "0000e532-1212-efde-1523-785feabcd123"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    const val STATUS_QUERY = "/SC0/1/-01\n"

    data class Status(
        val on: Boolean,
        val celsius: Boolean,
        val left: Int,
        val right: Int,
        val leftTarget: Int,
        val rightTarget: Int,
        val voltage: String,
        /** Compressor mode from field 9: the official app shows "2" as Max, anything else as Eco. */
        val max: Boolean = false,
    )

    /** Compressor mode: /SC6/1/001 = Eco, /SC6/1/002 = Max (from the official app). */
    fun modeCommand(max: Boolean): String = if (max) "/SC6/1/002\n" else "/SC6/1/001\n"

    fun temperatureCommand(compartment: Int, temperature: Int): String {
        require(compartment in 1..2) { "Invalid compartment" }
        require(temperature in -99..99) { "Temperature must be between -99 and 99" }
        val command = if (compartment == 1) "SC3" else "SC5"
        return String.format(Locale.US, "/%s/1/%+03d\n", command, temperature)
    }

    fun matchesName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return "downunder_" in lower || "coolride" in lower.replace(Regex("\\s+"), "")
    }

    fun parseStatus(frame: String): Status {
        // Mirror the official app: it consumes the first ten fields and ignores extras.
        val fields = frame.trim().removeSuffix(",").split(',').map { it.trim() }
        require(fields.size >= 10 && fields[0].startsWith("/SC0/")) { "Invalid status frame" }
        val power = fields[1].toIntOrNull()
        require(power != null) { "Invalid power status: ${fields[1]}" }
        fun temperature(index: Int): Int {
            require(fields[index].matches(Regex("[+-]?\\d{1,3}"))) { "Invalid temperature field $index: ${fields[index]}" }
            return fields[index].toInt()
        }
        return Status(
            on = power == 1,
            // The official app treats "1" as Celsius and anything else as Fahrenheit.
            celsius = fields[2] == "1",
            left = temperature(3),
            right = temperature(4),
            leftTarget = temperature(5),
            rightTarget = temperature(6),
            voltage = fields[8],
            max = fields[9].toIntOrNull() == 2,
        )
    }

    /** Notifications may split a message or contain more than one message. */
    class Frames {
        private var buffer = ""

        fun clear() { buffer = "" }

        fun append(bytes: ByteArray): List<String> {
            // Drop padding/NUL and other non-printable bytes rather than the whole notification.
            buffer += String(bytes.filter { it == 10.toByte() || it.toInt() in 32..126 }.toByteArray(), Charsets.US_ASCII)
            require(buffer.length <= 2048) { "Fridge response exceeded 2048 bytes" }
            val frames = mutableListOf<String>()
            while (buffer.isNotEmpty()) {
                val newline = buffer.indexOf('\n')
                val nextHeader = Regex("/(?:SC0|SEE|SCE)/").find(buffer, 1)?.range?.first ?: -1
                val end = listOf(newline, nextHeader).filter { it >= 0 }.minOrNull() ?: break
                buffer.substring(0, end).trim().takeIf { it.isNotEmpty() }?.let(frames::add)
                buffer = buffer.substring(end + if (end == newline) 1 else 0)
            }
            return frames
        }

        // The APK also accepts un-terminated CSV responses. The caller waits for a quiet
        // interval before trying this, so a partially received final field is not read early.
        fun finishUnterminated(): String? {
            if (buffer.isBlank()) return null
            val result = buffer.trim()
            if (result.startsWith("/SEE/") || result.startsWith("/SCE/")) {
                require(result.matches(Regex("/S(?:EE|CE)/1/[1-6](?:,[^\\r\\n]*)?"))) {
                    "Incomplete fault frame"
                }
            } else parseStatus(result)
            buffer = ""
            return result
        }
    }
}
