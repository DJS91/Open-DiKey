package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.sphy.airconcontroller.ui.OpenDiKeyActivity

/** Debug probe menus behind the home bug icon. */
class DebugHubActivity : OpenDiKeyActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_hub)

        findViewById<android.widget.ImageButton>(R.id.debugBackButton).setOnClickListener {
            finish()
        }
        findViewById<android.view.View>(R.id.openDiagnosticsButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openAdbServiceButton).setOnClickListener {
            openBydAdbServiceMenu()
        }
        findViewById<android.view.View>(R.id.openClimateButton).setOnClickListener {
            startActivity(Intent(this, ClimateTestActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openDikeyButton).setOnClickListener {
            startActivity(Intent(this, DiKeyProbeActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openUsbButton).setOnClickListener {
            startActivity(Intent(this, UsbProbeActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openVehicleDumpButton).setOnClickListener {
            startActivity(Intent(this, VehicleDumpActivity::class.java))
        }
    }

    private fun openBydAdbServiceMenu() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BYD_ADB_SERVICE_URI))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.adb_service_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /** Resolves to com.byd.secretcode/.activity.AdbActivity, which runs as the system uid. */
        const val BYD_ADB_SERVICE_URI = "byd_pad://secretcode:9521/adbactivity"
    }
}

