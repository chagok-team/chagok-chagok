package com.autotrip.ui.restriction

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * 주행 중 UI 제한 (CLAUDE.md 4장): CarUxRestrictionsManager를 구독해
 * isRequiresDistractionOptimization()이 true면 입력 요소·목록 스크롤·수정 화면을 숨긴다.
 * 화면들은 browsingAllowed(= !restricted) 하나만 받는다.
 */

/** 주행 제한 상태 규칙 (Android 의존 없음, 단위 테스트 대상) */
class DrivingRestrictionState {
    // 차량 서비스에 연결되기 전에는 주행 중일 수도 있으므로 제한된 상태로 시작한다
    private val _restricted = MutableStateFlow(true)
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    /** 연결 직후 현재 값, 이후 변경 알림 모두 여기로 */
    fun onRestrictions(requiresDistractionOptimization: Boolean?) {
        // 값을 못 읽었으면 직전 상태를 유지한다
        if (requiresDistractionOptimization != null) _restricted.value = requiresDistractionOptimization
    }

    /** 차량 서비스가 없는 기기(차량이 아닌 기기·단위 테스트)에서는 제한하지 않는다 */
    fun onCarUnavailable() {
        _restricted.value = false
    }
}

/** 화면 생명주기에 맞춰 start/stop을 짝지어 부른다 */
class CarDrivingRestrictionMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val state = DrivingRestrictionState()
    val restricted: StateFlow<Boolean> = state.restricted

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
        state.onRestrictions(restrictions?.isRequiresDistractionOptimization)
    }

    fun start() {
        if (car != null) return
        car = try {
            // DO_NOT_WAIT: 연결되면 콜백으로 알려 준다 (메인 스레드)
            Car.createCar(appContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (ready) attach(connected) else detach()
            }
        } catch (e: Exception) {
            Log.w(TAG, "차량 서비스에 연결하지 못함", e)
            null
        }
        if (car == null) state.onCarUnavailable()
    }

    fun stop() {
        detach()
        car?.disconnect()
        car = null
    }

    private fun attach(connected: Car) {
        val m = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        if (m == null) {
            state.onCarUnavailable()
            return
        }
        manager = m
        state.onRestrictions(m.currentCarUxRestrictions?.requiresDO())
        m.registerListener(listener)
    }

    /** 서비스가 끊기면 리스너만 정리하고 마지막 값을 유지한다 */
    private fun detach() {
        manager?.unregisterListener()
        manager = null
    }

    private fun CarUxRestrictions.requiresDO() = isRequiresDistractionOptimization

    private companion object {
        const val TAG = "DrivingRestriction"
    }
}

/** 정차 중이면 true. 화면에 들어올 때 구독하고 나갈 때 해제한다. */
@Composable
fun rememberBrowsingAllowed(): Boolean {
    val context = LocalContext.current
    val monitor = remember { CarDrivingRestrictionMonitor(context) }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.stop() }
    }
    val restricted: State<Boolean> = monitor.restricted.collectAsState()
    return !restricted.value
}
