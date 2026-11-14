package com.autotrip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.ReportRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.domain.repository.TripTracker
import com.autotrip.ui.trip.currentYearPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

class HomeViewModel(
    tracker: TripTracker,
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    reportRepository: ReportRepository,
    period: TaxPeriod = currentYearPeriod(),
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val actions = HomeActions(tracker)

    /** null이면 아직 불러오는 중 */
    val state: StateFlow<HomeUi?> = homeFlow(tracker, tripRepository, profileRepository, reportRepository, period, zone)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var running = false

    fun runManual(action: ManualAction) {
        if (running) return
        running = true
        viewModelScope.launch {
            _message.value = actions.run(action)
            running = false
        }
    }

    companion object {
        /** 연결 예: HomeViewModel.factory(c.tripTracker, c.tripRepository, c.profileRepository, c.reportRepository) */
        fun factory(
            tracker: TripTracker,
            tripRepository: TripRepository,
            profileRepository: ProfileRepository,
            reportRepository: ReportRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(tracker, tripRepository, profileRepository, reportRepository) }
        }
    }
}
