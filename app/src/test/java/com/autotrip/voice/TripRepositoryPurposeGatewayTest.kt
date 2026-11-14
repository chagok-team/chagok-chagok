package com.autotrip.voice

import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.BusinessProfile
import com.autotrip.domain.model.Client
import com.autotrip.domain.model.GeoPoint
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
import com.autotrip.domain.model.VehicleInfo
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.ui.toUserMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TripRepositoryPurposeGatewayTest {

    /** 이 테스트 전용 TripRepository (A의 Fake와 별개, 저장 호출만 기록) */
    private class StubTripRepository(
        private val trips: Map<Long, Trip>,
        private val result: AutoTripResult<Unit> = AutoTripResult.Success(Unit),
    ) : TripRepository {
        val calls = mutableListOf<String>()

        override fun observeTrip(tripId: Long): Flow<Trip?> = flowOf(trips[tripId])
        override suspend fun confirmPurpose(
            tripId: Long, purpose: TripPurpose, method: InputMethod, userId: Long?, voiceRawText: String?,
        ): AutoTripResult<Unit> {
            calls += "confirm $tripId $purpose $method $userId $voiceRawText"
            return result
        }
        override suspend fun setPlannedPurpose(
            purpose: TripPurpose, method: InputMethod, voiceRawText: String?,
        ): AutoTripResult<Unit> {
            calls += "planned $purpose $method $voiceRawText"
            return result
        }

        override fun observeTrips(period: TaxPeriod): Flow<List<Trip>> = flowOf(trips.values.toList())
        override fun observePendingPurposeTrips(): Flow<List<Trip>> = flowOf(emptyList())
        override fun observeEditHistory(tripId: Long): Flow<List<TripEditHistory>> = flowOf(emptyList())
        override suspend fun updatePurpose(tripId: Long, purpose: TripPurpose) = AutoTripResult.Success(Unit)
        override suspend fun updateUser(tripId: Long, userId: Long?) = AutoTripResult.Success(Unit)
        override suspend fun updateMemo(tripId: Long, memo: String?) = AutoTripResult.Success(Unit)
    }

    private class StubProfileRepository(private val clients: List<Client> = emptyList()) : ProfileRepository {
        override fun observeClients(): Flow<List<Client>> = flowOf(clients)
        override fun observeBusinessProfile(): Flow<BusinessProfile?> = flowOf(null)
        override fun observeVehicle(): Flow<VehicleInfo?> = flowOf(null)
        override fun observeUsers(): Flow<List<TripUser>> = flowOf(emptyList())
        override fun observeDefaultUserId(): Flow<Long?> = flowOf(null)
        override suspend fun saveBusinessProfile(profile: BusinessProfile) = AutoTripResult.Success(Unit)
        override suspend fun saveVehicle(model: String?, plateNumber: String): AutoTripResult<VehicleInfo> =
            AutoTripResult.Failure(AutoTripError.InvalidState("x"))
        override suspend fun addUser(department: String, name: String) = AutoTripResult.Success(TripUser(1, department, name))
        override suspend fun setDefaultUserId(userId: Long?) = AutoTripResult.Success(Unit)
        override suspend fun addClient(name: String, point: GeoPoint?) = AutoTripResult.Success(Client(1, name, point))
        override suspend fun removeClient(clientId: Long) = AutoTripResult.Success(Unit)
    }

    private fun trip(id: Long, status: PurposeStatus, purpose: TripPurpose? = null) = Trip(
        id = id, vehicleId = 1, source = TripSource.AUTO,
        startedAt = Instant.parse("2026-11-12T05:00:00Z"), endedAt = Instant.parse("2026-11-12T05:30:00Z"),
        odometerStart = OdometerReading(45_275_800, OdometerCaptureStatus.CAPTURED, null),
        odometerEnd = OdometerReading(45_293_200, OdometerCaptureStatus.CAPTURED, null),
        startPlace = null, endPlace = null, destinationName = null,
        userId = null, purpose = purpose, purposeStatus = status, purposeInputMethod = null,
        purposeInputTime = null, voiceRawText = null, memo = null,
    )

    private fun gateway(repo: TripRepository, clients: List<Client> = emptyList()) =
        TripRepositoryPurposeGateway(repo, StubProfileRepository(clients))

    @Test
    fun `목적 미입력 여부와 확정된 목적`() = runBlocking {
        val g = gateway(
            StubTripRepository(mapOf(1L to trip(1, PurposeStatus.PENDING), 2L to trip(2, PurposeStatus.CONFIRMED, TripPurpose.MEETING))),
        )
        assertTrue(g.isPurposePending(1))
        assertFalse(g.isPurposePending(2))
        assertFalse("없는 운행", g.isPurposePending(99))
        assertNull(g.confirmedPurpose(1))
        assertEquals(TripPurpose.MEETING, g.confirmedPurpose(2))
        assertNull(g.confirmedPurpose(99))
    }

    @Test
    fun `같은 목적지의 가장 최근 확정 목적을 찾는다`() = runBlocking {
        fun t(id: Long, start: String, dest: String?, status: PurposeStatus, purpose: TripPurpose?, ended: Boolean = true) =
            trip(id, status, purpose).copy(
                startedAt = Instant.parse(start),
                endedAt = if (ended) Instant.parse(start).plusSeconds(1800) else null,
                destinationName = dest,
            )
        val trips = listOf(
            t(1, "2026-11-01T01:00:00Z", "본사 대회의실", PurposeStatus.CONFIRMED, TripPurpose.TRAINING),
            t(2, "2026-11-05T01:00:00Z", "본사대회의실", PurposeStatus.CONFIRMED, TripPurpose.MEETING),   // 가장 최근 (공백만 다름)
            t(3, "2026-11-07T01:00:00Z", "본사 대회의실", PurposeStatus.PENDING, null),                    // 미입력은 건너뜀
            t(4, "2026-11-08T01:00:00Z", "판교", PurposeStatus.CONFIRMED, TripPurpose.CLIENT_VISIT),
        ).associateBy { it.id }
        val g = gateway(StubTripRepository(trips))
        assertEquals(TripPurpose.MEETING, g.lastPurposeFor("본사 대회의실"))
        assertEquals(TripPurpose.CLIENT_VISIT, g.lastPurposeFor("판교"))
        assertNull(g.lastPurposeFor("처음 가는 곳"))
    }

    @Test
    fun `거래처 이름 목록`() = runBlocking {
        val g = gateway(StubTripRepository(emptyMap()), listOf(Client(1, "기아 본사", null), Client(2, "현대모비스", null)))
        assertEquals(listOf("기아 본사", "현대모비스"), g.clientNames())
    }

    @Test
    fun `끝난 운행 저장은 기본 차량이용자(userId null)와 음성 원문을 넘긴다`() = runBlocking {
        val repo = StubTripRepository(emptyMap())
        assertEquals(ConfirmOutcome.Saved, gateway(repo).confirmPurpose(1, TripPurpose.CLIENT_VISIT, InputMethod.VOICE, "거래처 방문이요"))
        assertEquals(listOf("confirm 1 CLIENT_VISIT VOICE null 거래처 방문이요"), repo.calls)
    }

    @Test
    fun `출발 시 목적은 setPlannedPurpose로 넘긴다`() = runBlocking {
        val repo = StubTripRepository(emptyMap())
        assertEquals(ConfirmOutcome.Saved, gateway(repo).setPlannedPurpose(TripPurpose.CLIENT_VISIT, InputMethod.VOICE, "네"))
        assertEquals(listOf("planned CLIENT_VISIT VOICE 네"), repo.calls)
    }

    @Test
    fun `이미 확정된 운행은 조용히 끝낸다`() = runBlocking {
        val repo = StubTripRepository(emptyMap(), AutoTripResult.Failure(AutoTripError.PurposeAlreadyConfirmed(1)))
        assertEquals(ConfirmOutcome.AlreadyConfirmed, gateway(repo).confirmPurpose(1, TripPurpose.MEETING, InputMethod.BUTTON, null))
        assertEquals(ConfirmOutcome.AlreadyConfirmed, gateway(repo).setPlannedPurpose(TripPurpose.MEETING, InputMethod.BUTTON, null))
    }

    @Test
    fun `그 밖의 오류는 사용자 문구로 바꾼다`() = runBlocking {
        val repo = StubTripRepository(emptyMap(), AutoTripResult.Failure(AutoTripError.Storage(RuntimeException("disk"))))
        assertEquals(
            ConfirmOutcome.Failed("기록을 저장하지 못했어요"),
            gateway(repo).confirmPurpose(1, TripPurpose.MEETING, InputMethod.BUTTON, null),
        )
    }

    @Test
    fun `모든 오류 종류에 마침표 없는 문구가 있다`() {
        listOf(
            AutoTripError.TripNotFound(1), AutoTripError.PurposeAlreadyConfirmed(1), AutoTripError.PurposeNotConfirmed(1),
            AutoTripError.InvalidState("x"), AutoTripError.ProfileIncomplete, AutoTripError.Storage(RuntimeException()),
            AutoTripError.Sdk("Vehicle.Odometer", "x"),
        ).forEach { error ->
            val message = error.toUserMessage()
            assertTrue(message.isNotBlank() && !message.endsWith("."))
        }
    }
}
