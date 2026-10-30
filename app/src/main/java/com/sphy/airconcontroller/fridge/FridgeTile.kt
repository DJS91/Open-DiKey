package com.sphy.airconcontroller.fridge

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.sphy.airconcontroller.MainActivity
import com.sphy.airconcontroller.OpenDiKeyApp
import com.sphy.airconcontroller.R
import java.util.Locale

class FridgeTile(private val activity: MainActivity) {
    private val temperatures = activity.findViewById<TextView>(R.id.homeFridgeTemperatures)
    private val status = activity.findViewById<TextView>(R.id.homeFridgeStatus)
    private val client = EvakoolClient(activity.applicationContext, ::render)
    private val usb = OpenDiKeyApp.from(activity).dikey.usbBridge.fridge
    private val prefs = activity.getSharedPreferences("evakool", Context.MODE_PRIVATE)
    private val useUsb: Boolean get() = prefs.getString("transport", "usb") != "android"
    private var active = false
    private var androidStarted = false
    private var dialog: AlertDialog? = null
    private var renderPicker: (() -> Unit)? = null
    private val permissions = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (client.hasPermissions()) showDevices()
        else {
            Toast.makeText(activity, R.string.fridge_permission_needed, Toast.LENGTH_LONG).show()
            status.setText(R.string.fridge_permission_needed)
        }
    }

    init {
        activity.findViewById<android.view.View>(R.id.homeFridgeButton).setOnClickListener {
            showActions()
        }
        render()
    }

    fun start() {
        active = true
        usb.changed = ::render
        usb.setEnabled(useUsb, SystemClock.elapsedRealtime())
        render()
    }

    fun stop() {
        active = false
        androidStarted = false
        usb.changed = null
        usb.setEnabled(false, SystemClock.elapsedRealtime())
        dialog?.setOnDismissListener(null)
        client.stop()
        dialog?.dismiss()
        dialog = null
        client.devicesChanged = null
        renderPicker = null
    }

    private fun render() {
        if (active && !useUsb) {
            if (usb.safeForAndroid && !androidStarted) {
                androidStarted = true
                client.start()
            } else if (!usb.safeForAndroid && androidStarted) {
                androidStarted = false
                client.stop()
            }
        }
        val value = if (useUsb) usb.status else if (usb.safeForAndroid) client.status else null
        temperatures.text = if (value == null) activity.getString(R.string.fridge_no_readings)
        else activity.getString(
            R.string.fridge_readings,
            value.left, value.right, if (value.celsius) "C" else "F",
            value.leftTarget, value.rightTarget,
        )
        status.text = if (useUsb) listOf(
            activity.getString(R.string.fridge_transport_usb), usb.device, usb.message,
        ).filter { it.isNotBlank() }.joinToString("\n")
        else listOfNotNull(
            activity.getString(R.string.fridge_transport_android), client.selectedName,
            if (usb.safeForAndroid) client.message else usb.message,
        ).joinToString("\n")
        renderPicker?.invoke()
    }

    private fun chooseDevice() {
        if (useUsb || !usb.safeForAndroid) {
            Toast.makeText(activity, R.string.fridge_android_wait_off, Toast.LENGTH_LONG).show()
            return
        }
        if (!client.hasPermissions()) permissions.launch(client.requiredPermissions())
        else showDevices()
    }

    private fun showDevices() {
        if (useUsb || !usb.safeForAndroid) {
            Toast.makeText(activity, R.string.fridge_android_wait_off, Toast.LENGTH_LONG).show()
            return
        }
        dialog?.dismiss()
        var devices = emptyList<EvakoolClient.Device>()
        var visibleDevices = emptyList<EvakoolClient.Device>()
        val view = activity.layoutInflater.inflate(R.layout.dialog_fridge_devices, null)
        val scanStatus = view.findViewById<TextView>(R.id.fridgeScanStatus)
        val progress = view.findViewById<View>(R.id.fridgeScanProgress)
        val showAll = view.findViewById<CheckBox>(R.id.fridgeShowAllDevices)
        val list = view.findViewById<ListView>(R.id.fridgeDeviceList)
        val empty = view.findViewById<TextView>(R.id.fridgeScanEmpty)
        val adapter = ArrayAdapter<String>(activity, R.layout.item_fridge_device)
        list.adapter = adapter
        list.emptyView = empty
        val picker = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.fridge_scan_again, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog = picker
        renderPicker = {
            visibleDevices = devices.filter { showAll.isChecked || it.recommended }
                .sortedByDescending { it.recommended }
            adapter.clear()
            adapter.addAll(visibleDevices.map { device ->
                activity.getString(
                    if (device.recommended) R.string.fridge_device_recommended else R.string.fridge_device_other,
                    device.name, device.address,
                )
            })
            scanStatus.text = activity.getString(
                R.string.fridge_scan_summary, client.message, devices.size, visibleDevices.size,
            )
            progress.visibility = if (client.scanning) View.VISIBLE else View.GONE
            empty.setText(
                when {
                    devices.isNotEmpty() && visibleDevices.isEmpty() -> R.string.fridge_scan_hidden
                    client.scanning -> R.string.fridge_scan_empty
                    else -> R.string.fridge_scan_empty_finished
                },
            )
            picker.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !client.scanning
        }
        client.devicesChanged = {
            devices = it
            renderPicker?.invoke()
        }
        showAll.setOnCheckedChangeListener { _, _ -> renderPicker?.invoke() }
        list.setOnItemClickListener { _, _, position, _ ->
            val device = visibleDevices[position]
            // End the scan before connecting; dismiss must not start a second connection.
            picker.setOnDismissListener(null)
            client.devicesChanged = null
            renderPicker = null
            picker.dismiss()
            client.select(device)
        }
        picker.setOnDismissListener {
            client.stopScan()
            client.devicesChanged = null
            renderPicker = null
            if (active && !useUsb && usb.safeForAndroid && client.selectedAddress != null) client.start()
        }
        picker.show()
        picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { client.scan() }
        client.scan()
        renderPicker?.invoke()
    }

    private fun showActions() {
        val canSet = !useUsb && usb.safeForAndroid && client.ready && client.status != null && client.pending == null
        val actions = mutableListOf<Int>()
        if (canSet) actions.addAll(listOf(R.string.fridge_set_left, R.string.fridge_set_right))
        actions.addAll(listOf(R.string.fridge_details, R.string.fridge_reconnect))
        if (!useUsb) actions.addAll(listOf(R.string.fridge_choose, R.string.fridge_forget))
        actions.add(if (useUsb) R.string.fridge_use_android else R.string.fridge_use_usb)
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.fridge_title)
            .setItems(actions.map(activity::getString).toTypedArray()) { _, index ->
                when (actions[index]) {
                    R.string.fridge_set_left -> editTemperature(1)
                    R.string.fridge_set_right -> editTemperature(2)
                    R.string.fridge_details -> showDetails()
                    R.string.fridge_choose -> chooseDevice()
                    R.string.fridge_reconnect -> {
                        if (useUsb || !usb.safeForAndroid) usb.reconnect(SystemClock.elapsedRealtime())
                        else if (!client.hasPermissions() || client.selectedAddress == null) chooseDevice()
                        else client.reconnect()
                    }
                    R.string.fridge_forget -> client.forget()
                    R.string.fridge_use_android -> switchTransport(false)
                    R.string.fridge_use_usb -> switchTransport(true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchTransport(toUsb: Boolean) {
        dialog?.dismiss()
        client.stop()
        androidStarted = false
        prefs.edit().putString("transport", if (toUsb) "usb" else "android").apply()
        usb.setEnabled(toUsb && active, SystemClock.elapsedRealtime())
        if (!toUsb && !usb.safeForAndroid) usb.reconnect(SystemClock.elapsedRealtime())
        render()
    }

    private fun showDetails() {
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.fridge_details)
            .setMessage(activity.getString(
                R.string.fridge_details_text,
                if (useUsb) activity.getString(R.string.fridge_transport_usb) else client.selectedName.orEmpty(),
                if (useUsb) usb.device else client.selectedAddress.orEmpty(),
                if (useUsb || !usb.safeForAndroid) usb.message else client.message,
                (if (useUsb) usb.lastFrame else client.lastFrame) ?: activity.getString(R.string.fridge_no_readings),
            ))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun editTemperature(compartment: Int) {
        if (useUsb || !usb.safeForAndroid) return
        val value = client.status ?: return
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(String.format(Locale.US, "%d", if (compartment == 1) value.leftTarget else value.rightTarget))
            selectAll()
            setSingleLine()
            contentDescription = activity.getString(R.string.fridge_target_label)
        }
        val editor = AlertDialog.Builder(activity)
            .setTitle(if (compartment == 1) R.string.fridge_set_left else R.string.fridge_set_right)
            .setMessage(activity.getString(R.string.fridge_set_warning, if (value.celsius) "C" else "F"))
            .setView(input)
            .setPositiveButton(R.string.fridge_set, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog = editor
        editor.setOnShowListener {
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val target = input.text.toString().trim().toIntOrNull()
                if (target == null || target !in -99..99) {
                    input.error = activity.getString(R.string.fridge_invalid_target)
                } else if (useUsb || !usb.safeForAndroid || !client.ready || client.status?.celsius != value.celsius) {
                    input.error = activity.getString(R.string.fridge_not_ready)
                } else {
                    client.setTemperature(compartment, target)
                    editor.dismiss()
                }
            }
        }
        editor.show()
    }
}
