package com.autotrip.data.tracker

import com.autotrip.data.vehicle.VehicleSpeedEvent
import com.autotrip.data.vehicle.VehicleSpeedSource
import com.autotrip.domain.model.*
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoTripEndCoordinatorTest {
    private class Tracker : AutoStartTripPort {
        override val state = MutableStateFlow<TripState>(TripState.Driving(1, TripSource.AUTO, Instant.EPOCH))
        var finishes = 0
        override suspend fun startAutoTrip(): AutoTripResult<Long> = error("Must not start another trip")
        override suspend fun finishAutoTrip(expectedTripId: Long): AutoTripResult<Long> {
            assertEquals(1L, expectedTripId)
            finishes++
            state.value = TripState.Arrived(1, TripSource.AUTO, Instant.EPOCH)
            return AutoTripResult.Success(1)
        }
    }
    @Test fun verifiedEvidenceFinishesOnceThroughCoordinator() = runTest {
        val tracker = Tracker()
        val speed = object : VehicleSpeedSource { override val events = MutableSharedFlow<VehicleSpeedEvent>() }
        val evidence = MutableSharedFlow<VehicleEndEvidence>()
        val coordinator = AutoTripCoordinator(speed, tracker, backgroundScope,
            { (currentTime + 1_000) * 1_000_000 }, endEvidence = evidence, endPolicy = AutoTripEndPolicy(enabled = true))
        coordinator.start(); runCurrent()
        repeat(16) {
            advanceTimeBy(1_000)
            evidence.emit(VehicleEndEvidence(0f, true, true, true, (currentTime + 1_000) * 1_000_000))
            runCurrent()
        }
        assertEquals(1, tracker.finishes)
        assertTrue(tracker.state.value is TripState.Arrived)
        coordinator.stop()
    }
    @Test fun disabledProductionPolicyDoesNotSubscribeToEndEvidence() = runTest {
        val tracker = Tracker()
        val speed = object : VehicleSpeedSource { override val events = MutableSharedFlow<VehicleSpeedEvent>() }
        val evidence = MutableSharedFlow<VehicleEndEvidence>()
        val coordinator = AutoTripCoordinator(speed, tracker, backgroundScope,
            { 1_000_000_000 }, endEvidence = evidence)
        coordinator.start(); runCurrent()
        assertEquals(0, evidence.subscriptionCount.value)
        assertEquals(0, tracker.finishes)
        coordinator.stop()
    }
}
