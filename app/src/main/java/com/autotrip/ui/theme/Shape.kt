package com.autotrip.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 모서리 반경은 크기에 따라: 작은 표시 6, 버튼·입력 12, 카드 16, 패널 20.
 * 차량 화면 버튼은 크기(높이 88dp)가 커서 웹 기준(8~10)보다 조금 크게 잡았다.
 * Material3 버튼은 기본이 알약(완전 둥근) 모양이라 [ControlShape]를 직접 넘긴다.
 */
val ControlShape = RoundedCornerShape(12.dp)

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
