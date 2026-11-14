package com.autotrip.data.tracker

import com.autotrip.data.local.entity.toDomain
import com.autotrip.data.local.entity.toEntity
import com.autotrip.data.vehicle.*
import com.autotrip.domain.model.*
import com.autotrip.fake.FakeTripRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoomTripTrackerOdometerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC)

    /** Real tracker with an in-memory persistence boundary; Room is tested on device separately. */
    private class Store(clock: Clock) : ManualTripStore {
        val trips = FakeTripRepository(clock)
        override suspend fun activeTrip() = trips.activeTrip()
        override suspend fun beginTrip(vehicleId: Long, source: TripSource, startedAt: Instant,
            odometerStart: OdometerReading) = trips.beginTrip(vehicleId, source, startedAt, odometerStart)
        override suspend fun finishTrip(tripId: Long, endedAt: Instant,
            odometerEnd: OdometerReading) = trips.finishTrip(tripId, endedAt, odometerEnd)
    }

    private fun source(vararg values: Float?, error: Boolean = false) : OdometerSource {
        var index = 0
        return VehicleOdometer({ object : VehicleOdometerSession {
            override fun initialize() {}
            override suspend fun canRead() = true
            override suspend fun distanceKilometers(): Float? {
                if (error) throw SecurityException("SDK permission denied")
                return values[index++]
            }
            override fun release() {}
        } }, clock)
    }

    private suspend fun record(source: OdometerSource): Trip {
        val store = Store(clock)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val tracker = RoomTripTracker(store, scope, clock, odometer = source)
            assertTrue(tracker.startManualTrip() is AutoTripResult.Success)
            assertTrue(tracker.endManualTrip() is AutoTripResult.Success)
            assertTrue(tracker.state.value is TripState.Arrived)
            assertFalse(tracker.availability.value.autoDetectionSupported)
            return store.trips.snapshot().single()
        } finally { scope.cancel() }
    }

    @Test fun manualStartAndEndStoreMeasurementsWithExistingEntityContract() = runBlocking {
        val trip = record(source(100f, 101.25f))
        assertEquals(100_000L, trip.odometerStart.meters)
        assertEquals(101_250L, trip.odometerEnd.meters)
        assertEquals(1_250L, trip.drivenMeters)
        assertEquals(TripSource.MANUAL, trip.source)
        assertEquals(trip, trip.toEntity().toDomain())
    }

    @Test fun sdkFailureStillSavesCompleteTripWithMissingReadings() = runBlocking {
        val trip = record(source(error = true))
        assertNotNull(trip.endedAt)
        assertEquals(OdometerCaptureStatus.FAILED, trip.odometerStart.status)
        assertEquals(OdometerCaptureStatus.FAILED, trip.odometerEnd.status)
        assertNull(trip.odometerStart.meters)
        assertNull(trip.odometerEnd.meters)
        assertNull(trip.drivenMeters)
        assertEquals(trip, trip.toEntity().toDomain())
    }

    @Test fun decreasingEndDoesNotOverwriteStartAndStillSavesTrip() = runBlocking {
        val trip = record(source(100f, 99f))
        assertEquals(100_000L, trip.odometerStart.meters)
        assertEquals(OdometerCaptureStatus.FAILED, trip.odometerEnd.status)
        assertNull(trip.odometerEnd.meters)
        assertNull(trip.drivenMeters)
        assertNotNull(trip.endedAt)
    }

    @Test fun unexpectedProviderExceptionDoesNotPreventSaving() = runBlocking {
        val source = object : OdometerSource {
            override val supported = kotlinx.coroutines.flow.MutableStateFlow(false)
            override suspend fun capture(minimumMeters: Long?): OdometerReading = error("provider failed")
        }
        val trip = record(source)
        assertEquals(OdometerCaptureStatus.FAILED, trip.odometerStart.status)
        assertNotNull(trip.endedAt)
    }
}
