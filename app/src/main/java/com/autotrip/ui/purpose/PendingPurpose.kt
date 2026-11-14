package com.autotrip.ui.purpose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.Trip
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.ui.theme.AppCard
import com.autotrip.ui.theme.ControlShape
import com.autotrip.ui.theme.PrimaryButton
import com.autotrip.ui.toUserMessage
import com.autotrip.ui.trip.DrivingRestrictedNotice
import com.autotrip.ui.trip.TripRowUi
import com.autotrip.ui.trip.TripUiMapper
import com.autotrip.voice.VoicePrompts
import com.autotrip.voice.spokenLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/*
 * 목적 미입력 운행 목록과 나중 입력 (INTERFACES 4장: 재시작 후·내비 없이 출발한 운행은 자동으로 묻지 않고
 * 사용자가 정차 중에 직접 입력한다). 버튼으로 고르면 InputMethod.BUTTON으로 저장한다.
 */

data class PendingTripUi(val row: TripRowUi, val summary: TripSummaryText)

object PendingMapper {
    fun item(trip: Trip, zone: ZoneId): PendingTripUi = PendingTripUi(
        row = TripUiMapper.row(trip, emptyMap(), zone),
        summary = TripUiMapper.summary(trip, emptyMap(), zone),
    )
}

/** 버튼으로 목적 확정. 이미 다른 곳에서 확정됐으면 조용히 성공으로 본다. */
class PendingActions(private val tripRepository: TripRepository) {
    suspend fun confirm(tripId: Long, purpose: TripPurpose): String? =
        when (val result = tripRepository.confirmPurpose(tripId, purpose, InputMethod.BUTTON, userId = null, voiceRawText = null)) {
            is AutoTripResult.Success -> null
            is AutoTripResult.Failure ->
                if (result.error is AutoTripError.PurposeAlreadyConfirmed) null else result.error.toUserMessage()
        }
}

fun pendingFlow(tripRepository: TripRepository, zone: ZoneId): Flow<List<PendingTripUi>> =
    tripRepository.observePendingPurposeTrips().map { trips -> trips.map { PendingMapper.item(it, zone) } }

class PendingPurposeViewModel(
    tripRepository: TripRepository,
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val actions = PendingActions(tripRepository)

    val items: StateFlow<List<PendingTripUi>?> = pendingFlow(tripRepository, zone)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 목적을 고르고 있는 운행 (null이면 목록) */
    private val _selectedTripId = MutableStateFlow<Long?>(null)
    val selectedTripId: StateFlow<Long?> = _selectedTripId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun select(tripId: Long?) { _selectedTripId.value = tripId }

    fun confirm(purpose: TripPurpose) {
        val tripId = _selectedTripId.value ?: return
        viewModelScope.launch {
            val error = actions.confirm(tripId, purpose)
            _message.value = error ?: "${VoicePrompts.withEuro(purpose.spokenLabel)} 기록했어요"
            if (error == null) _selectedTripId.value = null
        }
    }

    companion object {
        fun factory(tripRepository: TripRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { PendingPurposeViewModel(tripRepository) } }
    }
}

// ── 화면 ──────────────────────────────────────────────

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

@Composable
fun PendingPurposeScreen(
    items: List<PendingTripUi>?,
    selectedTripId: Long?,
    message: String?,
    browsingAllowed: Boolean,
    onSelectTrip: (Long?) -> Unit,
    onConfirm: (TripPurpose) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!browsingAllowed) {
        DrivingRestrictedNotice(modifier)
        return
    }
    val selected = items?.firstOrNull { it.row.tripId == selectedTripId }
    if (selected != null) {
        PurposeSelectScreen(
            reasonText = "나중에 입력하는 목적은 버튼 입력으로 기록돼요",
            candidates = emptySet(),
            onSelectPurpose = onConfirm,
            onDismiss = { onSelectTrip(null) },
            modifier = modifier,
            trip = selected.summary,
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp)) {
            Text("목적 미입력 ${items?.size ?: 0}건", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.primary)
            }
            Spacer(Modifier.height(20.dp))
            when {
                items == null -> Unit
                items.isEmpty() -> Text("목적을 입력하지 않은 운행이 없어요", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                else -> AppCard(Modifier.fillMaxWidth(), contentPadding = com.autotrip.ui.trip.ListCardPadding) { LazyColumn {
                    items(items, key = { it.row.tripId }) { item ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.width(160.dp)) {
                                Text(item.row.dateText, style = MaterialTheme.typography.titleMedium.tabular(), color = colors.onBackground)
                                Text(item.row.timeText, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.outline)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(item.row.placeText ?: "행선지 정보 없음", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
                                val km = item.row.drivenKmText
                                Text(if (km == "-") "주행거리 -" else "$km km", style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.onSurfaceVariant)
                            }
                            PrimaryButton(
                                onClick = { onSelectTrip(item.row.tripId) },
                                shape = ControlShape,
                                modifier = Modifier.heightIn(min = 72.dp),
                            ) { Text("목적 입력", style = MaterialTheme.typography.labelLarge) }
                        }
                        if (item != items.last()) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                    }
                } }
            }
        }
    }
}
