package com.sphy.airconcontroller.dikey

import org.junit.Assert.assertEquals
import org.junit.Test

class FanDisplayTimeoutTest {
    private var now = 0L
    private val scheduled = mutableMapOf<Runnable, Long>()
    private val expired = mutableListOf<Boolean>()
    private val timeout = FanDisplayTimeout(
        schedule = { task, delay -> scheduled[task] = now + delay },
        cancel = { task -> scheduled.remove(task) },
        onTimeout = { left -> expired.add(left) },
    )

    private fun advanceTo(time: Long) {
        while (true) {
            val next = scheduled.entries.minByOrNull { it.value } ?: break
            if (next.value > time) break
            now = next.value
            val task = next.key
            scheduled.remove(task)
            task.run()
        }
        now = time
    }

    @Test
    fun expiresThreeSecondsAfterEnteringFanMode() {
        timeout.reset(left = true)
        advanceTo(2_999)
        assertEquals(emptyList<Boolean>(), expired)
        advanceTo(3_000)
        assertEquals(listOf(true), expired)
    }

    @Test
    fun turnResetsOnlyThatDialsDeadline() {
        timeout.reset(left = true)
        timeout.reset(left = false)
        advanceTo(2_000)
        timeout.reset(left = true)
        advanceTo(3_000)
        assertEquals(listOf(false), expired)
        advanceTo(4_999)
        assertEquals(listOf(false), expired)
        advanceTo(5_000)
        assertEquals(listOf(false, true), expired)
    }

    @Test
    fun cancelAndCancelAllPreventPendingTimeouts() {
        timeout.reset(left = true)
        timeout.reset(left = false)
        timeout.cancel(left = true)
        advanceTo(3_000)
        assertEquals(listOf(false), expired)
        timeout.reset(left = true)
        timeout.reset(left = false)
        timeout.cancelAll()
        advanceTo(6_000)
        assertEquals(listOf(false), expired)
    }
}
