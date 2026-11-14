package com.autotrip.data.navigation

import com.autotrip.data.tracker.ManualTripStore
import com.autotrip.data.tracker.RoomTripTracker
import com.autotrip.domain.model.*
import com.autotrip.fake.FakeTripRepository
import com.autotrip.voice.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class NaviDestinationsTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T05:00:00Z"), ZoneOffset.UTC)
    private val a = NaviDestination("회사", "서울", 37.5, 127.0)
    private val b = NaviDestination("거래처", "인천", 37.4, 126.7)

    private class Session : NaviSession {
        var listener: ((NaviSignal) -> Unit)? = null
        var connects = 0
        var adds = 0
        var removes = 0
        var reads = 0
        var connectError: Exception? = null
        var hang = false
        val released = CompletableDeferred<Unit>()
        override suspend fun connect() {
            connects++
            connectError?.let { throw it }
            if (hang) awaitCancellation()
        }
        override fun addListener(listener: (NaviSignal) -> Unit) { adds++; this.listener = listener }
        override fun requestDestination() { reads++ }
        override fun removeListener() { removes++ }
        override fun release() { released.complete(Unit) }
        fun signal(value: NaviSignal) { listener!!(value) }
        fun change(destination: NaviDestination) {
            signal(NaviSignal.Changed)
            signal(NaviSignal.Info(destination))
        }
    }

    private suspend fun exercise(block: suspend (CoroutineScope, Session, NaviDestinations, Channel<NavigationDestination>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = Session()
        val source = NaviDestinations(scope, clock, { session })
        val received = Channel<NavigationDestination>(Channel.UNLIMITED)
        try {
            scope.launch { source.events.collect { received.send(it) } }
            withTimeout(1_000) { source.available.first { it } }
            block(scope, session, source, received)
        } finally { scope.cancel() }
    }

    @Test fun settingAndChangingEmitDomainCoordinatesAndEventTime() = runBlocking {
        exercise { _, session, _, received ->
            session.change(a)
            val first = withTimeout(1_000) { received.receive() }
            assertEquals("회사", first.name)
            assertEquals(GeoPoint(37.5, 127.0), first.point)
            assertEquals(clock.instant(), first.setAt)
            session.change(b)
            assertEquals("거래처", withTimeout(1_000) { received.receive() }.name)
        }
    }

    @Test fun repeatedDestinationIsDeduplicated() = runBlocking {
        exercise { _, session, _, received ->
            session.change(a)
            withTimeout(1_000) { received.receive() }
            session.change(a.copy(name = " 회사 "))
            yield()
            assertTrue(received.tryReceive().isFailure)
        }
    }

    @Test fun cancellationDiscardsLateInfoAndAllowsSameDestinationAgain() = runBlocking {
        exercise { _, session, _, received ->
            session.change(a)
            received.receive()
            session.signal(NaviSignal.Cancelled)
            session.signal(NaviSignal.Info(b))
            yield()
            assertTrue(received.tryReceive().isFailure)
            session.change(a)
            assertEquals("회사", withTimeout(1_000) { received.receive() }.name)
        }
    }

    @Test fun connectionDoesNotReplayAnOldDestination() = runBlocking {
        exercise { _, session, _, received ->
            assertEquals(0, session.reads)
            session.signal(NaviSignal.Info(a))
            yield()
            assertTrue(received.tryReceive().isFailure)
        }
    }

    @Test fun overlappingChangesRefreshBeforePublishing() = runBlocking {
        exercise { _, session, _, received ->
            session.signal(NaviSignal.Changed)
            session.signal(NaviSignal.Changed)
            session.signal(NaviSignal.Info(a))
            yield()
            assertEquals(2, session.reads)
            assertTrue(received.tryReceive().isFailure)
            session.signal(NaviSignal.Info(b))
            assertEquals("거래처", withTimeout(1_000) { received.receive() }.name)
        }
    }

    @Test fun invalidCoordinatesAreNotInventedAndAddressIsFallback() = runBlocking {
        exercise { _, session, _, received ->
            session.change(NaviDestination(" ", "주소", Double.NaN, 181.0))
            val value = withTimeout(1_000) { received.receive() }
            assertEquals("주소", value.name)
            assertNull(value.point)
            session.change(NaviDestination(null, null, Double.NaN, Double.NaN))
            yield()
            assertTrue(received.tryReceive().isFailure)
        }
    }

    @Test fun errorDoesNotEmitADestinationAndNextLiveChangeCanRecover() = runBlocking {
        exercise { _, session, source, received ->
            session.signal(NaviSignal.Error("service unavailable"))
            yield()
            assertFalse(source.available.value)
            assertTrue(received.tryReceive().isFailure)
            session.change(a)
            withTimeout(1_000) { received.receive() }
            assertTrue(source.available.value)
        }
    }

    @Test fun multipleSubscribersShareOneListenerAndLastCancellationReleases() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = Session()
        val source = NaviDestinations(scope, clock, { session })
        try {
            val one = scope.launch { source.events.collect() }
            val two = scope.launch { source.events.collect() }
            withTimeout(1_000) { source.available.first { it } }
            assertEquals(1, session.connects)
            assertEquals(1, session.adds)
            one.cancelAndJoin()
            assertFalse(session.released.isCompleted)
            two.cancelAndJoin()
            withTimeout(1_000) { session.released.await() }
            assertEquals(1, session.removes)
            assertFalse(source.available.value)
            session.signal(NaviSignal.Changed) // A late callback must not reopen the SDK.
            assertEquals(0, session.reads)
        } finally { scope.cancel() }
    }

    @Test fun failedConnectionAndTimeoutAreCleanedUp() = runBlocking {
        for (hang in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val session = Session().apply {
                this.hang = hang
                if (!hang) connectError = SecurityException("permission denied")
            }
            val errors = mutableListOf<Exception>()
            val source = NaviDestinations(scope, clock, { session }, 30, errors::add)
            try {
                scope.launch { source.events.collect() }
                withTimeout(1_000) { session.released.await() }
                assertFalse(source.available.value)
                assertEquals(0, session.adds)
                assertEquals(1, errors.size)
            } finally { scope.cancel() }
        }
    }

    private class Store(clock: Clock) : ManualTripStore {
        private val repo = FakeTripRepository(clock)
        override suspend fun activeTrip() = repo.activeTrip()
        override suspend fun beginTrip(vehicleId: Long, source: TripSource, startedAt: Instant,
            odometerStart: OdometerReading) = repo.beginTrip(vehicleId, source, startedAt, odometerStart)
        override suspend fun finishTrip(tripId: Long, endedAt: Instant,
            odometerEnd: OdometerReading) = repo.finishTrip(tripId, endedAt, odometerEnd)
    }

    @Test fun trackerForwardsEventsToExistingVoiceCoordinator() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = Session()
        val navigation = NaviDestinations(scope, clock, { session })
        val tracker = RoomTripTracker(Store(clock), scope, clock, navigation = navigation)
        val gateway = FakeGateway()
        val output = FakeSpeechOutput()
        val coordinator = PurposePromptCoordinator(scope, tracker.state, tracker.destinationEvents,
            gateway, output, FakeSpeechInput(listOf(ListenResult.Recognized("회의"))))
        try {
            coordinator.start()
            withTimeout(1_000) { navigation.available.first { it } }
            session.change(a)
            withTimeout(1_000) {
                coordinator.state.first { it is PurposePromptState.Completed }
            }
            assertTrue(output.spoken.isNotEmpty())
            assertEquals(TripPurpose.MEETING, gateway.calls.single().purpose)
            assertTrue(tracker.availability.value.navigationSupported)
            assertTrue(tracker.startManualTrip() is AutoTripResult.Success)
            session.signal(NaviSignal.Error("navigation offline"))
            yield()
            assertTrue(tracker.endManualTrip() is AutoTripResult.Success)
            assertTrue(tracker.state.value is TripState.Arrived)
        } finally { coordinator.stop(); scope.cancel() }
    }
}
