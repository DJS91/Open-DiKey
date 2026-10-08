package com.sphy.airconcontroller.boot

import android.content.Context
import android.util.Log
import com.sphy.airconcontroller.byd.BydAdasController
import com.sphy.airconcontroller.storage.AdasEditMode
import com.sphy.airconcontroller.storage.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies the saved Custom ADAS profile on process start, retrying with backoff, and is safe to
 * (re)trigger from multiple call sites.
 *
 * [KeepAliveNotificationListener] can spin the process (and [DiKeyListenService]) up as soon as
 * system_server rebinds it after boot/standby — often well before the car's DiPilot/CarAssist/DMS
 * binder services have registered. [DiKeyListenService.onCreate] only runs once per process, so
 * if that early attempt exhausts its retries, simply opening the app later did nothing: the
 * process (and its `Application.onCreate`) already exists and nothing re-kicked the apply. Call
 * [ensureApplied] from any likely-ready moment (service start, activity start); it no-ops once a
 * profile apply has fully succeeded for this process lifetime, and only one attempt run at a time.
 */
object AdasAutoApplyCoordinator {
    private const val TAG = "AdasAutoApply"

    // Spread out over several minutes: the car's ADAS ECU can take a while to come up after a
    // true cold boot, much longer than after a standby wake where it's already warm.
    private val RETRY_DELAYS_MS = longArrayOf(0L, 15_000L, 30_000L, 60_000L, 120_000L, 180_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appliedOk = false
    private val attemptRunning = AtomicBoolean(false)

    /** Kicks off an auto-apply attempt; no-ops if already succeeded or already in progress. */
    fun ensureApplied(context: Context) {
        if (appliedOk) return
        val app = context.applicationContext
        if (!isEnabled(app)) return
        if (!attemptRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                runRetries(app)
            } finally {
                attemptRunning.set(false)
            }
        }
    }

    private fun isEnabled(app: Context): Boolean {
        val settings = AppSettings(app)
        return settings.adasEditMode == AdasEditMode.CUSTOM && settings.adasApplyOnBoot
    }

    private suspend fun runRetries(app: Context) {
        val controller = BydAdasController(app)
        RETRY_DELAYS_MS.forEachIndexed { attempt, delayMs ->
            if (appliedOk) return
            if (delayMs > 0) delay(delayMs)
            // Re-check each round in case the user disabled auto-apply or left Custom mode mid-retry.
            if (!isEnabled(app)) return
            val results = controller.applyCustomProfileVerified(AppSettings(app).adasCustomProfile())
            val ok = results.count { it.success }
            Log.i(TAG, "auto-apply attempt ${attempt + 1}/${RETRY_DELAYS_MS.size}: $ok/${results.size} ok")
            if (ok == results.size) {
                appliedOk = true
                return
            }
            results.filterNot { it.success }.forEach { Log.w(TAG, "auto-apply failed: ${it.method}: ${it.detail}") }
        }
        Log.w(TAG, "auto-apply exhausted retries without full success")
    }
}
