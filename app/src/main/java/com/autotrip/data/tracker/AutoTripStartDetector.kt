package com.autotrip.data.tracker

import com.autotrip.data.vehicle.VehicleSpeedEvent

/** All durations use the elapsedRealtimeNanos time base of VehicleSpeedEvent.Sample. */
data class AutoTripStartPolicy(
    val movementThresholdMetersPerSecond: Float = 5f / 3.6f,
    val movementDurationMillis: Long = 3_000,
    val maxSampleAgeMillis: Long = 2_000,
    val maxObservationGapMillis: Long = 1_500,
    val rearmSpeedMetersPerSecond: Float = 1f / 3.6f,
    val rearmStoppedDurationMillis: Long = 5_000,
    val retryCooldownMillis: Long = 3_000,
) {
    init {
        require(movementThresholdMetersPerSecond.isFinite() && movementThresholdMetersPerSecond > 0f)
        require(rearmSpeedMetersPerSecond.isFinite() && rearmSpeedMetersPerSecond >= 0f)
        require(rearmSpeedMetersPerSecond < movementThresholdMetersPerSecond)
        require(movementDurationMillis > 0 && maxSampleAgeMillis > 0)
        require(maxObservationGapMillis > 0 && rearmStoppedDurationMillis > 0)
        require(retryCooldownMillis >= 0)
        for (duration in listOf(movementDurationMillis, maxSampleAgeMillis,
            maxObservationGapMillis, rearmStoppedDurationMillis, retryCooldownMillis)) {
            require(duration <= Long.MAX_VALUE / 1_000_000)
        }
    }
}

enum class AutoTripStartState { IDLE, MOVEMENT_CANDIDATE, START_PENDING, ACTIVE, REARM_WAIT }
enum class ActiveTripOrigin { AUTO, MANUAL }

sealed interface AutoTripStartAction {
    data class AutoStartRequested(val requestId: Long) : AutoTripStartAction
}

/**
 * Pure Kotlin, single-thread-confined state machine. Call from one serialized owner.
 * No SDK subscription, persistence, coroutines, or wall-clock access is performed here.
 */
class AutoTripStartDetector(val policy: AutoTripStartPolicy = AutoTripStartPolicy()) {
    var state: AutoTripStartState = AutoTripStartState.IDLE
        private set
    var activeOrigin: ActiveTripOrigin? = null
        private set

    private var lastTimestampNanos: Long? = null
    private var candidateStartNanos: Long? = null
    private var stoppedStartNanos: Long? = null
    private var pendingRequestId: Long? = null
    private var nextRequestId = 1L
    private var retryNotBeforeNanos = 0L

    /** Called on initialization after Room active-trip restoration. */
    fun restoreActiveTrip(origin: ActiveTripOrigin?) {
        activeOrigin = origin
        state = if (origin == null) AutoTripStartState.IDLE else AutoTripStartState.ACTIVE
        clearObservations()
        pendingRequestId = null
        retryNotBeforeNanos = 0L
    }

    /**
     * 앱 시작 시 활성 운행이 없더라도 즉시 자동 감지를 허용하지 않는다.
     *
     * 차량이 정차 상태임을 확인한 후에만
     * 새로운 자동 운행 시작을 허용한다.
     */
    fun requireStoppedBeforeDetection() {
        activeOrigin = null
        state = AutoTripStartState.REARM_WAIT
        clearObservations()
        pendingRequestId = null
        retryNotBeforeNanos = 0L
    }


    /** Manual start always supersedes a movement candidate or pending auto-start. */
    fun onManualTripStarted() {
        activeOrigin = ActiveTripOrigin.MANUAL
        state = AutoTripStartState.ACTIVE
        clearObservations()
        pendingRequestId = null
    }

    /** Call after an active trip ends, regardless of whether it was manual or automatic. */
    fun onTripEnded() {
        activeOrigin = null
        state = AutoTripStartState.REARM_WAIT
        clearObservations()
        pendingRequestId = null
        retryNotBeforeNanos = 0L
    }

