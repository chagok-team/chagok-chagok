package com.autotrip.ui.settings

import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.BusinessProfile
import com.autotrip.domain.model.BusinessType
import com.autotrip.domain.model.Client
import com.autotrip.domain.model.GeoPoint
import com.autotrip.domain.model.TripUser
import com.autotrip.domain.model.ValueSource
import com.autotrip.domain.model.VehicleInfo
import com.autotrip.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsActionsTest {

    /** 이 테스트 전용 ProfileRepository (A의 Fake와 별개) */
    private class StubProfileRepository(private val vehicleResult: AutoTripResult<VehicleInfo>? = null) : ProfileRepository {
        val business = MutableStateFlow<BusinessProfile?>(null)
        val vehicle = MutableStateFlow<VehicleInfo?>(null)
        val users = MutableStateFlow<List<TripUser>>(emptyList())
        val defaultUser = MutableStateFlow<Long?>(null)
        var lastVehicleModel: String? = "unset"

        override fun observeBusinessProfile(): Flow<BusinessProfile?> = business
        override fun observeVehicle(): Flow<VehicleInfo?> = vehicle
        override fun observeUsers(): Flow<List<TripUser>> = users
        override fun observeDefaultUserId(): Flow<Long?> = defaultUser
        override suspend fun saveBusinessProfile(profile: BusinessProfile): AutoTripResult<Unit> {
            business.value = profile
            return AutoTripResult.Success(Unit)
        }
        override suspend fun saveVehicle(model: String?, plateNumber: String): AutoTripResult<VehicleInfo> {
            lastVehicleModel = model
            vehicleResult?.let { return it }
            val saved = VehicleInfo(1, model ?: "아이오닉 5", if (model == null) ValueSource.SDK else ValueSource.USER, plateNumber)
            vehicle.value = saved
            return AutoTripResult.Success(saved)
        }
        override suspend fun addUser(department: String, name: String): AutoTripResult<TripUser> {
            val user = TripUser(users.value.size + 1L, department, name)
            users.value = users.value + user
            return AutoTripResult.Success(user)
        }
        override suspend fun setDefaultUserId(userId: Long?): AutoTripResult<Unit> {
            defaultUser.value = userId
            return AutoTripResult.Success(Unit)
        }
        val clients = MutableStateFlow<List<Client>>(emptyList())
        val removed = mutableListOf<Long>()
        override fun observeClients(): Flow<List<Client>> = clients
        override suspend fun addClient(name: String, point: GeoPoint?): AutoTripResult<Client> {
            val client = Client(clients.value.size + 1L, name, point)
            clients.value = clients.value + client
            return AutoTripResult.Success(client)
        }
        override suspend fun removeClient(clientId: Long): AutoTripResult<Unit> {
            removed += clientId
            clients.value = clients.value.filterNot { it.id == clientId }
            return AutoTripResult.Success(Unit)
        }
    }

    @Test
    fun `거래처는 이름을 정리해서 위치 없이 추가하고, 중복은 막는다`() = runBlocking {
        val repo = StubProfileRepository()
        val actions = SettingsActions(repo)
        assertEquals(
            SettingsActions.Outcome.Saved("거래처를 추가했어요: 기아 본사"),
            actions.addClient("  기아   본사 ", repo.clients.value),
        )
        assertEquals(listOf(Client(1, "기아 본사", null)), repo.clients.value)
        assertEquals(
            SettingsActions.Outcome.Rejected("이미 등록된 거래처예요"),
            actions.addClient("기아본사", repo.clients.value),
        )
        assertEquals(1, repo.clients.value.size)
    }

    @Test
    fun `거래처 빼기는 그 거래처만 목록에서 지운다`() = runBlocking {
        val repo = StubProfileRepository()
        val actions = SettingsActions(repo)
        actions.addClient("기아 본사", emptyList())
        actions.addClient("현대모비스", repo.clients.value)
        assertEquals(
            SettingsActions.Outcome.Saved("거래처에서 뺐어요: 기아 본사"),
            actions.removeClient(repo.clients.value.first()),
        )
        assertEquals(listOf(1L), repo.removed)
        assertEquals(listOf("현대모비스"), repo.clients.value.map { it.name })
    }

    @Test
    fun `설정 흐름에 거래처 목록이 들어간다`() = runBlocking {
        val repo = StubProfileRepository()
        SettingsActions(repo).addClient("기아 본사", emptyList())
        assertEquals(listOf("기아 본사"), settingsFlow(repo).first().clients.map { it.name })
    }

    @Test
    fun `사업자 정보는 등록번호를 형식에 맞춰 저장한다`() = runBlocking {
        val repo = StubProfileRepository()
        val outcome = SettingsActions(repo).saveBusiness(BusinessType.SOLE_PROPRIETOR, " 오토트립 상사 ", "2345678901")
        assertEquals(SettingsActions.Outcome.Saved("사업자 정보를 저장했어요"), outcome)
        assertEquals(BusinessProfile(BusinessType.SOLE_PROPRIETOR, "오토트립 상사", "234-56-78901"), repo.business.value)
    }

    @Test
    fun `잘못된 입력은 저장하지 않고 이유를 알려준다`() = runBlocking {
        val repo = StubProfileRepository()
        val outcome = SettingsActions(repo).saveBusiness(BusinessType.CORPORATION, "(주)오토트립", "12345")
        assertEquals(SettingsActions.Outcome.Rejected("사업자등록번호는 숫자 10자리예요"), outcome)
        assertNull(repo.business.value)
    }

    @Test
    fun `차종을 비우면 null로 넘겨 차량 정보에서 읽게 한다`() = runBlocking {
        val repo = StubProfileRepository()
        SettingsActions(repo).saveVehicle("  ", "12가3456")
        assertNull(repo.lastVehicleModel)
        assertEquals("12가 3456", repo.vehicle.value?.plateNumber)
    }

    @Test
    fun `차량 정보를 읽지 못하면 오류 문구를 보여준다`() = runBlocking {
        val repo = StubProfileRepository(AutoTripResult.Failure(AutoTripError.Sdk("Vehicle.CarInfo", "x")))
        val outcome = SettingsActions(repo).saveVehicle("", "12가3456")
        assertEquals(SettingsActions.Outcome.Rejected("차량 정보를 읽지 못했어요"), outcome)
    }

    @Test
    fun `차량이용자 추가와 기본 이용자 지정`() = runBlocking {
        val repo = StubProfileRepository()
        val actions = SettingsActions(repo)
        assertEquals(SettingsActions.Outcome.Saved("영업팀 김현대 님을 추가했어요"), actions.addUser("영업팀", "김현대"))
        actions.setDefaultUser(1)
        val saved = settingsFlow(repo).first()
        assertEquals(1, saved.users.size)
        assertEquals(1L, saved.defaultUserId)
        assertFalse("사업자·차량 정보 전에는 출력 불가", saved.readyForExport)
    }

    @Test
    fun `사업자와 차량이 모두 있으면 출력 준비 완료`() = runBlocking {
        val repo = StubProfileRepository()
        val actions = SettingsActions(repo)
        actions.saveBusiness(BusinessType.CORPORATION, "(주)오토트립", "1234567890")
        actions.saveVehicle("아이오닉 5", "12가 3456")
        assertTrue(settingsFlow(repo).first().readyForExport)
    }
}
