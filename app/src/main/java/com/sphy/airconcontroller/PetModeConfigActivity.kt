package com.sphy.airconcontroller

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.PetModeProfile
import com.sphy.airconcontroller.ui.OpenDiKeyActivity

class PetModeConfigActivity : OpenDiKeyActivity() {
    private lateinit var settings: AppSettings
    private lateinit var fanValue: TextView
    private lateinit var temperatureValue: TextView
    private lateinit var recircSwitch: SwitchMaterial
    private lateinit var windModeToggle: MaterialButtonToggleGroup

    private var fanLevel = 3
    private var temperatureC = 21
    private var windModeIndex = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pet_mode_config)

        settings = AppSettings(this)
        fanValue = findViewById(R.id.petFanValue)
        temperatureValue = findViewById(R.id.petTempValue)
        recircSwitch = findViewById(R.id.petRecircSwitch)
        windModeToggle = findViewById(R.id.petWindModeToggle)

        val profile = settings.petModeProfile()
        fanLevel = profile.fanLevel
        temperatureC = profile.temperatureC
        windModeIndex = profile.windModeIndex
        recircSwitch.isChecked = profile.recirculating
        renderValues()
        windModeToggle.check(WIND_BUTTON_IDS[windModeIndex])

        findViewById<ImageButton>(R.id.petConfigBackButton).setOnClickListener {
            finish()
        }
        findViewById<Button>(R.id.petFanDownButton).setOnClickListener {
            fanLevel = (fanLevel - 1).coerceAtLeast(1)
            renderValues()
        }
        findViewById<Button>(R.id.petFanUpButton).setOnClickListener {
            fanLevel = (fanLevel + 1).coerceAtMost(7)
            renderValues()
        }
        findViewById<Button>(R.id.petTempDownButton).setOnClickListener {
            temperatureC = (temperatureC - 1).coerceAtLeast(17)
            renderValues()
        }
        findViewById<Button>(R.id.petTempUpButton).setOnClickListener {
            temperatureC = (temperatureC + 1).coerceAtMost(32)
            renderValues()
        }
        windModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                windModeIndex = WIND_BUTTON_IDS.indexOf(checkedId).coerceAtLeast(0)
            }
        }
        findViewById<Button>(R.id.petSaveButton).setOnClickListener { view ->
            settings.savePetModeProfile(
                PetModeProfile(
                    fanLevel = fanLevel,
                    temperatureC = temperatureC,
                    recirculating = recircSwitch.isChecked,
                    windModeIndex = windModeIndex,
                )
            )
            Snackbar.make(view, R.string.pet_mode_saved, Snackbar.LENGTH_SHORT)
                .addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        finish()
                    }
                })
                .show()
        }
    }

    private fun renderValues() {
        fanValue.text = getString(R.string.pet_mode_fan_fmt, fanLevel)
        temperatureValue.text = getString(R.string.pet_mode_temp_value_fmt, temperatureC)
    }

    companion object {
        private val WIND_BUTTON_IDS = intArrayOf(
            R.id.petWindFaceButton,
            R.id.petWindFaceFeetButton,
            R.id.petWindFeetButton,
            R.id.petWindFeetDemistButton,
            R.id.petWindFaceDemistButton,
        )
    }
}
