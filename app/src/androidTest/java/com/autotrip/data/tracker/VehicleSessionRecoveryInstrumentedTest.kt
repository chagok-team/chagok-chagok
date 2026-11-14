package com.autotrip.data.tracker

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.data.repository.RoomTripRepository
import com.autotrip.data.vehicle.*
import com.autotrip.domain.model.*
import java.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Car sessions and readings; fault notification is explicitly injected at the session boundary.
 * This is NOT a car_service/Binder crash test and never stops a system service. */
@RunWith(AndroidJUnit4::class)
class VehicleSessionRecoveryInstrumentedTest {
    private class OwnedSession(context: Context) : VehicleSpeedSession {
        private val delegate = AndroidVehicleSpeedSession(context)
        lateinit var disconnected: () -> Unit
        var cleanupCalls = 0
        override suspend fun connect(onDisconnected: () -> Unit) { disconnected = onDisconnected; delegate.connect(onDisconnected) }
        override fun isSupported() = delegate.isSupported()
        override fun current() = delegate.current()
        override fun subscribe(onValue: (RawVehicleSpeed) -> Unit, onError: () -> Unit) = delegate.subscribe(onValue, onError)
        override fun close() { cleanupCalls++; delegate.close() }
        fun injectOwnedDisconnect() { delegate.close(); disconnected() }
    }
    @Test fun actualSessionRecyclePreservesOneRoomTrip() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AutoTripDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sessions = java.util.concurrent.CopyOnWriteArrayList<OwnedSession>()
        try {
            val repository = RoomTripRepository(db)
            val tracker = RoomTripTracker(repository, scope, odometer = pleosOdometer(context, Clock.systemUTC()))
            val id = (tracker.startAutoTrip() as AutoTripResult.Success).value
            val source = ValidatedVehicleSpeedSource(scope,
                { context.checkSelfPermission("android.car.permission.CAR_SPEED") == android.content.pm.PackageManager.PERMISSION_GRANTED },
                { OwnedSession(context).also { sessions += it } }, SystemClock::elapsedRealtimeNanos)
            val latest = MutableStateFlow<VehicleSpeedEvent.Sample?>(null)
            val observer = scope.launch { source.events.collect { if (it is VehicleSpeedEvent.Sample) latest.value = it } }
            val coordinator = AutoTripCoordinator(source, tracker, scope, SystemClock::elapsedRealtimeNanos)
            coordinator.start()
            withTimeout(15_000) { latest.first { it != null } }
            val cutAt = SystemClock.elapsedRealtimeNanos()
            sessions.single().injectOwnedDisconnect()
            withTimeout(20_000) { latest.first { it != null && sessions.size >= 2 && it.timestampNanos > cutAt } }
            assertEquals(1, sessions.first().cleanupCalls)
            assertEquals(id, (tracker.state.value as TripState.Driving).tripId)
            assertEquals(id, repository.activeTrip()!!.id)
            assertEquals(1, db.tripDao().observeTrips(Long.MIN_VALUE, Long.MAX_VALUE).first().size)
            assertTrue(tracker.endManualTrip() is AutoTripResult.Success)
            assertNull(repository.activeTrip())
            coordinator.stop(); observer.cancelAndJoin()
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            assertTrue(sessions.all { it.cleanupCalls == 1 })
            db.close()
        }
    }
}
