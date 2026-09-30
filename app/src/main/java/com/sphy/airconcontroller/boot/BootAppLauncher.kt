package com.sphy.airconcontroller.boot

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.MainActivity
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.storage.AppSettings
import kotlin.concurrent.thread

/**
 * Opens [MainActivity] when the head unit comes up, if the user enabled it in Settings.
 *
 * The process is re-created both at cold boot and right after CarPowerService's standby
 * force-stop, so "process started" alone is not a power-on signal. We launch when the listener
 * starts with the display on, and again on `SCREEN_ON` while running (wake from standby).
 * Skipped whenever one of our activities is already started (user tapped the launcher, or the
 * app was left on top).
 */
object BootAppLauncher {
    private const val TAG = "BootAppLauncher"
    private const val LAUNCH_DELAY_MS = 3_000L
    private const val VERIFY_DELAY_MS = 2_000L
    private const val MIN_INTERVAL_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private var startedActivities = 0
    private var lastLaunchAt = 0L
    private var screenReceiver: BroadcastReceiver? = null

    fun trackActivities(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Called from [DiKeyListenService.onCreate]; registers for display wake and checks once now. */
    fun attach(context: Context) {
        val app = context.applicationContext
        if (screenReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_ON) schedule(app)
                }
            }
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenReceiver = receiver
        }
        schedule(app)
    }

    fun detach(context: Context) {
        screenReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        screenReceiver = null
        main.removeCallbacksAndMessages(null)
    }

    private fun schedule(app: Context) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ launchIfNeeded(app) }, LAUNCH_DELAY_MS)
    }

    private fun launchIfNeeded(app: Context) {
        if (!AppSettings(app).openAppOnBoot) return
        if (startedActivities > 0) return
        val power = app.getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return
        val now = SystemClock.elapsedRealtime()
        if (lastLaunchAt != 0L && now - lastLaunchAt < MIN_INTERVAL_MS) return
        lastLaunchAt = now

        Log.i(TAG, "opening main app on head-unit start")
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "startActivity failed: ${it.message}") }

        // Background activity starts are dropped silently if the SYSTEM_ALERT_WINDOW grant is missing.
        main.postDelayed({
            if (startedActivities > 0) return@postDelayed
            Log.w(TAG, "activity did not start; retrying via local ADB")
            val component = "${app.packageName}/${MainActivity::class.java.name}"
            thread(name = "boot-app-launch") {
                AdbPermissionManager.launchComponent(app, app.packageName, component)
            }
        }, VERIFY_DELAY_MS)
    }
}
