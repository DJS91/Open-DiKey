package com.sphy.airconcontroller.telemetry

import android.content.Context
import android.util.Log
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.usb.DiKeyUsbBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TelemetrySender(context: Context, private val bridge: DiKeyUsbBridge) {
    private val settings = AppSettings(context)
    private val snapshot = TelemetrySnapshot(context.applicationContext)
    private val transport = TelemetryTransport(bridge.telemetryReplies, { line ->
        withContext(Dispatchers.IO) { bridge.serial.writeLine(line) }
    })
    private val generation = MutableStateFlow(0)
    private val _status = MutableStateFlow("Telemetry disabled")
    val status = _status.asStateFlow()
    private var job: Job? = null

    fun reconfigure() { generation.value++ }

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(bridge.wifi.map { it.state }.distinctUntilChanged(), generation) { wifi, revision -> wifi to revision }
                .collectLatest { (wifi, revision) ->
                    val url = settings.telemetryUrl
                    val seconds = settings.telemetryIntervalSeconds
                    val includeLocation = settings.telemetryLocationEnabled
                    if (!settings.telemetryEnabled) {
                        _status.value = "Telemetry disabled"
                    } else if (!TelemetryConfig.validUrl(url)) {
                        _status.value = "Telemetry: invalid HTTPS URL"
                    } else if (wifi != DiKeyUsbBridge.WifiState.CONNECTED) {
                        _status.value = "Telemetry: waiting for ESP32 WiFi"
                    } else {
                        while (true) {
                            try {
                                _status.value = "Telemetry: collecting and sending"
                                val body = withContext(Dispatchers.IO) {
                                    snapshot.collect(includeLocation)
                                }
                                if (generation.value != revision || !settings.telemetryEnabled) return@collectLatest
                                val result = transport.send(url, body)
                                _status.value = result
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("Telemetry", "Telemetry collection or transfer failed", e)
                                _status.value = "Telemetry failed: ${e.javaClass.simpleName}"
                            }
                            delay(seconds * 1000L)
                        }
                    }
                }
        }
    }

}
