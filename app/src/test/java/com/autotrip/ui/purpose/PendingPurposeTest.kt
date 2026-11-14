package com.autotrip.ui.purpose

import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.OdometerCaptureStatus
import com.autotrip.domain.model.OdometerReading
import com.autotrip.domain.model.PurposeStatus
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.Trip
import com.autotrip.domain.model.TripEditHistory
import com.autotrip.domain.model.TripPlace
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PendingPurposeTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    private fun trip(id: Long, start: OdometerReading = OdometerReading(45_250_100, OdometerCaptureStatus.CAPTURED, null)) = Trip(
        id = id, vehicleId = 1, source = TripSource.AUTO,
        startedAt = Instant.parse("2026-11-12T02:10:00Z"), endedAt = Instant.parse("2026-11-12T02:41:00Z"),
        odometerStart = start, odometerEnd = OdometerReading(45_268_900, OdometerCaptureStatus.CAPTURED, null),
        startPlace = null, endPlace = TripPlace(null, "판교 테크노밸리"), destinationName = null,
        userId = null, purpose = null, purposeStatus = PurposeStatus.PENDING, purposeInputMethod = null,
        purposeInputTime = null, voiceRawText = null, memo = null,
    )

    private class StubTripRepository(
        private val pending: List<Trip>,
        private val result: AutoTripResult<Unit> = AutoTripResult.Success(Unit),
    ) : TripRepository {
        data class Call(val tripId: Long, val purpose: TripPurpose, val method: InputMethod, val userId: Long?, val raw: String?)
        val calls = mutableListOf<Call>()
        override fun observePendingPurposeTrips(): Flow<List<Trip>> = flowOf(pending)
        override suspend fun confirmPurpose(tripId: Long, purpose: TripPurpose, method: InputMethod, userId: Long?, voiceRawText: String?): AutoTripResult<Unit> {
            calls += Call(tripId, purpose, method, userId, voiceRawText)
            return result
        }
        override fun observeTrips(period: TaxPeriod): Flow<List<Trip>> = flowOf(emptyList())
        override fun observeTrip(tripId: Long): Flow<Trip?> = flowOf(null)
        override fun observeEditHistory(tripId: Long): Flow<List<TripEditHistory>> = flowOf(emptyList())
        override suspend fun updatePurpose(tripId: Long, purpose: TripPurpose) = AutoTripResult.Success(Unit)
        override suspend fun updateUser(tripId: Long, userId: Long?) = AutoTripResult.Success(Unit)
        override suspend fun updateMemo(tripId: Long, memo: String?) = AutoTripResult.Success(Unit)
        override suspend fun setPlannedPurpose(purpose: TripPurpose, method: InputMethod, voiceRawText: String?) =
            AutoTripResult.Success(Unit)
    }

    @Test
    fun `미입력 운행 목록과 요약`() = runBlocking {
        val items = pendingFlow(StubTripRepository(listOf(trip(4))), seoul).first()
        val item = items.single()
        assertEquals("판교 테크노밸리", item.row.placeText)
        assertEquals("18.8", item.summary.drivenKm)
        assertEquals("45,250.1 → 45,268.9 km", item.summary.odometerRange)
        assertEquals("11.12(목) 11:10 – 11:41 · 판교 테크노밸리", item.summary.arrivedAt)
    }

    @Test
    fun `계기판 실패 운행은 거리 대신 null`() {
        val item = PendingMapper.item(trip(5, start = OdometerReading(null, OdometerCaptureStatus.FAILED, null)), seoul)
        assertNull(item.summary.drivenKm)
        assertNull(item.summary.odometerRange)
    }

    @Test
    fun `나중 입력은 BUTTON으로, 기본 이용자로, 음성 원문 없이 저장한다`() = runBlocking {
        val repo = StubTripRepository(emptyList())
        assertNull(PendingActions(repo).confirm(4, TripPurpose.MEETING))
        assertEquals(StubTripRepository.Call(4, TripPurpose.MEETING, InputMethod.BUTTON, null, null), repo.calls.single())
    }

    @Test
    fun `이미 확정된 운행은 오류로 보지 않는다`() = runBlocking {
        val repo = StubTripRepository(emptyList(), AutoTripResult.Failure(AutoTripError.PurposeAlreadyConfirmed(4)))
        assertNull(PendingActions(repo).confirm(4, TripPurpose.MEETING))
    }

    @Test
    fun `그 밖의 오류는 문구로`() = runBlocking {
        val repo = StubTripRepository(emptyList(), AutoTripResult.Failure(AutoTripError.TripNotFound(4)))
        assertEquals("운행 기록을 찾지 못했어요", PendingActions(repo).confirm(4, TripPurpose.MEETING))
    }
}
