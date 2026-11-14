package com.autotrip.ui.purpose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.domain.repository.TripTracker
import com.autotrip.voice.PurposePromptCoordinator
import com.autotrip.voice.PurposePromptState
import com.autotrip.voice.QuietModeSetting
import com.autotrip.voice.STT_LOG_TAG
import android.util.Log
import com.autotrip.voice.SpeechInput
import com.autotrip.voice.SpeechOutput
import com.autotrip.voice.TripRepositoryPurposeGateway
import kotlinx.coroutines.flow.StateFlow

/**
 * 목적 질문 흐름을 앱 화면 생명주기에 묶는다.
 * - 만들 때 운행 상태·목적지 구독을 시작하고, 없어질 때 구독을 멈추고 TTS·STT를 release한다(CLAUDE.md 6장: initialize·release 짝).
 * - TTS·STT는 PLEOS 구현(PleosSpeechOutput·PleosSpeechInput)을 넘긴다. initialize는 처음 질문할 때 prepare()에서 한다.
 */
class PurposePromptViewModel(
    tracker: TripTracker,
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    private val speechOutput: SpeechOutput,
    private val speechInput: SpeechInput,
    private val quietMode: QuietModeSetting = QuietModeSetting(initial = false),
) : ViewModel() {

    private val coordinator = PurposePromptCoordinator(
        scope = viewModelScope,
        tripStates = tracker.state,
        destinations = tracker.destinationEvents,
        gateway = TripRepositoryPurposeGateway(tripRepository, profileRepository),
        speechOutput = speechOutput,
        speechInput = speechInput,
        quietMode = { quietMode.enabled.value },
        log = { Log.d(STT_LOG_TAG, it) },
    ).also { it.start() }

    val state: StateFlow<PurposePromptState> = coordinator.state

    /** 조용한 모드 (홈 화면에서 켜고 끈다) */
    val quietModeEnabled: StateFlow<Boolean> = quietMode.enabled

    /** 다음 질문부터 적용된다. (질문 중에는 화면이 홈을 덮고 있어서 켜고 끌 수 없다) */
    fun setQuietMode(enabled: Boolean) = quietMode.set(enabled)

    fun selectPurpose(purpose: TripPurpose) = coordinator.selectPurpose(purpose)

    /** 화면을 닫음 (완료·도착 안내를 치우거나 "나중에 입력") */
    fun dismiss() = coordinator.dismiss()

    override fun onCleared() {
        coordinator.stop()
        speechOutput.release()
        speechInput.release()
    }

    companion object {
        fun factory(
            tracker: TripTracker,
            tripRepository: TripRepository,
            profileRepository: ProfileRepository,
            speechOutput: () -> SpeechOutput,
            speechInput: () -> SpeechInput,
            quietMode: () -> QuietModeSetting = { QuietModeSetting(initial = false) },
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PurposePromptViewModel(tracker, tripRepository, profileRepository, speechOutput(), speechInput(), quietMode())
            }
        }
    }
}
