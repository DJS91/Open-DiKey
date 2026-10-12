package com.sphy.airconcontroller.boot

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Samples a few cheap vehicle signals once a second and re-kicks [AdasAutoApplyCoordinator] when
 * one of them shows the car was power-cycled or is about to drive, in addition to the app-launch
 * and app-open triggers.
 *
 * The car's ECU resets ADAS toggles on its power cycles, but the app process can survive a quick
 * off/on without ever being closed, so the other triggers never fire. Only the *trigger* is
 * sampled here — the (expensive) profile check/apply runs only when an edge is seen, and every
 * check/retry it starts is capped by [AdasAutoApplyCoordinator]'s 1-minute limit.
 *
 * Triggers:
 *  - **Power on**: Bodywork `getPowerLevel()` rising, or Energy `getEnergyMode()` going from 0 (or
 *    unreadable) to READY (> 0). An unreadable -> readable transition counts as a rise because
 *    the getters may return sentinels while the car is off.
 *  - **Sleep gap**: the loop wakes up far later than scheduled, meaning the head unit suspended
 *    (or the process was frozen) while the app stayed alive.
 *  - **Drive**: either gearbox getter changing from another gear into Drive.
 *
 * Runs for the life of the host process (started from [DiKeyListenService.onCreate]).
 */
object VehicleStateWatcher {
    private const val TAG = "VehicleStateWatcher"
    private const val POLL_INTERVAL_MS = 1_000L

    /** An elapsed-realtime gap this large between ticks means the device slept or stalled. */
    private const val SLEEP_GAP_MS = 10_000L

    /** Ignore power edges this soon after the previous one (power level can flicker while starting). */
    private const val POWER_TRIGGER_COOLDOWN_MS = 10_000L

    /** Gearbox raw value for Drive (see [BydVehicleInfoController]'s decodeGear: 1=P 2=R 3=N 4=D 5=S 6=M). */
    private const val GEAR_DRIVE = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    /** Starts the sampling loop once per process; subsequent calls no-op. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            val sampler = Sampler(app)
            while (true) {
                runCatching { sampler.tick() }
                    .onFailure { Log.w(TAG, "tick failed: ${it.javaClass.simpleName}: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private class Sampler(private val app: Context) {
        private val controller = BydVehicleInfoController(app)
        private var lastTickAt = SystemClock.elapsedRealtime()

        private var gearObserved = false
        private var gearUnavailable = false
        private var lastGear: Int? = null
        private var lastAutoMode: Int? = null

        private var powerObserved = false
        private var lastPowerLevel: Int? = null
        private var lastEnergyMode: Int? = null
        private var lastPowerTriggerAt = 0L

        fun tick() {
            checkSleepGap()
            checkGear()
            checkPower()
            // Last, so an event trigger from this tick takes priority over the periodic check.
            AdasAutoApplyCoordinator.periodicCheck(app)
        }

        private fun checkSleepGap() {
            val now = SystemClock.elapsedRealtime()
            val gapMs = now - lastTickAt
            lastTickAt = now
            if (gapMs > SLEEP_GAP_MS) {
                record("sleep gap: loop stalled for ${gapMs / 1000}s (device likely suspended)")
                recheck("sleep-gap")
            }
        }

        private fun checkGear() {
            val reads = controller.readGear()
            if (reads == null) {
                if (!gearUnavailable) {
                    record("gearbox read unavailable (${controller.lastBindError ?: "no error"}); will keep retrying", warning = true)
                    gearUnavailable = true
                }
                return
            }
            if (gearUnavailable) record("gearbox read recovered: $reads")
            gearUnavailable = false

            if (!gearObserved) {
                gearObserved = true
                record("initial gearbox reading: $reads")
                if (reads.gear == GEAR_DRIVE || reads.autoMode == GEAR_DRIVE) recheck("gear-observed-drive")
            } else {
                if (reads.gear != lastGear || reads.autoMode != lastAutoMode) {
                    record("gear changed: $reads (was getGear=${lastGear ?: "—"} getGearboxAutoModeType=${lastAutoMode ?: "—"})")
                }
                if (enteredDrive(lastGear, reads.gear) || enteredDrive(lastAutoMode, reads.autoMode)) {
                    recheck("gear-shift-to-drive")
                }
            }
            lastGear = reads.gear
            lastAutoMode = reads.autoMode
        }

        private fun checkPower() {
            val reads = controller.readPowerState()
            val level = reads.powerLevel
            val energy = reads.energyMode
            if (!powerObserved) {
                if (level == null && energy == null) return
                powerObserved = true
                record("initial power state: $reads")
                lastPowerLevel = level
                lastEnergyMode = energy
                return
            }

            if (level != lastPowerLevel || energy != lastEnergyMode) {
                record("power state changed: $reads (was powerLevel=${lastPowerLevel ?: "—"} energyMode=${lastEnergyMode ?: "—"})")
            }
            val levelRose = level != null && (lastPowerLevel == null || level > lastPowerLevel!!)
            val ready = energy != null && energy > 0 && (lastEnergyMode == null || lastEnergyMode!! <= 0)
            lastPowerLevel = level
            lastEnergyMode = energy

            if (levelRose || ready) {
                val now = SystemClock.elapsedRealtime()
                if (lastPowerTriggerAt == 0L || now - lastPowerTriggerAt >= POWER_TRIGGER_COOLDOWN_MS) {
                    lastPowerTriggerAt = now
                    recheck(if (ready) "vehicle-ready" else "power-level-up")
                }
            }
        }

        private fun enteredDrive(previous: Int?, current: Int?): Boolean =
            previous != null && previous != GEAR_DRIVE && current == GEAR_DRIVE

        private fun recheck(reason: String) {
            record("$reason — re-checking ADAS profile")
            AdasAutoApplyCoordinator.ensureApplied(app, reason = reason)
        }

        private fun record(message: String, warning: Boolean = false) {
            if (warning) Log.w(TAG, message) else Log.i(TAG, message)
            AdasAutoApplyLog.append(app, "vehicle watcher: $message")
        }
    }
}
