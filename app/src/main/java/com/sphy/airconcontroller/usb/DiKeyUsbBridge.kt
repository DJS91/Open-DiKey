package com.sphy.airconcontroller.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.sphy.airconcontroller.dikey.DiKeyController
import com.sphy.airconcontroller.dikey.DiKeyFrameSink
import com.sphy.airconcontroller.dikey.DiKeyProtocol
import com.sphy.airconcontroller.fridge.UsbFridgeSession
import com.sphy.airconcontroller.storage.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * USB serial link to firmware/src/DiKeyUsbBridge. The C3 owns DiKey GATT;
 * this forwards TX hex and feeds RX notifies into [DiKeyController].
 */
class DiKeyUsbBridge(
    usbManager: UsbManager,
    private val controller: DiKeyController,
    private val settings: AppSettings
) : DiKeyFrameSink {
    enum class WifiState { OFF, CONNECTING, CONNECTED, FAILED }

    data class WifiStatus(val state: WifiState = WifiState.OFF, val detail: String = "")
    data class WifiScan(
        val attempted: Boolean = false,
        val scanning: Boolean = false,
        val networks: List<WifiNetwork> = emptyList(),
        val error: String? = null,
    )

    private val _wifi = MutableStateFlow(WifiStatus())
    val wifi: StateFlow<WifiStatus> = _wifi.asStateFlow()
    private val _wifiScan = MutableStateFlow(WifiScan())
    val wifiScan: StateFlow<WifiScan> = _wifiScan.asStateFlow()
    private val _wifiDiagnostics = MutableStateFlow<List<String>>(emptyList())
    val wifiDiagnostics: StateFlow<List<String>> = _wifiDiagnostics.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable {
        if (_wifiScan.value.scanning) {
            _wifiScan.value = _wifiScan.value.copy(scanning = false, error = "No scan response from ESP32; update its firmware and try again.")
            recordWifiDiagnostic("Scan timed out waiting for ESP32")
        }
    }

    private fun recordWifiDiagnostic(message: String) {
        _wifiDiagnostics.value = (_wifiDiagnostics.value + message).takeLast(40)
    }
    private val writeTimeout = Runnable {
        Log.w(TAG, "TX timeout")
        controller.onWriteComplete(false)
    }
    private var dikeyReady = false
    val fridge = UsbFridgeSession { line ->
        serial.writeLine(line).also { sent ->
            if (!sent) Log.w(TAG, "Fridge USB write failed: $line")
        }
    }
    private val fridgeTick = object : Runnable {
        override fun run() {
            if (fridge.open && !serial.isOpen) fridge.onClose()
            fridge.tick(SystemClock.elapsedRealtime())
            if (fridge.open) main.postDelayed(this, 250)
        }
    }

    val serial: UsbHostSerial = UsbHostSerial(
        usbManager,
        onLine = { line -> handleLine(line) },
        onState = { msg ->
            if (msg == "USB read ended") {
                serial.close()
                dikeyReady = false
                wifiTransportDown()
                controller.onTransportDown(msg)
            }
            if (!serial.isOpen && fridge.open) fridge.onClose()
            controller.postStatus("USB · $msg")
        }
    )

    val isUsbOpen: Boolean get() = serial.isOpen
    val isDikeyReady: Boolean get() = dikeyReady && serial.isOpen

    fun open(device: UsbDevice): String? {
        dikeyReady = false
        controller.attachSink(this)
        val err = serial.open(device)
        if (err != null) {
            controller.attachSink(null)
            return err
        }
        serial.writeLine("STATUS")
        sendWifiConfig()
        fridge.onOpen(SystemClock.elapsedRealtime())
        main.removeCallbacks(fridgeTick)
        main.post(fridgeTick)
        return null
    }

    /** Pushes the saved WiFi credentials (or turns WiFi off when none are saved) to the ESP32. */
    fun sendWifiConfig() {
        val ssid = settings.wifiSsid
        if (ssid.isEmpty()) {
            if (serial.isOpen && !serial.writeLine("WIFI OFF")) {
                _wifi.value = WifiStatus(WifiState.FAILED, "Failed to send WiFi off command over USB")
                recordWifiDiagnostic("Failed to send WiFi off command over USB")
            } else {
                _wifi.value = WifiStatus()
            }
            return
        }
        val pass = settings.wifiPassword
        val line = "WIFI SET ${ssid.toHex()} ${if (pass.isEmpty()) "-" else pass.toHex()}"
        if (serial.isOpen && serial.writeLine(line)) {
            _wifi.value = WifiStatus(WifiState.CONNECTING, ssid)
        } else {
            val error = if (serial.isOpen) "Failed to send WiFi credentials over USB"
            else "ESP32 not connected over USB"
            _wifi.value = WifiStatus(WifiState.FAILED, error)
            recordWifiDiagnostic(error)
        }
    }

    private fun String.toHex(): String =
        toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private fun handleWifiLine(line: String) {
        val parts = line.removePrefix("WIFI STATE ").split(' ', limit = 2)
        val detail = parts.getOrElse(1) { "" }
        _wifi.value = when (parts[0]) {
            "CONNECTED" -> WifiStatus(WifiState.CONNECTED, detail)
            "CONNECTING", "CHECKING" -> WifiStatus(WifiState.CONNECTING, detail)
            "FAILED" -> WifiStatus(WifiState.FAILED, detail)
            else -> WifiStatus()
        }
        if (parts[0] == "FAILED") recordWifiDiagnostic("Connection failed: $detail")
    }

    fun requestWifiStatus(): Boolean = serial.isOpen && serial.writeLine("WIFI STATUS")

    fun scanWifi() {
        if (_wifiScan.value.scanning) return
        if (!serial.isOpen) {
            _wifiScan.value = WifiScan(attempted = true, error = "ESP32 USB bridge is not connected.")
            return
        }
        _wifiScan.value = WifiScan(attempted = true, scanning = true)
        if (!serial.writeLine("WIFI SCAN")) {
            _wifiScan.value = WifiScan(attempted = true, error = "Failed to send scan request over USB.")
            recordWifiDiagnostic("Failed to send WiFi scan request over USB")
            return
        }
        main.removeCallbacks(scanTimeout)
        main.postDelayed(scanTimeout, 35_000)
    }

    private fun handleWifiScanLine(line: String) {
        if (!_wifiScan.value.scanning) return
        when {
            line == "WIFI SCAN START" -> recordWifiDiagnostic("WiFi scan started")
            line.startsWith("WIFI SCAN RESULT ") -> {
                val network = WifiScanProtocol.parseResult(line)
                if (network == null) {
                    recordWifiDiagnostic("Ignored invalid or non-UTF-8 network name in scan")
                } else {
                    _wifiScan.value = _wifiScan.value.copy(
                        networks = WifiScanProtocol.addNetwork(_wifiScan.value.networks, network),
                    )
                }
            }
            line == "WIFI SCAN END" -> {
                main.removeCallbacks(scanTimeout)
                _wifiScan.value = _wifiScan.value.copy(scanning = false)
                recordWifiDiagnostic("WiFi scan completed: ${_wifiScan.value.networks.size} networks")
            }
            line.startsWith("WIFI SCAN ERROR ") -> {
                main.removeCallbacks(scanTimeout)
                val error = line.removePrefix("WIFI SCAN ERROR ")
                _wifiScan.value = _wifiScan.value.copy(scanning = false, error = error)
                recordWifiDiagnostic("WiFi scan failed: $error")
            }
        }
    }

    private fun wifiTransportDown() {
        _wifi.value = WifiStatus()
        main.removeCallbacks(scanTimeout)
        if (_wifiScan.value.scanning) {
            _wifiScan.value = _wifiScan.value.copy(scanning = false, error = "ESP32 USB bridge disconnected.")
        }
        recordWifiDiagnostic("ESP32 USB bridge disconnected; WiFi status unavailable")
    }

    fun close() {
        dikeyReady = false
        wifiTransportDown()
        main.removeCallbacks(writeTimeout)
        main.removeCallbacks(fridgeTick)
        serial.close()
        fridge.onClose()
        controller.attachSink(null)
    }

    override fun writeFrame(bytes: ByteArray): Boolean {
        if (!serial.isOpen || !dikeyReady) return false
        val hex = with(DiKeyProtocol) { bytes.toCompactHex() }
        val ok = serial.writeLine("TX $hex")
        if (ok) {
            main.removeCallbacks(writeTimeout)
            main.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
        }
        return ok
    }

    private fun handleLine(line: String) {
        if (fridge.onLine(line, SystemClock.elapsedRealtime())) {
            Log.d(TAG, line)
            return
        }
        if (line.startsWith("HELLO ODK-BRIDGE")) {
            fridge.onOpen(SystemClock.elapsedRealtime())
            sendWifiConfig()
        }
        if (line.startsWith("WIFI STATE ")) {
            if (_wifiScan.value.scanning && line == "WIFI STATE FAILED unsupported command") {
                main.removeCallbacks(scanTimeout)
                _wifiScan.value = _wifiScan.value.copy(scanning = false, error = "ESP32 firmware does not support WiFi scanning; update the firmware.")
                recordWifiDiagnostic("ESP32 firmware does not support WiFi scanning")
                return
            }
            handleWifiLine(line)
            return
        }
        if (line.startsWith("WIFI DIAG ")) {
            recordWifiDiagnostic(line.removePrefix("WIFI DIAG "))
            return
        }
        if (line.startsWith("WIFI SCAN ")) {
            handleWifiScanLine(line)
            return
        }
        when {
            line.startsWith("RX ", ignoreCase = true) -> {
                val bytes = DiKeyProtocol.parseHex(line.substring(3)) ?: return
                controller.ingestNotify(bytes)
            }
            line.equals("TXOK", ignoreCase = true) -> {
                main.removeCallbacks(writeTimeout)
                controller.onWriteComplete(true)
            }
            line.startsWith("TXERR") -> {
                main.removeCallbacks(writeTimeout)
                controller.onWriteComplete(false)
                controller.postStatus(line)
            }
            line.startsWith("STATE READY") -> {
                dikeyReady = true
                controller.attachSink(this)
                controller.onTransportReady()
            }
            line.startsWith("STATE DISCONNECTED") -> {
                dikeyReady = false
                controller.onTransportDown(line)
            }
            line.startsWith("STATE ") ||
                line.startsWith("LOG ") ||
                line.startsWith("HB ") ||
                line.startsWith("HELLO") ||
                line.startsWith("ID ") ||
                line.equals("PONG", ignoreCase = true) -> {
                controller.postStatus(line)
            }
            else -> Log.d(TAG, line)
        }
    }

    companion object {
        private const val TAG = "DiKeyUsbBridge"
        private const val WRITE_TIMEOUT_MS = 3000L
    }
}
