package com.autotrip.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autotrip.ui.theme.ControlShape

/**
 * 시연용 버튼 묶음 (CLAUDE.md 4장: FakeTripTracker가 있을 때만 보여준다).
 * Fake에 직접 의존하지 않고 동작만 받는다. 연결 예 (A의 Fake가 main에 들어온 뒤, MainActivity):
 * ```
 * val fake = container.tripTracker as? FakeTripTracker
 * debugContent = fake?.let { f -> {
 *     DemoControls(DemoActions(
 *         drive = { f.simulateDrive(5_000) }, arrive = { f.simulateArrive() },
 *         setDestination = { name -> f.simulateSetDestination(name) },
 *         failNextOdometer = { f.setOdometerFailure(onStart = false, onEnd = true) },
 *     ))
 * } }
 * ```
 */
data class DemoActions(
    val drive: () -> Unit,
    val arrive: () -> Unit,
    val setDestination: (String) -> Unit,
    val failNextOdometer: () -> Unit,
)

/** 시연 대본(시연영상_시나리오)에 나오는 목적지. 첫 번째는 Fake 샘플 거래처와 같은 이름을 담아야 제안이 나온다. */
val DEMO_DESTINATIONS = listOf("현대오토에버 판교캠퍼스", "본사 대회의실")

@Composable
fun DemoControls(
    actions: DemoActions,
    modifier: Modifier = Modifier,
    destinations: List<String> = DEMO_DESTINATIONS,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("시연", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
        destinations.forEach { name -> DemoButton("목적지: $name") { actions.setDestination(name) } }
        DemoButton("주행 5km", actions.drive)
        DemoButton("도착", actions.arrive)
        DemoButton("다음 도착 계기판 실패", actions.failNextOdometer)
    }
}

@Composable
private fun DemoButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = ControlShape, modifier = Modifier.heightIn(min = 64.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
