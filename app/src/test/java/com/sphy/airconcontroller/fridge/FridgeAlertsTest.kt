package com.sphy.airconcontroller.fridge

import com.sphy.airconcontroller.fridge.FridgeAlerts.Event
import org.junit.Assert.*
import org.junit.Test

class FridgeAlertsTest {
    private val alerts = FridgeAlerts()
    private val minute = 60_000L

    private fun status(left: Int, right: Int = -18, on: Boolean = true, celsius: Boolean = true) =
        EvakoolProtocol.Status(on, celsius, left, right, 4, -18, "12.6")

    /** Feeds one sample per minute starting at minute [start]. */
    private fun feed(vararg left: Int, start: Int = 0): List<Event> =
        left.withIndex().flatMap { (i, t) ->
            val at = (start + i) * minute
            alerts.update(status(t), at, at)
        }

    @Test fun disconnectAlertsOnlyAfterTwoMinutesOfPriorConnection() {
        assertTrue("never connected", alerts.update(null, 0, 0).isEmpty())
        assertTrue(alerts.update(null, 10 * minute, 10 * minute).isEmpty())
        alerts.update(status(4), 11 * minute, 11 * minute)
        assertTrue(alerts.update(null, 12 * minute, 12 * minute).isEmpty())
        assertTrue(alerts.update(null, 12 * minute + 119_000, 0).isEmpty())
        assertEquals(listOf(Event.Disconnected), alerts.update(null, 14 * minute, 0))
        assertTrue("only once", alerts.update(null, 20 * minute, 0).isEmpty())
        assertEquals(listOf(Event.Reconnected), alerts.update(status(4), 21 * minute, 21 * minute))
    }

    @Test fun briefDropoutDoesNotAlert() {
        alerts.update(status(4), 0, 0)
        alerts.update(null, minute, minute)
        alerts.update(status(4), 2 * minute + 50_000, 2 * minute)
        assertTrue(alerts.update(null, 3 * minute, 3 * minute).isEmpty())
        assertTrue(alerts.update(null, 4 * minute + 59_000, 0).isEmpty())
    }

    @Test fun warmAlertNeedsThreeRisesEachFiveAboveTarget() {
        assertTrue(feed(9, 10, 11).isEmpty())
        val events = feed(12, start = 3)
        assertEquals(listOf(Event.Warm(1, 12, 4, true)), events)
        assertTrue("alerted once", feed(13, 14, start = 4).isEmpty())
        assertEquals(listOf(Event.WarmCleared(1)), feed(8, start = 6))
    }

    @Test fun coolingDownFromWarmNeverAlerts() {
        assertTrue(feed(25, 24, 22, 20, 18, 15, 12, 9, 8).isEmpty())
    }

    @Test fun plateauKeepsRunButDropResetsIt() {
        assertTrue(feed(9, 10, 10, 11).isEmpty())
        assertEquals(1, feed(12, start = 4).size)
        val fresh = FridgeAlerts()
        val temps = listOf(9, 10, 11, 10, 11, 12)
        val out = temps.withIndex().flatMap { (i, t) -> fresh.update(status(t), i * minute, i * minute) }
        assertTrue("drop at 10 resets; only two rises after it", out.isEmpty())
    }

    @Test fun belowMarginOrOffOrGapsDoNotCount() {
        assertTrue("4 above target is under the margin", feed(5, 6, 7, 8).isEmpty())
        val off = FridgeAlerts()
        val out = (0..4).flatMap { off.update(status(10 + it, on = false), it * minute, it * minute) }
        assertTrue(out.isEmpty())
        val gap = FridgeAlerts()
        gap.update(status(9), 0, 0)
        gap.update(status(10), minute, minute)
        gap.update(status(11), 2 * minute, 2 * minute)
        assertTrue("skipped minute breaks the run", gap.update(status(12), 4 * minute, 4 * minute).isEmpty())
    }

    @Test fun onlyOneSamplePerMinuteAndFahrenheitMargin() {
        alerts.update(status(9), 0, 0)
        for (s in 1..50) alerts.update(status(9 + s), s * 1_000L, s * 1_000L)
        assertTrue("same minute is one sample", alerts.update(status(60), 59_000, 59_000).isEmpty())
        val f = FridgeAlerts()
        val fs = { t: Int, i: Int -> f.update(EvakoolProtocol.Status(true, false, t, 0, 40, 0, "12.6"), i * minute, i * minute) }
        assertTrue((0..3).flatMap { fs(45 + it, it) }.isEmpty())
        val warmF = FridgeAlerts()
        val out = (0..3).flatMap {
            warmF.update(EvakoolProtocol.Status(true, false, 49 + it, 0, 40, 0, "12.6"), it * minute, it * minute)
        }
        assertEquals(1, out.size)
    }

    @Test fun pauseCancelsPendingDisconnect() {
        alerts.update(status(4), 0, 0)
        alerts.update(null, minute, minute)
        alerts.pause()
        assertTrue(alerts.update(null, 2 * minute + 30_000, 0).isEmpty())
        assertTrue(alerts.update(null, 4 * minute, 0).isEmpty())
        assertEquals(listOf(Event.Disconnected), alerts.update(null, 5 * minute, 0))
    }
}
