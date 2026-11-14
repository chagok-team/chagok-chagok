package com.autotrip.data.navigation

import com.autotrip.domain.model.NavigationDestination
import java.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NaviRecoveryTest {
    private class Session(val fail: Boolean = false) : NaviSession {
        lateinit var signal: (NaviSignal) -> Unit
        var released = 0
        var reads = 0
        override suspend fun connect() { if (fail) error("service temporarily unavailable") }
        override fun addListener(listener: (NaviSignal) -> Unit) { signal = listener }
        override fun requestDestination() { reads++ }
        override fun removeListener() {}
        override fun release() { released++ }
    }
    @Test fun connectionRecoveryDoesNotReplayCurrentDestinationIntoVoiceFlow() = runTest {
        val sessions = mutableListOf<Session>()
        val source = NaviDestinations(backgroundScope, Clock.systemUTC(), {
            Session(fail = sessions.isEmpty()).also { sessions += it }
        })
        val events = mutableListOf<NavigationDestination>()
        backgroundScope.launch { source.events.collect { events += it } }
        runCurrent(); assertEquals(1, sessions.single().released)
        advanceTimeBy(3_100); runCurrent()
        assertEquals(2, sessions.size)
        assertEquals(0, sessions.last().reads)
        val info = NaviSignal.Info(NaviDestination("Live destination", null, 37.0, 127.0))
        sessions.last().signal(info); runCurrent()
        assertTrue(events.isEmpty())
        sessions.last().signal(NaviSignal.Changed); runCurrent()
        sessions.last().signal(info); runCurrent()
        assertEquals(1, events.size)
    }
    @Test fun callbackBurstIsBoundedAndReconnectsAfterCleanup() = runTest {
        val sessions = mutableListOf<Session>()
        val source = NaviDestinations(backgroundScope, Clock.systemUTC(), { Session().also { sessions += it } })
        backgroundScope.launch { source.events.collect {} }
        runCurrent()
        repeat(200) { sessions.first().signal(NaviSignal.Changed) }
        runCurrent()
        assertEquals(1, sessions.first().released)
        advanceTimeBy(3_100); runCurrent()
        assertEquals(2, sessions.size)
        assertEquals(0, sessions.last().reads)
        assertTrue(source.available.value)
    }
}
