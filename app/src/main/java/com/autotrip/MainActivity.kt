package com.autotrip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.autotrip.fake.FakeTripTracker
import com.autotrip.ui.navigation.AutoTripNavHost
import com.autotrip.ui.navigation.DemoActions
import com.autotrip.ui.navigation.DemoControls
import com.autotrip.ui.theme.AutoTripTheme
import com.autotrip.voice.pleos.PleosSpeechInput
import com.autotrip.voice.pleos.PleosSpeechOutput

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AutoTripApplication).container
        // 시연 버튼은 Fake일 때만 (CLAUDE.md 4장). 실제 모드(Room)에서는 null이라 버튼이 숨는다.
        val fake = container.tripTracker as? FakeTripTracker

        setContent {
            AutoTripTheme {
                AutoTripNavHost(
                    tripTracker = container.tripTracker,
                    tripRepository = container.tripRepository,
                    profileRepository = container.profileRepository,
                    reportRepository = container.reportRepository,
                    isDemo = BuildConfig.USE_FAKE_DATA,
                    speechOutput = { PleosSpeechOutput(applicationContext) },
                    speechInput = { PleosSpeechInput(applicationContext) },
                    debugContent = fake?.let { f ->
                        {
                            DemoControls(
                                DemoActions(
                                    // 정차 중이면 Fake가 새 운행(AUTO)을 시작하고, 주행 중이면 거리만 늘린다 (INTERFACES 7장)
                                    drive = { f.simulateDrive(DEMO_DRIVE_METERS) },
                                    arrive = { f.simulateArrive() },
                                    setDestination = { name -> f.simulateSetDestination(name) },
                                    failNextOdometer = { f.setOdometerFailure(onStart = false, onEnd = true) },
                                ),
                                destinations = DEMO_DESTINATIONS,
                            )
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val DEMO_DRIVE_METERS = 5_000L

        /**
         * 시연 목적지 (Fake 샘플 데이터 기준)
         * - "판교 데모 거래처 본관": 등록 거래처 "판교 데모 거래처"가 들어 있어 거래처 제안
         * - "회사": 샘플 운행에 있는 목적지라 "지난번처럼 ○○으로 기록할까요?" 제안
         * - "본사 대회의실": 처음 가는 곳이라 일반 질문
         */
        val DEMO_DESTINATIONS = listOf("판교 데모 거래처 본관", "회사", "본사 대회의실")
    }
}
