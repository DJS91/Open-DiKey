package com.sphy.airconcontroller.fridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.R
import java.util.UUID

@SuppressLint("MissingPermission")
class EvakoolClient(private val context: Context, private val changed: () -> Unit) {
    data class Device(val address: String, val name: String, val recommended: Boolean = false)
    data class Pending(val compartment: Int, val temperature: Int, val celsius: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("evakool", Context.MODE_PRIVATE)
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var active = false
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeBusy = false
    private val writes = ArrayDeque<String>()
    private val frames = EvakoolProtocol.Frames()
    private var targetUnconfirmed = false
    private var scanCallback: ScanCallback? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private val found = linkedMapOf<String, Device>()
    var devicesChanged: ((List<Device>) -> Unit)? = null

    val selectedName: String? get() = prefs.getString("name", null)
    val selectedAddress: String? get() = prefs.getString("address", null)
    var status: EvakoolProtocol.Status? = null
        private set
    var message: String = context.getString(R.string.fridge_select_hint)
        private set
    var ready = false
        private set
    var pending: Pending? = null
        private set
    var scanning = false
        private set
    var lastFrame: String? = null
        private set

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        active = true
        if (gatt == null && !scanning) connectSaved()
    }

    fun stop() {
        active = false
        stopScan()
        handler.removeCallbacksAndMessages(null)
        closeGatt()
        status = null
        pending = null
    }

    fun forget() {
        stop()
        lastFrame = null
        prefs.edit().remove("address").remove("name").apply()
        active = true
        update(R.string.fridge_select_hint)
    }

    fun select(device: Device) {
        stopScan()
        lastFrame = null
        prefs.edit().putString("address", device.address).putString("name", device.name).apply()
        reconnect()
    }

    fun reconnect() {
        handler.removeCallbacks(retry)
        closeGatt()
        status = null
        pending = null
        connectSaved()
    }

    private fun update(resource: Int, vararg args: Any) {
        message = context.getString(resource, *args)
        changed()
    }

