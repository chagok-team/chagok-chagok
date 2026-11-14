
package com.autotrip.data.tracker

import com.autotrip.data.vehicle.VehicleSpeedEvent
import com.autotrip.data.vehicle.VehicleSpeedSource
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.TripState
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import com.autotrip.data.vehicle.SpeedUnavailableReason
import com.autotrip.domain.model.AutoTripError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoTripCoordinatorTest {

    private class FakeSpeedSource : VehicleSpeedSource {
        private val stream = MutableSharedFlow<VehicleSpeedEvent?>()
        var subscriptions = 0
        var active = 0
        var maxActive = 0
        var cleanupGate: CompletableDeferred<Unit>? = null
        var throwOnEnd = false
        override val events: Flow<VehicleSpeedEvent> = flow {
            subscriptions++
            active++
            maxActive = maxOf(maxActive, active)
            try {
                stream.collect {
                    if (it == null) {
                        if (throwOnEnd) error("SDK stream failed")
                        throw EndOfStream()
                    }
                    emit(it)
                }
            } catch (_: EndOfStream) {
                // Normal SDK stream completion.
            } finally {
                withContext(NonCancellable) { cleanupGate?.await() }
                active--
            }
        }
        private class EndOfStream : Exception()
        suspend fun unavailable() = stream.emit(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.DISCONNECTED))
        suspend fun end() = stream.emit(null)
        suspend fun raw(value: VehicleSpeedEvent.Sample) = stream.emit(value)

        suspend fun awaitSubscriber() {
            stream.subscriptionCount.first { it > 0 }
        }

        suspend fun emit(speedMps: Float, timeMs: Long) {
            val nanos = timeMs * 1_000_000L
            stream.emit(
                VehicleSpeedEvent.Sample(
                    metersPerSecond = speedMps,
                    timestampNanos = nanos,
                    receivedAtNanos = nanos
                )
            )
        }
    }

    private class FakeTracker : AutoStartTripPort {
        private val mutableState = MutableStateFlow<TripState>(TripState.Idle)
        override val state: StateFlow<TripState> = mutableState

        var autoStartCount = 0
            private set
        var failures = 0
        var beforeSave: (suspend () -> Unit)? = null
        var availableSpeed = false
        override fun setSpeedAvailable(available: Boolean) { availableSpeed = available }
        fun manual() { mutableState.value = TripState.Driving(99L, TripSource.MANUAL, Instant.EPOCH) }
        fun end() { mutableState.value = TripState.Arrived(99L, TripSource.MANUAL, Instant.EPOCH) }

        override suspend fun startAutoTrip(): AutoTripResult<Long> {
            autoStartCount++
            beforeSave?.invoke()
            if (failures-- > 0 || mutableState.value is TripState.Driving) {
                return AutoTripResult.Failure(AutoTripError.Storage(IllegalStateException("save failed/active")))
            }
            mutableState.value = TripState.Driving(
                tripId = 1L,
                source = TripSource.AUTO,
                startedAt = Instant.parse("2026-11-14T00:00:00Z")
            )
            return AutoTripResult.Success(1L)
        }
    }

    private class Harness(val test: TestScope) {
        val speed = FakeSpeedSource()
        val tracker = FakeTracker()
        val coordinator = AutoTripCoordinator(speed, tracker, test.backgroundScope,
            { (test.currentTime + 1_000) * 1_000_000 })
        fun start() { coordinator.start(); test.runCurrent() }
        suspend fun sample(value: Float, timestamp: Long = test.currentTime + 1_000) {
            speed.emit(value, timestamp)
            test.runCurrent()
        }
        suspend fun observe(value: Float, duration: Long) {
            for (offset in 0L..duration step 500) {
                test.advanceTimeBy(500)
                sample(value)
            }
        }
        suspend fun arm() = observe(0f, 5_000)
        suspend fun drive() = observe(3f, 3_000)
    }

    @Test
    fun movingImmediatelyAfterLaunch_doesNotStartTrip() = runTest {
        val speed = FakeSpeedSource()
        val tracker = FakeTracker()
        var nowMs = 0L

        val coordinator = AutoTripCoordinator(
            speedSource = speed,
            tracker = tracker,
            scope = backgroundScope,
            elapsedNanos = { nowMs * 1_000_000L }
        )

        coordinator.start()
        runCurrent()
        speed.awaitSubscriber()

        for (time in 0L..5_000L step 500L) {
            nowMs = time
            speed.emit(speedMps = 3f, timeMs = time)
            runCurrent()
        }

        assertEquals(0, tracker.autoStartCount)
        assertEquals(TripState.Idle, tracker.state.value)
    }

    @Test
    fun stoppedThenMoving_startsAutoTripOnce() = runTest {
        val speed = FakeSpeedSource()
        val tracker = FakeTracker()
        var nowMs = 0L

        val coordinator = AutoTripCoordinator(
            speedSource = speed,
            tracker = tracker,
            scope = backgroundScope,
            elapsedNanos = { nowMs * 1_000_000L }
        )

        coordinator.start()
        runCurrent()
        speed.awaitSubscriber()

        // 정차 상태를 5초 이상 관측
        for (time in 0L..5_500L step 500L) {
            nowMs = time
            speed.emit(speedMps = 0f, timeMs = time)
            runCurrent()
        }

        // 이동 상태를 3초 이상 관측
        for (time in 6_000L..10_000L step 500L) {
            nowMs = time
            speed.emit(speedMps = 3f, timeMs = time)
            runCurrent()
        }

        assertEquals(1, tracker.autoStartCount)
        assertTrue(tracker.state.value is TripState.Driving)
        assertEquals(
            TripSource.AUTO,
            (tracker.state.value as TripState.Driving).source
        )
    }

    @Test fun manualTripPreventsAutoStart() = runTest {
        val h = Harness(this)
        h.tracker.manual()
        h.start()
        h.arm(); h.drive()
        assertEquals(0, h.tracker.autoStartCount)
        assertEquals(TripSource.MANUAL, (h.tracker.state.value as TripState.Driving).source)
    }

    @Test fun endedTripRequiresAnotherStopBeforeDetection() = runTest {
        val h = Harness(this)
        h.start(); h.arm(); h.drive()
        assertEquals(1, h.tracker.autoStartCount)
        h.tracker.end(); runCurrent()
        h.drive()
        assertEquals(1, h.tracker.autoStartCount)
        h.arm(); h.drive()
        assertEquals(2, h.tracker.autoStartCount)
    }

    @Test fun conflatedManualStartAndEndStillRequireAStop() = runTest {
        val h = Harness(this)
        h.start(); h.arm()
        // No scheduler turn between the two changes: collector only sees Arrived.
        h.tracker.manual(); h.tracker.end(); runCurrent()
        h.drive()
        assertEquals(0, h.tracker.autoStartCount)
        h.arm(); h.drive()
        assertEquals(1, h.tracker.autoStartCount)
    }

    @Test fun unavailableDuringStopResetsFiveSecondObservation() = runTest {
        val h = Harness(this)
        h.start(); h.observe(0f, 3_000)
        h.speed.unavailable(); runCurrent()
        assertEquals(false, h.tracker.availableSpeed)
        h.observe(0f, 2_000); h.drive()
        assertEquals(0, h.tracker.autoStartCount)
        h.arm(); h.drive()
        assertEquals(1, h.tracker.autoStartCount)
    }

    @Test fun speedAvailabilityIsClearedOnStopAndRecoveredByFreshSample() = runTest {
        val h = Harness(this)
        h.start(); h.sample(0f)
        assertEquals(true, h.tracker.availableSpeed)
        h.coordinator.stop(); runCurrent()
        assertEquals(false, h.tracker.availableSpeed)
        h.coordinator.start(); runCurrent(); h.sample(0f)
        assertEquals(true, h.tracker.availableSpeed)
    }

    @Test fun restoredAutoTripPreventsAnotherStartAfterCoordinatorRestart() = runTest {
        val h = Harness(this)
        h.start(); h.arm(); h.drive()
        val active = h.tracker.state.value
        h.coordinator.stop(); h.coordinator.start(); runCurrent()
        h.arm(); h.drive()
        assertEquals(active, h.tracker.state.value)
        assertEquals(1, h.tracker.autoStartCount)
    }

    @Test fun silentSampleGapResetsStoppedObservation() = runTest {
        val h = Harness(this)
        h.start(); h.observe(0f, 3_000)
        advanceTimeBy(2_000)
        h.observe(0f, 2_000); h.drive()
        assertEquals(0, h.tracker.autoStartCount)
        h.arm(); h.drive()
        assertEquals(1, h.tracker.autoStartCount)
    }

    @Test fun failedSaveRetriesAfterCooldownAndFreshMovement() = runTest {
        val h = Harness(this)
        h.tracker.failures = 1
        h.start(); h.arm(); h.drive()
        assertEquals(1, h.tracker.autoStartCount)
        assertEquals(TripState.Idle, h.tracker.state.value)
        h.observe(3f, 2_000)
        assertEquals(1, h.tracker.autoStartCount)
        h.observe(3f, 3_500)
        assertEquals(2, h.tracker.autoStartCount)
        assertTrue(h.tracker.state.value is TripState.Driving)
    }

    @Test fun repeatedStartSharesExactlyOneSubscription() = runTest {
        val h = Harness(this)
        repeat(10) { h.coordinator.start() }
        runCurrent()
        assertEquals(1, h.speed.subscriptions)
        h.arm(); h.drive()
        assertEquals(1, h.tracker.autoStartCount)
    }

    @Test fun immediateStopStartWaitsForCleanupAndRequiresStopAgain() = runTest {
        val h = Harness(this)
        h.start(); h.arm()
        val gate = CompletableDeferred<Unit>()
        h.speed.cleanupGate = gate
        h.coordinator.stop(); runCurrent()
        repeat(3) { h.coordinator.start(); h.coordinator.stop() }
        h.coordinator.start(); runCurrent()
        assertEquals(1, h.speed.subscriptions)
        gate.complete(Unit); runCurrent()
        assertEquals(2, h.speed.subscriptions)
        assertEquals(1, h.speed.maxActive)
        h.drive()
        assertEquals(0, h.tracker.autoStartCount)
        h.arm(); h.drive()
        assertEquals(1, h.tracker.autoStartCount)
    }

    @Test fun manualStartWhileAutoIsSavingCannotBeOverwrittenByOldState() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.tracker.beforeSave = { gate.await() }
        h.start(); h.arm(); h.drive()
        assertEquals(1, h.tracker.autoStartCount)
        h.tracker.manual(); runCurrent()
        gate.complete(Unit); runCurrent()
        h.drive()
        assertEquals(1, h.tracker.autoStartCount)
        assertEquals(TripSource.MANUAL, (h.tracker.state.value as TripState.Driving).source)
        h.tracker.end(); runCurrent(); h.drive()
        assertEquals(1, h.tracker.autoStartCount)
    }

    @Test fun staleFutureAndReorderedSamplesCannotBridgeMovementWindow() = runTest {
        val h = Harness(this)
        h.start(); h.arm(); h.observe(3f, 1_000)
        h.sample(3f, currentTime + 1_000 - 500) // Regressing/duplicate timestamp.
        h.sample(3f, currentTime + 1_001) // Future timestamp.
        h.sample(3f, currentTime + 1_000 - 2_000) // Stale timestamp.
        h.observe(3f, 2_000)
        assertEquals(0, h.tracker.autoStartCount)
        h.observe(3f, 1_000)
        assertEquals(1, h.tracker.autoStartCount)
    }

    @Test fun cancellationBeforeSaveCompletionDoesNotPublishOrDuplicate() = runTest {
        val h = Harness(this)
        h.tracker.beforeSave = { awaitCancellation() }
        h.start(); h.arm(); h.drive()
        h.coordinator.stop(); h.coordinator.start(); runCurrent()
        assertEquals(TripState.Idle, h.tracker.state.value)
        h.tracker.beforeSave = null
        h.drive()
        assertEquals(1, h.tracker.autoStartCount)
        h.arm(); h.drive()
        assertEquals(2, h.tracker.autoStartCount)
        assertEquals(1, h.speed.maxActive)
    }

    @Test fun sourceCompletionAndExceptionCloseSubscriptionAndRearmOnRestart() = runTest {
        for (failure in listOf(false, true)) {
            val h = Harness(this)
            h.start(); h.arm(); h.observe(3f, 1_000)
            h.speed.throwOnEnd = failure
            h.speed.end(); runCurrent()
            assertEquals(0, h.speed.active)
            h.coordinator.start(); runCurrent()
            h.drive()
            assertEquals(0, h.tracker.autoStartCount)
            h.arm(); h.drive()
            assertEquals(1, h.tracker.autoStartCount)
            h.coordinator.stop(); runCurrent()
        }
    }

    @Test fun repeatedUnavailableWhileDrivingDoesNotEndOrDuplicateTrip() = runTest {
        val h = Harness(this)
        h.start(); h.arm(); h.drive()
        val active = h.tracker.state.value
        repeat(10) {
            h.speed.unavailable(); runCurrent()
            h.observe(3f, 3_500)
        }
        assertEquals(active, h.tracker.state.value)
        assertEquals(1, h.tracker.autoStartCount)
    }

    @Test fun slowSaveBoundsProducerBacklogAndCancellationKeepsIdle() = runTest {
        val h = Harness(this)
        h.tracker.beforeSave = { awaitCancellation() }
        h.start(); h.arm(); h.drive()
        var sent = 0
        val producer = backgroundScope.launch {
            repeat(1_000) { h.speed.emit(3f, currentTime + 1_000); sent++ }
        }
        runCurrent()
        assertTrue("32-slot queue must backpressure the producer: $sent", sent <= 34)
        assertFalse(producer.isCompleted)
        h.coordinator.stop(); runCurrent()
        assertEquals(TripState.Idle, h.tracker.state.value)
        producer.cancel()
    }

    @Test fun delayedSaveCannotReplayBufferedHistoryAsAnotherTrip() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.tracker.beforeSave = { gate.await() }
        h.start(); h.arm(); h.drive()
        h.tracker.manual(); runCurrent()
        h.tracker.end(); runCurrent()
        h.observe(3f, 10_000)
        gate.complete(Unit); runCurrent()
        // The fake rejects if another trip is active; here the completed save owns one AUTO.
        assertEquals(1, h.tracker.autoStartCount)
        assertTrue(h.tracker.state.value is TripState.Driving)
    }
}
