package com.autotrip.ui.home

import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.BusinessProfile
import com.autotrip.domain.model.BusinessType
import com.autotrip.domain.model.DetectionAvailability
import com.autotrip.domain.model.MileageSummary
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.TripState
import com.autotrip.domain.model.ValueSource
import com.autotrip.domain.model.VehicleInfo
import com.autotrip.domain.repository.TripTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HomeTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val period = TaxPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    private val auto = DetectionAvailability(true, true, true, true)
    private val business = BusinessProfile(BusinessType.CORPORATION, "에이아이모빌리티", "123-45-67890")
    private val vehicle = VehicleInfo(1, "아이오닉 5", ValueSource.SDK, "12가3456")

    private fun summary(total: Long = 793_300, business: Long = 679_500, ratio: Double? = 0.8566) = MileageSummary(
        period = period, totalMeters = total, commuteMeters = 197_300, generalBusinessMeters = 482_200,
        businessMeters = business, nonBusinessMeters = 0, unclassifiedMeters = 0,
        businessUseRatio = ratio, incompleteTripCount = 0, pendingPurposeTripCount = 0,
    )

    @Test
    fun `대기 중이면 수동 시작을 보여주고 숫자는 그대로 옮긴다`() {
        val ui = HomeMapper.map(TripState.Idle, auto, summary(), business, vehicle, 2, zone)
        assertEquals("대기 중", ui.statusTitle)
        assertEquals("출발하면 자동으로 기록해요", ui.statusDetail)
        assertEquals(ManualAction.START, ui.manualAction)
        assertEquals("사업연도", ui.periodLabel)
        assertEquals("85.7%", ui.ratioText)
        assertEquals("793.3", ui.totalKm)
        assertEquals("679.5", ui.businessKm)
        assertEquals(2, ui.pendingCount)
        assertFalse(ui.profileMissing)
        assertFalse(ui.driving)
    }

    @Test
    fun `주행 중이면 출발 시각과 종료 버튼`() {
        val state = TripState.Driving(7, TripSource.MANUAL, Instant.parse("2026-11-13T00:12:00Z"))
        val ui = HomeMapper.map(state, auto, summary(), business, vehicle, 0, zone)
        assertEquals("주행 중", ui.statusTitle)
        assertEquals("09:12 출발 · 수동 기록", ui.statusDetail)
        assertEquals(ManualAction.END, ui.manualAction)
        assertTrue(ui.driving)
    }

    @Test
    fun `도착 상태`() {
        val state = TripState.Arrived(7, TripSource.AUTO, Instant.parse("2026-11-13T00:47:00Z"))
        assertEquals("도착" to "09:47 운행을 마쳤어요", HomeMapper.status(state, auto, zone))
    }

    @Test
    fun `자동 감지를 못 쓰면 수동 시작을 안내한다`() {
        val (_, detail) = HomeMapper.status(TripState.Idle, auto.copy(autoDetectionSupported = false), zone)
        assertEquals("자동 감지를 쓸 수 없어요. 출발 전에 수동으로 시작해 주세요", detail)
    }

    @Test
    fun `비율이 없으면 대시, 프로필이 없으면 설정 안내`() {
        val ui = HomeMapper.map(TripState.Idle, auto, summary(total = 0, business = 0, ratio = null), null, vehicle, 0, zone)
        assertEquals("-", ui.ratioText)
        assertEquals("과세기간", ui.periodLabel)
        assertTrue(ui.profileMissing)
        assertEquals("0.0 km", kmWithUnit(ui.totalKm))
        assertEquals("-", kmWithUnit("-"))
    }

    private class StubTracker(private val start: AutoTripResult<Long>, private val end: AutoTripResult<Long>) : TripTracker {
        override val state: StateFlow<TripState> = MutableStateFlow(TripState.Idle)
        override val availability: StateFlow<DetectionAvailability> = MutableStateFlow(DetectionAvailability(true, true, true, true))
        override suspend fun startManualTrip() = start
        override suspend fun endManualTrip() = end
        override val destinationEvents: kotlinx.coroutines.flow.Flow<com.autotrip.domain.model.NavigationDestination> =
            kotlinx.coroutines.flow.emptyFlow()
    }

    @Test
    fun `수동 시작·종료 결과 문구`() = runBlocking {
        val ok = HomeActions(StubTracker(AutoTripResult.Success(1), AutoTripResult.Success(1)))
        assertEquals("운행 기록을 시작했어요", ok.run(ManualAction.START))
        assertEquals("운행 기록을 마쳤어요", ok.run(ManualAction.END))
        val fail = HomeActions(StubTracker(AutoTripResult.Failure(AutoTripError.InvalidState("Driving")), AutoTripResult.Success(1)))
        assertEquals("지금은 처리할 수 없어요", fail.run(ManualAction.START))
    }
}
