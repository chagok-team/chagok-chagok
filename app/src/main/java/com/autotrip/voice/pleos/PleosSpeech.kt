package com.autotrip.voice.pleos

import android.content.Context
import android.util.Log
import com.autotrip.voice.STT_LOG_TAG
import com.autotrip.voice.maskForLog
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import com.autotrip.voice.ListenResult
import com.autotrip.voice.SpeakResult
import com.autotrip.voice.SpeechError
import com.autotrip.voice.SpeechInput
import com.autotrip.voice.SpeechOutput
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/*
 * PLEOS TextToSpeech / SpeechToText 3.3.1 구현.
 * 시그니처 근거: 공식 API Reference + 실제 AAR(javap)로 대조함.
 * - 앱 등록이 필요 없는 Mode.ON_DEVICE, 한국어 Locale.KOREA(ko-KR)
 * - initialize ↔ release, addListener ↔ removeListener를 짝지어 호출한다.
 */

/** PLEOS TTS. [speak]는 onDone / onStop / onError 중 하나가 올 때까지 기다린다. */
class PleosSpeechOutput(context: Context) : SpeechOutput {
    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    @Volatile private var prepared = false
    @Volatile private var pending: CompletableDeferred<SpeakResult>? = null

    // 에뮬레이터 확인(2026-11-12): 콜백은 메인이 아닌 binder 스레드에서 온다. 순서는 onReady → onStart → onDone.
    // 같은 콜백이 두 번 올 수 있으므로(SDK 로그상 onDone 2회) CompletableDeferred.complete의 1회 보장에 기댄다.
    private val listener = object : EventListener {
        override fun onReady() {}
        override fun onStart() {}
        override fun onDone() { pending?.complete(SpeakResult.Done) }
        override fun onStop() { pending?.complete(SpeakResult.Stopped) }
        override fun onError(errorType: TtsErrorType) { pending?.complete(SpeakResult.Failed(errorType.toSpeechError())) }
        override fun onUpdatedRms(rms: Double) {}
    }

    override suspend fun prepare(): SpeechError? {
        if (prepared) return null
        val error = suspendCancellableCoroutine<SpeechError?> { cont ->
            tts.initialize(object : TtsInitListener {
                override fun onSuccess() = cont.resumeOnce(null)
                override fun onFailure(errorType: TtsErrorType) = cont.resumeOnce(errorType.toSpeechError())
            })
        }
        if (error == null) {
            tts.addEventListener(listener)
            tts.setLocale(Locale.KOREA)
            prepared = true
        }
        return error
    }

    override suspend fun speak(text: String): SpeakResult {
        if (!prepared) return SpeakResult.Failed(SpeechError.Engine("NOT_PREPARED"))
        val result = CompletableDeferred<SpeakResult>()
        pending = result
        return try {
            tts.speak(text, true)
            result.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            tts.stop()
            throw e
        } finally {
            if (pending === result) pending = null
        }
    }

    override fun stop() = tts.stop()

    override fun release() {
        if (prepared) tts.removeEventListener(listener)
        prepared = false
        tts.release()
    }
}

/**
 * PLEOS STT. [listen]은 request()로 마이크를 열고 최종 결과가 나오면 끝난다.
 * - onUpdated(text, completed = false): 중간 결과 → onPartial
 * - onUpdated(text, completed = true): 최종 결과 → Recognized (빈 문자열이면 NoSpeech)
 * - onEndedRecognition: 최종 결과 없이 끝나면 마지막 중간 결과 또는 NoSpeech
 */
class PleosSpeechInput(context: Context) : SpeechInput {
    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    @Volatile private var prepared = false
    @Volatile private var pending: CompletableDeferred<ListenResult>? = null
    @Volatile private var onPartial: (String) -> Unit = {}
    @Volatile private var onReadyCallback: () -> Unit = {}
    @Volatile private var lastText: String = ""

    // 에뮬레이터 확인(2026-11-12): 초기화·마이크 열림·온디바이스 인식 동작, 콜백은 binder 스레드.
    // 실제 발화로 확인(2026-11-14): onReady → onStartedRecognition → onUpdated(중간) → onEndedRecognition
    //   → onUpdated(completed=true) 순서. 먼저 오는 쪽으로 끝내고(CompletableDeferred 1회 보장) 둘 다 처리한다.
    // ── 진단 (AutoTripSTT 로그) ──
    // 마이크 입력 크기: 듣는 동안 RMS 최대값을 모아 끝날 때 한 줄로 남긴다 (매번 찍으면 로그가 넘친다).
    // 최대값이 거의 0이면 마이크로 소리가 들어오지 않는 것(에뮬레이터 호스트 마이크 꺼짐 등)이다.
    @Volatile private var maxRms = Double.NEGATIVE_INFINITY
    @Volatile private var rmsCount = 0
    @Volatile private var listenStartedAt = 0L

    private fun elapsed() = "+${System.currentTimeMillis() - listenStartedAt}ms"

