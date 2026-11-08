package com.sphy.airconcontroller.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiagnosticUpload(context: Context) {
    data class State(val busy: Boolean = false, val progress: String? = null, val url: String? = null, val error: String? = null)

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    @Synchronized
    fun start() {
        if (_state.value.busy) return
        _state.value = State(busy = true)
        scope.launch {
            try {
                val runner = DiagnosticsRunner(app, DebugTrace::dikeySnapshot)
                val report = runner.run { progress -> _state.value = State(busy = true, progress = progress) }
                val fullReport = buildString {
                    append(report)
                    appendLine()
                    appendLine("== Recent USB debug log (up to 400 lines) ==")
                    val lines = DebugTrace.usbSnapshot()
                    if (lines.isEmpty()) appendLine("(no USB log captured in this app process)")
                    else lines.forEach { appendLine(it) }
                }
                _state.value = State(busy = true, progress = null)
                _state.value = State(url = DiagnosticStorage.upload(fullReport))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DiagnosticUpload", "Diagnostic run or upload failed", e)
                _state.value = State(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
