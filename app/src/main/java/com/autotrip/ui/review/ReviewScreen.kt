package com.autotrip.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.ui.theme.AppCard
import com.autotrip.ui.trip.DrivingRestrictedNotice
import com.autotrip.ui.trip.ListCardPadding
import com.autotrip.ui.trip.currentYearPeriod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

fun reviewFlow(
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    period: TaxPeriod,
    zone: ZoneId,
): Flow<List<ReviewItemUi>> =
    combine(tripRepository.observeTrips(period), profileRepository.observeUsers()) { trips, users ->
        ReviewRules.review(trips, users, zone)
    }

class ReviewViewModel(
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    period: TaxPeriod = currentYearPeriod(),
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    /** null이면 아직 불러오는 중 */
    val items: StateFlow<List<ReviewItemUi>?> = reviewFlow(tripRepository, profileRepository, period, zone)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        fun factory(tripRepository: TripRepository, profileRepository: ProfileRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { ReviewViewModel(tripRepository, profileRepository) } }
    }
}

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

/**
 * 확인이 필요한 기록 (재무·총무 담당자용). 줄을 누르면 운행 상세(수정·이력)로 간다.
 * 목록 화면이므로 주행 중에는 숨긴다.
 */
@Composable
fun ReviewScreen(
    items: List<ReviewItemUi>?,
    browsingAllowed: Boolean,
    onTripClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!browsingAllowed) {
        DrivingRestrictedNotice(modifier)
        return
    }
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp)) {
            Text("확인이 필요한 기록 ${items?.size ?: 0}건", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(
                "앱은 기록이 맞는지 판정하지 않고, 확인이 필요한 이유만 보여 줘요",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.outline,
            )
            Spacer(Modifier.height(20.dp))
            when {
                items == null -> Unit
                items.isEmpty() -> Text("확인이 필요한 기록이 없어요", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                else -> AppCard(Modifier.fillMaxWidth(), contentPadding = ListCardPadding) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        itemsIndexed(items, key = { _, item -> item.row.tripId }) { index, item ->
                            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                            ReviewRow(item, onClick = { onTripClick(item.row.tripId) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(item: ReviewItemUi, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable(onClick = onClick).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(160.dp)) {
            Text(item.row.dateText, style = MaterialTheme.typography.titleMedium.tabular(), color = colors.onSurface)
            Text(item.row.timeText, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.outline)
        }
        Column(Modifier.weight(1f)) {
            val place = item.row.placeText ?: "행선지 없음"
            Text(
                "${item.row.purposeText}  ·  $place",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { item.reasons.forEach { ReasonChip(it) } }
            item.details.forEach { detail ->
                Spacer(Modifier.height(4.dp))
                Text(detail, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.onSurfaceVariant)
            }
        }
        Text(
            if (item.row.drivenKmText == "-") "-" else "${item.row.drivenKmText} km",
            style = MaterialTheme.typography.titleLarge.tabular(),
            color = colors.onSurface,
        )
    }
}

@Composable
private fun ReasonChip(reason: ReviewReason) {
    Text(
        reason.label,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
