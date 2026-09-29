package com.sphy.airconcontroller.boot

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Wake hook, not a notification reader. Once approved (`cmd notification allow_listener`),
 * system_server keeps this bound and rebinds it after the process is killed, including the
 * force-stop DiLink's CarPowerService applies to every third-party app on standby. Each bind
 * creates the process, so [com.sphy.airconcontroller.OpenDiKeyApp.onCreate] runs again.
 */
class KeepAliveNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Log.i(TAG, "listener connected, pid=${android.os.Process.myPid()}")
        DiKeyListenService.start(this)
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "listener disconnected, requesting rebind")
        runCatching { requestRebind(ComponentName(this, KeepAliveNotificationListener::class.java)) }
    }

    companion object {
        private const val TAG = "KeepAliveNLS"

        fun component(pkg: String): String = "$pkg/${KeepAliveNotificationListener::class.java.name}"
    }
}
