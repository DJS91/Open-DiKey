package com.sphy.airconcontroller.fridge

/**
 * Alert rules, free of Android types so they can be unit tested.
 *
 * - Disconnect: the fridge delivered readings, then none for [DISCONNECT_MS].
 * - Warm: while the fridge is on, a compartment's one-minute samples rise [WARM_RISES] times
 *   without falling, each at least 5 °C (9 °F) above target. A fridge still pulling down to
 *   target never alerts. Cleared once it is back within the margin.
 */
class FridgeAlerts {
    sealed class Event {
        object Disconnected : Event()
        object Reconnected : Event()
        data class Warm(val compartment: Int, val temperature: Int, val target: Int, val celsius: Boolean) : Event()
        data class WarmCleared(val compartment: Int) : Event()
    }

    private var everConnected = false
    private var disconnectedSince: Long? = null
    var disconnectAlerted = false
        private set
    private var lastMinute = -1L
    private val warmCount = IntArray(3)
    private val warmAlerted = BooleanArray(3)
    private val lastTemp = arrayOfNulls<Int>(3)

    /**
     * Feed every status change and periodic ticks. [elapsed] is a monotonic clock for the
     * disconnect timer; [wallMs] buckets the warm check into one sample per minute.
     */
    fun update(status: EvakoolProtocol.Status?, elapsed: Long, wallMs: Long): List<Event> {
        val events = mutableListOf<Event>()
        if (status != null) {
            everConnected = true
            disconnectedSince = null
            if (disconnectAlerted) {
                disconnectAlerted = false
                events += Event.Reconnected
            }
            val minute = wallMs / 60_000L
            if (minute != lastMinute) {
                // A skipped minute breaks the "in a row" run.
                if (lastMinute >= 0 && minute != lastMinute + 1) resetRuns()
                lastMinute = minute
                sampleWarm(status, events)
            }
        } else {
            resetRuns()
            lastMinute = -1
            if (everConnected) {
                val since = disconnectedSince ?: elapsed.also { disconnectedSince = it }
                if (!disconnectAlerted && elapsed - since >= DISCONNECT_MS) {
                    disconnectAlerted = true
                    events += Event.Disconnected
                }
            }
        }
        return events
    }

    /**
     * Readings are deliberately not being collected (e.g. the Android backup with no fridge
     * screen open), so don't treat the gap as a disconnect or break in a warm run.
     */
    fun pause() {
        disconnectedSince = null
        resetRuns()
        lastMinute = -1
    }

    private fun resetRuns() {
        warmCount.fill(0)
        lastTemp.fill(null)
    }

    private fun sampleWarm(status: EvakoolProtocol.Status, events: MutableList<Event>) {
        val margin = if (status.celsius) WARM_MARGIN_C else WARM_MARGIN_F
        for (c in 1..2) {
            val temp = if (c == 1) status.left else status.right
            val target = if (c == 1) status.leftTarget else status.rightTarget
            val previous = lastTemp[c]
            lastTemp[c] = temp
            val warm = status.on && temp - target >= margin
            when {
                // Cooling down (or back near target) is never a fault, even when well above target.
                !warm || (previous != null && temp < previous) -> warmCount[c] = 0
                previous != null && temp > previous -> warmCount[c]++
                // Unchanged (integer readings) neither counts nor breaks the run.
                else -> Unit
            }
            if (warmCount[c] >= WARM_RISES && !warmAlerted[c]) {
                warmAlerted[c] = true
                events += Event.Warm(c, temp, target, status.celsius)
            } else if (!warm && warmAlerted[c]) {
                warmAlerted[c] = false
                events += Event.WarmCleared(c)
            }
        }
    }

    companion object {
        const val DISCONNECT_MS = 2 * 60_000L
        const val WARM_RISES = 3
        const val WARM_MARGIN_C = 5
        const val WARM_MARGIN_F = 9
    }
}
