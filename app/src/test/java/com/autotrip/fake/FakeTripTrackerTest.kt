package com.autotrip.fake

import com.autotrip.domain.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeTripTrackerTest {
    private fun clock() = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC)

    @Test fun startDriveEndUpdatesStateAndDistance() = runBlocking {
        val repo = FakeTripRepository(clock())
        val tracker = FakeTripTracker(repo, clock(), initialOdometerMeters = 10_000L)
        assertEquals(TripState.Idle, tracker.state.value)
        val started = tracker.startManualTrip() as AutoTripResult.Success
        assertTrue(tracker.state.value is TripState.Driving)
        tracker.simulateDrive(2_500L)
        val ended = tracker.endManualTrip() as AutoTripResult.Success
        assertEquals(started.value, ended.value)
        assertTrue(tracker.state.value is TripState.Arrived)
        assertEquals(2_500L, repo.snapshot().single().drivenMeters)
    }

    @Test fun duplicateStartAndEndWithoutDrivingAreRejected() = runBlocking {
        val tracker = FakeTripTracker(FakeTripRepository(clock()), clock())
        assertTrue(tracker.endManualTrip() is AutoTripResult.Failure)
        tracker.startManualTrip()
        assertTrue(tracker.startManualTrip() is AutoTripResult.Failure)
        tracker.endManualTrip()
        assertTrue(tracker.endManualTrip() is AutoTripResult.Failure)
    }

    @Test fun odometerFailureDoesNotInventDistance() = runBlocking {
        val repo = FakeTripRepository(clock())
        val tracker = FakeTripTracker(repo, clock())
        tracker.setOdometerFailure(true)
        tracker.startManualTrip()
        tracker.simulateDrive(100L)
        tracker.endManualTrip()
        val trip = repo.snapshot().single()
        assertEquals(OdometerCaptureStatus.FAILED, trip.odometerStart.status)
        assertEquals(OdometerCaptureStatus.FAILED, trip.odometerEnd.status)
        assertNull(trip.drivenMeters)
    }

    @Test fun arrivalEndsActiveTrip() = runBlocking {
        val repo = FakeTripRepository(clock())
        val tracker = FakeTripTracker(repo, clock())
        tracker.startManualTrip()
        tracker.simulateArrive()
        assertTrue(tracker.state.value is TripState.Arrived)
        assertNotNull(repo.snapshot().single().endedAt)
    }

    @Test fun destinationIsStoredAtStart() = runBlocking {
        val repo = FakeTripRepository(clock())
        val tracker = FakeTripTracker(repo, clock())
        tracker.simulateSetDestination("판교 거래처")
        tracker.startManualTrip()
        assertEquals("판교 거래처", repo.snapshot().single().destinationName)
    }

    @Test fun reservedPurposeIsAppliedAtStart() = runBlocking {
        val repo = FakeTripRepository(clock())
        val tracker = FakeTripTracker(repo, clock())
        assertTrue(repo.setPlannedPurpose(TripPurpose.CLIENT_VISIT, InputMethod.VOICE, "거래처 방문") is AutoTripResult.Success)
        tracker.startManualTrip()
        assertEquals(PurposeStatus.CONFIRMED, repo.snapshot().single().purposeStatus)
        assertEquals(TripPurpose.CLIENT_VISIT, repo.snapshot().single().purpose)
    }


    @Test
    fun simulateDriveStartsAutoTripFromIdle() = runBlocking {
        val repo = FakeTripRepository(clock())
        val tracker = FakeTripTracker(
            repo,
            clock(),
            initialOdometerMeters = 10_000L,
        )

        tracker.simulateDrive(1_500L)
        tracker.simulateDrive(500L)

        val driving = tracker.state.value as TripState.Driving
        assertEquals(TripSource.AUTO, driving.source)
        assertEquals(1, repo.snapshot().size)

        tracker.simulateArrive()
        assertTrue(tracker.state.value is TripState.Arrived)

        val trip = repo.snapshot().single()
        assertEquals(TripSource.AUTO, trip.source)
        assertEquals(2_000L, trip.drivenMeters)
    }

    @Test
    fun simulateDriveStartsNewAutoTripAfterArrival() = runBlocking {
        val repo = FakeTripRepository(clock())
        val tracker = FakeTripTracker(
            repo,
            clock(),
            initialOdometerMeters = 10_000L,
        )

        tracker.simulateDrive(1_000L)
        tracker.simulateArrive()
        assertTrue(tracker.state.value is TripState.Arrived)

        tracker.simulateDrive(2_000L)
        assertEquals(2, repo.snapshot().size)

        val driving = tracker.state.value as TripState.Driving
        assertEquals(TripSource.AUTO, driving.source)

        tracker.simulateArrive()
        assertTrue(tracker.state.value is TripState.Arrived)

        val trips = repo.snapshot().sortedBy { it.id }
        assertEquals(1_000L, trips[0].drivenMeters)
        assertEquals(2_000L, trips[1].drivenMeters)
    }


    @Test
    fun odometerFailureAppliesOnlyToNextTrip() = runBlocking {
        val repo = FakeTripRepository(clock())
        val tracker = FakeTripTracker(repo, clock())

        tracker.setOdometerFailure(
            onStart = true,
            onEnd = false,
        )

        tracker.startManualTrip()
        tracker.simulateDrive(1_000L)
        tracker.endManualTrip()

        tracker.startManualTrip()
        tracker.simulateDrive(2_000L)
        tracker.endManualTrip()

        val trips = repo.snapshot().sortedBy { it.id }

        assertEquals(
            OdometerCaptureStatus.FAILED,
            trips[0].odometerStart.status,
        )
        assertEquals(
            OdometerCaptureStatus.CAPTURED,
            trips[0].odometerEnd.status,
        )
        assertNull(trips[0].drivenMeters)

        assertEquals(
            OdometerCaptureStatus.CAPTURED,
            trips[1].odometerStart.status,
        )
        assertEquals(
            OdometerCaptureStatus.CAPTURED,
            trips[1].odometerEnd.status,
        )
        assertEquals(2_000L, trips[1].drivenMeters)
    }


    @Test
    fun simulateArriveDoesNothingWhenNotDriving() {
        val repo = FakeTripRepository(clock())
        val tracker = FakeTripTracker(repo, clock())

        // Idle: 아무 동작도 하지 않음
        tracker.simulateArrive()
        assertEquals(TripState.Idle, tracker.state.value)
        assertTrue(repo.snapshot().isEmpty())

        // Driving -> Arrived
        tracker.simulateDrive(1_000L)
        tracker.simulateArrive()
        assertTrue(tracker.state.value is TripState.Arrived)

        // Arrived: 다시 호출해도 새 운행을 생성하지 않음
        tracker.simulateArrive()
        assertEquals(1, repo.snapshot().size)
    }


}
