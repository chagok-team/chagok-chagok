package com.autotrip.data.tracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.data.repository.RoomTripRepository
import com.autotrip.data.location.TripLocationSource
import com.autotrip.data.vehicle.OdometerSource
import com.autotrip.domain.model.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room persistence with explicitly injected locations, NOT a GPS/SDK hardware test. */
@RunWith(AndroidJUnit4::class)
class LocationPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun readOnlyDbFailurePreservesDrivingAndReopenAllowsRetry() = runBlocking<Unit> {
        val name = "stage7-db-failure-${System.currentTimeMillis()}.db"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.databaseBuilder(context, AutoTripDatabase::class.java, name).build()
        try {
            val tracker = RoomTripTracker(RoomTripRepository(db), scope)
            val id = (tracker.startAutoTrip() as AutoTripResult.Success).value
            // Test-only connection setting: deterministic write failure, no production schema change.
            db.openHelper.writableDatabase.execSQL("PRAGMA query_only=ON")
            val result = tracker.finishAutoTrip(id)
            assertTrue(result is AutoTripResult.Failure && result.error is AutoTripError.Storage)
            assertTrue(tracker.state.value is TripState.Driving)
            db.openHelper.writableDatabase.execSQL("PRAGMA query_only=OFF")
            db.close()
            val reopened = Room.databaseBuilder(context, AutoTripDatabase::class.java, name).build()
            try {
                val restored = RoomTripTracker(RoomTripRepository(reopened), scope)
                restored.restoreActiveTrip()
                assertEquals(id, (restored.state.value as TripState.Driving).tripId)
                assertTrue(restored.finishAutoTrip(id) is AutoTripResult.Success)
            } finally { reopened.close() }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }
    @Test fun endpointsSurviveDbReopenAndNeverReplaceOdometerDistance() = runBlocking<Unit> {
        val name = "stage7-location-${System.currentTimeMillis()}.db"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.databaseBuilder(context, AutoTripDatabase::class.java, name).build()
        var captures = 0
        val start = TripPlace(GeoPoint(37.0, 127.0), null)
        val end = TripPlace(GeoPoint(38.0, 128.0), null)
        val location = object : TripLocationSource {
            override val available = MutableStateFlow(true)
            override suspend fun capture() = if (captures++ == 0) start else end
        }
        var readings = 0
        val odometer = object : OdometerSource {
            override val supported = MutableStateFlow(true)
            override suspend fun capture(minimumMeters: Long?) = OdometerReading(
                if (readings++ == 0) 100_000 else 101_000, OdometerCaptureStatus.CAPTURED, Instant.now())
        }
        var id = 0L
        try {
            val repository = RoomTripRepository(db)
            val tracker = RoomTripTracker(repository, scope, location = location, odometer = odometer)
            id = (tracker.startAutoTrip() as AutoTripResult.Success).value
            assertTrue(tracker.finishAutoTrip(id) is AutoTripResult.Success)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
        val reopened = Room.databaseBuilder(context, AutoTripDatabase::class.java, name).build()
        try {
            val trip = RoomTripRepository(reopened).observeTrip(id).first()!!
            assertEquals(start, trip.startPlace)
            assertEquals(end, trip.endPlace)
            assertEquals(1_000L, trip.drivenMeters)
            assertNotNull(trip.endedAt)
            val restoredScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val restored = RoomTripTracker(RoomTripRepository(reopened), restoredScope)
                restored.restoreActiveTrip()
                assertEquals(id, (restored.state.value as TripState.Arrived).tripId)
            } finally { restoredScope.coroutineContext[Job]!!.cancelAndJoin() }
        } finally { reopened.close() }
    }
    @Test fun locationFailureStillPersistsManualTrip() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, AutoTripDatabase::class.java).build()
        val location = object : TripLocationSource {
            override val available = MutableStateFlow(false)
            override suspend fun capture(): TripPlace? = throw SecurityException("Location denied")
        }
        try {
            val repository = RoomTripRepository(db)
            val tracker = RoomTripTracker(repository, scope, location = location)
            val id = (tracker.startManualTrip() as AutoTripResult.Success).value
            assertTrue(tracker.endManualTrip() is AutoTripResult.Success)
            val trip = repository.observeTrip(id).first()!!
            assertNull(trip.startPlace)
            assertNull(trip.endPlace)
            assertNotNull(trip.endedAt)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }
}
