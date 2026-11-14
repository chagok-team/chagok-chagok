package com.autotrip.ui.restriction

import org.junit.Assert.assertEquals
import org.junit.Test

class DrivingRestrictionStateTest {

    @Test
    fun `연결 전에는 제한된 상태로 시작한다`() {
        assertEquals(true, DrivingRestrictionState().restricted.value)
    }

    @Test
    fun `정차 값이 오면 풀리고 주행 값이 오면 다시 제한한다`() {
        val state = DrivingRestrictionState()
        state.onRestrictions(false)
        assertEquals(false, state.restricted.value)
        state.onRestrictions(true)
        assertEquals(true, state.restricted.value)
    }

    @Test
    fun `값을 못 읽으면 직전 상태를 유지한다`() {
        val state = DrivingRestrictionState()
        state.onRestrictions(false)
        state.onRestrictions(null)
        assertEquals(false, state.restricted.value)
    }

    @Test
    fun `차량 서비스가 없는 기기에서는 제한하지 않는다`() {
        val state = DrivingRestrictionState()
        state.onCarUnavailable()
        assertEquals(false, state.restricted.value)
    }
}
