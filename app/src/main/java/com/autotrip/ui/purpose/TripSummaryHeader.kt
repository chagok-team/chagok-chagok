package com.autotrip.ui.purpose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 이번 운행 요약 (화면용 문자열).
 * 실제 데이터 연결 후에는 Trip의 odometerStart/End, drivenMeters, endedAt을 DisplayFormat으로 바꿔 채운다.
 * 계기판 값을 읽지 못한 운행은 [drivenKm]을 null로 둔다 (0이나 추정값으로 채우지 않는다).
 */
data class TripSummaryText(
    val drivenKm: String?,         // "17.4", 계기판 실패면 null
    val odometerRange: String?,    // "45,275.8 → 45,293.2 km"
    val arrivedAt: String?,        // "14:32 도착"
)

/** 오도미터처럼 숫자 폭을 고정한다 (tabular figures) */
private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

/** 이번 운행을 큰 숫자 하나로: "17.4 km" + 계기판 값·도착 시각. 이 화면의 시그니처. */
@Composable
internal fun TripSummaryHeader(trip: TripSummaryText, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                trip.drivenKm ?: "-",
                style = MaterialTheme.typography.displayMedium
                    .copy(fontSize = 72.sp, lineHeight = 72.sp, fontWeight = FontWeight.Medium)
                    .tabular(),
                color = colors.onBackground,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "km",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        val meta = listOfNotNull(trip.odometerRange, trip.arrivedAt).joinToString("  ·  ")
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(meta, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.outline)
        }
    }
}
