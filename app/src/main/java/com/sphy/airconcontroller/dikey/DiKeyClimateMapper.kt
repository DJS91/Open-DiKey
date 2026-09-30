package com.sphy.airconcontroller.dikey

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sphy.airconcontroller.byd.BydAcController
import java.util.concurrent.Executors

/**
 * Pushing a DiKey button downwards (click) → vehicle climate (fixed).
 * Dial click toggles temp / fan on that side (fixed). Dial long-press and
 * other remappable presses are handled by [DiKeyUpMapper]. Rotate writes the value.
 *
 * While the key is connected, car-side climate changes are mirrored back onto the
 * dials in whichever mode (temp / fan) each dial is currently in. Climate off blanks
 * both dials without changing either dial's mode. All state here is main-thread only.
 */
class DiKeyClimateMapper(
    private val ac: BydAcController,
    private val dikey: DiKeyController,
    private val onLog: (String) -> Unit
) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var leftMode = DialMode.TEMP
    private var rightMode = DialMode.TEMP

    /** What each LCD currently shows (as far as we know); null forces a re-push. */
    private var shownLeft: DialTarget? = null
    private var shownRight: DialTarget? = null

    private var lastLocalInputAt = 0L
    private var polling = false
    private var pollInFlight = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            pollCar()
            main.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    fun handle(event: DiKeyEvent) {
        when (event) {
            is DiKeyEvent.Button -> handleButton(event)
            is DiKeyEvent.Encoder -> handleEncoder(event)
            is DiKeyEvent.Raw -> Unit
        }
    }

    /** Align with the dial types [DiKeyController] restores on connect. */
    fun seedModes(leftType: Int, rightType: Int) {
        DialMode.fromDisplayType(leftType)?.let { leftMode = it }
        DialMode.fromDisplayType(rightType)?.let { rightMode = it }
    }

    fun startSync() {
        shownLeft = null
        shownRight = null
        if (polling) {
            pollCar()
            return
        }
        polling = true
        main.post(pollTask)
    }

    fun stopSync() {
        polling = false
        main.removeCallbacks(pollTask)
        shownLeft = null
        shownRight = null
    }

    private fun handleButton(event: DiKeyEvent.Button) {
        if (event.direction != "DOWN") return
        if (event.event != "CLICK") return
        val action = buttonAction(event.logicalId) ?: return
        io.execute {
            ac.bind()
            val result = action.invoke()
            main.post {
                onLog("Climate · btn${event.logicalId} DOWN · ${result.method} → ${result.detail}")
                pollCar()
            }
        }
    }

    private fun buttonAction(logicalId: Int): (() -> BydAcController.CommandResult)? =
        when (logicalId) {
            1 -> { { ac.togglePower() } }
            2 -> { { ac.toggleCompressor() } }
            3 -> { { ac.cycleWindDirection() } }
            4 -> { { ac.toggleRecirc() } }
            5 -> { { ac.toggleAuto() } }
            6 -> { { ac.toggleFrontDefrost() } }
            7 -> { { ac.toggleRearWindowHeat() } }
            8 -> { { ac.toggleAirOnly() } }
            9 -> { { ac.toggleSync() } }
            10 -> { { ac.toggleMaxCool() } }
            else -> null
        }

    /** True while [left]'s LCD is blanked for climate off. */
    fun isShowingOff(left: Boolean): Boolean = shownOf(left)?.off == true

    private fun handleEncoder(event: DiKeyEvent.Encoder) {
        val left = event.side == "LEFT"
        if (event.event == "SINGLE_CLICK") {
            cycleDialMode(left)
            return
        }
        if (isShowingOff(left)) {
            if (event.event == "ROTATE_RIGHT" || event.event == "ROTATE_LEFT") {
                setShown(left, null)
                pollCar()
            }
            return
        }
        when (event.event) {
            "ROTATE_RIGHT", "ROTATE_LEFT" -> {
                setShown(left, DialTarget(event.displayType, event.value))
                val inferred = DialMode.fromDisplayType(event.displayType) ?: return
                lastLocalInputAt = SystemClock.elapsedRealtime()
                if (left) leftMode = inferred else rightMode = inferred
                applyDialValue(left, event.value)
            }
            else -> DialMode.fromDisplayType(event.displayType)?.let { inferred ->
                if (left) leftMode = inferred else rightMode = inferred
            }
        }
    }

    private fun cycleDialMode(left: Boolean) {
        val next = modeOf(left).next()
        if (left) leftMode = next else rightMode = next
        setShown(left, null)
        io.execute {
            val state = readState()
            main.post {
                if (state != null) {
                    pushSide(left, state)
                } else {
                    val type = displayType(left, next)
                    setShown(left, DialTarget(type.code, type.defaultValue))
                    dikey.sendDialDisplay(left, type.code, type.defaultValue)
                }
            }
        }
    }

    private fun applyDialValue(left: Boolean, value: Int) {
        when (modeOf(left)) {
            DialMode.TEMP -> io.execute {
                ac.bind()
                val temp = value.coerceIn(BydAcController.TEMP_MIN, BydAcController.TEMP_MAX)
                val result = if (left) ac.setPassengerTemp(temp) else ac.setDriverTemp(temp)
                main.post {
                    onLog(
                        "Climate · ${if (left) "passenger" else "driver"} temp $temp → ${result.detail}"
                    )
                }
            }
            DialMode.FAN -> io.execute {
                ac.bind()
                val result = ac.setFanLevel(value)
                main.post {
                    onLog("Climate · fan $value → ${result.detail}")
                }
            }
        }
    }

    private fun pollCar() {
        if (!dikey.isConnected || pollInFlight) return
        pollInFlight = true
        io.execute {
            val state = readState()
            main.post {
                pollInFlight = false
                if (state == null || !dikey.isConnected) return@post
                // The ECU reports the old setpoint for a moment after a dial write;
                // mirroring it back would make the dial jump backwards.
                if (SystemClock.elapsedRealtime() - lastLocalInputAt < LOCAL_INPUT_HOLD_MS) return@post
                pushSide(left = true, state = state)
                pushSide(left = false, state = state)
            }
        }
    }

    private fun readState(): BydAcController.DialState? {
        if (!ac.bind()) return null
        val state = ac.dialState()
        if (state.powerOn == null && state.driverTempC == null && state.fanLevel == null) return null
        return state
    }

    private fun pushSide(left: Boolean, state: BydAcController.DialState) {
        if (!dikey.isConnected) return
        val target = targetFor(left, state) ?: return
        if (target == shownOf(left)) return
        setShown(left, target)
        dikey.sendDialDisplay(left, target.displayType, target.value)
        val side = if (left) "LEFT" else "RIGHT"
        onLog(
            if (target.off) {
                "Climate sync · $side dial → off"
            } else {
                "Climate sync · $side dial → " +
                    "${DiKeyProtocol.displayTypeLabel(target.displayType)} = ${target.value}"
            }
        )
    }

    private fun targetFor(left: Boolean, state: BydAcController.DialState): DialTarget? {
        if (state.powerOn == false) {
            return DialTarget(DiKeyProtocol.DISPLAY_TYPE_BLANK, 0, off = true)
        }
        val mode = modeOf(left)
        val type = displayType(left, mode)
        val raw = when (mode) {
            DialMode.TEMP -> if (left) state.passengerTempC else state.driverTempC
            DialMode.FAN -> state.fanLevel
        } ?: return null
        return DialTarget(type.code, type.clamp(raw))
    }

    private fun modeOf(left: Boolean): DialMode = if (left) leftMode else rightMode

    private fun shownOf(left: Boolean): DialTarget? = if (left) shownLeft else shownRight

    private fun setShown(left: Boolean, target: DialTarget?) {
        if (left) shownLeft = target else shownRight = target
    }

    private fun displayType(left: Boolean, mode: DialMode): DialDisplayType =
        when (mode) {
            DialMode.TEMP -> if (left) DialDisplayType.PASSENGER_TEMP else DialDisplayType.DRIVER_TEMP
            DialMode.FAN -> if (left) DialDisplayType.PASSENGER_FAN else DialDisplayType.DRIVER_FAN
        }

    private data class DialTarget(val displayType: Int, val value: Int, val off: Boolean = false)

    private enum class DialMode {
        TEMP, FAN;

        fun next(): DialMode = when (this) {
            TEMP -> FAN
            FAN -> TEMP
        }

        companion object {
            fun fromDisplayType(code: Int): DialMode? =
                when (DialDisplayType.fromCode(code)) {
                    DialDisplayType.DRIVER_TEMP, DialDisplayType.PASSENGER_TEMP -> TEMP
                    DialDisplayType.DRIVER_FAN, DialDisplayType.PASSENGER_FAN -> FAN
                    else -> null
                }
        }
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 1_000L
        const val LOCAL_INPUT_HOLD_MS = 2_000L
    }
}
