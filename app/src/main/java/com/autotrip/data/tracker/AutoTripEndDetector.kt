package com.autotrip.data.tracker

import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.TripState

/** Disabled until a real, verified producer for all the evidence below exists. */
internal data class AutoTripEndPolicy(
    val enabled: Boolean = false,
    val stationaryMetersPerSecond: Float = 1f / 3.6f,
    val confirmationMillis: Long = 10_000,
    val maxAgeMillis: Long = 2_000,
    val maxGapMillis: Long = 1_500,
    val retryCooldownMillis: Long = 3_000,
) {
    init {
        require(stationaryMetersPerSecond.isFinite() && stationaryMetersPerSecond >= 0)
        require(confirmationMillis > 0 && maxAgeMillis > 0 && maxGapMillis > 0 && retryCooldownMillis >= 0)
        require(listOf(confirmationMillis, maxAgeMillis, maxGapMillis, retryCooldownMillis).all { it <= Long.MAX_VALUE / 1_000_000 })
    }
}

/** Producer must verify ALL fields in one coherent fresh observation. Not a brake-pedal signal. */
internal data class VehicleEndEvidence(
    val speedMetersPerSecond: Float,
    val gearPark: Boolean,
    val parkingBrakeOn: Boolean,
    val ignitionOff: Boolean?,
    val timestampNanos: Long,
)

internal class AutoTripEndDetector(private val policy: AutoTripEndPolicy = AutoTripEndPolicy()) {
    private var tripId: Long? = null
    private var lastTimestamp = 0L
    private var candidate: Long? = null
    private var pending = false
    private var retryAt = 0L

    fun reset() { tripId = null; lastTimestamp = 0; candidate = null; pending = false; retryAt = 0 }
    fun onUnavailable() { candidate = null }
    fun onPersisted(succeeded: Boolean, nowNanos: Long) {
        if (!pending) return
        candidate = null
        if (!succeeded) {
            pending = false
            val cooldown = policy.retryCooldownMillis * 1_000_000
            retryAt = if (nowNanos > Long.MAX_VALUE - cooldown) Long.MAX_VALUE else nowNanos + cooldown
        }
    }
    fun onEvidence(value: VehicleEndEvidence, state: TripState, nowNanos: Long): Long? {
        if (!policy.enabled || state !is TripState.Driving ||
            state.source == TripSource.MANUAL) {
            reset(); return null
        }
        if (tripId != state.tripId) { reset(); tripId = state.tripId }
        val ts = value.timestampNanos
        if (ts <= lastTimestamp || ts <= 0 || ts > nowNanos ||
            nowNanos - ts >= policy.maxAgeMillis * 1_000_000 ||
            !value.speedMetersPerSecond.isFinite() || value.speedMetersPerSecond < 0) {
            onUnavailable(); return null
        }
        if (lastTimestamp > 0 && ts - lastTimestamp > policy.maxGapMillis * 1_000_000) onUnavailable()
        lastTimestamp = ts
        if (pending || ts < retryAt) return null
        if (value.speedMetersPerSecond > policy.stationaryMetersPerSecond || !value.gearPark ||
            !value.parkingBrakeOn || value.ignitionOff != true) {
            onUnavailable(); return null
        }
        if (candidate == null) candidate = ts
        if (ts - candidate!! < policy.confirmationMillis * 1_000_000) return null
        pending = true
        return state.tripId
    }
}
