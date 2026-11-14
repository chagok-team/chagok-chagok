package com.autotrip.data.location

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GpsRecoveryTest {
    private class Session : GpsSession {
        lateinit var fix: (GpsFix) -> Unit
        var closed = 0
        override fun start(onFix: (GpsFix) -> Unit, onUnavailable: () -> Unit) { fix = onFix }
        override fun close() { closed++ }
    }
    private fun TestScope.fix(ageMillis: Long = 0) = GpsFix(37.0, 127.0, 5f, (currentTime + 20_000 - ageMillis) * 1_000_000)
    @Test fun reapprovedPermissionRestartsActiveJourneyWithoutNewTrip() = runTest {
        var allowed = false
        val sessions = mutableListOf<Session>()
        val source = GpsLocationSource(backgroundScope, { allowed }, { Session().also { sessions += it } }, { fix().timestampNanos })
        source.startJourney(7); runCurrent()
        assertTrue(sessions.isEmpty())
        allowed = true; advanceTimeBy(3_100); runCurrent()
        assertEquals(1, sessions.size)
        sessions.single().fix(fix()); runCurrent()
        assertTrue(source.available.value)
        assertEquals(7L, source.journey.value!!.tripId)
    }
    @Test fun quietPermissionRevocationInvalidatesWithinPollingInterval() = runTest {
        var allowed = true
        val session = Session()
        val source = GpsLocationSource(backgroundScope, { allowed }, { session }, { fix().timestampNanos })
        source.startJourney(1); runCurrent(); session.fix(fix()); runCurrent()
        allowed = false; advanceTimeBy(1_100); runCurrent()
        assertFalse(source.available.value)
        assertEquals(1, session.closed)
    }
    @Test fun staleExpiryUsesMeasurementTimeRatherThanReceiptTime() = runTest {
        val session = Session()
        val source = GpsLocationSource(backgroundScope, { true }, { session }, { fix().timestampNanos })
        source.startJourney(1); runCurrent(); session.fix(fix(ageMillis = 9_000)); runCurrent()
        assertTrue(source.available.value)
        advanceTimeBy(1_100); runCurrent()
        assertFalse(source.available.value)
    }
    @Test fun initialSessionExceptionCanRecoverWithoutRestartingJourney() = runTest {
        var creations = 0
        val session = Session()
        val source = GpsLocationSource(backgroundScope, { true }, {
            if (++creations == 1) error("temporary GPS service error")
            session
        }, { fix().timestampNanos })
        source.startJourney(1); runCurrent(); advanceTimeBy(3_100); runCurrent()
        assertEquals(2, creations)
        session.fix(fix()); runCurrent()
        assertTrue(source.available.value)
        assertTrue(source.journey.value!!.incomplete)
    }
    @Test fun stationaryUpdatesCannotMakeASuddenJumpLookSlow() {
        val path = GpsPathAccumulator(1, GpsPolicy())
        for (second in 0L..20) path.add(GpsFix(37.0, 127.0, 2f, (second + 1) * 1_000_000_000), (second + 1) * 1_000_000_000)
        path.add(GpsFix(37.005, 127.0, 2f, 22_000_000_000), 22_000_000_000)
        assertEquals(0.0, path.snapshot().estimatedMeters, 0.0)
        assertTrue(path.snapshot().incomplete)
    }

    @Test fun initialProviderStartFailureClosesSessionAndRetriesWhenAvailable() = runTest {
        var enabled = false
        var releases = 0
        var creations = 0
        val healthy = Session()
        val source = GpsLocationSource(backgroundScope, { true }, {
            creations++
            if (enabled) healthy else object : GpsSession {
                override fun start(onFix: (GpsFix) -> Unit, onUnavailable: () -> Unit) { error("provider disabled") }
                override fun close() { releases++ }
            }
        }, { fix().timestampNanos })
        source.startJourney(1); runCurrent()
        assertEquals(1, releases)
        enabled = true; advanceTimeBy(3_100); runCurrent()
        healthy.fix(fix()); runCurrent()
        assertEquals(2, creations)
        assertTrue(source.available.value)
        assertEquals(1L, source.journey.value!!.tripId)
    }

    @Test fun cachedPreTripPointAndLateCallbacksCannotRepopulateEndedJourney() = runTest {
        val sessions = mutableListOf<Session>()
        val source = GpsLocationSource(backgroundScope, { true }, { Session().also { sessions += it } }, { fix().timestampNanos })
        source.startJourney(1); runCurrent()
        sessions.last().fix(fix()); runCurrent()
        advanceTimeBy(500)
        source.endJourney(1); source.startJourney(2); runCurrent()
        assertEquals(2L, source.journey.value!!.tripId)
        assertTrue(source.journey.value!!.path.isEmpty())
        sessions.last().fix(fix()); runCurrent()
        assertEquals(1, source.journey.value!!.path.size)
        source.endJourney(2)
        sessions.last().fix(fix()); runCurrent()
        assertNull(source.journey.value)
    }
}
