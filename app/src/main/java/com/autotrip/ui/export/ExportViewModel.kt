package com.autotrip.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.ReportRepository
import com.autotrip.export.LogbookPdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * 출력 화면 ViewModel.
 * @param exportDir 앱 전용 저장소 폴더 (예: context.filesDir/exports)
 * @param isDemo BuildConfig.USE_FAKE_DATA → PDF에 "시연용" 워터마크
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModel(
    reportRepository: ReportRepository,
    profileRepository: ProfileRepository,
    exportDir: File,
    isDemo: Boolean,
) : ViewModel() {

    private val actions = ExportActions(reportRepository) { export ->
        LogbookPdfWriter(isDemo).writeToDirectory(export, exportDir)
    }

    private val _year = MutableStateFlow(LocalDate.now().year)
    val year: StateFlow<Int> = _year.asStateFlow()

    /** null이면 아직 불러오는 중 */
    val summary: StateFlow<ExportSummaryUi?> = combine(
        _year.flatMapLatest { reportRepository.observeSummary(ExportMapper.yearPeriod(it)) },
        profileRepository.observeBusinessProfile(),
    ) { summary, business -> ExportMapper.summary(summary, business?.type) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _outcome = MutableStateFlow<ExportActions.Outcome?>(null)
    val outcome: StateFlow<ExportActions.Outcome?> = _outcome.asStateFlow()

    fun changeYear(delta: Int) {
        _year.value += delta
        _outcome.value = null
    }

    fun export() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _outcome.value = withContext(Dispatchers.IO) { actions.export(ExportMapper.yearPeriod(_year.value)) }
            _busy.value = false
        }
    }

    companion object {
        fun factory(
            reportRepository: ReportRepository,
            profileRepository: ProfileRepository,
            exportDir: File,
            isDemo: Boolean,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ExportViewModel(reportRepository, profileRepository, exportDir, isDemo) }
        }
    }
}
