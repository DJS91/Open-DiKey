package com.sphy.airconcontroller.boot

import android.content.Context
import android.os.SystemClock
import com.sphy.airconcontroller.byd.BydAdasController
import com.sphy.airconcontroller.storage.AdasCustomProfile
import com.sphy.airconcontroller.storage.AdasEditMode
import com.sphy.airconcontroller.storage.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies the saved Custom ADAS profile on process start, retrying at a fixed interval until it
 * sticks, and is safe to (re)trigger from multiple call sites.
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
 * Only one check/apply runs at a time; a concurrent call simply no-ops.
 * Gives up after [MAX_RETRY_DURATION_MS] if the car's ADAS services never came up in time.
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _succeeded = MutableStateFlow(false)

    /** True once the car's ADAS state has been confirmed to match the target profile. */
    val succeeded: StateFlow<Boolean> = _succeeded.asStateFlow()

    private val attemptRunning = AtomicBoolean(false)

    /**
     * Re-verifies the car's ADAS state against the target profile and (re)applies if needed.
     * No-ops only if auto-apply is disabled or a check/apply is already in progress — never
     * skips just because a previous call in this process already succeeded.
     */
    fun ensureApplied(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        if (!attemptRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                recheckAndApply(app)
            } finally {
                attemptRunning.set(false)
            }
        }
    }

    private fun isEnabled(app: Context): Boolean {
        val settings = AppSettings(app)
        return settings.adasEditMode == AdasEditMode.CUSTOM && settings.adasApplyOnBoot
    }

    private fun log(app: Context, line: String) = AdasAutoApplyLog.append(app, line)

    private suspend fun recheckAndApply(app: Context) {
        val controller = BydAdasController(app)
        val profile = AppSettings(app).adasCustomProfile()
        // Cheap read-only check first: if DiPilot is already bound (common on a sleep/wake, since
        // our process never died) and everything already matches, skip the write/verify loop
        // entirely instead of needlessly re-asserting settings that are already correct.
        val matches = runCatching { controller.matchesProfile(profile) }.getOrDefault(false)
        log(app, "recheck on open: matches target = $matches")
        if (matches) {
            _succeeded.value = true
            return
        }
        _succeeded.value = false
        runRetries(app, controller, profile)
    }

    private suspend fun runRetries(app: Context, controller: BydAdasController, initialProfile: AdasCustomProfile) {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + MAX_RETRY_DURATION_MS
        log(app, "=== auto-apply run start ===")
        var attempt = 0
        while (!_succeeded.value) {
            attempt++
            if (attempt > 1) delay(RETRY_INTERVAL_MS)
            // Re-check each round in case the user disabled auto-apply or left Custom mode mid-retry.
            if (!isEnabled(app)) {
                log(app, "auto-apply stopped: disabled mid-retry")
                return
            }
            val profile = if (attempt == 1) initialProfile else AppSettings(app).adasCustomProfile()
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            log(
                app,
                "--- attempt $attempt (t+${elapsedMs}ms) target: elka=${profile.elka} " +
                    "lda=${profile.laneDeparture} aeb=${profile.aeb} dms=${profile.dms} ---"
            )
            val results = controller.applyCustomProfileVerified(profile) { line -> log(app, line) }
            val ok = results.count { it.success }
            log(app, "attempt $attempt result: $ok/${results.size} ok")
            val snap = controller.snapshot()
            log(app, "attempt $attempt readback: $snap")
            if (ok == results.size) {
                _succeeded.value = true
                log(app, "=== auto-apply succeeded on attempt $attempt ===")
                return
            }
            results.filterNot { it.success }.forEach { log(app, "attempt $attempt failed: ${it.method}: ${it.detail}") }
            if (SystemClock.elapsedRealtime() >= deadline) {
                log(app, "=== auto-apply giving up after $attempt attempts (~${MAX_RETRY_DURATION_MS / 1000}s) ===")
                return
            }
        }
    }
}
