package com.sphy.airconcontroller

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sphy.airconcontroller.dikey.DiKeyController
import com.sphy.airconcontroller.dikey.DiKeyEvent
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.dikey.DialDisplayType
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug overlay: live DiKey event / transport log, plus tools for diagnosing a dial that doesn't report turns. */
class DiKeyProbeActivity : OpenDiKeyActivity() {
    private lateinit var session: DiKeySession
    private lateinit var connStatusText: TextView
    private lateinit var eventLogText: TextView
    private lateinit var countersText: TextView
    private lateinit var pauseSwitch: SwitchMaterial

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val eventLines = ArrayDeque<String>(MAX_LOG_LINES)
    private val counters = DialCounters()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dikey_probe)

        session = OpenDiKeyApp.from(this).dikey
        connStatusText = findViewById(R.id.dikeyConnStatusText)
        eventLogText = findViewById(R.id.dikeyEventLog)
        countersText = findViewById(R.id.dialToolsCounters)
        pauseSwitch = findViewById(R.id.dialToolsPauseSwitch)

        findViewById<android.widget.ImageButton>(R.id.dikeyBackButton).setOnClickListener {
            finish()
        }
        findViewById<Button>(R.id.dikeyClearLogButton).setOnClickListener {
            eventLines.clear()
            eventLogText.text = getString(R.string.dikey_event_log_empty)
        }
        setUpDialTools()
        renderCounters()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.status.collect { connStatusText.text = it }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.events.collect {
                    counters.record(it)
                    renderCounters()
                    appendEvent(it)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.logs.collect { appendLogLine(it) }
            }
        }
    }

    override fun onStop() {
        // Never leave the dials disconnected from the car's climate when the tools aren't on screen.
        pauseSwitch.isChecked = false
        super.onStop()
    }

    private fun setUpDialTools() {
        pauseSwitch.isChecked = session.isDialTestMode
        pauseSwitch.setOnCheckedChangeListener { _, checked -> session.setDialTestMode(checked) }

        val lcdButtons = listOf(
            Triple(R.id.dialLeftPassengerTemp, true, DialDisplayType.PASSENGER_TEMP),
            Triple(R.id.dialLeftPassengerFan, true, DialDisplayType.PASSENGER_FAN),
            Triple(R.id.dialLeftDriverTemp, true, DialDisplayType.DRIVER_TEMP),
            Triple(R.id.dialLeftMedia, true, DialDisplayType.MEDIA_VOLUME),
            Triple(R.id.dialRightDriverTemp, false, DialDisplayType.DRIVER_TEMP),
            Triple(R.id.dialRightDriverFan, false, DialDisplayType.DRIVER_FAN),
            Triple(R.id.dialRightPassengerTemp, false, DialDisplayType.PASSENGER_TEMP),
            Triple(R.id.dialRightMedia, false, DialDisplayType.MEDIA_VOLUME),
        )
        for ((id, left, type) in lcdButtons) {
            findViewById<Button>(id).setOnClickListener {
                // The mode buttons are for experiments, so stop the climate sync repainting the LCD over them.
                pauseSwitch.isChecked = true
                toast(if (session.testDialMode(left, type)) R.string.dial_tools_sent else R.string.dial_tools_not_connected)
            }
        }

        val leftToggle = findViewById<MaterialButtonToggleGroup>(R.id.dialLeftAllowToggle)
        val rightToggle = findViewById<MaterialButtonToggleGroup>(R.id.dialRightAllowToggle)
        val current = session.dialAllowLists
        leftToggle.check(
            when {
                current.left == WIDE_TYPES -> R.id.dialLeftAllowWide
                current.leftAllowNone -> R.id.dialLeftAllowNone
                else -> R.id.dialLeftAllowDefault
            },
        )
        rightToggle.check(if (current.right == WIDE_TYPES) R.id.dialRightAllowWide else R.id.dialRightAllowDefault)

        findViewById<Button>(R.id.dialResendSetup).setOnClickListener {
            val defaults = DiKeyController.AllowLists()
            val lists = DiKeyController.AllowLists(
                left = if (leftToggle.checkedButtonId == R.id.dialLeftAllowWide) WIDE_TYPES else defaults.left,
                right = if (rightToggle.checkedButtonId == R.id.dialRightAllowWide) WIDE_TYPES else defaults.right,
                leftAllowNone = leftToggle.checkedButtonId == R.id.dialLeftAllowNone,
            )
            toast(if (session.resendDialSetup(lists)) R.string.dial_tools_sent else R.string.dial_tools_not_connected)
        }
        findViewById<Button>(R.id.dialResetCounters).setOnClickListener {
            counters.reset()
            renderCounters()
        }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun renderCounters() {
        countersText.text = getString(
            R.string.dial_tools_counters_fmt,
            counters.leftTurns, counters.leftClicks, counters.leftLong,
            counters.rightTurns, counters.rightClicks, counters.rightLong,
            counters.other,
        )
    }

    private fun appendEvent(event: DiKeyEvent) {
        appendLogLine("${event.summary}\n  ${event.hex}")
    }

    private fun appendLogLine(line: String) {
        val stamped = "${timeFmt.format(Date())}  $line"
        while (eventLines.size >= MAX_LOG_LINES) eventLines.removeFirst()
        eventLines.addFirst(stamped)
        eventLogText.text = eventLines.joinToString("\n\n")
    }

    /** Per-dial tallies of what the DiKey has actually sent while this page has been open. */
    private class DialCounters {
        var leftTurns = 0
        var leftClicks = 0
        var leftLong = 0
        var rightTurns = 0
        var rightClicks = 0
        var rightLong = 0
        var other = 0

        fun record(event: DiKeyEvent) {
            if (event !is DiKeyEvent.Encoder) {
                other++
                return
            }
            val left = event.side == "LEFT"
            when (event.event) {
                "ROTATE_RIGHT", "ROTATE_LEFT" -> if (left) leftTurns++ else rightTurns++
                "SINGLE_CLICK", "MULTI_CLICK" -> if (left) leftClicks++ else rightClicks++
                "LONG_PRESS" -> if (left) leftLong++ else rightLong++
                else -> other++
            }
        }

        fun reset() {
            leftTurns = 0; leftClicks = 0; leftLong = 0
            rightTurns = 0; rightClicks = 0; rightLong = 0
            other = 0
        }
    }

    companion object {
        private const val MAX_LOG_LINES = 40

        /** Driver and passenger temp + fan, as in the vendor protocol's example allow-list. */
        private val WIDE_TYPES = setOf(
            DialDisplayType.DRIVER_TEMP.code,
            DialDisplayType.DRIVER_FAN.code,
            DialDisplayType.PASSENGER_TEMP.code,
            DialDisplayType.PASSENGER_FAN.code,
        )
    }
}
