
package com.autotrip.data.tracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.data.repository.RoomTripRepository
import com.autotrip.domain.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTripTrackerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var database: AutoTripDatabase
    private lateinit var repository: RoomTripRepository
    private lateinit var tracker: RoomTripTracker
    private lateinit var scope: CoroutineScope

    private val databaseName = "autotrip_tracker_test.db"
    private val clock = Clock.fixed(
        Instant.parse("2026-11-13T01:00:00Z"),
        ZoneId.of("Asia/Seoul")
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
        openDatabase()
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(
            context,
            AutoTripDatabase::class.java,
            databaseName
        ).build()

        repository = RoomTripRepository(
            database = database,
            clock = clock
        )

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        tracker = RoomTripTracker(
            repository = repository,
            scope = scope,
            clock = clock
        )
    }

    private fun reopenDatabase() {
        scope.cancel()
        database.close()
        openDatabase()
    }

    @Test
    fun manualTripStartsAndPersists() = runBlocking {
        val result = tracker.startManualTrip()
        assertTrue(result is AutoTripResult.Success)

        val driving = tracker.state.value as? TripState.Driving
        assertNotNull(driving)

        val trip = repository.observeTrip(driving!!.tripId).first()
        assertNotNull(trip)
        assertNull(trip!!.endedAt)
        assertEquals(TripSource.MANUAL, trip.source)
        assertEquals(OdometerCaptureStatus.UNSUPPORTED, trip.odometerStart.status)
        assertNull(trip.odometerStart.meters)
    }

    @Test
    fun duplicateStartIsRejected() = runBlocking {
        assertTrue(tracker.startManualTrip() is AutoTripResult.Success)

        val second = tracker.startManualTrip()
        assertTrue(second is AutoTripResult.Failure)
    }

    @Test
    fun activeTripRestoresAfterDatabaseReopen() = runBlocking {
        val result = tracker.startManualTrip() as AutoTripResult.Success
        val tripId = result.value

        reopenDatabase()
        tracker.restoreActiveTrip()

        val restored = tracker.state.value as? TripState.Driving
        assertNotNull(restored)
        assertEquals(tripId, restored!!.tripId)

        val ended = tracker.endManualTrip()
        assertTrue("endManualTrip: $ended", ended is AutoTripResult.Success)

        val trip = repository.observeTrip(tripId).first()
        assertNotNull(trip!!.endedAt)
        assertEquals(OdometerCaptureStatus.UNSUPPORTED, trip.odometerEnd.status)
        assertNull(trip.drivenMeters)
    }

    @Test
    fun finishedTripDoesNotRestoreAsDriving() = runBlocking {
        val id = (tracker.startManualTrip() as AutoTripResult.Success).value
        assertTrue(tracker.endManualTrip() is AutoTripResult.Success)

        reopenDatabase()
        tracker.restoreActiveTrip()

        // INTERFACES.md requires the most recently ended trip to restore as Arrived.
        assertEquals(TripState.Arrived(id, TripSource.MANUAL, clock.instant()), tracker.state.value)
        assertFalse(tracker.state.value is TripState.Driving)
        assertNull(repository.activeTrip())
    }

    @Test
    fun endingWithoutActiveTripFails() = runBlocking {
        val result = tracker.endManualTrip()
        assertTrue(result is AutoTripResult.Failure)
    }

    @Test
    fun odometerIsNeverFabricated() = runBlocking {
        val started = tracker.startManualTrip() as AutoTripResult.Success
        val id = started.value

        assertTrue(tracker.endManualTrip() is AutoTripResult.Success)

        val trip = repository.observeTrip(id).first()!!
        assertNull(trip.odometerStart.meters)
        assertNull(trip.odometerEnd.meters)
        assertNull(trip.drivenMeters)
    }
}
