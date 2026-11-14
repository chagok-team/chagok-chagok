package com.autotrip.ui.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.voice.VoicePrompts
import com.autotrip.voice.spokenLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** 기본 과세기간: 올해 1/1 ~ 12/31 (INTERFACES 10장 #8) */
fun currentYearPeriod(today: LocalDate = LocalDate.now()): TaxPeriod =
    TaxPeriod(today.withDayOfYear(1), today.withMonth(12).withDayOfMonth(31))

// ── 흐름 조합 (ViewModel 밖에 두어 단위 테스트한다) ─────────────

fun tripListFlow(
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    period: TaxPeriod,
    zone: ZoneId,
): Flow<TripListUi> =
    combine(tripRepository.observeTrips(period), profileRepository.observeUsers()) { trips, users ->
        TripUiMapper.list(trips, users, period, zone)
    }

sealed interface TripDetailState {
    data object Loading : TripDetailState
    data object NotFound : TripDetailState
    data class Content(val ui: TripDetailUi) : TripDetailState
}

fun tripDetailFlow(
    tripId: Long,
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    zone: ZoneId,
): Flow<TripDetailState> =
    combine(
        tripRepository.observeTrip(tripId),
        profileRepository.observeUsers(),
        tripRepository.observeEditHistory(tripId),
    ) { trip, users, history ->
        if (trip == null) TripDetailState.NotFound
        else TripDetailState.Content(TripUiMapper.detail(trip, users, history, zone))
    }

// ── ViewModel ───────────────────────────────────────────────

class TripListViewModel(
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    period: TaxPeriod = currentYearPeriod(),
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    /** null이면 아직 불러오는 중 */
    val state: StateFlow<TripListUi?> = tripListFlow(tripRepository, profileRepository, period, zone)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        /** 연결 예: TripListViewModel.factory(container.tripRepository, container.profileRepository) */
        fun factory(tripRepository: TripRepository, profileRepository: ProfileRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { TripListViewModel(tripRepository, profileRepository) } }
    }
}

class TripDetailViewModel(
    private val tripId: Long,
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val actions = TripEditActions(tripRepository)

    val state: StateFlow<TripDetailState> = tripDetailFlow(tripId, tripRepository, profileRepository, zone)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailState.Loading)

    /** 지금 고치고 있는 값 (null이면 상세 화면) */
    private val _editing = MutableStateFlow<EditTarget?>(null)
    val editing: StateFlow<EditTarget?> = _editing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val handlers = TripEditHandlers(
        onEdit = { _editing.value = it; _message.value = null },
        onCancel = { _editing.value = null },
        onPurpose = { purpose ->
            val pending = (state.value as? TripDetailState.Content)?.ui?.edit?.purposePending ?: false
            save("${VoicePrompts.withEuro(purpose.spokenLabel)} 기록했어요") {
                actions.purpose(tripId, pending, purpose)
            }
        },
        onUser = { userId -> save("차량이용자를 바꿨어요") { actions.user(tripId, userId) } },
        onMemo = { text -> save("메모를 저장했어요") { actions.memo(tripId, text) } },
    )

    private fun save(done: String, block: suspend () -> String?) {
        viewModelScope.launch {
            val error = block()
            _message.value = error ?: done
            if (error == null) _editing.value = null
        }
    }

    companion object {
        fun factory(tripId: Long, tripRepository: TripRepository, profileRepository: ProfileRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { TripDetailViewModel(tripId, tripRepository, profileRepository) } }
    }
}
