package com.sphy.airconcontroller.fridge

/** Read-only, namespaced extension of the DiKey USB bridge protocol. */
class UsbFridgeSession(private val send: (String) -> Boolean) {
    var changed: (() -> Unit)? = null
    var enabled = false
        private set
    var open = false
        private set
    var supported = false
        private set
    var confirmedOff = false
        private set
    var status: EvakoolProtocol.Status? = null
        private set
    var message = "Connect the ESP32 USB bridge. Fridge firmware update required."
        private set
    var lastFrame: String? = null
        private set
    var device = ""
        private set
    val safeForAndroid: Boolean get() = !open || confirmedOff
    /** Last completed ESP32 scan report: header line followed by FRIDGE SEEN lines. */
    var diagReport: List<String> = emptyList()
        private set
    var diagScanning = false
        private set
    /** Recent fridge-namespace traffic (excluding RX payloads), newest last. */
    val events: List<String> get() = eventLog.toList()
    private val eventLog = ArrayDeque<String>()
    private val diagBuilding = mutableListOf<String>()
    private val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    private var lastLogged = ""
    /** Diagnostics: count and printable form of raw fridge notifications since the link came up. */
    var rxCount = 0
        private set
    var lastRx: String? = null
        private set
    var lastParseError: String? = null
        private set
    private var ready = false
    private var frames = EvakoolProtocol.Frames()
    private var openedAt = 0L
    private var commandAt = 0L
    private var lastStatusAt = 0L
    private var lastBytesAt = 0L
    private var lastQueryAt = 0L
    private var lastEventAt = 0L
    private var awaitingState = false
    private var restarting = false

    fun onOpen(now: Long) {
        open = true
        supported = false
        confirmedOff = false
        clearReadings()
        lastFrame = null
        device = ""
        openedAt = now
        lastEventAt = now
        lastQueryAt = now
        message = "Checking ESP32 fridge firmware..."
        command("FRIDGE STATUS")
        changed?.invoke()
    }

    fun onClose() {
        open = false
        supported = false
        confirmedOff = false
        awaitingState = false
        restarting = false
        diagScanning = false
        diagBuilding.clear()
        if (lastLogged != "closed") record("USB bridge closed")
        lastLogged = "closed"
        clearReadings()
        message = "USB bridge disconnected. No live fridge readings."
        changed?.invoke()
    }

    fun setEnabled(value: Boolean, now: Long) {
        if (enabled == value) return
        enabled = value
        restarting = false
        confirmedOff = false
        clearReadings()
        if (open && supported) applyMode(now)
        changed?.invoke()
    }

    fun reconnect(now: Long) {
        clearReadings()
        restarting = enabled
        if (open) {
            supported = false
            confirmedOff = false
            openedAt = now
            message = "Checking ESP32 fridge firmware..."
            command("FRIDGE STATUS")
        } else message = "USB bridge disconnected. Connect it using the DiKey USB connection."
        changed?.invoke()
    }

    /** Read-only ESP32 scan report; [scan] runs a fresh 15 s scan first. Never writes to the fridge. */
    fun requestDiagnostics(scan: Boolean): Boolean {
        if (!open) return false
        val line = if (scan) "FRIDGE DIAG SCAN" else "FRIDGE DIAG"
        val sent = send(line)
        record(if (sent) "→ $line" else "→ $line (USB write failed)")
        if (sent && scan) diagScanning = true
        changed?.invoke()
        return sent
    }

