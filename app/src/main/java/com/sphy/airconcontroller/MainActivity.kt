package com.sphy.airconcontroller

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.apps.ProjectionApps
import com.sphy.airconcontroller.apps.ProjectionAutoSwitch
import com.sphy.airconcontroller.boot.AdasAutoApplyCoordinator
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.fridge.FridgeTile
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import com.sphy.airconcontroller.update.AppUpdater
import com.sphy.airconcontroller.usb.UsbPermissionReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : OpenDiKeyActivity() {
    private lateinit var session: DiKeySession
    private lateinit var fridgeTile: FridgeTile
    private lateinit var connStatus: android.widget.TextView
    private lateinit var connDot: android.view.View
    private var dotPulse: ObjectAnimator? = null

    /** The running automatic switch to CarPlay, if any; a touch anywhere cancels it. */
    private var autoSwitchCountdown: Job? = null
    private var swallowingCancelTouch = false
    private lateinit var adasIcon: android.widget.ImageView

    private val adasHoldHandler = Handler(Looper.getMainLooper())
    private var adasHoldFired = false
    private val adasHoldRunnable = Runnable {
        adasHoldFired = true
        showAdasAutoApplyDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fridgeTile = FridgeTile(this)

        session = OpenDiKeyApp.from(this).dikey
        connStatus = findViewById(R.id.mainConnStatus)
        connDot = findViewById(R.id.mainConnDot)
        adasIcon = findViewById(R.id.homeAdasIcon)
        findViewById<android.widget.TextView>(R.id.homeVersionText).text = AppUpdater.currentVersionName(this)

        findViewById<android.view.View>(R.id.homeColorsButton).setOnClickListener {
            startActivity(Intent(this, ColorConfigActivity::class.java))
        }
        findViewById<android.view.View>(R.id.homeButtonsButton).setOnClickListener {
            startActivity(Intent(this, ButtonMappingActivity::class.java))
        }
        findViewById<android.view.View>(R.id.homeVehicleInfoButton).setOnClickListener {
            startActivity(Intent(this, VehicleInfoActivity::class.java))
        }
        findViewById<android.view.View>(R.id.homeAdasButton).let { adasButton ->
            adasButton.setOnClickListener {
                startActivity(Intent(this, AdasActivity::class.java))
            }
            adasButton.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        adasHoldFired = false
                        adasHoldHandler.postDelayed(adasHoldRunnable, ADAS_HOLD_MS)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        adasHoldHandler.removeCallbacks(adasHoldRunnable)
                        if (!adasHoldFired) v.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        adasHoldHandler.removeCallbacks(adasHoldRunnable)
                        true
                    }
                    else -> false
                }
            }
        }
        findViewById<android.view.View>(R.id.homeSentryButton).setOnClickListener {
            startActivity(Intent(this, SentryActivity::class.java))
        }
        findViewById<android.view.View>(R.id.homePetModeButton).setOnClickListener {
            startActivity(Intent(this, PetModeActivity::class.java))
        }
        findViewById<android.widget.ImageButton>(R.id.mainDebugButton).setOnClickListener {
            startActivity(Intent(this, DebugHubActivity::class.java))
        }
        findViewById<android.widget.ImageButton>(R.id.mainSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsHubActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    session.connectionState.collect { state ->
                        connStatus.setText(
                            when (state) {
                                DiKeySession.ConnectionState.CONNECTED -> R.string.dikey_state_connected
                                DiKeySession.ConnectionState.CONNECTING -> R.string.dikey_state_connecting
                                DiKeySession.ConnectionState.DISCONNECTED -> R.string.dikey_state_disconnected
                            },
                        )
                        showConnectionDot(state)
                    }
                } finally {
                    // Don't leave the pulse running while the screen is stopped.
                    stopDotPulse()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AdasAutoApplyCoordinator.succeeded.collect { succeeded ->
                    val color = if (succeeded) R.color.tyre_ok else R.color.status_warning
                    adasIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, color))
                }
            }
        }

        setUpProjectionShortcut()
        maybePromptHiddenApiConsent()
        startAdbSetupIfNeeded()
        handleLaunchIntent(intent)
        maybeRequestBluetoothPermissions()
    }

    override fun onStart() {
        super.onStart()
        fridgeTile.start()
        session.ensureConnected()
        // Re-kick ADAS auto-apply here too: if the process was already spun up at boot by
        // KeepAliveNotificationListener before the car's ADAS services were ready, opening the
        // app won't recreate the process/service, so nothing else would retry it.
        AdasAutoApplyCoordinator.ensureApplied(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun showConnectionDot(state: DiKeySession.ConnectionState) {
        val color = when (state) {
            DiKeySession.ConnectionState.CONNECTED -> R.color.tyre_ok
            DiKeySession.ConnectionState.CONNECTING -> R.color.status_warning
            DiKeySession.ConnectionState.DISCONNECTED -> R.color.text_muted
        }
        connDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        if (state == DiKeySession.ConnectionState.CONNECTING) startDotPulse() else stopDotPulse()
    }

    private fun startDotPulse() {
        if (dotPulse?.isRunning == true) return
        dotPulse = ObjectAnimator.ofFloat(connDot, android.view.View.ALPHA, 1f, DOT_PULSE_MIN_ALPHA).apply {
            duration = DOT_PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopDotPulse() {
        dotPulse?.cancel()
        dotPulse = null
        connDot.alpha = 1f
    }

    /**
     * Shows a shortcut to the car's CarPlay / Android Auto app, but only while a phone is projecting
     * (see [ProjectionApps.isPhoneProjecting]). Re-checked every few seconds while this screen is visible.
     *
     * With "Switch to CarPlay automatically" on, it also starts a short countdown and then opens that app,
     * once, when a phone is projecting and the ADAS profile is applied (see [ProjectionAutoSwitch]).
     * Touching the screen during the countdown cancels it (see [dispatchTouchEvent]).
     */
    private fun setUpProjectionShortcut() {
        val tile = findViewById<android.view.View>(R.id.homeProjectionButton)
        val icon = findViewById<android.widget.ImageView>(R.id.homeProjectionIcon)
        val title = findViewById<android.widget.TextView>(R.id.homeProjectionTitle)
        var shown: ProjectionApps.Entry? = null

        tile.setOnClickListener {
            val entry = shown ?: return@setOnClickListener
            openProjection(entry)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Children of this scope are cancelled when the screen stops, which also cancels a countdown.
                val scope = this
                while (true) {
                    val (entry, projecting) = withContext(Dispatchers.IO) {
                        val app = runCatching { ProjectionApps.find(this@MainActivity) }.getOrNull()
                        val active = if (app == null) false else runCatching { ProjectionApps.isPhoneProjecting(this@MainActivity) }.getOrNull()
                        app to active
                    }
                    // null means "couldn't tell": leave the tile as it is rather than flicker it.
                    if (projecting != null) {
                        val target = if (projecting) entry else null
                        if (target?.packageName != shown?.packageName) {
                            if (target != null) {
                                icon.setImageDrawable(target.icon)
                                title.text = target.label
                                tile.contentDescription = target.label
                            }
                            // INVISIBLE, not GONE: the slot stays reserved so the other tiles keep their size.
                            tile.visibility = if (target != null) android.view.View.VISIBLE else android.view.View.INVISIBLE
                        }
                        shown = target
                    }

                    if (AppSettings(this@MainActivity).autoSwitchToCarPlay) {
                        // With ADAS auto-apply off there is no profile to wait for, so don't wait on it.
                        val adasApplied = !AdasAutoApplyCoordinator.isAutoApplyActive(this@MainActivity) ||
                            AdasAutoApplyCoordinator.succeeded.value
                        if (autoSwitchCountdown?.isActive == true && ProjectionAutoSwitch.shouldAbort(projecting, adasApplied)) {
                            autoSwitchCountdown?.cancel()
                        }
                        if (entry != null && ProjectionAutoSwitch.shouldStart(projecting, adasApplied)) {
                            autoSwitchCountdown = scope.launch { runAutoSwitchCountdown(entry) }
                        }
                    } else {
                        autoSwitchCountdown?.cancel()
                    }
                    delay(PROJECTION_POLL_MS)
                }
            }
        }
    }

    /**
     * Counts down on the tile's subtitle ("Launching in 3", "Launching in 2", "Launching in 1") and then opens [entry]. Cancelling the
     * coroutine (a touch, the phone disconnecting, leaving the screen) restores the subtitle.
     */
    private suspend fun runAutoSwitchCountdown(entry: ProjectionApps.Entry) {
        val subtitle = findViewById<android.widget.TextView>(R.id.homeProjectionSubtitle)
        val normalText = getString(R.string.projection_home_subtitle)
        try {
            for (second in AUTO_SWITCH_COUNTDOWN_S downTo 1) {
                subtitle.text = getString(R.string.projection_auto_launching, second)
                delay(1_000L)
            }
            openProjection(entry)
        } finally {
            subtitle.text = normalText
        }
    }

    private fun openProjection(entry: ProjectionApps.Entry) {
        runCatching { startActivity(entry.launchIntent) }.onFailure {
            Toast.makeText(this, getString(R.string.projection_open_failed, entry.label), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Any touch while the automatic switch is counting down cancels it. That touch is swallowed, so a
     * stray tap meant to stop the countdown can't also press a tile underneath.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && autoSwitchCountdown?.isActive == true) {
            autoSwitchCountdown?.cancel()
            swallowingCancelTouch = true
            return true
        }
        if (swallowingCancelTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowingCancelTouch = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        fridgeTile.stop()
        super.onStop()
    }

    override fun onDestroy() {
        adasHoldHandler.removeCallbacks(adasHoldRunnable)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BT_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            session.ensureConnected()
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            UsbPermissionReceiver.ACTION -> session.onUsbPermissionResult(intent)
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = deviceExtra(intent) ?: return
                session.tryConnectUsb(device)
            }
        }
    }

    private fun deviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun maybeRequestBluetoothPermissions() {
        if (session.isConnected || session.isUsbOpen) return
        if (session.hasBluetoothPermissions()) return
        ActivityCompat.requestPermissions(
            this,
            session.requiredBluetoothPermissions(),
            BT_PERMISSION_REQUEST
        )
    }

    private fun showAdasAutoApplyDialog() {
        val settings = AppSettings(this)
        val view = layoutInflater.inflate(R.layout.dialog_adas_auto_apply, null)
        val toggle = view.findViewById<SwitchMaterial>(R.id.adasAutoApplyDialogSwitch)
        toggle.isChecked = settings.adasApplyOnBoot
        AlertDialog.Builder(this)
            .setTitle(R.string.adas_auto_apply_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.adas_auto_apply_save) { _, _ ->
                settings.adasApplyOnBoot = toggle.isChecked
            }
            .setNegativeButton(R.string.adas_auto_apply_cancel, null)
            .show()
    }

    private fun startAdbSetupIfNeeded() {
        lifecycleScope.launch {
            AdbPermissionManager.ensureVehicleApiAccess(this@MainActivity)
            if (!AdbPermissionManager.isSetupComplete(this@MainActivity)) {
                AdbPermissionManager.runSetup(this@MainActivity)
            }
        }
    }

    private fun maybePromptHiddenApiConsent() {
        if (AdbPermissionManager.hasHiddenApiConsent(this) ||
            AdbPermissionManager.hasBeenPromptedForHiddenApi(this)
        ) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.hidden_api_title)
            .setMessage(R.string.hidden_api_body)
            .setCancelable(false)
            .setPositiveButton(R.string.hidden_api_allow) { _, _ ->
                AdbPermissionManager.setHiddenApiConsent(this, true)
                AdbPermissionManager.markHiddenApiPrompted(this)
                lifecycleScope.launch {
                    val applied = AdbPermissionManager.ensureVehicleApiAccess(this@MainActivity)
                    if (applied) AdbPermissionManager.restartApp(this@MainActivity)
                }
            }
            .setNegativeButton(R.string.hidden_api_not_now) { _, _ ->
                AdbPermissionManager.markHiddenApiPrompted(this)
            }
            .show()
    }

    companion object {
        private const val BT_PERMISSION_REQUEST = 1001
        private const val ADAS_HOLD_MS = 5_000L
        private const val DOT_PULSE_MS = 800L
        private const val PROJECTION_POLL_MS = 4_000L
        private const val AUTO_SWITCH_COUNTDOWN_S = 3
        private const val DOT_PULSE_MIN_ALPHA = 0.2f
    }
}
