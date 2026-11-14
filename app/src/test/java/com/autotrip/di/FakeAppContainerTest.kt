package com.autotrip.di

import com.autotrip.domain.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeAppContainerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC)


    @Test
    fun dependenciesShareTheSameTrips() = runBlocking {
        val app = FakeAppContainer(clock)

        val period = TaxPeriod(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31)
        )

        // 신규 운행 시작 전 집계값
        val before = app.reportRepository
            .observeSummary(period)
            .first()

        val started = app.tripTracker.startManualTrip()
        assertTrue(started is AutoTripResult.Success)

        val tripId = (started as AutoTripResult.Success).value

        // 샘플 10건 다음 ID
        assertEquals(11L, tripId)

        app.demoTracker.simulateDrive(1_500L)

        assertTrue(
            app.tripTracker.endManualTrip() is AutoTripResult.Success
        )

        val trip = app.tripRepository.observeTrip(tripId).first()

        assertNotNull(trip)
        assertEquals(1_500L, trip?.drivenMeters)

        // 기존 샘플 집계 + 신규 운행거리
        val after = app.reportRepository
            .observeSummary(period)
            .first()

        assertEquals(
            before.totalMeters + 1_500L,
            after.totalMeters
        )
    }


    @Test fun demoProfileAndClientsAreAvailable() = runBlocking {
        val app = FakeAppContainer(clock)
        assertNotNull(app.profileRepository.observeBusinessProfile().first())
        assertNotNull(app.profileRepository.observeVehicle().first())
        assertEquals(2, app.profileRepository.observeUsers().first().size)
        assertEquals(2, app.profileRepository.observeClients().first().size)
        assertEquals(1L, app.profileRepository.observeDefaultUserId().first())
    }


    @Test
    fun demoTripsContainExpectedSamples() = runBlocking {
        val app = FakeAppContainer(clock)

        val period = TaxPeriod(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31)
        )

        val trips = app.tripRepository.observeTrips(period).first()

        // 샘플 운행 10건
        assertEquals(10, trips.size)

        // 모든 샘플 운행은 종료된 상태
        assertTrue(trips.all { it.endedAt != null })

        // 목적 미확정 1건
        assertEquals(
            1,
            trips.count {
                it.purposeStatus == PurposeStatus.PENDING
            }
        )

        // 출발 계기판 실패 1건
        assertEquals(
            1,
            trips.count {
                it.odometerStart.status ==
                        OdometerCaptureStatus.FAILED
            }
        )

        // 도착 계기판 실패 1건
        assertEquals(
            1,
            trips.count {
                it.odometerEnd.status ==
                        OdometerCaptureStatus.FAILED
            }
        )

        // 수동 운행 1건
        assertEquals(
            1,
            trips.count { it.source == TripSource.MANUAL }
        )

        // 샘플 이후 신규 운행 가능
        assertTrue(app.tripTracker.state.value is TripState.Idle)
    }


    @Test
    fun demoTripsHaveEditHistory() = runBlocking {
        val app = FakeAppContainer(clock)

        val expected = mapOf(
            5L to EditableField.PURPOSE,
            6L to EditableField.USER,
            10L to EditableField.MEMO,
        )

        expected.forEach { (tripId, field) ->
            val history = app.tripRepository
                .observeEditHistory(tripId)
                .first()

            assertEquals(1, history.size)
            assertEquals(field, history.single().field)
        }

        val trip5 = app.tripRepository.observeTrip(5L).first()
        val trip6 = app.tripRepository.observeTrip(6L).first()
        val trip10 = app.tripRepository.observeTrip(10L).first()

        assertEquals(TripPurpose.CLIENT_VISIT, trip5?.purpose)
        assertEquals(2L, trip6?.userId)
        assertEquals("정기 업무 교육 참석", trip10?.memo)
    }

}
