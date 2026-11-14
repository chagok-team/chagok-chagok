
package com.autotrip.data.tracker

import com.autotrip.domain.model.*
import com.autotrip.fake.FakeTripRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.autotrip.data.vehicle.VehicleSpeedSource
import com.autotrip.data.vehicle.VehicleSpeedEvent
import com.autotrip.data.vehicle.OdometerSource

class RoomTripTrackerAutoStartTest {

    private val clock = Clock.fixed(
        Instant.parse("2026-11-14T03:00:00Z"),
        ZoneOffset.UTC
    )

    private class Store(
        clock: Clock,
        var failNextStart: Boolean = false
    ) : ManualTripStore {
        val trips = FakeTripRepository(clock)
        var beginCalls = 0
        var beforeBegin: (suspend () -> Unit)? = null
        var beforeFinish: (suspend () -> Unit)? = null
        var failFinish = false
        var finishCalls = 0

        override suspend fun activeTrip(): Trip? =
            trips.activeTrip()

        override suspend fun latestFinishedTrip(): Trip? =
            trips.snapshot().filter { it.endedAt != null }.maxWithOrNull(compareBy<Trip> { it.endedAt }.thenBy { it.id })

        override suspend fun beginTrip(
            vehicleId: Long,
            source: TripSource,
            startedAt: Instant,
            odometerStart: OdometerReading
        ): AutoTripResult<Trip> {
            beginCalls++
            beforeBegin?.invoke()

            if (failNextStart) {
                failNextStart = false
                return AutoTripResult.Failure(
                    AutoTripError.InvalidState("Simulated failure")
                )
            }

            return trips.beginTrip(
                vehicleId, source, startedAt, odometerStart
            )
        }

        override suspend fun finishTrip(
            tripId: Long,
            endedAt: Instant,
            odometerEnd: OdometerReading
        ): AutoTripResult<Trip> {
            finishCalls++
            beforeFinish?.invoke()
            if (failFinish) return AutoTripResult.Failure(AutoTripError.Storage(IllegalStateException("DB failed")))
            return trips.finishTrip(tripId, endedAt, odometerEnd)
        }
    }

