package com.autotrip.ui.purpose

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autotrip.domain.model.TripPurpose
import com.autotrip.ui.theme.AutoTripTheme
import com.autotrip.ui.trip.TripRowUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PendingPurposeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens")

    private val items = listOf(
        PendingTripUi(
            TripRowUi(4, "11.12(목)", "11:10 – 11:41", "목적 미입력", "판교 테크노밸리", "-", "18.8", emptyList()),
            TripSummaryText("18.8", "45,250.1 → 45,268.9 km", "11.12(목) 11:10 – 11:41 · 판교 테크노밸리"),
        ),
        PendingTripUi(
            TripRowUi(7, "11.11(수)", "15:02 – 15:40", "목적 미입력", null, "-", "-", emptyList()),
            TripSummaryText(null, null, "11.11(수) 15:02 – 15:40"),
        ),
    )

    private fun capture(name: String, selected: Long?) {
        compose.setContent {
            AutoTripTheme(darkTheme = false) { PendingPurposeScreen(items, selected, null, true, {}, {}) }
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun list() = capture("pending-list", null)
    @Test fun selecting() = capture("pending-select", 4)

    @Test
    fun 목적_버튼을_누르면_그_목적이_전달된다() {
        var chosen: TripPurpose? = null
        compose.setContent {
            AutoTripTheme(darkTheme = false) { PendingPurposeScreen(items, 4, null, true, {}, { chosen = it }) }
        }
        compose.onNodeWithText("회의").performClick()
        assertEquals(TripPurpose.MEETING, chosen)
    }
}