    /** Returns true only for the fridge namespace, never DiKey RX/STATE/TX messages. */
    fun onLine(line: String, now: Long): Boolean {
        if (!line.startsWith("FRIDGE ")) return false
        if (!open) return true
        lastEventAt = now
        if (!line.startsWith("FRIDGE RX ") && !line.startsWith("FRIDGE SEEN ") &&
            line != "FRIDGE CAP READONLY_V1" && line != lastLogged
        ) {
            lastLogged = line
            record("← $line")
        }
        try {
            when {
                line.startsWith("FRIDGE DIAG SCANNING") -> diagScanning = true
                line.startsWith("FRIDGE DIAG BEGIN") -> {
                    diagBuilding.clear()
                    diagBuilding.add(line.removePrefix("FRIDGE DIAG BEGIN ").trim())
                }
                line.startsWith("FRIDGE SEEN ") ->
                    if (diagBuilding.size < 64) diagBuilding.add(line.removePrefix("FRIDGE SEEN "))
                line == "FRIDGE DIAG END" -> {
                    diagReport = diagBuilding.toList()
                    diagBuilding.clear()
                    diagScanning = false
                }
                line == "FRIDGE CAP READONLY_V1" -> {
                    if (!supported) {
                        supported = true
                        applyMode(now)
                    }
                }
                line.startsWith("FRIDGE CAP ") ->
                    fail("Unsupported ESP32 fridge capability: ${line.removePrefix("FRIDGE CAP ")}")
                line.startsWith("FRIDGE STATE ") -> receiveState(line.removePrefix("FRIDGE STATE "), now)
                line.startsWith("FRIDGE RX ") -> {
                    if (enabled && !restarting && supported && ready) {
                        val hex = line.removePrefix("FRIDGE RX ")
                        require(hex.isNotEmpty() && hex.length <= 1024 && hex.length % 2 == 0 &&
                            hex.all { it.digitToIntOrNull(16) != null }) { "Invalid fridge USB hex" }
                        val bytes = ByteArray(hex.length / 2) { index ->
                            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                        }
                        lastBytesAt = now
                        rxCount++
                        lastRx = printable(bytes)
                        if (rxCount <= 3) record("← fridge data #$rxCount: $lastRx")
                        frames.append(bytes).forEach { receiveFrameSafely(it, now) }
                    }
                }
                else -> fail("Unknown ESP32 fridge response: $line")
            }
        } catch (e: IllegalArgumentException) {
            fail("Invalid fridge response: ${e.message}")
        }
        changed?.invoke()
        return true
    }

    fun tick(now: Long) {
        if (!open) return
        when {
            !supported && now - openedAt >= 5_000 ->
                message = "ESP32 fridge support not confirmed. Flash the updated bridge firmware; Android BLE remains available without the USB bridge."
            awaitingState && now - commandAt >= 5_000 -> {
                clearReadings()
                confirmedOff = false
                message = "ESP32 did not acknowledge fridge ${if (enabled && !restarting) "ON" else "OFF"}. Reconnect USB or retry; Android BLE waits for OFF."
            }
            now - lastEventAt >= 25_000 -> {
                clearReadings()
                confirmedOff = false
                message = "ESP32 fridge messages timed out. Check USB and reconnect."
            }
            enabled && ready && now - lastStatusAt >= 20_000 -> {
                status = null
                message = "No fresh fridge status for 20 seconds. Waiting for ESP32 reconnection."
            }
        }
        if (ready && enabled && lastBytesAt != 0L && now - lastBytesAt >= 500) {
            lastBytesAt = 0
            try {
                frames.finishUnterminated()?.let { receiveFrame(it, now) }
            } catch (e: IllegalArgumentException) {
                // Incomplete frames may continue in a later notification; never display them.
                status = null
                lastParseError = e.message
                message = "Incomplete fridge response: ${e.message}"
                record("✗ incomplete/unrecognised response (${e.message})")
            }
        }
        if (supported && now - lastQueryAt >= 10_000) {
            lastQueryAt = now
            command("FRIDGE STATUS")
        }
        changed?.invoke()
    }

