package com.autotrip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 현대 CI 톤 (주간: Light Sand 바탕 + 흰 카드 + Hyundai Blue).
 * - background: 화면 바탕 / surface: 카드 / surfaceVariant: 작은 표시·배너
 * - 글자 역할 세 가지: onSurface(본문), onSurfaceVariant(보조), outline(흐린 메타)
 * - outlineVariant: 구분선 (주간 Sand)
 * - tertiary: 살아 있는 상태(주행 중·듣는 중·진행 막대)에만 쓰는 Active Blue
 */
private val LightColors = lightColorScheme(
    primary = HyundaiCi.Blue,
    onPrimary = Color.White,
    secondary = HyundaiCi.Blue,
    onSecondary = Color.White,
    tertiary = HyundaiCi.ActiveBlue,
    onTertiary = Color.White,
    background = HyundaiCi.LightSand,
    onBackground = Graphite.LightTextPrimary,
    surface = Color.White,
    onSurface = Graphite.LightTextPrimary,
    surfaceVariant = HyundaiCi.Sand,
    onSurfaceVariant = Graphite.LightTextSecondary,
    outline = Graphite.LightTextMuted,
    outlineVariant = HyundaiCi.Sand,
    error = HyundaiCi.ActiveRed,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = HyundaiCi.SkyBlue,
    onPrimary = HyundaiCi.Blue,
    secondary = HyundaiCi.SkyBlue,
    onSecondary = HyundaiCi.Blue,
    tertiary = HyundaiCi.ActiveBlue,
    onTertiary = Graphite.DarkBackground,
    background = Graphite.DarkBackground,
    onBackground = Graphite.DarkTextPrimary,
    surface = Graphite.DarkCard,
    onSurface = Graphite.DarkTextPrimary,
    surfaceVariant = Graphite.DarkRaised,
    onSurfaceVariant = Graphite.DarkTextSecondary,
    outline = Graphite.DarkTextMuted,
    outlineVariant = Graphite.DarkTextPrimary.copy(alpha = 0.08f),
    error = NegativeDark,
    onError = Graphite.DarkBackground,
)

/**
 * 앱 테마. 차량 화면에서 색이 바뀌지 않도록 동적 색상(Material You)은 쓰지 않는다.
 * 주간·야간 모드는 시스템 설정(isSystemInDarkTheme)을 따른다.
 */
@Composable
fun AutoTripTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
