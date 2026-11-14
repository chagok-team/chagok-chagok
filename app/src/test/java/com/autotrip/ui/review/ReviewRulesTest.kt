package com.autotrip.ui.review

import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.OdometerCaptureStatus
import com.autotrip.domain.model.OdometerReading
import com.autotrip.domain.model.PurposeStatus
import com.autotrip.domain.model.Trip
import com.autotrip.domain.model.TripPlace
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.model.TripSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ReviewRulesTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val base = Instant.parse("2026-11-02T00:00:00Z")

    private fun reading(m: Long?) =
        if (m == null) OdometerReading(null, OdometerCaptureStatus.FAILED, null) else OdometerReading(m, OdometerCaptureStatus.CAPTURED, null)

    private fun trip(
        id: Long,
        day: Long,
        start: Long?,
        end: Long?,
        purpose: TripPurpose? = TripPurpose.MEETING,
        dest: String? = "본사",
        inputAfter: Duration? = Duration.ofMinutes(1),
        ended: Boolean = true,
    ): Trip {
        val startedAt = base.plus(Duration.ofDays(day))
        val endedAt = startedAt.plusSeconds(1800).takeIf { ended }
        return Trip(
            id = id, vehicleId = 1, source = TripSource.AUTO,
            startedAt = startedAt, endedAt = endedAt,
            odometerStart = reading(start), odometerEnd = reading(end),
            startPlace = null, endPlace = dest?.let { TripPlace(null, it) }, destinationName = dest,
            userId = null, purpose = purpose,
            purposeStatus = if (purpose == null) PurposeStatus.PENDING else PurposeStatus.CONFIRMED,
            purposeInputMethod = purpose?.let { InputMethod.VOICE },
            purposeInputTime = if (purpose != null && endedAt != null) inputAfter?.let { endedAt.plus(it) } else null,
            voiceRawText = null, memo = null,
        )
    }

    private fun reasonsOf(trips: List<Trip>) =
        ReviewRules.review(trips, emptyList(), zone).associate { it.row.tripId to it.reasons }

    @Test
    fun `문제없는 운행은 나오지 않는다`() {
        val trips = listOf(trip(1, 0, 1_000_000, 1_010_000), trip(2, 1, 1_010_000, 1_020_000))
        assertTrue(ReviewRules.review(trips, emptyList(), zone).isEmpty())
    }

    @Test
    fun `목적 미입력`() {
        assertEquals(mapOf(1L to listOf(ReviewReason.PURPOSE_PENDING)), reasonsOf(listOf(trip(1, 0, 1_000_000, 1_010_000, purpose = null))))
    }

    @Test
    fun `업무 목적인데 행선지가 없으면 표시하고, 사적 사용은 표시하지 않는다`() {
        val trips = listOf(
            trip(1, 0, 1_000_000, 1_010_000, purpose = TripPurpose.CLIENT_VISIT, dest = null),
            trip(2, 1, 1_010_000, 1_020_000, purpose = TripPurpose.PERSONAL, dest = null),
        )
        assertEquals(mapOf(1L to listOf(ReviewReason.MISSING_DESTINATION)), reasonsOf(trips))
    }

    @Test
    fun `앞 운행 도착 거리와 다음 출발 거리가 0_1km보다 차이 나면 다음 운행에 표시한다`() {
        val trips = listOf(
            trip(1, 0, 1_000_000, 1_010_000),
            trip(2, 1, 1_010_050, 1_020_000),   // 50m 차이: 허용
            trip(3, 2, 1_031_100, 1_040_000),   // 11.1km 차이: 표시
            trip(4, 3, null, 1_050_000),        // 출발값 실패: 비교하지 않음
        )
        val items = ReviewRules.review(trips, emptyList(), zone)
        assertEquals(listOf(3L), items.map { it.row.tripId })
        assertEquals(listOf(ReviewReason.ODOMETER_GAP), items.single().reasons)
        assertEquals(listOf("앞 운행 도착 1,020.0 km → 이번 출발 1,031.1 km"), items.single().details)
    }

    @Test
    fun `운행이 끝나고 7일이 지나 목적을 입력하면 표시한다`() {
        val trips = listOf(
            trip(1, 0, 1_000_000, 1_010_000, inputAfter = Duration.ofDays(7)),             // 딱 7일: 표시 안 함
            trip(2, 1, 1_010_000, 1_020_000, inputAfter = Duration.ofDays(9)),
        )
        val items = ReviewRules.review(trips, emptyList(), zone)
        assertEquals(listOf(2L), items.map { it.row.tripId })
        assertEquals(listOf("운행 종료 9일 뒤 입력"), items.single().details)
    }

    @Test
    fun `사유가 여러 개면 함께 보여 주고, 최근 운행부터, 주행 중인 운행은 빼고`() {
        val trips = listOf(
            trip(1, 0, 1_000_000, 1_010_000, purpose = null),
            trip(2, 1, 1_020_000, 1_030_000, purpose = TripPurpose.SITE_VISIT, dest = null, inputAfter = Duration.ofDays(10)),
            trip(3, 2, 1_030_000, null, purpose = null, ended = false),
        )
        val items = ReviewRules.review(trips, emptyList(), zone)
        assertEquals(listOf(2L, 1L), items.map { it.row.tripId })
        assertEquals(
            listOf(ReviewReason.MISSING_DESTINATION, ReviewReason.ODOMETER_GAP, ReviewReason.LATE_INPUT),
            items.first().reasons,
        )
    }
}
