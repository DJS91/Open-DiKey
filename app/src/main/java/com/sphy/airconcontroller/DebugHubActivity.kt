package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.launch

/** Debug probe menus behind the home bug icon. */
class DebugHubActivity : OpenDiKeyActivity() {
    private var wifiDetailsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_hub)

        findViewById<android.widget.ImageButton>(R.id.debugBackButton).setOnClickListener {
            finish()
        }
        val bridge = OpenDiKeyApp.from(this).dikey.usbBridge
        val wifiDebug = findViewById<android.widget.TextView>(R.id.wifiDebugText)
        val wifiDetails = findViewById<View>(R.id.wifiDebugDetails)
        val wifiToggle = findViewById<Button>(R.id.wifiDebugToggleButton)
        fun renderWifiDetails() {
            wifiDetails.visibility = if (wifiDetailsVisible) View.VISIBLE else View.GONE
            wifiToggle.setText(if (wifiDetailsVisible) R.string.wifi_debug_hide else R.string.wifi_debug_show)
        }
        wifiDetailsVisible = savedInstanceState?.getBoolean(STATE_WIFI_DETAILS_VISIBLE) ?: false
        renderWifiDetails()
        wifiToggle.setOnClickListener {
            wifiDetailsVisible = !wifiDetailsVisible
            renderWifiDetails()
        }
        findViewById<android.widget.Button>(R.id.wifiDebugRefreshButton).setOnClickListener {
            if (!bridge.requestWifiStatus()) {
                val message = if (bridge.isUsbOpen) R.string.wifi_debug_send_failed
                else R.string.wifi_debug_usb_unavailable
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bridge.wifiDiagnostics.collect { lines ->
                    wifiDebug.text = lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
                        ?: getString(R.string.wifi_debug_empty)
                }
            }
        }
        if (bridge.isUsbOpen) bridge.requestWifiStatus()
        findViewById<android.view.View>(R.id.openDiagnosticsButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openBluetoothDiagnosticsButton).setOnClickListener {
            startActivity(Intent(this, BluetoothDiagnosticsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openAdasLogButton).setOnClickListener {
            startActivity(Intent(this, AdasAutoApplyLogActivity::class.java))
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_WIFI_DETAILS_VISIBLE, wifiDetailsVisible)
        super.onSaveInstanceState(outState)
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
        const val STATE_WIFI_DETAILS_VISIBLE = "wifi_details_visible"
        /** Resolves to com.byd.secretcode/.activity.AdbActivity, which runs as the system uid. */
        const val BYD_ADB_SERVICE_URI = "byd_pad://secretcode:9521/adbactivity"
    }
}
