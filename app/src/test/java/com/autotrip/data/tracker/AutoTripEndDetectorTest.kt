package com.autotrip.data.tracker

import com.autotrip.domain.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AutoTripEndDetectorTest {
    private val active = TripState.Driving(1, TripSource.AUTO, Instant.EPOCH)
    private fun proof(ms: Long) = VehicleEndEvidence(0f, true, true, true, (ms + 1_000) * 1_000_000)
    private fun feed(d: AutoTripEndDetector, ms: Long, v: VehicleEndEvidence = proof(ms), state: TripState = active) =
        d.onEvidence(v, state, (ms + 1_000) * 1_000_000)
    private fun detector() = AutoTripEndDetector(AutoTripEndPolicy(enabled = true))
    @Test fun defaultPolicyNeverEndsEvenWithParkingEvidence() {
        val d = AutoTripEndDetector()
        for (ms in 0L..30_000 step 1_000) assertNull(feed(d, ms))
    }
    @Test fun trafficLightsAndCongestionNeverEndFromZeroSpeedAlone() {
        for (brake in listOf(false, true)) {
            val d = detector()
            for (ms in 0L..60_000 step 1_000) assertNull(feed(d, ms,
                proof(ms).copy(gearPark = false, parkingBrakeOn = brake, ignitionOff = false)))
        }
    }
    @Test fun missingIgnitionNeverEndsEvenWhileParked() {
        val d = detector()
        for (ms in 0L..30_000 step 1_000) assertNull(feed(d, ms, proof(ms).copy(ignitionOff = null)))
    }
    @Test fun confirmedParkingRequestsOneEndOnly() {
        val d = detector()
        for (ms in 0L..9_000 step 1_000) assertNull(feed(d, ms))
        assertEquals(1L, feed(d, 10_000))
        d.onPersisted(true, proof(10_000).timestampNanos)
        for (ms in 11_000L..30_000 step 1_000) assertNull(feed(d, ms))
    }
    @Test fun manualTripsNeverEndAutomatically() {
        val d = detector()
        for (ms in 0L..30_000 step 1_000) assertNull(feed(d, ms, state = active.copy(source = TripSource.MANUAL)))
    }
    @Test fun disconnectedAndStaleRegressingDataResetConfirmation() {
        val d = detector()
        for (ms in 0L..9_000 step 1_000) feed(d, ms)
        d.onUnavailable()
        assertNull(feed(d, 10_000))
        assertNull(feed(d, 11_000, proof(5_000)))
        assertNull(feed(d, 12_000, proof(9_000)))
        for (ms in 13_000L..22_000 step 1_000) assertNull(feed(d, ms))
        assertEquals(1L, feed(d, 23_000))
    }
    @Test fun failureRetriesOnlyAfterCooldownAndNewConfirmation() {
        val d = detector()
        for (ms in 0L..10_000 step 1_000) feed(d, ms)
        d.onPersisted(false, proof(10_000).timestampNanos)
        for (ms in 11_000L..22_000 step 1_000) assertNull(feed(d, ms))
        assertEquals(1L, feed(d, 23_000))
    }
    @Test fun restartOrDifferentTripRequiresNewEvidenceWindow() {
        val d = detector()
        for (ms in 0L..9_000 step 1_000) feed(d, ms)
        d.reset()
        assertNull(feed(d, 10_000))
        assertNull(feed(d, 11_000, state = active.copy(tripId = 2)))
        for (ms in 12_000L..20_000 step 1_000) assertNull(feed(d, ms, state = active.copy(tripId = 2)))
        assertEquals(2L, feed(d, 21_000, state = active.copy(tripId = 2)))
    }
}
