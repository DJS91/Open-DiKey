package com.sphy.airconcontroller.fridge

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sphy.airconcontroller.OpenDiKeyApp
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.ui.OpenDiKeyActivity

/** Fridge page: Bluetooth name, temperature history graph and target controls. */
class FridgeActivity : OpenDiKeyActivity() {
    private class Card(root: View, val compartment: Int) {
        val label: TextView = root.findViewById(R.id.compartmentLabel)
        val current: TextView = root.findViewById(R.id.compartmentCurrent)
        val target: TextView = root.findViewById(R.id.compartmentTarget)
        val up: Button = root.findViewById(R.id.compartmentUp)
        val down: Button = root.findViewById(R.id.compartmentDown)
    }

    private lateinit var controller: FridgeController
    private lateinit var name: TextView
    private lateinit var subtitle: TextView
    private lateinit var statusText: TextView
    private lateinit var info: TextView
    private lateinit var targetNote: TextView
    private lateinit var graph: FridgeGraphView
    private lateinit var cards: List<Card>
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private var renderingMode = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("evakool", Context.MODE_PRIVATE) }
    private var rangeMs = FridgeHistory.DAY_MS
    private var graphVersion = -1
    private var graphRange = 0L
    private var graphCelsius = true
    private var dialog: AlertDialog? = null
    private var renderPicker: (() -> Unit)? = null
    private val listener: () -> Unit = { render() }
    private val graphTick = object : Runnable {
        override fun run() {
            renderGraph(force = true)
            handler.postDelayed(this, 30_000)
        }
    }
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (controller.client.hasPermissions()) showDevices()
        else Toast.makeText(this, R.string.fridge_permission_needed, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fridge)
        controller = OpenDiKeyApp.from(this).fridge
        name = findViewById(R.id.fridgeName)
        subtitle = findViewById(R.id.fridgeSubtitle)
        statusText = findViewById(R.id.fridgeStatus)
        info = findViewById(R.id.fridgeInfo)
        targetNote = findViewById(R.id.fridgeTargetNote)
        graph = findViewById(R.id.fridgeGraph)
        graph.emptyText = getString(R.string.fridge_graph_empty)
        cards = listOf(
            Card(findViewById(R.id.fridgeLeftCard), 1),
            Card(findViewById(R.id.fridgeRightCard), 2),
        )
        cards[0].label.setText(R.string.fridge_left)
        cards[0].label.setTextColor(getColor(R.color.classic_accent))
        cards[1].label.setText(R.string.fridge_right)
        cards[1].label.setTextColor(getColor(R.color.gauge_charge))
        cards.forEach { card ->
            val side = card.label.text.toString()
            card.up.contentDescription = getString(R.string.fridge_up, side)
            card.down.contentDescription = getString(R.string.fridge_down, side)
            card.up.setOnClickListener { controller.adjust(card.compartment, +1) }
            card.down.setOnClickListener { controller.adjust(card.compartment, -1) }
        }
        findViewById<View>(R.id.fridgeBack).setOnClickListener { finish() }
        modeGroup = findViewById(R.id.fridgeMode)
        modeGroup.addOnButtonCheckedListener { _, id, checked ->
            // Ignore programmatic updates from render(); only user taps send a command.
            if (!checked || renderingMode) return@addOnButtonCheckedListener
            controller.setMode(id == R.id.fridgeModeMax)
            render()
        }
        findViewById<Button>(R.id.fridgeReconnect).setOnClickListener { reconnect() }
        findViewById<Button>(R.id.fridgeMore).setOnClickListener { showMore() }

        rangeMs = prefs.getLong("graph_range", FridgeHistory.DAY_MS)
        val group = findViewById<MaterialButtonToggleGroup>(R.id.fridgeRange)
        group.check(
            when (rangeMs) {
                FridgeHistory.HOUR_MS -> R.id.fridgeRangeHour
                FridgeHistory.WEEK_MS -> R.id.fridgeRangeWeek
                else -> R.id.fridgeRangeDay
            },
        )
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            rangeMs = when (id) {
                R.id.fridgeRangeHour -> FridgeHistory.HOUR_MS
                R.id.fridgeRangeWeek -> FridgeHistory.WEEK_MS
                else -> FridgeHistory.DAY_MS
            }
            prefs.edit().putLong("graph_range", rangeMs).apply()
            renderGraph(force = true)
        }
    }

    override fun onStart() {
        super.onStart()
        controller.addListener(listener)
        controller.attach()
        handler.post(graphTick)
        render()
    }

    override fun onStop() {
        handler.removeCallbacks(graphTick)
        controller.removeListener(listener)
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        controller.client.stopScan()
        controller.client.devicesChanged = null
        renderPicker = null
        controller.detach()
        super.onStop()
    }

    private fun render() {
        val status = controller.status
        val unit = if (status?.celsius != false) "C" else "F"
        name.text = controller.name ?: getString(R.string.fridge_unknown_name)
        subtitle.text = listOfNotNull(controller.transportLabel, controller.address).joinToString(" · ")
        val blocker = controller.controlBlocker
        val pending = controller.pendingCompartment
        cards.forEach { card ->
            val now = status?.let { if (card.compartment == 1) it.left else it.right }
            card.current.text = now?.let { "$it°$unit" } ?: "—"
            card.target.text = controller.displayTarget(card.compartment)?.let { "$it°$unit" } ?: "—"
            card.target.alpha = if (pending == card.compartment) 0.6f else 1f
            val enabled = blocker == null
            card.up.isEnabled = enabled
            card.down.isEnabled = enabled
        }
        info.text = if (status == null) "" else listOf(
            getString(R.string.fridge_power_state, getString(if (status.on) R.string.fridge_on else R.string.fridge_off)),
            getString(R.string.fridge_voltage, status.voltage),
        ).joinToString("\n")
        renderMode()
        val note = controller.targetNote ?: if (status != null) blocker else null
        targetNote.text = note
        targetNote.visibility = if (note.isNullOrBlank()) View.GONE else View.VISIBLE
        statusText.text = controller.message
        renderPicker?.invoke()
        renderGraph(force = false)
    }

    private fun renderMode() {
        val max = controller.displayMax
        val pendingMax = controller.pendingMax
        val blocker = controller.modeBlocker
        renderingMode = true
        if (max == null) modeGroup.clearChecked()
        else modeGroup.check(if (max) R.id.fridgeModeMax else R.id.fridgeModeEco)
        renderingMode = false
        // Allow taps whenever changes are possible; dim while a change is awaiting confirmation.
        val enabled = blocker == null
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = enabled
        modeGroup.alpha = if (pendingMax != null) 0.6f else 1f
    }

    private fun renderGraph(force: Boolean) {
        val history = controller.history
        val celsius = controller.status?.celsius ?: history.samples.lastOrNull()?.celsius ?: true
        if (!force && history.version == graphVersion && graphRange == rangeMs && graphCelsius == celsius) return
        val now = System.currentTimeMillis()
        graphVersion = history.version
        graphRange = rangeMs
        graphCelsius = celsius
        graph.setData(history.since(rangeMs, now), rangeMs, now, celsius)
    }

    private fun reconnect() {
        val client = controller.client
        if (!controller.useUsb && controller.usb.safeForAndroid &&
            (!client.hasPermissions() || client.selectedAddress == null)
        ) chooseDevice()
        else controller.reconnect()
    }

    private fun showMore() {
        val actions = mutableListOf(R.string.fridge_details)
        if (!controller.useUsb) actions.addAll(listOf(R.string.fridge_choose, R.string.fridge_forget))
        actions.add(if (controller.useUsb) R.string.fridge_use_android else R.string.fridge_use_usb)
        actions.add(R.string.fridge_clear_history)
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.fridge_title)
            .setItems(actions.map(::getString).toTypedArray()) { _, index ->
                when (actions[index]) {
                    R.string.fridge_details -> showDetails()
                    R.string.fridge_choose -> chooseDevice()
                    R.string.fridge_forget -> controller.client.forget()
                    R.string.fridge_use_android -> controller.switchTransport(false)
                    R.string.fridge_use_usb -> controller.switchTransport(true)
                    R.string.fridge_clear_history -> confirmClearHistory()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearHistory() {
        dialog = AlertDialog.Builder(this)
            .setMessage(R.string.fridge_clear_history_confirm)
            .setPositiveButton(R.string.fridge_clear_history) { _, _ ->
                controller.history.clear()
                renderGraph(force = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDetails() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.fridge_details)
            .setMessage(getString(
                R.string.fridge_details_text,
                listOfNotNull(controller.transportLabel, controller.name).joinToString(" · "),
                controller.address.orEmpty(),
                controller.message,
                controller.lastFrame ?: getString(R.string.fridge_no_readings),
            ))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun chooseDevice() {
        if (controller.useUsb || !controller.usb.safeForAndroid) {
            Toast.makeText(this, R.string.fridge_android_wait_off, Toast.LENGTH_LONG).show()
            return
        }
        val client = controller.client
        if (!client.hasPermissions()) permissions.launch(client.requiredPermissions())
        else showDevices()
    }

    private fun showDevices() {
        val client = controller.client
        if (controller.useUsb || !controller.usb.safeForAndroid) {
            Toast.makeText(this, R.string.fridge_android_wait_off, Toast.LENGTH_LONG).show()
            return
        }
        dialog?.dismiss()
        var devices = emptyList<EvakoolClient.Device>()
        var visibleDevices = emptyList<EvakoolClient.Device>()
        val view = layoutInflater.inflate(R.layout.dialog_fridge_devices, null)
        val scanStatus = view.findViewById<TextView>(R.id.fridgeScanStatus)
        val progress = view.findViewById<View>(R.id.fridgeScanProgress)
        val showAll = view.findViewById<CheckBox>(R.id.fridgeShowAllDevices)
        val list = view.findViewById<ListView>(R.id.fridgeDeviceList)
        val empty = view.findViewById<TextView>(R.id.fridgeScanEmpty)
        val adapter = ArrayAdapter<String>(this, R.layout.item_fridge_device)
        list.adapter = adapter
        list.emptyView = empty
        val picker = AlertDialog.Builder(this)
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
                getString(
                    if (device.recommended) R.string.fridge_device_recommended else R.string.fridge_device_other,
                    device.name, device.address,
                )
            })
            scanStatus.text = getString(R.string.fridge_scan_summary, client.message, devices.size, visibleDevices.size)
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
            if (!controller.useUsb && controller.usb.safeForAndroid && client.selectedAddress != null) client.start()
        }
        picker.show()
        picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { client.scan() }
        client.scan()
        renderPicker?.invoke()
    }
}
