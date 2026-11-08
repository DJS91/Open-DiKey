package com.sphy.airconcontroller

import android.app.Application
import android.content.Context
import com.sphy.airconcontroller.adb.AdbKeepAlive
import com.sphy.airconcontroller.boot.BootAppLauncher
import com.sphy.airconcontroller.boot.DiKeyListenService
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.diagnostics.DiagnosticUpload
import com.sphy.airconcontroller.fridge.FridgeController
import com.sphy.airconcontroller.ui.ThemeManager
import com.sphy.airconcontroller.telemetry.TelemetrySender

class OpenDiKeyApp : Application() {
    lateinit var dikey: DiKeySession
        private set
    /** Camping fridge link and history; created after [dikey] because it uses the USB bridge. */
    lateinit var fridge: FridgeController
        private set
    lateinit var diagnosticUpload: DiagnosticUpload
        private set
    lateinit var telemetry: TelemetrySender
        private set

    override fun onCreate() {
        super.onCreate()
        ThemeManager.applySaved(this)
        BootAppLauncher.trackActivities(this)
        AdbKeepAlive.start(this)
        dikey = DiKeySession(this)
        dikey.start()
        fridge = FridgeController(this, dikey.usbBridge.fridge)
        diagnosticUpload = DiagnosticUpload(this)
        telemetry = TelemetrySender(this, dikey.usbBridge)
        com.sphy.airconcontroller.lighting.LightingScheduler.scheduleNext(this)
        DiKeyListenService.start(this)
    }

    companion object {
        fun from(context: Context): OpenDiKeyApp =
            context.applicationContext as OpenDiKeyApp
    }
}
