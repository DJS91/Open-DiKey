package com.sphy.airconcontroller

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.PetModeProfile
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PetModeActivity : OpenDiKeyActivity() {
    private lateinit var ac: BydAcController
    private lateinit var vehicleInfo: BydVehicleInfoController
    private lateinit var settings: AppSettings
    private lateinit var temperatureText: TextView
    private lateinit var statusText: TextView
    private lateinit var readyWarningText: TextView
    private lateinit var activeContentViews: List<View>
    private var configWasOpened = false
    private var exiting = false
    private var petModeStarted = false
    private var vehicleReadyConfirmed = false
    private var profilePending = false
    private var profileApplyInProgress = false
    private var originalBrightness: Int? = null
    private var originalBrightnessFloat: Float? = null
    private var originalBrightnessMode: Int? = null
    private var brightnessJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pet_mode)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = 1.0f
        }
        captureBrightnessSettings()
        brightnessJob = lifecycleScope.launch {
            forceSystemBrightness()
        }

        ac = BydAcController(this)
        vehicleInfo = BydVehicleInfoController(this)
        settings = AppSettings(this)
        temperatureText = findViewById(R.id.petTemperatureText)
        statusText = findViewById(R.id.petStatusText)
        readyWarningText = findViewById(R.id.petReadyWarningText)
        activeContentViews = listOf<View>(
            findViewById<View>(R.id.petOwnerMessage),
            findViewById<View>(R.id.petTemperatureLabel),
            temperatureText,
            statusText,
        )

        findViewById<ImageButton>(R.id.petBackButton).setOnClickListener {
            exitPetMode()
        }
        findViewById<ImageButton>(R.id.petSettingsButton).setOnClickListener {
            configWasOpened = true
            startActivity(Intent(this, PetModeConfigActivity::class.java))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitPetMode()
            }
        })

        showWaitingForReady(checking = true)
        monitorVehicleReadyState()
        monitorPetModeEnforcement()
    }

    override fun onResume() {
        super.onResume()
        if (configWasOpened) {
            configWasOpened = false
            val profile = settings.petModeProfile()
            showProfile(profile)
            if (petModeStarted && vehicleReadyConfirmed) {
                applyProfile(profile)
            } else {
                profilePending = true
            }
        }
    }

    private fun enterPetMode() {
        if (profileApplyInProgress) return
        profileApplyInProgress = true
        val profile = settings.petModeProfile()
        showProfile(profile)
        statusText.setText(R.string.pet_mode_starting)
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    if (!sessionActive) {
                        originalSnapshot = ac.snapshot()
                        sessionActive = true
                    }
                    ac.applyPetMode(
                        fanLevel = profile.fanLevel,
                        temperatureC = profile.temperatureC,
                        recirculating = profile.recirculating,
                        windModeIndex = profile.windModeIndex,
                    )
                }
                showApplyResult(results)
            } finally {
                profileApplyInProgress = false
            }
        }
    }

    private fun applyProfile(profile: PetModeProfile, showProgress: Boolean = true) {
        if (profileApplyInProgress) {
            profilePending = true
            return
        }
        profileApplyInProgress = true
        showProfile(profile)
        if (showProgress) statusText.setText(R.string.pet_mode_starting)
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    ac.applyPetMode(
                        fanLevel = profile.fanLevel,
                        temperatureC = profile.temperatureC,
                        recirculating = profile.recirculating,
                        windModeIndex = profile.windModeIndex,
                    )
                }
                showApplyResult(results)
            } finally {
                profileApplyInProgress = false
            }
        }
    }

    private fun showProfile(profile: PetModeProfile) {
        temperatureText.text = getString(
            R.string.pet_mode_temp_value_fmt,
            profile.temperatureC,
        )
    }

    private fun showApplyResult(results: List<BydAcController.CommandResult>) {
        statusText.setText(
            if (results.all { it.success }) {
                R.string.pet_mode_active
            } else {
                R.string.pet_mode_apply_failed
            }
        )
    }

    private fun monitorVehicleReadyState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val ready = withContext(Dispatchers.IO) {
                        vehicleInfo.readReadyState().isReady
                    }
                    when (ready) {
                        true -> {
                            vehicleReadyConfirmed = true
                            showReadyContent()
                            if (!petModeStarted) {
                                petModeStarted = true
                                profilePending = false
                                enterPetMode()
                            } else if (profilePending) {
                                profilePending = false
                                applyProfile(settings.petModeProfile())
                            }
                        }
                        false -> {
                            vehicleReadyConfirmed = false
                            showWaitingForReady(checking = false)
                        }
                        null -> {
                            vehicleReadyConfirmed = false
                            showWaitingForReady(checking = true)
                        }
                    }
                    delay(READY_POLL_MS)
                }
            }
        }
    }

    private fun showWaitingForReady(checking: Boolean) {
        activeContentViews.forEach { it.visibility = View.GONE }
        readyWarningText.setText(
            if (checking) {
                R.string.pet_mode_checking_ready
            } else {
                R.string.pet_mode_vehicle_not_ready
            }
        )
        readyWarningText.visibility = View.VISIBLE
    }

    private fun showReadyContent() {
        activeContentViews.forEach { it.visibility = View.VISIBLE }
        readyWarningText.visibility = View.GONE
    }

    private fun monitorPetModeEnforcement() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(PROFILE_REAPPLY_MS)
                    if (petModeStarted && vehicleReadyConfirmed && !exiting) {
                        applyProfile(settings.petModeProfile(), showProgress = false)
                    }
                }
            }
        }
    }

    private fun captureBrightnessSettings() {
        val resolver = contentResolver
        originalBrightness = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        originalBrightnessFloat = runCatching {
            Settings.System.getFloat(resolver, SCREEN_BRIGHTNESS_FLOAT)
        }.getOrNull()
        originalBrightnessMode = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrNull()
    }

    private suspend fun forceSystemBrightness() {
        val wroteDirectly = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = contentResolver
                val mode = Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                val level = Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    MAX_SYSTEM_BRIGHTNESS,
                )
                val levelFloat = Settings.System.putFloat(
                    resolver,
                    SCREEN_BRIGHTNESS_FLOAT,
                    1.0f,
                )
                mode && level && levelFloat
            }.getOrDefault(false)
        }
        if (!wroteDirectly) {
            AdbPermissionManager.runShellCommand(
                this,
                "settings put system screen_brightness_mode 0; " +
                    "settings put system screen_brightness 255; " +
                    "settings put system screen_brightness_float 1.0"
            )
        }
    }

    private suspend fun restoreSystemBrightness() {
        val brightness = originalBrightness ?: return
        val brightnessFloat = originalBrightnessFloat
        val mode = originalBrightnessMode
        val restoredDirectly = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = contentResolver
                val level = Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness,
                )
                val levelFloat = brightnessFloat?.let {
                    Settings.System.putFloat(resolver, SCREEN_BRIGHTNESS_FLOAT, it)
                } ?: true
                val restoredMode = mode?.let {
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, it)
                } ?: true
                level && levelFloat && restoredMode
            }.getOrDefault(false)
        }
        if (!restoredDirectly) {
            val floatCommand = brightnessFloat?.let {
                "; settings put system screen_brightness_float $it"
            }.orEmpty()
            val modeCommand = mode?.let {
                "; settings put system screen_brightness_mode $it"
            }.orEmpty()
            AdbPermissionManager.runShellCommand(
                this,
                "settings put system screen_brightness $brightness" +
                    floatCommand + modeCommand
            )
        }
    }

    private fun exitPetMode() {
        if (exiting) return
        exiting = true
        statusText.setText(R.string.pet_mode_restoring)
        findViewById<ImageButton>(R.id.petBackButton).isEnabled = false
        lifecycleScope.launch {
            brightnessJob?.join()
            while (profileApplyInProgress) {
                delay(100L)
            }
            val snapshot = originalSnapshot
            if (sessionActive && snapshot != null) {
                withContext(Dispatchers.IO) {
                    ac.restoreSnapshot(snapshot)
                }
            }
            originalSnapshot = null
            sessionActive = false
            restoreSystemBrightness()
            finish()
        }
    }

    companion object {
        @Volatile
        private var originalSnapshot: BydAcController.AcSnapshot? = null

        @Volatile
        private var sessionActive = false

        private const val READY_POLL_MS = 2_000L
        private const val PROFILE_REAPPLY_MS = 5_000L
        private const val MAX_SYSTEM_BRIGHTNESS = 255
        private const val SCREEN_BRIGHTNESS_FLOAT = "screen_brightness_float"
    }
}
