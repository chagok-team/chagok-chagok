package com.autotrip.data.tracker

import com.autotrip.data.vehicle.SpeedUnavailableReason
import com.autotrip.data.vehicle.VehicleSpeedEvent
import org.junit.Assert.*
import org.junit.Test

class AutoTripStartDetectorTest {
    private val second = 1_000_000_000L
    private fun sample(atMs: Long, kmh: Float = 8f, receivedMs: Long = atMs) =
        VehicleSpeedEvent.Sample(kmh / 3.6f, second + atMs * 1_000_000, second + receivedMs * 1_000_000)
    private fun feed(d: AutoTripStartDetector, atMs: Long, kmh: Float = 8f, nowMs: Long = atMs): AutoTripStartAction? =
        d.onSample(sample(atMs, kmh), second + nowMs * 1_000_000)
    private fun moving(d: AutoTripStartDetector, from: Long = 0): AutoTripStartAction.AutoStartRequested {
        for (offset in listOf(0L, 1_000, 2_000)) assertNull(feed(d, from + offset))
        return feed(d, from + 3_000) as AutoTripStartAction.AutoStartRequested
    }

    @Test fun stationaryNeverStarts() {
        val d = AutoTripStartDetector()
        for (t in 0L..10_000L step 1_000) assertNull(feed(d, t, 0f))
        assertEquals(AutoTripStartState.IDLE, d.state)
    }

    @Test fun continuousMovementRequestsExactlyOnceUntilAcknowledged() {
        val d = AutoTripStartDetector()
        val request = moving(d)
        assertEquals(AutoTripStartState.START_PENDING, d.state)
        for (t in 4_000L..10_000L step 1_000) assertNull(feed(d, t))
        d.onAutoStartPersisted(request.requestId, true, second + 10_000_000_000L)
        assertEquals(AutoTripStartState.ACTIVE, d.state)
        assertEquals(ActiveTripOrigin.AUTO, d.activeOrigin)
        assertNull(feed(d, 11_000))
    }

    @Test fun speedNoiseResetsCandidate() {
        val d = AutoTripStartDetector()
        assertNull(feed(d, 0))
        assertNull(feed(d, 1_000, 2f))
        assertNull(feed(d, 2_000))
        assertNull(feed(d, 3_000))
        assertNull(feed(d, 4_000))
        assertNotNull(feed(d, 5_000))
    }

    @Test fun duplicatesAndRegressingTimestampsResetContinuity() {
        val d = AutoTripStartDetector()
        assertNull(feed(d, 0))
        assertNull(feed(d, 1_000))
        assertNull(feed(d, 1_000))
        assertNull(feed(d, 500))
        assertNull(feed(d, 2_000))
        assertNull(feed(d, 3_000))
        assertNull(feed(d, 4_000))
        assertNotNull(feed(d, 5_000))
    }

    @Test fun sparseSamplesDoNotProveContinuousMovement() {
        val d = AutoTripStartDetector()
        assertNull(feed(d, 0))
        assertNull(feed(d, 3_000))
        assertEquals(AutoTripStartState.MOVEMENT_CANDIDATE, d.state)
        assertNull(feed(d, 4_000))
        assertNull(feed(d, 5_000))
        assertNotNull(feed(d, 6_000))
    }

