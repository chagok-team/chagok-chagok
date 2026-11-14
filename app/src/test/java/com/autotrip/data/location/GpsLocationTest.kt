package com.autotrip.data.location

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GpsLocationTest {
    private val policy = GpsPolicy()
    private fun fix(ms: Long, lat: Double = 37.0, lon: Double = 127.0, accuracy: Float? = 2f) =
        GpsFix(lat, lon, accuracy, (ms + 1_000) * 1_000_000)
    @Test fun normalMovementProducesSeparateEstimate() {
        val path = GpsPathAccumulator(7, policy)
        for (ms in 0L..10_000 step 1_000) path.add(fix(ms, lon = 127.0 + ms / 2_000_000.0), fix(ms).timestampNanos)
        assertEquals(7L, path.snapshot().tripId)
        assertTrue(path.snapshot().estimatedMeters in 440.0..450.0)
        assertFalse(path.snapshot().incomplete)
    }
    @Test fun repeatedCoordinatesDoNotAccumulateDistance() {
        val path = GpsPathAccumulator(1, policy)
        for (ms in 0L..10_000 step 1_000) path.add(fix(ms), fix(ms).timestampNanos)
        assertEquals(0.0, path.snapshot().estimatedMeters, 0.0)
    }
    @Test fun rejectsInvalidCoordinatesAndPoorOrUnknownAccuracy() {
        val bad = listOf(fix(0, 91.0), fix(0, lon = -181.0), fix(0, Double.NaN),
            fix(0, accuracy = 100f), fix(0, accuracy = null), fix(0, accuracy = Float.NaN))
        for (sample in bad) assertFalse(policy.accepts(sample, sample.timestampNanos))
    }
    @Test fun rejectsFutureStaleAndRegressingSamples() {
        assertFalse(policy.accepts(fix(0), fix(0).timestampNanos - 1))
        assertFalse(policy.accepts(fix(0), fix(10_000).timestampNanos))
        val path = GpsPathAccumulator(1, policy)
        path.add(fix(1_000), fix(1_000).timestampNanos)
        path.add(fix(0), fix(1_000).timestampNanos)
        assertTrue(path.snapshot().incomplete)
        assertEquals(0.0, path.snapshot().estimatedMeters, 0.0)
    }
    @Test fun teleportsAreRejectedWithoutPoisoningAnchor() {
        val path = GpsPathAccumulator(1, policy)
        path.add(fix(0), fix(0).timestampNanos)
        path.add(fix(1_000, lat = 38.0), fix(1_000).timestampNanos)
        path.add(fix(2_000, lon = 127.0001), fix(2_000).timestampNanos)
        assertTrue(path.snapshot().estimatedMeters in 8.0..10.0)
        assertTrue(path.snapshot().incomplete)
    }
    @Test fun signalLossDoesNotConnectAcrossMissingSegment() {
        val path = GpsPathAccumulator(1, policy)
        path.add(fix(0), fix(0).timestampNanos)
        path.unavailable()
        path.add(fix(20_000, lon = 128.0), fix(20_000).timestampNanos)
        assertEquals(0.0, path.snapshot().estimatedMeters, 0.0)
        path.add(fix(21_000, lon = 128.0001), fix(21_000).timestampNanos)
        assertTrue(path.snapshot().estimatedMeters > 0)
        assertTrue(path.snapshot().incomplete)
    }
    @Test fun longGapAndPathBoundAreExplicitlyIncomplete() {
        val path = GpsPathAccumulator(1, policy.copy(maxPathPoints = 2))
        path.add(fix(0), fix(0).timestampNanos)
        path.add(fix(31_000, lon = 128.0), fix(31_000).timestampNanos)
        path.add(fix(32_000, lon = 128.0001), fix(32_000).timestampNanos)
        assertEquals(2, path.snapshot().path.size)
        assertTrue(path.snapshot().incomplete)
        assertTrue(path.snapshot().estimatedMeters < 20)
    }
    @Test fun restoredProcessHasNoInventedHistoricalPath() {
        val path = GpsPathAccumulator(1, policy, restored = true)
        assertEquals(0.0, path.snapshot().estimatedMeters, 0.0)
        assertTrue(path.snapshot().incomplete)
    }

    private class Session : GpsSession {
        lateinit var fix: (GpsFix) -> Unit
        lateinit var unavailable: () -> Unit
        var closed = 0
        override fun start(onFix: (GpsFix) -> Unit, onUnavailable: () -> Unit) { fix = onFix; unavailable = onUnavailable }
        override fun close() { closed++ }
    }
    @Test fun captureTimesOutAndClosesSession() = runTest {
        val session = Session()
        val source = GpsLocationSource(backgroundScope, { true }, { session }, { (currentTime + 1_000) * 1_000_000 })
        val capture = async { source.capture() }
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertNull(capture.await())
        runCurrent()
        assertEquals(1, session.closed)
    }
    @Test fun deniedPermissionDoesNotCreateSession() = runTest {
        val source = GpsLocationSource(backgroundScope, { false }, { error("must not connect") }, { 1L })
        assertNull(source.capture())
    }
    @Test fun activeJourneyAndCaptureShareSessionAndEndDropsPath() = runTest {
        val session = Session()
        var connections = 0
        val source = GpsLocationSource(backgroundScope, { true }, { connections++; session }, { fix(currentTime).timestampNanos })
        source.startJourney(10); runCurrent()
        session.fix(fix(currentTime)); runCurrent()
        assertNotNull(source.capture())
        assertEquals(1, connections)
        assertEquals(10L, source.journey.value!!.tripId)
        source.endJourney(10); runCurrent()
        assertNull(source.journey.value)
        session.fix(fix(currentTime + 1_000)); runCurrent()
        assertNull(source.journey.value)
        assertEquals(1, session.closed)
    }
    @Test fun SDKFailureAndReconnectKeepGapVisible() = runTest {
        val session = Session()
        val source = GpsLocationSource(backgroundScope, { true }, { session }, { fix(currentTime).timestampNanos })
        source.startJourney(1); runCurrent()
        session.fix(fix(currentTime)); runCurrent()
        session.unavailable(); runCurrent()
        assertFalse(source.available.value)
        advanceTimeBy(1_000)
        session.fix(fix(currentTime, lon = 127.01)); runCurrent()
        assertTrue(source.available.value)
        assertTrue(source.journey.value!!.incomplete)
        assertEquals(0.0, source.journey.value!!.estimatedMeters, 0.0)
    }
}
