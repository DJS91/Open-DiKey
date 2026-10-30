package com.sphy.airconcontroller.fridge

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sphy.airconcontroller.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FridgePickerLayoutTest {
    @Test fun emptyPickerHasVisibleInstructionsAndReservedListSpaceInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (theme in listOf(R.style.Theme_OpenDiKey, R.style.Theme_OpenDiKey_Modern)) {
                val context = ContextThemeWrapper(instrumentation.targetContext, theme)
                val root = LayoutInflater.from(context).inflate(R.layout.dialog_fridge_devices, null)
                val list = root.findViewById<ListView>(R.id.fridgeDeviceList)
                val empty = root.findViewById<TextView>(R.id.fridgeScanEmpty)
                list.adapter = ArrayAdapter<String>(context, R.layout.item_fridge_device)
                list.emptyView = empty
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)

                assertEquals(View.VISIBLE, empty.visibility)
                assertTrue(empty.height > 0)
                assertTrue(empty.text.isNotBlank())
                assertTrue(root.findViewById<TextView>(R.id.fridgeScanStatus).text.isNotBlank())
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.fridgeScanProgress).visibility)
                assertTrue(root.findViewById<CheckBox>(R.id.fridgeShowAllDevices).text.isNotBlank())
            }
        }
    }

    @Test fun deviceArrivalReplacesEmptyInstructionsWithASelectableRow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_OpenDiKey)
            val root = LayoutInflater.from(context).inflate(R.layout.dialog_fridge_devices, null)
            val list = root.findViewById<ListView>(R.id.fridgeDeviceList)
            val empty = root.findViewById<TextView>(R.id.fridgeScanEmpty)
            val adapter = ArrayAdapter<String>(context, R.layout.item_fridge_device)
            list.adapter = adapter
            list.emptyView = empty
            adapter.add("DownUnder_1234\nAA:BB:CC:DD:EE:FF")
            assertEquals(View.GONE, empty.visibility)
            assertEquals(View.VISIBLE, list.visibility)
            assertEquals(1, list.count)
            assertTrue(adapter.getView(0, null, list).isEnabled)
            adapter.clear()
            assertEquals(View.VISIBLE, empty.visibility)
        }
    }
}
