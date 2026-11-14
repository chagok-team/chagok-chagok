package com.autotrip

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 실제 앱(MainActivity + AutoTripApplication.container)을 띄워 화면이 뜨고 이동되는지만 본다.
 * 데이터를 쓰지 않는다(운행 시작·저장 버튼은 누르지 않음).
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens")

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun 실제_앱이_뜨고_화면을_오간다() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("운행기록부 출력").fetchSemanticsNodes().isNotEmpty() }
        capture("real-home")
        compose.onNodeWithText("설정").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("자주 가는 거래처").fetchSemanticsNodes().isNotEmpty() }
        capture("real-settings")
        compose.onNodeWithText("← 홈").performClick()
        compose.onNodeWithText("운행기록부 출력").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("운행기록부 PDF 만들기").fetchSemanticsNodes().isNotEmpty() }
        capture("real-export")
    }
}
