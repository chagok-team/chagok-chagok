package com.autotrip.data.vehicle

import com.autotrip.domain.model.OdometerCaptureStatus.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import org.junit.Assert.*
import org.junit.Test

class VehicleOdometerTest {
    private val now = Instant.parse("2026-11-14T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private class Session : VehicleOdometerSession {
        var initialized = false
        var released = 0
        var reads = 0
        var readable = true
        var kilometers: Float? = 123.456f
        var initializeError: Exception? = null
        var capabilityError: Exception? = null
        var readError: Exception? = null
        var waitForever = false
        val enteredRead = CompletableDeferred<Unit>()
        override fun initialize() { initializeError?.let { throw it }; initialized = true }
        override suspend fun canRead(): Boolean {
            check(initialized)
            capabilityError?.let { throw it }
            return readable
        }
        override suspend fun distanceKilometers(): Float? {
            reads++
            enteredRead.complete(Unit)
            if (waitForever) awaitCancellation()
            readError?.let { throw it }
            return kilometers
        }
        override fun release() { released++ }
    }

    @Test fun kilometersAreRoundedToMetersAndTimestamped() = runBlocking {
        val session = Session()
        val source = VehicleOdometer({ session }, clock)
        val reading = source.capture()
        assertEquals(CAPTURED, reading.status)
        assertEquals(123_456L, reading.meters)
        assertEquals(now, reading.capturedAt)
        assertTrue(source.supported.value)
        assertEquals(1, session.released)
    }

    @Test fun zeroIsAValidMeasuredValue() = runBlocking {
        val source = VehicleOdometer({ Session().apply { kilometers = 0f } }, clock)
        assertEquals(0L, source.capture().meters)
    }

    @Test fun unsupportedCapabilityDoesNotRead() = runBlocking {
        val session = Session().apply { readable = false }
        val source = VehicleOdometer({ session }, clock)
        val reading = source.capture()
        assertEquals(UNSUPPORTED, reading.status)
        assertNull(reading.meters)
        assertNull(reading.capturedAt)
        assertFalse(source.supported.value)
        assertEquals(0, session.reads)
        assertEquals(1, session.released)
    }

    @Test fun permissionErrorIsFailureRatherThanUnsupported() = runBlocking {
        val session = Session().apply { capabilityError = SecurityException("denied") }
        assertEquals(FAILED, VehicleOdometer({ session }, clock).capture().status)
        assertEquals(0, session.reads)
        assertEquals(1, session.released)
    }

    @Test fun sdkReadErrorIsFailureAndKeepsConfirmedCapability() = runBlocking {
        val session = Session().apply { readError = IllegalStateException("disconnected") }
        val source = VehicleOdometer({ session }, clock)
        val reading = source.capture()
        assertEquals(FAILED, reading.status)
        assertNull(reading.meters)
        assertTrue(source.supported.value)
        assertEquals(1, session.released)
    }

    @Test fun initializationFailureReleasesAndNeverReads() = runBlocking {
        val session = Session().apply { initializeError = IllegalStateException("bind failed") }
        assertEquals(FAILED, VehicleOdometer({ session }, clock).capture().status)
        assertEquals(0, session.reads)
        assertEquals(1, session.released)
    }

    @Test fun invalidValuesNeverBecomeMeasurements() = runBlocking {
        for (value in listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.MAX_VALUE)) {
            val source = VehicleOdometer({ Session().apply { kilometers = value } }, clock)
            val reading = source.capture()
            assertEquals("value=$value", FAILED, reading.status)
            assertNull(reading.meters)
            assertNull(reading.capturedAt)
        }
    }

    @Test fun decreasingEndValueIsFailure() = runBlocking {
        val source = VehicleOdometer({ Session().apply { kilometers = 10f } }, clock)
        assertEquals(FAILED, source.capture(minimumMeters = 10_001).status)
        assertEquals(CAPTURED, source.capture(minimumMeters = 10_000).status)
    }

    @Test fun timeoutReleasesSession() = runBlocking {
        val session = Session().apply { waitForever = true }
        assertEquals(FAILED, VehicleOdometer({ session }, clock, timeoutMillis = 30).capture().status)
        assertEquals(1, session.released)
    }

    @Test fun callerCancellationReleasesWithoutReturningAFakeFailure() = runBlocking {
        val session = Session().apply { waitForever = true }
        val job = async { VehicleOdometer({ session }, clock).capture() }
        session.enteredRead.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, session.released)
    }

    @Test fun eachCaptureUsesAndReleasesItsOwnSession() = runBlocking {
        val sessions = mutableListOf<Session>()
        val source = VehicleOdometer({ Session().also { sessions += it } }, clock)
        source.capture()
        source.capture()
        assertEquals(2, sessions.size)
        assertTrue(sessions.all { it.released == 1 && it.reads == 1 })
    }

    @Test fun onlyReadAndReadWriteCapabilitiesAllowCapture() {
        assertTrue(isReadable(AccessType.READ))
        assertTrue(isReadable(AccessType.READ_WRITE))
        assertFalse(isReadable(AccessType.NONE))
        assertFalse(isReadable(AccessType.WRITE))
    }

    @Test fun outerTimeoutIsNotConvertedToASdkFailure() = runBlocking {
        val session = Session().apply { waitForever = true }
        try {
            withTimeout(30) { VehicleOdometer({ session }, clock).capture() }
            fail("Caller timeout must propagate")
        } catch (_: TimeoutCancellationException) {
            assertEquals(1, session.released)
        }
    }
}
