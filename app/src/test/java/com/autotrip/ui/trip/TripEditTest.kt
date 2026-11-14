package com.autotrip.ui.trip

import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.OdometerCaptureStatus
import com.autotrip.domain.model.OdometerReading
import com.autotrip.domain.model.PurposeStatus
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.Trip
import com.autotrip.domain.model.TripEditHistory
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.TripUser
import com.autotrip.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TripEditTest {

    private class StubRepo(
        var confirmResult: AutoTripResult<Unit> = AutoTripResult.Success(Unit),
        var updateResult: AutoTripResult<Unit> = AutoTripResult.Success(Unit),
    ) : TripRepository {
        val calls = mutableListOf<String>()
        override suspend fun confirmPurpose(tripId: Long, purpose: TripPurpose, method: InputMethod, userId: Long?, voiceRawText: String?): AutoTripResult<Unit> {
            calls += "confirm $tripId $purpose $method $userId $voiceRawText"
            return confirmResult
        }
        override suspend fun updatePurpose(tripId: Long, purpose: TripPurpose): AutoTripResult<Unit> {
            calls += "updatePurpose $tripId $purpose"
            return updateResult
        }
        override suspend fun updateUser(tripId: Long, userId: Long?): AutoTripResult<Unit> {
            calls += "updateUser $tripId $userId"
            return updateResult
        }
        override suspend fun updateMemo(tripId: Long, memo: String?): AutoTripResult<Unit> {
            calls += "updateMemo $tripId $memo"
            return updateResult
        }
        override fun observeTrips(period: TaxPeriod): Flow<List<Trip>> = flowOf(emptyList())
        override fun observeTrip(tripId: Long): Flow<Trip?> = flowOf(null)
        override fun observePendingPurposeTrips(): Flow<List<Trip>> = flowOf(emptyList())
        override fun observeEditHistory(tripId: Long): Flow<List<TripEditHistory>> = flowOf(emptyList())
        override suspend fun setPlannedPurpose(purpose: TripPurpose, method: InputMethod, voiceRawText: String?) =
            AutoTripResult.Success(Unit)
    }

    @Test
    fun `목적이 없으면 버튼 입력으로 최초 확정한다`() = runBlocking {
        val repo = StubRepo()
        assertNull(TripEditActions(repo).purpose(4, pending = true, TripPurpose.MEETING))
        assertEquals(listOf("confirm 4 MEETING BUTTON null null"), repo.calls)
    }

    @Test
    fun `목적이 있으면 수정으로 저장한다`() = runBlocking {
        val repo = StubRepo()
        assertNull(TripEditActions(repo).purpose(5, pending = false, TripPurpose.MEETING))
        assertEquals(listOf("updatePurpose 5 MEETING"), repo.calls)
    }

    @Test
    fun `화면은 미입력인데 이미 확정됐으면 수정으로 다시 시도한다`() = runBlocking {
        val repo = StubRepo(confirmResult = AutoTripResult.Failure(AutoTripError.PurposeAlreadyConfirmed(4)))
        assertNull(TripEditActions(repo).purpose(4, pending = true, TripPurpose.MEETING))
        assertEquals(listOf("confirm 4 MEETING BUTTON null null", "updatePurpose 4 MEETING"), repo.calls)
    }

    @Test
    fun `화면은 확정인데 미확정이면 최초 확정으로 다시 시도한다`() = runBlocking {
        val repo = StubRepo(updateResult = AutoTripResult.Failure(AutoTripError.PurposeNotConfirmed(4)))
        assertNull(TripEditActions(repo).purpose(4, pending = false, TripPurpose.MEETING))
        assertEquals(listOf("updatePurpose 4 MEETING", "confirm 4 MEETING BUTTON null null"), repo.calls)
    }

    @Test
    fun `저장 실패는 문구로`() = runBlocking {
        val repo = StubRepo(updateResult = AutoTripResult.Failure(AutoTripError.Storage(RuntimeException())))
        assertEquals("기록을 저장하지 못했어요", TripEditActions(repo).user(5, 2))
    }

    @Test
    fun `이용자는 지정 안 함(null)도 저장한다`() = runBlocking {
        val repo = StubRepo()
        assertNull(TripEditActions(repo).user(5, null))
        assertEquals(listOf("updateUser 5 null"), repo.calls)
    }

    @Test
    fun `메모는 앞뒤 공백을 지우고 빈 메모는 null로`() = runBlocking {
        val repo = StubRepo()
        val actions = TripEditActions(repo)
        assertNull(actions.memo(5, "  계약서 전달 "))
        assertNull(actions.memo(5, "   "))
        assertEquals(listOf("updateMemo 5 계약서 전달", "updateMemo 5 null"), repo.calls)
    }

    @Test
    fun `너무 긴 메모는 저장하지 않는다`() = runBlocking {
        val repo = StubRepo()
        assertNotNull(TripEditActions(repo).memo(5, "가".repeat(MEMO_MAX_LENGTH + 1)))
        assertTrue(repo.calls.isEmpty())
    }

    // ── 상세 화면의 수정 가능 표시 ──

    private val zone = ZoneId.of("Asia/Seoul")
    private val users = listOf(TripUser(1, "영업팀", "김현대"))

    private fun trip(ended: Boolean, purpose: TripPurpose?) = Trip(
        id = 7, vehicleId = 1, source = TripSource.AUTO,
        startedAt = Instant.parse("2026-11-12T02:10:00Z"),
        endedAt = if (ended) Instant.parse("2026-11-12T02:41:00Z") else null,
        odometerStart = OdometerReading(45_250_100, OdometerCaptureStatus.CAPTURED, null),
        odometerEnd = if (ended) OdometerReading(45_268_900, OdometerCaptureStatus.CAPTURED, null)
        else OdometerReading(null, OdometerCaptureStatus.NOT_YET_CAPTURED, null),
        startPlace = null, endPlace = null, destinationName = "판교",
        userId = 1, purpose = purpose,
        purposeStatus = if (purpose == null) PurposeStatus.PENDING else PurposeStatus.CONFIRMED,
        purposeInputMethod = null, purposeInputTime = null, voiceRawText = null, memo = "메모",
    )

    @Test
    fun `끝난 운행은 목적·이용자·메모만 수정할 수 있다`() {
        val ui = TripUiMapper.detail(trip(ended = true, TripPurpose.MEETING), users, emptyList(), zone)
        assertEquals(listOf(EditTarget.PURPOSE, EditTarget.USER, EditTarget.MEMO), ui.inputFields.mapNotNull { it.edit })
        assertTrue(ui.recordFields.all { it.edit == null })
        val edit = ui.edit!!
        assertEquals(false, edit.purposePending)
        assertEquals(TripPurpose.MEETING, edit.purpose)
        assertEquals(listOf(UserOptionUi(1, "영업팀 김현대")), edit.users)
        assertEquals("18.8", edit.summary.drivenKm)
    }

    @Test
    fun `주행 중인 운행은 수정하지 않는다`() {
        val ui = TripUiMapper.detail(trip(ended = false, null), users, emptyList(), zone)
        assertNull(ui.edit)
        assertTrue(ui.inputFields.all { it.edit == null })
        assertEquals("주행 중", ui.inputFields.first { it.label == "목적" }.value)
    }
}
