package com.sphy.airconcontroller

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.diagnostics.BleScanEvidence
import com.sphy.airconcontroller.fridge.EvakoolProtocol
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import com.sphy.airconcontroller.update.AppUpdater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
class BluetoothDiagnosticsActivity : OpenDiKeyActivity() {
    private lateinit var reportText: TextView
    private lateinit var scanButton: Button
    private lateinit var stopButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val evidence = BleScanEvidence()
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var started = 0L
    private var lastStart = 0L
    private var elapsed = 0L
    private val devices = linkedMapOf<String, String>()
    private val events = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var checks = ""
    private val manager get() = getSystemService(BluetoothManager::class.java)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        result.forEach { (permission, granted) -> event("$permission: ${if (granted) "GRANTED" else "DENIED"}") }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth_diagnostics)
        reportText = findViewById(R.id.btDiagReport)
        scanButton = findViewById(R.id.btDiagScan)
        stopButton = findViewById(R.id.btDiagStop)
        findViewById<android.view.View>(R.id.btDiagBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btDiagRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btDiagPermissions).setOnClickListener {
            stopScan("Stopped before requesting permissions")
            permissions.launch(requiredPermissions())
        }
        scanButton.setOnClickListener { startScan() }
        stopButton.setOnClickListener { stopScan("Stopped by user") }
        findViewById<Button>(R.id.btDiagEspScan).setOnClickListener { espDiagnostics(scan = true) }
        findViewById<Button>(R.id.btDiagEspReport).setOnClickListener { espDiagnostics(scan = false) }
        findViewById<Button>(R.id.btDiagLocation).setOnClickListener { openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        findViewById<Button>(R.id.btDiagBluetooth).setOnClickListener { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        findViewById<Button>(R.id.btDiagAppSettings).setOnClickListener {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        findViewById<Button>(R.id.btDiagCopy).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.bt_diag_title), report()),
            )
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btDiagShare).setOnClickListener {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.bt_diag_title))
                    putExtra(Intent.EXTRA_TEXT, report())
                }, getString(R.string.diag_share)))
            } catch (e: ActivityNotFoundException) {
                event("No app can share the report: ${e.message}")
                Toast.makeText(this, R.string.diag_share_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.removeCallbacks(espTick)
        handler.post(espTick)
    }

    private val usbFridge get() = OpenDiKeyApp.from(this).dikey.usbBridge.fridge

    private val espTick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    private fun espDiagnostics(scan: Boolean) {
        if (!usbFridge.requestDiagnostics(scan)) {
            event("ESP32 diagnostics not sent: USB bridge not open or write failed.")
            Toast.makeText(this, R.string.bt_diag_esp_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        event(if (scan) "Requested 15-second ESP32 scan; report arrives automatically." else "Requested ESP32 scan report.")
    }

    override fun onStop() {
        stopScan("Stopped because diagnostics page left the foreground")
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        checks = buildString {
            appendLine("Open DiKey ${AppUpdater.currentVersionName(this@BluetoothDiagnosticsActivity)}")
            appendLine("Report time: ${Date()}")
            appendLine("Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("BLE feature advertised by OS: ${packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}")
            val adapter = manager?.adapter
            appendLine("Android Bluetooth adapter present: ${adapter != null}")
            val connectAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)
            if (connectAllowed) {
                try {
                    appendLine("Adapter enabled: ${adapter?.isEnabled}")
                    appendLine("Adapter state: ${adapter?.state} (10=OFF, 11=TURNING_ON, 12=ON, 13=TURNING_OFF)")
                    appendLine("Android BLE scanner available: ${adapter?.bluetoothLeScanner != null}")
                    val connected = manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
                    appendLine("Android-reported connected GATT devices: ${connected.size}")
                    connected.take(20).forEach { appendLine("  ${it.name.orEmpty()} ${it.address}") }
                } catch (e: SecurityException) {
                    appendLine("Bluetooth access rejected: ${e.message}")
                    Log.w(TAG, "Bluetooth check rejected", e)
                } catch (e: UnsupportedOperationException) {
                    appendLine("Bluetooth query unsupported by vendor: ${e.message}")
                    Log.w(TAG, "Bluetooth query unsupported", e)
                }
            } else appendLine("Adapter/GATT query skipped: BLUETOOTH_CONNECT denied")
            appendLine()
            appendLine("PERMISSIONS (Android 11 has no Bluetooth runtime permission toggle)")
            val tested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) requiredPermissions().toList()
            else listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            tested.forEach { appendLine("$it: ${if (granted(it)) "GRANTED" else "DENIED"}") }
            appendLine("System Location enabled: ${getSystemService(LocationManager::class.java)?.isLocationEnabled}")
            val ops = getSystemService(AppOpsManager::class.java)
            try {
                val locationOp = ops?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, Process.myUid(), packageName)
                appendLine("Fine Location AppOp: ${opName(locationOp)} (FOREGROUND requires this page visible)")
            } catch (e: SecurityException) {
                appendLine("AppOp query denied: ${e.message}")
                Log.w(TAG, "AppOp check rejected", e)
            }
            appendLine()
            val session = OpenDiKeyApp.from(this@BluetoothDiagnosticsActivity).dikey
            appendLine("DiKey connected: ${session.isConnected}")
            appendLine("DiKey USB bridge open: ${session.isUsbOpen}")
            appendLine("ESP32 fridge firmware confirmed: ${session.usbBridge.fridge.supported}")
            appendLine("ESP32 fridge enabled: ${session.usbBridge.fridge.enabled}")
            appendLine("ESP32 fridge status: ${session.usbBridge.fridge.message}")
            appendLine("DiKey scanner state: ${session.bleScanState.value}")
            appendLine("This test does not disconnect DiKey or connect/write to any BLE device.")
            appendLine("USB bridge radio traffic is separate from the head-unit Bluetooth adapter.")
        }
        render()
    }

    private fun opName(mode: Int?): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "ALLOWED"
        AppOpsManager.MODE_IGNORED -> "IGNORED / blocked"
        AppOpsManager.MODE_ERRORED -> "ERRORED / blocked"
        AppOpsManager.MODE_DEFAULT -> "DEFAULT (permission/policy dependent)"
        AppOpsManager.MODE_FOREGROUND -> "FOREGROUND"
        else -> "UNKNOWN ($mode)"
    }

    private fun startScan() {
        if (evidence.running) return
        val now = SystemClock.elapsedRealtime()
        if (lastStart != 0L && now - lastStart < 30_000) {
            event("Wait 30 seconds between scan starts to avoid Android scan throttling.")
            return
        }
        refresh()
        if (requiredPermissions().any { !granted(it) }) {
            event("BLOCKED: required runtime permission denied. Tap Request permissions.")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            event("BLOCKED: Android does not advertise BLE support.")
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R &&
            getSystemService(LocationManager::class.java)?.isLocationEnabled != true
        ) {
            event("BLOCKED: Android 11 requires system Location enabled. Open Location settings.")
            return
        }
        try {
            val adapter = manager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                event("BLOCKED: Android Bluetooth adapter missing or switched off.")
                return
            }
            val le = adapter.bluetoothLeScanner
            if (le == null) {
                event("BLOCKED: Android returned no BLE scanner.")
                return
            }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post {
                        if (callback !== this || !evidence.running) return@post
                        try {
                            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                                ?: result.device.name.orEmpty()
                            val address = result.device.address
                            val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid.toString() }
                            val match = EvakoolProtocol.matchesName(name) || EvakoolProtocol.SERVICE_UUID in uuids
                            evidence.record(address, result.isConnectable, match)
                            if (devices.size < 50 || devices.containsKey(address)) {
                                devices[address] = "$address name=${name.ifBlank { "(unnamed)" }} RSSI=${result.rssi} " +
                                    "connectable=${result.isConnectable} fridgeMatch=$match services=$uuids"
                            }
                            if (evidence.callbacks == 1) event("FIRST CALLBACK received: BLE reception confirmed.")
                        } catch (e: SecurityException) {
                            event("Scan result access denied: ${e.message}")
                            stopScan("Stopped after permission rejection", rejection = "Scan result access denied: ${e.message}")
                        }
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        if (callback !== this) return@post
                        event("onScanFailed: $errorCode ${BleScanEvidence.failureName(errorCode)}")
                        stopScan("Android reported scan failure", errorCode)
                    }
                }
            }
            devices.clear()
            elapsed = 0
            started = now
            lastStart = now
            scanner = le
            callback = cb
            evidence.start()
            event("Calling startScan: unfiltered, LOW_LATENCY, reportDelay=0, duration=15 seconds.")
            le.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(), cb)
            event("startScan returned without exception. This alone does NOT prove radio activity.")
            handler.post(tick)
        } catch (e: SecurityException) {
            event("startScan permission exception: ${e.message}")
            stopScan("Scan request rejected", rejection = "startScan permission exception: ${e.message}")
        } catch (e: IllegalStateException) {
            event("startScan Bluetooth state exception: ${e.message}")
            stopScan("Scan request rejected", rejection = "startScan Bluetooth state exception: ${e.message}")
        } catch (e: IllegalArgumentException) {
            event("startScan configuration exception: ${e.message}")
            stopScan("Scan request rejected", rejection = "startScan configuration exception: ${e.message}")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!evidence.running) return
            elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed >= 15_000) stopScan("15-second diagnostic scan completed")
            else {
                render()
                handler.postDelayed(this, 1_000)
            }
        }
    }

    private fun stopScan(reason: String, error: Int? = null, rejection: String? = null) {
        handler.removeCallbacks(tick)
        val old = callback
        callback = null
        if (old == null) return
        elapsed = SystemClock.elapsedRealtime() - started
        evidence.stop(error, rejection)
        try {
            scanner?.stopScan(old)
        } catch (e: SecurityException) {
            event("stopScan permission exception: ${e.message}")
        } catch (e: IllegalStateException) {
            event("stopScan Bluetooth state exception: ${e.message}")
        }
        scanner = null
        event(reason)
        render()
    }

    private fun event(message: String) {
        events.addLast("${clock.format(Date())} $message")
        while (events.size > 80) events.removeFirst()
        Log.i(TAG, message)
        if (::reportText.isInitialized) render()
    }

    private fun report(): String = buildString {
        appendLine(checks)
        val fridge = usbFridge
        appendLine("ESP32 BRIDGE BLE (USB bridge open: ${fridge.open}; fridge firmware confirmed: ${fridge.supported})")
        appendLine("ESP32 fridge status now: ${fridge.message.replace('\n', ' ')}")
        appendLine("Fridge notifications received since READY: ${fridge.rxCount}")
        appendLine("Last raw fridge data: ${fridge.lastRx ?: "(none)"}")
        appendLine("Last fridge frame: ${fridge.lastFrame ?: "(none)"}")
        appendLine("Last parse error: ${fridge.lastParseError ?: "(none)"}")
        fridge.status?.let {
            appendLine("Parsed: power=${if (it.on) "on" else "off"} left=${it.left} right=${it.right} " +
                "targets=${it.leftTarget}/${it.rightTarget} ${if (it.celsius) "C" else "F"} voltage=${it.voltage}")
        }
        if (fridge.diagScanning) appendLine("ESP32 15-second scan in progress...")
        if (fridge.diagReport.isEmpty()) {
            appendLine("No ESP32 scan report yet. Tap ESP32 scan (15s).")
        } else {
            appendLine("Summary: ${fridge.diagReport.first()}")
            appendLine("Seen devices (address RSSI C=connectable/N=not M=fridge match count name):")
            fridge.diagReport.drop(1).forEach { appendLine("  $it") }
            if (fridge.diagReport.size == 1) appendLine("  (none - ESP32 received no advertisements)")
        }
        appendLine("ESP32 fridge events (newest last):")
        fridge.events.takeLast(30).forEach { appendLine("  $it") }
        appendLine()
        appendLine("RAW BLE SCAN EVIDENCE")
        appendLine(evidence.conclusion())
        appendLine("Elapsed: ${elapsed / 1000}s; callbacks: ${evidence.callbacks}; unique devices: ${evidence.uniqueDevices} (capped at 512)")
        appendLine("Connectable callbacks: ${evidence.connectableCallbacks}; EVAKOOL match callbacks: ${evidence.fridgeCallbacks}")
        appendLine("Counts include unnamed, non-connectable and non-fridge advertisements.")
        appendLine("No callback cannot distinguish a silent radio from no nearby advertisers or vendor filtering.")
        appendLine("A callback proves reception, not fridge compatibility or multiple-connection support.")
        appendLine("On Android 12+, neverForLocation may filter some advertisement types.")
        appendLine()
        appendLine("DEVICE SAMPLE (up to 50; contains nearby device names and addresses)")
        devices.values.forEach(::appendLine)
        appendLine()
        appendLine("EVENTS")
        events.forEach(::appendLine)
    }

    private fun render() {
        val text = report()
        if (reportText.text.toString() != text) reportText.text = text
        scanButton.isEnabled = !evidence.running
        stopButton.isEnabled = evidence.running
    }

    private fun openSettings(action: String, uri: Uri? = null) {
        try {
            startActivity(Intent(action, uri))
        } catch (e: ActivityNotFoundException) {
            event("Settings screen unavailable on this head unit: $action (${e.message})")
            Toast.makeText(this, R.string.bt_diag_settings_unavailable, Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            event("Vendor blocked settings screen: $action (${e.message})")
            Toast.makeText(this, R.string.bt_diag_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "OpenDiKeyBleDiag"
    }
}
