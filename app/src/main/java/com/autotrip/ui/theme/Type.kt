package com.autotrip.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 차량 화면용 글자 크기. 운전석에서 팔 길이 거리로 읽는 것을 기준으로 휴대폰보다 크게 잡았다.
 * - display: 주행 중 상태 표시처럼 한눈에 읽어야 하는 큰 글씨
 * - headline/title: 화면 제목, 질문 문구
 * - body/label: 목록, 버튼
 * 굵기는 400·500을 쓰고, 큰 제목만 600. 위계는 굵기보다 크기와 색으로 만든다.
 */
val Typography = Typography(
    displayMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 60.sp),
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 48.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 34.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
)