    private val listener = object : ResultListener {
        override fun onReady() {
            Log.d(STT_LOG_TAG, "onReady ${elapsed()}")
            onReadyCallback()
        }
        override fun onStartedRecognition() { Log.d(STT_LOG_TAG, "onStartedRecognition ${elapsed()}") }
        override fun onUpdated(stt: String, completed: Boolean) {
            Log.d(STT_LOG_TAG, "onUpdated text=${maskForLog(stt)} completed=$completed ${elapsed()}")
            lastText = stt
            if (completed) {
                pending?.complete(if (stt.isBlank()) ListenResult.NoSpeech else ListenResult.Recognized(stt.trim()))
            } else {
                onPartial(stt)
            }
        }
        override fun onEndedRecognition() {
            val text = lastText
            Log.d(STT_LOG_TAG, "onEndedRecognition lastText=${maskForLog(text)} ${elapsed()} ${rmsSummary()}")
            pending?.complete(if (text.isBlank()) ListenResult.NoSpeech else ListenResult.Recognized(text.trim()))
        }
        override fun onError(errorType: SttErrorType) {
            Log.w(STT_LOG_TAG, "onError ${errorType.name} ${elapsed()} ${rmsSummary()}")
            pending?.complete(ListenResult.Failed(errorType.toSpeechError()))
        }
        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onUpdatedRms(rms: Double) {
            rmsCount++
            if (rms > maxRms) maxRms = rms
        }
    }

    private fun rmsSummary() = if (rmsCount == 0) "rms=(콜백 없음)" else "rmsMax=${"%.2f".format(maxRms)} rmsCount=$rmsCount"

    override suspend fun prepare(): SpeechError? {
        if (prepared) return null
        val error = suspendCancellableCoroutine<SpeechError?> { cont ->
            stt.initialize(object : SttInitListener {
                override fun onSuccess() = cont.resumeOnce(null)
                override fun onFailure(errorType: SttErrorType) {
                    Log.w(STT_LOG_TAG, "initialize onFailure ${errorType.name}")
                    cont.resumeOnce(errorType.toSpeechError())
                }
            })
        }
        if (error == null) {
            stt.addListener(listener)
            stt.setLocale(Locale.KOREA)
            prepared = true
            Log.d(STT_LOG_TAG, "prepare 성공 (ON_DEVICE, ko-KR)")
        } else {
            Log.w(STT_LOG_TAG, "prepare 실패 error=$error")
        }
        return error
    }

    override suspend fun listen(onPartial: (String) -> Unit, onReady: () -> Unit): ListenResult {
        if (!prepared) {
            Log.w(STT_LOG_TAG, "listen: 준비 안 됨 (NOT_PREPARED)")
            return ListenResult.Failed(SpeechError.Engine("NOT_PREPARED"))
        }
        val result = CompletableDeferred<ListenResult>()
        this.onPartial = onPartial
        // onReady는 이번 듣기에서 한 번만 (SDK가 같은 콜백을 두 번 보낸 적이 있음)
        var readySent = false
        this.onReadyCallback = { if (!readySent) { readySent = true; onReady() } }
        lastText = ""
        maxRms = Double.NEGATIVE_INFINITY
        rmsCount = 0
        pending = result
        return try {
            listenStartedAt = System.currentTimeMillis()
            Log.d(STT_LOG_TAG, "request() 호출")
            stt.request()
            result.await().also { Log.d(STT_LOG_TAG, "listen 결과=${it.describe()} ${elapsed()} ${rmsSummary()}") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(STT_LOG_TAG, "listen 취소 ${elapsed()} lastText=${maskForLog(lastText)} ${rmsSummary()}")
            stt.stop()
            throw e
        } finally {
            if (pending === result) pending = null
            this.onPartial = {}
            this.onReadyCallback = {}
        }
    }

    override fun stop() {
        Log.d(STT_LOG_TAG, "stop() ${elapsed()}")
        stt.stop()
        pending?.complete(ListenResult.Stopped)
    }

    override fun release() {
        if (prepared) stt.removeListener(listener)
        prepared = false
        stt.release()
    }
}

// ── 오류 변환 (SpeechError KDoc의 대응 표) ──────────────────

internal fun TtsErrorType.toSpeechError(): SpeechError = errorNameToSpeechError(name)
internal fun SttErrorType.toSpeechError(): SpeechError = errorNameToSpeechError(name)

internal fun errorNameToSpeechError(name: String): SpeechError = when (name) {
    "PERMISSION_DENIED", "UNAUTHENTICATED" -> SpeechError.PermissionDenied
    "UNAVAILABLE_LANGUAGE" -> SpeechError.LanguageUnavailable
    "MIC_ERROR" -> SpeechError.Microphone
    "AUDIO_ERROR" -> SpeechError.AudioOutput
    "NETWORK_ERROR", "SERVER_INTERNAL_ERROR" -> SpeechError.Network
    "TIMEOUT" -> SpeechError.Timeout
    else -> SpeechError.Engine(name)
}

/** 로그용: 인식 원문은 가려서 */
private fun ListenResult.describe(): String = when (this) {
    is ListenResult.Recognized -> "Recognized(${maskForLog(text)})"
    is ListenResult.Failed -> "Failed($error)"
    else -> toString()
}

/** 초기화 콜백이 두 번 오더라도 한 번만 재개한다 */
private fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
    if (isActive) resume(value)
}
