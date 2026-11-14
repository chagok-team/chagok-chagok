package com.autotrip.voice

import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.NavigationDestination
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.TripState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PurposePromptCoordinatorTest {

    private val t0 = Instant.parse("2026-11-13T00:00:00Z")
    private fun driving(id: Long) = TripState.Driving(id, TripSource.AUTO, t0)
    private fun arrived(id: Long) = TripState.Arrived(id, TripSource.AUTO, t0)
    private fun recognized(text: String) = ListenResult.Recognized(text)

    /** 테스트 하나를 위한 구성. 상태·목적지는 Channel로 넣어서 값이 합쳐지지 않게 한다. */
    private inner class Harness(
        scope: CoroutineScope,
        val output: FakeSpeechOutput = FakeSpeechOutput(),
        val input: FakeSpeechInput = FakeSpeechInput(),
        val gateway: FakeGateway = FakeGateway(),
        config: PurposePromptCoordinator.Config = PurposePromptCoordinator.Config(listenTimeoutMillis = 200),
        var quiet: Boolean = false,
    ) {
        var now: Instant = t0
        private val states = Channel<TripState>(Channel.UNLIMITED)
        private val destinations = Channel<NavigationDestination>(Channel.UNLIMITED)
        val coordinator = PurposePromptCoordinator(
            scope, states.receiveAsFlow(), destinations.receiveAsFlow(), gateway, output, input,
            config = config, clock = { now }, quietMode = { quiet },
        ).also { it.start() }

        suspend fun send(vararg values: TripState) {
            values.forEach { states.send(it) }
            delay(20)   // 수집 코루틴이 처리할 시간
        }

        suspend fun destination(name: String?) {
            destinations.send(NavigationDestination(name, null, now))
            delay(20)
        }

        suspend fun awaitState(predicate: (PurposePromptState) -> Boolean): PurposePromptState =
            withTimeout(2_000) { coordinator.state.first(predicate) }

        fun questions() = output.spoken.count { it == VoicePrompts.QUESTION }
    }

    // ── 규칙 ────────────────────────────────────────────

    @Test
    fun `도착 판정은 같은 운행의 Driving에서 Arrived로 바뀔 때만 참이다`() {
        val rule = { previous: TripState?, current: TripState -> PurposePromptCoordinator.observedArrival(previous, current) }
        assertEquals(1L, rule(driving(1), arrived(1)))
        assertNull("구독 직후 첫 값", rule(null, arrived(1)))
        assertNull("다른 운행", rule(driving(1), arrived(2)))
        assertNull("Arrived 유지", rule(arrived(1), arrived(1)))
    }

    @Test
    fun `목적지 이름에 거래처 이름이 들어 있으면 그 거래처, 여러 개면 가장 긴 이름`() {
        val clients = listOf("현대오토에버", "현대오토에버 판교", "현", "기아")
        assertEquals("현대오토에버 판교", PurposePromptCoordinator.matchClient("현대오토에버 판교캠퍼스", clients))
        assertEquals("현대오토에버", PurposePromptCoordinator.matchClient("현대오토에버 본사", clients))
        assertNull("한 글자 이름은 보지 않는다", PurposePromptCoordinator.matchClient("현충원", clients))
        assertNull(PurposePromptCoordinator.matchClient("판교역", clients))
    }

    // ── 질문 조건 ───────────────────────────────────────

    @Test
    fun `정차 중 목적지를 찍으면 묻고, 보관 목적으로 저장한다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(listOf(recognized("회의 가요"))))
        h.send(TripState.Idle)
        h.destination("본사 대회의실")

        assertEquals(
            PurposePromptState.Completed(TripPurpose.MEETING, InputMethod.VOICE),
            h.awaitState { it is PurposePromptState.Completed },
        )
        assertEquals(listOf(VoicePrompts.QUESTION, "회의로 기록할게요."), h.output.spoken)
        assertEquals(
            FakeGateway.Call(null, TripPurpose.MEETING, InputMethod.VOICE, "회의 가요"),
            h.gateway.calls.single(),
        )
        h.coordinator.stop()
    }

    @Test
    fun `출발 전 같은 목적지는 30분 안에 다시 묻지 않고, 다른 목적지로 바꾸면 다시 묻는다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(listOf(recognized("회의"), recognized("교육"), recognized("판촉"))))
        h.send(TripState.Idle)
        h.destination("본사")
        h.awaitState { it is PurposePromptState.Completed }

        h.now = t0.plus(Duration.ofMinutes(10))
        h.destination(" 본 사 ")   // 같은 곳 (공백만 다름)
        delay(100)
        assertEquals(1, h.questions())

        h.destination("판교")      // 다른 곳 → 다시 묻고 보관 목적을 대체
        h.awaitState { it == PurposePromptState.Completed(TripPurpose.TRAINING, InputMethod.VOICE) }
        assertEquals(2, h.questions())

        h.now = t0.plus(Duration.ofMinutes(41))
        h.destination("판교")      // 같은 곳이어도 30분이 지나면 다시 묻는다
        h.awaitState { it == PurposePromptState.Completed(TripPurpose.PROMOTION, InputMethod.VOICE) }
        assertEquals(listOf(TripPurpose.MEETING, TripPurpose.TRAINING, TripPurpose.PROMOTION), h.gateway.calls.map { it.purpose })
        h.coordinator.stop()
    }

    @Test
    fun `묻는 중에 출발 전 목적지를 바꾸면 새 목적지로 처음부터 다시 묻는다`() = runBlocking {
        val h = Harness(
            this,
            input = FakeSpeechInput(listOf(null, recognized("회의"))),   // 첫 듣기는 답 없음
            gateway = FakeGateway(clients = listOf("기아 본사")),
            config = PurposePromptCoordinator.Config(listenTimeoutMillis = 10_000),
        )
        h.send(TripState.Idle)
        h.destination("판교역")
        h.awaitState { it is PurposePromptState.Listening }

        h.destination("기아 본사")   // 거래처로 바꿈 → 거래처 제안부터 다시
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(
            listOf(VoicePrompts.QUESTION, "기아 본사 방문으로 기록할까요?", "회의로 기록할게요."),
            h.output.spoken,
        )
        assertTrue("묻던 듣기를 닫는다", h.input.stopCount >= 1)
        assertEquals(TripPurpose.MEETING, h.gateway.calls.single().purpose)
        h.coordinator.stop()
    }

    @Test
    fun `같은 목적지 판정은 공백·대소문자를 무시한다`() {
        assertTrue(PurposePromptCoordinator.sameDestination("기아 본사", "기아본사"))
        assertTrue(PurposePromptCoordinator.sameDestination(null, null))
        assertTrue(!PurposePromptCoordinator.sameDestination("기아 본사", null))
        assertTrue(!PurposePromptCoordinator.sameDestination("기아 본사", "기아 판교"))
    }

    @Test
    fun `출발 전에 받은 목적이 붙은 운행은 주행 중에 목적지를 바꿔도 다시 묻지 않는다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(listOf(recognized("거래처"))))
        h.send(TripState.Idle)
        h.destination("현대모비스")
        h.awaitState { it is PurposePromptState.Completed }

        h.send(driving(5))
        h.destination("다른 곳")
        delay(100)
        assertEquals(1, h.questions())
        h.coordinator.stop()
    }

    @Test
    fun `주행 중 처음 목적지를 찍으면 그 운행이 미입력일 때만 묻는다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(listOf(recognized("출근"))), gateway = FakeGateway(pendingTripIds = setOf(1L)))
        h.send(driving(2))   // 확정된 운행
        h.destination("본사")
        delay(100)
        assertEquals(0, h.questions())

        h.send(arrived(2), driving(1))   // 미입력 운행
        h.destination("본사")
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(TripPurpose.COMMUTE, h.gateway.calls.single().purpose)
        h.coordinator.stop()
    }

    @Test
    fun `주행 중 같은 운행에서 목적지를 바꿔도 다시 묻지 않는다`() = runBlocking {
        // 저장을 실패시켜 운행이 계속 PENDING으로 남게 한다 → promptedTripIds만으로 막혀야 한다
        val h = Harness(
            this,
            input = FakeSpeechInput(listOf(recognized("회의"))),
            gateway = FakeGateway(outcomes = mutableListOf(ConfirmOutcome.Failed("x"))),
        )
        h.send(driving(1))
        h.destination("A")
        h.awaitState { it is PurposePromptState.SaveFailed }
        h.destination("B")
        delay(100)
        assertEquals(1, h.questions())
        h.coordinator.stop()
    }

    @Test
    fun `출발해도 묻던 질문을 멈추지 않는다`() = runBlocking {
        val h = Harness(
            this,
            input = FakeSpeechInput(partials = listOf("거")),
            config = PurposePromptCoordinator.Config(listenTimeoutMillis = 10_000),
        )
        h.send(TripState.Idle)
        h.destination("판교")
        h.awaitState { it is PurposePromptState.Listening }
        h.send(driving(3))
        assertTrue(h.coordinator.state.value is PurposePromptState.Listening)
        h.coordinator.stop()
    }

    // ── 거래처 제안 ─────────────────────────────────────

    @Test
    fun `등록 거래처면 먼저 제안하고, 네라고 하면 거래처 방문으로 저장한다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(listOf(recognized("네"))), gateway = FakeGateway(clients = listOf("현대오토에버 판교")))
        h.send(TripState.Idle)
        h.destination("현대오토에버 판교캠퍼스")

        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(listOf("현대오토에버 판교 방문으로 기록할까요?", "거래처 방문으로 기록할게요."), h.output.spoken)
        assertEquals(
            FakeGateway.Call(null, TripPurpose.CLIENT_VISIT, InputMethod.VOICE, "네"),
            h.gateway.calls.single(),
        )
        h.coordinator.stop()
    }

    @Test
    fun `아니요라고 하면 일반 질문으로 넘어간다`() = runBlocking {
        val h = Harness(
            this,
            input = FakeSpeechInput(listOf(recognized("아니요"), recognized("교육"))),
            gateway = FakeGateway(clients = listOf("기아 본사")),
        )
        h.send(TripState.Idle)
        h.destination("기아 본사")

        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(listOf("기아 본사 방문으로 기록할까요?", VoicePrompts.QUESTION, "교육으로 기록할게요."), h.output.spoken)
        assertEquals(TripPurpose.TRAINING, h.gateway.calls.single().purpose)
        h.coordinator.stop()
    }

    @Test
    fun `아니요와 함께 목적을 말하면 바로 그 목적으로 저장한다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(listOf(recognized("아니요 회의예요"))), gateway = FakeGateway(clients = listOf("기아 본사")))
        h.send(TripState.Idle)
        h.destination("기아 본사")
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(TripPurpose.MEETING, h.gateway.calls.single().purpose)
        assertEquals(0, h.questions())
        h.coordinator.stop()
    }

    // ── 이전 방문 목적 제안 ─────────────────────────────

    @Test
    fun `같은 목적지로 간 적이 있으면 지난번 목적을 제안하고, 네라고 하면 그 목적으로 저장한다`() = runBlocking {
        val h = Harness(
            this,
            input = FakeSpeechInput(listOf(recognized("응"))),
            gateway = FakeGateway(previous = mapOf("본사 대회의실" to TripPurpose.MEETING)),
        )
        h.send(TripState.Idle)
        h.destination("본사 대회의실")

        // (제안 상태는 가짜 음성이 바로 답해서 순식간에 지나가므로, 결과와 말한 문장으로 확인한다)
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(listOf("지난번처럼 회의로 기록할까요?", "회의로 기록할게요."), h.output.spoken)
        assertEquals(FakeGateway.Call(null, TripPurpose.MEETING, InputMethod.VOICE, "응"), h.gateway.calls.single())
        assertEquals(0, h.questions())
        h.coordinator.stop()
    }

    @Test
    fun `지난번 목적이 있으면 거래처 제안보다 먼저 한다`() = runBlocking {
        val h = Harness(
            this,
            input = FakeSpeechInput(listOf(recognized("네"))),
            gateway = FakeGateway(clients = listOf("기아 본사"), previous = mapOf("기아 본사" to TripPurpose.PROMOTION)),
        )
        h.send(TripState.Idle)
        h.destination("기아 본사")
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals("지난번처럼 판촉으로 기록할까요?", h.output.spoken.first())
        assertEquals(TripPurpose.PROMOTION, h.gateway.calls.single().purpose)
        h.coordinator.stop()
    }

    @Test
    fun `지난번 목적 제안에 아니요라고 하면 일반 질문으로 넘어간다`() = runBlocking {
        val h = Harness(
            this,
            input = FakeSpeechInput(listOf(recognized("아니"), recognized("교육"))),
            gateway = FakeGateway(previous = mapOf("연수원" to TripPurpose.MEETING)),
        )
        h.send(TripState.Idle)
        h.destination("연수원")
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(listOf("지난번처럼 회의로 기록할까요?", VoicePrompts.QUESTION, "교육으로 기록할게요."), h.output.spoken)
        assertEquals(TripPurpose.TRAINING, h.gateway.calls.single().purpose)
        h.coordinator.stop()
    }

    @Test
    fun `제안에 답이 없으면 자동으로 확정하지 않고 일반 질문으로 넘어간다`() = runBlocking {
        val h = Harness(
            this,
            input = FakeSpeechInput(listOf(null, recognized("판촉"))),   // 첫 듣기는 시간 초과
            gateway = FakeGateway(clients = listOf("기아 본사")),
        )
        h.send(TripState.Idle)
        h.destination("기아 본사")
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(VoicePrompts.QUESTION, h.output.spoken[1])
        assertEquals(TripPurpose.PROMOTION, h.gateway.calls.single().purpose)
        h.coordinator.stop()
    }

    // ── 조용한 모드 ─────────────────────────────────────

    @Test
    fun `조용한 모드면 소리 없이 바로 버튼 화면을 띄우고, 고르면 보관 목적으로 저장한다`() = runBlocking {
        val h = Harness(this, quiet = true)
        h.send(TripState.Idle)
        h.destination("본사 대회의실")

        assertEquals(
            PurposePromptState.AwaitingButton(ButtonReason.QUIET_MODE, emptySet()),
            h.awaitState { it is PurposePromptState.AwaitingButton },
        )
        h.coordinator.selectPurpose(TripPurpose.MEETING)
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(FakeGateway.Call(null, TripPurpose.MEETING, InputMethod.BUTTON, null), h.gateway.calls.single())
        assertTrue("말하지 않는다", h.output.spoken.isEmpty())
        assertEquals("듣지 않는다", 0, h.input.listenCount)
        h.coordinator.stop()
    }

    @Test
    fun `조용한 모드에서도 지난번 목적·거래처 방문을 버튼 앞에 둔다`() = runBlocking {
        val h = Harness(
            this,
            gateway = FakeGateway(clients = listOf("기아 본사"), previous = mapOf("연수원" to TripPurpose.TRAINING)),
            quiet = true,
        )
        h.send(TripState.Idle)
        h.destination("연수원")
        assertEquals(setOf(TripPurpose.TRAINING), (h.awaitState { it is PurposePromptState.AwaitingButton } as PurposePromptState.AwaitingButton).candidates)

        h.destination("기아 본사 2층")
        assertEquals(
            PurposePromptState.AwaitingButton(ButtonReason.QUIET_MODE, setOf(TripPurpose.CLIENT_VISIT)),
            h.awaitState { it is PurposePromptState.AwaitingButton && it.candidates == setOf(TripPurpose.CLIENT_VISIT) },
        )
        assertTrue(h.output.spoken.isEmpty())
        h.coordinator.stop()
    }

    @Test
    fun `조용한 모드에서 고르지 않고 출발하면 화면을 닫고 목적 미입력으로 남긴다`() = runBlocking {
        val h = Harness(this, gateway = FakeGateway(pendingTripIds = setOf(1L)), quiet = true)
        h.send(TripState.Idle)
        h.destination("판교")
        h.awaitState { it is PurposePromptState.AwaitingButton }

        h.send(driving(1))
        assertEquals(PurposePromptState.Idle, h.awaitState { it == PurposePromptState.Idle })
        h.destination("다른 곳")   // 주행 중 목적지 변경
        delay(50)
        assertEquals(PurposePromptState.Idle, h.coordinator.state.value)
        assertTrue(h.gateway.calls.isEmpty())
        h.coordinator.stop()
    }

    @Test
    fun `조용한 모드면 주행 중 처음 목적지를 찍어도 묻지 않는다`() = runBlocking {
        val h = Harness(this, gateway = FakeGateway(pendingTripIds = setOf(1L)), quiet = true)
        h.send(driving(1))
        h.destination("판교")
        delay(50)
        assertEquals(PurposePromptState.Idle, h.coordinator.state.value)
        assertTrue(h.output.spoken.isEmpty())
        h.coordinator.stop()
    }

    @Test
    fun `조용한 모드면 도착 안내를 화면에만 보여 준다`() = runBlocking {
        val h = Harness(this, gateway = FakeGateway(confirmed = mapOf(1L to TripPurpose.CLIENT_VISIT)), quiet = true)
        h.send(driving(1), arrived(1))
        assertEquals(
            PurposePromptState.ArrivalNotice(TripPurpose.CLIENT_VISIT),
            h.awaitState { it is PurposePromptState.ArrivalNotice },
        )
        delay(50)
        assertTrue(h.output.spoken.isEmpty())
        h.coordinator.stop()
    }

    @Test
    fun `조용한 모드를 끄면 다음 질문부터 다시 음성으로 묻는다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(listOf(recognized("교육"))), quiet = true)
        h.send(TripState.Idle)
        h.destination("판교")
        h.awaitState { it is PurposePromptState.AwaitingButton }
        h.coordinator.dismiss()

        h.quiet = false
        h.destination("연수원")
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(listOf(VoicePrompts.QUESTION, "교육으로 기록할게요."), h.output.spoken)
        h.coordinator.stop()
    }

    @Test
    fun `조용한 모드 설정은 바뀔 때만 저장한다`() {
        val saved = mutableListOf<Boolean>()
        val setting = QuietModeSetting(initial = false) { saved += it }
        setting.set(false)
        setting.set(true)
        setting.set(true)
        assertTrue(setting.enabled.value)
        assertEquals(listOf(true), saved)
    }

    // ── 도착 안내 ───────────────────────────────────────

    @Test
    fun `도착하면 확정된 목적을 안내만 한다`() = runBlocking {
        val h = Harness(this, gateway = FakeGateway(confirmed = mapOf(1L to TripPurpose.CLIENT_VISIT)))
        h.send(driving(1), arrived(1))
        assertEquals(
            PurposePromptState.ArrivalNotice(TripPurpose.CLIENT_VISIT),
            h.awaitState { it is PurposePromptState.ArrivalNotice },
        )
        delay(50)
        assertEquals(listOf("거래처 방문으로 기록했어요."), h.output.spoken)
        assertTrue(h.gateway.calls.isEmpty())
        h.coordinator.stop()
    }

    @Test
    fun `목적 미입력 운행이나 재시작 후 복원된 도착은 안내하지 않는다`() = runBlocking {
        val h = Harness(this, gateway = FakeGateway(confirmed = mapOf(2L to TripPurpose.MEETING)))
        h.send(arrived(2))               // 구독 직후 첫 값
        h.send(driving(1), arrived(1))   // 미입력
        delay(100)
        assertTrue(h.output.spoken.isEmpty())
        assertEquals(PurposePromptState.Idle, h.coordinator.state.value)
        h.coordinator.stop()
    }

    // ── 버튼 선택 ───────────────────────────────────────

    @Test
    fun `두 번 다 못 알아들으면 버튼으로 넘기고, 정차 중 고르면 보관 목적으로 저장한다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(listOf(recognized("음"), ListenResult.NoSpeech)))
        h.send(TripState.Idle)
        h.destination("판교")

        assertEquals(
            PurposePromptState.AwaitingButton(ButtonReason.NOT_UNDERSTOOD, emptySet()),
            h.awaitState { it is PurposePromptState.AwaitingButton },
        )
        assertEquals(VoicePrompts.FALLBACK_TO_BUTTON, h.output.spoken.last())
        h.coordinator.selectPurpose(TripPurpose.MEETING)
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(FakeGateway.Call(null, TripPurpose.MEETING, InputMethod.BUTTON, null), h.gateway.calls.single())
        h.coordinator.stop()
    }

    @Test
    fun `도착한 뒤에 버튼을 고르면 그 운행에 직접 확정한다`() = runBlocking {
        val h = Harness(
            this,
            input = FakeSpeechInput(listOf(recognized("음"), ListenResult.NoSpeech)),
            gateway = FakeGateway(pendingTripIds = setOf(7L)),
        )
        h.send(TripState.Idle)
        h.destination("판교")
        h.awaitState { it is PurposePromptState.AwaitingButton }

        h.send(driving(7), arrived(7))
        assertTrue("미입력이라 도착 안내 없이 버튼 화면 유지", h.coordinator.state.value is PurposePromptState.AwaitingButton)
        h.coordinator.selectPurpose(TripPurpose.SITE_VISIT)
        h.awaitState { it is PurposePromptState.Completed }
        assertEquals(FakeGateway.Call(7, TripPurpose.SITE_VISIT, InputMethod.BUTTON, null), h.gateway.calls.single())
        h.coordinator.stop()
    }

    @Test
    fun `두 목적이 섞이면 다시 묻고, 그래도 섞이면 후보를 버튼으로 준다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(listOf(recognized("거래처 미팅"), recognized("거래처 회의"))))
        h.send(TripState.Idle)
        h.destination("판교")
        val state = h.awaitState { it is PurposePromptState.AwaitingButton } as PurposePromptState.AwaitingButton
        assertEquals(ButtonReason.AMBIGUOUS, state.reason)
        assertEquals(setOf(TripPurpose.CLIENT_VISIT, TripPurpose.MEETING), state.candidates)
        assertEquals(VoicePrompts.RETRY_AMBIGUOUS, h.output.spoken[1])
        h.coordinator.stop()
    }

    @Test
    fun `TTS를 쓸 수 없으면 묻지 않고 바로 버튼으로 넘긴다`() = runBlocking {
        val h = Harness(this, output = FakeSpeechOutput(prepareError = SpeechError.LanguageUnavailable))
        h.send(TripState.Idle)
        h.destination("판교")
        val state = h.awaitState { it is PurposePromptState.AwaitingButton } as PurposePromptState.AwaitingButton
        assertEquals(ButtonReason.VOICE_UNAVAILABLE, state.reason)
        assertEquals(0, h.input.listenCount)
        h.coordinator.stop()
    }

    @Test
    fun `마이크 오류는 다시 묻지 않고 바로 버튼으로 넘긴다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(listOf(ListenResult.Failed(SpeechError.Microphone))))
        h.send(TripState.Idle)
        h.destination("판교")
        val state = h.awaitState { it is PurposePromptState.AwaitingButton } as PurposePromptState.AwaitingButton
        assertEquals(ButtonReason.VOICE_UNAVAILABLE, state.reason)
        assertEquals(1, h.input.listenCount)
        h.coordinator.stop()
    }

    @Test
    fun `중간 인식 결과를 듣는 중 상태에 보여준다`() = runBlocking {
        val h = Harness(this, input = FakeSpeechInput(partials = listOf("거래")))
        h.send(TripState.Idle)
        h.destination("판교")
        val listening = h.awaitState { it is PurposePromptState.Listening && it.partialText.isNotEmpty() }
        assertEquals("거래", (listening as PurposePromptState.Listening).partialText)
        h.coordinator.stop()
    }

    @Test
    fun `엔진이 준비되기 전에는 준비 중, 준비되면 듣는 중으로 표시한다`() = runBlocking {
        // 준비 알림이 오지 않는 엔진: 계속 준비 중으로 남는다 (이때 말하면 놓칠 수 있으므로 "듣고 있어요"를 띄우지 않는다)
        val notReady = FakeSpeechInput().apply { sendReady = false }
        val h1 = Harness(this, input = notReady)
        h1.send(TripState.Idle)
        h1.destination("판교")
        val waiting = h1.awaitState { it is PurposePromptState.Listening } as PurposePromptState.Listening
        delay(50)
        assertEquals(false, waiting.ready)
        assertEquals(false, (h1.coordinator.state.value as PurposePromptState.Listening).ready)
        h1.coordinator.stop()

        // 준비 알림이 오는 엔진: 듣는 중으로 바뀐다
        val h2 = Harness(this, input = FakeSpeechInput())
        h2.send(TripState.Idle)
        h2.destination("판교")
        h2.awaitState { it is PurposePromptState.Listening && it.ready }
        h2.coordinator.stop()
    }

    // ── 저장 결과 ───────────────────────────────────────

    @Test
    fun `저장에 실패하면 실패 상태가 되고 버튼으로 다시 저장할 수 있다`() = runBlocking {
        val h = Harness(
            this,
            input = FakeSpeechInput(listOf(recognized("출근"))),
            gateway = FakeGateway(outcomes = mutableListOf(ConfirmOutcome.Failed("저장 실패"))),
        )
        h.send(TripState.Idle)
        h.destination("본사")
        h.awaitState { it is PurposePromptState.SaveFailed }
        h.coordinator.selectPurpose(TripPurpose.COMMUTE)
        assertEquals(
            PurposePromptState.Completed(TripPurpose.COMMUTE, InputMethod.BUTTON),
            h.awaitState { it is PurposePromptState.Completed },
        )
        h.coordinator.stop()
    }

    @Test
    fun `이미 확정됐으면 조용히 끝낸다`() = runBlocking {
        val h = Harness(
            this,
            input = FakeSpeechInput(listOf(recognized("교육"))),
            gateway = FakeGateway(outcomes = mutableListOf(ConfirmOutcome.AlreadyConfirmed)),
        )
        h.send(TripState.Idle)
        h.destination("연수원")
        delay(150)
        assertEquals(1, h.gateway.calls.size)
        assertEquals(PurposePromptState.Idle, h.coordinator.state.value)
        h.coordinator.stop()
    }
}
