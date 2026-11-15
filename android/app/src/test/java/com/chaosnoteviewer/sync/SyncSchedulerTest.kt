package com.chaosnoteviewer.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {
    private val base = SyncScheduler.FOREGROUND_POLL_MS

    @Test fun noFailuresIsTheNormalRhythm() = assertEquals(30_000L, SyncScheduler.pollInterval(base, 0))

    @Test fun everyFailureDoublesTheWait() {
        assertEquals(60_000L, SyncScheduler.pollInterval(base, 1))
        assertEquals(120_000L, SyncScheduler.pollInterval(base, 2))
        assertEquals(240_000L, SyncScheduler.pollInterval(base, 3))
    }

    @Test fun itStopsGettingSlowerAfterSixFailures() {
        assertEquals(30_000L * 64, SyncScheduler.pollInterval(base, 6))
        assertEquals(30_000L * 64, SyncScheduler.pollInterval(base, 7))
        assertEquals(30_000L * 64, SyncScheduler.pollInterval(base, 1000))
    }

    @Test fun aNegativeCountIsTreatedAsNone() = assertEquals(30_000L, SyncScheduler.pollInterval(base, -3))

    @Test fun theSameRuleForAnyBaseTime() = assertEquals(300_000L * 4, SyncScheduler.pollInterval(300_000L, 2))
}
