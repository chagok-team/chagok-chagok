package com.autotrip.ui.trip

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autotrip.ui.theme.AppCard
import com.autotrip.ui.theme.ControlShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed

/** 목록 카드: 줄 사이 구분선이 카드 끝까지 닿도록 위아래만 조금 띄운다 */
internal val ListCardPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp)

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

/** 주행 중에 목록·상세 대신 보여주는 안내 (입력 요소·스크롤 없음) */
@Composable
fun DrivingRestrictedNotice(modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) {
            Text("주행 중에는 기록을 볼 수 없어요", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── 기록 목록 ─────────────────────────────────────────

internal const val EMPTY_TRIPS_TITLE = "아직 기록된 운행이 없어요"
internal const val EMPTY_TRIPS_HINT = "운행을 마치면 여기에 쌓여요. 홈의 '수동으로 운행 시작'으로도 기록할 수 있어요"

/**
 * @param browsingAllowed 정차 중이면 true. 주행 중(CarUxRestrictions)에는 목록 스크롤을 숨긴다.
 */
@Composable
fun TripListScreen(
    state: TripListUi?,
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
            Row(verticalAlignment = Alignment.Bottom) {
                Text("운행 기록", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
                Spacer(Modifier.width(16.dp))
                if (state != null) {
                    Text(state.periodText, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.outline, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            if (state != null && state.pendingCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text("목적 미입력 ${state.pendingCount}건", style = MaterialTheme.typography.bodyLarge, color = colors.primary)
            }
            Spacer(Modifier.height(20.dp))
            when {
                state == null -> Unit
                // 실제 모드 첫 실행: 무엇을 하면 기록이 생기는지 알려 준다
                state.rows.isEmpty() -> Column {
                    Text(EMPTY_TRIPS_TITLE, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(EMPTY_TRIPS_HINT, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                else -> AppCard(Modifier.fillMaxWidth(), contentPadding = ListCardPadding) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        itemsIndexed(state.rows, key = { _, row -> row.tripId }) { index, row ->
                            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                            TripRow(row, onClick = { onTripClick(row.tripId) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripRow(row: TripRowUi, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(160.dp)) {
            Text(row.dateText, style = MaterialTheme.typography.titleMedium.tabular(), color = colors.onBackground)
            Text(row.timeText, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.outline)
        }
        Column(Modifier.weight(1f)) {
            val pending = row.purposeText == TripUiMapper.PENDING_TEXT
            Text(
                row.purposeText,
                style = MaterialTheme.typography.titleLarge,
                color = if (pending) colors.primary else colors.onBackground,
            )
            val meta = listOfNotNull(row.placeText, row.userText.takeIf { it != "-" }).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // 미입력·주행 중은 목적 자리에 이미 보이므로 작은 표시에서는 뺀다
            val badges = row.badges.filter { it != TripBadge.PURPOSE_PENDING && it != TripBadge.DRIVING }
            if (badges.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { badges.forEach { BadgeChip(it) } }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(row.drivenKmText, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium).tabular(), color = colors.onBackground)
            if (row.drivenKmText != "-") {
                Spacer(Modifier.width(6.dp))
                Text("km", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun BadgeChip(badge: TripBadge) {
    Text(
        badge.label,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// ── 기록 상세 ─────────────────────────────────────────

@Composable
fun TripDetailScreen(
    state: TripDetailState,
    browsingAllowed: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    editing: EditTarget? = null,
    message: String? = null,
    handlers: TripEditHandlers = TripEditHandlers(),
) {
    // 주행 중에는 수정 화면도 숨긴다 (CLAUDE.md 4장 주행 중 UI 제한)
    if (!browsingAllowed) {
        DrivingRestrictedNotice(modifier)
        return
    }
    val editInfo = (state as? TripDetailState.Content)?.ui?.edit
    if (editing != null && editInfo != null) {
        TripEditContent(editing, editInfo, handlers, modifier)
        return
    }
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, shape = ControlShape, modifier = Modifier.heightIn(min = 72.dp)) {
                    Text("← 기록 목록", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                }
                if (message != null) {
                    Spacer(Modifier.width(24.dp))
                    Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.primary)
                }
            }
            when (state) {
                TripDetailState.Loading -> Unit
                TripDetailState.NotFound ->
                    Text("운행 기록을 찾지 못했어요", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
                is TripDetailState.Content -> DetailContent(state.ui, handlers.onEdit)
            }
        }
    }
}

@Composable
private fun DetailContent(ui: TripDetailUi, onEdit: (EditTarget) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.title, style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ui.badges.forEach { BadgeChip(it) } }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            AppCard(Modifier.weight(1f)) {
                FieldSection("차량이 기록한 값 (수정 불가)", ui.recordFields)
            }
            AppCard(Modifier.weight(1f)) {
                FieldSection("입력한 값", ui.inputFields, purposePending = ui.edit?.purposePending == true, onEdit = onEdit)
            }
        }
        Spacer(Modifier.height(20.dp))
        AppCard(Modifier.fillMaxWidth()) {
            Text("수정 이력", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (ui.history.isEmpty()) {
                Text("수정한 적 없음", style = MaterialTheme.typography.bodyLarge, color = colors.outline)
            } else {
                ui.history.forEach { item ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text(item.whenText, style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.outline, modifier = Modifier.width(140.dp))
                        Text(item.fieldText, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.width(140.dp))
                        Text(item.changeText, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldSection(
    title: String,
    fields: List<DetailField>,
    modifier: Modifier = Modifier,
    purposePending: Boolean = false,
    onEdit: (EditTarget) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        fields.forEach { field ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (field.edit != null) 72.dp else 0.dp).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(field.label, style = MaterialTheme.typography.bodyLarge, color = colors.outline, modifier = Modifier.width(200.dp))
                Text(field.value, style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.onBackground, modifier = Modifier.weight(1f))
                val target = field.edit
                if (target != null) {
                    val label = if (target == EditTarget.PURPOSE && purposePending) "입력" else "수정"
                    TextButton(onClick = { onEdit(target) }, shape = ControlShape, modifier = Modifier.heightIn(min = 64.dp)) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
        }
    }
}
