package com.sphy.airconcontroller.diagnostics

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sphy.airconcontroller.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BluetoothDiagnosticsLayoutTest {
    @Test fun diagnosticControlsAndExistingDebugToolsRemainAccessibleInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (theme in listOf(R.style.Theme_OpenDiKey, R.style.Theme_OpenDiKey_Modern)) {
                val context = ContextThemeWrapper(instrumentation.targetContext, theme)
                val inflater = LayoutInflater.from(context)
                val diagnostics = inflater.inflate(R.layout.activity_bluetooth_diagnostics, null)
                measureLandscape(diagnostics)
                val actions = listOf(
                    R.id.btDiagScan, R.id.btDiagStop, R.id.btDiagRefresh, R.id.btDiagPermissions,
                    R.id.btDiagLocation, R.id.btDiagBluetooth, R.id.btDiagAppSettings,
                    R.id.btDiagCopy, R.id.btDiagShare,
                )
                actions.forEach { id ->
                    val button = diagnostics.findViewById<Button>(id)
                    assertTrue(button.text.isNotBlank())
                    assertTrue(button.width > 0 && button.height > 0)
                }
                assertFalse(diagnostics.findViewById<Button>(R.id.btDiagStop).isEnabled)
                assertTrue(diagnostics.findViewById<TextView>(R.id.btDiagReport).isTextSelectable)

                val hub = inflater.inflate(R.layout.activity_debug_hub, null)
                measureLandscape(hub)
                val tools = listOf(
                    R.id.openBluetoothDiagnosticsButton, R.id.openDiagnosticsButton,
                    R.id.openAdasLogButton, R.id.openAdbServiceButton, R.id.openClimateButton,
                    R.id.openDikeyButton, R.id.openUsbButton, R.id.openVehicleDumpButton,
                )
                tools.forEach { id ->
                    val tool = hub.findViewById<View>(id)
                    assertTrue("Debug tool $id must retain height", tool.height > 0)
                }
                assertTrue(hub.findViewById<View>(R.id.openBluetoothDiagnosticsButton).parent.parent is ScrollView)
            }
        }
    }

    private fun measureLandscape(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
