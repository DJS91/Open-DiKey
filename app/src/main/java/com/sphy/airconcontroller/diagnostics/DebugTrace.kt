package com.sphy.airconcontroller.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded process-lifetime history; credentials sent to the bridge are never recorded. */
object DebugTrace {
    private const val MAX_LINES = 400
    private val usb = ArrayDeque<String>()
    private val dikey = ArrayDeque<String>()

    @Synchronized
    fun usb(line: String) = add(usb, line)

    @Synchronized
    fun dikey(line: String) = add(dikey, line)

    @Synchronized
    fun usbSnapshot(): List<String> = usb.toList()

    @Synchronized
    fun dikeySnapshot(): List<String> = dikey.toList()

    private fun add(target: ArrayDeque<String>, line: String) {
        val safe = when {
            line.contains("WIFI SET ", ignoreCase = true) -> "WiFi credentials command (redacted)"
            line.contains("TELEMETRY DATA ", ignoreCase = true) -> "Telemetry data command (redacted)"
            else -> line
        }
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        target.addLast("$time $safe")
        while (target.size > MAX_LINES) target.removeFirst()
    }
}
