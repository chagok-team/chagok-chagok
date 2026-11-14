package com.autotrip.fake

import com.autotrip.domain.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeReportRepositoryTest {
    private val instant = Instant.parse("2026-11-13T10:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private val period = TaxPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    @Test fun exportRequiresProfileAndVehicle() = runBlocking {
        val trips = FakeTripRepository(clock)
        val profiles = FakeProfileRepository()
        val reports = FakeReportRepository(trips, profiles, clock)
        assertEquals(AutoTripError.ProfileIncomplete, (reports.buildLogbookExport(period) as AutoTripResult.Failure).error)
    }

    @Test fun completedTripContributesToSummaryAndExport() = runBlocking {
        val profiles = FakeProfileRepository()
        profiles.saveBusinessProfile(BusinessProfile(BusinessType.CORPORATION, "테스트 회사", "123-45-67890"))
        profiles.saveVehicle("테스트 차량", "12가3456")
        val trips = FakeTripRepository(clock)
        val start = (trips.beginTrip(1L, TripSource.MANUAL, instant.minusSeconds(3600),
            OdometerReading(100_000L, OdometerCaptureStatus.CAPTURED, instant.minusSeconds(3600))) as AutoTripResult.Success).value
        trips.finishTrip(start.id, instant, OdometerReading(102_000L, OdometerCaptureStatus.CAPTURED, instant))
        trips.confirmPurpose(start.id, TripPurpose.CLIENT_VISIT, InputMethod.BUTTON, null, null)
        val reports = FakeReportRepository(trips, profiles, clock)
        val summary = reports.observeSummary(period).first()
        assertEquals(2000L, summary.totalMeters)
        assertEquals(2000L, summary.businessMeters)
        assertEquals(1.0, summary.businessUseRatio ?: -1.0, 0.0001)
        val export = (reports.buildLogbookExport(period) as AutoTripResult.Success).value
        assertEquals(1, export.rows.size)
        assertEquals(2000L, export.rows.single().generalBusinessMeters)
        assertTrue(RowFlag.MANUAL_TRIP in export.rows.single().flags)
        assertEquals(
            "거래처 방문 [수동기록]",
            export.rows.single().remark
        )
    }

    @Test fun pendingAndMissingOdometerAreFlagged() = runBlocking {
        val profiles = FakeProfileRepository()
        profiles.saveBusinessProfile(BusinessProfile(BusinessType.CORPORATION, "테스트", "123-45-67890"))
        profiles.saveVehicle("테스트", "12가3456")
        val trips = FakeTripRepository(clock)
        val start = (trips.beginTrip(1L, TripSource.MANUAL, instant.minusSeconds(60),
            OdometerReading(null, OdometerCaptureStatus.FAILED, null)) as AutoTripResult.Success).value
        trips.finishTrip(start.id, instant, OdometerReading(null, OdometerCaptureStatus.FAILED, null))
        val reports = FakeReportRepository(trips, profiles, clock)
        val summary = reports.observeSummary(period).first()
        assertEquals(1, summary.incompleteTripCount)
        assertEquals(1, summary.pendingPurposeTripCount)
        val row = (reports.buildLogbookExport(period) as AutoTripResult.Success).value.rows.single()
        assertTrue(RowFlag.ODOMETER_MISSING in row.flags)
        assertTrue(RowFlag.PURPOSE_PENDING in row.flags)
        assertEquals(
            "[수동기록] [계기판 수집 실패] [목적 미입력]",
            row.remark
        )
    }

    @Test
    fun unsupportedAndPartialOdometerAreCountedAsIncomplete() = runBlocking {
        val profiles = FakeProfileRepository()
        profiles.saveBusinessProfile(
            BusinessProfile(
                BusinessType.CORPORATION,
                "테스트 회사",
                "123-45-67890"
            )
        )
        profiles.saveVehicle("테스트 차량", "12가3456")

        val trips = FakeTripRepository(clock)

        val captured = { meters: Long ->
            OdometerReading(
                meters,
                OdometerCaptureStatus.CAPTURED,
                instant
            )
        }

        val unsupported = OdometerReading(
            null,
            OdometerCaptureStatus.UNSUPPORTED,
            null
        )

        val failed = OdometerReading(
            null,
            OdometerCaptureStatus.FAILED,
            null
        )

        // 운행 1: 정상 (2,000m)
        val first = (trips.beginTrip(
            1L, TripSource.AUTO,
            instant.minusSeconds(400),
            captured(100_000L)
        ) as AutoTripResult.Success).value

        trips.finishTrip(first.id, instant.minusSeconds(350), captured(102_000L))

        // 운행 2: 출발 UNSUPPORTED
        val second = (trips.beginTrip(
            1L, TripSource.AUTO,
            instant.minusSeconds(300),
            unsupported
        ) as AutoTripResult.Success).value

        trips.finishTrip(second.id, instant.minusSeconds(250), captured(105_000L))

        // 운행 3: 도착 FAILED
        val third = (trips.beginTrip(
            1L, TripSource.AUTO,
            instant.minusSeconds(200),
            captured(105_000L)
        ) as AutoTripResult.Success).value

        trips.finishTrip(third.id, instant.minusSeconds(150), failed)

        // 운행 4: 출발·도착 모두 UNSUPPORTED
        val fourth = (trips.beginTrip(
            1L, TripSource.AUTO,
            instant.minusSeconds(100),
            unsupported
        ) as AutoTripResult.Success).value

        trips.finishTrip(fourth.id, instant, unsupported)

        val reports = FakeReportRepository(trips, profiles, clock)
        val summary = reports.observeSummary(period).first()

        assertEquals(2_000L, summary.totalMeters)
        assertEquals(3, summary.incompleteTripCount)
        assertEquals(4, summary.pendingPurposeTripCount)

        val export = (
                reports.buildLogbookExport(period) as AutoTripResult.Success
                ).value

        assertEquals(4, export.rows.size)

        val rows = export.rows.associateBy { it.tripId }

        assertFalse(RowFlag.ODOMETER_MISSING in rows.getValue(first.id).flags)

        for (id in listOf(second.id, third.id, fourth.id)) {
            val row = rows.getValue(id)
            assertTrue(RowFlag.ODOMETER_MISSING in row.flags)
            assertNull(row.drivenMeters)
        }

        assertEquals(3, export.summary.incompleteTripCount)
        assertEquals(2_000L, export.summary.totalMeters)
    }
    @Test
    fun editedPurposeAndMemoAppearInRemark() = runBlocking {
        val profiles = FakeProfileRepository()
        profiles.saveBusinessProfile(
            BusinessProfile(
                BusinessType.CORPORATION,
                "테스트 회사",
                "123-45-67890"
            )
        )
        profiles.saveVehicle("테스트 차량", "12가3456")

        val trips = FakeTripRepository(clock)

        val start = (
                trips.beginTrip(
                    1L,
                    TripSource.MANUAL,
                    instant.minusSeconds(3600),
                    OdometerReading(
                        100_000L,
                        OdometerCaptureStatus.CAPTURED,
                        instant.minusSeconds(3600)
                    ),
                    destinationName = "판교 데모 거래처 본관"
                ) as AutoTripResult.Success
                ).value

        trips.finishTrip(
            start.id,
            instant,
            OdometerReading(
                105_000L,
                OdometerCaptureStatus.CAPTURED,
                instant
            )
        )

        trips.confirmPurpose(
            start.id,
            TripPurpose.CLIENT_VISIT,
            InputMethod.BUTTON,
            null,
            null
        )

        // 최초 확정 후 운행 목적 수정
        assertTrue(
            trips.updatePurpose(
                start.id,
                TripPurpose.MEETING
            ) is AutoTripResult.Success
        )

        // 메모 수정
        assertTrue(
            trips.updateMemo(
                start.id,
                "거래처 담당자 미팅"
            ) is AutoTripResult.Success
        )

        assertEquals(
            2,
            trips.observeEditHistory(start.id).first().size
        )

        val reports = FakeReportRepository(trips, profiles, clock)
        val export = (
                reports.buildLogbookExport(period) as AutoTripResult.Success
                ).value

        val row = export.rows.single()

        assertEquals(
            "회의 · 판교 데모 거래처 본관 · 거래처 담당자 미팅 [수동기록] [수정됨]",
            row.remark
        )

        assertEquals(TripPurpose.MEETING, row.purpose)
        assertTrue(RowFlag.EDITED in row.flags)
        assertTrue(RowFlag.MANUAL_TRIP in row.flags)
    }


}
