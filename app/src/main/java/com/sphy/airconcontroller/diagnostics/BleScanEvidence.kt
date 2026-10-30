package com.sphy.airconcontroller.diagnostics

/** Counts advertisements before any fridge-name or connectability filter. */
class BleScanEvidence {
    var requested = false
        private set
    var running = false
        private set
    var callbacks = 0
        private set
    var connectableCallbacks = 0
        private set
    var fridgeCallbacks = 0
        private set
    var failure: Int? = null
        private set
    var rejection: String? = null
        private set
    private val addresses = mutableSetOf<String>()
    val uniqueDevices: Int get() = addresses.size

    fun start() {
        requested = true
        running = true
        callbacks = 0
        connectableCallbacks = 0
        fridgeCallbacks = 0
        failure = null
        rejection = null
        addresses.clear()
    }

    fun record(address: String, connectable: Boolean, fridge: Boolean) {
        if (!running) return
        callbacks++
        if (connectable) connectableCallbacks++
        if (fridge) fridgeCallbacks++
        // Bound memory even in crowded locations. Raw callback counts remain exact.
        if (addresses.size < 512) addresses.add(address)
    }

    fun stop(error: Int? = null, reason: String? = null) {
        running = false
        failure = error
        rejection = reason
    }

    fun conclusion(): String {
        val error = failure
        return when {
            !requested -> "NOT TESTED: no diagnostic scan requested."
            rejection != null -> "FAILED: $rejection"
            error != null -> "FAILED: Android reported scan error $error (${failureName(error)})."
            callbacks > 0 -> "CONFIRMED: Android delivered $callbacks advertisement callbacks. BLE reception works."
            running -> "REQUESTED, NOT CONFIRMED: waiting for the first advertisement callback."
            else -> "INCONCLUSIVE: scan returned no callbacks. This does not prove that the radio scanned. Check permissions, Location, nearby BLE advertisers and vendor restrictions."
        }
    }

    companion object {
        fun failureName(code: Int): String = when (code) {
            1 -> "ALREADY_STARTED"
            2 -> "APPLICATION_REGISTRATION_FAILED"
            3 -> "INTERNAL_ERROR"
            4 -> "FEATURE_UNSUPPORTED"
            5 -> "OUT_OF_HARDWARE_RESOURCES"
            6 -> "SCANNING_TOO_FREQUENTLY"
            else -> "UNKNOWN"
        }
    }
}
