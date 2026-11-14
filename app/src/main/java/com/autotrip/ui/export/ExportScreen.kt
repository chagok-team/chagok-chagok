package com.autotrip.ui.export

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autotrip.ui.theme.AppCard
import com.autotrip.ui.theme.ControlShape
import com.autotrip.ui.theme.PrimaryButton
import androidx.compose.foundation.layout.PaddingValues
import com.autotrip.ui.trip.DrivingRestrictedNotice

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

/** 고정 안내 (세무 표현 원칙: 서식 양식까지만, 전문가 확인 안내) */
const val EXPORT_NOTICE = "국세청 고시 서식 양식에 맞춰 작성돼요. 세무 판단은 전문가 확인이 필요해요"

/** 만든 PDF를 앱 안에서 여는 버튼 */
const val PREVIEW_ACTION = "미리보기"

/**
 * 운행기록부 출력 화면: 연도 선택 → ⑪⑫⑬ 미리보기 → (경고 확인) → PDF 만들기.
 */
@Composable
fun ExportScreen(
    year: Int,
    summary: ExportSummaryUi?,
    busy: Boolean,
    outcome: ExportActions.Outcome?,
    browsingAllowed: Boolean,
    onChangeYear: (Int) -> Unit,
    onExport: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!browsingAllowed) {
        DrivingRestrictedNotice(modifier)
        return
    }
    val colors = MaterialTheme.colorScheme
    var confirming by remember(summary?.warnings) { mutableStateOf(false) }
    // 만든 PDF를 앱 안에서 보는 중이면 그 화면만 (닫으면 출력 화면으로)
    var previewing by remember(outcome) { mutableStateOf(false) }
    if (previewing && outcome is ExportActions.Outcome.Created) {
        PdfPreviewScreen(outcome.file, onClose = { previewing = false }, modifier = modifier)
        return
    }

    Surface(modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp)) {
            Text("운행기록부 출력", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            Spacer(Modifier.height(16.dp))

            // ── 기간 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onChangeYear(-1) }, shape = ControlShape, modifier = Modifier.heightIn(min = 72.dp)) {
                    Text("‹", style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("$year", style = MaterialTheme.typography.headlineMedium.tabular(), color = colors.onBackground)
                    if (summary != null) {
                        Text("${summary.periodLabel}  ${summary.periodText}", style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.outline)
                    }
                }
                Spacer(Modifier.width(16.dp))
                OutlinedButton(onClick = { onChangeYear(+1) }, shape = ControlShape, modifier = Modifier.heightIn(min = 72.dp)) {
                    Text("›", style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(Modifier.height(24.dp))

            if (summary != null) AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 32.dp)) {
                // ── ⑬ 업무사용비율: 화면의 중심 숫자 ──
                Text("⑬ 업무사용비율", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                Text(
                    summary.ratioText,
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = 72.sp, lineHeight = 76.sp, fontWeight = FontWeight.Medium).tabular(),
                    color = colors.primary,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                    Figure("⑪ 총주행거리", summary.totalKm)
                    Figure("⑫ 업무용 사용거리", summary.businessKm)
                    Figure("⑧ 출·퇴근용", summary.commuteKm, small = true)
                    Figure("⑨ 일반 업무용", summary.generalBusinessKm, small = true)
                }
                if (summary.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                    Spacer(Modifier.height(16.dp))
                    summary.warnings.forEach { warning ->
                        Text("※ $warning", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ── 결과 ──
            when (outcome) {
                is ExportActions.Outcome.Created -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PDF를 만들었어요 · ${outcome.pageNote} · ${outcome.file.name}",
                        style = MaterialTheme.typography.bodyLarge, color = colors.primary,
                    )
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onClick = { previewing = true }, shape = ControlShape, modifier = Modifier.heightIn(min = 72.dp)) {
                        Text(PREVIEW_ACTION, style = MaterialTheme.typography.labelLarge)
                    }
                }
                ExportActions.Outcome.NeedsProfile -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("사업자·차량 정보를 먼저 입력해 주세요", style = MaterialTheme.typography.bodyLarge, color = colors.error)
                    Spacer(Modifier.width(16.dp))
                    TextButton(onClick = onOpenSettings, shape = ControlShape, modifier = Modifier.heightIn(min = 72.dp)) {
                        Text("설정으로", style = MaterialTheme.typography.labelLarge)
                    }
                }
                is ExportActions.Outcome.Failed -> Text(outcome.message, style = MaterialTheme.typography.bodyLarge, color = colors.error)
                null -> Unit
            }
            Spacer(Modifier.height(12.dp))

            // ── 만들기 (경고가 있으면 한 번 더 확인) ──
            val hasWarnings = summary?.warnings?.isNotEmpty() == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (confirming) {
                    Text("포함되지 않는 운행이 있어요. 그래도 만들까요?", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onClick = { confirming = false }, shape = ControlShape, modifier = Modifier.heightIn(min = 72.dp)) {
                        Text("취소", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(12.dp))
                }
                PrimaryButton(
                    onClick = {
                        if (hasWarnings && !confirming) confirming = true
                        else { confirming = false; onExport() }
                    },
                    enabled = !busy && summary != null,
                    shape = ControlShape,
                    modifier = Modifier.heightIn(min = 72.dp),
                ) {
                    Text(if (busy) "만드는 중" else if (confirming) "그래도 만들기" else "운행기록부 PDF 만들기", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(EXPORT_NOTICE, style = MaterialTheme.typography.bodyMedium, color = colors.outline)
        }
    }
}

@Composable
private fun Figure(label: String, km: String, small: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                km,
                style = (if (small) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium).tabular(),
                color = if (small) colors.onSurfaceVariant else colors.onBackground,
            )
            if (km != "-") {
                Spacer(Modifier.width(4.dp))
                Text("km", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}
