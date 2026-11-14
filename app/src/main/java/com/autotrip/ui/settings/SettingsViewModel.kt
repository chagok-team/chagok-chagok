package com.autotrip.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.BusinessProfile
import com.autotrip.domain.model.BusinessType
import com.autotrip.domain.model.Client
import com.autotrip.domain.model.TripUser
import com.autotrip.domain.model.VehicleInfo
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.ui.toUserMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 저장된 설정 값 (화면이 입력칸 초기값으로 쓴다) */
data class SettingsSaved(
    val business: BusinessProfile?,
    val vehicle: VehicleInfo?,
    val users: List<TripUser>,
    val defaultUserId: Long?,
    val clients: List<Client> = emptyList(),
) {
    /** PDF를 출력하려면 사업자·차량 정보가 있어야 한다 (ProfileIncomplete 방지) */
    val readyForExport: Boolean get() = business != null && vehicle != null
}

fun settingsFlow(profileRepository: ProfileRepository): Flow<SettingsSaved> = combine(
    profileRepository.observeBusinessProfile(),
    profileRepository.observeVehicle(),
    profileRepository.observeUsers(),
    profileRepository.observeDefaultUserId(),
    profileRepository.observeClients(),
) { business, vehicle, users, defaultUserId, clients -> SettingsSaved(business, vehicle, users, defaultUserId, clients) }

/**
 * 설정 저장 동작. 입력 검사 → Repository 호출 → 사용자에게 보여줄 결과 문구를 돌려준다.
 * ViewModel 밖에 두어 단위 테스트한다.
 */
class SettingsActions(private val profileRepository: ProfileRepository) {

    sealed interface Outcome {
        val message: String
        data class Saved(override val message: String) : Outcome
        data class Rejected(override val message: String) : Outcome
    }

    suspend fun saveBusiness(type: BusinessType, name: String, registration: String): Outcome {
        val checkedName = SettingsRules.businessName(name, type)
        if (checkedName is Checked.Invalid) return Outcome.Rejected(checkedName.message)
        val checkedNumber = SettingsRules.registrationNumber(registration)
        if (checkedNumber is Checked.Invalid) return Outcome.Rejected(checkedNumber.message)
        val profile = BusinessProfile(type, (checkedName as Checked.Ok).value, (checkedNumber as Checked.Ok).value)
        return profileRepository.saveBusinessProfile(profile).toOutcome("사업자 정보를 저장했어요")
    }

    suspend fun saveVehicle(model: String, plate: String): Outcome {
        val checkedPlate = SettingsRules.plateNumber(plate)
        if (checkedPlate is Checked.Invalid) return Outcome.Rejected(checkedPlate.message)
        return profileRepository.saveVehicle(SettingsRules.model(model), (checkedPlate as Checked.Ok).value)
            .toOutcome("차량 정보를 저장했어요")
    }

    suspend fun addUser(department: String, name: String): Outcome {
        val checked = SettingsRules.user(department, name)
        if (checked is Checked.Invalid) return Outcome.Rejected(checked.message)
        val (dept, person) = (checked as Checked.Ok).value
        return profileRepository.addUser(dept, person).toOutcome("$dept $person 님을 추가했어요")
    }

    suspend fun setDefaultUser(userId: Long): Outcome =
        profileRepository.setDefaultUserId(userId).toOutcome("기본 차량이용자를 바꿨어요")

    /** v1.1 거래처 (출발할 때 "○○ 방문으로 기록할까요?" 제안에 쓴다) */
    suspend fun addClient(name: String, existing: List<Client>): Outcome {
        val checked = SettingsRules.clientName(name, existing.map { it.name })
        if (checked is Checked.Invalid) return Outcome.Rejected(checked.message)
        val clean = (checked as Checked.Ok).value
        return profileRepository.addClient(clean, point = null).toOutcome("거래처를 추가했어요: $clean")
    }

    /** 거래처 목록에서만 지운다. 이미 기록된 운행은 바뀌지 않는다. */
    suspend fun removeClient(client: Client): Outcome =
        profileRepository.removeClient(client.id).toOutcome("거래처에서 뺐어요: ${client.name}")

    private fun <T> AutoTripResult<T>.toOutcome(success: String): Outcome = when (this) {
        is AutoTripResult.Success -> Outcome.Saved(success)
        is AutoTripResult.Failure -> Outcome.Rejected(error.toUserMessage())
    }
}

class SettingsViewModel(profileRepository: ProfileRepository) : ViewModel() {
    private val actions = SettingsActions(profileRepository)

    /** null이면 아직 불러오는 중 */
    val saved: StateFlow<SettingsSaved?> = settingsFlow(profileRepository)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _lastOutcome = MutableStateFlow<SettingsActions.Outcome?>(null)
    val lastOutcome: StateFlow<SettingsActions.Outcome?> = _lastOutcome.asStateFlow()

    fun saveBusiness(type: BusinessType, name: String, registration: String) =
        run { actions.saveBusiness(type, name, registration) }

    fun saveVehicle(model: String, plate: String) = run { actions.saveVehicle(model, plate) }
    fun addUser(department: String, name: String) = run { actions.addUser(department, name) }
    fun setDefaultUser(userId: Long) = run { actions.setDefaultUser(userId) }
    fun addClient(name: String) = run { actions.addClient(name, saved.value?.clients.orEmpty()) }
    fun removeClient(client: Client) = run { actions.removeClient(client) }

    private fun run(block: suspend () -> SettingsActions.Outcome) {
        viewModelScope.launch { _lastOutcome.value = block() }
    }

    companion object {
        fun factory(profileRepository: ProfileRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { SettingsViewModel(profileRepository) } }
    }
}
