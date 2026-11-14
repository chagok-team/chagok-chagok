package com.autotrip.ui.restriction

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 에뮬레이터(정차 상태)에서 차량 서비스에 연결해 제한이 풀리는지 확인한다 */
@RunWith(AndroidJUnit4::class)
class CarDrivingRestrictionMonitorTest {

    @Test
    fun 정차_중에는_제한이_풀린다() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val monitor = CarDrivingRestrictionMonitor(context)
        withContext(Dispatchers.Main) { monitor.start() }
        try {
            val restricted = withTimeout(5_000) { monitor.restricted.first { !it } }
            assertEquals(false, restricted)
        } finally {
            withContext(Dispatchers.Main) { monitor.stop() }
        }
    }
}
