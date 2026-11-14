package com.autotrip.ui.home

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autotrip.ui.theme.AutoTripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens")

    private val idle = HomeUi(
        statusTitle = "대기 중", statusDetail = "출발하면 자동으로 기록해요", driving = false,
        periodLabel = "사업연도", periodText = "2026.01.01 ~ 2026.12.31",
        ratioText = "85.7%", totalKm = "793.3", businessKm = "679.5",
        pendingCount = 2, profileMissing = true, manualAction = ManualAction.START,
    )
    private val driving = idle.copy(statusTitle = "주행 중", statusDetail = "09:12 출발", driving = true, manualAction = ManualAction.END)

    private fun capture(name: String, ui: HomeUi, browsing: Boolean, dark: Boolean = false, quiet: QuietModeControl? = null) {
        compose.setContent {
            AutoTripTheme(darkTheme = dark) { HomeScreen(ui, null, browsing, {}, HomeNavigation(), quietMode = quiet) }
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun idle() = capture("home-idle", idle, true)
    @Test fun drivingRestricted() = capture("home-driving", driving, false, dark = true)
    @Test fun quietOn() = capture("home-quiet", idle, true, quiet = QuietModeControl(true) {})
    @Test fun quietOnNight() = capture("home-quiet-night", idle, true, dark = true, quiet = QuietModeControl(true) {})
    @Test fun quietWhileDriving() = capture("home-quiet-driving", driving, false, dark = true, quiet = QuietModeControl(true) {})

    @Test
    fun 조용한_모드_줄을_누르면_켜지고_꺼진다() {
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            var on by remember { mutableStateOf(false) }
            AutoTripTheme(darkTheme = false) {
                HomeScreen(idle, null, true, {}, HomeNavigation(), quietMode = QuietModeControl(on) { on = it; changes += it })
            }
        }
        compose.onNodeWithText(QUIET_MODE_LABEL).performClick()
        compose.onNodeWithText(QUIET_MODE_LABEL).performClick()
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun 주행_중에는_조용한_모드_스위치를_숨긴다() {
        compose.setContent {
            AutoTripTheme(darkTheme = false) {
                HomeScreen(driving, null, false, {}, HomeNavigation(), quietMode = QuietModeControl(true) {})
            }
        }
        compose.onNodeWithText(QUIET_MODE_LABEL).assertDoesNotExist()
        compose.onNodeWithText(QUIET_WHILE_DRIVING).assertExists()
    }

    @Test
    fun 버튼이_각_화면으로_이어진다() {
        val opened = mutableListOf<String>()
        var manual: ManualAction? = null
        compose.setContent {
            AutoTripTheme(darkTheme = false) {
                HomeScreen(
                    idle, null, true, { manual = it },
                    HomeNavigation(
                        onOpenTrips = { opened += "trips" }, onOpenPending = { opened += "pending" },
                        onOpenExport = { opened += "export" }, onOpenSettings = { opened += "settings" },
                    ),
                )
            }
        }
        compose.onNodeWithText("운행 기록").performClick()
        compose.onNodeWithText("목적 입력").performClick()
        compose.onNodeWithText("운행기록부 출력").performClick()
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("수동으로 운행 시작").performClick()
        assertEquals(listOf("trips", "pending", "export", "settings"), opened)
        assertEquals(ManualAction.START, manual)
    }
}
