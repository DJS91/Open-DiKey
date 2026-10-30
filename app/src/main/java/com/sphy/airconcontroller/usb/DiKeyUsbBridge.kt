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

/**
 * USB serial link to firmware/src/DiKeyUsbBridge. The C3 owns DiKey GATT;
 * this forwards TX hex and feeds RX notifies into [DiKeyController].
 */
class DiKeyUsbBridge(
    usbManager: UsbManager,
    private val controller: DiKeyController
) : DiKeyFrameSink {
    private val main = Handler(Looper.getMainLooper())
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
        fridge.onOpen(SystemClock.elapsedRealtime())
        main.removeCallbacks(fridgeTick)
        main.post(fridgeTick)
        return null
    }

    fun close() {
        dikeyReady = false
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
