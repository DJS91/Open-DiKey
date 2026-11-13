package com.sphy.airconcontroller.dikey

internal class FanDisplayTimeout(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onTimeout: (Boolean) -> Unit,
) {
    private val leftTask = Runnable { onTimeout(true) }
    private val rightTask = Runnable { onTimeout(false) }

    fun reset(left: Boolean) {
        val task = if (left) leftTask else rightTask
        cancel(task)
        schedule(task, TIMEOUT_MS)
    }

    fun cancel(left: Boolean) {
        cancel(if (left) leftTask else rightTask)
    }

    fun cancelAll() {
        cancel(true)
        cancel(false)
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
    }
}
