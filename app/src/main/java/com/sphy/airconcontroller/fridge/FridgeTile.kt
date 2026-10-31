package com.sphy.airconcontroller.fridge

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.sphy.airconcontroller.MainActivity
import com.sphy.airconcontroller.OpenDiKeyApp
import com.sphy.airconcontroller.R

/**
 * Home tile. Hidden until the fridge first delivers live readings in this app run; after that
 * it stays, showing temperatures and Eco/Max, or "Disconnected" with a Reconnect button.
 * Tapping opens [FridgeActivity].
 */
class FridgeTile(private val activity: MainActivity) {
    private val tile = activity.findViewById<View>(R.id.homeFridgeButton)
    private val temperatures = activity.findViewById<TextView>(R.id.homeFridgeTemperatures)
    private val status = activity.findViewById<TextView>(R.id.homeFridgeStatus)
    private val reconnect = activity.findViewById<Button>(R.id.homeFridgeReconnect)
    private val controller = OpenDiKeyApp.from(activity).fridge
    private val listener: () -> Unit = { render() }
    private var active = false

    init {
        tile.setOnClickListener {
            activity.startActivity(Intent(activity, FridgeActivity::class.java))
        }
        reconnect.setOnClickListener { controller.reconnect() }
        render()
    }

    fun start() {
        if (active) return
        active = true
        controller.addListener(listener)
        controller.attach()
        render()
    }

    fun stop() {
        if (!active) return
        active = false
        controller.removeListener(listener)
        // The ESP32 link stays up app-wide (history keeps recording); only the
        // Android BLE backup stops once no fridge UI is visible.
        controller.detach()
    }

    private fun render() {
        // INVISIBLE (not GONE) keeps the other tiles in this row the same size.
        tile.visibility = if (controller.seenConnected) View.VISIBLE else View.INVISIBLE
        val value = controller.status
        if (value != null) {
            val unit = if (value.celsius) "C" else "F"
            temperatures.text = activity.getString(R.string.fridge_tile_temps, value.left, value.right, unit)
            val max = controller.displayMax ?: value.max
            status.setText(if (max) R.string.fridge_mode_max_label else R.string.fridge_mode_eco_label)
            reconnect.visibility = View.GONE
        } else {
            temperatures.setText(R.string.fridge_disconnected_tile)
            status.text = controller.message
            reconnect.visibility = View.VISIBLE
        }
        tile.contentDescription = listOf(
            activity.getString(R.string.fridge_title), temperatures.text, status.text,
        ).joinToString(", ")
    }
}