    @Test fun unavailableCancelsCandidate() {
        val d = AutoTripStartDetector()
        feed(d, 0)
        feed(d, 1_000)
        d.onUnavailable(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.DISCONNECTED))
        assertEquals(AutoTripStartState.IDLE, d.state)
        assertNull(feed(d, 2_000))
        assertNull(feed(d, 3_000))
        assertNull(feed(d, 4_000))
        assertNotNull(feed(d, 5_000))
    }

    @Test fun invalidAndStaleSamplesCannotAdvanceCandidate() {
        val d = AutoTripStartDetector()
        feed(d, 0)
        assertNull(d.onSample(sample(1_000, Float.NaN), second + 1_000_000_000L))
        assertNull(feed(d, 1_000, nowMs = 3_000)) // age = 2s: stale
        assertNull(feed(d, 3_000)) // gap resets candidate
        assertNull(feed(d, 4_000))
        assertNull(feed(d, 5_000))
        assertNotNull(feed(d, 6_000))
    }

    @Test fun manualStartCancelsCandidateAndBlocksAutoStart() {
        val d = AutoTripStartDetector()
        feed(d, 0)
        feed(d, 1_000)
        d.onManualTripStarted()
        assertEquals(ActiveTripOrigin.MANUAL, d.activeOrigin)
        for (t in 2_000L..8_000L step 1_000) assertNull(feed(d, t))
        assertEquals(AutoTripStartState.ACTIVE, d.state)
    }

    @Test fun failedSaveNeedsCooldownAndFreshMovement() {
        val d = AutoTripStartDetector()
        val first = moving(d)
        d.onAutoStartPersisted(first.requestId, false, second + 3_000_000_000L)
        assertEquals(AutoTripStartState.IDLE, d.state)
        for (t in 4_000L..5_000L step 1_000) assertNull(feed(d, t))
        assertNull(feed(d, 6_000))
        assertNull(feed(d, 7_000))
        assertNull(feed(d, 8_000))
        val retry = feed(d, 9_000) as AutoTripStartAction.AutoStartRequested
        assertTrue(retry.requestId > first.requestId)
    }

    @Test fun lateAcknowledgementCannotOverrideNewRequestOrManualStart() {
        val d = AutoTripStartDetector()
        val first = moving(d)
        d.onAutoStartPersisted(first.requestId, false, second + 3_000_000_000L)
        for (t in 6_000L..9_000L step 1_000) {
            val result = feed(d, t)
            if (t == 9_000L) assertTrue(result is AutoTripStartAction.AutoStartRequested)
        }
        d.onAutoStartPersisted(first.requestId, true, second + 9_000_000_000L)
        assertEquals(AutoTripStartState.START_PENDING, d.state)
        d.onManualTripStarted()
        d.onAutoStartPersisted(first.requestId + 1, true, second + 10_000_000_000L)
        assertEquals(ActiveTripOrigin.MANUAL, d.activeOrigin)
    }

    @Test fun tripEndRequiresFiveSecondsOfContinuousStopBeforeRearming() {
        val d = AutoTripStartDetector()
        d.restoreActiveTrip(ActiveTripOrigin.MANUAL)
        d.onTripEnded()
        for (t in 0L..5_000L step 1_000) assertNull(feed(d, t, 8f))
        assertEquals(AutoTripStartState.REARM_WAIT, d.state)
        for (t in 6_000L..11_000L step 1_000) assertNull(feed(d, t, 0f))
        assertEquals(AutoTripStartState.IDLE, d.state)
        assertNotNull(moving(d, 12_000))
    }

    @Test fun interruptedStopDoesNotRearm() {
        val d = AutoTripStartDetector()
        d.onTripEnded()
        feed(d, 0, 0f)
        feed(d, 1_000, 0f)
        feed(d, 2_000, 3f)
        for (t in 3_000L..7_000L step 1_000) feed(d, t, 0f)
        assertEquals(AutoTripStartState.REARM_WAIT, d.state)
        feed(d, 8_000, 0f)
        assertEquals(AutoTripStartState.IDLE, d.state)
    }

    @Test fun restoredActiveTripsDoNotStartAgain() {
        for (origin in ActiveTripOrigin.entries) {
            val d = AutoTripStartDetector()
            d.restoreActiveTrip(origin)
            for (t in 0L..6_000L step 1_000) assertNull(feed(d, t))
            assertEquals(AutoTripStartState.ACTIVE, d.state)
            assertEquals(origin, d.activeOrigin)
        }
    }

    @Test fun badClockAndFutureSamplesAreRejected() {
        val d = AutoTripStartDetector()
        assertNull(d.onSample(sample(1_000), second + 500_000_000L))
        assertNull(d.onSample(sample(1_000, receivedMs = 2_000), second + 1_000_000_000L))
        assertEquals(AutoTripStartState.IDLE, d.state)
    }
}
