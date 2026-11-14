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
import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.TripPurpose
import com.autotrip.ui.theme.AutoTripTheme
import com.autotrip.voice.ButtonReason
import com.autotrip.voice.PurposePromptState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 목적 질문 화면을 상태별로 그려 PNG로 남긴다 (앱 files/screens 폴더).
 * 꺼내기: adb exec-out run-as com.autotrip --user 10 cat files/screens/<이름>.png > <이름>.png
 */
@RunWith(AndroidJUnit4::class)
class PurposePromptScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens")

    private val sampleTrip = TripSummaryText(
        drivenKm = "17.4",
        odometerRange = "45,275.8 → 45,293.2 km",
        arrivedAt = "14:32 도착",
    )
    private val ambiguous =
        PurposePromptState.AwaitingButton(ButtonReason.AMBIGUOUS, setOf(TripPurpose.CLIENT_VISIT, TripPurpose.MEETING))

    private fun capture(
        name: String,
        state: PurposePromptState,
        buttonsAllowed: Boolean = true,
        dark: Boolean = false,
        trip: TripSummaryText? = null,
    ) {
        compose.setContent {
            AutoTripTheme(darkTheme = dark) {
                PurposePromptContent(state, buttonsAllowed, onSelectPurpose = {}, onDismiss = {}, trip = trip)
            }
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun buttonsQuiet() = capture(
        "5c-buttons-quiet",
        PurposePromptState.AwaitingButton(ButtonReason.QUIET_MODE, setOf(TripPurpose.CLIENT_VISIT)),
    )
    @Test fun suggestingClient() = capture("0-suggest-client", PurposePromptState.SuggestingClient("현대오토에버 판교"))
    @Test fun suggestingPrevious() = capture("0c-suggest-previous", PurposePromptState.SuggestingPrevious(TripPurpose.MEETING))
    @Test fun listeningYesNo() = capture("0b-listening-yes-no", PurposePromptState.Listening(0, ""))
    @Test fun asking() = capture("1-asking", PurposePromptState.Asking(1))
    @Test fun listening() = capture("2-listening", PurposePromptState.Listening(1, "거래처 방"))
    @Test fun preparing() = capture("2a-preparing", PurposePromptState.Listening(1, "", ready = false))
    @Test fun confirming() = capture("3-confirming", PurposePromptState.Confirming(TripPurpose.CLIENT_VISIT))
    @Test fun completed() = capture("4-completed", PurposePromptState.Completed(TripPurpose.CLIENT_VISIT, InputMethod.VOICE))
    @Test fun arrivalNotice() = capture("9-arrival-notice", PurposePromptState.ArrivalNotice(TripPurpose.CLIENT_VISIT), dark = true)
    @Test fun buttonsAmbiguous() = capture("5-buttons-ambiguous", ambiguous)
    @Test fun buttonsWithTripLight() = capture("7-buttons-trip-light", ambiguous, trip = sampleTrip)
    @Test fun buttonsWithTripDark() = capture("8-buttons-trip-dark", ambiguous, dark = true, trip = sampleTrip)
    @Test fun buttonsWhileDriving() = capture(
        "6-buttons-while-driving",
        PurposePromptState.AwaitingButton(ButtonReason.NOT_UNDERSTOOD, emptySet()),
        buttonsAllowed = false,
    )

    @Test
    fun 버튼을_누르면_그_목적이_전달된다() {
        var selected: TripPurpose? = null
        compose.setContent {
            AutoTripTheme(darkTheme = false) {
                PurposePromptContent(
                    PurposePromptState.AwaitingButton(ButtonReason.NOT_UNDERSTOOD, emptySet()),
                    buttonsAllowed = true,
                    onSelectPurpose = { selected = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("출퇴근").performClick()
        assertEquals(TripPurpose.COMMUTE, selected)
    }
}
