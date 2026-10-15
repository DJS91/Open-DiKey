package com.sphy.airconcontroller.boot

import android.content.Context
import android.os.SystemClock
import com.sphy.airconcontroller.byd.BydAdasController
import com.sphy.airconcontroller.byd.BydAdasController.ProfileCheck
import com.sphy.airconcontroller.storage.AdasCustomProfile
import com.sphy.airconcontroller.storage.AdasEditMode
import com.sphy.airconcontroller.storage.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Applies the saved Custom ADAS profile on process start, retrying at a fixed interval until it
 * sticks, and is safe to (re)trigger from multiple call sites — currently cold boot/app launch
 * ([DiKeyListenService]), every app open ([com.sphy.airconcontroller.MainActivity.onStart]), and
 * the vehicle-state triggers in [VehicleStateWatcher] (power-on, device sleep/wake, shift to Drive),
 * plus a quiet [periodicCheck] safety net that re-verifies every few seconds while the app runs.
 *
 * [KeepAliveNotificationListener] can spin the process (and [DiKeyListenService]) up as soon as
 * system_server rebinds it after boot/standby — often well before the car's DiPilot/CarAssist/DMS
 * binder services have registered. [DiKeyListenService.onCreate] only runs once per process, so
 * if that early attempt exhausts its retries, simply opening the app later did nothing: the
 * process (and its `Application.onCreate`) already exists and nothing re-kicked the apply. Call
 * [ensureApplied] from any likely-ready moment (service start, activity start).
 *
 * Critically, the head unit can *sleep and wake* without ever killing our process — so a prior
 * success this process lifetime does not mean the car's ADAS state is still correct: BYD's own
 * ECU resets these toggles on its power cycles (see [BydAdasController] class doc), and our
 * process has no way to observe that happening while asleep. So every call re-verifies the car's
 * actual current state against the target profile via [BydAdasController.matchesProfile] — a
 * previous success is only ever used to skip the (potentially expensive) retry loop when the
 * quick read-only check already confirms nothing needs to change; it's never trusted blindly.
 * Only one check/apply runs at a time; triggers that arrive meanwhile coalesce into a single
 * pending check that runs afterward. Every check/retry run is hard-capped at
 * [MAX_RETRY_DURATION_MS] so a missed or never-ready car can't keep it running.
 *
 * Every check and attempt — target values, per-setting write/verify results, and the actual
 * readback from the car — is recorded to [AdasAutoApplyLog] so intermittent failures can be
 * diagnosed from the Debug Hub after the fact, without needing to catch it live in Logcat.
 * [succeeded] lets the UI (e.g. the home screen's ADAS tile) reflect current state live.
 */
object AdasAutoApplyCoordinator {
    // Keep polling at a steady cadence (rather than backing off) until it succeeds, the user
    // disables auto-apply/Custom mode, or we run out of time — the car's ADAS ECU can take a
    // while to come up after a true cold boot and there's no reliable signal for when it'll be
    // ready, but we don't want to poll forever if it never shows up.
    private const val RETRY_INTERVAL_MS = 10_000L
    private const val MAX_RETRY_DURATION_MS = 60_000L

    // Each BYD read/apply is a blocking binder call that can stall for a long time while the car's
    // ADAS services are busy or still coming up. Coroutine timeouts can't interrupt that, so these
    // calls run on a separate thread and are abandoned after the limits below.
    private const val CHECK_TIMEOUT_MS = 10_000L
    private const val ATTEMPT_TIMEOUT_MS = 45_000L

    // Periodic safety-net check: this long after the previous run *ended* while the profile was
    // last confirmed good, or the longer backoff after a run that failed or gave up — so a car
    // whose ADAS services are down isn't hammered with full apply runs every few seconds.
    private const val PERIODIC_INTERVAL_MS = 5_000L
    private const val PERIODIC_FAILURE_BACKOFF_MS = 60_000L

    // Wait before re-reading to confirm a mismatch seen by the silent periodic check.
    private const val MISMATCH_CONFIRM_DELAY_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _succeeded = MutableStateFlow(false)

    /** True once the car's ADAS state has been confirmed to match the target profile. */
    val succeeded: StateFlow<Boolean> = _succeeded.asStateFlow()

    private val attemptRunning = AtomicBoolean(false)
    private val pendingRequest = AtomicReference<ApplyRequest?>(null)

    /** What the current run is doing, so a trigger that has to wait can say what it's waiting on. */
    @Volatile private var stage = "idle"
    @Volatile private var runStartedAt = 0L
    @Volatile private var lastRunEndedAt = 0L
    @Volatile private var lastRunOk = true
    @Volatile private var lastPeriodicUnknown = false

    /** [quiet] runs (the periodic check) only log when something is wrong, to keep the log readable. */
    private data class ApplyRequest(val app: Context, val reason: String, val quiet: Boolean = false)

    /**
     * Re-verifies the car's ADAS state against the target profile and (re)applies if needed.
     * Never skips just because a previous call in this process already succeeded; if a run is
     * already in progress this request is kept (latest wins) and run right after it. [reason] is
     * purely for the [AdasAutoApplyLog] trail (e.g. "app-open", "gear-shift-to-drive").
     */
    fun ensureApplied(context: Context, reason: String = "app-open") {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        pendingRequest.set(ApplyRequest(app, reason))
        if (attemptRunning.get()) {
            val runningForMs = SystemClock.elapsedRealtime() - runStartedAt
            log(app, "trigger ($reason) queued: a run is already in progress (stage=$stage, ${runningForMs}ms)")
        }
        launchPendingRequest()
    }

    /**
     * Safety-net check, meant to be called often (the watcher calls it every second): it does
     * nothing unless auto-apply is enabled, no run is active or pending, and enough time has
     * passed since the last run ended. Silent unless the car's state doesn't match the profile.
     */
    fun periodicCheck(context: Context) {
        val app = context.applicationContext
        if (attemptRunning.get() || pendingRequest.get() != null) return
        val interval = if (lastRunOk) PERIODIC_INTERVAL_MS else PERIODIC_FAILURE_BACKOFF_MS
        if (SystemClock.elapsedRealtime() - lastRunEndedAt < interval) return
        if (!isEnabled(app)) return
        if (!pendingRequest.compareAndSet(null, ApplyRequest(app, "periodic", quiet = true))) return
        launchPendingRequest()
    }

    private fun launchPendingRequest() {
        if (!attemptRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val request = pendingRequest.getAndSet(null) ?: break
                    if (!isEnabled(request.app)) continue
                    runStartedAt = SystemClock.elapsedRealtime()
                    val completed = withTimeoutOrNull(MAX_RETRY_DURATION_MS) {
                        recheckAndApply(request.app, request.reason, request.quiet)
                        true
                    }
                    if (completed == null) {
                        // Only the apply stage can run this long, and it didn't finish.
                        _succeeded.value = false
                        log(request.app, "recheck (${request.reason}) timed out after ${MAX_RETRY_DURATION_MS / 1000}s (stage=$stage)")
                    }
                    lastRunOk = completed != null && _succeeded.value
                    lastRunEndedAt = SystemClock.elapsedRealtime()
                    stage = "idle"
                }
            } finally {
                stage = "idle"
                attemptRunning.set(false)
                if (pendingRequest.get() != null) launchPendingRequest()
            }
        }
    }

    /** Whether ADAS auto-apply is switched on (Custom mode + "auto-apply on launch"); if not, there is no profile to wait for. */
    fun isAutoApplyActive(context: Context): Boolean = isEnabled(context.applicationContext)

    private fun isEnabled(app: Context): Boolean {
        val settings = AppSettings(app)
        return settings.adasEditMode == AdasEditMode.CUSTOM && settings.adasApplyOnBoot
    }

    private fun log(app: Context, line: String) = AdasAutoApplyLog.append(app, line)

    /** Runs a blocking call on its own thread; null if it didn't finish within [timeoutMs] (the thread is abandoned). */
    private suspend fun <T> runBounded(timeoutMs: Long, block: () -> T): Result<T>? {
        val deferred = CompletableDeferred<Result<T>>()
        thread(name = "adas-blocking-call", isDaemon = true) { deferred.complete(runCatching(block)) }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    private suspend fun recheckAndApply(app: Context, reason: String, quiet: Boolean = false) {
        stage = "checking current ADAS state"
        if (!quiet) log(app, "recheck ($reason): reading current ADAS state")
        val controller = BydAdasController(app)
        val profile = AppSettings(app).adasCustomProfile()
        // Cheap read-only check first: if DiPilot is already bound (common on a sleep/wake, since
        // our process never died) and everything already matches, skip the write/verify loop
        // entirely instead of needlessly re-asserting settings that are already correct.
        var check = readCheck(app, controller, profile, reason)
        // A one-off mismatch read shouldn't flip the icon or trigger writes from the silent
        // periodic check; confirm it with a second read first. Event-driven triggers act at once.
        if (quiet && check.result == ProfileCheck.MISMATCH) {
            log(app, "recheck ($reason): mismatch read, confirming...")
            delay(MISMATCH_CONFIRM_DELAY_MS)
            check = readCheck(app, controller, profile, reason)
            if (check.result != ProfileCheck.MISMATCH) log(app, "recheck ($reason): mismatch not confirmed (${check.result}); ignoring")
        }
        val tookMs = check.tookMs
        when (check.result) {
            ProfileCheck.MATCH -> {
                lastPeriodicUnknown = false
                if (!quiet) log(app, "recheck ($reason): matches target = true (${tookMs}ms)")
                else if (!_succeeded.value) log(app, "recheck ($reason): profile matches again (${tookMs}ms)")
                _succeeded.value = true
                return
            }
            ProfileCheck.UNKNOWN -> {
                // Couldn't tell. Don't flip the icon on that alone; the silent periodic check just
                // waits for the next read, while an event trigger still applies to be safe.
                if (quiet) {
                    if (!lastPeriodicUnknown) log(app, "recheck ($reason): ADAS state unreadable (${tookMs}ms); keeping current status")
                    lastPeriodicUnknown = true
                    return
                }
                log(app, "recheck ($reason): ADAS state unknown (${tookMs}ms); applying to be safe")
            }
            ProfileCheck.MISMATCH -> {
                log(app, "recheck ($reason): matches target = false (${tookMs}ms)")
                _succeeded.value = false
            }
        }
        lastPeriodicUnknown = false
        runRetries(app, controller, profile)
    }

    private class CheckOutcome(val result: ProfileCheck, val tookMs: Long)

    private suspend fun readCheck(
        app: Context,
        controller: BydAdasController,
        profile: AdasCustomProfile,
        reason: String,
    ): CheckOutcome {
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = runBounded(CHECK_TIMEOUT_MS) { controller.compareProfile(profile) }
        val tookMs = SystemClock.elapsedRealtime() - startedAt
        val result = when {
            outcome == null -> {
                log(app, "recheck ($reason): state read did not finish within ${CHECK_TIMEOUT_MS / 1000}s")
                ProfileCheck.UNKNOWN
            }
            outcome.isFailure -> {
                log(app, "recheck ($reason): state read failed: ${outcome.exceptionOrNull()}")
                ProfileCheck.UNKNOWN
            }
            else -> outcome.getOrThrow()
        }
        return CheckOutcome(result, tookMs)
    }

    private suspend fun runRetries(app: Context, controller: BydAdasController, initialProfile: AdasCustomProfile) {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + MAX_RETRY_DURATION_MS
        log(app, "=== auto-apply run start ===")
        var attempt = 0
        while (true) {
            attempt++
            if (attempt > 1) {
                stage = "waiting to retry (attempt $attempt)"
                delay(RETRY_INTERVAL_MS)
            }
            // Re-check each round in case the user disabled auto-apply or left Custom mode mid-retry.
            if (!isEnabled(app)) {
                log(app, "auto-apply stopped: disabled mid-retry")
                return
            }
            stage = "applying (attempt $attempt)"
            val profile = if (attempt == 1) initialProfile else AppSettings(app).adasCustomProfile()
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            log(
                app,
                "--- attempt $attempt (t+${elapsedMs}ms) target: elka=${profile.elka} " +
                    "lda=${profile.laneDeparture} aeb=${profile.aeb} dms=${profile.dms} ---"
            )
            val results = runBounded(ATTEMPT_TIMEOUT_MS) {
                controller.applyCustomProfileVerified(profile) { line -> log(app, line) }
            }?.onFailure { log(app, "attempt $attempt threw: $it") }?.getOrNull()
            if (results == null) {
                log(app, "attempt $attempt did not complete within ${ATTEMPT_TIMEOUT_MS / 1000}s or failed")
                if (SystemClock.elapsedRealtime() >= deadline) {
                    _succeeded.value = false
                    log(app, "=== auto-apply giving up after $attempt attempts (~${MAX_RETRY_DURATION_MS / 1000}s) ===")
                    return
                }
                continue
            }
            val ok = results.count { it.success }
            log(app, "attempt $attempt result: $ok/${results.size} ok")
            val snap = runBounded(CHECK_TIMEOUT_MS) { controller.snapshot() }?.getOrNull()
            log(app, "attempt $attempt readback: ${snap ?: "unavailable (timed out or failed)"}")
            if (ok == results.size) {
                _succeeded.value = true
                log(app, "=== auto-apply succeeded on attempt $attempt ===")
                return
            }
            results.filterNot { it.success }.forEach { log(app, "attempt $attempt failed: ${it.method}: ${it.detail}") }
            if (SystemClock.elapsedRealtime() >= deadline) {
                _succeeded.value = false
                log(app, "=== auto-apply giving up after $attempt attempts (~${MAX_RETRY_DURATION_MS / 1000}s) ===")
                return
            }
        }
    }
}