    private fun connectSaved() {
        if (!active || scanning || gatt != null) return
        handler.removeCallbacks(retry)
        val address = selectedAddress ?: return update(R.string.fridge_select_hint)
        if (!hasPermissions()) return update(R.string.fridge_permission_needed)
        val bluetooth = adapter ?: return update(R.string.fridge_no_bluetooth)
        try {
            if (!bluetooth.isEnabled) {
                update(R.string.fridge_bluetooth_off)
                handler.postDelayed(retry, RETRY_MS)
                return
            }
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                update(R.string.fridge_invalid_address)
                return
            }
            update(R.string.fridge_connecting)
            gatt = bluetooth.getRemoteDevice(address).connectGatt(
                context, false, callback, BluetoothDevice.TRANSPORT_LE,
            )
            if (gatt == null) fail(context.getString(R.string.fridge_connect_failed))
            else handler.postDelayed(connectTimeout, 30_000)
        } catch (e: SecurityException) {
            permissionFailure(e)
        }
    }

    private val retry = Runnable { connectSaved() }
    private val connectTimeout = Runnable { fail(context.getString(R.string.fridge_connect_timeout)) }
    private val writeTimeout = Runnable { fail(context.getString(R.string.fridge_write_timeout)) }
    private val statusTimeout = Runnable { fail(context.getString(R.string.fridge_status_timeout)) }
    private val targetTimeout = Runnable {
        pending = null
        targetUnconfirmed = true
        update(R.string.fridge_target_unconfirmed)
    }
    private val quiet = Runnable {
        try {
            frames.finishUnterminated()?.let(::receiveFrame)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Incomplete or invalid status response", e)
            // Preserve a partial message until more bytes arrive or the status timeout fires.
            status = null
            update(R.string.fridge_invalid_response, e.message.orEmpty())
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!ready) return
            enqueue(EvakoolProtocol.STATUS_QUERY)
            handler.postDelayed(this, 5_000)
        }
    }

    fun setTemperature(compartment: Int, temperature: Int) {
        val current = status
        if (!ready || current == null || pending != null) {
            update(R.string.fridge_not_ready)
            return
        }
        val command = try {
            EvakoolProtocol.temperatureCommand(compartment, temperature)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid setpoint", e)
            update(R.string.fridge_invalid_target)
            return
        }
        pending = Pending(compartment, temperature, current.celsius)
        targetUnconfirmed = false
        handler.postDelayed(targetTimeout, 10_000)
        update(R.string.fridge_target_pending)
        enqueue(command)
        enqueue(EvakoolProtocol.STATUS_QUERY)
    }

    private fun enqueue(command: String) {
        if (command == EvakoolProtocol.STATUS_QUERY && writes.contains(command)) return
        writes.addLast(command)
        writeNext()
    }

    private fun writeNext() {
        if (writeBusy || !ready || writes.isEmpty()) return
        val g = gatt ?: return
        val ch = writeChar ?: return
        try {
            val bytes = writes.removeFirst().toByteArray(Charsets.US_ASCII)
            writeBusy = true
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            if (!accepted) fail(context.getString(R.string.fridge_write_failed))
            else handler.postDelayed(writeTimeout, 4_000)
        } catch (e: SecurityException) {
            permissionFailure(e)
        }
    }

    private fun receive(bytes: ByteArray) {
        handler.removeCallbacks(quiet)
        try {
            frames.append(bytes).forEach(::receiveFrame)
            handler.postDelayed(quiet, 500)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid fridge notification", e)
            frames.clear()
            status = null
            update(R.string.fridge_invalid_response, e.message.orEmpty())
        }
    }

    private fun receiveFrame(frame: String) {
        lastFrame = frame.take(256)
        Log.d(TAG, "RX: $lastFrame")
        if (frame.startsWith("/SEE/") || frame.startsWith("/SCE/")) {
            status = null
            pending = null
            handler.removeCallbacks(targetTimeout)
            update(R.string.fridge_fault, frame.take(100))
            return
        }
        try {
            val value = EvakoolProtocol.parseStatus(frame)
            status = value
            handler.removeCallbacks(statusTimeout)
            handler.postDelayed(statusTimeout, 20_000)
            val target = pending
            if (target != null) {
                val actual = if (target.compartment == 1) value.leftTarget else value.rightTarget
                if (actual == target.temperature && value.celsius == target.celsius) {
                    pending = null
                    handler.removeCallbacks(targetTimeout)
                    update(R.string.fridge_target_confirmed)
                } else update(R.string.fridge_target_pending)
            } else update(
                when {
                    targetUnconfirmed -> R.string.fridge_target_unconfirmed
                    value.on -> R.string.fridge_connected
                    else -> R.string.fridge_power_off
                },
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cannot decode fridge frame", e)
            status = null
            update(R.string.fridge_invalid_response, e.message.orEmpty())
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        closeGatt()
        status = null
        pending = null
        message = reason
        changed()
        if (active && !scanning) handler.postDelayed(retry, RETRY_MS)
    }

    private fun permissionFailure(e: SecurityException) {
        Log.w(TAG, "Bluetooth permission denied", e)
        fail(context.getString(R.string.fridge_permission_needed))
    }

    private fun closeGatt() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(writeTimeout)
        handler.removeCallbacks(statusTimeout)
        handler.removeCallbacks(targetTimeout)
        handler.removeCallbacks(poll)
        handler.removeCallbacks(quiet)
        handler.removeCallbacks(retry)
        ready = false
        writeBusy = false
        writes.clear()
        frames.clear()
        targetUnconfirmed = false
        writeChar = null
        val old = gatt
        gatt = null
        try {
            old?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission revoked while closing GATT", e)
        }
    }

    private fun onGatt(g: BluetoothGatt, block: () -> Unit) {
        handler.post {
            if (active && g === gatt) {
                try { block() } catch (e: SecurityException) { permissionFailure(e) }
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, newState: Int) = onGatt(g) {
            when {
                code != BluetoothGatt.GATT_SUCCESS ->
                    fail(context.getString(R.string.fridge_gatt_error, code))
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    update(R.string.fridge_discovering)
                    if (!g.discoverServices()) fail(context.getString(R.string.fridge_discovery_failed))
                }
                newState == BluetoothProfile.STATE_DISCONNECTED ->
                    fail(context.getString(R.string.fridge_disconnected))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) = onGatt(g) {
            if (code != BluetoothGatt.GATT_SUCCESS) {
                fail(context.getString(R.string.fridge_gatt_error, code))
                return@onGatt
            }
            val service = g.getService(UUID.fromString(EvakoolProtocol.SERVICE_UUID))
            val write = service?.getCharacteristic(UUID.fromString(EvakoolProtocol.WRITE_UUID))
            val notify = service?.getCharacteristic(UUID.fromString(EvakoolProtocol.NOTIFY_UUID))
            val cccd = notify?.getDescriptor(UUID.fromString(EvakoolProtocol.CCCD_UUID))
            if (write == null || notify == null || cccd == null ||
                write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0 ||
                notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            ) {
                fail(context.getString(R.string.fridge_incompatible))
                return@onGatt
            }
            writeChar = write
            if (!g.setCharacteristicNotification(notify, true)) {
                fail(context.getString(R.string.fridge_notify_failed))
                return@onGatt
            }
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!accepted) fail(context.getString(R.string.fridge_notify_failed))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, code: Int) =
            onGatt(g) {
                if (code != BluetoothGatt.GATT_SUCCESS) {
                    fail(context.getString(R.string.fridge_notify_failed))
                } else {
                    handler.removeCallbacks(connectTimeout)
                    ready = true
                    handler.postDelayed(statusTimeout, 20_000)
                    update(R.string.fridge_waiting_status)
                    handler.post(poll)
                }
            }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, code: Int) =
            onGatt(g) {
                handler.removeCallbacks(writeTimeout)
                writeBusy = false
                if (code != BluetoothGatt.GATT_SUCCESS) {
                    fail(context.getString(R.string.fridge_gatt_error, code))
                } else writeNext()
            }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = ch.value?.clone() ?: return
            onGatt(g) { if (ch.uuid.toString() == EvakoolProtocol.NOTIFY_UUID) receive(bytes) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray) =
            onGatt(g) { if (ch.uuid.toString() == EvakoolProtocol.NOTIFY_UUID) receive(bytes) }
    }

    fun scan() {
        stopScan()
        closeGatt()
        status = null
        pending = null
        found.clear()
        devicesChanged?.invoke(emptyList())
        if (!hasPermissions()) return update(R.string.fridge_permission_needed)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return update(R.string.fridge_no_bluetooth)
        }
        try {
            val bluetooth = adapter ?: return update(R.string.fridge_no_bluetooth)
            if (!bluetooth.isEnabled) return update(R.string.fridge_bluetooth_off)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R &&
                context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true
            ) {
                return update(R.string.fridge_location_off)
            }
            val le = bluetooth.bluetoothLeScanner ?: return update(R.string.fridge_no_bluetooth)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post {
                        if (scanCallback !== this || !scanning) return@post
                        try {
                            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                                ?: result.device.name.orEmpty()
                            val service = result.scanRecord?.serviceUuids.orEmpty().any {
                                it.uuid.toString() == EvakoolProtocol.SERVICE_UUID
                            }
                            if (result.isConnectable) {
                                val device = Device(result.device.address, name.ifBlank {
                                    context.getString(R.string.fridge_unnamed_device)
                                }, EvakoolProtocol.matchesName(name) || service)
                                if (found[device.address] != device) {
                                    found[device.address] = device
                                    devicesChanged?.invoke(found.values.toList())
                                }
                            }
                        } catch (e: SecurityException) {
                            stopScan()
                            permissionFailure(e)
                        }
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        if (scanCallback !== this) return@post
                        stopScan()
                        update(R.string.fridge_scan_failed, errorCode)
                    }
                }
            }
            scanner = le
            scanCallback = callback
            scanning = true
            update(R.string.fridge_scanning)
            le.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            handler.postDelayed(scanTimeout, 15_000)
        } catch (e: SecurityException) {
            stopScan()
            permissionFailure(e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Bluetooth became unavailable while scanning", e)
            stopScan()
            update(R.string.fridge_bluetooth_off)
        }
    }

    private val scanTimeout = Runnable {
        stopScan()
        update(if (found.isEmpty()) R.string.fridge_no_devices else R.string.fridge_scan_done)
    }

    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        val callback = scanCallback
        scanCallback = null
        scanning = false
        try {
            if (callback != null) scanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission revoked while stopping scan", e)
        }
        scanner = null
    }

    companion object {
        private const val TAG = "Evakool"
        private const val RETRY_MS = 15_000L
    }
}
