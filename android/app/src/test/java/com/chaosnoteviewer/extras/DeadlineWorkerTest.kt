package com.chaosnoteviewer.extras

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class DeadlineWorkerTest {
    private fun delay(at: String) = DeadlineWorker.delayUntilNext(LocalDateTime.parse(at))

    @Test fun beforeEightItIsToday() = assertEquals(Duration.ofHours(2).plusMinutes(30), delay("2026-11-15T05:30:00"))

    @Test fun afterEightItIsTomorrow() = assertEquals(Duration.ofHours(16), delay("2026-11-15T16:00:00"))

    @Test fun exactlyEightWaitsAFullDay() = assertEquals(Duration.ofHours(24), delay("2026-11-15T08:00:00"))

    @Test fun justBeforeMidnight() = assertEquals(Duration.ofHours(8).plusMinutes(1), delay("2026-11-15T23:59:00"))
}