    private fun applyMode(now: Long) {
        confirmedOff = false
        commandAt = now
        awaitingState = true
        val turnOn = enabled && !restarting
        message = if (turnOn) "ESP32 searching for EVAKOOL (read-only)..."
        else if (restarting) "Stopping ESP32 fridge link before reconnecting..."
        else "Waiting for ESP32 fridge OFF before Android BLE..."
        command(if (turnOn) "FRIDGE ON" else "FRIDGE OFF")
    }

    private fun command(line: String) {
        if (line != "FRIDGE STATUS") record("→ $line")
        if (!send(line)) fail("USB write failed: $line. Reconnect USB and retry.")
    }

    private fun record(entry: String) {
        eventLog.addLast("${clock.format(java.util.Date())} $entry")
        while (eventLog.size > 60) eventLog.removeFirst()
    }

    private fun receiveState(value: String, now: Long) {
        val state = value.substringBefore(' ')
        val detail = value.substringAfter(' ', "")
        require(state in setOf("OFF", "SCANNING", "CONNECTING", "READY", "DISCONNECTED", "ERROR")) {
            "Unknown fridge state: $value"
        }
        if (!supported) return
        if (state == "OFF") {
            clearReadings()
            if (enabled && restarting) {
                restarting = false
                applyMode(now)
                return
            }
            confirmedOff = !enabled
            if (!enabled) awaitingState = false
            message = if (enabled) "ESP32 fridge is OFF. Tap Reconnect to retry." else "ESP32 fridge OFF. Android BLE backup available."
            return
        }
        confirmedOff = false
        if (!enabled || restarting) {
            // A late READY must not re-enable a link after the user chose Android BLE.
            command("FRIDGE OFF")
            return
        }
        awaitingState = false
        if (state != "READY") clearReadings()
        else if (!ready) {
            ready = true
            lastStatusAt = now
            rxCount = 0
            lastRx = null
            lastParseError = null
        }
        if (state == "READY" && detail.isNotBlank()) device = detail
        if (state != "READY" || status == null) message = when (state) {
            "READY" -> "ESP32 connected; waiting for fridge readings (read-only)."
            "SCANNING" -> "ESP32 scanning for EVAKOOL (read-only)."
            "CONNECTING" -> "ESP32 connecting to fridge..."
            "DISCONNECTED" -> "ESP32 fridge disconnected; retrying."
            else -> "ESP32 fridge error."
        } + if (detail.isBlank()) "" else "\n$detail"
    }

    private fun receiveFrameSafely(frame: String, now: Long) {
        try {
            receiveFrame(frame, now)
        } catch (e: IllegalArgumentException) {
            lastFrame = frame
            lastParseError = e.message
            status = null
            message = "Unrecognised fridge response: ${e.message}"
            record("✗ parse failed (${e.message}): $frame")
        }
    }

    private fun printable(bytes: ByteArray): String = bytes.joinToString("") { b ->
        val c = b.toInt() and 255
        when (c) {
            10 -> "\\n"
            13 -> "\\r"
            in 32..126 -> c.toChar().toString()
            else -> "<%02X>".format(c)
        }
    }.take(240)

    private fun receiveFrame(frame: String, now: Long) {
        lastFrame = frame
        if (frame.startsWith("/SEE/") || frame.startsWith("/SCE/")) {
            require(frame.matches(Regex("/S(?:EE|CE)/1/[1-6](?:,[^\\r\\n]*)?"))) { "Invalid fridge fault" }
            status = null
            message = "Fridge fault: $frame"
        } else {
            status = EvakoolProtocol.parseStatus(frame)
            lastStatusAt = now
            if (lastParseError != null || rxCount <= 3) record("✓ status parsed: $frame")
            lastParseError = null
            message = if (status?.on == true) "Live via ESP32 USB - read-only"
            else "Fridge reports power off (ESP32 USB, read-only)"
        }
    }

    private fun clearReadings() {
        ready = false
        status = null
        lastBytesAt = 0
        frames.clear()
    }

    private fun fail(reason: String) {
        status = null
        frames.clear()
        message = reason
    }
}
