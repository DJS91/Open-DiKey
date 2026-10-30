package com.sphy.airconcontroller.fridge

import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.sphy.airconcontroller.MainActivity
import com.sphy.airconcontroller.R
import java.util.Locale

class FridgeTile(private val activity: MainActivity) {
    private val temperatures = activity.findViewById<TextView>(R.id.homeFridgeTemperatures)
    private val status = activity.findViewById<TextView>(R.id.homeFridgeStatus)
    private val client = EvakoolClient(activity.applicationContext, ::render)
    private var dialog: AlertDialog? = null
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
            if (client.selectedAddress == null) chooseDevice() else showActions()
        }
        render()
    }

    fun start() = client.start()

    fun stop() {
        dialog?.setOnDismissListener(null)
        client.stop()
        dialog?.dismiss()
        dialog = null
        client.devicesChanged = null
        client.scanFinished = null
    }

    private fun render() {
        val value = client.status
        temperatures.text = if (value == null) activity.getString(R.string.fridge_no_readings)
        else activity.getString(
            R.string.fridge_readings,
            value.left, value.right, if (value.celsius) "C" else "F",
            value.leftTarget, value.rightTarget,
        )
        status.text = listOfNotNull(client.selectedName, client.message).joinToString("\n")
    }

    private fun chooseDevice() {
        if (!client.hasPermissions()) permissions.launch(client.requiredPermissions())
        else showDevices()
    }

    private fun showDevices() {
        dialog?.dismiss()
        var devices = emptyList<EvakoolClient.Device>()
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        val picker = AlertDialog.Builder(activity)
            .setTitle(R.string.fridge_scanning)
            .setAdapter(adapter) { _, position -> client.select(devices[position]) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog = picker
        client.devicesChanged = {
            devices = it
            adapter.clear()
            adapter.addAll(it.map { device -> "${device.name}\n${device.address}" })
        }
        client.scanFinished = { picker.setTitle(client.message) }
        picker.setOnDismissListener {
            client.stopScan()
            client.devicesChanged = null
            client.scanFinished = null
            if (client.selectedAddress != null) client.start()
        }
        picker.show()
        client.scan()
        if (!client.scanning) picker.setTitle(client.message)
    }

    private fun showActions() {
        val canSet = client.ready && client.status != null && client.pending == null
        val actions = mutableListOf<Int>()
        if (canSet) actions.addAll(listOf(R.string.fridge_set_left, R.string.fridge_set_right))
        actions.addAll(listOf(
            R.string.fridge_details, R.string.fridge_reconnect, R.string.fridge_choose, R.string.fridge_forget,
        ))
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.fridge_title)
            .setItems(actions.map(activity::getString).toTypedArray()) { _, index ->
                when (actions[index]) {
                    R.string.fridge_set_left -> editTemperature(1)
                    R.string.fridge_set_right -> editTemperature(2)
                    R.string.fridge_details -> showDetails()
                    R.string.fridge_choose -> chooseDevice()
                    R.string.fridge_reconnect -> {
                        if (!client.hasPermissions()) chooseDevice() else client.reconnect()
                    }
                    R.string.fridge_forget -> client.forget()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDetails() {
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.fridge_details)
            .setMessage(activity.getString(
                R.string.fridge_details_text,
                client.selectedName.orEmpty(), client.selectedAddress.orEmpty(), client.message,
                client.lastFrame ?: activity.getString(R.string.fridge_no_readings),
            ))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun editTemperature(compartment: Int) {
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
                } else if (!client.ready || client.status?.celsius != value.celsius) {
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