    /** Match acknowledgments to their request ID to ignore delayed/stale callbacks. */
    fun onAutoStartPersisted(requestId: Long, succeeded: Boolean, nowNanos: Long) {
        if (state != AutoTripStartState.START_PENDING || pendingRequestId != requestId) return
        pendingRequestId = null
        clearObservations()
        if (succeeded) {
            activeOrigin = ActiveTripOrigin.AUTO
            state = AutoTripStartState.ACTIVE
        } else {
            activeOrigin = null
            state = AutoTripStartState.IDLE
            retryNotBeforeNanos = saturatingAdd(nowNanos.coerceAtLeast(0), policy.retryCooldownMillis * 1_000_000)
        }
    }

    /** Unavailable invalidates continuity; it is never interpreted as a zero-speed sample. */
    fun onUnavailable(@Suppress("UNUSED_PARAMETER") event: VehicleSpeedEvent.Unavailable) {
        candidateStartNanos = null
        stoppedStartNanos = null
        if (state == AutoTripStartState.MOVEMENT_CANDIDATE) state = AutoTripStartState.IDLE
    }

    /** nowNanos must be read from the same monotonic clock as Sample.timestampNanos. */
    fun onSample(sample: VehicleSpeedEvent.Sample, nowNanos: Long): AutoTripStartAction? {
        val timestamp = sample.timestampNanos
        val last = lastTimestampNanos
        // Reject bad data without allowing a duplicate/regressing sample to advance continuity.
        if (timestamp <= 0 || nowNanos < timestamp || sample.receivedAtNanos < timestamp ||
            sample.receivedAtNanos > nowNanos || !sample.metersPerSecond.isFinite() ||
            sample.metersPerSecond < 0f ||
            nowNanos - timestamp >= policy.maxSampleAgeMillis * 1_000_000 ||
            (last != null && timestamp <= last)) {
            // Rejected data cannot bridge two otherwise valid observation windows.
            onUnavailable(VehicleSpeedEvent.Unavailable(com.autotrip.data.vehicle.SpeedUnavailableReason.INVALID_VALUE))
            return null
        }

        val hasGap = last != null && timestamp - last > policy.maxObservationGapMillis * 1_000_000
        lastTimestampNanos = timestamp
        if (hasGap) {
            candidateStartNanos = null
            stoppedStartNanos = null
            if (state == AutoTripStartState.MOVEMENT_CANDIDATE) state = AutoTripStartState.IDLE
        }

        when (state) {
            AutoTripStartState.ACTIVE, AutoTripStartState.START_PENDING -> return null
            AutoTripStartState.REARM_WAIT -> {
                if (sample.metersPerSecond <= policy.rearmSpeedMetersPerSecond) {
                    if (stoppedStartNanos == null) stoppedStartNanos = timestamp
                    if (timestamp - stoppedStartNanos!! >= policy.rearmStoppedDurationMillis * 1_000_000) {
                        state = AutoTripStartState.IDLE
                        stoppedStartNanos = null
                    }
                } else stoppedStartNanos = null
                return null
            }
            AutoTripStartState.IDLE, AutoTripStartState.MOVEMENT_CANDIDATE -> Unit
        }

        if (sample.metersPerSecond < policy.movementThresholdMetersPerSecond) {
            candidateStartNanos = null
            state = AutoTripStartState.IDLE
            return null
        }
        if (timestamp < retryNotBeforeNanos) {
            candidateStartNanos = null
            state = AutoTripStartState.IDLE
            return null
        }
        if (candidateStartNanos == null) {
            candidateStartNanos = timestamp
            state = AutoTripStartState.MOVEMENT_CANDIDATE
            return null
        }
        if (timestamp - candidateStartNanos!! < policy.movementDurationMillis * 1_000_000) return null

        val requestId = nextRequestId++
        pendingRequestId = requestId
        candidateStartNanos = null
        state = AutoTripStartState.START_PENDING
        return AutoTripStartAction.AutoStartRequested(requestId)
    }

    private fun clearObservations() {
        lastTimestampNanos = null
        candidateStartNanos = null
        stoppedStartNanos = null
    }

    private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}
