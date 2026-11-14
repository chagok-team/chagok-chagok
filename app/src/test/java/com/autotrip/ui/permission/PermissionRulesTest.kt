package com.autotrip.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRulesTest {

    private val all = setOf(
        "android.permission.RECORD_AUDIO",
        "android.car.permission.CAR_SPEED",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
    )

    @Test
    fun `시연 모드는 마이크만, 실제 모드는 속도·위치까지`() {
        assertEquals(listOf(AppPermission.MICROPHONE), PermissionRules.relevant(isDemo = true))
        assertEquals(AppPermission.values().toList(), PermissionRules.relevant(isDemo = false))
    }

    @Test
    fun `처음 실행 요청은 빠진 권한만, 위치는 정밀·대략을 함께`() {
        val names = PermissionRules.startupRequest(isDemo = false, granted = setOf(AppPermission.MICROPHONE), declared = all)
        assertEquals(
            listOf("android.car.permission.CAR_SPEED", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            names,
        )
        assertTrue(PermissionRules.startupRequest(isDemo = false, granted = AppPermission.values().toSet(), declared = all).isEmpty())
    }

    @Test
    fun `앱이 선언하지 않은 권한은 요청하지도 보여 주지도 않는다`() {
        val onlyMic = setOf("android.permission.RECORD_AUDIO")
        assertEquals(listOf("android.permission.RECORD_AUDIO"), PermissionRules.startupRequest(false, emptySet(), onlyMic))
        assertEquals(listOf(AppPermission.MICROPHONE), PermissionRules.rows(false, emptySet(), onlyMic).map { it.permission })
    }

    @Test
    fun `허용됨이면 용도, 꺼짐이면 영향을 보여 준다`() {
        val rows = PermissionRules.rows(isDemo = false, granted = setOf(AppPermission.MICROPHONE), declared = all)
            .associateBy { it.permission }
        assertEquals("허용됨", rows.getValue(AppPermission.MICROPHONE).stateText)
        assertEquals("운행 목적을 음성으로 답해요", rows.getValue(AppPermission.MICROPHONE).detailText)
        assertEquals("꺼짐", rows.getValue(AppPermission.LOCATION).stateText)
        assertEquals("출발지·도착지가 '-'로 남아요", rows.getValue(AppPermission.LOCATION).detailText)
        assertEquals("출발 전에 홈의 '수동으로 운행 시작'을 눌러 주세요", rows.getValue(AppPermission.VEHICLE_SPEED).detailText)
    }

    @Test
    fun `위치 허용은 정밀 위치 기준 (A의 GPS가 확인하는 권한)`() {
        assertEquals("android.permission.ACCESS_FINE_LOCATION", AppPermission.LOCATION.requiredName)
    }
}
