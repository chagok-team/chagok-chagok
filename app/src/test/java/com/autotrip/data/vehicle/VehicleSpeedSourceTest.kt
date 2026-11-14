package com.autotrip.data.vehicle

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class VehicleSpeedSourceTest {
    private class Session : VehicleSpeedSession {
        var connections = 0
        var registrations = 0
        var closes = 0
        var supported = true
        var hang = false
        var connectFailure: Exception? = null
        var registerFailure = false
        var value = RawVehicleSpeed(2f, 9_999_000_000L)
        lateinit var onValue: (RawVehicleSpeed) -> Unit
        lateinit var onError: () -> Unit
        lateinit var onDisconnect: () -> Unit
        val closed = CompletableDeferred<Unit>()
        override suspend fun connect(onDisconnected: () -> Unit) {
            connections++
            onDisconnect = onDisconnected
            connectFailure?.let { throw it }
            if (hang) awaitCancellation()
        }
        override fun isSupported() = supported
        override fun current() = value
        override fun subscribe(onValue: (RawVehicleSpeed) -> Unit, onError: () -> Unit) {
            registrations++
            this.onValue = onValue
            this.onError = onError
            if (registerFailure) error("registration rejected")
        }
        override fun close() { closes++; closed.complete(Unit) }
    }

    private class Harness(session: Session = Session()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var permission = true
        var now = 10_000_000_000L
        val session = session
        var creations = 0
        val source = ValidatedVehicleSpeedSource(scope, { permission },
            { creations++; this.session }, { now })
        val events = Channel<VehicleSpeedEvent>(Channel.UNLIMITED)
        val collector = scope.launch { source.events.collect { events.send(it) } }
        suspend fun next(): VehicleSpeedEvent = withTimeout(1_000) { events.receive() }
        fun stop() { scope.cancel() }
    }

    @Test fun readsInitialValueAndUsesAbsoluteMetersPerSecondForReverse() = runBlocking {
        val h = Harness()
        try {
            val first = h.next() as VehicleSpeedEvent.Sample
            assertEquals(2f, first.metersPerSecond)
            h.session.onValue(RawVehicleSpeed(-5f, h.now))
            val reverse = h.next() as VehicleSpeedEvent.Sample
            assertEquals(5f, reverse.metersPerSecond)
            assertEquals(h.now, reverse.timestampNanos)
            assertEquals(h.now, reverse.receivedAtNanos)
        } finally { h.stop() }
    }

    @Test fun zeroIsOnlyPublishedWhenActuallyMeasured() = runBlocking {
        val h = Harness()
        try {
            h.next()
            h.session.onValue(RawVehicleSpeed(0f, h.now))
            assertEquals(0f, (h.next() as VehicleSpeedEvent.Sample).metersPerSecond)
            h.session.onError()
            assertEquals(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.SDK_ERROR), h.next())
        } finally { h.stop() }
    }

    @Test fun rejectsNullNanInfinityAndUnavailableStatus() = runBlocking {
        val h = Harness()
        try {
            h.next()
            for (value in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                h.session.onValue(RawVehicleSpeed(value, h.now))
                assertEquals(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.INVALID_VALUE), h.next())
            }
            h.session.onValue(RawVehicleSpeed(5f, h.now, available = false))
            assertEquals(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.SDK_ERROR), h.next())
        } finally { h.stop() }
    }

    @Test fun rejectsFutureZeroOldRegressingAndDuplicateTimestamps() = runBlocking {
        val h = Harness()
        try {
            h.next()
            val cases = listOf(
                0L to SpeedUnavailableReason.INVALID_TIMESTAMP,
                (h.now + 1) to SpeedUnavailableReason.INVALID_TIMESTAMP,
                (h.now - 2_000_000_000L) to SpeedUnavailableReason.STALE,
                (h.now - 2_000_000L) to SpeedUnavailableReason.OUT_OF_ORDER,
                h.session.value.timestampNanos to SpeedUnavailableReason.DUPLICATE,
            )
            for ((timestamp, reason) in cases) {
                h.session.onValue(RawVehicleSpeed(3f, timestamp))
                assertEquals(VehicleSpeedEvent.Unavailable(reason), h.next())
            }
            h.session.onValue(RawVehicleSpeed(3f, h.now))
            assertTrue(h.next() is VehicleSpeedEvent.Sample)
        } finally { h.stop() }
    }

    @Test fun unchangedSpeedWithNewTimestampRemainsValid() = runBlocking {
        val h = Harness()
        try {
            h.next()
            h.session.onValue(RawVehicleSpeed(2f, h.now))
            assertTrue(h.next() is VehicleSpeedEvent.Sample)
        } finally { h.stop() }
    }

    @Test fun permissionDeniedDoesNotCreateCarSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var created = false
        try {
            val source = ValidatedVehicleSpeedSource(scope, { false }, { created = true; Session() }, { 1L })
            assertEquals(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.PERMISSION_DENIED),
                withTimeout(1_000) { source.events.first() })
            assertFalse(created)
        } finally { scope.cancel() }
    }

    @Test fun revokedPermissionInvalidatesAndClosesSession() = runBlocking {
        val h = Harness()
        try {
            h.next()
            h.permission = false
            h.session.onValue(RawVehicleSpeed(4f, h.now))
            assertEquals(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.PERMISSION_DENIED), h.next())
            withTimeout(1_000) { h.session.closed.await() }
        } finally { h.stop() }
    }

    @Test fun unsupportedPropertyDoesNotRegisterListener() = runBlocking {
        val h = Harness(Session().apply { supported = false })
        try {
            assertEquals(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.UNSUPPORTED), h.next())
            assertEquals(0, h.session.registrations)
            withTimeout(1_000) { h.session.closed.await() }
        } finally { h.stop() }
    }

    @Test fun disconnectAndCallbackErrorAreIsolatedAndCleanedUp() = runBlocking {
        for (disconnect in listOf(true, false)) {
            val h = Harness()
            try {
                h.next()
                if (disconnect) h.session.onDisconnect() else h.session.onError()
                assertEquals(VehicleSpeedEvent.Unavailable(if (disconnect) SpeedUnavailableReason.DISCONNECTED else SpeedUnavailableReason.SDK_ERROR), h.next())
                withTimeout(1_000) { h.session.closed.await() }
                assertEquals(1, h.session.closes)
            } finally { h.stop() }
        }
    }

    @Test fun connectionFailureAndRegistrationFailureCloseSession() = runBlocking {
        for (connect in listOf(true, false)) {
            val h = Harness(Session().apply {
                if (connect) connectFailure = IllegalStateException("bind failed") else registerFailure = true
            })
            try {
                assertEquals(VehicleSpeedEvent.Unavailable(if (connect) SpeedUnavailableReason.CONNECTION_FAILED else SpeedUnavailableReason.SDK_ERROR), h.next())
                withTimeout(1_000) { h.session.closed.await() }
            } finally { h.stop() }
        }
    }

    @Test fun connectionTimeoutClosesPartialSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = Session().apply { hang = true }
        try {
            val source = ValidatedVehicleSpeedSource(scope, { true }, { session }, { 1L }, connectionTimeoutMillis = 30)
            assertEquals(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.CONNECTION_TIMEOUT),
                withTimeout(1_000) { source.events.first() })
            withTimeout(1_000) { session.closed.await() }
        } finally { scope.cancel() }
    }

    @Test fun silenceExpiresLastMeasurementWithoutInventingZero() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = Session().apply { value = RawVehicleSpeed(5f, System.nanoTime()) }
        val events = Channel<VehicleSpeedEvent>(Channel.UNLIMITED)
        try {
            val source = ValidatedVehicleSpeedSource(scope, { true }, { session }, System::nanoTime, maxAgeMillis = 200)
            scope.launch { source.events.collect { events.send(it) } }
            assertTrue(withTimeout(1_000) { events.receive() } is VehicleSpeedEvent.Sample)
            assertEquals(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.STALE), withTimeout(1_000) { events.receive() })
        } finally { scope.cancel() }
    }

    @Test fun subscribersShareOneRegistrationAndLastUnsubscribeCloses() = runBlocking {
        val h = Harness()
        try {
            h.next()
            val secondEvents = Channel<VehicleSpeedEvent>(Channel.UNLIMITED)
            val second = h.scope.launch { h.source.events.collect { secondEvents.send(it) } }
            yield()
            assertTrue(secondEvents.tryReceive().isFailure) // No replay of the first reading.
            assertEquals(1, h.session.connections)
            assertEquals(1, h.session.registrations)
            h.collector.cancelAndJoin()
            assertFalse(h.session.closed.isCompleted)
            second.cancelAndJoin()
            withTimeout(1_000) { h.session.closed.await() }
            h.session.onValue(RawVehicleSpeed(10f, h.now)) // Late callback must be harmless.
            assertEquals(1, h.session.closes)
        } finally { h.stop() }
    }
}