    private fun tracker(
        store: Store,
        scope: CoroutineScope
    ) = RoomTripTracker(
        repository = store,
        scope = scope,
        clock = clock
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun endedTripRestoresArrivedWithoutCreatingAnotherTrip() = runTest {
        val store = Store(clock)
        val first = tracker(store, backgroundScope)
        val id = (first.startAutoTrip() as AutoTripResult.Success).value
        first.endManualTrip()
        val restored = tracker(store, backgroundScope)
        restored.restoreActiveTrip()
        assertEquals(first.state.value, restored.state.value)
        assertEquals(id, (restored.state.value as TripState.Arrived).tripId)
        assertEquals(1, store.beginCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun activeTripTakesPriorityOverOlderFinishedTrip() = runTest {
        val store = Store(clock)
        val first = tracker(store, backgroundScope)
        first.startAutoTrip(); first.endManualTrip()
        val active = (first.startManualTrip() as AutoTripResult.Success).value
        val restored = tracker(store, backgroundScope)
        restored.restoreActiveTrip()
        assertEquals(active, (restored.state.value as TripState.Driving).tripId)
        assertEquals(TripSource.MANUAL, (restored.state.value as TripState.Driving).source)
        assertEquals(2, store.trips.snapshot().size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun concurrentManualAndAutomaticFinishPersistOnlyOnce() = runTest {
        val store = Store(clock)
        val tracker = tracker(store, backgroundScope)
        val id = (tracker.startAutoTrip() as AutoTripResult.Success).value
        val gate = CompletableDeferred<Unit>()
        store.beforeFinish = { gate.await() }
        val auto = async { tracker.finishAutoTrip(id) }
        runCurrent()
        val manual = async { tracker.endManualTrip() }
        runCurrent()
        gate.complete(Unit)
        assertTrue(auto.await() is AutoTripResult.Success)
        assertTrue(manual.await() is AutoTripResult.Failure)
        assertEquals(1, store.finishCalls)
        assertTrue(tracker.state.value is TripState.Arrived)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun finishCancellationPublishesBeforeNewTripAndOldIdCannotEndIt() = runTest {
        val store = Store(clock)
        val tracker = tracker(store, backgroundScope)
        val id = (tracker.startAutoTrip() as AutoTripResult.Success).value
        val gate = CompletableDeferred<Unit>()
        store.beforeFinish = { gate.await() }
        val ending = launch { tracker.finishAutoTrip(id) }
        runCurrent(); ending.cancel()
        val newTrip = async { tracker.startAutoTrip() }
        runCurrent()
        assertFalse(newTrip.isCompleted)
        gate.complete(Unit); ending.join()
        val nextId = (newTrip.await() as AutoTripResult.Success).value
        assertNotEquals(id, nextId)
        assertTrue(tracker.finishAutoTrip(id) is AutoTripResult.Failure)
        assertEquals(nextId, store.activeTrip()!!.id)
        assertEquals(nextId, (tracker.state.value as TripState.Driving).tripId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun failedFinishRetainsActiveTripAndRestoresForRetry() = runTest {
        val store = Store(clock)
        val original = tracker(store, backgroundScope)
        val id = (original.startAutoTrip() as AutoTripResult.Success).value
        store.failFinish = true
        assertTrue(original.finishAutoTrip(id) is AutoTripResult.Failure)
        assertTrue(original.state.value is TripState.Driving)
        val restored = tracker(store, backgroundScope)
        restored.restoreActiveTrip()
        assertEquals(original.state.value, restored.state.value)
        store.failFinish = false
        assertTrue(restored.finishAutoTrip(id) is AutoTripResult.Success)
        assertNull(store.activeTrip())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun automaticFinishNeverEndsManualTrip() = runTest {
        val store = Store(clock)
        val tracker = tracker(store, backgroundScope)
        val id = (tracker.startManualTrip() as AutoTripResult.Success).value
        assertTrue(tracker.finishAutoTrip(id) is AutoTripResult.Failure)
        assertEquals(0, store.finishCalls)
        assertNotNull(store.activeTrip())
    }

    @Test
    fun autoStartPersistsAutoSource() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = Store(clock)
            val tracker = tracker(store, scope)

            val result = tracker.startAutoTrip()

            assertTrue(result is AutoTripResult.Success)
            assertEquals(1, store.beginCalls)

            val trip = store.trips.snapshot().single()
            assertEquals(TripSource.AUTO, trip.source)

            val state = tracker.state.value as TripState.Driving
            assertEquals(trip.id, state.tripId)
            assertEquals(TripSource.AUTO, state.source)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun manualTripBlocksAutoStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = Store(clock)
            val tracker = tracker(store, scope)

            assertTrue(tracker.startManualTrip() is AutoTripResult.Success)
            assertTrue(tracker.startAutoTrip() is AutoTripResult.Failure)

            assertEquals(1, store.beginCalls)
            assertEquals(1, store.trips.snapshot().size)
            assertEquals(
                TripSource.MANUAL,
                store.trips.snapshot().single().source
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun autoTripBlocksManualStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = Store(clock)
            val tracker = tracker(store, scope)

            assertTrue(tracker.startAutoTrip() is AutoTripResult.Success)
            assertTrue(tracker.startManualTrip() is AutoTripResult.Failure)

            assertEquals(1, store.beginCalls)
            assertEquals(1, store.trips.snapshot().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedAutoStartDoesNotActivateTrip() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = Store(clock, failNextStart = true)
            val tracker = tracker(store, scope)

            assertTrue(tracker.startAutoTrip() is AutoTripResult.Failure)
            assertEquals(TripState.Idle, tracker.state.value)
            assertTrue(store.trips.snapshot().isEmpty())

            // A later explicit retry can succeed.
            assertTrue(tracker.startAutoTrip() is AutoTripResult.Success)
            assertEquals(2, store.beginCalls)
            assertEquals(1, store.trips.snapshot().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun restoredAutoTripBlocksDuplicateStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = Store(clock)
            val original = tracker(store, scope)

            assertTrue(original.startAutoTrip() is AutoTripResult.Success)

            // Simulate a new tracker instance using the same persistence.
            val restored = tracker(store, scope)
            restored.restoreActiveTrip()

            val state = restored.state.value as TripState.Driving
            assertEquals(TripSource.AUTO, state.source)

            assertTrue(restored.startAutoTrip() is AutoTripResult.Failure)
            assertEquals(1, store.beginCalls)
            assertEquals(1, store.trips.snapshot().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun simultaneousStartsCreateOnlyOneTrip() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = Store(clock)
            val tracker = tracker(store, scope)

            val results = coroutineScope {
                listOf(
                    async(Dispatchers.Default) { tracker.startAutoTrip() },
                    async(Dispatchers.Default) { tracker.startManualTrip() }
                ).awaitAll()
            }

            assertEquals(
                1,
                results.count { it is AutoTripResult.Success }
            )
            assertEquals(
                1,
                results.count { it is AutoTripResult.Failure }
            )
            assertEquals(1, store.beginCalls)
            assertEquals(1, store.trips.snapshot().size)
        } finally {
            scope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun manualStartWaitsForPendingAutoCommitAndCannotDuplicate() = runTest {
        val store = Store(clock)
        val gate = CompletableDeferred<Unit>()
        store.beforeBegin = { gate.await() }
        val tracker = tracker(store, backgroundScope)
        val auto = async { tracker.startAutoTrip() }
        runCurrent()
        val manual = async { tracker.startManualTrip() }
        runCurrent()
        assertEquals(1, store.beginCalls)
        assertFalse(manual.isCompleted)
        gate.complete(Unit)
        assertTrue(auto.await() is AutoTripResult.Success)
        assertTrue(manual.await() is AutoTripResult.Failure)
        assertEquals(1, store.trips.snapshot().size)
        assertEquals(TripSource.AUTO, store.trips.snapshot().single().source)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationDuringCommitPublishesSavedTripBeforeUnlocking() = runTest {
        val store = Store(clock)
        val gate = CompletableDeferred<Unit>()
        store.beforeBegin = { gate.await() }
        val tracker = tracker(store, backgroundScope)
        val auto = launch { tracker.startAutoTrip() }
        runCurrent()
        auto.cancel()
        val manual = async { tracker.startManualTrip() }
        runCurrent()
        assertFalse(auto.isCompleted)
        gate.complete(Unit)
        auto.join()
        assertTrue(auto.isCancelled)
        assertTrue(manual.await() is AutoTripResult.Failure)
        assertEquals(1, store.beginCalls)
        assertEquals(store.trips.snapshot().single().id, (tracker.state.value as TripState.Driving).tripId)
        val restored = tracker(store, backgroundScope)
        restored.restoreActiveTrip()
        assertEquals(tracker.state.value, restored.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun coordinatorUsesRealTrackerAndOdometerContractAcrossRestoration() = runTest {
        // Exercises the real Tracker and coordinator; the store/vehicle are injected JVM fakes.
        val store = Store(clock)
        val speedEvents = MutableSharedFlow<VehicleSpeedEvent>()
        val speed = object : VehicleSpeedSource { override val events = speedEvents }
        var captures = 0
        val odometer = object : OdometerSource {
            override val supported = MutableStateFlow(true)
            override suspend fun capture(minimumMeters: Long?): OdometerReading =
                OdometerReading(if (++captures == 1) 100_000 else 101_000,
                    OdometerCaptureStatus.CAPTURED, clock.instant())
        }
        fun newTracker() = RoomTripTracker(store, backgroundScope, clock, odometer = odometer)
        fun coordinator(tracker: RoomTripTracker) = AutoTripCoordinator(speed, tracker, backgroundScope,
            { (currentTime + 1_000) * 1_000_000 })
        suspend fun samples(value: Float, count: Int) {
            repeat(count) {
                advanceTimeBy(500)
                val nanos = (currentTime + 1_000) * 1_000_000
                speedEvents.emit(VehicleSpeedEvent.Sample(value, nanos, nanos))
                runCurrent()
            }
        }
        val original = newTracker()
        original.restoreActiveTrip()
        val firstCoordinator = coordinator(original)
        firstCoordinator.start(); runCurrent()
        samples(0f, 11); samples(3f, 7)
        assertEquals(1, store.beginCalls)
        assertEquals(TripSource.AUTO, store.trips.snapshot().single().source)
        assertEquals(100_000L, store.trips.snapshot().single().odometerStart.meters)
        assertTrue(original.availability.value.autoDetectionSupported)
        firstCoordinator.stop(); runCurrent()
        assertFalse(original.availability.value.autoDetectionSupported)

        val restored = newTracker()
        restored.restoreActiveTrip()
        assertEquals(original.state.value, restored.state.value)
        val secondCoordinator = coordinator(restored)
        secondCoordinator.start(); runCurrent()
        samples(3f, 10)
        assertEquals(1, store.beginCalls)
        assertTrue(restored.endManualTrip() is AutoTripResult.Success)
        val saved = store.trips.snapshot().single()
        assertNotNull(saved.endedAt)
        assertEquals(1_000L, saved.drivenMeters)
        secondCoordinator.stop()
    }
}
