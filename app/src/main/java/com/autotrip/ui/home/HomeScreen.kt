package com.autotrip.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autotrip.ui.theme.AppCard
import com.autotrip.ui.theme.ControlShape
import com.autotrip.ui.theme.PrimaryButton

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

/** 대시보드에서 다른 화면으로 가는 길 */
data class HomeNavigation(
    val onOpenTrips: () -> Unit = {},
    val onOpenPending: () -> Unit = {},
    val onOpenExport: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenReview: () -> Unit = {},
)

/** 조용한 모드 스위치 (동승자가 있을 때 음성 질문·안내를 끈다) */
data class QuietModeControl(val enabled: Boolean, val onChange: (Boolean) -> Unit)

/**
 * 메인 대시보드 (현대 CI 톤: Light Sand 바탕 위 흰 카드 두 장).
 * - 왼쪽 카드: 지금 운행 상태(크게)와 수동 시작·종료. 오른쪽 카드: 올해 ⑬ 업무사용비율과 ⑪ ⑫.
 * - 주행 중(browsingAllowed = false)에는 상태와 숫자만 남기고 버튼을 모두 숨긴다.
 * @param debugContent Fake 모드 시연 버튼 자리 (FakeTripTracker가 있을 때만 넘긴다)
 */
@Composable
fun HomeScreen(
    ui: HomeUi?,
    message: String?,
    browsingAllowed: Boolean,
    onManual: (ManualAction) -> Unit,
    navigation: HomeNavigation,
    modifier: Modifier = Modifier,
    debugContent: (@Composable () -> Unit)? = null,
    quietMode: QuietModeControl? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = colors.background) {
        if (ui == null) return@Surface
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AppCard(Modifier.weight(1.2f).fillMaxHeight(), contentPadding = CardPadding) {
                    StatusPanel(ui, message, browsingAllowed, onManual, quietMode)
                }
                AppCard(Modifier.weight(1f).fillMaxHeight(), contentPadding = CardPadding) {
                    MileagePanel(ui)
                }
            }
            if (browsingAllowed) {
                Spacer(Modifier.height(16.dp))
                Notices(ui, navigation)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    NavButton("운행 기록", navigation.onOpenTrips, Modifier.weight(1f))
                    NavButton("운행기록부 출력", navigation.onOpenExport, Modifier.weight(1f))
                    NavButton("설정", navigation.onOpenSettings, Modifier.weight(1f))
                }
                if (debugContent != null) {
                    Spacer(Modifier.height(16.dp))
                    debugContent()
                }
            }
        }
    }
}

private val CardPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 40.dp, vertical = 32.dp)

@Composable
private fun StatusPanel(
    ui: HomeUi,
    message: String?,
    browsingAllowed: Boolean,
    onManual: (ManualAction) -> Unit,
    quietMode: QuietModeControl?,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("지금 상태", style = MaterialTheme.typography.titleMedium, color = colors.outline)
        Spacer(Modifier.height(4.dp))
        Text(
            ui.statusTitle,
            style = MaterialTheme.typography.displayMedium.copy(fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.Medium),
            // 주행 중은 "살아 있는 상태"라 Active Blue
            color = if (ui.driving) colors.tertiary else colors.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(ui.statusDetail, style = MaterialTheme.typography.titleLarge.tabular(), color = colors.onSurfaceVariant)
        if (!browsingAllowed) {
            Spacer(Modifier.height(24.dp))
            Text(
                if (quietMode?.enabled == true) QUIET_WHILE_DRIVING else "주행 중에는 음성으로만 안내해요",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.outline,
            )
        }
        val action = ui.manualAction
        if (browsingAllowed && action != null) {
            Spacer(Modifier.height(32.dp))
            PrimaryButton(
                onClick = { onManual(action) },
                shape = ControlShape,
                modifier = Modifier.width(360.dp).heightIn(min = 88.dp),
            ) { Text(action.label, style = MaterialTheme.typography.titleLarge) }
        }
        // 켜고 끄는 것은 정차 중에만 (주행 중 입력 요소 숨김)
        if (browsingAllowed && quietMode != null) {
            Spacer(Modifier.height(24.dp))
            QuietModeRow(quietMode)
        }
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.primary)
        }
    }
}

