package com.autotrip.fake

import com.autotrip.domain.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeTripRepositoryTest {
    private val base = Instant.parse("2026-11-13T10:00:00Z")
    private val reading = OdometerReading(10000L, OdometerCaptureStatus.CAPTURED, base)
    private fun repo(at: Instant = base) = FakeTripRepository(Clock.fixed(at, ZoneOffset.UTC))

    private fun begin(repository: FakeTripRepository, at: Instant = base): Trip {
        val result = repository.beginTrip(1L, TripSource.MANUAL, at, reading)
        return (result as AutoTripResult.Success).value
    }

    private fun finish(repository: FakeTripRepository, trip: Trip) {
        val result = repository.finishTrip(
            trip.id, trip.startedAt.plusSeconds(60),
            OdometerReading(12000L, OdometerCaptureStatus.CAPTURED, trip.startedAt.plusSeconds(60))
        )
        assertTrue(result is AutoTripResult.Success)
    }

    @Test fun startFinishAndObserve() = runBlocking {
        val repository = repo()
        val trip = begin(repository)
        finish(repository, trip)
        val period = TaxPeriod(LocalDate.parse("2026-11-13"), LocalDate.parse("2026-11-13"))
        val trips = repository.observeTrips(period).first()
        assertEquals(1, trips.size)
        assertEquals(2000L, trips.single().drivenMeters)
        assertEquals(trip.id, repository.observePendingPurposeTrips().first().single().id)
    }

    @Test fun confirmAfterFinishAndRejectDoubleConfirm() = runBlocking {
        val repository = repo()
        val trip = begin(repository)
        assertTrue(repository.confirmPurpose(trip.id, TripPurpose.MEETING, InputMethod.BUTTON, null, null) is AutoTripResult.Failure)
        finish(repository, trip)
        assertTrue(repository.confirmPurpose(trip.id, TripPurpose.MEETING, InputMethod.BUTTON, null, null) is AutoTripResult.Success)
        assertTrue(repository.confirmPurpose(trip.id, TripPurpose.MEETING, InputMethod.BUTTON, null, null) is AutoTripResult.Failure)
        assertEquals(PurposeStatus.CONFIRMED, repository.observeTrip(trip.id).first()?.purposeStatus)
        assertTrue(repository.observeEditHistory(trip.id).first().isEmpty())
    }

    @Test fun plannedPurposeWithinThirtyMinutes() = runBlocking {
        val repository = repo()
        assertTrue(repository.setPlannedPurpose(TripPurpose.CLIENT_VISIT, InputMethod.VOICE, "거래처",) is AutoTripResult.Success)
        val trip = begin(repository, base.plusSeconds(29 * 60))
        assertEquals(TripPurpose.CLIENT_VISIT, trip.purpose)
        assertEquals(PurposeStatus.CONFIRMED, trip.purposeStatus)
        assertEquals(base, trip.purposeInputTime)
        assertEquals("거래처", trip.voiceRawText)
    }

    @Test fun plannedPurposeExpiresAfterThirtyMinutes() = runBlocking {
        val repository = repo()
        repository.setPlannedPurpose(TripPurpose.MEETING, InputMethod.BUTTON, null)
        val trip = begin(repository, base.plusSeconds(31 * 60))
        assertEquals(PurposeStatus.PENDING, trip.purposeStatus)
        assertNull(trip.purpose)
    }

    @Test fun editsWriteHistoryOnlyWhenValueChanges() = runBlocking {
        val repository = repo()
        val trip = begin(repository)
        finish(repository, trip)
        repository.confirmPurpose(trip.id, TripPurpose.MEETING, InputMethod.BUTTON, null, null)
        assertTrue(repository.updatePurpose(trip.id, TripPurpose.MEETING) is AutoTripResult.Success)
        assertTrue(repository.observeEditHistory(trip.id).first().isEmpty())
        repository.updatePurpose(trip.id, TripPurpose.TRAINING)
        val changes = repository.observeEditHistory(trip.id).first()
        assertEquals(1, changes.size)
        assertEquals(EditableField.PURPOSE, changes.single().field)
        assertEquals("MEETING", changes.single().oldValue)
        assertEquals("TRAINING", changes.single().newValue)
    }

    @Test fun plannedPurposeWhileDrivingConfirmsImmediately() = runBlocking {
        val repository = repo()
        val trip = begin(repository)
        assertTrue(repository.setPlannedPurpose(TripPurpose.SITE_VISIT, InputMethod.BUTTON, null) is AutoTripResult.Success)
        val updated = repository.observeTrip(trip.id).first()!!
        assertEquals(PurposeStatus.CONFIRMED, updated.purposeStatus)
        assertNull(updated.endedAt)
        assertTrue(repository.setPlannedPurpose(TripPurpose.MEETING, InputMethod.BUTTON, null) is AutoTripResult.Failure)
    }
}
