package com.autotrip.data.vehicle

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleSpeedRecoveryTest {
    private class Session(val now: () -> Long) : VehicleSpeedSession {
        lateinit var disconnect: () -> Unit
        lateinit var value: (RawVehicleSpeed) -> Unit
        var closed = 0
        var registrations = 0
        override suspend fun connect(onDisconnected: () -> Unit) { disconnect = onDisconnected }
        override fun isSupported() = true
        override fun current() = RawVehicleSpeed(0f, now())
        override fun subscribe(onValue: (RawVehicleSpeed) -> Unit, onError: () -> Unit) { registrations++; value = onValue }
        override fun close() { closed++ }
    }
    private class Harness(val test: TestScope) {
        var permission = true
        val sessions = mutableListOf<Session>()
        var samples = 0
        val source = ValidatedVehicleSpeedSource(test.backgroundScope, { permission },
            { Session { now() }.also { sessions += it } }, { now() })
        fun now() = (test.currentTime + 1_000) * 1_000_000
        fun start() { test.backgroundScope.launch { source.events.collect { if (it is VehicleSpeedEvent.Sample) samples++ } }; test.runCurrent() }
    }
    @Test fun disconnectReconnectsOnceAfterBackoffWithoutExtraSubscriber() = runTest {
        val h = Harness(this); h.start()
        h.sessions.single().disconnect(); runCurrent()
        assertEquals(1, h.sessions.single().closed)
        advanceTimeBy(2_999); runCurrent(); assertEquals(1, h.sessions.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, h.sessions.size)
        assertEquals(1, h.sessions.last().registrations)
        assertEquals(2, h.samples)
    }
    @Test fun permissionGrantedLaterRecoversExistingSubscription() = runTest {
        val h = Harness(this); h.permission = false; h.start()
        assertTrue(h.sessions.isEmpty())
        h.permission = true
        advanceTimeBy(3_100); runCurrent()
        assertEquals(1, h.sessions.size)
        assertEquals(1, h.samples)
    }
    @Test fun quietPermissionRevocationAndReapprovalReleaseAndReconnect() = runTest {
        val h = Harness(this); h.start()
        h.permission = false
        advanceTimeBy(2_100); runCurrent()
        assertEquals(1, h.sessions.first().closed)
        h.permission = true
        advanceTimeBy(3_100); runCurrent()
        assertEquals(2, h.sessions.size)
    }
    @Test fun callbackOverflowClosesOldSessionThenRecovers() = runTest {
        val h = Harness(this); h.start()
        val first = h.sessions.single()
        repeat(200) { first.value(RawVehicleSpeed(5f, h.now())) }
        runCurrent()
        advanceTimeBy(3_100); runCurrent()
        assertEquals(1, first.closed)
        assertEquals(2, h.sessions.size)
        assertEquals(1, h.sessions.last().registrations)
    }
    @Test fun repeatedDisconnectsNeverOverlapSessions() = runTest {
        val h = Harness(this); h.start()
        repeat(10) {
            h.sessions.last().disconnect(); runCurrent()
            advanceTimeBy(3_100); runCurrent()
            assertEquals(it + 2, h.sessions.size)
            assertTrue(h.sessions.dropLast(1).all { old -> old.closed == 1 })
        }
    }
    @Test fun twoVirtualHoursUseOneListenerAndDoNotReplayOldSamples() = runTest {
        val h = Harness(this); h.start()
        repeat(7_200) {
            advanceTimeBy(1_000)
            h.sessions.single().value(RawVehicleSpeed(5f, h.now()))
            runCurrent()
        }
        assertEquals(1, h.sessions.size)
        assertEquals(1, h.sessions.single().registrations)
        assertEquals(7_201, h.samples)
    }
}