internal const val QUIET_MODE_LABEL = "조용한 모드"
internal const val QUIET_MODE_DETAIL = "동승자가 있을 때 음성 대신 화면 버튼으로 목적을 받아요"
internal const val QUIET_WHILE_DRIVING = "조용한 모드 · 음성 안내를 하지 않아요"

/** 줄 전체를 눌러도 켜지고 꺼진다 (차량 화면이라 누를 영역을 넓게) */
@Composable
private fun QuietModeRow(control: QuietModeControl) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .width(560.dp)
            .heightIn(min = 72.dp)
            .toggleable(value = control.enabled, role = Role.Switch, onValueChange = control.onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(QUIET_MODE_LABEL, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Text(QUIET_MODE_DETAIL, style = MaterialTheme.typography.bodyMedium, color = colors.outline)
        }
        Spacer(Modifier.width(16.dp))
        // 줄 전체가 스위치 역할을 하므로 스위치 자체의 클릭은 끈다 (두 번 토글되지 않게)
        Switch(checked = control.enabled, onCheckedChange = null)
    }
}

@Composable
private fun MileagePanel(ui: HomeUi) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("${ui.periodLabel} ${ui.periodText}", style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.outline)
        Spacer(Modifier.height(12.dp))
        Text("⑬ 업무사용비율", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
        Text(
            ui.ratioText,
            style = MaterialTheme.typography.displayMedium.copy(fontSize = 88.sp, lineHeight = 96.sp, fontWeight = FontWeight.Medium).tabular(),
            color = colors.primary,
        )
        ui.ratio?.let { RatioBar(it) }
        Spacer(Modifier.height(20.dp))
        MileageRow("⑪ 총주행거리", kmWithUnit(ui.totalKm))
        MileageRow("⑫ 업무용 사용거리", kmWithUnit(ui.businessKm))
    }
}

/** ⑬을 막대로: Sand 바탕 위 Active Blue (A가 계산한 비율을 그대로 그린다) */
@Composable
private fun RatioBar(ratio: Float) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).background(colors.outlineVariant, shape)) {
        Box(Modifier.fillMaxWidth(ratio).height(8.dp).background(colors.tertiary, shape))
    }
}

@Composable
private fun MileageRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.outline, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleLarge.tabular(), color = colors.onSurface)
    }
}

@Composable
private fun Notices(ui: HomeUi, navigation: HomeNavigation) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.pendingCount > 0) {
            NoticeRow("목적 미입력 ${ui.pendingCount}건", "목적 입력", colors.primary, navigation.onOpenPending)
        }
        if (ui.reviewCount > 0) {
            NoticeRow("확인이 필요한 기록 ${ui.reviewCount}건", "보기", colors.onSurfaceVariant, navigation.onOpenReview)
        }
        if (ui.profileMissing) {
            NoticeRow("운행기록부를 만들려면 사업자·차량 정보가 필요해요", "정보 입력", colors.onSurfaceVariant, navigation.onOpenSettings)
        }
    }
}

@Composable
private fun NoticeRow(text: String, action: String, color: Color, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = color)
        Spacer(Modifier.width(16.dp))
        TextButton(onClick = onClick, shape = ControlShape, modifier = Modifier.heightIn(min = 64.dp)) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 흰 카드 모양 버튼 (화면당 채운 버튼은 하나만 두고 나머지는 이 모양) */
@Composable
private fun NavButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        shape = ControlShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = colors.onSurface),
        elevation = null,
        modifier = modifier.heightIn(min = 88.dp),
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
}
