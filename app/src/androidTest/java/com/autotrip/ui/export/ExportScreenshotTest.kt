package com.autotrip.ui.export

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autotrip.ui.theme.AutoTripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 출력 화면을 샘플 값으로 그려 PNG로 남긴다 (앱 files/screens 폴더). */
@RunWith(AndroidJUnit4::class)
class ExportScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens")

    private val summary = ExportSummaryUi(
        periodLabel = "사업연도", periodText = "2026.01.01 ~ 2026.12.31",
        totalKm = "793.3", businessKm = "679.5", commuteKm = "197.3", generalBusinessKm = "482.2", ratioText = "85.7%",
        warnings = listOf(
            "목적 미입력 운행 1건은 업무용 사용거리(⑫)에 들어가지 않아요",
            "계기판 수집 실패 운행 1건은 총주행거리(⑪)에서 빠져요",
        ),
    )

    private fun capture(name: String, outcome: ExportActions.Outcome?) {
        compose.setContent {
            AutoTripTheme(darkTheme = false) {
                ExportScreen(2026, summary, false, outcome, true, {}, {}, {})
            }
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun summaryWithWarnings() = capture("export-summary", null)
    @Test fun created() = capture("export-created", ExportActions.Outcome.Created(File("logbook_20260101_20261231.pdf"), "운행 25건"))
    @Test fun needsProfile() = capture("export-needs-profile", ExportActions.Outcome.NeedsProfile)

    @Test
    fun 경고가_있으면_한_번_더_확인한_뒤에_만든다() {
        var exports = 0
        compose.setContent {
            AutoTripTheme(darkTheme = false) { ExportScreen(2026, summary, false, null, true, {}, { exports++ }, {}) }
        }
        compose.onNodeWithText("운행기록부 PDF 만들기").performClick()
        assertEquals("첫 번째 누름은 확인만", 0, exports)
        compose.onNodeWithText("그래도 만들기").performClick()
        assertEquals(1, exports)
    }
}
