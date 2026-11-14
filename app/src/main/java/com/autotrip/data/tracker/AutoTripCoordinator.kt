
package com.autotrip.data.tracker

import com.autotrip.data.vehicle.VehicleSpeedEvent
import com.autotrip.data.vehicle.VehicleSpeedSource
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.TripState
import com.autotrip.data.vehicle.SpeedUnavailableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.autotrip.domain.model.AutoTripError


/**
 * 코디네이터가 필요로 하는 최소 운행 추적 기능.
 * 기존 TripTracker 계약은 변경하지 않는다.
 */
internal interface AutoStartTripPort {
    val state: StateFlow<TripState>

    suspend fun startAutoTrip(): AutoTripResult<Long>
    suspend fun finishAutoTrip(expectedTripId: Long): AutoTripResult<Long> =
        AutoTripResult.Failure(AutoTripError.InvalidState("Automatic ending unavailable"))
    fun setSpeedAvailable(available: Boolean) = Unit
}
/**
 * 차량 속도와 운행 상태를 하나의 이벤트 처리 코루틴에서 직렬화한다.
 *
 * start()는 Room 복원 완료 이후 호출해야 한다.
 * stop()은 구독을 취소하며, 재시작 시 정차 확인을 다시 요구한다.
 */
internal class AutoTripCoordinator(
    private val speedSource: VehicleSpeedSource,
    private val tracker: AutoStartTripPort,
    private val scope: CoroutineScope,
    private val elapsedNanos: () -> Long,
    private val detector: AutoTripStartDetector = AutoTripStartDetector(),
    private val endEvidence: Flow<VehicleEndEvidence> = emptyFlow(),
    private val endPolicy: AutoTripEndPolicy = AutoTripEndPolicy(),
) {
    constructor(
        speedSource: VehicleSpeedSource,
        tracker: RoomTripTracker,
        scope: CoroutineScope,
        elapsedNanos: () -> Long,
        detector: AutoTripStartDetector = AutoTripStartDetector(),
        endEvidence: Flow<VehicleEndEvidence> = emptyFlow(),
        endPolicy: AutoTripEndPolicy = AutoTripEndPolicy(),
    ) : this(
        speedSource = speedSource,
        tracker = object : AutoStartTripPort {
            override val state = tracker.state

            override suspend fun startAutoTrip(): AutoTripResult<Long> =
                tracker.startAutoTrip()
            override suspend fun finishAutoTrip(expectedTripId: Long) = tracker.finishAutoTrip(expectedTripId)
            override fun setSpeedAvailable(available: Boolean) =
                tracker.setAutoDetectionAvailable(available)
        },
        scope = scope,
        elapsedNanos = elapsedNanos,
        detector = detector,
        endEvidence = endEvidence,
        endPolicy = endPolicy,
    )
    private sealed interface Event {
        data class Speed(val value: VehicleSpeedEvent) : Event
        object StateChanged : Event
        object SourceEnded : Event
        data class EndEvidence(val value: VehicleEndEvidence) : Event
        object EndUnavailable : Event
    }

    private var requested = false
    private var collectionJob: Job? = null
    // StateFlow can conflate a very quick manual Driving -> Arrived transition.
    private var lastArrivedTripId: Long? = null
    private val endDetector = AutoTripEndDetector(endPolicy)

    @Synchronized
    fun start() {
        if (requested && collectionJob?.isCompleted == false) return
        requested = true
        val previous = collectionJob
        collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Wait for both the old collectors and any in-flight persistence to finish.
            // No old generation may touch the detector after this point.
            withContext(NonCancellable) { previous?.join() }
            currentCoroutineContext().ensureActive()
            runSession()
        }
    }

    @Synchronized
    fun stop() {
        requested = false
        collectionJob?.cancel()
    }

    private suspend fun runSession() = coroutineScope {
        lastArrivedTripId = null
        endDetector.reset()
        detector.requireStoppedBeforeDetection()
        tracker.setSpeedAvailable(false)
        synchronizeState(tracker.state.value)
        // Bounded backpressure; every sample is revalidated at processing time.
        val events = Channel<Event>(32)
        val stateJob = launch {
            tracker.state.collect { events.send(Event.StateChanged) }
        }
        val speedJob = launch {
            try {
                speedSource.events.collect { events.send(Event.Speed(it)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                events.send(Event.Speed(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.SDK_ERROR)))
            }
            events.send(Event.SourceEnded)
        }
        val endJob = if (endPolicy.enabled) launch {
            try {
                endEvidence.collect { events.send(Event.EndEvidence(it)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Loss of evidence never means arrival. */ }
            events.send(Event.EndUnavailable)
        } else null
        try {
            for (event in events) {
                currentCoroutineContext().ensureActive()
                // Notifications never carry a past state capable of reversing newer state.
                synchronizeState(tracker.state.value)
                when (event) {
                    Event.EndUnavailable -> endDetector.onUnavailable()
                    is Event.EndEvidence -> {
                        val id = endDetector.onEvidence(event.value, tracker.state.value, elapsedNanos())
                        if (id != null) {
                            val succeeded = try {
                                tracker.finishAutoTrip(id) is AutoTripResult.Success
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { false }
                            currentCoroutineContext().ensureActive()
                            endDetector.onPersisted(succeeded, elapsedNanos())
                            synchronizeState(tracker.state.value)
                        }
                    }
                    Event.StateChanged -> Unit
                    Event.SourceEnded -> {
                        detector.onUnavailable(VehicleSpeedEvent.Unavailable(SpeedUnavailableReason.DISCONNECTED))
                        break
                    }
                    is Event.Speed -> when (val speed = event.value) {
                        is VehicleSpeedEvent.Unavailable -> {
                            endDetector.onUnavailable()
                            tracker.setSpeedAvailable(false)
                            detector.onUnavailable(speed)
                        }
                        is VehicleSpeedEvent.Sample -> {
                            val now = elapsedNanos()
                            tracker.setSpeedAvailable(
                                speed.metersPerSecond.isFinite() && speed.metersPerSecond >= 0f &&
                                    speed.timestampNanos > 0 && speed.timestampNanos <= speed.receivedAtNanos &&
                                    speed.receivedAtNanos <= now &&
                                    now - speed.timestampNanos < detector.policy.maxSampleAgeMillis * 1_000_000
                            )
                            val action = detector.onSample(speed, now)
                            if (action is AutoTripStartAction.AutoStartRequested) {
                                val succeeded = try {
                                    tracker.startAutoTrip() is AutoTripResult.Success
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                                currentCoroutineContext().ensureActive()
                                detector.onAutoStartPersisted(action.requestId, succeeded, elapsedNanos())
                                synchronizeState(tracker.state.value)
                            }
                        }
                    }
                }
            }
        } finally {
            tracker.setSpeedAvailable(false)
            stateJob.cancel()
            speedJob.cancel()
            endJob?.cancel()
            events.cancel()
        }
    }

    private fun synchronizeState(state: TripState) {
        when (state) {
            is TripState.Driving -> {
                val origin = when (state.source) {
                    TripSource.AUTO -> ActiveTripOrigin.AUTO
                    TripSource.MANUAL -> ActiveTripOrigin.MANUAL
                }

                if (
                    detector.state != AutoTripStartState.ACTIVE ||
                    detector.activeOrigin != origin
                ) {
                    detector.restoreActiveTrip(origin)
                }
            }

            is TripState.Arrived -> {
                if (lastArrivedTripId != state.tripId || detector.state == AutoTripStartState.ACTIVE) {
                    lastArrivedTripId = state.tripId
                    detector.onTripEnded()
                }
            }
            TripState.Idle -> {
                if (detector.state == AutoTripStartState.ACTIVE) {
                    detector.onTripEnded()
                }
            }
        }
    }
}
