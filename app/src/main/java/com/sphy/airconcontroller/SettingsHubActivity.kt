package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import com.sphy.airconcontroller.usb.DiKeyUsbBridge
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.ThemeMode
import com.sphy.airconcontroller.storage.ThemeStyle
import com.sphy.airconcontroller.telemetry.TelemetryConfig
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import com.sphy.airconcontroller.ui.ThemeManager
import com.sphy.airconcontroller.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App settings hub. */
class SettingsHubActivity : OpenDiKeyActivity() {
    private val requestTelemetryLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.none { it }) {
            Toast.makeText(this, R.string.telemetry_location_denied, Toast.LENGTH_LONG).show()
        }
        OpenDiKeyApp.from(this).telemetry.reconfigure()
    }
    private lateinit var versionText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var checkButton: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_hub)

        versionText = findViewById(R.id.settingsVersionText)
        statusText = findViewById(R.id.settingsUpdateStatus)
        progress = findViewById(R.id.settingsUpdateProgress)
        checkButton = findViewById(R.id.settingsCheckUpdateButton)

        findViewById<android.widget.ImageButton>(R.id.settingsBackButton).setOnClickListener {
            finish()
        }
        versionText.text = getString(
            R.string.settings_version_fmt,
            AppUpdater.currentVersionName(this),
        )
        checkButton.setOnClickListener { checkForUpdates() }

        val settings = AppSettings(this)
        findViewById<SwitchMaterial>(R.id.settingsOpenOnBootSwitch).apply {
            isChecked = settings.openAppOnBoot
            setOnCheckedChangeListener { _, checked -> settings.openAppOnBoot = checked }
        }
        findViewById<SwitchMaterial>(R.id.settingsAutoCarPlaySwitch).apply {
            isChecked = settings.autoSwitchToCarPlay
            setOnCheckedChangeListener { _, checked -> settings.autoSwitchToCarPlay = checked }
        }
        setUpThemeToggle(settings)
        setUpStyleToggle(settings)
        setUpWifi(settings)
        setUpTelemetry(settings)
    }

    private fun setUpTelemetry(settings: AppSettings) {
        val url = findViewById<EditText>(R.id.telemetryUrl)
        val interval = findViewById<EditText>(R.id.telemetryInterval)
        val location = findViewById<SwitchMaterial>(R.id.telemetryLocation)
        val enabled = findViewById<SwitchMaterial>(R.id.telemetryEnabled)
        val status = findViewById<TextView>(R.id.telemetryStatus)
        val sender = OpenDiKeyApp.from(this).telemetry
        url.setText(settings.telemetryUrl)
        interval.setText(settings.telemetryIntervalSeconds.toString())
        location.isChecked = settings.telemetryLocationEnabled
        enabled.isChecked = settings.telemetryEnabled

        findViewById<Button>(R.id.telemetrySave).setOnClickListener {
            val newUrl = url.text.toString().trim()
            val seconds = interval.text.toString().toIntOrNull()
            if (enabled.isChecked && !TelemetryConfig.validUrl(newUrl)) {
                url.error = getString(R.string.telemetry_invalid_url)
                return@setOnClickListener
            }
            if (seconds == null || seconds !in 5..3600) {
                interval.error = getString(R.string.telemetry_invalid_interval)
                return@setOnClickListener
            }
            settings.saveTelemetry(enabled.isChecked, newUrl, seconds, location.isChecked)
            sender.reconfigure()
            if (enabled.isChecked && location.isChecked) {
                requestTelemetryLocation.launch(arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
                ))
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sender.status.collect { status.text = it }
            }
        }
    }

    private fun setUpWifi(settings: AppSettings) {
        val bridge = OpenDiKeyApp.from(this).dikey.usbBridge
        val selectButton = findViewById<Button>(R.id.settingsWifiSelectButton)
        val scanButton = findViewById<Button>(R.id.settingsWifiScanButton)
        val connectButton = findViewById<Button>(R.id.settingsWifiConnectButton)
        val scanStatus = findViewById<TextView>(R.id.settingsWifiScanStatus)
        val passInput = findViewById<EditText>(R.id.settingsWifiPassword)
        val wifiStatus = findViewById<TextView>(R.id.settingsWifiStatus)
        var selectedSsid = settings.wifiSsid
        fun updateSelection() {
            selectButton.text = if (selectedSsid.isEmpty()) getString(R.string.wifi_select_network)
            else getString(R.string.wifi_selected_network, selectedSsid)
        }
        updateSelection()
        passInput.setText(settings.wifiPassword)

        scanButton.setOnClickListener { bridge.scanWifi() }
        selectButton.setOnClickListener {
            val networks = bridge.wifiScan.value.networks
            if (networks.isEmpty()) {
                Toast.makeText(this, R.string.wifi_select_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val labels = networks.map {
                getString(
                    R.string.wifi_network_item, it.ssid, it.rssi,
                    getString(if (it.secured) R.string.wifi_network_secured else R.string.wifi_network_open),
                )
            }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_select_network)
                .setItems(labels) { _, index ->
                    val ssid = networks[index].ssid
                    if (ssid != selectedSsid) passInput.setText("")
                    selectedSsid = ssid
                    updateSelection()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        connectButton.setOnClickListener {
            val ssid = selectedSsid
            val password = passInput.text.toString()
            if (ssid.isEmpty()) {
                Toast.makeText(this, R.string.wifi_select_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if ((password.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size !in 8..63) ||
                '\u0000' in password
            ) {
                passInput.error = getString(R.string.wifi_invalid_password)
                return@setOnClickListener
            }
            settings.wifiSsid = ssid
            settings.wifiPassword = password
            bridge.sendWifiConfig()
        }
        findViewById<Button>(R.id.settingsWifiForgetButton).setOnClickListener {
            settings.wifiSsid = ""
            settings.wifiPassword = ""
            selectedSsid = ""
            updateSelection()
            passInput.setText("")
            bridge.sendWifiConfig()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bridge.wifiScan.collect { scan ->
                    scanButton.isEnabled = !scan.scanning
                    connectButton.isEnabled = !scan.scanning
                    scanStatus.text = when {
                        scan.scanning -> getString(R.string.wifi_scan_running)
                        scan.error != null -> getString(R.string.wifi_scan_failed, scan.error)
                        scan.networks.isNotEmpty() -> getString(R.string.wifi_scan_found, scan.networks.size)
                        !scan.attempted -> getString(R.string.wifi_scan_initial)
                        else -> getString(R.string.wifi_scan_empty)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bridge.wifi.collect { wifi ->
                    wifiStatus.text = when (wifi.state) {
                        DiKeyUsbBridge.WifiState.OFF -> getString(R.string.settings_wifi_status_off)
                        DiKeyUsbBridge.WifiState.CONNECTING ->
                            getString(R.string.settings_wifi_status_connecting, settings.wifiSsid)
                        DiKeyUsbBridge.WifiState.CONNECTED ->
                            getString(R.string.settings_wifi_status_connected, wifi.detail)
                        DiKeyUsbBridge.WifiState.FAILED ->
                            getString(R.string.settings_wifi_status_failed, wifi.detail)
                    }
                }
            }
        }
    }

    private fun setUpStyleToggle(settings: AppSettings) {
        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.settingsStyleToggle)
        val buttonFor = mapOf(
            ThemeStyle.CLASSIC to R.id.settingsStyleClassic,
            ThemeStyle.MODERN to R.id.settingsStyleModern,
        )
        toggle.check(buttonFor.getValue(settings.themeStyle))
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val style = buttonFor.entries.first { it.value == checkedId }.key
            if (style == settings.themeStyle) return@addOnButtonCheckedListener
            settings.themeStyle = style
            // Other open screens notice the change when they resume (see OpenDiKeyActivity).
            recreate()
        }
    }

    private fun setUpThemeToggle(settings: AppSettings) {
        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.settingsThemeToggle)
        val buttonFor = mapOf(
            ThemeMode.DARK to R.id.settingsThemeDark,
            ThemeMode.LIGHT to R.id.settingsThemeLight,
            ThemeMode.SYSTEM to R.id.settingsThemeSystem,
        )
        // Check before adding the listener so restoring the saved choice doesn't re-apply it.
        toggle.check(buttonFor.getValue(settings.themeMode))
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = buttonFor.entries.first { it.value == checkedId }.key
            if (mode == settings.themeMode) return@addOnButtonCheckedListener
            settings.themeMode = mode
            // Recreates this and any other open screens in the new palette.
            ThemeManager.apply(mode)
        }
    }

    private fun checkForUpdates() {
        if (busy) return
        setBusy(true, getString(R.string.settings_updates_checking))
        lifecycleScope.launch {
            when (val result = AppUpdater.checkForUpdate(this@SettingsHubActivity)) {
                is AppUpdater.CheckResult.UpToDate -> {
                    setBusy(false, getString(R.string.settings_updates_up_to_date, result.latest))
                }
                is AppUpdater.CheckResult.Failed -> {
                    setBusy(false, getString(R.string.settings_updates_failed, result.message))
                }
                is AppUpdater.CheckResult.UpdateAvailable -> {
                    setBusy(false, getString(R.string.settings_updates_available, result.release.versionName))
                    promptInstall(result.release, result.current)
                }
            }
        }
    }

    private fun promptInstall(release: AppUpdater.LatestRelease, current: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_update_dialog_title)
            .setMessage(
                getString(
                    R.string.settings_update_dialog_body,
                    current,
                    release.versionName,
                ),
            )
            .setNegativeButton(R.string.settings_update_later, null)
            .setPositiveButton(R.string.settings_update_install) { _, _ ->
                downloadAndInstall(release)
            }
            .show()
    }

    private fun downloadAndInstall(release: AppUpdater.LatestRelease) {
        if (busy) return
        setBusy(true, getString(R.string.settings_updates_downloading, 0))
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        progress.progress = 0

        lifecycleScope.launch {
            try {
                if (!ensureInstallAllowed()) return@launch
                val dest = AppUpdater.updateCacheFile(this@SettingsHubActivity)
                AppUpdater.downloadApk(release.apkUrl, dest) { downloaded, total ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (total > 0L) {
                            progress.isIndeterminate = false
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            progress.progress = pct
                            statusText.text = getString(R.string.settings_updates_downloading, pct)
                        } else {
                            progress.isIndeterminate = true
                            statusText.text = getString(
                                R.string.settings_updates_downloading_bytes,
                                downloaded / 1024L,
                            )
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    setBusy(false, getString(R.string.settings_updates_installing))
                    progress.visibility = View.GONE
                    try {
                        startActivity(AppUpdater.installApkIntent(this@SettingsHubActivity, dest))
                    } catch (e: ActivityNotFoundException) {
                        setBusy(
                            false,
                            getString(
                                R.string.settings_updates_failed,
                                e.message ?: e.javaClass.simpleName,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    setBusy(
                        false,
                        getString(
                            R.string.settings_updates_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                    )
                    Toast.makeText(
                        this@SettingsHubActivity,
                        R.string.settings_updates_signature_hint,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /**
     * DiLink often has no "unknown apps" settings screen. Prefer ADB grant; if that fails and a
     * settings UI exists, open it and pause; otherwise continue and let the package installer decide.
     */
    private suspend fun ensureInstallAllowed(): Boolean {
        if (AppUpdater.canInstallPackages(this)) return true

        val pkg = packageName
        AdbPermissionManager.runShellBatch(
            this,
            listOf(
                "appops set ${AdbPermissionManager.userFlag()}$pkg REQUEST_INSTALL_PACKAGES allow",
                "cmd appops set ${AdbPermissionManager.userFlag()}$pkg REQUEST_INSTALL_PACKAGES allow",
            ),
        )
        if (AppUpdater.canInstallPackages(this)) return true

        val opened = withContext(Dispatchers.Main) {
            AppUpdater.openInstallPermissionSettings(this@SettingsHubActivity)
        }
        if (opened) {
            withContext(Dispatchers.Main) {
                setBusy(false, getString(R.string.settings_updates_need_permission))
            }
            return false
        }

        withContext(Dispatchers.Main) {
            statusText.text = getString(R.string.settings_updates_install_anyway)
        }
        return true
    }

    private fun setBusy(value: Boolean, status: String) {
        busy = value
        checkButton.isEnabled = !value
        statusText.text = status
        if (!value) progress.visibility = View.GONE
    }
}
