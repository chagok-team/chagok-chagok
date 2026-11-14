package com.autotrip.ui.review

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
import com.autotrip.ui.trip.TripRowUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReviewScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens")

    private val items = listOf(
        ReviewItemUi(
            TripRowUi(7, "11.12(목)", "15:02 – 15:40", "사업장 방문", null, "영업팀 김현대", "23.4", emptyList()),
            listOf(ReviewReason.MISSING_DESTINATION, ReviewReason.ODOMETER_GAP),
            listOf("앞 운행 도착 45,268.9 km → 이번 출발 45,280.0 km"),
        ),
        ReviewItemUi(
            TripRowUi(4, "11.11(수)", "11:10 – 11:41", "목적 미입력", "판교 테크노밸리", "-", "18.8", emptyList()),
            listOf(ReviewReason.PURPOSE_PENDING),
            emptyList(),
        ),
        ReviewItemUi(
            TripRowUi(2, "11.02(월)", "09:20 – 09:58", "회의", "본사", "관리팀 박제네", "12.3", emptyList()),
            listOf(ReviewReason.LATE_INPUT),
            listOf("운행 종료 9일 뒤 입력"),
        ),
    )

    private fun capture(name: String, dark: Boolean) {
        compose.setContent { AutoTripTheme(darkTheme = dark) { ReviewScreen(items, true, {}) } }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun light() = capture("review-light", dark = false)
    @Test fun night() = capture("review-night", dark = true)

    @Test
    fun 줄을_누르면_그_운행으로_간다() {
        var clicked: Long? = null
        compose.setContent { AutoTripTheme(darkTheme = false) { ReviewScreen(items, true, { clicked = it }) } }
        compose.onNodeWithText("목적 미입력  ·  판교 테크노밸리").performClick()
        assertEquals(4L, clicked)
    }
}
