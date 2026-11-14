package com.autotrip.data.tracker

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.data.repository.RoomTripRepository
import com.autotrip.data.vehicle.pleosOdometer
import com.autotrip.data.vehicle.vehicleSpeedSource
import com.autotrip.data.vehicle.VehicleSpeedEvent
import com.autotrip.domain.model.*
import java.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two separate instrumentation invocations with force-stop between them. No Fake SDK data. */
@RunWith(AndroidJUnit4::class)
class PleosAutoStartTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun record(message: String) {
        context.openFileOutput("stage7-auto-start.txt", Context.MODE_APPEND).bufferedWriter().use {
            it.appendLine("${java.time.Instant.now()} uid=${android.os.Process.myUid()} $message")
        }
    }
    @Test fun captureAutoTrip() = runBlocking<Unit> {
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission("android.car.permission.CAR_SPEED"))
        val dbName = "stage7-auto-${System.currentTimeMillis()}.db"
        context.openFileOutput("stage7-auto-db.txt", Context.MODE_PRIVATE).bufferedWriter().use { it.write(dbName) }
        context.openFileOutput("stage7-auto-start.txt", Context.MODE_PRIVATE).close()
        val db = Room.databaseBuilder(context, AutoTripDatabase::class.java, dbName).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = RoomTripRepository(db)
            val tracker = RoomTripTracker(repository, scope, odometer = pleosOdometer(context, Clock.systemUTC()))
            tracker.restoreActiveTrip()
            val coordinator = AutoTripCoordinator(vehicleSpeedSource(context, scope), tracker, scope, SystemClock::elapsedRealtimeNanos)
            coordinator.start()
            record("READY: real source; require 5s stop then 3s movement")
            val state = withTimeout(65_000) { tracker.state.first { it is TripState.Driving } } as TripState.Driving
            val trip = repository.observeTrip(state.tripId).first()!!
            assertEquals(TripSource.AUTO, trip.source)
            assertEquals(OdometerCaptureStatus.CAPTURED, trip.odometerStart.status)
            assertNull(trip.endedAt)
            coordinator.stop()
            record("ACTIVE_PERSISTED id=${trip.id} startMeters=${trip.odometerStart.meters}")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
        }
    }
    @Test fun restoreAndManuallyFinishAfterProcessRestart() = runBlocking<Unit> {
        val dbName = context.openFileInput("stage7-auto-db.txt").bufferedReader().use { it.readText() }
        val db = Room.databaseBuilder(context, AutoTripDatabase::class.java, dbName).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = RoomTripRepository(db)
            val before = repository.activeTrip() ?: error("No active AUTO trip")
            val tracker = RoomTripTracker(repository, scope, odometer = pleosOdometer(context, Clock.systemUTC()))
            tracker.restoreActiveTrip()
            assertEquals(before.id, (tracker.state.value as TripState.Driving).tripId)
            assertEquals(TripSource.AUTO, before.source)
            record("RESTORED id=${before.id}; waiting for actual speed 0")
            withTimeout(20_000) {
                vehicleSpeedSource(context, scope).events.first {
                    it is VehicleSpeedEvent.Sample && it.metersPerSecond <= 0.01f
                }
            }
            assertTrue(tracker.endManualTrip() is AutoTripResult.Success)
            val saved = repository.observeTrip(before.id).first()!!
            assertNotNull(saved.endedAt)
            assertEquals(OdometerCaptureStatus.CAPTURED, saved.odometerEnd.status)
            assertEquals(1_000L, saved.drivenMeters)
            assertNull(repository.activeTrip())
            record("COMPLETE id=${saved.id} endMeters=${saved.odometerEnd.meters} drivenMeters=${saved.drivenMeters}")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
        }
    }
}
