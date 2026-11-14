package com.autotrip.ui.settings

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autotrip.domain.model.BusinessProfile
import com.autotrip.domain.model.BusinessType
import com.autotrip.domain.model.TripUser
import com.autotrip.domain.model.ValueSource
import com.autotrip.domain.model.VehicleInfo
import com.autotrip.ui.theme.AutoTripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 설정 화면을 샘플 값으로 그려 PNG로 남긴다 (앱 files/screens 폴더). */
@RunWith(AndroidJUnit4::class)
class SettingsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens")

    private val filled = SettingsSaved(
        business = BusinessProfile(BusinessType.CORPORATION, "(주)오토트립 시연", "123-45-67890"),
        vehicle = VehicleInfo(1, "아이오닉 5", ValueSource.SDK, "12가 3456"),
        users = listOf(TripUser(1, "영업팀", "김현대"), TripUser(2, "관리팀", "박제네")),
        defaultUserId = 1,
        clients = listOf(
            com.autotrip.domain.model.Client(1, "현대오토에버 판교", null),
            com.autotrip.domain.model.Client(2, "현대모비스 의왕연구소", null),
        ),
    )

    private fun capture(name: String, saved: SettingsSaved?, outcome: SettingsActions.Outcome? = null) {
        compose.setContent {
            AutoTripTheme(darkTheme = false) {
                SettingsScreen(saved, true, outcome, { _, _, _ -> }, { _, _ -> }, { _, _ -> }, {})
            }
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun filled() = capture("settings-filled", filled, SettingsActions.Outcome.Saved("차량 정보를 저장했어요"))

    /** 실제 모드: 마이크 허용, 차량 속도·위치 꺼짐 + 허용 창이 안 뜰 때 안내 */
    @Test fun permissions() {
        val declared = setOf(
            "android.permission.RECORD_AUDIO", "android.car.permission.CAR_SPEED",
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
        )
        val requested = mutableListOf<com.autotrip.ui.permission.AppPermission>()
        compose.setContent {
            AutoTripTheme(darkTheme = false) {
                SettingsScreen(
                    filled, true, null, { _, _, _ -> }, { _, _ -> }, { _, _ -> }, {},
                    permissions = com.autotrip.ui.permission.PermissionRules.rows(
                        isDemo = false, granted = setOf(com.autotrip.ui.permission.AppPermission.MICROPHONE), declared = declared,
                    ),
                    permissionHint = com.autotrip.ui.permission.PermissionRules.BLOCKED_HINT,
                    onRequestPermission = { requested += it },
                )
            }
        }
        compose.onNodeWithText("권한").performScrollTo()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "settings-permissions.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onAllNodesWithText("허용")[0].performScrollTo().performClick()
        assertEquals(listOf(com.autotrip.ui.permission.AppPermission.VEHICLE_SPEED), requested)
    }

    @Test fun empty() = capture(
        "settings-empty",
        SettingsSaved(null, null, emptyList(), null),
        SettingsActions.Outcome.Rejected("사업자등록번호는 숫자 10자리예요"),
    )

    @Test
    fun 저장_버튼은_입력값을_넘긴다() {
        var saved: Triple<BusinessType, String, String>? = null
        compose.setContent {
            AutoTripTheme(darkTheme = false) {
                SettingsScreen(filled, true, null, { t, n, r -> saved = Triple(t, n, r) }, { _, _ -> }, { _, _ -> }, {})
            }
        }
        compose.onNodeWithText("개인사업자").performClick()
        compose.onNodeWithText("사업자 정보 저장").performClick()
        assertEquals(Triple(BusinessType.SOLE_PROPRIETOR, "(주)오토트립 시연", "123-45-67890"), saved)
    }
}
