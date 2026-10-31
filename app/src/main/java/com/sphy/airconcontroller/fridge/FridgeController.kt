package com.sphy.airconcontroller.fridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sphy.airconcontroller.R
import java.io.File

/**
 * App-wide fridge state shared by the home tile and [FridgeActivity].
 * The ESP32 USB link stays enabled for the whole process so history keeps recording;
 * the Android BLE backup only runs while a fridge UI is visible ([attach]/[detach]).
 */
class FridgeController(private val context: Context, val usb: UsbFridgeSession) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("evakool", Context.MODE_PRIVATE)
    private val listeners = linkedSetOf<() -> Unit>()
    private var visible = 0
    private var androidStarted = false
    private val desired = arrayOfNulls<Int>(3)
    private var note: String? = null
    val client = EvakoolClient(context, ::onChanged)
    val history = FridgeHistory(FridgeHistory.FileStorage(File(context.filesDir, "fridge_history.csv")))
    private val alerts = FridgeAlerts()
    private val notifier = FridgeNotifier(context)
    private val alertTick = object : Runnable {
        override fun run() {
            checkAlerts()
            handler.postDelayed(this, ALERT_TICK_MS)
        }
    }

    val useUsb: Boolean get() = prefs.getString("transport", "usb") != "android"

    /** Bluetooth name of the fridge (last known name over USB). */
    val name: String? get() = if (useUsb) prefs.getString("usb_name", null) else client.selectedName

    val address: String? get() = if (useUsb) usb.device.substringAfterLast(' ', "").ifBlank { null } else client.selectedAddress

    val status: EvakoolProtocol.Status?
        get() = if (useUsb) usb.status else if (usb.safeForAndroid) client.status else null

    val message: String
        get() = if (useUsb || !usb.safeForAndroid) usb.message else client.message

    /** Outcome of the latest target change (or why it was refused). */
    val targetNote: String? get() = note ?: if (useUsb) usb.targetNote else null

    val lastFrame: String? get() = if (useUsb) usb.lastFrame else client.lastFrame

    val transportLabel: String
        get() = context.getString(if (useUsb) R.string.fridge_transport_usb else R.string.fridge_transport_android)

    /** Compartment (1 or 2) currently awaiting confirmation, if any. */
    val pendingCompartment: Int?
        get() = if (useUsb) usb.pending?.compartment else client.pending?.compartment

    /** True once live readings have arrived during this app run; the home tile stays visible after that. */
    var seenConnected = false
        private set

    val connected: Boolean get() = status != null

    /** Mode change written but not yet reported back by the fridge (true = Max). */
    val pendingMax: Boolean? get() = if (useUsb) usb.pendingMax else client.pendingMax

    /** Mode to display: in-flight value first, then the fridge's reported mode. */
    val displayMax: Boolean? get() = pendingMax ?: status?.max

    /** Null when Eco/Max can be changed, otherwise a user-facing reason. */
    val modeBlocker: String?
        get() = when {
            status == null -> context.getString(R.string.fridge_not_ready)
            useUsb && !usb.canSetMode -> context.getString(R.string.fridge_usb_update_firmware)
            !useUsb && (!usb.safeForAndroid || !client.ready) -> context.getString(R.string.fridge_not_ready)
            pendingMax != null -> context.getString(R.string.fridge_mode_pending)
            else -> null
        }

    fun setMode(max: Boolean) {
        modeBlocker?.let { note = it; expireNote(); notifyListeners(); return }
        if (status?.max == max) return
        note = null
        if (useUsb) usb.setMode(max, SystemClock.elapsedRealtime()) else client.setMode(max)
        notifyListeners()
    }

    /** Power change written but not yet reported back by the fridge (true = on). */
    val pendingOn: Boolean? get() = if (useUsb) usb.pendingOn else client.pendingOn

    /** Power state to display: in-flight value first, then the fridge's reported state. */
    val displayOn: Boolean? get() = pendingOn ?: status?.on

    /** Null when power can be switched, otherwise a user-facing reason. */
    val powerBlocker: String?
        get() = when {
            status == null -> context.getString(R.string.fridge_not_ready)
            useUsb && !usb.canSetPower -> context.getString(R.string.fridge_usb_update_firmware)
            !useUsb && (!usb.safeForAndroid || !client.ready) -> context.getString(R.string.fridge_not_ready)
            pendingOn != null -> context.getString(R.string.fridge_power_pending)
            else -> null
        }

    fun setPower(on: Boolean) {
        powerBlocker?.let { note = it; expireNote(); notifyListeners(); return }
        if (status?.on == on) return
        note = null
        if (useUsb) usb.setPower(on, SystemClock.elapsedRealtime()) else client.setPower(on)
        notifyListeners()
    }

    /** Null when targets can be changed, otherwise a user-facing reason. */
    val controlBlocker: String?
        get() = when {
            status == null -> context.getString(R.string.fridge_not_ready)
            useUsb && !usb.canControl -> context.getString(R.string.fridge_usb_update_firmware)
            !useUsb && (!usb.safeForAndroid || !client.ready) -> context.getString(R.string.fridge_not_ready)
            else -> null
        }

    init {
        usb.changed = ::onChanged
        usb.setEnabled(useUsb, SystemClock.elapsedRealtime())
        handler.postDelayed(alertTick, ALERT_TICK_MS)
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    fun attach() {
        visible++
        onChanged()
    }

    fun detach() {
        visible = (visible - 1).coerceAtLeast(0)
        if (visible == 0 && androidStarted) {
            androidStarted = false
            client.stop()
        }
    }

    /** Target shown in the UI: unsent adjustment, then in-flight value, then fridge value. */
    fun displayTarget(compartment: Int): Int? {
        desired[compartment]?.let { return it }
        val p = if (useUsb) usb.pending?.let { it.compartment to it.temperature }
        else client.pending?.let { it.compartment to it.temperature }
        if (p != null && p.first == compartment) return p.second
        val s = status ?: return null
        return if (compartment == 1) s.leftTarget else s.rightTarget
    }

    /** Steps a compartment target; the change is sent after a short pause so taps can be combined. */
    fun adjust(compartment: Int, delta: Int) {
        require(compartment in 1..2)
        controlBlocker?.let { note = it; expireNote(); notifyListeners(); return }
        val s = status ?: return
        val current = displayTarget(compartment) ?: return
        val range = targetRange(s.celsius)
        val next = (current + delta).coerceIn(range)
        note = if (next == current) context.getString(R.string.fridge_target_limit, range.first, range.last) else null
        if (note != null) expireNote()
        if (next != current) desired[compartment] = next
        handler.removeCallbacks(sendDesired)
        handler.postDelayed(sendDesired, SEND_DELAY_MS)
        notifyListeners()
    }

    fun reconnect() {
        note = null
        desired.fill(null)
        if (useUsb || !usb.safeForAndroid) usb.reconnect(SystemClock.elapsedRealtime())
        else client.reconnect()
        onChanged()
    }

    fun switchTransport(toUsb: Boolean) {
        note = null
        desired.fill(null)
        client.stop()
        androidStarted = false
        prefs.edit().putString("transport", if (toUsb) "usb" else "android").apply()
        usb.setEnabled(toUsb, SystemClock.elapsedRealtime())
        if (!toUsb && !usb.safeForAndroid) usb.reconnect(SystemClock.elapsedRealtime())
        onChanged()
    }

    private val sendDesired = object : Runnable {
        override fun run() {
            val s = status
            if (s == null) {
                desired.fill(null)
                notifyListeners()
                return
            }
            for (c in 1..2) {
                val want = desired[c] ?: continue
                val actual = if (c == 1) s.leftTarget else s.rightTarget
                if (want == actual && pendingCompartment == null) { desired[c] = null; continue }
                if (pendingCompartment != null) {
                    handler.postDelayed(this, RETRY_MS)
                    return
                }
                desired[c] = null
                if (useUsb) usb.setTarget(c, want, SystemClock.elapsedRealtime())
                else client.setTemperature(c, want)
                if (desired.any { it != null }) handler.postDelayed(this, RETRY_MS)
                notifyListeners()
                return
            }
            notifyListeners()
        }
    }

    private fun onChanged() {
        if (!useUsb && visible > 0) {
            if (usb.safeForAndroid && !androidStarted) {
                androidStarted = true
                client.start()
            } else if (!usb.safeForAndroid && androidStarted) {
                androidStarted = false
                client.stop()
            }
        }
        if (useUsb) {
            val n = usb.device.substringBeforeLast(' ', usb.device).trim()
            if (n.isNotBlank() && n != prefs.getString("usb_name", null)) prefs.edit().putString("usb_name", n).apply()
        }
        status?.let {
            seenConnected = true
            history.record(it, System.currentTimeMillis())
        }
        checkAlerts()
        notifyListeners()
    }

    private fun checkAlerts() {
        // The Android backup only runs while a fridge screen is open, so its absence isn't a disconnect.
        if (!useUsb && visible == 0) {
            alerts.pause()
            return
        }
        alerts.update(status, SystemClock.elapsedRealtime(), System.currentTimeMillis())
            .forEach { notifier.handle(it, name) }
    }

    private val clearNote = Runnable {
        note = null
        notifyListeners()
    }

    private fun expireNote() {
        handler.removeCallbacks(clearNote)
        handler.postDelayed(clearNote, 8_000)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    companion object {
        private const val SEND_DELAY_MS = 1_200L
        private const val RETRY_MS = 1_000L
        private const val ALERT_TICK_MS = 15_000L

        /** Conservative app-side guard; the fridge model's real limits are not published in the app. */
        fun targetRange(celsius: Boolean): IntRange = if (celsius) -25..20 else -13..68
    }
}
