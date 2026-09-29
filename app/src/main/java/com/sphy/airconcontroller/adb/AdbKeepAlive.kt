package com.sphy.airconcontroller.adb

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Keeps `Settings.Global.adb_enabled` at 1.
 *
 * BYD's OTA app (`com.byd.ota`, `UpdatePresenter.init`) writes `adb_enabled=0` on every
 * start on non-userdebug builds, which is why ADB is off after each boot until the
 * secret-code menu's "Connect USB" button flips it back. With the ADB-granted
 * WRITE_SECURE_SETTINGS we can do the same write ourselves; adbd keeps its TCP port
 * (5555) configuration across boots, so this alone brings local/wireless ADB back.
 */
object AdbKeepAlive {
    private const val TAG = "AdbKeepAlive"
    private const val ADB_ENABLED = "adb_enabled"

    @Volatile private var observer: ContentObserver? = null

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, ADB_ENABLED, 0) == 1

    /** Returns true if ADB was off and this call turned it on. */
    fun ensureEnabled(context: Context): Boolean {
        if (isEnabled(context) || !canWrite(context)) return false
        return try {
            Settings.Global.putInt(context.contentResolver, ADB_ENABLED, 1)
            Log.i(TAG, "adb_enabled was 0, restored to 1")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot write adb_enabled: ${e.message}")
            false
        }
    }

    /** Re-enable ADB immediately and whenever something turns it off while the process lives. */
    fun start(context: Context) {
        val app = context.applicationContext
        ensureEnabled(app)
        if (observer != null || !canWrite(app)) return
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!isEnabled(app)) {
                    Log.i(TAG, "adb_enabled turned off externally")
                    ensureEnabled(app)
                }
            }
        }
        app.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(ADB_ENABLED), false, obs
        )
        observer = obs
    }
}
